package id.nearyou.app.infra.db

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.flywaydb.core.Flyway
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * Database-dependent smoke test for V16 (`V16__admin_users.sql`). Boots Flyway
 * against the running dev Postgres (or the CI service container) and asserts
 * the admin-schema-bootstrap invariants from `openspec/specs/admin-schema`
 * (post-archive) plus the FK-presence + comment-replacement scenarios from the
 * `reports`, `moderation-queue`, and `chat-conversations` MODIFIED deltas.
 *
 *   - V16 present in `flyway_schema_history` with success.
 *   - Five admin tables exist with canonical column shape: admin_users,
 *     admin_webauthn_credentials, admin_sessions, admin_webauthn_challenges,
 *     admin_actions_log.
 *   - All CHECK constraints reject out-of-allowlist values (role, ceremony).
 *   - All NOT NULL constraints reject NULL inserts on load-bearing columns
 *     (password_hash, display_name, csrf_token_hash, expires_at, ip, ceremony).
 *   - All UNIQUE constraints fire SQLSTATE 23505 (email, credential_id,
 *     session_token_hash).
 *   - DEFAULT values land where omitted (webauthn_enrolled=FALSE,
 *     is_active=TRUE).
 *   - FK CASCADE behavior: admin deletion cascades to webauthn credentials,
 *     sessions, challenges.
 *   - FK NO ACTION (default) on admin_actions_log.admin_id rejects orphaning
 *     admin DELETE with SQLSTATE 23503; same constraint rejects INSERT with
 *     bogus admin_id UUID.
 *   - All 7 new admin indexes exist with correct columns + the two partial
 *     indexes carry their immutable WHERE predicates.
 *   - Three FK backfills are present + validated (convalidated=true) on
 *     reports.reviewed_by, moderation_queue.resolved_by, chat_messages.redacted_by.
 *   - Comment-replacement: the three columns' pg_description no longer contains
 *     "deferred" AND mentions "admin_users".
 *   - SET NULL behavior on reports.reviewed_by triggers on admin hard-delete
 *     when the admin has no admin_actions_log references.
 *   - No GRANT/REVOKE statement in V16 SQL source (admin_app role is operational,
 *     not Flyway-managed).
 *   - No seed rows in admin_users or admin_actions_log post-V16.
 *   - No RLS enabled on the five admin tables (pg_class.relrowsecurity=false)
 *     AND no policies attached (pg_policies empty for those tables).
 *   - V16 sql file does not reference auth.* or realtime.* schemas (parity-
 *     init not needed for this migration).
 *
 * Tagged `database` so CI excludes it by default. Run locally with:
 *   `./gradlew :backend:ktor:test -Dkotest.tags=database`
 */
@Tags("database")
class MigrationV16SmokeTest : StringSpec({

    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val user = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"

    Flyway
        .configure()
        .dataSource(url, user, password)
        .locations("classpath:db/migration")
        .load()
        .migrate()

    fun seedAdmin(
        conn: Connection,
        emailSuffix: String = UUID.randomUUID().toString(),
        role: String = "moderator",
    ): UUID {
        val id = UUID.randomUUID()
        conn.prepareStatement(
            """
            INSERT INTO admin_users (id, email, display_name, password_hash, role)
            VALUES (?, ?, 'V16 Smoke Admin', 'argon2id-placeholder', ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, id)
            ps.setString(2, "v16-smoke-$emailSuffix@test.local")
            ps.setString(3, role)
            ps.executeUpdate()
        }
        return id
    }

    fun cleanupAdmins(
        conn: Connection,
        vararg ids: UUID,
    ) {
        conn.prepareStatement("DELETE FROM admin_users WHERE id = ANY(?)").use { ps ->
            ps.setArray(1, conn.createArrayOf("uuid", ids.map { it }.toTypedArray()))
            ps.executeUpdate()
        }
    }

    "V16 present in flyway_schema_history with success" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.prepareStatement(
                "SELECT success FROM flyway_schema_history WHERE version = '16'",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    rs.getBoolean(1) shouldBe true
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Requirement 1: admin_users
    // -----------------------------------------------------------------------

    "admin_users — all 10 canonical columns present" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.prepareStatement(
                """
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'admin_users'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val cols = mutableSetOf<String>()
                    while (rs.next()) cols.add(rs.getString(1))
                    cols shouldContainAll
                        setOf(
                            "id", "email", "display_name", "password_hash",
                            "totp_secret_encrypted", "webauthn_enrolled", "role",
                            "is_active", "created_at", "last_login_at",
                        )
                }
            }
        }
    }

    "admin_users — role CHECK rejects unsupported value" {
        DriverManager.getConnection(url, user, password).use { conn ->
            try {
                conn.prepareStatement(
                    """
                    INSERT INTO admin_users (email, display_name, password_hash, role)
                    VALUES (?, 'X', 'hash', 'superuser')
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, "rejected-${UUID.randomUUID()}@test.local")
                    ps.executeUpdate()
                }
                error("CHECK constraint should have rejected role='superuser'")
            } catch (e: PSQLException) {
                e.sqlState shouldBe "23514"
            }
        }
    }

    "admin_users — email UNIQUE rejects duplicate" {
        DriverManager.getConnection(url, user, password).use { conn ->
            val email = "dup-${UUID.randomUUID()}@test.local"
            val first = UUID.randomUUID()
            val second = UUID.randomUUID()
            conn.prepareStatement(
                "INSERT INTO admin_users (id, email, display_name, password_hash, role) VALUES (?, ?, 'A', 'h', 'admin')",
            ).use { ps ->
                ps.setObject(1, first)
                ps.setString(2, email)
                ps.executeUpdate()
            }
            try {
                conn.prepareStatement(
                    "INSERT INTO admin_users (id, email, display_name, password_hash, role) VALUES (?, ?, 'B', 'h', 'admin')",
                ).use { ps ->
                    ps.setObject(1, second)
                    ps.setString(2, email)
                    ps.executeUpdate()
                }
                error("UNIQUE constraint should have rejected duplicate email")
            } catch (e: PSQLException) {
                e.sqlState shouldBe "23505"
            } finally {
                cleanupAdmins(conn, first)
            }
        }
    }

    "admin_users — webauthn_enrolled defaults to FALSE; is_active defaults to TRUE" {
        DriverManager.getConnection(url, user, password).use { conn ->
            val id = UUID.randomUUID()
            conn.prepareStatement(
                "INSERT INTO admin_users (id, email, display_name, password_hash, role) VALUES (?, ?, 'D', 'h', 'admin')",
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "defaults-${UUID.randomUUID()}@test.local")
                ps.executeUpdate()
            }
            try {
                conn.prepareStatement(
                    "SELECT webauthn_enrolled, is_active FROM admin_users WHERE id = ?",
                ).use { ps ->
                    ps.setObject(1, id)
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getBoolean(1) shouldBe false
                        rs.getBoolean(2) shouldBe true
                    }
                }
            } finally {
                cleanupAdmins(conn, id)
            }
        }
    }

    "admin_users — password_hash NOT NULL rejects NULL" {
        DriverManager.getConnection(url, user, password).use { conn ->
            try {
                conn.prepareStatement(
                    "INSERT INTO admin_users (email, display_name, password_hash, role) VALUES (?, 'X', NULL, 'admin')",
                ).use { ps ->
                    ps.setString(1, "nullhash-${UUID.randomUUID()}@test.local")
                    ps.executeUpdate()
                }
                error("NOT NULL on password_hash should have rejected NULL")
            } catch (e: PSQLException) {
                e.sqlState shouldBe "23502"
            }
        }
    }

    "admin_users — display_name NOT NULL rejects NULL" {
        DriverManager.getConnection(url, user, password).use { conn ->
            try {
                conn.prepareStatement(
                    "INSERT INTO admin_users (email, display_name, password_hash, role) VALUES (?, NULL, 'h', 'admin')",
                ).use { ps ->
                    ps.setString(1, "nullname-${UUID.randomUUID()}@test.local")
                    ps.executeUpdate()
                }
                error("NOT NULL on display_name should have rejected NULL")
            } catch (e: PSQLException) {
                e.sqlState shouldBe "23502"
            }
        }
    }

    // -----------------------------------------------------------------------
    // Requirement 2: admin_webauthn_credentials
    // -----------------------------------------------------------------------

    "admin_webauthn_credentials — column shape + CASCADE on admin deletion" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.prepareStatement(
                """
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'admin_webauthn_credentials'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val cols = mutableSetOf<String>()
                    while (rs.next()) cols.add(rs.getString(1))
                    cols shouldContainAll
                        setOf(
                            "id", "admin_id", "credential_id", "public_key",
                            "sign_count", "device_label", "created_at", "last_used_at",
                        )
                }
            }
            val admin = seedAdmin(conn)
            val credId = ByteArray(32) { 0x42 }
            conn.prepareStatement(
                "INSERT INTO admin_webauthn_credentials (admin_id, credential_id, public_key) VALUES (?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, admin)
                ps.setBytes(2, credId)
                ps.setBytes(3, ByteArray(64) { 0x01 })
                ps.executeUpdate()
            }
            // CASCADE on admin DELETE — but we can't hard-delete the admin if any
            // admin_actions_log refs exist. Fresh admin has none → DELETE is allowed.
            conn.prepareStatement("DELETE FROM admin_users WHERE id = ?").use { ps ->
                ps.setObject(1, admin)
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "SELECT COUNT(*) FROM admin_webauthn_credentials WHERE admin_id = ?",
            ).use { ps ->
                ps.setObject(1, admin)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) shouldBe 0
                }
            }
        }
    }

    "admin_webauthn_credentials — credential_id UNIQUE rejects duplicate" {
        DriverManager.getConnection(url, user, password).use { conn ->
            val admin = seedAdmin(conn)
            val credId = ByteArray(32) { 0x33 }
            conn.prepareStatement(
                "INSERT INTO admin_webauthn_credentials (admin_id, credential_id, public_key) VALUES (?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, admin)
                ps.setBytes(2, credId)
                ps.setBytes(3, ByteArray(64) { 0x02 })
                ps.executeUpdate()
            }
            try {
                conn.prepareStatement(
                    "INSERT INTO admin_webauthn_credentials (admin_id, credential_id, public_key) VALUES (?, ?, ?)",
                ).use { ps ->
                    ps.setObject(1, admin)
                    ps.setBytes(2, credId)
                    ps.setBytes(3, ByteArray(64) { 0x03 })
                    ps.executeUpdate()
                }
                error("UNIQUE on credential_id should have rejected duplicate")
            } catch (e: PSQLException) {
                e.sqlState shouldBe "23505"
            } finally {
                cleanupAdmins(conn, admin)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Requirement 3: admin_sessions
    // -----------------------------------------------------------------------

    "admin_sessions — column shape + both indexes present" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.prepareStatement(
                """
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'admin_sessions'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val cols = mutableSetOf<String>()
                    while (rs.next()) cols.add(rs.getString(1))
                    cols shouldContainAll
                        setOf(
                            "id", "admin_id", "session_token_hash", "csrf_token_hash",
                            "ip", "user_agent", "created_at", "last_active_at",
                            "expires_at", "revoked_at",
                        )
                }
            }
            conn.prepareStatement(
                """
                SELECT indexname FROM pg_indexes
                 WHERE schemaname = 'public' AND tablename = 'admin_sessions'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val idx = mutableSetOf<String>()
                    while (rs.next()) idx.add(rs.getString(1))
                    idx shouldContainAll setOf("admin_sessions_admin_idx", "admin_sessions_active_idx")
                }
            }
        }
    }

    "admin_sessions — csrf_token_hash NOT NULL rejects NULL" {
        DriverManager.getConnection(url, user, password).use { conn ->
            val admin = seedAdmin(conn)
            try {
                conn.prepareStatement(
                    """
                    INSERT INTO admin_sessions
                        (admin_id, session_token_hash, csrf_token_hash, ip, expires_at)
                    VALUES (?, ?, NULL, '127.0.0.1'::inet, NOW() + INTERVAL '30 minutes')
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, admin)
                    ps.setString(2, "session-${UUID.randomUUID()}")
                    ps.executeUpdate()
                }
                error("NOT NULL on csrf_token_hash should have rejected NULL")
            } catch (e: PSQLException) {
                e.sqlState shouldBe "23502"
            } finally {
                cleanupAdmins(conn, admin)
            }
        }
    }

    "admin_sessions — session_token_hash UNIQUE rejects duplicate" {
        DriverManager.getConnection(url, user, password).use { conn ->
            val admin = seedAdmin(conn)
            val token = "shared-token-${UUID.randomUUID()}"
            conn.prepareStatement(
                """
                INSERT INTO admin_sessions
                    (admin_id, session_token_hash, csrf_token_hash, ip, expires_at)
                VALUES (?, ?, 'csrf-1', '127.0.0.1'::inet, NOW() + INTERVAL '30 minutes')
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, admin)
                ps.setString(2, token)
                ps.executeUpdate()
            }
            try {
                conn.prepareStatement(
                    """
                    INSERT INTO admin_sessions
                        (admin_id, session_token_hash, csrf_token_hash, ip, expires_at)
                    VALUES (?, ?, 'csrf-2', '127.0.0.1'::inet, NOW() + INTERVAL '30 minutes')
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, admin)
                    ps.setString(2, token)
                    ps.executeUpdate()
                }
                error("UNIQUE on session_token_hash should have rejected duplicate")
            } catch (e: PSQLException) {
                e.sqlState shouldBe "23505"
            } finally {
                cleanupAdmins(conn, admin)
            }
        }
    }

    "admin_sessions — expires_at NOT NULL rejects NULL" {
        DriverManager.getConnection(url, user, password).use { conn ->
            val admin = seedAdmin(conn)
            try {
                conn.prepareStatement(
                    """
                    INSERT INTO admin_sessions
                        (admin_id, session_token_hash, csrf_token_hash, ip, expires_at)
                    VALUES (?, ?, 'csrf', '127.0.0.1'::inet, NULL)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, admin)
                    ps.setString(2, "session-${UUID.randomUUID()}")
                    ps.executeUpdate()
                }
                error("NOT NULL on expires_at should have rejected NULL")
            } catch (e: PSQLException) {
                e.sqlState shouldBe "23502"
            } finally {
                cleanupAdmins(conn, admin)
            }
        }
    }

    "admin_sessions — ip NOT NULL rejects NULL" {
        DriverManager.getConnection(url, user, password).use { conn ->
            val admin = seedAdmin(conn)
            try {
                conn.prepareStatement(
                    """
                    INSERT INTO admin_sessions
                        (admin_id, session_token_hash, csrf_token_hash, ip, expires_at)
                    VALUES (?, ?, 'csrf', NULL, NOW() + INTERVAL '30 minutes')
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, admin)
                    ps.setString(2, "session-${UUID.randomUUID()}")
                    ps.executeUpdate()
                }
                error("NOT NULL on ip should have rejected NULL")
            } catch (e: PSQLException) {
                e.sqlState shouldBe "23502"
            } finally {
                cleanupAdmins(conn, admin)
            }
        }
    }

    "admin_sessions_active_idx — partial predicate AND indexed column is expires_at" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.prepareStatement(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'admin_sessions_active_idx'",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    val def = rs.getString(1)
                    def shouldContain "(expires_at)"
                    def shouldContain "WHERE (revoked_at IS NULL)"
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Requirement 4: admin_webauthn_challenges
    // -----------------------------------------------------------------------

    "admin_webauthn_challenges — column shape (incl admin_id nullable) + indexes" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.prepareStatement(
                """
                SELECT column_name, is_nullable FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'admin_webauthn_challenges'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val cols = mutableMapOf<String, String>()
                    while (rs.next()) cols[rs.getString(1)] = rs.getString(2)
                    cols.keys shouldContainAll
                        setOf(
                            "id", "admin_id", "challenge", "ceremony",
                            "created_at", "expires_at", "consumed_at",
                        )
                    cols["admin_id"] shouldBe "YES"
                }
            }
            conn.prepareStatement(
                """
                SELECT indexname FROM pg_indexes
                 WHERE schemaname = 'public' AND tablename = 'admin_webauthn_challenges'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val idx = mutableSetOf<String>()
                    while (rs.next()) idx.add(rs.getString(1))
                    idx shouldContainAll
                        setOf(
                            "admin_webauthn_challenges_admin_idx",
                            "admin_webauthn_challenges_cleanup_idx",
                        )
                }
            }
        }
    }

    "admin_webauthn_challenges — ceremony CHECK rejects unsupported value" {
        DriverManager.getConnection(url, user, password).use { conn ->
            try {
                conn.prepareStatement(
                    """
                    INSERT INTO admin_webauthn_challenges (challenge, ceremony, expires_at)
                    VALUES ('\x42'::bytea, 'enrollment', NOW() + INTERVAL '5 minutes')
                    """.trimIndent(),
                ).executeUpdate()
                error("CHECK on ceremony should have rejected 'enrollment'")
            } catch (e: PSQLException) {
                e.sqlState shouldBe "23514"
            }
        }
    }

    "admin_webauthn_challenges — ceremony NOT NULL rejects NULL" {
        DriverManager.getConnection(url, user, password).use { conn ->
            try {
                conn.prepareStatement(
                    """
                    INSERT INTO admin_webauthn_challenges (challenge, ceremony, expires_at)
                    VALUES ('\x42'::bytea, NULL, NOW() + INTERVAL '5 minutes')
                    """.trimIndent(),
                ).executeUpdate()
                error("NOT NULL on ceremony should have rejected NULL")
            } catch (e: PSQLException) {
                e.sqlState shouldBe "23502"
            }
        }
    }

    "admin_webauthn_challenges — expires_at NOT NULL rejects NULL" {
        DriverManager.getConnection(url, user, password).use { conn ->
            try {
                conn.prepareStatement(
                    """
                    INSERT INTO admin_webauthn_challenges (challenge, ceremony, expires_at)
                    VALUES ('\x42'::bytea, 'registration', NULL)
                    """.trimIndent(),
                ).executeUpdate()
                error("NOT NULL on expires_at should have rejected NULL")
            } catch (e: PSQLException) {
                e.sqlState shouldBe "23502"
            }
        }
    }

    "admin_webauthn_challenges — admin_id NULL accepted for registration pre-binding" {
        DriverManager.getConnection(url, user, password).use { conn ->
            val challengeId =
                conn.prepareStatement(
                    """
                    INSERT INTO admin_webauthn_challenges (challenge, ceremony, expires_at)
                    VALUES ('\x42'::bytea, 'registration', NOW() + INTERVAL '5 minutes')
                    RETURNING id
                    """.trimIndent(),
                ).executeQuery().use { rs ->
                    rs.next() shouldBe true
                    rs.getObject(1, UUID::class.java)
                }
            conn.prepareStatement("DELETE FROM admin_webauthn_challenges WHERE id = ?").use { ps ->
                ps.setObject(1, challengeId)
                ps.executeUpdate()
            }
        }
    }

    "admin_webauthn_challenges_cleanup_idx — partial predicate present" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.prepareStatement(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'admin_webauthn_challenges_cleanup_idx'",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    rs.getString(1) shouldContain "WHERE (consumed_at IS NULL)"
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Requirement 5: admin_actions_log
    // -----------------------------------------------------------------------

    "admin_actions_log — column shape + three secondary indexes" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.prepareStatement(
                """
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'admin_actions_log'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val cols = mutableSetOf<String>()
                    while (rs.next()) cols.add(rs.getString(1))
                    cols shouldContainAll
                        setOf(
                            "id", "admin_id", "action_type", "target_type", "target_id",
                            "reason", "before_state", "after_state", "ip", "user_agent",
                            "created_at",
                        )
                }
            }
            conn.prepareStatement(
                """
                SELECT indexname FROM pg_indexes
                 WHERE schemaname = 'public' AND tablename = 'admin_actions_log'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val idx = mutableSetOf<String>()
                    while (rs.next()) idx.add(rs.getString(1))
                    idx shouldContainAll
                        setOf(
                            "admin_actions_admin_idx",
                            "admin_actions_target_idx",
                            "admin_actions_type_idx",
                        )
                }
            }
        }
    }

    "admin_actions_log — admin_id NOT NULL rejects NULL inserts" {
        DriverManager.getConnection(url, user, password).use { conn ->
            try {
                conn.prepareStatement(
                    "INSERT INTO admin_actions_log (admin_id, action_type) VALUES (NULL, 'test_action')",
                ).executeUpdate()
                error("NOT NULL on admin_id should have rejected NULL")
            } catch (e: PSQLException) {
                e.sqlState shouldBe "23502"
            }
        }
    }

    "admin_actions_log — NO ACTION default rejects orphaning admin DELETE" {
        DriverManager.getConnection(url, user, password).use { conn ->
            val admin = seedAdmin(conn)
            conn.prepareStatement(
                "INSERT INTO admin_actions_log (admin_id, action_type) VALUES (?, 'test_action')",
            ).use { ps ->
                ps.setObject(1, admin)
                ps.executeUpdate()
            }
            try {
                conn.prepareStatement("DELETE FROM admin_users WHERE id = ?").use { ps ->
                    ps.setObject(1, admin)
                    ps.executeUpdate()
                }
                error("FK NO ACTION should have rejected DELETE of admin with audit-log refs")
            } catch (e: PSQLException) {
                e.sqlState shouldBe "23503"
            } finally {
                conn.prepareStatement("DELETE FROM admin_actions_log WHERE admin_id = ?").use { ps ->
                    ps.setObject(1, admin)
                    ps.executeUpdate()
                }
                cleanupAdmins(conn, admin)
            }
        }
    }

    "admin_actions_log — admin_id FK rejects INSERT with bogus UUID" {
        DriverManager.getConnection(url, user, password).use { conn ->
            try {
                conn.prepareStatement(
                    "INSERT INTO admin_actions_log (admin_id, action_type) VALUES (?, 'test_action')",
                ).use { ps ->
                    ps.setObject(1, UUID.randomUUID())
                    ps.executeUpdate()
                }
                error("FK should have rejected INSERT with non-existent admin_id")
            } catch (e: PSQLException) {
                e.sqlState shouldBe "23503"
            }
        }
    }

    // -----------------------------------------------------------------------
    // Requirements 6/7/8: No GRANT/REVOKE statements; no seed rows; no RLS
    // -----------------------------------------------------------------------

    "V16 SQL file contains no GRANT or REVOKE statements" {
        val sql =
            this::class.java.classLoader
                .getResourceAsStream("db/migration/V16__admin_users.sql")!!
                .bufferedReader()
                .use { it.readText() }
        // Strip comments before scanning to avoid false positives on the design-rationale
        // text in the header (which legitimately mentions GRANT/REVOKE) and on the
        // column name `revoked_at` (substring match on REVOKE).
        val stripped =
            sql.lineSequence()
                .map { it.replace(Regex("--.*$"), "") }
                .joinToString("\n")
        val statementPattern = Regex("(?im)^\\s*(GRANT|REVOKE)\\b")
        statementPattern.containsMatchIn(stripped) shouldBe false
    }

    "V16 SQL file contains no auth.* or realtime.* schema references" {
        val sql =
            this::class.java.classLoader
                .getResourceAsStream("db/migration/V16__admin_users.sql")!!
                .bufferedReader()
                .use { it.readText() }
        // Inspect only non-comment lines.
        val stripped =
            sql.lineSequence()
                .map { it.replace(Regex("--.*$"), "") }
                .joinToString("\n")
        Regex("\\bauth\\.").containsMatchIn(stripped) shouldBe false
        Regex("\\brealtime\\.").containsMatchIn(stripped) shouldBe false
    }

    "No seed rows in admin_users or admin_actions_log post-V16" {
        DriverManager.getConnection(url, user, password).use { conn ->
            // After Flyway applies V16; tests in this file may briefly seed and
            // clean up rows, but at the moment Flyway applied V16 there were zero.
            // We can only assert "no permanent seed" by checking the row count is
            // the same as a fresh post-V16 state, which means: count rows whose
            // email matches the test-seeded pattern and assert all other rows
            // were not introduced by V16. Concretely: check that there are no
            // rows with display_name = 'V16 Smoke Admin' OR 'Test Admin' is
            // possible because of in-flight test data; instead we just verify
            // that the file has no INSERT INTO admin_users statement.
            val sql =
                this::class.java.classLoader
                    .getResourceAsStream("db/migration/V16__admin_users.sql")!!
                    .bufferedReader()
                    .use { it.readText() }
            val stripped =
                sql.lineSequence()
                    .map { it.replace(Regex("--.*$"), "") }
                    .joinToString("\n")
            Regex("(?i)\\bINSERT\\s+INTO\\s+admin_users\\b").containsMatchIn(stripped) shouldBe false
            Regex("(?i)\\bINSERT\\s+INTO\\s+admin_actions_log\\b").containsMatchIn(stripped) shouldBe false
            // Sanity sniff that the connection is live (silences unused warning):
            conn.isValid(2) shouldBe true
        }
    }

    "No RLS enabled on the five admin tables" {
        DriverManager.getConnection(url, user, password).use { conn ->
            val adminTables =
                listOf(
                    "admin_users",
                    "admin_webauthn_credentials",
                    "admin_sessions",
                    "admin_webauthn_challenges",
                    "admin_actions_log",
                )
            conn.prepareStatement(
                """
                SELECT c.relname, c.relrowsecurity
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'public' AND c.relname = ANY(?)
                """.trimIndent(),
            ).use { ps ->
                ps.setArray(1, conn.createArrayOf("text", adminTables.toTypedArray()))
                ps.executeQuery().use { rs ->
                    val rls = mutableMapOf<String, Boolean>()
                    while (rs.next()) rls[rs.getString(1)] = rs.getBoolean(2)
                    rls.size shouldBe adminTables.size
                    rls.values.all { !it } shouldBe true
                }
            }
            // Also verify no policies are attached (even if not enabled).
            conn.prepareStatement(
                """
                SELECT COUNT(*) FROM pg_policies
                 WHERE schemaname = 'public' AND tablename = ANY(?)
                """.trimIndent(),
            ).use { ps ->
                ps.setArray(
                    1,
                    conn.createArrayOf(
                        "text",
                        arrayOf(
                            "admin_users",
                            "admin_webauthn_credentials",
                            "admin_sessions",
                            "admin_webauthn_challenges",
                            "admin_actions_log",
                        ),
                    ),
                )
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) shouldBe 0
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // FK backfills: reports / moderation_queue / chat_messages
    // -----------------------------------------------------------------------

    "FK backfill — reports.reviewed_by exists, validated, ON DELETE SET NULL" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.prepareStatement(
                """
                SELECT confdeltype, convalidated FROM pg_constraint
                 WHERE contype = 'f' AND conname = 'reports_reviewed_by_fkey'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    rs.getString("confdeltype") shouldBe "n"
                    rs.getBoolean("convalidated") shouldBe true
                }
            }
        }
    }

    "FK backfill — moderation_queue.resolved_by exists, validated, ON DELETE SET NULL" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.prepareStatement(
                """
                SELECT confdeltype, convalidated FROM pg_constraint
                 WHERE contype = 'f' AND conname = 'moderation_queue_resolved_by_fkey'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    rs.getString("confdeltype") shouldBe "n"
                    rs.getBoolean("convalidated") shouldBe true
                }
            }
        }
    }

    "FK backfill — chat_messages.redacted_by exists, validated, ON DELETE SET NULL" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.prepareStatement(
                """
                SELECT confdeltype, convalidated FROM pg_constraint
                 WHERE contype = 'f' AND conname = 'chat_messages_redacted_by_fkey'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    rs.getString("confdeltype") shouldBe "n"
                    rs.getBoolean("convalidated") shouldBe true
                }
            }
        }
    }

    "Comment replacement — reports.reviewed_by mentions admin_users + no longer deferred" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.prepareStatement(
                """
                SELECT col_description(c.oid, a.attnum)
                  FROM pg_class c JOIN pg_attribute a ON a.attrelid = c.oid
                 WHERE c.relname = 'reports' AND a.attname = 'reviewed_by'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    val comment = rs.getString(1)
                    comment shouldNotBe null
                    comment shouldContain "admin_users"
                    comment shouldContain "SET NULL"
                    comment.lowercase().contains("deferred") shouldBe false
                }
            }
        }
    }

    "Comment replacement — moderation_queue.resolved_by mentions admin_users + no longer deferred" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.prepareStatement(
                """
                SELECT col_description(c.oid, a.attnum)
                  FROM pg_class c JOIN pg_attribute a ON a.attrelid = c.oid
                 WHERE c.relname = 'moderation_queue' AND a.attname = 'resolved_by'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    val comment = rs.getString(1)
                    comment shouldNotBe null
                    comment shouldContain "admin_users"
                    comment shouldContain "SET NULL"
                    comment.lowercase().contains("deferred") shouldBe false
                }
            }
        }
    }

    "Comment replacement — chat_messages.redacted_by mentions admin_users + no longer deferred" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.prepareStatement(
                """
                SELECT col_description(c.oid, a.attnum)
                  FROM pg_class c JOIN pg_attribute a ON a.attrelid = c.oid
                 WHERE c.relname = 'chat_messages' AND a.attname = 'redacted_by'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    val comment = rs.getString(1)
                    comment shouldNotBe null
                    comment shouldContain "admin_users"
                    comment shouldContain "SET NULL"
                    comment.lowercase().contains("deferred") shouldBe false
                }
            }
        }
    }

    "chat_messages.embedded_post_edit_id remains deferred (no FK post-V16)" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.prepareStatement(
                """
                SELECT COUNT(*) FROM pg_constraint c
                  JOIN pg_class t ON t.oid = c.conrelid
                  JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
                 WHERE t.relname = 'chat_messages'
                   AND c.contype = 'f'
                   AND a.attname = 'embedded_post_edit_id'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) shouldBe 0
                }
            }
        }
    }

    "SET NULL behavior — admin hard-DELETE sets reports.reviewed_by to NULL" {
        DriverManager.getConnection(url, user, password).use { conn ->
            // Seed a fixture admin with no admin_actions_log refs (so hard-delete is allowed).
            val admin = seedAdmin(conn)
            // Seed a fixture reporter user.
            val reporter = UUID.randomUUID()
            val shortPrefix = "v16ref"
            val short = reporter.toString().replace("-", "").take(8)
            conn.prepareStatement(
                """
                INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix)
                VALUES (?, ?, 'V16 Fixture User', '1990-01-01', ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, reporter)
                ps.setString(2, "${shortPrefix}_user_$short")
                ps.setString(3, "v16$short".take(8))
                ps.executeUpdate()
            }
            // Seed a posts row to target.
            val postId = UUID.randomUUID()
            conn.prepareStatement(
                """
                INSERT INTO posts (id, author_id, content, actual_location, display_location)
                VALUES (?, ?, 'V16 fixture', ST_SetSRID(ST_MakePoint(106.8, -6.2), 4326)::geography,
                                                ST_SetSRID(ST_MakePoint(106.8, -6.2), 4326)::geography)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, postId)
                ps.setObject(2, reporter)
                ps.executeUpdate()
            }
            // Seed a reports row referencing the admin in reviewed_by.
            val reportId = UUID.randomUUID()
            conn.prepareStatement(
                """
                INSERT INTO reports (id, reporter_id, target_type, target_id, reason_category, reviewed_by, reviewed_at)
                VALUES (?, ?, 'post', ?, 'spam', ?, NOW())
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, reportId)
                ps.setObject(2, reporter)
                ps.setObject(3, postId)
                ps.setObject(4, admin)
                ps.executeUpdate()
            }
            try {
                // Hard-delete the admin — no admin_actions_log refs block this.
                conn.prepareStatement("DELETE FROM admin_users WHERE id = ?").use { ps ->
                    ps.setObject(1, admin)
                    ps.executeUpdate()
                }
                // Assert reviewed_by is now NULL on the report row.
                conn.prepareStatement("SELECT reviewed_by FROM reports WHERE id = ?").use { ps ->
                    ps.setObject(1, reportId)
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getObject("reviewed_by") shouldBe null
                    }
                }
            } finally {
                conn.prepareStatement("DELETE FROM reports WHERE id = ?").use { ps ->
                    ps.setObject(1, reportId)
                    ps.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM posts WHERE id = ?").use { ps ->
                    ps.setObject(1, postId)
                    ps.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM users WHERE id = ?").use { ps ->
                    ps.setObject(1, reporter)
                    ps.executeUpdate()
                }
            }
        }
    }
})
