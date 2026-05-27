package id.nearyou.app.admin

import id.nearyou.app.admin.routes.adminIndex
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.pebble.Pebble
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.pebbletemplates.pebble.loader.ClasspathLoader
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("id.nearyou.app.admin.AdminModule")

// ---------------------------------------------------------------------------
// admin-panel-ktor-htmx-bootstrap — Admin #2 scaffold (PR #115).
//
// This module mounts the /admin/* route subtree WITHOUT an authentication
// gate. The auth gate (Argon2id password + TOTP + __Host-admin_session
// cookie with csrf_token_hash) lands in Admin #3 (admin-login-argon2-totp).
// The unauthenticated posture here is INTENTIONAL and TIME-BOUND:
//
//   - Spec Req 4 of admin-panel-scaffold capability mandates the
//     unauthenticated-subtree posture for scaffold validation.
//   - Spec Req 5 (this change's `KTOR_ENV != "production"` mount guard)
//     ensures the unauthenticated routes are STRUCTURALLY UNREACHABLE in
//     production until Admin #3 lifts the guard.
//   - Per-route WARN log (below) makes the unauthenticated state observable
//     in Sentry — its disappearance signals Admin #3's auth gate landing.
//
// When Admin #3 ships:
//   1. Remove the `production` mount guard below.
//   2. Replace the per-route WARN interceptor with the CSRF + session check.
//   3. Update this comment block.
// ---------------------------------------------------------------------------
fun Application.admin() {
    // Read the canonical KTOR_ENV key used elsewhere in this Application
    // (see Application.kt lines 190, 399 for the precedent). Note: task 2.2
    // suggests `ktor.deployment.environment` but the project's actual config
    // key (per application.conf line 9 + Application.kt usages) is
    // `ktor.environment`. Using the project-canonical key.
    val ktorEnv = environment.config.propertyOrNull("ktor.environment")?.getString() ?: "dev"

    // Decision 6: KTOR_ENV != "production" mount guard. Defense-in-depth at
    // the platform level so the unauthenticated scaffold is structurally
    // unreachable in production until Admin #3 lifts the guard.
    if (ktorEnv == "production") {
        log.warn(
            "event=admin_module_skipped env={} reason=unauthenticated_scaffold mount_guard_lifts_in=admin-login-argon2-totp",
            ktorEnv,
        )
        return
    }

    // Bootstrap WARN log: spec Req 4 Sc 2 (bootstrap-only emission shape) +
    // spec Req 5 Sc 1 (positive observability of mount success in non-prod).
    // Contains both required substrings:
    //   - admin-login-argon2-totp (search marker for the future-gate ref)
    //   - unauthenticated scaffold (posture identifier)
    log.warn(
        "event=admin_module_mounted env={} posture=unauthenticated_scaffold auth_gate_lands_in=admin-login-argon2-totp",
        ktorEnv,
    )

    install(Pebble) {
        loader(
            ClasspathLoader().apply {
                prefix = "templates/admin"
            },
        )
    }

    routing {
        route("/admin") {
            // Per-request WARN log: spec Req 4 Sc 2 (route-level interceptor
            // emission shape). Fires once per /admin/* hit. Skipped on
            // static-asset GETs to reduce noise — they have no business
            // surface, just serve a vendored JS file.
            //
            // Contains the same required substrings as the bootstrap log
            // above so log-capture tests can match either emission shape.
            intercept(ApplicationCallPipeline.Plugins) {
                val path = call.request.local.uri
                if (!path.startsWith("/admin/static/")) {
                    log.warn(
                        "event=admin_route_hit path={} posture=unauthenticated_scaffold auth_gate_lands_in=admin-login-argon2-totp",
                        path,
                    )
                }
            }

            // Index route — extracted to AdminIndexRoute.kt for clarity.
            adminIndex()

            // Static assets (vendored HTMX + any future admin CSS/JS) served
            // from src/main/resources/admin/static/ via Ktor's classpath
            // static-resource handler. Path-traversal sanitization is built
            // in (classpath getResource() does not resolve `..` segments) —
            // verified by spec Req 3 Sc 3's path-traversal test.
            //
            // Inner path is /static because outer route("/admin") already
            // prefixes the URL — final URL is /admin/static/<file>.
            staticResources("/static", "admin/static")
        }
    }
}
