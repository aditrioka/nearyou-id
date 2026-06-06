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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
            body shouldContain "<footer>"
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

    "6.10 — deferred-action negative guard: no clear / remove / delete control is rendered" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seed("h1", reason = "age_under_18")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.get("/admin/rejected-identifiers") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldNotContain "Clear"
            body shouldNotContain "Remove"
            body shouldNotContain "Delete"
            body shouldNotContain "hx-delete"
            body shouldNotContain "hx-post"
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
