package id.nearyou.app.user

import id.nearyou.app.core.domain.lint.AllowMissingBlockJoin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.sql.DataSource

/**
 * Single-column write of `users.private_profile_opt_in` for the `private-profile` capability — the
 * sanctioned `user_settings` privacy-flag writer (the Settings "Profil privat" toggle). Mirrors
 * [HideDistanceRepository] with two deliberate differences:
 *
 *  1. `private_profile_opt_in` IS on the privacy-flag-write convention allowlist
 *     (`openspec/project.md` § Coding Conventions), so the `UPDATE` carries the
 *     `@allow-privacy-write: user_settings` marker (the reserved-but-unused Settings-flow entry).
 *  2. The opt-OUT branch ALSO clears `privacy_flip_scheduled_at` in the SAME statement (the
 *     "confirm switch public" grace-clear, `docs/03-UX-Design.md` § Downgrade flow privacy flip) —
 *     required so an opt-out inside the 72h grace window takes effect (the grace short-circuit
 *     `privacy_flip_scheduled_at > now()` would otherwise keep the profile effectively private). The
 *     opt-IN branch touches ONLY `private_profile_opt_in`.
 *
 * Own-content write (the `id` is always the verified JWT `sub` of the caller — the route never
 * accepts a `user_id` body/path param), scoped to a single row. It changes no RLS policy and touches
 * no `visible_*` view, so it is exempt from the shadow-ban / block-exclusion read rules (which match
 * `FROM`/`JOIN`, not `UPDATE`).
 *
 * Effectiveness is enforced at READ (the canonical effective-private formula
 * `private_profile_opt_in AND subscription_status IN ('premium_active','premium_billing_retry')`,
 * plus the grace short-circuit), so storing the bit for a non-Premium caller is harmless and
 * produces no read effect — the `hide-distance` "write-anytime + read-gated" posture.
 *
 * Runs on the pool-bounded JDBC dispatcher (docs/11 §3.2).
 */
class PrivateProfileRepository(
    private val dataSource: DataSource,
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Writes [privateProfile] to the caller's row. The JWT-`sub` caller's row always exists (the
     * `sub` is FK-anchored — `configureUserJwt` validates `sub ∈ public.users` — and the column is
     * `NOT NULL DEFAULT FALSE`), so a 0-row update is unreachable post-auth; the result is therefore
     * not surfaced.
     *
     * Opt-IN (`true`) writes only `private_profile_opt_in`; opt-OUT (`false`) also clears
     * `privacy_flip_scheduled_at` (the grace-clear). Both shapes are own-row (`WHERE id = ?`).
     */
    suspend fun updatePrivateProfile(
        userId: UUID,
        privateProfile: Boolean,
    ) {
        // @allow-privacy-write: user_settings — the reserved Settings-flow writer of the
        // private_profile_opt_in privacy flag (openspec/project.md § Coding Conventions). Own-row
        // (WHERE id = :caller), no user_id param. Opt-OUT also clears privacy_flip_scheduled_at.
        val sql = if (privateProfile) SQL_OPT_IN else SQL_OPT_OUT
        withContext(dbDispatcher) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(sql).use { ps ->
                    ps.setObject(1, userId)
                    ps.executeUpdate()
                }
            }
        }
    }

    /**
     * Reads the caller's stored `private_profile_opt_in` flag (the toggle's current state for the
     * mobile Settings screen). Premium-effectiveness is NOT computed here — the GET handler derives
     * it from the JWT principal's `subscriptionStatus` (no extra read). The JWT-`sub` caller's row
     * always exists.
     */
    suspend fun getPrivateProfile(userId: UUID): Boolean =
        withContext(dbDispatcher) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(SQL_SELECT).use { ps ->
                    ps.setObject(1, userId)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getBoolean("private_profile_opt_in") else false }
                }
            }
        }

    private companion object {
        // @allow-privacy-write: user_settings (opt-IN: touches only the opt-in column).
        const val SQL_OPT_IN =
            "UPDATE users SET private_profile_opt_in = TRUE WHERE id = ?"

        // @allow-privacy-write: user_settings (opt-OUT: also clears the pending privacy flip —
        // the "confirm switch public" grace-clear so the toggle takes effect mid-grace).
        const val SQL_OPT_OUT =
            "UPDATE users SET private_profile_opt_in = FALSE, privacy_flip_scheduled_at = NULL WHERE id = ?"

        @AllowMissingBlockJoin(
            "own-flag read: self read of raw users scoped to id = :caller — a shadow-banned caller " +
                "reads their own settings; no block predicate (you cannot block yourself), no shadow-ban " +
                "filter (own-row read). Precedent: JdbcUserProfileReader.SQL_SELF / HideDistanceRepository.SQL_SELECT.",
        )
        const val SQL_SELECT =
            "SELECT private_profile_opt_in FROM users WHERE id = ?"
    }
}
