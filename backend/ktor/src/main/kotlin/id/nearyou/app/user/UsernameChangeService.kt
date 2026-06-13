package id.nearyou.app.user

import id.nearyou.app.auth.signup.UsernameHistoryRepository
import id.nearyou.app.config.RemoteConfig
import id.nearyou.app.core.domain.ratelimit.computeTTLToNextReset
import id.nearyou.app.infra.repo.ReservedUsernameRepository
import id.nearyou.app.infra.repo.UserRepository
import id.nearyou.app.moderation.TextModerator
import id.nearyou.app.moderation.Verdict
import id.nearyou.app.notifications.NotificationEmitter
import id.nearyou.data.repository.ModerationQueueRepository
import id.nearyou.data.repository.NotificationType
import id.nearyou.data.repository.ReportTargetType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Premium username customization (docs/02 § Premium Username Customization;
 * docs/05 § Username Generation & Customization). Owns the gate ordering and the
 * race-safe release-hold transaction for `PATCH /api/v1/user/username` and the
 * read-only `GET /api/v1/username/check` probe.
 *
 * **Change gate order** (cheapest/most-decisive first): feature flag → Premium →
 * 30-day cooldown → failed-attempt limiter → format → collision → moderation →
 * `FOR UPDATE` transaction. The 10-failed/hour limiter slot is acquired before
 * format and RELEASED only on success, so only failed attempts accumulate
 * (anti-probing). Premium status is the principal claim (no `FROM users` read).
 * Cooldown is DB-authoritative on `username_last_changed_at` (design D3).
 *
 * Mirrors `SearchService` (gate-ordered `Result`) and `CreatePostService` (the
 * single-transaction `connection.use { autoCommit=false … }` shape on the
 * pool-bounded dispatcher, docs/11 §3.2).
 */
class UsernameChangeService(
    private val dataSource: DataSource,
    private val users: UserRepository,
    private val reserved: ReservedUsernameRepository,
    private val history: UsernameHistoryRepository,
    private val moderationQueue: ModerationQueueRepository,
    private val textModerator: TextModerator,
    private val notificationEmitter: NotificationEmitter,
    private val remoteConfig: RemoteConfig,
    private val rateLimiter: UsernameRateLimiter = UsernameRateLimiter(),
    private val nowProvider: () -> Instant = Instant::now,
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    sealed interface ChangeResult {
        data class Success(val newUsername: String) : ChangeResult

        data object FeatureDisabled : ChangeResult

        data object PremiumRequired : ChangeResult

        data class CooldownActive(val retryAfterSeconds: Long) : ChangeResult

        data object InvalidFormat : ChangeResult

        data object Unavailable : ChangeResult

        data object Moderated : ChangeResult

        data class RateLimited(val retryAfterSeconds: Long) : ChangeResult
    }

    sealed interface CheckResult {
        data class Probed(val available: Boolean) : CheckResult

        data object FeatureDisabled : CheckResult

        data object PremiumRequired : CheckResult

        data object InvalidFormat : CheckResult

        data class RateLimited(val retryAfterSeconds: Long) : CheckResult
    }

    suspend fun changeUsername(
        userId: UUID,
        subscriptionStatus: String,
        rawCandidate: String,
    ): ChangeResult {
        if (!flagEnabled(userId)) return ChangeResult.FeatureDisabled
        if (subscriptionStatus !in PREMIUM_STATES) return ChangeResult.PremiumRequired

        val now = nowProvider()
        // Cooldown — DB-authoritative read (no lock), cheap early gate.
        val lastChanged = withContext(dbDispatcher) { users.findById(userId)?.usernameLastChangedAt }
        cooldownRemaining(lastChanged, now)?.let { return ChangeResult.CooldownActive(it) }

        // Acquire a failed-attempt slot BEFORE format so malformed/probing attempts
        // count toward the 10/hour cap. Released only on success (below).
        when (val rl = withContext(dbDispatcher) { rateLimiter.tryAcquireFailedChange(userId) }) {
            is UsernameRateLimiter.Outcome.RateLimited -> return ChangeResult.RateLimited(rl.retryAfterSeconds)
            is UsernameRateLimiter.Outcome.Allowed -> Unit
        }

        val candidate = rawCandidate.trim()
        if (!isValidFormat(candidate)) return ChangeResult.InvalidFormat
        val lc = candidate.lowercase()

        // Collision pre-check (non-authoritative; the FOR UPDATE block re-checks).
        if (withContext(dbDispatcher) { dataSource.connection.use { collides(it, lc) } }) {
            return ChangeResult.Unavailable
        }

        // Moderation — outside any DB lock (does Redis/Remote-Config I/O). Any hit
        // (profanity Reject OR UU-ITE Flag) rejects upfront + leaves a standing
        // username_flagged queue row (docs/06 § Premium Username Moderation).
        val verdict = withContext(dbDispatcher) { textModerator.moderate(candidate) }
        if (verdict is Verdict.Reject || verdict is Verdict.Flag) {
            withContext(dbDispatcher) {
                dataSource.connection.use { conn ->
                    moderationQueue.upsertUsernameFlaggedRow(conn, ReportTargetType.USER, userId)
                }
            }
            return ChangeResult.Moderated
        }

        // Race-safe commit — FOR UPDATE lock → re-validate → write → notify.
        val result =
            withContext(dbDispatcher) {
                dataSource.connection.use { conn ->
                    conn.autoCommit = false
                    try {
                        val locked = users.findByIdForUpdate(conn, userId)
                        if (locked == null) {
                            conn.rollback()
                            return@use ChangeResult.Unavailable
                        }
                        cooldownRemaining(locked.usernameLastChangedAt, nowProvider())?.let {
                            conn.rollback()
                            return@use ChangeResult.CooldownActive(it)
                        }
                        if (collides(conn, lc)) {
                            conn.rollback()
                            return@use ChangeResult.Unavailable
                        }
                        val oldUsername = locked.username
                        users.updateUsername(conn, userId, candidate)
                        val releasedAt = history.insertReleaseHold(conn, userId, oldUsername, candidate)
                        notificationEmitter.emit(
                            conn = conn,
                            recipientId = userId,
                            // null actor = system-originated; avoids the emitter's
                            // self-action suppression for the user's own confirmation.
                            actorUserId = null,
                            type = NotificationType.USERNAME_RELEASE_SCHEDULED,
                            targetType = null,
                            targetId = null,
                            bodyData =
                                buildJsonObject {
                                    put("old_username", oldUsername)
                                    put("released_at", releasedAt.toString())
                                },
                        )
                        conn.commit()
                        ChangeResult.Success(candidate)
                    } catch (e: SQLException) {
                        conn.rollback()
                        if (e.sqlState == UNIQUE_VIOLATION_SQLSTATE) {
                            ChangeResult.Unavailable
                        } else {
                            throw e
                        }
                    } catch (t: Throwable) {
                        conn.rollback()
                        throw t
                    } finally {
                        conn.autoCommit = true
                    }
                }
            }

        // Only failed attempts count — release the slot acquired above on success.
        if (result is ChangeResult.Success) {
            withContext(dbDispatcher) { rateLimiter.releaseFailedChange(userId) }
            log.info("event=username_changed user={} new_username={}", userId, result.newUsername)
        }
        return result
    }

    suspend fun checkAvailability(
        userId: UUID,
        subscriptionStatus: String,
        rawCandidate: String,
    ): CheckResult {
        if (!flagEnabled(userId)) return CheckResult.FeatureDisabled
        if (subscriptionStatus !in PREMIUM_STATES) return CheckResult.PremiumRequired

        // Format is checked BEFORE the probe limiter: a malformed candidate is a
        // client error, reveals nothing, and must not burn the 3/day enumeration
        // budget (which governs well-formed probes).
        val candidate = rawCandidate.trim()
        if (!isValidFormat(candidate)) return CheckResult.InvalidFormat

        val ttl = computeTTLToNextReset(userId, nowProvider())
        when (val rl = withContext(dbDispatcher) { rateLimiter.tryAcquireProbe(userId, ttl) }) {
            is UsernameRateLimiter.Outcome.RateLimited -> return CheckResult.RateLimited(rl.retryAfterSeconds)
            is UsernameRateLimiter.Outcome.Allowed -> Unit
        }

        val lc = candidate.lowercase()
        val taken = withContext(dbDispatcher) { dataSource.connection.use { collides(it, lc) } }
        return CheckResult.Probed(available = !taken)
    }

    private fun flagEnabled(userId: UUID): Boolean =
        try {
            remoteConfig.getBoolean(FEATURE_FLAG_KEY) ?: true
        } catch (t: Throwable) {
            log.warn(
                "event=remote_config_error key={} fallback=default_true user={} message={}",
                FEATURE_FLAG_KEY,
                userId,
                t.message,
            )
            true
        }

    /** Reserved + active-release-hold + currently-taken collision, all LOWER-matched. */
    private fun collides(
        conn: java.sql.Connection,
        lowercaseCandidate: String,
    ): Boolean =
        reserved.exists(conn, lowercaseCandidate) ||
            history.existsOnHold(conn, lowercaseCandidate) ||
            users.usernameExists(conn, lowercaseCandidate)

    /** Null if outside the 30-day cooldown; else the seconds remaining (≥1). */
    private fun cooldownRemaining(
        lastChanged: Instant?,
        now: Instant,
    ): Long? {
        if (lastChanged == null) return null
        val unlockAt = lastChanged.plus(COOLDOWN)
        if (!now.isBefore(unlockAt)) return null
        return Duration.between(now, unlockAt).seconds.coerceAtLeast(1)
    }

    private fun isValidFormat(candidate: String): Boolean {
        if (candidate.length < MIN_LENGTH || candidate.length > MAX_LENGTH) return false
        if (candidate.contains("..")) return false
        return USERNAME_REGEX.matches(candidate)
    }

    companion object {
        const val FEATURE_FLAG_KEY: String = "premium_username_customization_enabled"
        const val MIN_LENGTH: Int = 3
        const val MAX_LENGTH: Int = 30
        val COOLDOWN: Duration = Duration.ofDays(30)
        private const val UNIQUE_VIOLATION_SQLSTATE = "23505"

        /** Premium states allowed to customize — mirrors `SearchService.PREMIUM_STATES`. */
        internal val PREMIUM_STATES = setOf("premium_active", "premium_billing_retry")

        /** Lowercase alphanumerics; dots and underscores middle-only. No-consecutive-dots is a separate guard. */
        val USERNAME_REGEX = Regex("^[a-z0-9][a-z0-9_.]*[a-z0-9_]$")

        private val log = LoggerFactory.getLogger(UsernameChangeService::class.java)
    }
}
