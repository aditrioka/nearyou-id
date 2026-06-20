package id.nearyou.app.admin.deletionqueue

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuthProvider
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
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
import java.time.Instant
import java.util.UUID

/**
 * Integration tests for the `admin-hard-delete-queue` surface
 * (`GET /admin/deletion-requests` + `POST .../{id}/expedite`), tasks.md Section 6.
 * DB-backed; tagged `database`. Uses `AdminAuthTestSupport` for the
 * authenticated-session wiring + `DeletionQueueTestSupport` for `users` /
 * `deletion_requests` / `admin_actions_log` seeding.
 *
 * Covers: the auth gate, the pending list + executed/cancelled exclusions +
 * soft-deleted-target tolerance, lenient/injection-safe filters, the count
 * summary, PII discipline + output escaping, the expedite write (advances the
 * deadline; the route does not erase), the reason requirement, the CSRF + role
 * guards (incl. CSRF-before-role), the distinct rate-limit cap + budget
 * independence + per-admin scoping, the already-expedited indicator, and the
 * pending/future-state rejection.
 */
@Tags("database")
class AdminDeletionQueueRouteTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val seededUsers = mutableListOf<UUID>()
    val seededAdmins = mutableListOf<UUID>()
    afterEach {
        seededUsers.forEach { DeletionQueueTestSupport.deleteUser(dataSource, it) }
        seededUsers.clear()
        seededAdmins.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seededAdmins.clear()
    }

    fun seedAdmin(role: String = "owner") = AdminAuthTestSupport.seedAdmin(dataSource, role = role).also { seededAdmins.add(it.id) }

    fun seedRequest(
        username: String,
        scheduledHardDeleteAt: Instant = Instant.now().plusSeconds(30 * 24 * 3600L),
        source: String = "user",
        deletedAt: Instant? = null,
        cancelledAt: Instant? = null,
        executedAt: Instant? = null,
    ): UUID {
        val userId = DeletionQueueTestSupport.seedUser(dataSource, username, deletedAt = deletedAt).also { seededUsers.add(it) }
        return DeletionQueueTestSupport.seedDeletionRequest(
            dataSource,
            userId = userId,
            scheduledHardDeleteAt = scheduledHardDeleteAt,
            source = source,
            cancelledAt = cancelledAt,
            executedAt = executedAt,
        )
    }

    fun cookie(token: String) = "${AdminAuthProvider.COOKIE_NAME}=$token"

    // ---- 6.1: the pending list ----

    "6.1 — authenticated GET lists a pending request with source + countdown + base layout" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedRequest("pamit_dulu", source = "user")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/deletion-requests") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "pamit_dulu"
            body shouldContain "user" // the source badge
            body shouldContain "days" // the countdown (~30 days out)
            body shouldContain "<header>"
            body shouldContain "<nav>"
        }
    }

    "6.1 — executed + cancelled requests are excluded; pending lists" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedRequest("pending_one")
        seedRequest("executed_one", executedAt = Instant.now().minusSeconds(60))
        seedRequest("cancelled_one", cancelledAt = Instant.now().minusSeconds(60))

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/deletion-requests") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "pending_one"
            body shouldNotContain "executed_one"
            body shouldNotContain "cancelled_one"
        }
    }

    "6.1 — a soft-deleted target user is still listed with its username" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedRequest("soft_deleted_user", deletedAt = Instant.now().minusSeconds(120))

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/deletion-requests") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "soft_deleted_user"
        }
    }

    "6.1 — unauthenticated GET redirects (302) to /admin/login" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/deletion-requests")
            res.status shouldBe HttpStatusCode.Found
            res.headers[HttpHeaders.Location] shouldBe "/admin/login"
        }
    }

    // ---- 6.2: filters + count + injection safety ----

    "6.2 — q lookup by username; blank q ignored (full population)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedRequest("budi_kopi")
        seedRequest("rina_sore")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val filtered = client.get("/admin/deletion-requests?q=budi_kopi") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            filtered shouldContain "budi_kopi"
            filtered shouldNotContain "rina_sore"

            val blank = client.get("/admin/deletion-requests?q=") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            blank shouldContain "budi_kopi"
            blank shouldContain "rina_sore"
        }
    }

    "6.2 — source filter narrows to the requested source" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedRequest("src_user_row", source = "user")
        seedRequest("src_apple_row", source = "apple_s2s_consent_revoked")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.get("/admin/deletion-requests?source=apple_s2s_consent_revoked") {
                    header(HttpHeaders.Cookie, cookie(token))
                }.bodyAsText()
            body shouldContain "src_apple_row"
            body shouldNotContain "src_user_row"
        }
    }

    "6.2 — count summary reports the full pending population" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val baseline = DeletionQueueTestSupport.pendingCount(dataSource)
        seedRequest("cnt_a")
        seedRequest("cnt_b")
        seedRequest("cnt_c")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/deletion-requests") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "Pending hard-delete: ${baseline + 3}"
        }
    }

    "6.2 — SQL-metacharacter q is a literal (no injection); deletion_requests survives" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val req = seedRequest("safe_user")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get(
                    "/admin/deletion-requests?q=%27%3B+DROP+TABLE+deletion_requests%3B--",
                ) { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "No accounts are pending hard-delete"
            // table still queryable (would throw if dropped)
            DeletionQueueTestSupport.scheduledHardDeleteAtOf(dataSource, req) shouldNotBe null
        }
    }

    // ---- 6.3: PII discipline + escaping ----

    "6.3 — no sensitive PII (DOB) rendered; username markup is escaped" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val evilUser = DeletionQueueTestSupport.seedUser(dataSource, "<b>x</b>_user").also { seededUsers.add(it) }
        DeletionQueueTestSupport.seedDeletionRequest(dataSource, evilUser, Instant.now().plusSeconds(86400))

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/deletion-requests") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "&lt;b&gt;x&lt;/b&gt;_user"
            body shouldNotContain "<b>x</b>_user"
            body shouldNotContain "1995" // seeded DOB year never rendered
        }
    }

    // ---- 6.4: expedite advances the deadline; route does not erase ----

    "6.4 — successful expedite (HX) advances the deadline, logs one row, does not erase" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val req = seedRequest("expedite_me")
        val userId = seededUsers.last()
        val original = DeletionQueueTestSupport.scheduledHardDeleteAtOf(dataSource, req)!!

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/deletion-requests/$req/expedite") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    header("HX-Request", "true")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("reason=user+asked+to+delete+now")
                }
            res.status shouldBe HttpStatusCode.OK
            DeletionQueueTestSupport.countExpeditesFor(dataSource, req) shouldBe 1
            DeletionQueueTestSupport.scheduledHardDeleteAtOf(dataSource, req)!!.isBefore(original) shouldBe true
            // The route schedules; the worker erases. Nothing erased here.
            DeletionQueueTestSupport.executedAtOf(dataSource, req) shouldBe null
            DeletionQueueTestSupport.deletionLogCountFor(dataSource, userId) shouldBe 0

            val logged =
                AdminAuthTestSupport.latestAuditRows(dataSource, admin.id)
                    .first { it.actionType == "deletion_request_expedited" }
            logged.reason!! shouldContain "user asked to delete now"
        }
    }

    "6.4 — successful expedite (no-JS path) is a 303 PRG redirect to the list" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val req = seedRequest("expedite_nojs")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/deletion-requests/$req/expedite") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("reason=SUP-NOJS")
                }
            res.status shouldBe HttpStatusCode.SeeOther
            res.headers[HttpHeaders.Location] shouldBe "/admin/deletion-requests"
            DeletionQueueTestSupport.countExpeditesFor(dataSource, req) shouldBe 1
        }
    }

    "6.4 — expedite with a malformed (non-UUID) id is 400 (no write)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/deletion-requests/not-a-uuid/expedite") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("reason=x")
                }
            res.status shouldBe HttpStatusCode.BadRequest
        }
    }

    "6.4 — expedite without a reason is rejected (400, no write)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val req = seedRequest("no_reason")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/deletion-requests/$req/expedite") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("reason=")
                }
            res.status shouldBe HttpStatusCode.BadRequest
            DeletionQueueTestSupport.countExpeditesFor(dataSource, req) shouldBe 0
        }
    }

    // ---- 6.5: role + CSRF guards ----

    "6.5 — a read_only admin cannot expedite (403, no write)" {
        val admin = seedAdmin(role = "read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val req = seedRequest("ro_target")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/deletion-requests/$req/expedite") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("reason=r")
                }
            res.status shouldBe HttpStatusCode.Forbidden
            DeletionQueueTestSupport.countExpeditesFor(dataSource, req) shouldBe 0
        }
    }

    "6.5 — CSRF-token mismatch is 403 + audited (no write)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val req = seedRequest("csrf_target")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/deletion-requests/$req/expedite") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", "wrong-token")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("reason=r")
                }
            res.status shouldBe HttpStatusCode.Forbidden
            DeletionQueueTestSupport.countExpeditesFor(dataSource, req) shouldBe 0
            val audit = AdminAuthTestSupport.latestAuditRows(dataSource, admin.id)
            audit.any { it.actionType == "admin_csrf_violation" } shouldBe true
            audit.any { it.actionType == "deletion_request_expedited" } shouldBe false
        }
    }

    "6.5 — CSRF rejection precedes the role check (read_only + bad CSRF → CSRF violation)" {
        val admin = seedAdmin(role = "read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val req = seedRequest("csrf_pre_role")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/deletion-requests/$req/expedite") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", "wrong-token")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("reason=r")
                }
            res.status shouldBe HttpStatusCode.Forbidden
            // The CSRF violation is audited (it ran BEFORE the role gate masked it).
            AdminAuthTestSupport.latestAuditRows(dataSource, admin.id)
                .any { it.actionType == "admin_csrf_violation" } shouldBe true
        }
    }

    // ---- 6.6: distinct rate-limit cap ----

    "6.6 — the 11th expedite in an hour is rejected (quota), no write, deadline unchanged" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val req = seedRequest("capped_target")
        val original = DeletionQueueTestSupport.scheduledHardDeleteAtOf(dataSource, req)!!
        repeat(10) { DeletionQueueTestSupport.seedAuditRow(dataSource, admin.id, "deletion_request_expedited") }

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/deletion-requests/$req/expedite") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    header("HX-Request", "true")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("reason=eleventh")
                }
            res.bodyAsText() shouldContain "quota exceeded"
            DeletionQueueTestSupport.countExpeditesFor(dataSource, req) shouldBe 0
            DeletionQueueTestSupport.scheduledHardDeleteAtOf(dataSource, req) shouldBe original
        }
    }

    "6.6 — expedite succeeds when the destructive budget is exhausted (independent counters)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val req = seedRequest("indep_target")
        repeat(20) { DeletionQueueTestSupport.seedAuditRow(dataSource, admin.id, "user_suspended") }

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/deletion-requests/$req/expedite") {
                header(HttpHeaders.Cookie, cookie(token))
                header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("reason=independent")
            }
            DeletionQueueTestSupport.countExpeditesFor(dataSource, req) shouldBe 1
        }
    }

    "6.6 — the cap is per-admin (admin B unaffected by admin A's exhausted quota)" {
        val adminA = seedAdmin()
        val adminB = seedAdmin()
        val tokenB = AdminAuthTestSupport.seedSession(dataSource, adminB.id)
        val req = seedRequest("peradmin_target")
        repeat(10) { DeletionQueueTestSupport.seedAuditRow(dataSource, adminA.id, "deletion_request_expedited") }

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/deletion-requests/$req/expedite") {
                header(HttpHeaders.Cookie, cookie(tokenB))
                header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(tokenB))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("reason=adminB")
            }
            DeletionQueueTestSupport.countExpeditesFor(dataSource, req) shouldBe 1
        }
    }

    // ---- 6.7: already-expedited indicator ----

    "6.7 — already-expedited row shows the handled indicator (latest of two); render writes no audit row" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val req = seedRequest("handled_req")
        DeletionQueueTestSupport.seedAuditRow(
            dataSource,
            admin.id,
            "deletion_request_expedited",
            targetId = req,
            createdAt = Instant.parse("2026-06-10T10:00:00Z"),
        )
        DeletionQueueTestSupport.seedAuditRow(
            dataSource,
            admin.id,
            "deletion_request_expedited",
            targetId = req,
            createdAt = Instant.parse("2026-06-12T15:00:00Z"),
        )

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val auditBefore = AdminAuthTestSupport.countAuditRows(dataSource, admin.id)
            val body = client.get("/admin/deletion-requests") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "Expedited"
            body shouldContain "2026-06-12 15:00"
            body shouldNotContain "2026-06-10 10:00"
            AdminAuthTestSupport.countAuditRows(dataSource, admin.id) shouldBe auditBefore
        }
    }

    "6.7 — a never-expedited (future) row offers the expedite control" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedRequest("fresh_req")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/deletion-requests") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "Expedite now"
            body shouldContain "name=\"reason\""
        }
    }

    // ---- 6.8: pending/future-state rejection ----

    "6.8 — expedite against an already-due request is rejected (no write)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val due = seedRequest("due_target", scheduledHardDeleteAt = Instant.now().minusSeconds(60), source = "apple_s2s_account_delete")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/deletion-requests/$due/expedite") {
                header(HttpHeaders.Cookie, cookie(token))
                header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("reason=already+due")
            }.bodyAsText() shouldContain "no longer pending"
            DeletionQueueTestSupport.countExpeditesFor(dataSource, due) shouldBe 0
        }
    }

    "6.8 — expedite against an unknown id is rejected (no write)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val unknown = UUID.randomUUID()

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/deletion-requests/$unknown/expedite") {
                header(HttpHeaders.Cookie, cookie(token))
                header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("reason=ghost")
            }.bodyAsText() shouldContain "no longer pending"
            DeletionQueueTestSupport.countExpeditesFor(dataSource, unknown) shouldBe 0
        }
    }

    "6.2 — HX-Request returns only the table fragment (no full-page layout)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedRequest("frag_req")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/deletion-requests") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("HX-Request", "true")
                }
            val body = res.bodyAsText()
            body shouldContain "id=\"deletion-requests-table\""
            body shouldNotContain "<html"
            body shouldNotContain "<header>"
        }
    }
})
