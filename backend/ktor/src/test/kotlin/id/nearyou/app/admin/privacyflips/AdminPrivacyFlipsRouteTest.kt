package id.nearyou.app.admin.privacyflips

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuthProvider
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.UUID

/**
 * Integration tests for the `GET /admin/privacy-flips` route — the viewer of
 * the `admin-privacy-flip-monitor` capability (tasks.md Section 6). DB-backed;
 * tagged `database`. Uses the `AdminAuthTestSupport` harness for the
 * authenticated-session wiring + `PrivacyFlipsTestSupport` for `users` seeding.
 *
 * Asserts the auth gate, render + classification, HTMX/plain dual-mode, lenient
 * status/q filtering, the boundary fencepost, load-bearing output escaping
 * (incl. the deep-link href), the count summary, the read-only/no-mutation
 * guard, any-admin-role access, and the empty state.
 *
 * The route is wired with a FIXED clock (`evalInstant`) via the test app
 * builder so the IN_WINDOW / OVERDUE boundary is deterministic.
 */
@Tags("database")
class AdminPrivacyFlipsRouteTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val eval = Instant.parse("2026-06-13T12:00:00Z")
    val seededUsers = mutableListOf<UUID>()
    val seededAdmins = mutableListOf<UUID>()
    afterEach {
        seededUsers.forEach { PrivacyFlipsTestSupport.deleteUser(dataSource, it) }
        seededUsers.clear()
        seededAdmins.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seededAdmins.clear()
    }

    fun seedAdmin(role: String = "owner") = AdminAuthTestSupport.seedAdmin(dataSource, role = role).also { seededAdmins.add(it.id) }

    fun seedUser(
        username: String,
        scheduledAt: Instant?,
        isPrivate: Boolean = true,
        deletedAt: Instant? = null,
        displayName: String = "Flip Tester",
    ): UUID =
        PrivacyFlipsTestSupport.seedUser(
            dataSource,
            username,
            displayName,
            scheduledAt,
            isPrivate,
            deletedAt,
        ).also { seededUsers.add(it) }

    fun cookie(token: String) = "${AdminAuthProvider.COOKIE_NAME}=$token"

    "6.1 — authenticated GET renders the table with a pending-flip user + base layout" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedUser("budi_kopi", eval.plusSeconds(3600))

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            val res = client.get("/admin/privacy-flips") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "budi_kopi"
            body shouldContain "IN_WINDOW"
            body shouldContain "<header>"
            body shouldContain "<nav>"
        }
    }

    "6.1 — unauthenticated GET redirects (302) to /admin/login" {
        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            val res = client.get("/admin/privacy-flips")
            res.status shouldBe HttpStatusCode.Found
            res.headers[HttpHeaders.Location] shouldBe "/admin/login"
        }
    }

    "6.3 — classification: future → IN_WINDOW, past → OVERDUE, both appear" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedUser("flip_future", eval.plusSeconds(47 * 3600))
        seedUser("flip_overdue", eval.minusSeconds(25 * 3600))

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            val body =
                client.get("/admin/privacy-flips") {
                    header(HttpHeaders.Cookie, cookie(token))
                }.bodyAsText()
            body shouldContain "flip_future"
            body shouldContain "flip_overdue"
            body shouldContain "IN_WINDOW"
            body shouldContain "OVERDUE"
        }
    }

    "6.3c — a flip scheduled exactly at eval is OVERDUE and obeys the status filter" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedUser("flip_boundary", eval)

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            client.get("/admin/privacy-flips?status=overdue") {
                header(HttpHeaders.Cookie, cookie(token))
            }.bodyAsText() shouldContain "flip_boundary"
            client.get("/admin/privacy-flips?status=in_window") {
                header(HttpHeaders.Cookie, cookie(token))
            }.bodyAsText() shouldNotContain "flip_boundary"
        }
    }

    "6.2 — soft-deleted user with a pending flip is still surfaced" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedUser("flip_soft_deleted", eval.minusSeconds(3600), deletedAt = eval.minusSeconds(60))

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            client.get("/admin/privacy-flips") {
                header(HttpHeaders.Cookie, cookie(token))
            }.bodyAsText() shouldContain "flip_soft_deleted"
        }
    }

    "6.10 — HX-Request returns only the table fragment (no full-page layout)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedUser("flip_frag", eval.plusSeconds(3600))

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            val res =
                client.get("/admin/privacy-flips") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("HX-Request", "true")
                }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "id=\"privacy-flips-table\""
            body shouldNotContain "<html"
            body shouldNotContain "<header>"
        }
    }

    "6.10 — plain GET with a status filter returns the full page reflecting the filter" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedUser("flip_ow", eval.minusSeconds(3600)) // overdue
        seedUser("flip_iw", eval.plusSeconds(3600)) // in_window

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            val body =
                client.get("/admin/privacy-flips?status=overdue") {
                    header(HttpHeaders.Cookie, cookie(token))
                }.bodyAsText()
            body shouldContain "id=\"privacy-flips-table\""
            body shouldContain "<header>"
            body shouldContain "flip_ow"
            body shouldNotContain "flip_iw"
        }
    }

    "6.7 — q lookup by username; empty q is ignored (unfiltered)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedUser("budi_kopi", eval.plusSeconds(3600))
        seedUser("rina_sore", eval.plusSeconds(3600))

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            val filtered =
                client.get("/admin/privacy-flips?q=budi_kopi") {
                    header(HttpHeaders.Cookie, cookie(token))
                }.bodyAsText()
            filtered shouldContain "budi_kopi"
            filtered shouldNotContain "rina_sore"

            // Empty q → unfiltered (both users visible), not an empty-username match.
            val emptyQ =
                client.get("/admin/privacy-flips?q=") {
                    header(HttpHeaders.Cookie, cookie(token))
                }.bodyAsText()
            emptyQ shouldContain "budi_kopi"
            emptyQ shouldContain "rina_sore"
        }
    }

    "6.9 — SQL-metacharacter q is a literal (no injection); over-long q → empty state" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedUser("flip_safe", eval.plusSeconds(3600))

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            val inj =
                client.get("/admin/privacy-flips?q=%27%3B+DROP+TABLE+users%3B--") {
                    header(HttpHeaders.Cookie, cookie(token))
                }
            inj.status shouldBe HttpStatusCode.OK
            inj.bodyAsText() shouldContain "No users match"
            // users table still queryable afterward
            PrivacyFlipsTestSupport.pendingFlipCount(dataSource) // throws if table dropped

            val tooLong =
                client.get("/admin/privacy-flips?q=${"x".repeat(80)}") {
                    header(HttpHeaders.Cookie, cookie(token))
                }
            tooLong.status shouldBe HttpStatusCode.OK
            tooLong.bodyAsText() shouldContain "No users match"
        }
    }

    "6.6 — unrecognized status is ignored while q still applies" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedUser("flip_keep", eval.plusSeconds(3600))
        seedUser("flip_drop", eval.plusSeconds(3600))

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            val body =
                client.get("/admin/privacy-flips?status=bogus&q=flip_keep") {
                    header(HttpHeaders.Cookie, cookie(token))
                }.bodyAsText()
            body shouldContain "flip_keep"
            body shouldNotContain "flip_drop"
        }
    }

    "6.8 — count summary reports in-window vs overdue for the scope" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedUser("flip_sum_o", eval.minusSeconds(3600))
        seedUser("flip_sum_i", eval.plusSeconds(3600))

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            val body =
                client.get("/admin/privacy-flips") {
                    header(HttpHeaders.Cookie, cookie(token))
                }.bodyAsText()
            body shouldContain "overdue:"
            body shouldContain "in_window:"
        }
    }

    "6.11 — display_name markup is escaped, never a live tag" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedUser("flip_xss", eval.plusSeconds(3600), displayName = "<script>alert(1)</script>")

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            val body =
                client.get("/admin/privacy-flips") {
                    header(HttpHeaders.Cookie, cookie(token))
                }.bodyAsText()
            body shouldContain "&lt;script&gt;"
            body shouldNotContain "<script>alert(1)</script>"
        }
    }

    "6.12 — username links to the shipped /admin/users?q= lookup" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedUser("budi_kopi", eval.plusSeconds(3600))

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            val body =
                client.get("/admin/privacy-flips") {
                    header(HttpHeaders.Cookie, cookie(token))
                }.bodyAsText()
            body shouldContain "/admin/users?q=budi_kopi"
            body shouldNotContain "/admin/users/" // not the in-flight {id} profile route
        }
    }

    "6.13 — POST is not wired (405); serving mutates nothing" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedUser("flip_ro", eval.minusSeconds(3600))

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            val before = PrivacyFlipsTestSupport.pendingFlipCount(dataSource)
            client.get("/admin/privacy-flips") { header(HttpHeaders.Cookie, cookie(token)) }
            val post = client.post("/admin/privacy-flips") { header(HttpHeaders.Cookie, cookie(token)) }
            post.status shouldBe HttpStatusCode.MethodNotAllowed
            PrivacyFlipsTestSupport.pendingFlipCount(dataSource) shouldBe before
        }
    }

    "6.14 — a read_only admin can view the monitor" {
        val admin = seedAdmin(role = "read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedUser("flip_ro_view", eval.plusSeconds(3600))

        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            val res = client.get("/admin/privacy-flips") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "flip_ro_view"
        }
    }

    "6.15 — empty state renders in both the full page and the HTMX fragment" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        // No pending-flip user matches this q.
        AdminAuthTestSupport.withAdminApp(dataSource, clock = { eval }) { client ->
            val full =
                client.get("/admin/privacy-flips?q=nobody_here") {
                    header(HttpHeaders.Cookie, cookie(token))
                }.bodyAsText()
            full shouldContain "No users match"

            val frag =
                client.get("/admin/privacy-flips?q=nobody_here") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("HX-Request", "true")
                }.bodyAsText()
            frag shouldContain "No users match"
            frag shouldContain "id=\"privacy-flips-table\""
        }
    }
})
