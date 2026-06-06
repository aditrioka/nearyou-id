package id.nearyou.app.admin.reportqueue

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuthProvider
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import id.nearyou.app.admin.auth.AdminCsrfGate
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
 * Integration tests for the `GET /admin/reports` route — the HTTP/render half
 * of the `admin-report-queue` capability (tasks.md Section 5). DB-backed;
 * tagged `database`. Uses the `AdminAuthTestSupport` harness for the
 * authenticated-session wiring (`withAdminApp` mounts the full admin module,
 * including this route) + `ReportQueueTestSupport` for report seeding.
 *
 * The queue is a GLOBAL list, so render assertions key on unique `reason_note`
 * markers (robust to any pre-existing `reports` rows); the page-size-sensitive
 * pagination tests isolate their rows in a disjoint far-future date window via
 * the `from` / `to` filter.
 */
@Tags("database")
class ReportQueueRouteTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val seeded = mutableListOf<UUID>()
    val seededAdmins = mutableListOf<UUID>()
    afterEach {
        ReportQueueTestSupport.cleanup(dataSource, seeded)
        seeded.clear()
        seededAdmins.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seededAdmins.clear()
    }

    fun user(username: String? = null): UUID = ReportQueueTestSupport.seedUser(dataSource, username).also { seeded.add(it) }

    /** Seed an admin + session and return the session-cookie token. */
    fun authToken(role: String = "owner"): String {
        val admin = AdminAuthTestSupport.seedAdmin(dataSource, role = role).also { seededAdmins.add(it.id) }
        return AdminAuthTestSupport.seedSession(dataSource, admin.id)
    }

    fun cookie(token: String) = "${AdminAuthProvider.COOKIE_NAME}=$token"

    val base = Instant.parse("2090-01-01T00:00:00Z")

    "5.1 — authenticated GET renders the table (reason_category + status) + base layout" {
        val token = authToken()
        val reporter = user()
        ReportQueueTestSupport.seedReport(
            dataSource,
            reporter,
            "user",
            user(),
            base,
            reasonCategory = "harassment",
            status = "pending",
            reasonNote = "rq-5-1-marker",
        )
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/reports") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "harassment"
            body shouldContain "pending"
            body shouldContain "rq-5-1-marker"
            body shouldContain "<header>" // full page extends layout
            body shouldContain "<nav>"
        }
    }

    "5.2 — unauthenticated GET redirects (302) to /admin/login, no content" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/reports")
            res.status shouldBe HttpStatusCode.Found
            res.headers[HttpHeaders.Location] shouldBe "/admin/login"
            res.bodyAsText() shouldNotContain "Report Queue"
        }
    }

    "5.3 — rows render newest-first (created_at DESC)" {
        val token = authToken()
        val reporter = user()
        ReportQueueTestSupport.seedReport(dataSource, reporter, "user", user(), base, reasonNote = "rq53-old")
        ReportQueueTestSupport.seedReport(dataSource, reporter, "user", user(), base.plusSeconds(60), reasonNote = "rq53-mid")
        ReportQueueTestSupport.seedReport(dataSource, reporter, "user", user(), base.plusSeconds(120), reasonNote = "rq53-new")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/reports") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body.indexOf("rq53-new") shouldBe minOf(body.indexOf("rq53-new"), body.indexOf("rq53-mid"), body.indexOf("rq53-old"))
            (body.indexOf("rq53-new") < body.indexOf("rq53-mid")) shouldBe true
            (body.indexOf("rq53-mid") < body.indexOf("rq53-old")) shouldBe true
        }
    }

    "5.4 — no-match filter returns 200 with an empty-state message" {
        val token = authToken()
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/reports?status=matches_nothing_at_all") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "No reports match"
        }
    }

    "5.5a — page capped at fixed size + older control; following the cursor returns the next-older, non-overlapping page" {
        val token = authToken()
        val reporter = user()
        // 60 reports within one far-future UTC day, markers evt-00..evt-59 (evt-59 newest).
        // target_id has no FK, so random ids keep the UNIQUE(reporter,type,target) distinct.
        val day = Instant.parse("2091-03-10T00:00:00Z")
        repeat(60) { i ->
            ReportQueueTestSupport.seedReport(
                dataSource,
                reporter,
                "user",
                UUID.randomUUID(),
                day.plusSeconds(i.toLong()),
                reasonNote = "evt-%02d".format(i),
            )
        }
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val page1 =
                client.get("/admin/reports?from=2091-03-10&to=2091-03-10") {
                    header(HttpHeaders.Cookie, cookie(token))
                }.bodyAsText()
            page1 shouldContain "evt-59"
            page1 shouldContain "evt-10"
            page1 shouldNotContain "evt-09"
            page1 shouldContain "pagination-older"

            val olderUrl =
                Regex("class=\"pagination-older\"\\s+href=\"([^\"]+)\"")
                    .find(page1)!!
                    .groupValues[1]
                    .replace("&amp;", "&")
            val page2 = client.get(olderUrl) { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            page2 shouldContain "evt-09"
            page2 shouldContain "evt-00"
            page2 shouldNotContain "evt-59"
            page2 shouldNotContain "evt-10"
        }
    }

    "5.5b — malformed cursor falls back to the newest page (no error)" {
        val token = authToken()
        val reporter = user()
        ReportQueueTestSupport.seedReport(dataSource, reporter, "user", user(), base, reasonNote = "rq55b-marker")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/reports?cursor=not-a-valid-cursor") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "rq55b-marker"
        }
    }

    "5.5c — a short page (fewer than page size) omits the older control" {
        val token = authToken()
        val reporter = user()
        ReportQueueTestSupport.seedReport(dataSource, reporter, "user", user(), Instant.parse("2091-04-10T00:00:00Z"), reasonNote = "rq55c")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.get("/admin/reports?from=2091-04-10&to=2091-04-10") {
                    header(HttpHeaders.Cookie, cookie(token))
                }.bodyAsText()
            body shouldContain "rq55c"
            body shouldNotContain "pagination-older"
        }
    }

    "5.6 — status / target_type / reason_category filters narrow; compose with AND" {
        val token = authToken()
        val reporter = user()
        ReportQueueTestSupport.seedReport(
            dataSource,
            reporter,
            "post",
            user(),
            base.plusSeconds(1),
            reasonCategory = "spam",
            status = "pending",
            reasonNote = "rq56-pp",
        )
        ReportQueueTestSupport.seedReport(
            dataSource,
            reporter,
            "user",
            user(),
            base.plusSeconds(2),
            reasonCategory = "harassment",
            status = "dismissed",
            reasonNote = "rq56-uh",
        )
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val byStatus = client.get("/admin/reports?status=pending") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            byStatus shouldContain "rq56-pp"
            byStatus shouldNotContain "rq56-uh"

            val byType = client.get("/admin/reports?target_type=user") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            byType shouldContain "rq56-uh"
            byType shouldNotContain "rq56-pp"

            val anded =
                client.get("/admin/reports?status=pending&target_type=post") {
                    header(HttpHeaders.Cookie, cookie(token))
                }.bodyAsText()
            anded shouldContain "rq56-pp"
            anded shouldNotContain "rq56-uh"
        }
    }

    "5.7 — trigger filter returns only reports whose target has a matching moderation_queue row" {
        val token = authToken()
        val reporter = user()
        val withQueue = user()
        val withoutQueue = user()
        ReportQueueTestSupport.seedReport(dataSource, reporter, "user", withQueue, base.plusSeconds(2), reasonNote = "rq57-with")
        ReportQueueTestSupport.seedReport(dataSource, reporter, "user", withoutQueue, base.plusSeconds(1), reasonNote = "rq57-without")
        ReportQueueTestSupport.seedQueueRow(dataSource, "user", withQueue, trigger = "auto_hide_3_reports")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.get("/admin/reports?trigger=auto_hide_3_reports") {
                    header(HttpHeaders.Cookie, cookie(token))
                }.bodyAsText()
            body shouldContain "rq57-with"
            body shouldNotContain "rq57-without"
        }
    }

    "5.8 — a status value carrying SQL is bound as a parameter; the reports table survives" {
        val token = authToken()
        val reporter = user()
        ReportQueueTestSupport.seedReport(dataSource, reporter, "user", user(), base, reasonNote = "rq58")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/reports?status=pending'); DROP TABLE reports;--") {
                    header(HttpHeaders.Cookie, cookie(token))
                }
            res.status shouldBe HttpStatusCode.OK
        }
        ReportQueueTestSupport.reportsTableExists(dataSource) shouldBe true
    }

    "5.9 — moderation_queue context renders the representative trigger; a report without a queue row renders fine" {
        val token = authToken()
        val reporter = user()
        val tWith = user()
        ReportQueueTestSupport.seedReport(dataSource, reporter, "user", tWith, base.plusSeconds(2), reasonNote = "rq59-with")
        ReportQueueTestSupport.seedQueueRow(dataSource, "user", tWith, trigger = "auto_hide_3_reports", priority = 5)
        ReportQueueTestSupport.seedReport(dataSource, reporter, "user", user(), base.plusSeconds(1), reasonNote = "rq59-without")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/reports") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "rq59-with"
            body shouldContain "auto_hide_3_reports"
            body shouldContain "rq59-without" // renders without crashing
        }
    }

    "5.10 — deep-links resolve per target_type; a hard-deleted target renders without a link" {
        val token = authToken()
        val reporter = user()

        val targetUser = user()
        ReportQueueTestSupport.seedReport(dataSource, reporter, "user", targetUser, base.plusSeconds(5))

        val postAuthor = user()
        val postId = ReportQueueTestSupport.seedPost(dataSource, postAuthor).also { seeded.add(it) }
        ReportQueueTestSupport.seedReport(dataSource, reporter, "post", postId, base.plusSeconds(4))

        val replyAuthor = user()
        val replyId = ReportQueueTestSupport.seedReply(dataSource, postId, replyAuthor).also { seeded.add(it) }
        ReportQueueTestSupport.seedReport(dataSource, reporter, "reply", replyId, base.plusSeconds(3))

        val sender = user()
        val msgId = ReportQueueTestSupport.seedChatMessage(dataSource, sender).also { seeded.add(it) }
        ReportQueueTestSupport.seedReport(dataSource, reporter, "chat_message", msgId, base.plusSeconds(2))

        val ghost = ReportQueueTestSupport.seedUser(dataSource).also { seeded.add(it) }
        ReportQueueTestSupport.seedReport(dataSource, reporter, "user", ghost, base.plusSeconds(1))
        ReportQueueTestSupport.hardDeleteUser(dataSource, ghost)

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/reports") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "/admin/users?q=$targetUser"
            body shouldContain "/admin/users?q=$postAuthor"
            body shouldContain "/admin/users?q=$replyAuthor"
            body shouldContain "/admin/users?q=$sender"
            // hard-deleted target → no link to it, but the target_id is still rendered as text
            body shouldNotContain "/admin/users?q=$ghost"
            body shouldContain ghost.toString()
        }
    }

    "5.11 — reason_note containing <script> renders escaped, not as a live tag" {
        val token = authToken()
        val reporter = user()
        ReportQueueTestSupport.seedReport(
            dataSource,
            reporter,
            "user",
            user(),
            base,
            reasonNote = "<script>alert(1)</script>",
        )
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/reports") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "&lt;script&gt;"
            body shouldNotContain "<script>alert(1)</script>"
        }
    }

    "5.12 — HX-Request returns only the fragment; plain GET returns the full page" {
        val token = authToken()
        val reporter = user()
        ReportQueueTestSupport.seedReport(dataSource, reporter, "user", user(), base, reasonNote = "rq512")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val fragment =
                client.get("/admin/reports") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("HX-Request", "true")
                }.bodyAsText()
            fragment shouldContain "id=\"reports-table\""
            fragment shouldNotContain "<html"
            fragment shouldNotContain "<header>"

            val full = client.get("/admin/reports") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            full shouldContain "<header>"
            full shouldContain "id=\"reports-table\""
        }
    }

    "5.13 — serving the page writes no audit row and mutates no reports / moderation_queue row" {
        val admin = AdminAuthTestSupport.seedAdmin(dataSource).also { seededAdmins.add(it.id) }
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val reporter = user()
        val t = user()
        ReportQueueTestSupport.seedReport(dataSource, reporter, "user", t, base, reasonNote = "rq513")
        ReportQueueTestSupport.seedQueueRow(dataSource, "user", t)

        val auditBefore = AdminAuthTestSupport.countAuditRows(dataSource, admin.id)
        val reportsBefore = ReportQueueTestSupport.totalReportCount(dataSource)
        val queueBefore = ReportQueueTestSupport.totalQueueCount(dataSource)

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.get("/admin/reports") { header(HttpHeaders.Cookie, cookie(token)) }
            client.get("/admin/reports?status=pending&cursor=bad") { header(HttpHeaders.Cookie, cookie(token)) }
        }

        AdminAuthTestSupport.countAuditRows(dataSource, admin.id) shouldBe auditBefore
        ReportQueueTestSupport.totalReportCount(dataSource) shouldBe reportsBefore
        ReportQueueTestSupport.totalQueueCount(dataSource) shouldBe queueBefore
    }

    "5.14 — no resolution control is rendered; has_edit_history is ignored; no resolution route is mounted" {
        val token = authToken()
        val reporter = user()
        ReportQueueTestSupport.seedReport(dataSource, reporter, "user", user(), base, status = "pending", reasonNote = "rq514")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/reports") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            // status shown read-only — no resolution form/button posting to a resolution endpoint
            body shouldContain "rq514"
            body shouldNotContain "mark actioned"
            body shouldNotContain "action=\"/admin/reports"

            // the deferred edit-history prioritization filter is ignored (200, not a 4xx)
            val withParam =
                client.get("/admin/reports?has_edit_history=true") { header(HttpHeaders.Cookie, cookie(token)) }
            withParam.status shouldBe HttpStatusCode.OK
            withParam.bodyAsText() shouldContain "rq514"

            // no resolution route is mounted by this change
            val resolve =
                client.post("/admin/reports/${UUID.randomUUID()}/resolve") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            resolve.status shouldBe HttpStatusCode.NotFound
        }
    }

    "5.15 — reporter renders the resolved username, not the bare reporter UUID" {
        val token = authToken()
        val reporter = user(username = "rq_http_reporter")
        ReportQueueTestSupport.seedReport(dataSource, reporter, "user", user(), base, reasonNote = "rq515")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/reports") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "rq_http_reporter"
            body shouldNotContain reporter.toString() // reporter_id UUID is never rendered raw
        }
    }

    "5.16 — malformed filters are tolerated (200): unparseable from, out-of-enum status, from > to" {
        val token = authToken()
        val reporter = user()
        ReportQueueTestSupport.seedReport(dataSource, reporter, "user", user(), base, reasonNote = "rq516")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            // unparseable from → ignored, the row still renders
            val badDate = client.get("/admin/reports?from=13th-of-never") { header(HttpHeaders.Cookie, cookie(token)) }
            badDate.status shouldBe HttpStatusCode.OK
            badDate.bodyAsText() shouldContain "rq516"

            // out-of-enum status → empty state, never 400/500
            val badStatus = client.get("/admin/reports?status=definitely-not-an-enum") { header(HttpHeaders.Cookie, cookie(token)) }
            badStatus.status shouldBe HttpStatusCode.OK
            badStatus.bodyAsText() shouldContain "No reports match"

            // from later than to → empty, not an error
            val inverted =
                client.get("/admin/reports?from=2090-12-31&to=2090-01-01") { header(HttpHeaders.Cookie, cookie(token)) }
            inverted.status shouldBe HttpStatusCode.OK
            inverted.bodyAsText() shouldContain "No reports match"
        }
    }

    "5.18 — POST /admin/reports returns 405 (only GET is wired)" {
        val token = authToken()
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/reports") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldBe HttpStatusCode.MethodNotAllowed
        }
    }
})
