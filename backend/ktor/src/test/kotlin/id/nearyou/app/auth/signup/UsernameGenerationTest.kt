package id.nearyou.app.auth.signup

import id.nearyou.app.auth.session.InMemoryUsers
import id.nearyou.app.infra.repo.ReservedUsernameRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import java.sql.Connection
import java.time.LocalDate
import java.util.SplittableRandom
import java.util.UUID

private class InMemoryReserved(
    private val set: MutableSet<String> = mutableSetOf(),
) : ReservedUsernameRepository {
    fun add(vararg handles: String) {
        set.addAll(handles.map { it.lowercase() })
    }

    override fun exists(lowercaseUsername: String): Boolean = lowercaseUsername in set

    override fun exists(
        conn: Connection,
        lowercaseUsername: String,
    ): Boolean = lowercaseUsername in set
}

private class ToggleHistory(
    private val holdSet: MutableSet<String> = mutableSetOf(),
) : UsernameHistoryRepository {
    fun holds(vararg handles: String) {
        holdSet.addAll(handles.map { it.lowercase() })
    }

    override fun existsOnHold(
        conn: Connection,
        lowercaseUsername: String,
    ): Boolean = lowercaseUsername in holdSet
}

private fun words(
    adj: List<String> = listOf("ramah", "gesit"),
    nouns: List<String> = listOf("harimau", "garuda"),
    mods: List<String> = listOf("jaya"),
) = WordPairResource(adj, nouns, mods)

private fun baseRow(id: UUID = UUID.randomUUID()) =
    UsernameGenerator.BaseRow(
        id = id,
        dateOfBirth = LocalDate.of(1995, 3, 14),
        googleIdHash = "g_$id",
        appleIdHash = null,
        inviteCodePrefix = "ABCDEFGH",
        deviceFingerprintHash = null,
    )

/**
 * Stub [Connection] used in generator tests. In-memory fakes ignore the conn argument;
 * the dynamic proxy is just a type-safe way to satisfy the signature without bringing
 * in MockK. Any accidental method call surfaces via [UnsupportedOperationException].
 */
private val NOOP_CONN: Connection =
    java.lang.reflect.Proxy.newProxyInstance(
        Connection::class.java.classLoader,
        arrayOf<Class<*>>(Connection::class.java),
    ) { _, method, _ -> throw UnsupportedOperationException("stub conn does not implement ${method.name}") } as Connection

class UsernameGenerationTest : StringSpec({

    "attempt 1 lands on happy path" {
        val users = InMemoryUsers()
        val gen =
            UsernameGenerator(
                words = words(),
                reserved = InMemoryReserved(),
                history = ToggleHistory(),
                users = users,
                rng = SplittableRandom(1L),
            )
        val result = gen.generateAndInsert(NOOP_CONN, baseRow())
        result.username.shouldMatch(Regex("^[a-z0-9]+_[a-z0-9]+(_[a-z0-9]+)?$"))
        users.rows.values.map { it.username }.shouldContainAll(listOf(result.username))
    }

    "reserved-word hit is skipped" {
        // Force a small vocab so the seeded RNG lands on a predictable first candidate.
        val w = words(adj = listOf("ramah"), nouns = listOf("harimau"), mods = listOf("jaya"))
        val reserved = InMemoryReserved().apply { add("ramah_harimau") }
        val users = InMemoryUsers()
        val gen =
            UsernameGenerator(
                words = w,
                reserved = reserved,
                history = ToggleHistory(),
                users = users,
                rng = SplittableRandom(42L),
            )
        val result = gen.generateAndInsert(NOOP_CONN, baseRow())
        // Attempt 1 is reserved → attempt 3 template kicks in (or fallback), so the result
        // either has a modifier or a 5-digit suffix. At minimum it's longer than ramah_harimau.
        (result.username.length > "ramah_harimau".length) shouldBe true
    }

    "release-hold hit is skipped" {
        val w = words(adj = listOf("ramah"), nouns = listOf("harimau"), mods = listOf("jaya"))
        val history = ToggleHistory().apply { holds("ramah_harimau") }
        val users = InMemoryUsers()
        val gen =
            UsernameGenerator(
                words = w,
                reserved = InMemoryReserved(),
                history = history,
                users = users,
                rng = SplittableRandom(7L),
            )
        val result = gen.generateAndInsert(NOOP_CONN, baseRow())
        (result.username != "ramah_harimau") shouldBe true
    }

    "UNIQUE collision retries then falls back" {
        val w = words(adj = listOf("ramah"), nouns = listOf("harimau"), mods = listOf("jaya"))
        // Pre-seed ramah_harimau so attempt 1/2 collide; ramah_harimau_jaya so 3/4 collide.
        val users = InMemoryUsers()
        val existingId = UUID.randomUUID()
        users.rows[existingId] =
            id.nearyou.app.infra.repo.UserRow(
                id = existingId, username = "ramah_harimau", displayName = "x",
                email = null, googleIdHash = "preexisting", appleIdHash = null,
                appleRelayEmail = false, isShadowBanned = false, isBanned = false,
                suspendedUntil = null, tokenVersion = 0, deletedAt = null,
            )
        val id2 = UUID.randomUUID()
        users.rows[id2] = users.rows[existingId]!!.copy(id = id2, username = "ramah_harimau_jaya", googleIdHash = "p2")
        val gen =
            UsernameGenerator(
                words = w,
                reserved = InMemoryReserved(),
                history = ToggleHistory(),
                users = users,
                rng = SplittableRandom(99L),
            )
        val result = gen.generateAndInsert(NOOP_CONN, baseRow())
        // attempt 5 adds a 5-digit suffix, which cannot collide with the two pre-seeded rows.
        result.username.shouldMatch(Regex("^ramah_harimau(_jaya|_\\d{5}|_[0-9a-f]{8})$"))
    }

    "all 5 collide, fallback lands" {
        val w = words(adj = listOf("ramah"), nouns = listOf("harimau"), mods = listOf("jaya"))
        // Pre-seed enough rows to block templates 1..5 by claiming the only candidates they can produce.
        val reserved =
            InMemoryReserved().apply {
                add("ramah_harimau", "ramah_harimau_jaya")
                // 5-digit-suffix attempt is NOT reserved; we let the users table block it via collision.
            }
        val users = InMemoryUsers()
        // Claim every 5-digit suffix 10000..99999 by pre-seeding the username — overkill, so use a
        // "claim-on-match" fake instead by overriding create to always raise 23505 for the 5-digit form.
        val blockingUsers =
            object : id.nearyou.app.infra.repo.UserRepository by users {
                override fun create(
                    conn: Connection,
                    row: id.nearyou.app.infra.repo.NewUserRow,
                ): UUID {
                    if (Regex("^ramah_harimau_\\d{5}$").matches(row.username)) {
                        throw java.sql.SQLException("collide", "23505")
                    }
                    return users.create(conn, row)
                }
            }
        val gen =
            UsernameGenerator(
                words = w,
                reserved = reserved,
                history = ToggleHistory(),
                users = blockingUsers,
                rng = SplittableRandom(123L),
            )
        val result = gen.generateAndInsert(NOOP_CONN, baseRow())
        result.username.shouldMatch(Regex("^ramah_harimau_[0-9a-f]{8}$"))
    }

    "fallback collision surfaces exception" {
        val w = words(adj = listOf("ramah"), nouns = listOf("harimau"), mods = listOf("jaya"))
        val alwaysCollide =
            object : id.nearyou.app.infra.repo.UserRepository by InMemoryUsers() {
                override fun create(
                    conn: Connection,
                    row: id.nearyou.app.infra.repo.NewUserRow,
                ): UUID = throw java.sql.SQLException("collide", "23505")
            }
        val gen =
            UsernameGenerator(
                words = w,
                reserved = InMemoryReserved(),
                history = ToggleHistory(),
                users = alwaysCollide,
                rng = SplittableRandom(5L),
            )
        shouldThrow<UsernameGenerationFailedException> {
            gen.generateAndInsert(NOOP_CONN, baseRow())
        }
    }

    "every emitted candidate fits within 60 chars" {
        val w = words()
        val gen =
            UsernameGenerator(
                words = w,
                reserved = InMemoryReserved(),
                history = ToggleHistory(),
                users = InMemoryUsers(),
                rng = SplittableRandom(321L),
            )
        (1..5).forEach { attempt ->
            gen.sampleCandidateFor(attempt).length shouldBeLessThanOrEqual 60
        }
        gen.sampleFallback().length shouldBeLessThanOrEqual 60
    }

    "seeded RNG produces deterministic output" {
        val w = words()

        fun first(): String =
            UsernameGenerator(
                words = w,
                reserved = InMemoryReserved(),
                history = ToggleHistory(),
                users = InMemoryUsers(),
                rng = SplittableRandom(777L),
            ).sampleCandidateFor(1)
        first() shouldBe first()
    }

    "malformed wordpairs rejected by the resource" {
        shouldThrow<IllegalArgumentException> {
            WordPairResource(adjectives = listOf("ramah", "BAD!"), nouns = listOf("harimau"), modifiers = listOf("jaya"))
        }
    }
})
