package id.nearyou.app.auth.signup

import id.nearyou.app.infra.repo.NewUserRow
import id.nearyou.app.infra.repo.ReservedUsernameRepository
import id.nearyou.app.infra.repo.UserRepository
import java.sql.Connection
import java.sql.SQLException
import java.time.LocalDate
import java.util.SplittableRandom
import java.util.UUID

/** SQLState for a PostgreSQL UNIQUE-violation. */
private const val UNIQUE_VIOLATION_SQLSTATE = "23505"
private const val MAX_USERNAME_LENGTH = 60

class UsernameGenerationFailedException(message: String) : RuntimeException(message)

data class GeneratedUser(val userId: UUID, val username: String)

/**
 * Atomic username generator per
 * `docs/05-Implementation.md § Username Generation & Customization`.
 *
 * Attempts candidates in a fixed template order:
 *  1–2: "{adj}_{noun}"
 *  3–4: "{adj}_{noun}_{modifier}"
 *    5: "{adj}_{noun}_{random_5_digit}"
 *  fb:  "{adj}_{noun}_{uuid8hex}"
 *
 * Each attempt consults `reserved_usernames` + `username_history (released_at > NOW())`
 * as fast-path filters and then lets the `users_username_key` UNIQUE constraint
 * be the authoritative collision check via INSERT-then-catch-23505.
 */
class UsernameGenerator(
    private val words: WordPairResource,
    private val reserved: ReservedUsernameRepository,
    private val history: UsernameHistoryRepository,
    private val users: UserRepository,
    private val rng: SplittableRandom = SplittableRandom(),
) {
    /**
     * Insert a new user row with a generated username inside [conn]'s transaction.
     * Returns the committed `(userId, username)`.
     *
     * Caller must manage the transaction boundary.
     */
    fun generateAndInsert(
        conn: Connection,
        baseRow: BaseRow,
    ): GeneratedUser {
        for (attempt in 1..5) {
            val candidate = sampleCandidateFor(attempt)
            if (reserved.exists(conn, candidate)) continue
            if (history.existsOnHold(conn, candidate)) continue
            if (tryInsert(conn, baseRow, candidate)) {
                return GeneratedUser(baseRow.id, candidate)
            }
        }
        // Fallback: {adj}_{noun}_{uuid8hex} — attempt exactly once.
        val fallback = sampleFallback()
        check(fallback.length <= MAX_USERNAME_LENGTH) {
            "fallback username exceeds 60 chars: $fallback"
        }
        if (tryInsert(conn, baseRow, fallback)) {
            return GeneratedUser(baseRow.id, fallback)
        }
        throw UsernameGenerationFailedException(
            "exhausted 5 template attempts + 1 fallback without a free username",
        )
    }

    /** Testing hook: produce the candidate a given 1-based attempt would try. */
    internal fun sampleCandidateFor(attempt: Int): String {
        val adj = pick(words.adjectives)
        val noun = pick(words.nouns)
        return when (attempt) {
            1, 2 -> assertFit("${adj}_$noun")
            3, 4 -> assertFit("${adj}_${noun}_${pick(words.modifiers)}")
            5 -> assertFit("${adj}_${noun}_${five()}")
            else -> error("attempt $attempt out of range 1..5")
        }
    }

    internal fun sampleFallback(): String {
        val adj = pick(words.adjectives)
        val noun = pick(words.nouns)
        val raw = "${adj}_${noun}_${uuid8()}"
        return if (raw.length <= MAX_USERNAME_LENGTH) {
            raw
        } else {
            // truncate adj/noun deterministically to keep the uuid8 intact (it's the uniqueness bit).
            val uuid = raw.takeLast(8)
            // -2 for the two underscores framing the uuid.
            val room = MAX_USERNAME_LENGTH - uuid.length - 2
            val half = room / 2
            "${adj.take(half)}_${noun.take(room - half)}_$uuid"
        }
    }

    private fun assertFit(candidate: String): String {
        check(candidate.length <= MAX_USERNAME_LENGTH) {
            "username template produced an overlength candidate: $candidate (${candidate.length} chars)"
        }
        return candidate
    }

    private fun tryInsert(
        conn: Connection,
        baseRow: BaseRow,
        username: String,
    ): Boolean {
        val row =
            NewUserRow(
                id = baseRow.id,
                username = username,
                displayName = baseRow.displayNameOverride ?: username.take(50),
                dateOfBirth = baseRow.dateOfBirth,
                googleIdHash = baseRow.googleIdHash,
                appleIdHash = baseRow.appleIdHash,
                inviteCodePrefix = baseRow.inviteCodePrefix,
                deviceFingerprintHash = baseRow.deviceFingerprintHash,
            )
        return try {
            users.create(conn, row)
            true
        } catch (ex: SQLException) {
            if (ex.sqlState == UNIQUE_VIOLATION_SQLSTATE) false else throw ex
        }
    }

    private fun pick(list: List<String>): String = list[rng.nextInt(list.size)]

    private fun five(): String = (10_000 + rng.nextInt(90_000)).toString()

    private fun uuid8(): String = UUID.randomUUID().toString().replace("-", "").take(8)

    /** Inputs shared across every generation attempt for a single signup. */
    data class BaseRow(
        val id: UUID,
        val dateOfBirth: LocalDate,
        val googleIdHash: String?,
        val appleIdHash: String?,
        val inviteCodePrefix: String,
        val deviceFingerprintHash: String?,
        val displayNameOverride: String? = null,
    )
}
