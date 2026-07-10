package id.nearyou.app.auth.session

import id.nearyou.app.infra.repo.RefreshTokenRepository
import id.nearyou.app.infra.repo.UserRepository
import id.nearyou.app.user.FcmTokenRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.sql.DataSource

/**
 * Logout behavior per the `auth-session` spec (`logout-revocation` change). Interface so the
 * in-memory auth route tests (no DataSource) can substitute a fake; production binds
 * [TransactionalLogoutService].
 */
interface LogoutService {
    /**
     * Single-device logout: revoke the supplied refresh token, and when [fcmToken] is present
     * delete the caller's matching `user_fcm_tokens` row(s) — even when the refresh token is
     * stale/not found (an already-rotated token must still stop pushes to the device). Does NOT
     * bump `token_version` (spec'd deferral: the ≤15-min access token expires naturally;
     * [logoutAll] is the immediate-kill path).
     */
    suspend fun logout(
        userId: UUID,
        rawRefreshToken: String,
        fcmToken: String?,
    )

    /**
     * Global logout, ONE transaction: delete every refresh token for the user, bump
     * `users.token_version`, and delete ALL of the user's `user_fcm_tokens` rows. Atomicity is
     * spec'd (design D3): a partial failure that kills sessions but leaves push rows would
     * silently reproduce the shared-device push leak this change fixes.
     */
    suspend fun logoutAll(userId: UUID)
}

class TransactionalLogoutService(
    private val dataSource: DataSource,
    private val tokens: RefreshTokenRepository,
    private val users: UserRepository,
    private val refreshTokenService: RefreshTokenService,
    private val fcmTokens: FcmTokenRepository,
    // Pool-bounded JDBC dispatcher (docs/11 §3.2); production passes DbDispatchers.db.
    private val dbDispatcher: CoroutineDispatcher,
) : LogoutService {
    override suspend fun logout(
        userId: UUID,
        rawRefreshToken: String,
        fcmToken: String?,
    ) {
        withContext(dbDispatcher) {
            refreshTokenService.revokeSingle(userId, rawRefreshToken)
        }
        // Two independent idempotent statements, deliberately NOT one transaction (design D3):
        // revoke-by-hash needs service-level logic and there is no cross-statement invariant —
        // a failed FCM delete leaves a row the existing GC paths already cover.
        if (fcmToken != null) {
            fcmTokens.deleteByUserAndToken(userId, fcmToken)
        }
    }

    override suspend fun logoutAll(userId: UUID) {
        withContext(dbDispatcher) {
            dataSource.connection.use { conn ->
                conn.autoCommit = false
                try {
                    tokens.deleteAllForUser(conn, userId)
                    users.incrementTokenVersion(conn, userId)
                    fcmTokens.deleteAllForUser(conn, userId)
                    conn.commit()
                } catch (t: Throwable) {
                    conn.rollback()
                    throw t
                } finally {
                    conn.autoCommit = true
                }
            }
        }
    }
}
