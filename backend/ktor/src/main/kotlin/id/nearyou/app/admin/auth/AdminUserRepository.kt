package id.nearyou.app.admin.auth

import java.util.UUID
import javax.sql.DataSource

/**
 * Reads `admin_users` for the login flow.
 *
 * Per `admin-login-argon2-totp` spec Req "Login POST verifies Argon2id
 * password and TOTP and creates a session on success", scenario "Login uses
 * parameterized query for admin lookup":
 *  - Lookup is `WHERE email = $1 AND is_active = TRUE` (deactivated admins
 *    are NOT selected; the `inactive_admin` failure path is reachable only
 *    if the deactivation happened mid-session).
 *  - All queries via JDBC `PreparedStatement` (parameterized; no string
 *    interpolation).
 *
 * The repository does NOT expose write paths in Admin #3 — the first admin
 * row is provisioned via the bootstrap script (design.md D11), and admin
 * self-service password / email change endpoints are deferred follow-ups.
 */
class AdminUserRepository(
    private val dataSource: DataSource,
) {
    /**
     * Find an active admin by [email]. Returns null when no row matches
     * OR when the matched row has `is_active = FALSE`.
     */
    fun findActiveByEmail(email: String): AdminUserRow? {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT id, email, password_hash, totp_secret_encrypted, role, is_active
                  FROM admin_users
                 WHERE email = ? AND is_active = TRUE
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, email)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return AdminUserRow(
                        id = rs.getObject("id", UUID::class.java),
                        email = rs.getString("email"),
                        passwordHash = rs.getString("password_hash"),
                        totpSecretEncrypted = rs.getBytes("totp_secret_encrypted"),
                        role = rs.getString("role"),
                        isActive = rs.getBoolean("is_active"),
                    )
                }
            }
        }
    }

    /**
     * Find an admin by [email] WITHOUT the `is_active` filter. Used ONLY as
     * a secondary, audit-purpose lookup when [findActiveByEmail] returned
     * null, to distinguish an inactive-admin attempt (→ `inactive_admin`
     * audit row) from a truly-missing email (→ `email_not_found` INFO log).
     *
     * Per `admin-login-argon2-totp` spec reconciliation (apply phase): the
     * primary auth lookup [findActiveByEmail] keeps the `is_active = TRUE`
     * WHERE clause (so a deactivated admin's `password_hash` is never even
     * loaded for verification — defense in depth), while this audit-only
     * lookup makes the spec's `inactive_admin` audit reason reachable.
     */
    fun findByEmailAnyStatus(email: String): AdminUserRow? {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT id, email, password_hash, totp_secret_encrypted, role, is_active
                  FROM admin_users
                 WHERE email = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, email)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return AdminUserRow(
                        id = rs.getObject("id", UUID::class.java),
                        email = rs.getString("email"),
                        passwordHash = rs.getString("password_hash"),
                        totpSecretEncrypted = rs.getBytes("totp_secret_encrypted"),
                        role = rs.getString("role"),
                        isActive = rs.getBoolean("is_active"),
                    )
                }
            }
        }
    }

    /**
     * Find an admin by id WITHOUT the `is_active` filter — used by audit
     * paths that need the admin row even if it's been deactivated. NOT
     * consumed by the login flow.
     */
    fun findById(id: UUID): AdminUserRow? {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT id, email, password_hash, totp_secret_encrypted, role, is_active
                  FROM admin_users
                 WHERE id = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return AdminUserRow(
                        id = rs.getObject("id", UUID::class.java),
                        email = rs.getString("email"),
                        passwordHash = rs.getString("password_hash"),
                        totpSecretEncrypted = rs.getBytes("totp_secret_encrypted"),
                        role = rs.getString("role"),
                        isActive = rs.getBoolean("is_active"),
                    )
                }
            }
        }
    }
}

/**
 * Row shape returned by [AdminUserRepository]. [totpSecretEncrypted] is the
 * raw BYTEA value (`nonce || ciphertext || auth_tag`); decrypt via
 * [AesGcmCipher.decrypt] with the AES key from GCP Secret Manager and the
 * admin's UUID as AAD.
 */
data class AdminUserRow(
    val id: UUID,
    val email: String,
    val passwordHash: String,
    val totpSecretEncrypted: ByteArray?,
    val role: String,
    val isActive: Boolean,
) {
    // ByteArray equals/hashCode are reference-based by default; the row is
    // primarily compared by id in callers, so this is acceptable. Override
    // if cross-instance equality becomes load-bearing.
    override fun equals(other: Any?): Boolean = other is AdminUserRow && id == other.id

    override fun hashCode(): Int = id.hashCode()
}
