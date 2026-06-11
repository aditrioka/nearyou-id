package id.nearyou.app.user

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.sql.DataSource

/**
 * Full-object write of `users.analytics_consent` for the `analytics-consent-update`
 * capability: `UPDATE users SET analytics_consent = ?::jsonb WHERE id = ?`.
 *
 * Own-content write (the `id` is always the verified JWT `sub` of the caller —
 * the route never accepts a `user_id` body/path param), scoped to a single row.
 * It changes no RLS policy and touches no `visible_*` view, so it is exempt from
 * the shadow-ban / block-exclusion read rules (which match `FROM`/`JOIN`, not
 * `UPDATE`). `analytics_consent` is on neither the username-write nor the
 * privacy-flag-write convention allowlist, so no `@allow-*-write` annotation
 * applies.
 *
 * The JSONB object is built from three `Boolean` literals (no injection surface —
 * a `Boolean` renders only as `true`/`false`) and written with an explicit
 * `?::jsonb` cast. The whole object is replaced (no partial merge), since the
 * caller always submits the complete triple.
 *
 * Runs inside `Dispatchers.IO`.
 */
class ConsentRepository(
    private val dataSource: DataSource,
    // Pool-bounded JDBC dispatcher (docs/11 §3.2); production passes DbDispatchers.db.
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Writes the consent triple for [userId] (full-object replace). The JWT-`sub`
     * caller's row always exists — the `sub` is FK-anchored (`configureUserJwt`
     * validates `sub ∈ public.users`) and `analytics_consent` is `NOT NULL DEFAULT`
     * — so a 0-row update is unreachable post-auth; the result is therefore not
     * surfaced.
     */
    suspend fun updateConsent(
        userId: UUID,
        analytics: Boolean,
        crash: Boolean,
        adsPersonalization: Boolean,
    ) {
        withContext(dbDispatcher) {
            val json =
                """{"analytics":$analytics,"crash":$crash,"ads_personalization":$adsPersonalization}"""
            dataSource.connection.use { conn ->
                conn.prepareStatement(SQL_UPDATE).use { ps ->
                    ps.setString(1, json)
                    ps.setObject(2, userId)
                    ps.executeUpdate()
                }
            }
        }
    }

    private companion object {
        const val SQL_UPDATE =
            "UPDATE users SET analytics_consent = ?::jsonb WHERE id = ?"
    }
}
