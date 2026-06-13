package id.nearyou.app.admin.auth

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.samstevens.totp.code.DefaultCodeGenerator
import dev.samstevens.totp.code.HashingAlgorithm
import id.nearyou.app.admin.admin
import io.ktor.client.HttpClient
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.apache.commons.codec.binary.Base32
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * Shared fixtures + helpers for the `admin-login-argon2-totp` integration
 * tests (S11). Centralizes the DataSource, the fixed AES key, admin/session
 * seeding, TOTP code generation, cookie parsing, and the testApplication
 * wiring so each spec file maps cleanly to its requirement without
 * duplicating boilerplate.
 *
 * All DB-backed specs are tagged `database` so CI excludes them by default;
 * run locally with the standard `DB_URL` / `DB_USER` / `DB_PASSWORD` env
 * vars (defaults match Docker Compose dev Postgres at `localhost:5433`).
 */
object AdminAuthTestSupport {
    /**
     * Fixed AES-256 key for the test admin TOTP-secret encryption. NOT a
     * real secret — only used in tests. The production key is sourced via
     * `secretKey(env, "admin-totp-secret-aes-key")` (spec Req "Key is
     * sourced via secretKey helper, not a hardcoded constant" is verified
     * by AdminAuthConstantTimeScanTest, which asserts no such literal lives
     * outside test fixtures).
     */
    val FIXED_AES_KEY: ByteArray = ByteArray(32) { (it + 1).toByte() }

    /** Fixed HMAC key for the test CSRF derivation. NOT a real secret. */
    val FIXED_CSRF_HMAC_KEY: ByteArray = ByteArray(32) { (it + 101).toByte() }

    /** Deployment-env name wired into admin() under test — rendered uppercased as the env chip. */
    const val TEST_ENVIRONMENT_NAME = "test"

    private val secureRandom = SecureRandom()
    private val base32 = Base32()
    private val totpGenerator = DefaultCodeGenerator(HashingAlgorithm.SHA1, TotpVerifier.DIGITS)

    fun hikari(): HikariDataSource {
        val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
        val user = System.getenv("DB_USER") ?: "postgres"
        val password = System.getenv("DB_PASSWORD") ?: "postgres"
        return HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = url
                username = user
                this.password = password
                maximumPoolSize = 4
                initializationFailTimeout = -1
            },
        )
    }

    /** A 20-byte random TOTP secret. */
    fun randomTotpSecret(): ByteArray = ByteArray(20).also { secureRandom.nextBytes(it) }

    /** The current valid 6-digit code for [secret] (current 30s step). */
    fun currentTotpCode(secret: ByteArray): String {
        val step = (System.currentTimeMillis() / 1000) / TotpVerifier.TIME_PERIOD_SECONDS
        return totpGenerator.generate(base32.encodeAsString(secret), step)
    }

    /**
     * Seed an `admin_users` row. Returns the [SeededAdmin] (UUID + plaintext
     * password + raw TOTP secret bytes) so the caller can drive the login
     * flow. The password is hashed with the production [PasswordHasher]; the
     * TOTP secret is AES-256-GCM-encrypted with [FIXED_AES_KEY] + the admin
     * UUID AAD, matching what the bootstrap script produces.
     */
    fun seedAdmin(
        dataSource: DataSource,
        email: String = "oka+${UUID.randomUUID().toString().take(8)}@nearyou.id",
        password: String = "correct horse battery staple",
        totpSecret: ByteArray? = randomTotpSecret(),
        isActive: Boolean = true,
        role: String = "owner",
    ): SeededAdmin {
        val id = UUID.randomUUID()
        val passwordHash = PasswordHasher.hash(password)
        val encryptedSecret =
            totpSecret?.let {
                AesGcmCipher.encrypt(it, FIXED_AES_KEY, AesGcmCipher.adminUuidAsBytes(id))
            }
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO admin_users (
                    id, email, display_name, password_hash, totp_secret_encrypted, role, is_active
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, email)
                ps.setString(3, "Test Admin")
                ps.setString(4, passwordHash)
                ps.setBytes(5, encryptedSecret)
                ps.setString(6, role)
                ps.setBoolean(7, isActive)
                ps.executeUpdate()
            }
        }
        return SeededAdmin(id, email, password, totpSecret)
    }

    /**
     * Seed an `admin_sessions` row directly with controlled timestamps and
     * return the plaintext session token (to be sent as the cookie). The
     * `csrf_token_hash` is computed to match what the login flow would
     * store: `SHA-256(deriveCsrfFromSessionToken(sessionToken, FIXED_CSRF_HMAC_KEY))`.
     */
    fun seedSession(
        dataSource: DataSource,
        adminId: UUID,
        expiresAt: Instant = Instant.now().plusSeconds(8 * 3600),
        lastActiveAt: Instant = Instant.now(),
        revokedAt: Instant? = null,
        ip: String = "127.0.0.1",
    ): String {
        val sessionToken = generateOpaqueToken()
        val sessionTokenHash = HashUtil.sha256Hex(sessionToken)
        val csrfTokenHash =
            HashUtil.sha256Hex(HashUtil.deriveCsrfFromSessionToken(sessionToken, FIXED_CSRF_HMAC_KEY))
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO admin_sessions (
                    admin_id, session_token_hash, csrf_token_hash, ip,
                    created_at, last_active_at, expires_at, revoked_at
                ) VALUES (?, ?, ?, ?::inet, NOW(), ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, adminId)
                ps.setString(2, sessionTokenHash)
                ps.setString(3, csrfTokenHash)
                ps.setString(4, ip)
                ps.setObject(5, Timestamp.from(lastActiveAt))
                ps.setObject(6, Timestamp.from(expiresAt))
                ps.setObject(7, revokedAt?.let { Timestamp.from(it) })
                ps.executeUpdate()
            }
        }
        return sessionToken
    }

    private fun generateOpaqueToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Derive the plaintext CSRF token a session cookie value implies. */
    fun csrfFor(sessionToken: String): String = HashUtil.deriveCsrfFromSessionToken(sessionToken, FIXED_CSRF_HMAC_KEY)

    // ---------------- DB query helpers ----------------

    fun countSessions(
        dataSource: DataSource,
        adminId: UUID,
    ): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT count(*) FROM admin_sessions WHERE admin_id = ?").use { ps ->
                ps.setObject(1, adminId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    fun sessionByTokenHash(
        dataSource: DataSource,
        sessionToken: String,
    ): SessionSnapshot? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT id, admin_id, session_token_hash, csrf_token_hash,
                       expires_at, last_active_at, revoked_at
                  FROM admin_sessions WHERE session_token_hash = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, HashUtil.sha256Hex(sessionToken))
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    SessionSnapshot(
                        id = rs.getObject("id", UUID::class.java),
                        adminId = rs.getObject("admin_id", UUID::class.java),
                        sessionTokenHash = rs.getString("session_token_hash"),
                        csrfTokenHash = rs.getString("csrf_token_hash"),
                        expiresAt = rs.getTimestamp("expires_at").toInstant(),
                        lastActiveAt = rs.getTimestamp("last_active_at").toInstant(),
                        revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
                    )
                }
            }
        }

    fun latestAuditRows(
        dataSource: DataSource,
        adminId: UUID,
    ): List<AuditSnapshot> =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT action_type, target_type, reason, before_state::text AS bs,
                       after_state::text AS afs, ip, user_agent
                  FROM admin_actions_log WHERE admin_id = ? ORDER BY created_at DESC
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, adminId)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                AuditSnapshot(
                                    actionType = rs.getString("action_type"),
                                    targetType = rs.getString("target_type"),
                                    reason = rs.getString("reason"),
                                    beforeState = rs.getString("bs"),
                                    afterState = rs.getString("afs"),
                                    ip = rs.getString("ip"),
                                    userAgent = rs.getString("user_agent"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    fun countAuditRows(
        dataSource: DataSource,
        adminId: UUID,
    ): Int = latestAuditRows(dataSource, adminId).size

    /** Clean up seeded rows for an admin (sessions + audit rows + the admin). */
    fun cleanupAdmin(
        dataSource: DataSource,
        adminId: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM admin_sessions WHERE admin_id = ?").use { ps ->
                ps.setObject(1, adminId)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM admin_actions_log WHERE admin_id = ?").use { ps ->
                ps.setObject(1, adminId)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM admin_users WHERE id = ?").use { ps ->
                ps.setObject(1, adminId)
                ps.executeUpdate()
            }
        }
    }

    // ---------------- testApplication wiring ----------------

    /**
     * Boot a testApplication with the admin module wired against
     * [dataSource] + [FIXED_AES_KEY], and a non-redirect-following client so
     * 302s are observable. [aesKeyOverride] lets a test inject a different
     * key (e.g., to exercise wrong-key decryption).
     */
    suspend fun withAdminApp(
        dataSource: DataSource,
        aesKeyOverride: (() -> ByteArray)? = null,
        clock: () -> Instant = Instant::now,
        block: suspend ApplicationTestBuilder.(client: HttpClient) -> Unit,
    ) {
        testApplication {
            application {
                installTestClientIpDefault()
                admin(
                    dataSource = dataSource,
                    aesKeyProvider = aesKeyOverride ?: { FIXED_AES_KEY },
                    csrfHmacKeyProvider = { FIXED_CSRF_HMAC_KEY },
                    environmentName = TEST_ENVIRONMENT_NAME,
                    privacyFlipsClock = clock,
                )
            }
            val client = createClient { followRedirects = false }
            block(client)
        }
    }

    /**
     * Parse the value of a `__Host-admin_session` Set-Cookie header. Returns
     * the cookie value (between `=` and the first `;`), or null if the
     * header doesn't set that cookie.
     */
    fun parseSessionCookieValue(setCookieHeaders: List<String>): String? {
        val header =
            setCookieHeaders.firstOrNull { it.startsWith("${AdminAuthProvider.COOKIE_NAME}=") }
                ?: return null
        val afterEquals = header.substringAfter("${AdminAuthProvider.COOKIE_NAME}=")
        return afterEquals.substringBefore(";")
    }

    /**
     * Install a test-only plugin that pre-seeds the `ClientIp` request
     * attribute to a valid INET (`127.0.0.1`) WHEN no `CF-Connecting-IP`
     * header is present. testApplication's `remoteHost` is `"localhost"`
     * (not INET-valid → the `admin_sessions.ip` / `admin_actions_log.ip`
     * INSERTs would fail), and the production `ClientIpExtractorPlugin`
     * isn't installed by `admin()`. By pre-seeding only when the CF header
     * is absent, tests that assert IP propagation (sending their own
     * `CF-Connecting-IP: 1.2.3.4`) still resolve from the header — no
     * production code changes, no `DefaultRequest` header-append artifact.
     */
    private fun io.ktor.server.application.Application.installTestClientIpDefault() {
        install(
            io.ktor.server.application.createApplicationPlugin("TestClientIpDefault") {
                onCall { call ->
                    val hasCfHeader = !call.request.headers["CF-Connecting-IP"].isNullOrBlank()
                    if (!hasCfHeader && !call.attributes.contains(id.nearyou.app.common.ClientIpAttributeKey)) {
                        call.attributes.put(id.nearyou.app.common.ClientIpAttributeKey, "127.0.0.1")
                    }
                }
            },
        )
    }

    data class SeededAdmin(
        val id: UUID,
        val email: String,
        val password: String,
        val totpSecret: ByteArray?,
    )

    data class SessionSnapshot(
        val id: UUID,
        val adminId: UUID,
        val sessionTokenHash: String,
        val csrfTokenHash: String,
        val expiresAt: Instant,
        val lastActiveAt: Instant,
        val revokedAt: Instant?,
    )

    data class AuditSnapshot(
        val actionType: String,
        val targetType: String?,
        val reason: String?,
        val beforeState: String?,
        val afterState: String?,
        val ip: String?,
        val userAgent: String?,
    )
}
