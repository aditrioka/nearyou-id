package id.nearyou.app.admin.dataexportqueue

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuthProvider
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
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
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Integration tests for the `admin-data-export-queue` surface
 * (`GET /admin/data-exports` + `POST .../{id}/trigger`), tasks.md Section 5.
 * DB-backed; tagged `database`. Uses `AdminAuthTestSupport` for the authenticated
 * session wiring + `DataExportQueueTestSupport` for `users` / `data_export_requests`
 * / `admin_actions_log` seeding + the REAL producer seam injected as the trigger
 * processor (so a `failed`/`pending` re-run actually reaches `ready`).
 *
 * Covers: the auth gate, the keyset list (newest-first + page-boundary tie-break),
 * the five status labels + delivered-via cell, the username deep-link, lenient/
 * composed filters + count summary, identity-only PII discipline + output escaping,
 * the trigger write (re-enqueue + drive through the producer pipeline), the reason
 * requirement, the CSRF + role guards (CSRF-before-role), the distinct rate-limit cap
 * + budget independence, and the benign idempotent no-op rejections.
 */
@Tags("database")
class AdminDataExportQueueRouteTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val seededUsers = mutableListOf<UUID>()
    val seededAdmins = mutableListOf<UUID>()
    afterEach {
        seededUsers.forEach { DataExportQueueTestSupport.deleteUser(dataSource, it) }
        seededUsers.clear()
        seededAdmins.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seededAdmins.clear()
    }

    fun seedAdmin(role: String = "owner") = AdminAuthTestSupport.seedAdmin(dataSource, role = role).also { seededAdmins.add(it.id) }

    fun seedUser(username: String): UUID = DataExportQueueTestSupport.seedUser(dataSource, username).also { seededUsers.add(it) }

    fun seedRequest(
        username: String,
        status: String = "pending",
        requestedAt: Instant = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS),
    ): UUID = DataExportQueueTestSupport.seedRequest(dataSource, seedUser(username), status = status, requestedAt = requestedAt)

    fun cookie(token: String) = "${AdminAuthProvider.COOKIE_NAME}=$token"

    // ---- 5.1: read list ----

    "5.1 — authenticated GET lists requests newest-requested-first with the base layout" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        seedRequest("oldest_x", status = "failed", requestedAt = now.minusSeconds(300))
        seedRequest("middle_x", status = "ready", requestedAt = now.minusSeconds(200))
        seedRequest("newest_x", status = "pending", requestedAt = now.minusSeconds(100))

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/data-exports") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "<header>"
            body shouldContain "<nav>"
            // Newest requested_at first.
            body.indexOf("newest_x") shouldBeGreaterThan -1
            (body.indexOf("newest_x") < body.indexOf("middle_x")) shouldBe true
            (body.indexOf("middle_x") < body.indexOf("oldest_x")) shouldBe true
        }
    }

    "5.1 — the five DB statuses render as their frame-16 labels; delivered-via only for ready" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedRequest("q_pending", status = "pending")
        seedRequest("q_processing", status = "processing")
        seedRequest("q_ready", status = "ready")
        seedRequest("q_expired", status = "expired")
        seedRequest("q_failed", status = "failed")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/data-exports") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "QUEUED"
            body shouldContain "RUNNING"
            body shouldContain "DELIVERED"
            body shouldContain "EXPIRED"
            body shouldContain "FAILED"
            // The delivered-via indicator surfaces only for the ready row.
            body shouldContain "notif <span class=\"code\">data_export_ready</span> + email"
        }
    }

    "5.1 — username deep-links to /admin/users?q=" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedRequest("sari_menyapa", status = "pending")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/data-exports") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "/admin/users?q=sari_menyapa"
        }
    }

    "5.1 — plain GET returns the full page; HX-Request returns only the table fragment" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedRequest("frag_user", status = "pending")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val full = client.get("/admin/data-exports") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            full shouldContain "<html"
            full shouldContain "id=\"data-exports-table\""

            val frag =
                client.get("/admin/data-exports") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("HX-Request", "true")
                }.bodyAsText()
            frag shouldContain "id=\"data-exports-table\""
            frag shouldNotContain "<html"
            frag shouldNotContain "<header>"
        }
    }

    "5.1 — unauthenticated GET redirects (302) to /admin/login" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/data-exports")
            res.status shouldBe HttpStatusCode.Found
            res.headers[HttpHeaders.Location] shouldBe "/admin/login"
        }
    }

    "5.1 — identity-only PII: a delivered row shows DELIVERED but no object key or signed URL" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser("pii_user")
        DataExportQueueTestSupport.seedRequest(
            dataSource,
            uid,
            status = "ready",
            r2ObjectKey = "exports/$uid/secret-archive-key.zip",
            downloadExpiresAt = Instant.now().plusSeconds(24 * 3600L),
        )

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/data-exports") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "DELIVERED"
            // No object key, signed URL, or download window value anywhere in the response.
            body shouldNotContain "secret-archive-key"
            body shouldNotContain "exports/$uid"
            body shouldNotContain "?sig="
            body shouldNotContain "download_expires_at"
        }
    }

    "5.1 — a username with HTML metacharacters renders escaped in the cell and the deep-link" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedRequest("<script>&\"x", status = "pending")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/data-exports") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "&lt;script&gt;" // escaped in the cell
            body shouldNotContain "<script>&\"x" // never raw
            // The deep-link q value is URL-encoded (not a raw < or ").
            body shouldContain "/admin/users?q=%3Cscript%3E"
        }
    }

    // ---- 5.1: filters + count summary ----

    "5.1 — status filter narrows to one state and the count summary recomputes" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedRequest("flt_failed", status = "failed")
        seedRequest("flt_pending", status = "pending")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/data-exports?status=failed") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "flt_failed"
            body shouldNotContain "flt_pending"
            body shouldContain "Export requests: 1"
        }
    }

    "5.1 — q + status compose with AND semantics; the count reflects the composed filter" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val target = seedUser("jaka_kelana")
        DataExportQueueTestSupport.seedRequest(dataSource, target, status = "pending")
        DataExportQueueTestSupport.seedRequest(dataSource, target, status = "failed")
        seedRequest("other_user", status = "pending")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.get("/admin/data-exports?status=pending&q=jaka_kelana") {
                    header(HttpHeaders.Cookie, cookie(token))
                }.bodyAsText()
            body shouldContain "jaka_kelana"
            body shouldNotContain "other_user"
            body shouldContain "Export requests: 1" // only the pending one for that user
        }
    }

    "5.1 — blank q is ignored; a SQL-metacharacter q is a literal (no injection)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedRequest("safe_export_user", status = "pending")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val blank = client.get("/admin/data-exports?q=") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            blank shouldContain "safe_export_user"

            val res =
                client.get("/admin/data-exports?q=%27%3B+DROP+TABLE+data_export_requests%3B--") {
                    header(HttpHeaders.Cookie, cookie(token))
                }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "No export requests match"
            // table still queryable (would throw if dropped)
            DataExportQueueTestSupport.statusOf(dataSource, UUID.randomUUID()) // smoke: query runs
        }
    }

    "5.1 — page boundary + (requested_at, id) keyset tie-break on equal requested_at" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        // A FUTURE requested_at so these rows are the global newest regardless of any other
        // export rows in the DB (the list is unfiltered) — all SHARE the instant, so the
        // keyset must tie-break on id DESC.
        val sameTs = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS)
        val pageSize = DataExportQueueRepository.PAGE_SIZE
        // > pageSize ready rows for distinct users. Ready rows have no one-active constraint.
        val rows =
            (0..pageSize).map { i ->
                val uname = "pgbnd_${i.toString().padStart(2, '0')}"
                val uid = seedUser(uname)
                val rid = DataExportQueueTestSupport.seedRequest(dataSource, uid, status = "ready", requestedAt = sameTs)
                rid to uname
            }
        // Expected ordering: requested_at DESC (all equal) then id DESC. PostgreSQL orders
        // the `uuid` type by UNSIGNED byte comparison (NOT Java's signed UUID.compareTo), so
        // mirror it with compareUnsigned over the two longs (big-endian = byte-wise order).
        val pgUuid =
            Comparator<UUID> { a, b ->
                val hi = java.lang.Long.compareUnsigned(a.mostSignificantBits, b.mostSignificantBits)
                if (hi != 0) hi else java.lang.Long.compareUnsigned(a.leastSignificantBits, b.leastSignificantBits)
            }
        val byKeyDesc = rows.sortedWith(compareByDescending(pgUuid) { it.first })
        val page1Expected = byKeyDesc.take(pageSize).map { it.second }
        val page2Expected = byKeyDesc.drop(pageSize).map { it.second } // exactly 1

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val page1 = client.get("/admin/data-exports") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            page1 shouldContain "Next" // a further page exists
            page1Expected.forEach { page1 shouldContain it }
            page2Expected.forEach { page1 shouldNotContain ">$it<" }

            // Follow the Next cursor.
            val nextHref = Regex("/admin/data-exports\\?cursor=[^\"]+").find(page1)!!.value
            val page2 = client.get(nextHref) { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            page2Expected.forEach { page2 shouldContain it }
        }
    }

    // ---- 5.2: trigger write (real producer seam) ----

    fun triggerProcessor() = DataExportQueueTestSupport.realProcessor(dataSource)

    "5.2 — owner re-runs a failed export → ready + one data_export_triggered audit (before failed)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser("rerun_failed")
        val req = DataExportQueueTestSupport.seedRequest(dataSource, uid, status = "failed")

        AdminAuthTestSupport.withAdminApp(dataSource, dataExportProcessor = triggerProcessor()) { client ->
            val res =
                client.post("/admin/data-exports/$req/trigger") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    header("HX-Request", "true")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("reason=scheduler+stalled")
                }
            res.status shouldBe HttpStatusCode.OK
            DataExportQueueTestSupport.statusOf(dataSource, req) shouldBe "ready"
            DataExportQueueTestSupport.notificationCount(dataSource, uid) shouldBe 1
            DataExportQueueTestSupport.countTriggersFor(dataSource, req) shouldBe 1
            val logged =
                AdminAuthTestSupport.latestAuditRows(dataSource, admin.id)
                    .first { it.actionType == "data_export_triggered" }
            logged.reason!! shouldContain "scheduler stalled"
            logged.beforeState!! shouldContain "failed"
        }
    }

    "5.2 — owner triggers a stuck pending row → processed immediately + one audit row" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser("stuck_pending")
        val req = DataExportQueueTestSupport.seedRequest(dataSource, uid, status = "pending")

        AdminAuthTestSupport.withAdminApp(dataSource, dataExportProcessor = triggerProcessor()) { client ->
            client.post("/admin/data-exports/$req/trigger") {
                header(HttpHeaders.Cookie, cookie(token))
                header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                header("HX-Request", "true")
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("reason=run+it+now")
            }
            DataExportQueueTestSupport.statusOf(dataSource, req) shouldBe "ready"
            DataExportQueueTestSupport.countTriggersFor(dataSource, req) shouldBe 1
        }
    }

    "5.2 — missing/invalid CSRF → 403 + admin_csrf_violation, no mutation, no trigger audit" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser("csrf_target")
        val req = DataExportQueueTestSupport.seedRequest(dataSource, uid, status = "failed")

        AdminAuthTestSupport.withAdminApp(dataSource, dataExportProcessor = triggerProcessor()) { client ->
            val res =
                client.post("/admin/data-exports/$req/trigger") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", "wrong-token")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("reason=r")
                }
            res.status shouldBe HttpStatusCode.Forbidden
            DataExportQueueTestSupport.statusOf(dataSource, req) shouldBe "failed"
            DataExportQueueTestSupport.countTriggersFor(dataSource, req) shouldBe 0
            val audit = AdminAuthTestSupport.latestAuditRows(dataSource, admin.id)
            audit.any { it.actionType == "admin_csrf_violation" } shouldBe true
            audit.any { it.actionType == "data_export_triggered" } shouldBe false
        }
    }

    "5.2 — CSRF rejection precedes the role check (read_only + bad CSRF → CSRF violation)" {
        val admin = seedAdmin(role = "read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser("csrf_pre_role")
        val req = DataExportQueueTestSupport.seedRequest(dataSource, uid, status = "failed")

        AdminAuthTestSupport.withAdminApp(dataSource, dataExportProcessor = triggerProcessor()) { client ->
            val res =
                client.post("/admin/data-exports/$req/trigger") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", "wrong-token")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("reason=r")
                }
            res.status shouldBe HttpStatusCode.Forbidden
            AdminAuthTestSupport.latestAuditRows(dataSource, admin.id)
                .any { it.actionType == "admin_csrf_violation" } shouldBe true
        }
    }

    "5.2 — a read_only / moderator admin cannot trigger (403, no mutation)" {
        listOf("read_only", "moderator").forEach { role ->
            val admin = seedAdmin(role = role)
            val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
            val uid = seedUser("ro_target_$role")
            val req = DataExportQueueTestSupport.seedRequest(dataSource, uid, status = "failed")

            AdminAuthTestSupport.withAdminApp(dataSource, dataExportProcessor = triggerProcessor()) { client ->
                val res =
                    client.post("/admin/data-exports/$req/trigger") {
                        header(HttpHeaders.Cookie, cookie(token))
                        header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody("reason=r")
                    }
                res.status shouldBe HttpStatusCode.Forbidden
                DataExportQueueTestSupport.statusOf(dataSource, req) shouldBe "failed"
                DataExportQueueTestSupport.countTriggersFor(dataSource, req) shouldBe 0
            }
        }
    }

    "5.2 — trigger without a reason is rejected (400, no audit)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser("no_reason")
        val req = DataExportQueueTestSupport.seedRequest(dataSource, uid, status = "failed")

        AdminAuthTestSupport.withAdminApp(dataSource, dataExportProcessor = triggerProcessor()) { client ->
            val res =
                client.post("/admin/data-exports/$req/trigger") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("reason=")
                }
            res.status shouldBe HttpStatusCode.BadRequest
            DataExportQueueTestSupport.statusOf(dataSource, req) shouldBe "failed"
            DataExportQueueTestSupport.countTriggersFor(dataSource, req) shouldBe 0
        }
    }

    "5.2 — the 11th trigger in an hour is rate-limited (no mutation, no audit)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser("capped")
        val req = DataExportQueueTestSupport.seedRequest(dataSource, uid, status = "failed")
        repeat(10) { DataExportQueueTestSupport.seedAuditRow(dataSource, admin.id, "data_export_triggered") }

        AdminAuthTestSupport.withAdminApp(dataSource, dataExportProcessor = triggerProcessor()) { client ->
            val res =
                client.post("/admin/data-exports/$req/trigger") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    header("HX-Request", "true")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("reason=eleventh")
                }
            res.bodyAsText() shouldContain "quota exceeded"
            DataExportQueueTestSupport.statusOf(dataSource, req) shouldBe "failed"
            DataExportQueueTestSupport.countTriggersFor(dataSource, req) shouldBe 0
        }
    }

    "5.2 — triggers do not consume the separate 20/hr destructive budget" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser("indep")
        val req = DataExportQueueTestSupport.seedRequest(dataSource, uid, status = "failed")
        repeat(20) { DataExportQueueTestSupport.seedAuditRow(dataSource, admin.id, "user_suspended") }

        AdminAuthTestSupport.withAdminApp(dataSource, dataExportProcessor = triggerProcessor()) { client ->
            client.post("/admin/data-exports/$req/trigger") {
                header(HttpHeaders.Cookie, cookie(token))
                header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("reason=independent")
            }
            DataExportQueueTestSupport.countTriggersFor(dataSource, req) shouldBe 1
        }
    }

    // ---- 5.3: benign idempotent no-op ----

    listOf("processing", "ready", "expired").forEach { state ->
        "5.3 — trigger on a $state row is a benign no-op (no mutation, no audit)" {
            val admin = seedAdmin()
            val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
            val uid = seedUser("noop_$state")
            val req = DataExportQueueTestSupport.seedRequest(dataSource, uid, status = state)

            AdminAuthTestSupport.withAdminApp(dataSource, dataExportProcessor = triggerProcessor()) { client ->
                client.post("/admin/data-exports/$req/trigger") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    header("HX-Request", "true")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("reason=noop")
                }
                DataExportQueueTestSupport.statusOf(dataSource, req) shouldBe state
                DataExportQueueTestSupport.countTriggersFor(dataSource, req) shouldBe 0
            }
        }
    }

    "5.3 — re-enqueue colliding with another active request maps to a no-op (no audit, no partial mutation)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uid = seedUser("collide_user")
        // The user has BOTH a failed row (the trigger target) AND a separate pending row
        // (the active one-of holder) — re-enqueueing the failed one would violate
        // data_export_requests_one_active_idx.
        val failed = DataExportQueueTestSupport.seedRequest(dataSource, uid, status = "failed")
        DataExportQueueTestSupport.seedRequest(dataSource, uid, status = "pending")

        AdminAuthTestSupport.withAdminApp(dataSource, dataExportProcessor = triggerProcessor()) { client ->
            val res =
                client.post("/admin/data-exports/$failed/trigger") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    header("HX-Request", "true")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("reason=collide")
                }
            res.status shouldBe HttpStatusCode.OK // no unhandled exception
            res.bodyAsText() shouldContain "already has another active export"
            DataExportQueueTestSupport.statusOf(dataSource, failed) shouldBe "failed" // unchanged
            DataExportQueueTestSupport.countTriggersFor(dataSource, failed) shouldBe 0
        }
    }

    "5.3 — trigger on an unknown id is a benign no-op (no audit)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val unknown = UUID.randomUUID()

        AdminAuthTestSupport.withAdminApp(dataSource, dataExportProcessor = triggerProcessor()) { client ->
            val res =
                client.post("/admin/data-exports/$unknown/trigger") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    header("HX-Request", "true")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("reason=ghost")
                }
            res.status shouldBe HttpStatusCode.OK
            DataExportQueueTestSupport.countTriggersFor(dataSource, unknown) shouldBe 0
        }
    }

    "5.2 — trigger with a malformed (non-UUID) id is 400 (no write)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)

        AdminAuthTestSupport.withAdminApp(dataSource, dataExportProcessor = triggerProcessor()) { client ->
            val res =
                client.post("/admin/data-exports/not-a-uuid/trigger") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("reason=x")
                }
            res.status shouldBe HttpStatusCode.BadRequest
        }
    }
})
