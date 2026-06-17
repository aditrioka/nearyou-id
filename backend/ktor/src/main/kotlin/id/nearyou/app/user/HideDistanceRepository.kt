package id.nearyou.app.user

import id.nearyou.app.core.domain.lint.AllowMissingBlockJoin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.sql.DataSource

/**
 * Single-column write of `users.hide_distance_opt_in` for the `hide-distance` capability:
 * `UPDATE users SET hide_distance_opt_in = ? WHERE id = ?`.
 *
 * Own-content write (the `id` is always the verified JWT `sub` of the caller — the route
 * never accepts a `user_id` body/path param), scoped to a single row. It changes no RLS
 * policy and touches no `visible_*` view, so it is exempt from the shadow-ban / block-exclusion
 * read rules (which match `FROM`/`JOIN`, not `UPDATE`). `hide_distance_opt_in` is neither
 * `username` nor `private_profile_opt_in`, so it is on NEITHER the username-write nor the
 * privacy-flag-write convention allowlist — no `@allow-*-write` annotation applies.
 *
 * Effectiveness is enforced at READ (the Nearby read path gates the flag on
 * `subscription_status IN ('premium_active','premium_billing_retry')`), so storing the bit
 * for a non-Premium caller is harmless and produces no distance suppression.
 *
 * Runs on the pool-bounded JDBC dispatcher (docs/11 §3.2).
 */
class HideDistanceRepository(
    private val dataSource: DataSource,
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Writes [hideDistance] to the caller's row. The JWT-`sub` caller's row always exists
     * (the `sub` is FK-anchored — `configureUserJwt` validates `sub ∈ public.users` — and the
     * column is `NOT NULL DEFAULT FALSE`), so a 0-row update is unreachable post-auth; the
     * result is therefore not surfaced.
     */
    suspend fun updateHideDistance(
        userId: UUID,
        hideDistance: Boolean,
    ) {
        withContext(dbDispatcher) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(SQL_UPDATE).use { ps ->
                    ps.setBoolean(1, hideDistance)
                    ps.setObject(2, userId)
                    ps.executeUpdate()
                }
            }
        }
    }

    /**
     * Reads the caller's stored `hide_distance_opt_in` flag (the toggle's current state for the mobile
     * Settings screen). Premium-effectiveness is NOT computed here — the GET handler derives it from the
     * JWT principal's `subscriptionStatus` (no extra read). The JWT-`sub` caller's row always exists.
     */
    suspend fun getHideDistance(userId: UUID): Boolean =
        withContext(dbDispatcher) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(SQL_SELECT).use { ps ->
                    ps.setObject(1, userId)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getBoolean("hide_distance_opt_in") else false }
                }
            }
        }

    private companion object {
        const val SQL_UPDATE =
            "UPDATE users SET hide_distance_opt_in = ? WHERE id = ?"

        @AllowMissingBlockJoin(
            "own-flag read: self read of raw users scoped to id = :caller — a shadow-banned caller " +
                "reads their own settings; no block predicate (you cannot block yourself), no shadow-ban " +
                "filter (own-row read). Precedent: JdbcUserProfileReader.SQL_SELF.",
        )
        const val SQL_SELECT =
            "SELECT hide_distance_opt_in FROM users WHERE id = ?"
    }
}
