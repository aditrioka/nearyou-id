package id.nearyou.app.admin.rejectedidentifiers

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
 * Integration tests for the `GET /admin/rejected-identifiers` route — the
 * viewer of the `admin-rejected-identifiers-viewer` capability (tasks.md
 * Section 6). DB-backed; tagged `database`. Uses the `AdminAuthTestSupport`
 * harness for the authenticated-session wiring + `RejectedIdentifiersTestSupport`
 * for row seeding. Asserts the auth gate, render, HTMX/plain dual-mode, lenient
 * filtering, output escaping, the count summary, the read-only/no-mutation +
 * deferred-action negative guard, role access, and the empty state.
 */
@Tags("database")
class AdminRejectedIdentifiersRouteTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val seededAdmins = mutableListOf<UUID>()
    beforeEach { RejectedIdentifiersTestSupport.deleteAll(dataSource) }
    afterEach {
        RejectedIdentifiersTestSupport.deleteAll(dataSource)
        seededAdmins.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seededAdmins.clear()
    }

    fun seedAdmin(role: String = "owner"): AdminAuthTestSupport.SeededAdmin =
        AdminAuthTestSupport.seedAdmin(dataSource, role = role).also { seededAdmins.add(it.id) }

    fun cookie(token: String) = "${AdminAuthProvider.COOKIE_NAME}=$token"

    val base = Instant.parse("2026-05-20T00:00:00Z")

    fun seed(
        hash: String,
        type: String = "google",
        reason: String = "age_under_18",
        at: Instant = base,
    ) = RejectedIdentifiersTestSupport.seedRejectedIdentifier(dataSource, hash, type, reason, at)

    fun form(vararg pairs: Pair<String, String>): String = pairs.toList().formUrlEncode()

    fun csrfViolated(adminId: UUID): Boolean =
        AdminAuthTestSupport.latestAuditRows(dataSource, adminId).any { it.actionType == "admin_csrf_violation" }

    "6.1 — authenticated GET renders the table with a seeded row's hash + reason + base layout" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seed("hash-abc123", reason = "age_under_18")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/rejected-identifiers") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "hash-abc123"
            body shouldContain "age_under_18"
            body shouldContain "<header>" // full page extends layout
            body shouldContain "<nav>" // frame-2 shell (no footer — admin-mockup-parity)
        }
    }

    "6.1 — unauthenticated GET redirects (302) to /admin/login" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/rejected-identifiers")
            res.status shouldBe HttpStatusCode.Found
            res.headers[HttpHeaders.Location] shouldBe "/admin/login"
        }
    }

    "6.7 — HX-Request returns only the table fragment (no full-page layout)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seed("hash-frag", reason = "age_under_18")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/rejected-identifiers") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("HX-Request", "true")
                }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "id=\"rejected-identifiers-table\""
            body shouldNotContain "<html"
            body shouldNotContain "<header>"
        }
    }

    "6.7 — plain GET with a filter returns the full page reflecting the filter" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seed("hash-age", reason = "age_under_18", at = base.plusSeconds(1))
        seed("hash-att", reason = "attestation_persistent_fail", at = base.plusSeconds(2))

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/rejected-identifiers?reason=age_under_18") {
                    header(HttpHeaders.Cookie, cookie(token))
                }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "id=\"rejected-identifiers-table\""
            body shouldContain "<header>" // full page
            body shouldContain "hash-age"
            body shouldNotContain "hash-att"
        }
    }

    "6.6 — full page renders the per-reason / per-type count summary (all buckets, zero-defaulted)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seed("only-age", reason = "age_under_18", type = "google")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.get("/admin/rejected-identifiers") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "Total matching: 1"
            // both reason buckets render even though only age_under_18 has rows
            body shouldContain "age_under_18: 1"
            body shouldContain "attestation_persistent_fail: 0"
            // both type buckets render
            body shouldContain "google: 1"
            body shouldContain "apple: 0"
        }
    }

    "6.5 — unrecognized reason is ignored; the valid identifier_type filter still applies" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seed("g1", type = "google", reason = "age_under_18", at = base.plusSeconds(1))
        seed("a1", type = "apple", reason = "age_under_18", at = base.plusSeconds(2))

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/rejected-identifiers?reason=not-a-real-reason&identifier_type=google") {
                    header(HttpHeaders.Cookie, cookie(token))
                }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "g1"
            body shouldNotContain "a1"
        }
    }

    "6.5 — SQL-metacharacter reason is ignored (200) and the table survives" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seed("survivor", reason = "age_under_18")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            // URL-encoded `'; DROP TABLE rejected_identifiers;--`
            val res =
                client.get("/admin/rejected-identifiers?reason=%27%3B+DROP+TABLE+rejected_identifiers%3B--") {
                    header(HttpHeaders.Cookie, cookie(token))
                }
            res.status shouldBe HttpStatusCode.OK
        }
        // table still exists + the row is intact
        RejectedIdentifiersTestSupport.count(dataSource) shouldBe 1
    }

    "6.5 — unparseable date is ignored; over-long reason is bounded → 200 (no 400/500)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seed("present", reason = "age_under_18")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val badDate =
                client.get("/admin/rejected-identifiers?from=13th-of-never") {
                    header(HttpHeaders.Cookie, cookie(token))
                }
            badDate.status shouldBe HttpStatusCode.OK
            badDate.bodyAsText() shouldContain "present" // unfiltered by the ignored date

            val overLong =
                client.get("/admin/rejected-identifiers?reason=${"x".repeat(200)}") {
                    header(HttpHeaders.Cookie, cookie(token))
                }
            overLong.status shouldBe HttpStatusCode.OK
        }
    }

    "6.8 — identifier_hash with <script> renders escaped, not as a live tag" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seed("<script>alert(1)</script>", reason = "age_under_18")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.get("/admin/rejected-identifiers") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "&lt;script&gt;"
            body shouldNotContain "<script>alert(1)</script>"
        }
    }

    "6.9 — POST /admin/rejected-identifiers returns 405 (only GET is wired)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/rejected-identifiers") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldBe HttpStatusCode.MethodNotAllowed
        }
    }

    "6.9 — serving the viewer mutates no row and writes no audit row" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seed("h1", reason = "age_under_18")
        seed("h2", reason = "attestation_persistent_fail")
        val rejectedBefore = RejectedIdentifiersTestSupport.count(dataSource)
        val auditBefore = AdminAuthTestSupport.countAuditRows(dataSource, admin.id)

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.get("/admin/rejected-identifiers") { header(HttpHeaders.Cookie, cookie(token)) }
            client.get("/admin/rejected-identifiers") { header(HttpHeaders.Cookie, cookie(token)) }
        }
        RejectedIdentifiersTestSupport.count(dataSource) shouldBe rejectedBefore
        AdminAuthTestSupport.countAuditRows(dataSource, admin.id) shouldBe auditBefore
    }

    // ---- Clear action (admin-rejected-identifiers-clear-action) -------------

    "clear control renders for an owner/admin session (inverting the prior deferral guard)" {
        val admin = seedAdmin(role = "admin")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val id = seed("ctrl-hash", reason = "age_under_18")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.get("/admin/rejected-identifiers") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "/admin/rejected-identifiers/$id/clear" // per-row clear form action
            body shouldContain "Clear"
        }
    }

    "no clear control is rendered for a read_only or moderator session (read view still 200)" {
        seed("noctrl-hash", reason = "age_under_18")
        listOf("read_only", "moderator").forEach { role ->
            val admin = seedAdmin(role = role)
            val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
            AdminAuthTestSupport.withAdminApp(dataSource) { client ->
                val res = client.get("/admin/rejected-identifiers") { header(HttpHeaders.Cookie, cookie(token)) }
                res.status shouldBe HttpStatusCode.OK
                res.bodyAsText() shouldNotContain "/clear" // no per-row clear control
            }
        }
    }

    "clear (HTMX) by an owner removes the row + writes one audit row; the cleared row is absent from the fragment" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val id = seed("htmx-clear-hash", reason = "age_under_18")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/rejected-identifiers/$id/clear") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("HX-Request", "true")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token), "reason" to "legitimate adult"))
                }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldNotContain "htmx-clear-hash" // cleared row gone from the re-queried fragment
        }
        RejectedIdentifiersTestSupport.existsById(dataSource, id) shouldBe false
        val rows = AdminAuthTestSupport.latestAuditRows(dataSource, admin.id)
        rows.count { it.actionType == "rejected_identifier_cleared" } shouldBe 1
    }

    "clear (no-JS) by an owner returns 303 back to the listing + removes the row" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val id = seed("nojs-clear-hash", reason = "age_under_18")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/rejected-identifiers/$id/clear") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token), "reason" to "legitimate adult"))
                }
            res.status shouldBe HttpStatusCode.SeeOther
            res.headers[HttpHeaders.Location] shouldBe "/admin/rejected-identifiers"
        }
        RejectedIdentifiersTestSupport.existsById(dataSource, id) shouldBe false
    }

    "clear without a CSRF token → 403 + admin_csrf_violation, no delete" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val id = seed("nocsrf-hash", reason = "age_under_18")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/rejected-identifiers/$id/clear") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(form("reason" to "no csrf"))
                }
            res.status shouldBe HttpStatusCode.Forbidden
        }
        csrfViolated(admin.id) shouldBe true
        RejectedIdentifiersTestSupport.existsById(dataSource, id) shouldBe true
    }

    "clear by a moderator → 403 (owner/admin-only), no delete, no CSRF violation" {
        val admin = seedAdmin(role = "moderator")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val id = seed("mod-hash", reason = "age_under_18")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/rejected-identifiers/$id/clear") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token), "reason" to "should fail"))
                }
            res.status shouldBe HttpStatusCode.Forbidden
        }
        RejectedIdentifiersTestSupport.existsById(dataSource, id) shouldBe true
        csrfViolated(admin.id) shouldBe false // the role gate rejected, not CSRF
    }

    "clear by a read_only admin → 403, no delete" {
        val admin = seedAdmin(role = "read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val id = seed("ro-hash", reason = "age_under_18")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/rejected-identifiers/$id/clear") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token), "reason" to "should fail"))
                }
            res.status shouldBe HttpStatusCode.Forbidden
        }
        RejectedIdentifiersTestSupport.existsById(dataSource, id) shouldBe true
    }

    "clear with CSRF missing AND a non-write role → the CSRF rejection fires first (CSRF before role)" {
        val admin = seedAdmin(role = "read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val id = seed("order-hash", reason = "age_under_18")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/rejected-identifiers/$id/clear") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(form("reason" to "no csrf"))
                }
            res.status shouldBe HttpStatusCode.Forbidden
        }
        csrfViolated(admin.id) shouldBe true // CSRF evaluated before the role gate
        RejectedIdentifiersTestSupport.existsById(dataSource, id) shouldBe true
    }

    "clear with a blank reason → 400, no delete" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val id = seed("blank-reason-hash", reason = "age_under_18")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/rejected-identifiers/$id/clear") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token), "reason" to "   "))
                }
            res.status shouldBe HttpStatusCode.BadRequest
        }
        RejectedIdentifiersTestSupport.existsById(dataSource, id) shouldBe true
    }

    "clear with an over-long reason → 400, no delete" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val id = seed("long-reason-hash", reason = "age_under_18")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/rejected-identifiers/$id/clear") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token), "reason" to "x".repeat(501)))
                }
            res.status shouldBe HttpStatusCode.BadRequest
        }
        RejectedIdentifiersTestSupport.existsById(dataSource, id) shouldBe true
    }

    "clear with a malformed (non-UUID) id → 400, no write" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/rejected-identifiers/not-a-uuid/clear") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token), "reason" to "valid reason"))
                }
            res.status shouldBe HttpStatusCode.BadRequest
        }
        AdminAuthTestSupport.latestAuditRows(dataSource, admin.id)
            .count { it.actionType == "rejected_identifier_cleared" } shouldBe 0
    }

    "clear of a nonexistent id → graceful no-op (200 re-render), no audit row" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/rejected-identifiers/${UUID.randomUUID()}/clear") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token), "reason" to "valid reason"))
                }
            res.status shouldBe HttpStatusCode.OK // graceful re-render, not a 5xx
            res.bodyAsText() shouldContain "no longer exists"
        }
        AdminAuthTestSupport.latestAuditRows(dataSource, admin.id)
            .count { it.actionType == "rejected_identifier_cleared" } shouldBe 0
    }

    "clear at the 10/hr cap → quota-exceeded re-render (200), row remains, no new audit row" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        repeat(10) { RejectedIdentifiersTestSupport.seedClearAudit(dataSource, admin.id) }
        val id = seed("cap-hash", reason = "age_under_18")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/rejected-identifiers/$id/clear") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token), "reason" to "over cap"))
                }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "quota"
        }
        RejectedIdentifiersTestSupport.existsById(dataSource, id) shouldBe true
        AdminAuthTestSupport.latestAuditRows(dataSource, admin.id)
            .count { it.actionType == "rejected_identifier_cleared" } shouldBe 10 // the 10 seeded; no new one
    }

    "6.3 — malformed cursor falls back to the newest page (200, no error)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seed("cursor-fallback-row", reason = "age_under_18")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/rejected-identifiers?cursor=not-a-valid-cursor") {
                    header(HttpHeaders.Cookie, cookie(token))
                }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "cursor-fallback-row" // malformed cursor ignored, newest page rendered
        }
    }

    "6.3 — last page (≤ page size) omits the older pagination control" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seed("only-1", reason = "age_under_18", at = base.plusSeconds(1))
        seed("only-2", reason = "age_under_18", at = base.plusSeconds(2))
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.get("/admin/rejected-identifiers") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "only-1"
            body shouldContain "only-2"
            body shouldNotContain "pagination-older" // no older page → no control rendered
        }
    }

    "hash-only PII discipline — the rendered row exposes the hash, not a raw identifier or /admin/users cross-link" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seed("hash-pii-guard", reason = "age_under_18")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            // Use the HTMX fragment (no layout nav, which itself links /admin/users)
            // so the assertion is scoped to the table rows, per the spec scenario.
            val fragment =
                client.get("/admin/rejected-identifiers") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("HX-Request", "true")
                }.bodyAsText()
            fragment shouldContain "hash-pii-guard" // the hash IS shown
            fragment shouldNotContain "/admin/users" // no cross-link to a users record
            fragment shouldNotContain "@" // no resolved email / raw identifier
        }
    }

    "6.11 — read_only admin can view the rejected-identifiers list" {
        val admin = seedAdmin(role = "read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seed("h1", reason = "age_under_18")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/rejected-identifiers") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
        }
    }

    "6.12 — no-match filter renders the empty state (full page AND HTMX fragment)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seed("present-2026", reason = "age_under_18") // exists but excluded by the future date filter

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val full =
                client.get("/admin/rejected-identifiers?from=2999-01-01") {
                    header(HttpHeaders.Cookie, cookie(token))
                }
            full.status shouldBe HttpStatusCode.OK
            full.bodyAsText() shouldContain "No rejected identifiers match"

            val fragment =
                client.get("/admin/rejected-identifiers?from=2999-01-01") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("HX-Request", "true")
                }
            fragment.status shouldBe HttpStatusCode.OK
            val fragBody = fragment.bodyAsText()
            fragBody shouldContain "id=\"rejected-identifiers-table\""
            fragBody shouldContain "No rejected identifiers match"
        }
    }

    "6.3c — following the rendered older-cursor link retains the reason filter + returns the next-older, non-overlapping page" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        // 60 age_under_18 rows evt-00..evt-59 (evt-59 newest). Page size 50 →
        // page 1 = evt-59..evt-10, page 2 = evt-09..evt-00.
        repeat(60) { i ->
            seed("evt-%02d".format(i), reason = "age_under_18", at = base.plusSeconds(i.toLong()))
        }
        // attestation rows NEWER than every evt row — must NOT leak past the
        // reason filter onto either page.
        seed("att-leak-a", reason = "attestation_persistent_fail", at = base.plusSeconds(1000))
        seed("att-leak-b", reason = "attestation_persistent_fail", at = base.plusSeconds(1001))

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val page1 =
                client.get("/admin/rejected-identifiers?reason=age_under_18") {
                    header(HttpHeaders.Cookie, cookie(token))
                }.bodyAsText()
            page1 shouldContain "evt-59"
            page1 shouldContain "evt-10"
            page1 shouldNotContain "evt-09"
            page1 shouldNotContain "evt-00"
            page1 shouldNotContain "att-leak" // reason filter excludes attestation rows

            // Extract the older-cursor link and follow it over HTTP — exercises the
            // full cursor encode → query-param → decode round-trip.
            val olderUrl =
                Regex("class=\"pagination-older\"\\s+href=\"([^\"]+)\"")
                    .find(page1)!!
                    .groupValues[1]
                    .replace("&amp;", "&")
            // the cursor link composes with (retains) the active reason filter
            olderUrl shouldContain "reason=age_under_18"

            val page2 = client.get(olderUrl) { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            page2 shouldContain "evt-09"
            page2 shouldContain "evt-00"
            page2 shouldNotContain "evt-59"
            page2 shouldNotContain "evt-10"
            page2 shouldNotContain "att-leak" // filter still applied on the paged URL
        }
    }
})
