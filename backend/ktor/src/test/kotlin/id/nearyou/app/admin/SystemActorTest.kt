package id.nearyou.app.admin

import id.nearyou.app.admin.actionslog.ActionsLogTestSupport
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import id.nearyou.app.admin.auth.AdminLoginRoutes
import id.nearyou.app.admin.auth.PasswordHasher
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import java.time.Instant
import java.util.UUID

/**
 * Integration tests for the `system-actor` capability — the deterministic
 * `system` sentinel admin user seeded by `V18__seed_system_actor.sql`.
 *
 * DB-backed; tagged `database` (CI excludes by default). Run locally with
 * `DB_URL` / `DB_USER` / `DB_PASSWORD` (defaults: Docker Compose dev Postgres
 * at localhost:5433). The sentinel row is created by the V18 migration, so the
 * test DB must be migrated (the standard local + CI integration setup).
 */
@Tags("database")
class SystemActorTest : StringSpec({

    val dataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val sentinelId = SuspensionUnbanWorker.SYSTEM_ACTOR_ID

    "4.7 sentinel cannot authenticate via POST /admin/login — no session, no-enumeration, inactive_admin audit row" {
        try {
            AdminAuthTestSupport.withAdminApp(dataSource) { client ->
                val res =
                    client.post("/admin/login") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(
                            listOf(
                                "email" to "system@system.nearyou.invalid",
                                "password" to "anything",
                                "totp" to "000000",
                            ).formUrlEncode(),
                        )
                    }
                res.status shouldBe HttpStatusCode.OK
                res.bodyAsText() shouldContain AdminLoginRoutes.GENERIC_ERROR_MESSAGE
                AdminAuthTestSupport.countSessions(dataSource, sentinelId) shouldBe 0
                // The sentinel is an existing-but-inactive admin, so the login flow
                // takes the inactive-admin branch and writes a benign inactive_admin
                // failure row attributed to the sentinel (design D2 side effect).
                val rows = AdminAuthTestSupport.latestAuditRows(dataSource, sentinelId)
                rows.any { it.actionType == "admin_login_failure" && it.reason == "inactive_admin" } shouldBe true
            }
        } finally {
            // Clean only the login-failure rows this test created (NOT the worker's
            // system_unban_applied rows, which use the same admin_id).
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "DELETE FROM admin_actions_log WHERE admin_id = ? AND action_type = 'admin_login_failure'",
                ).use { ps ->
                    ps.setObject(1, sentinelId)
                    ps.executeUpdate()
                }
            }
        }
    }

    "4.8 sentinel password_hash is well-formed and PasswordHasher.verify returns false (never throws)" {
        val storedHash =
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT password_hash FROM admin_users WHERE id = ?").use { ps ->
                    ps.setObject(1, sentinelId)
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getString("password_hash")
                    }
                }
            }
        // Well-formed Argon2id PHC string -> verify parses it (no BadParametersException)
        // and returns false for every candidate (plaintext is a discarded secret).
        PasswordHasher.verify("anything", storedHash) shouldBe false
        PasswordHasher.verify("", storedHash) shouldBe false
        PasswordHasher.verify("System Actor", storedHash) shouldBe false
    }

    "4.9 sentinel cannot be hard-deleted while it owns admin_actions_log rows" {
        val auditId =
            ActionsLogTestSupport.seedActionLog(
                dataSource = dataSource,
                adminId = sentinelId,
                actionType = "system_unban_applied",
                createdAt = Instant.now(),
                targetType = "user",
                targetId = UUID.randomUUID().toString(),
                reason = "suspension_elapsed",
            )
        try {
            shouldThrow<Exception> {
                dataSource.connection.use { conn ->
                    conn.prepareStatement("DELETE FROM admin_users WHERE id = ?").use { ps ->
                        ps.setObject(1, sentinelId)
                        ps.executeUpdate()
                    }
                }
            }
            // Sentinel still present.
            val stillExists =
                dataSource.connection.use { conn ->
                    conn.prepareStatement("SELECT count(*) FROM admin_users WHERE id = ?").use { ps ->
                        ps.setObject(1, sentinelId)
                        ps.executeQuery().use { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                    }
                }
            stillExists shouldBe 1
        } finally {
            dataSource.connection.use { conn ->
                conn.prepareStatement("DELETE FROM admin_actions_log WHERE id = ?").use { ps ->
                    ps.setObject(1, auditId)
                    ps.executeUpdate()
                }
            }
        }
    }

    "4.10 sentinel seed is idempotent — re-running the V18 INSERT leaves exactly one row" {
        // Re-execute the same ON CONFLICT (id) DO NOTHING insert shape as V18.
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO admin_users (id, email, display_name, password_hash, role, is_active, webauthn_enrolled)
                VALUES (?, 'system@system.nearyou.invalid', 'System Actor', 'placeholder', 'read_only', FALSE, FALSE)
                ON CONFLICT (id) DO NOTHING
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, sentinelId)
                ps.executeUpdate() // returns 0 rows affected; MUST NOT throw
            }
        }
        val count =
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT count(*) FROM admin_users WHERE id = ?").use { ps ->
                    ps.setObject(1, sentinelId)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }
        count shouldBe 1
        // And it is still the inactive, read-only sentinel (the re-insert was a no-op,
        // so the placeholder password_hash above did NOT overwrite the real one).
        val (active, role) =
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT is_active, role FROM admin_users WHERE id = ?").use { ps ->
                    ps.setObject(1, sentinelId)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getBoolean("is_active") to rs.getString("role")
                    }
                }
            }
        active shouldBe false
        role shouldBe "read_only"
    }
})
