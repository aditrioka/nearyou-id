package id.nearyou.app.auth.signup

import java.sql.Connection

/**
 * Reads + writes against the `username_history` table (the 30-day release hold).
 *
 * The real JDBC implementation ([id.nearyou.app.user.JdbcUsernameHistoryRepository])
 * ships with `premium-username-customization`: the username-change transaction
 * writes a release-hold row, and both signup AND the change path consult
 * [existsOnHold] so a recently-released handle stays unclaimable for 30 days.
 */
interface UsernameHistoryRepository {
    /**
     * Is [lowercaseUsername] an old handle still inside its 30-day release hold
     * (`released_at > NOW()`)? Caller passes the already-lowercased candidate.
     */
    fun existsOnHold(
        conn: Connection,
        lowercaseUsername: String,
    ): Boolean

    /**
     * Record a username change: write [oldUsername] to `username_history` with
     * `released_at = changed_at + INTERVAL '30 days'`. Runs inside the caller's
     * transaction (atomic with the `users.username` write). Returns the computed
     * `released_at` so the caller can put it in the confirmation notification.
     */
    fun insertReleaseHold(
        conn: Connection,
        userId: java.util.UUID,
        oldUsername: String,
        newUsername: String,
    ): java.time.Instant
}

/**
 * No-op binding used only where the release hold is irrelevant (e.g. isolated
 * signup unit tests). [existsOnHold] returns `false` (never skip a candidate);
 * [insertReleaseHold] is unreachable on the signup path (signup never renames).
 */
class NoopUsernameHistoryRepository : UsernameHistoryRepository {
    override fun existsOnHold(
        conn: Connection,
        lowercaseUsername: String,
    ): Boolean = false

    override fun insertReleaseHold(
        conn: Connection,
        userId: java.util.UUID,
        oldUsername: String,
        newUsername: String,
    ): java.time.Instant = java.time.Instant.now()
}
