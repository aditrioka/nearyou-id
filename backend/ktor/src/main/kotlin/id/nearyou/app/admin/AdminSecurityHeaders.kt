package id.nearyou.app.admin

import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallSetup

/**
 * Security response headers on EVERY response the host serves (#531,
 * `admin-panel-scaffold` "Every response carries the admin security response headers").
 *
 * Host-wide, not keyed on an `/admin` path prefix: Ktor's router decodes and
 * collapses path segments, so `//admin/users` and `/%61dmin/login` reach the admin
 * handlers while a raw-path prefix check misses them (design D1). The set is inert
 * on the JSON API (no rendering context) and `nosniff` + HSTS help it; the backend
 * serves no other HTML. `CallSetup` runs before every plugin's `onCall`, so an early
 * rejection (the `RequestBodyLimit` 413) still carries the set. The CSP is strict
 * `'self'`: no inline script or style survives in the admin templates
 * (`AdminTemplateStyleTest`), panel JS lives in the vendored `admin.js`, and
 * `layout.peb` tells htmx not to inject a `<style>`, eval, re-run swapped scripts or
 * cache page snapshots (design D2–D4).
 */
val AdminSecurityHeaders =
    createApplicationPlugin("AdminSecurityHeaders") {
        on(CallSetup) { call ->
            ADMIN_SECURITY_HEADERS.forEach { (name, value) -> call.response.headers.append(name, value) }
        }
    }

val ADMIN_SECURITY_HEADERS: Map<String, String> =
    mapOf(
        "Content-Security-Policy" to
            "default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; " +
            "base-uri 'none'; form-action 'self'; frame-ancestors 'none'",
        "X-Frame-Options" to "DENY",
        "X-Content-Type-Options" to "nosniff",
        "Referrer-Policy" to "same-origin",
        // One year, no includeSubDomains (the host has none) and no preload (an
        // apex-domain list commitment, not this host's call). Ignored over HTTP.
        "Strict-Transport-Security" to "max-age=31536000",
    )
