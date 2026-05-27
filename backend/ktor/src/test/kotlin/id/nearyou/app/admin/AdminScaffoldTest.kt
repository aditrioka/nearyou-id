package id.nearyou.app.admin

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.pebble.Pebble
import io.ktor.server.pebble.PebbleContent
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.pebbletemplates.pebble.loader.ClasspathLoader
import org.slf4j.LoggerFactory
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * Tests for the admin-panel-ktor-htmx-bootstrap scaffold (PR #115).
 *
 * Covers all 11 scenarios across the admin-panel-scaffold capability's
 * 5 requirements. Mirror of the StringSpec style used by the existing
 * SuspensionUnbanWorkerTest + UnbanWorkerRouteTest.
 */
class AdminScaffoldTest : StringSpec({

    val adminModuleLogger = LoggerFactory.getLogger("id.nearyou.app.admin.AdminModule") as LogbackLogger

    fun attachLogCapture(): ListAppender<ILoggingEvent> {
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        adminModuleLogger.addAppender(appender)
        return appender
    }

    fun detachLogCapture(appender: ListAppender<ILoggingEvent>) {
        adminModuleLogger.detachAppender(appender)
        appender.stop()
    }

    // ----------------- Req 1 Sc 1 + Req 4 Sc 1 -----------------
    "GET /admin/ returns 200 with 'Admin Panel' identifier (unauthenticated)" {
        testApplication {
            application { admin() }
            // NO Authorization header, NO session cookie, NO X-CSRF-Token —
            // verifies spec Req 4 Sc 1 unauthenticated-subtree posture.
            val response = client.get("/admin/")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "Admin Panel"
        }
    }

    // ----------------- Req 1 Sc 2 -----------------
    "GET /admin/nonexistent-page returns 404 (route subtree exists, path not found)" {
        testApplication {
            application { admin() }
            val response = client.get("/admin/nonexistent-page")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    // ----------------- Req 1 Sc 3 -----------------
    "POST /admin/ returns 405 Method Not Allowed (only GET wired)" {
        testApplication {
            application { admin() }
            val response = client.post("/admin/")
            // Ktor's default for an unhandled method on a wired path is 405.
            // If implementation observes 404 instead, the route is not
            // method-fenced and Ktor's routing tree didn't see the path as
            // 'wired' — that's a regression worth catching here.
            response.status shouldBe HttpStatusCode.MethodNotAllowed
        }
    }

    // ----------------- Req 2 Sc 1 -----------------
    "Index page extends base layout (header/nav/footer/content-block markers)" {
        testApplication {
            application { admin() }
            val body = client.get("/admin/").bodyAsText()
            // Structural layout-inheritance markers from layout.peb.
            body shouldContain "<header>"
            body shouldContain "<nav>"
            body shouldContain "<footer>"
            // Content block from index.peb extending layout.peb.
            body shouldContain "Admin Panel"
        }
    }

    // ----------------- Req 3 Sc 1 -----------------
    "Admin index loads HTMX via vendored static asset" {
        testApplication {
            application { admin() }
            val body = client.get("/admin/").bodyAsText()
            // Matches both self-closing and explicit-end-tag <script> forms,
            // and is robust to attribute reorder (e.g., `defer` before/after `src`).
            body shouldContain "/admin/static/htmx.min.js"
        }
    }

    // ----------------- Req 3 Sc 2 -----------------
    "GET /admin/static/htmx.min.js returns 200 + accept-list Content-Type + HTMX body" {
        testApplication {
            application { admin() }
            val response: HttpResponse = client.get("/admin/static/htmx.min.js")
            response.status shouldBe HttpStatusCode.OK
            // Accept BOTH application/javascript AND text/javascript per spec
            // Req 3 Sc 2 accept-list (Ktor's MIME-table for `.js` varies by JDK).
            val rawCt =
                response.headers[HttpHeaders.ContentType]
                    ?: error("missing Content-Type header on static asset response")
            val parsed = ContentType.parse(rawCt)
            val acceptable =
                setOf(
                    ContentType.Application.JavaScript,
                    ContentType.Text.JavaScript,
                )
            val matched =
                acceptable.any { expected ->
                    parsed.contentType == expected.contentType &&
                        parsed.contentSubtype == expected.contentSubtype
                }
            matched shouldBe true
            // Body sanity: vendored HTMX file starts with `var htmx=function()`
            // and contains the version literal. Defense against an empty or
            // corrupted vendoring (e.g., if CI's `sha256sum -c` ever regresses
            // OR if a future re-vendor accidentally ships a 0-byte placeholder).
            // Test-coverage lens N4 from PR #115 step-8 review.
            val body = response.bodyAsText()
            body shouldContain "var htmx"
            body shouldContain "version:\"2.0.4\""
        }
    }

    // ----------------- Req 3 Sc 3 -----------------
    "Path-traversal under /admin/static/ does not serve a 2xx (raw + URL-encoded)" {
        testApplication {
            application { admin() }
            // Raw `..` traversal — typically normalized by the HTTP client OR by
            // Ktor's routing layer before reaching the static handler. The
            // request falls through to no route → 404 Not Found.
            val raw = client.get("/admin/static/../../some-other-resource").status
            (raw.value !in 200..299) shouldBe true
            // URL-encoded `..` (`%2E%2E`) — NOT normalized client-side.
            // Local testApplication: Ktor's URL parser rejects with 400 Bad
            // Request (request never reaches the static handler). Production
            // (Cloudflare → Google Frontend → Cloud Run → Ktor): Google
            // Frontend normalizes `%2E%2E` upstream and issues a 302 redirect
            // to the normalized URL (which then 404s). Both satisfy the
            // security property; assert non-2xx to cover BOTH environments
            // consistently — narrower 4xx assertion would falsely fail
            // against staging (verified 2026-05-28 on PR #115). Per spec Req
            // 3 Sc 3 (re-amended during /opsx:apply step-8 security review).
            val encoded = client.get("/admin/static/%2E%2E/config").status
            (encoded.value !in 200..299) shouldBe true
        }
    }

    // ----------------- Req 4 Sc 2 -----------------
    "WARN log emitted on /admin/ hit (or at bootstrap) with required markers" {
        val appender = attachLogCapture()
        try {
            testApplication {
                application { admin() }
                client.get("/admin/")
            }
            // Accept either bootstrap-only emission shape OR per-request
            // emission shape (per spec Req 4 Sc 2 alternatives (i) and (ii)).
            // Our AdminModule.kt implements BOTH — bootstrap log AND
            // per-request interceptor — so we expect at least one WARN with
            // both required substrings.
            val warnEvents =
                appender.list
                    .filter { it.level == Level.WARN }
                    .filter { it.formattedMessage.contains("admin-login-argon2-totp") }
                    .filter { it.formattedMessage.contains("unauthenticated") }
            (warnEvents.isNotEmpty()) shouldBe true
        } finally {
            detachLogCapture(appender)
        }
    }

    // ----------------- Req 4 Sc 2 (negative case) -----------------
    "Per-request WARN log does NOT fire on non-/admin/* requests OR on /admin/static/*" {
        val appender = attachLogCapture()
        try {
            testApplication {
                application {
                    admin()
                    // Wire a throwaway non-admin route so we have a 200-returning
                    // path outside /admin/* to exercise.
                    routing {
                        get("/throwaway-non-admin") {
                            call.respond(HttpStatusCode.OK, "ok")
                        }
                    }
                }
                // Path #1: completely non-admin route — verifies the
                // path-prefix scoping (3802843 fix lock-in).
                client.get("/throwaway-non-admin").status shouldBe HttpStatusCode.OK
                // Path #2: /admin/static/* asset — verifies the static-asset
                // exemption clause. Per-request WARN log is noise-suppressed
                // for static-asset GETs since they have no business surface,
                // just serve the vendored HTMX file.
                client.get("/admin/static/htmx.min.js").status shouldBe HttpStatusCode.OK
            }
            // ZERO per-request `admin_route_hit` lines should appear from
            // EITHER of the two requests above. The bootstrap
            // `admin_module_mounted` line is fine (one-time at boot) —
            // filter for the per-request shape specifically.
            val perRequestEvents =
                appender.list
                    .filter { it.level == Level.WARN }
                    .filter { it.formattedMessage.contains("admin_route_hit") }
            perRequestEvents.size shouldBe 0
        } finally {
            detachLogCapture(appender)
        }
    }

    // ----------------- Req 5 Sc 1 -----------------
    "KTOR_ENV=production mount guard prevents /admin/ registration" {
        val appender = attachLogCapture()
        try {
            testApplication {
                environment {
                    config = MapApplicationConfig("ktor.environment" to "production")
                }
                application { admin() }
                // Route never mounted in prod → 404.
                client.get("/admin/").status shouldBe HttpStatusCode.NotFound
            }
            // Bootstrap WARN with `admin module skipped` + `admin-login-argon2-totp`.
            val skipEvents =
                appender.list
                    .filter { it.level == Level.WARN }
                    .filter { it.formattedMessage.contains("admin_module_skipped") }
                    .filter { it.formattedMessage.contains("admin-login-argon2-totp") }
            (skipEvents.isNotEmpty()) shouldBe true
        } finally {
            detachLogCapture(appender)
        }
    }

    // ----------------- Req 5 Sc 2 -----------------
    "KTOR_ENV=staging mount succeeds (cross-check with default-env path)" {
        testApplication {
            environment {
                config = MapApplicationConfig("ktor.environment" to "staging")
            }
            application { admin() }
            client.get("/admin/").status shouldBe HttpStatusCode.OK
        }
    }

    // ----------------- Decision 5 template-not-found smoke -----------------
    "Template-not-found smoke: missing template surfaces as non-2xx response" {
        testApplication {
            application(installPebbleAndRouteMissing())
            val response = client.get("/throwaway-render-missing")
            // Pebble throws LoaderException on missing template → Ktor's
            // default error handler returns 500. Asserting non-2xx covers
            // both 500 (default) and any future StatusPages override that
            // maps loader errors to a different status.
            (response.status.value in 200..299) shouldBe false
        }
    }
})

/**
 * Helper extracted to disambiguate the `install(Pebble)` receiver — calling it
 * directly inside the `testApplication { application { ... } }` nested lambda
 * triggers an "implicit receiver" compile error because `install` is defined
 * on both `Application` (the inner lambda's receiver) and surfaces transitively
 * on `ApplicationTestBuilder`. Capturing the body as an explicit `Application
 * .() -> Unit` function reference eliminates the ambiguity.
 */
private fun installPebbleAndRouteMissing(): Application.() -> Unit =
    {
        install(Pebble) {
            loader(ClasspathLoader().apply { prefix = "templates/admin" })
        }
        routing {
            get("/throwaway-render-missing") {
                call.respond(PebbleContent("nonexistent.peb", emptyMap()))
            }
        }
    }
