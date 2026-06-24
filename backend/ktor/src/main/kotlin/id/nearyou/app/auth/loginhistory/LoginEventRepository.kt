package id.nearyou.app.auth.loginhistory

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.sql.DataSource

/** The two authenticated-event kinds recorded in `login_events`. */
enum class LoginEventType(val wire: String) {
    SIGNIN("signin"),
    REFRESH("refresh"),
}

/** Writer for `login_events` (V34); the in-memory test fake mirrors this seam. */
interface LoginEventRepository {
    suspend fun insert(
        userId: UUID,
        eventType: LoginEventType,
        ip: String?,
        deviceFingerprintHash: String?,
        identifierHash: String?,
    )
}

/**
 * JDBC [LoginEventRepository] — the durable login-history store
 * (`login-history-tracking`). One self-contained `INSERT` on its own auto-commit
 * connection, run on the pool-bounded DB dispatcher (`docs/11` §3.2 — never raw
 * `Dispatchers.IO`; production passes `DbDispatchers.db`), mirroring
 * [id.nearyou.app.admin.retention.JdbcRetentionCleanupRepository].
 *
 * `ip` is bound as text and cast `?::inet` so a NULL stays NULL; the generated
 * `ip_subnet_24` /24 is computed by the column, not here.
 */
class JdbcLoginEventRepository(
    private val dataSource: DataSource,
    // Pool-bounded JDBC dispatcher (docs/11 §3.2); production passes DbDispatchers.db.
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LoginEventRepository {
    override suspend fun insert(
        userId: UUID,
        eventType: LoginEventType,
        ip: String?,
        deviceFingerprintHash: String?,
        identifierHash: String?,
    ) {
        withContext(dbDispatcher) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(INSERT_SQL).use { ps ->
                    ps.setObject(1, userId)
                    ps.setString(2, eventType.wire)
                    ps.setString(3, ip)
                    ps.setString(4, deviceFingerprintHash)
                    ps.setString(5, identifierHash)
                    ps.executeUpdate()
                }
            }
        }
    }

    private companion object {
        const val INSERT_SQL =
            "INSERT INTO login_events (user_id, event_type, ip, device_fingerprint_hash, identifier_hash) " +
                "VALUES (?, ?, ?::inet, ?, ?)"
    }
}
