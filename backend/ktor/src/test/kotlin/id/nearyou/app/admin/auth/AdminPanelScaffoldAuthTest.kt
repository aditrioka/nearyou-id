package id.nearyou.app.admin.auth

import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.util.UUID

/**
 * Integration tests for the `admin-panel-scaffold` capability's MODIFIED
 * requirements under `admin-login-argon2-totp` — the index route is now
 * auth-gated (302 to /admin/login when unauthenticated), static assets stay
 * public, and the layout structural sections render in both authed +
 * unauthed contexts. Replaces the deleted (pre-auth-gate) AdminScaffoldTest.
 *
 * DB-backed; tagged `database`.
 */
@Tags("database")
class AdminPanelScaffoldAuthTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val seeded = mutableListOf<UUID>()
    afterEach {
        seeded.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seeded.clear()
    }

    fun seedAdmin(): AdminAuthTestSupport.SeededAdmin = AdminAuthTestSupport.seedAdmin(dataSource).also { seeded.add(it.id) }

    "authenticated GET /admin/ renders the frame-2 shell: nav, identity box, top bar, no footer" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/") {
                    header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$token")
                }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()

            // Landing content block (greeting identifies the index page).
            body shouldContain "Welcome back, Test Admin"

            // Grouped sidebar: exactly the six shipped nav items, each with
            // its data-icon-identified inline SVG (spec scenario "Authenticated
            // page extends the base layout and renders all structural sections").
            body shouldContain "<header>"
            body shouldContain "<nav>"
            body shouldContain "data-icon=\"dashboard\""
            body shouldContain "data-icon=\"flag\""
            body shouldContain "data-icon=\"group\""
            body shouldContain "data-icon=\"block\""
            body shouldContain "data-icon=\"timer\""
            body shouldContain "data-icon=\"auto_delete\""
            body shouldContain "data-icon=\"receipt_long\""
            body shouldContain "data-icon=\"toggle_on\""
            body shouldContain "data-icon=\"badge\""
            body shouldContain "data-icon=\"alternate_email\""
            body shouldContain "data-icon=\"credit_card\""
            body shouldContain "data-icon=\"gpp_maybe\""
            body shouldContain "data-icon=\"article\""
            body shouldContain "data-icon=\"download\""
            // EXACTLY fifteen nav items, under their six group headings: the
            // Moderasi group holds Dashboard + Reports + Appeals (admin-appeal-
            // review, content-moderation-appeal) + Users; the Premium group holds
            // Subscription grace (admin-subscription-grace-monitor); the Konfigurasi
            // group holds Feature flags (admin-feature-flag-editor) + Reserved
            // usernames (admin-reserved-usernames-editor) + Username oversight
            // (admin-premium-username-oversight); the Lifecycle group holds Privacy
            // flips (admin-privacy-flip-monitor) + Hard delete queue (admin-hard-
            // delete-queue) + Data export queue (admin-data-export-queue); the
            // Anti-abuse group holds the Block registry item (admin-block-registry)
            // + the CSAM detection log (admin-csam-detection-log); the Premium group
            // holds Subscription grace (admin-subscription-grace-monitor) + Referral
            // grants (admin-referral-manual-grant).
            Regex("class=\"nitem").findAll(body).count() shouldBe 16
            body shouldContain "Moderasi"
            body shouldContain "Anti-abuse &amp; keamanan"
            body shouldContain "Lifecycle"
            body shouldContain "Premium"
            body shouldContain "Konfigurasi"
            body shouldContain "Sistem"
            // Feature flags (admin-feature-flag-editor) + Reserved usernames
            // (admin-reserved-usernames-editor) shipped; other Usulan items +
            // board-annotation status dots are still absent.
            body shouldContain "Feature flags"
            body shouldContain "Reserved usernames"
            (body.contains("Post edit history")) shouldBe false
            (body.contains("Attestation review")) shouldBe false
            (body.contains("class=\"dot")) shouldBe false
            // No page footer — frame 2 has none (deliberate removal).
            (body.contains("<footer>")) shouldBe false

            // Identity box: role chip + display name + session line.
            body shouldContain "rolechip"
            body shouldContain "OWNER"
            body shouldContain "Session idle 30 m"
            body shouldContain "UTC"

            // Top bar: page title crumb + uppercased env chip.
            body shouldContain "envchip"
            body shouldContain AdminAuthTestSupport.TEST_ENVIRONMENT_NAME.uppercase()

            // Landing drops the static User moderation card (spec scenario)
            // and renders the CSRF info banner.
            (body.contains("User moderation")) shouldBe false
            body shouldContain "banner info"
        }
    }

    "bare GET /admin redirects 302 to /admin/ regardless of session" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val unauthed = client.get("/admin")
            unauthed.status shouldBe HttpStatusCode.Found
            unauthed.headers[HttpHeaders.Location] shouldBe "/admin/"
            (unauthed.bodyAsText().contains("Welcome back")) shouldBe false

            val authed =
                client.get("/admin") {
                    header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$token")
                }
            authed.status shouldBe HttpStatusCode.Found
            authed.headers[HttpHeaders.Location] shouldBe "/admin/"
            (authed.bodyAsText().contains("Welcome back")) shouldBe false
        }
    }

    "POST /admin (bare path) stays 404 — the redirect is GET-only" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.post("/admin")
            res.status shouldBe HttpStatusCode.NotFound
        }
    }

    "active nav item + crumb reflect the current page on /admin/reports" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.get("/admin/reports") {
                    header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$token")
                }.bodyAsText()
            // The Reports nav item carries the active-state class…
            body shouldContain "nitem active\" href=\"/admin/reports\""
            // …and the top-bar crumb names the page.
            body shouldContain "<div class=\"crumb\"><b>Reports</b></div>"
        }
    }

    "session line shows the absolute cap when it is the sooner bound" {
        val admin = seedAdmin()
        // Session within 10 minutes of its absolute cap: idle deadline
        // (now + 30 m) is LATER than expires_at → the cap must render.
        val cap = java.time.Instant.now().plusSeconds(10 * 60)
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id, expiresAt = cap)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.get("/admin/") {
                    header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$token")
                }.bodyAsText()
            val expected =
                java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                    .withZone(java.time.ZoneOffset.UTC)
                    .format(cap)
            body shouldContain "expires $expected UTC"
        }
    }

    "session line shows the idle deadline for a fresh session" {
        val admin = seedAdmin()
        // Default seed: expires_at = now + 8 h → idle deadline (now + 30 m)
        // is the sooner bound. Allow a one-minute boundary race.
        val before = java.time.Instant.now()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.get("/admin/") {
                    header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$token")
                }.bodyAsText()
            val fmt =
                java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                    .withZone(java.time.ZoneOffset.UTC)
            val candidates =
                listOf(0L, 60L, 120L).map { skew ->
                    "expires ${fmt.format(before.plusSeconds(30 * 60 + skew))} UTC"
                }
            (candidates.any { body.contains(it) }) shouldBe true
        }
    }

    "unauthenticated GET /admin/ redirects (302) to /admin/login" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/")
            res.status shouldBe HttpStatusCode.Found
            res.headers[HttpHeaders.Location] shouldBe "/admin/login"
        }
    }

    "unmapped admin route returns 404 regardless of auth state" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            // Per spec: 404 is returned at the routing layer regardless of auth.
            // (Ktor resolves an unmapped path to 404 before the authenticate
            // block's challenge fires for the matched-but-unauthenticated case.)
            client.get("/admin/nonexistent-page").status shouldBe HttpStatusCode.NotFound
            client.get("/admin/nonexistent-page") {
                header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$token")
            }.status shouldBe HttpStatusCode.NotFound
        }
    }

    "non-GET on /admin/ index returns 405" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            // /admin/ has only GET wired; POST → 405. Carry a valid session so
            // the auth gate passes and the routing layer's method-not-allowed
            // surfaces (not a redirect).
            val res =
                client.post("/admin/") {
                    header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$token")
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldBe HttpStatusCode.MethodNotAllowed
        }
    }

    "static HTMX asset is served publicly (no auth required)" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/static/htmx.min.js")
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "var htmx"
        }
    }

    "path-traversal under /admin/static/ does not serve a 2xx" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/static/../../some-other-resource")
            (res.status.value !in 200..299) shouldBe true
        }
    }

    "static stylesheet asset is served publicly (no auth required)" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/static/admin.css")
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "--primary"
        }
    }

    "unauthenticated login page extends layout but omits CSRF block" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/login").bodyAsText()
            // Layout extension is proven by the vendored panel stylesheet; the
            // authenticated shell (header/nav/footer) intentionally does not
            // render without a session (board frame 1, docs/11 § 3.6).
            body shouldContain "/admin/static/admin.css"
            (body.contains("<meta name=\"csrf-token\"")) shouldBe false
            (body.contains("htmx:configRequest")) shouldBe false
        }
    }
})
