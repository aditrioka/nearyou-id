package id.nearyou.app.admin.auth

import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Reads + writes `admin_sessions` for the admin login flow.
 *
 * Per `admin-login-argon2-totp` spec Req "Session middleware validates the
 * cookie on every authenticated request":
 *  - The active-session predicate is `revoked_at IS NULL AND expires_at >
 *    NOW() AND last_active_at > NOW() - INTERVAL '30 minutes'`. The
 *    middleware applies this predicate at the application layer rather
 *    than in the SQL `WHERE` clause so the idle-timeout boundary can be
 *    asserted deterministically in tests (no Postgres-NOW() vs JVM-clock
 *    drift).
 *  - All queries via JDBC `PreparedStatement` (parameterized).
 *  - The session token's plaintext NEVER reaches a column; only its
 *    SHA-256 hash (hex string).
 */
class SessionRepository(
    private val dataSource: DataSource,
) {
    /**
     * INSERT a session row and return its UUID. Caller is responsible for
     * generating the plaintext tokens, SHA-256-hashing them, and hex-
     * encoding the hashes before calling.
     */
    fun insert(
        adminId: UUID,
        sessionTokenHash: String,
        csrfTokenHash: String,
        ip: String,
        userAgent: String?,
        expiresAt: Instant,
    ): UUID {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO admin_sessions (
                    admin_id, session_token_hash, csrf_token_hash,
                    ip, user_agent, expires_at
                ) VALUES (?, ?, ?, ?::inet, ?, ?)
                RETURNING id
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, adminId)
                ps.setString(2, sessionTokenHash)
                ps.setString(3, csrfTokenHash)
                ps.setString(4, ip)
                ps.setString(5, userAgent)
                ps.setObject(6, java.sql.Timestamp.from(expiresAt))
                ps.executeQuery().use { rs ->
                    if (!rs.next()) error("admin_sessions INSERT did not return an id")
                    return rs.getObject(1, UUID::class.java)
                }
            }
        }
    }

    /**
     * Find any session row (active or not) whose `session_token_hash`
     * matches. Returns null when no row matches. Caller applies the
     * active-session predicate (revoked/expires/idle).
     */
    fun findBySessionHash(sessionTokenHash: String): SessionRow? {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT id, admin_id, csrf_token_hash,
                       expires_at, last_active_at, revoked_at
                  FROM admin_sessions
                 WHERE session_token_hash = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, sessionTokenHash)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return SessionRow(
                        id = rs.getObject("id", UUID::class.java),
                        adminId = rs.getObject("admin_id", UUID::class.java),
                        csrfTokenHash = rs.getString("csrf_token_hash"),
                        expiresAt = rs.getTimestamp("expires_at").toInstant(),
                        lastActiveAt = rs.getTimestamp("last_active_at").toInstant(),
                        revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
                    )
                }
            }
        }
    }

    /**
     * Refresh `last_active_at` to NOW() for an authenticated session.
     * Called on every request that passes the session-middleware gate.
     */
    fun refreshLastActive(sessionId: UUID): Int {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "UPDATE admin_sessions SET last_active_at = NOW() WHERE id = ?",
            ).use { ps ->
                ps.setObject(1, sessionId)
                return ps.executeUpdate()
            }
        }
    }

    /**
     * Revoke a session by setting `revoked_at = NOW()`. Called by the
     * logout handler. Idempotent — subsequent revokes are no-ops because
     * `revoked_at IS NULL` is the only way to be "active".
     */
    fun revoke(sessionId: UUID): Int {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "UPDATE admin_sessions SET revoked_at = NOW() WHERE id = ? AND revoked_at IS NULL",
            ).use { ps ->
                ps.setObject(1, sessionId)
                return ps.executeUpdate()
            }
        }
    }
}

/**
 * Row shape returned by [SessionRepository.findBySessionHash]. Caller
 * applies the active-session predicate (revoked/expires/idle) at the
 * application layer rather than in the SQL.
 */
data class SessionRow(
    val id: UUID,
    val adminId: UUID,
    val csrfTokenHash: String,
    val expiresAt: Instant,
    val lastActiveAt: Instant,
    val revokedAt: Instant?,
)
