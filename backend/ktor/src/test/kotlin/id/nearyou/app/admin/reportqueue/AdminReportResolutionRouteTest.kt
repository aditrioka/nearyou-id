package id.nearyou.app.admin.reportqueue

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuthProvider
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import id.nearyou.app.admin.auth.AdminCsrfGate
import id.nearyou.app.admin.moderation.UserModerationTestSupport
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import java.time.Instant
import java.util.UUID

/**
 * Route-level integration tests for `adminReportResolution` — the HTTP auth /
 * CSRF / role gate + gate ORDER, the malformed + out-of-scope input handling,
 * the in-row resolution controls + escaping, the no-JS 303 / HTMX-fragment
 * dual-mode, the filter-preserving redirect, and the GET-listing-unchanged +
 * 405 + deferred-filter guards (`admin-report-queue-resolution-actions`,
 * tasks.md Section 4: 4.1 (HTTP half), 4.7, 4.9 (malformed/out-of-scope), 4.11,
 * 4.12, 4.13). The repository-internal transaction semantics (enforcement,
 * atomicity, idempotency, no-cascade) are covered by
 * [ReportResolutionRepositoryTest].
 *
 * DB-backed; tagged `database`. Uses the `AdminAuthTestSupport` harness
 * (`withAdminApp` mounts the full admin module, including these routes) +
 * `ReportQueueTestSupport` / `ReportResolutionTestSupport` for fixtures + reads.
 */
@Tags("database")
class AdminReportResolutionRouteTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val seededIds = mutableListOf<UUID>()
    val seededAdmins = mutableListOf<UUID>()
    afterEach {
        ReportQueueTestSupport.cleanup(dataSource, seededIds)
        seededIds.clear()
        seededAdmins.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seededAdmins.clear()
    }

    fun seedAdmin(role: String = "owner"): AdminAuthTestSupport.SeededAdmin =
        AdminAuthTestSupport.seedAdmin(dataSource, role = role).also { seededAdmins += it.id }

    fun seedUser(): UUID = ReportResolutionTestSupport.seedUser(dataSource).also { seededIds += it }

    fun seedPost(authorId: UUID): UUID = ReportQueueTestSupport.seedPost(dataSource, authorId).also { seededIds += it }

    fun seedQueue(
        targetType: String,
        targetId: UUID,
    ): UUID = ReportQueueTestSupport.seedQueueRow(dataSource, targetType, targetId, trigger = "admin_flag")

    fun seedReport(
        reporterId: UUID,
        targetType: String,
        targetId: UUID,
        reasonNote: String? = null,
    ): UUID = ReportQueueTestSupport.seedReport(dataSource, reporterId, targetType, targetId, BASE, reasonNote = reasonNote)

    fun cookie(token: String) = "${AdminAuthProvider.COOKIE_NAME}=$token"

    fun formBody(vararg pairs: Pair<String, String>): String = pairs.toList().formUrlEncode()

    fun queueStatus(queueId: UUID) = ReportResolutionTestSupport.loadQueue(dataSource, queueId).status

    // ============================ 4.1 (HTTP) ===================================

    "4.1-route a no-JS report resolution applies and records reviewed_by = the session admin" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val report = seedReport(seedUser(), "user", seedUser())
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/reports/$report/resolve") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("_csrf" to AdminAuthTestSupport.csrfFor(token), "decision" to "actioned"))
                }
            res.status shouldBe HttpStatusCode.SeeOther
        }
        val state = ReportResolutionTestSupport.loadReport(dataSource, report)
        state.status shouldBe "actioned"
        state.reviewedBy shouldBe admin.id
    }

    // ============================ 4.7 gating ===================================

    "4.7a unauthenticated resolution POST → 302 /admin/login, no write" {
        val author = seedUser()
        val queue = seedQueue("post", seedPost(author))
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.post("/admin/moderation-queue/$queue/resolve")
            res.status shouldBe HttpStatusCode.Found
            res.headers[HttpHeaders.Location] shouldBe "/admin/login"
        }
        queueStatus(queue) shouldBe "pending"
    }

    "4.7b resolution POST without a CSRF token → 403 + admin_csrf_violation, no write" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val queue = seedQueue("post", seedPost(seedUser()))
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/moderation-queue/$queue/resolve") {
                    header(HttpHeaders.Cookie, cookie(token))
                }
            res.status shouldBe HttpStatusCode.Forbidden
        }
        queueStatus(queue) shouldBe "pending"
        AdminAuthTestSupport.latestAuditRows(dataSource, admin.id).any { it.actionType == "admin_csrf_violation" } shouldBe true
    }

    "4.7c invalid CSRF token → 403, no write" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val queue = seedQueue("post", seedPost(seedUser()))
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/moderation-queue/$queue/resolve") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, "wrong-token-value")
                }
            res.status shouldBe HttpStatusCode.Forbidden
        }
        queueStatus(queue) shouldBe "pending"
    }

    "4.7d CSRF is validated BEFORE the role gate (read_only + bad CSRF → the CSRF rejection)" {
        val admin = seedAdmin(role = "read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val queue = seedQueue("post", seedPost(seedUser()))
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/moderation-queue/$queue/resolve") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, "wrong-token-value")
                }
            res.status shouldBe HttpStatusCode.Forbidden
        }
        // The CSRF violation was audited — proving CSRF ran before the role gate.
        AdminAuthTestSupport.latestAuditRows(dataSource, admin.id).any { it.actionType == "admin_csrf_violation" } shouldBe true
        queueStatus(queue) shouldBe "pending"
    }

    "4.7e read_only with a VALID CSRF token → role-gated (403), no write, no CSRF violation" {
        val admin = seedAdmin(role = "read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val queue = seedQueue("post", seedPost(seedUser()))
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/moderation-queue/$queue/resolve") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldBe HttpStatusCode.Forbidden
        }
        queueStatus(queue) shouldBe "pending"
        // The role gate (not CSRF) rejected — no admin_csrf_violation row.
        AdminAuthTestSupport.latestAuditRows(dataSource, admin.id).any { it.actionType == "admin_csrf_violation" } shouldBe false
    }

    // ============================ 4.9 malformed + out-of-scope (HTTP) ==========

    "4.9a malformed report id → 400 (not 500), no write" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/reports/not-a-uuid/resolve") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldNotBe HttpStatusCode.InternalServerError
            res.status shouldBe HttpStatusCode.BadRequest
        }
    }

    "4.9b out-of-scope resolution values (delete / accept_flagged_username / garbage) are rejected by the allowlist (not 5xx), no write" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val queue = seedQueue("post", seedPost(seedUser()))
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            listOf("delete", "accept_flagged_username", "reject_flagged_username", "totally-bogus").forEach { bad ->
                val res =
                    client.post("/admin/moderation-queue/$queue/resolve") {
                        header(HttpHeaders.Cookie, cookie(token))
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(formBody("_csrf" to AdminAuthTestSupport.csrfFor(token), "resolution" to bad))
                    }
                res.status shouldNotBe HttpStatusCode.InternalServerError
                res.status shouldBe HttpStatusCode.BadRequest
            }
        }
        queueStatus(queue) shouldBe "pending"
        UserModerationTestSupport.auditRowsForTarget(dataSource, queue, "moderation_queue_resolved") shouldHaveSize 0
    }

    // ============================ 4.11 in-row controls =========================

    "4.11a a resolvable row renders the resolution forms with a CSRF hidden field" {
        val token =
            run {
                val admin = seedAdmin()
                AdminAuthTestSupport.seedSession(dataSource, admin.id)
            }
        val author = seedUser()
        val post = seedPost(author)
        val report = seedReport(seedUser(), "post", post, reasonNote = "rr-411a")
        val queue = seedQueue("post", post)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/reports") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "rr-411a"
            body shouldContain "action=\"/admin/reports/$report/resolve\""
            body shouldContain "action=\"/admin/moderation-queue/$queue/resolve\""
            body shouldContain "name=\"_csrf\""
            body shouldContain "name=\"decision\""
            body shouldContain "name=\"resolution\""
        }
    }

    "4.11b rendered control page HTML-escapes a reason_note containing a script tag" {
        val token =
            run {
                val admin = seedAdmin()
                AdminAuthTestSupport.seedSession(dataSource, admin.id)
            }
        seedReport(seedUser(), "user", seedUser(), reasonNote = "<script>alert(1)</script>")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/reports") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "&lt;script&gt;"
            body shouldNotContain "<script>alert(1)</script>"
        }
    }

    "4.11c a successful no-JS resolution → 303 back to /admin/reports preserving the active filters" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val report = seedReport(seedUser(), "user", seedUser())
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/reports/$report/resolve") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(
                        formBody(
                            "_csrf" to AdminAuthTestSupport.csrfFor(token),
                            "decision" to "actioned",
                            "status" to "pending",
                        ),
                    )
                }
            res.status shouldBe HttpStatusCode.SeeOther
            val location = res.headers[HttpHeaders.Location]
            location.shouldStartWithReports()
            location!! shouldContain "status=pending"
        }
    }

    "4.11d an HX-Request resolution returns the table fragment only (no full-page wrapper)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val report = seedReport(seedUser(), "user", seedUser())
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/reports/$report/resolve") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("HX-Request", "true")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("_csrf" to AdminAuthTestSupport.csrfFor(token), "decision" to "dismissed"))
                }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "id=\"reports-table\""
            body shouldNotContain "<html"
            body shouldNotContain "<header>"
        }
        ReportResolutionTestSupport.loadReport(dataSource, report).status shouldBe "dismissed"
    }

    // ============================ 4.12 GET unchanged + 405 =====================

    "4.12a GET /admin/reports writes no audit row and mutates no reports / moderation_queue row" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val post = seedPost(seedUser())
        seedReport(seedUser(), "post", post)
        val queue = seedQueue("post", post)

        val auditBefore = AdminAuthTestSupport.countAuditRows(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.get("/admin/reports") { header(HttpHeaders.Cookie, cookie(token)) }
        }
        AdminAuthTestSupport.countAuditRows(dataSource, admin.id) shouldBe auditBefore
        queueStatus(queue) shouldBe "pending"
    }

    "4.12b bare POST /admin/reports → 405 (only GET + the /{id}/resolve sub-route are wired)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/reports") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldBe HttpStatusCode.MethodNotAllowed
        }
    }

    // ============================ 4.13 deferred edit-history filter =============

    "4.13 GET /admin/reports?has_edit_history=true → 200, parameter ignored" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedReport(seedUser(), "user", seedUser(), reasonNote = "rr-413")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/reports?has_edit_history=true") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "rr-413"
        }
    }
})

private val BASE: Instant = Instant.parse("2092-01-01T00:00:00Z")

private fun String?.shouldStartWithReports() {
    (this != null && this.startsWith("/admin/reports")) shouldBe true
}
