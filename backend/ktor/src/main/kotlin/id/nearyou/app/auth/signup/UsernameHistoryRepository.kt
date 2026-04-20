package id.nearyou.app.auth.signup

import java.sql.Connection

/**
 * Release-hold lookups against the `username_history` table.
 *
 * This change lands the schema but does NOT write to it; the real
 * implementation comes with the Premium-username-customization change.
 * The signup generator uses this contract to future-proof the 30-day
 * release-hold check; the default [NoopUsernameHistoryRepository] always
 * returns `false` so signup never skips a candidate on that basis.
 */
interface UsernameHistoryRepository {
    fun existsOnHold(
        conn: Connection,
        lowercaseUsername: String,
    ): Boolean
}

class NoopUsernameHistoryRepository : UsernameHistoryRepository {
    override fun existsOnHold(
        conn: Connection,
        lowercaseUsername: String,
    ): Boolean = false
}
