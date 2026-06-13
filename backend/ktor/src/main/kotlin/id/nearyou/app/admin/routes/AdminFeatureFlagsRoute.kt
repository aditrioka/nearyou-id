package id.nearyou.app.admin.routes

import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.auth.AdminCsrfGate
import id.nearyou.app.admin.auth.AdminPrincipal
import id.nearyou.app.admin.auth.AdminRoleGate
import id.nearyou.app.admin.featureflags.FeatureFlagCatalog
import id.nearyou.app.admin.featureflags.FeatureFlagService
import id.nearyou.app.admin.featureflags.FeatureFlagToggleRateLimiter
import id.nearyou.app.admin.featureflags.FeatureFlagViewState
import id.nearyou.app.admin.featureflags.FlagKind
import id.nearyou.app.admin.featureflags.ToggleOutcome
import id.nearyou.app.admin.featureflags.ToggleRequest
import id.nearyou.app.common.clientIp
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.principal
import io.ktor.server.pebble.PebbleContent
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * `GET /admin/feature-flags` + `POST /admin/feature-flags/{key}` — the Feature
 * Flag Admin panel (`admin-feature-flags` capability, mockup frame 20).
 *
 * GET renders the canonical flag catalog with current Server-template values
 * (read-only when write credentials are absent). Each POST is a single-parameter
 * write: CSRF gate → owner/admin role gate (NOT [AdminRoleGate.requireWriteRole],
 * which admits moderator) → the [FeatureFlagService] gate chain (validation →
 * no-op → rate-limit → publish → audit). Dual-mode rendering mirrors the shipped
 * admin viewers: `HX-Request` → the `feature-flags-content.peb` fragment;
 * otherwise the full `feature-flags.peb` page (no-JS fallback).
 */
fun Route.adminFeatureFlags(
    service: FeatureFlagService,
    auditLogger: AdminAuditLogger,
    layout: AdminLayout,
) {
    get("/feature-flags") {
        call.respondFeatureFlags(layout, service.viewState())
    }

    post("/feature-flags/{key}") {
        if (!AdminCsrfGate.validateCsrf(call, auditLogger)) return@post
        if (!AdminRoleGate.requireOwnerOrAdmin(call)) return@post
        val principal =
            call.principal<AdminPrincipal>() ?: run {
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }
        val key = call.parameters["key"]
        if (key == null || FeatureFlagCatalog.editable(key) == null) {
            call.respondFeatureFlags(layout, service.viewState(), message = MSG_UNKNOWN_PARAM, status = HttpStatusCode.BadRequest)
            return@post
        }
        val form = AdminCsrfGate.formParametersAfterValidation(call)
        val outcome =
            service.toggle(
                ToggleRequest(
                    key = key,
                    rawValue = form["value"].orEmpty(),
                    reason = form["reason"].orEmpty(),
                    expectedEtag = form["etag"].orEmpty(),
                    actingAdminId = principal.adminId,
                    ip = call.clientIp,
                    userAgent = call.request.headers[HttpHeaders.UserAgent],
                ),
            )
        val (message, status) = outcome.toMessage(key)
        call.respondFeatureFlags(layout, service.viewState(), message = message, status = status)
    }
}

/** Map a [ToggleOutcome] to a user-facing inline message + HTTP status. */
private fun ToggleOutcome.toMessage(key: String): Pair<String, HttpStatusCode> =
    when (this) {
        ToggleOutcome.Applied -> "Updated $key." to HttpStatusCode.OK
        is ToggleOutcome.Invalid -> message to HttpStatusCode.BadRequest
        ToggleOutcome.NoOp -> "No change — $key is already set to that value." to HttpStatusCode.OK
        ToggleOutcome.RateLimited -> {
            val cap = FeatureFlagToggleRateLimiter.FEATURE_FLAG_TOGGLE_CAP
            "Feature-flag quota exceeded ($cap/hour). Try again later. No change made." to HttpStatusCode.OK
        }
        ToggleOutcome.Stale -> "Flags changed since this page loaded. Reload and retry. No change made." to HttpStatusCode.OK
        ToggleOutcome.WriteUnavailable ->
            "Writes unavailable — the admin service needs the Remote Config write role. No change made." to HttpStatusCode.OK
        ToggleOutcome.Failed -> "Publish failed. No change made." to HttpStatusCode.OK
    }

/**
 * Render the feature-flag surface: the `feature-flags-content.peb` fragment for
 * an `HX-Request`, otherwise the full `feature-flags.peb` page (which carries the
 * authenticated layout shell + CSRF meta so the no-JS `_csrf` hidden fields
 * populate). Every value is Pebble-autoescaped on output.
 */
private suspend fun ApplicationCall.respondFeatureFlags(
    layout: AdminLayout,
    state: FeatureFlagViewState,
    message: String? = null,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    val isHtmx = request.headers["HX-Request"] == "true"
    val model =
        buildMap<String, Any> {
            put("flags", buildFlagRows(state))
            put("lists", buildListRows(state))
            put("writeAvailable", state.writeAvailable)
            state.etag?.let { put("etag", it) }
            put("version", state.version ?: EM_DASH)
            put("thresholdMin", FeatureFlagCatalog.MATCH_THRESHOLD_MIN)
            put("thresholdMax", FeatureFlagCatalog.MATCH_THRESHOLD_MAX)
            put("cap", FeatureFlagToggleRateLimiter.FEATURE_FLAG_TOGGLE_CAP)
            message?.let { put("message", it) }
            if (!isHtmx) {
                layout.putShellModel(this@respondFeatureFlags, this, pageTitle = "Feature Flags", activePath = "/admin/feature-flags")
            }
        }
    val template = if (isHtmx) "feature-flags-content.peb" else "feature-flags.peb"
    respond(status, PebbleContent(template, model))
}

/** Pre-format each editable flag into a template view row (the control kind drives
 *  which inputs the template renders). NULL current value → unset. */
private fun buildFlagRows(state: FeatureFlagViewState): List<Map<String, Any>> =
    FeatureFlagCatalog.EDITABLE.map { def ->
        val value = state.values[def.key]
        buildMap<String, Any> {
            put("key", def.key)
            put("description", def.description)
            put("securitySensitive", def.securitySensitive)
            put("value", value ?: "")
            put("displayValue", value ?: EM_DASH)
            when (val kind = def.kind) {
                FlagKind.Bool -> {
                    put("kind", "bool")
                    put("on", value == "true")
                    // The value to submit when toggling = the opposite of the current state.
                    put("toggleTo", if (value == "true") "false" else "true")
                }
                is FlagKind.Enum -> {
                    put("kind", "enum")
                    put("enumValues", kind.values)
                }
                is FlagKind.IntRange -> {
                    put("kind", "int")
                    put("min", kind.min)
                    put("max", kind.max)
                }
                FlagKind.ReadOnlyList -> put("kind", "readonly")
            }
        }
    }

/** Pre-format the two read-only wordlists into count summaries. */
private fun buildListRows(state: FeatureFlagViewState): List<Map<String, Any>> =
    FeatureFlagCatalog.READ_ONLY_LISTS.map { def ->
        mapOf(
            "key" to def.key,
            "description" to def.description,
            "summary" to entryCountSummary(state.values[def.key]),
        )
    }

/** "<n> entries" for a JSON string-array value; "unset" for null; "malformed" otherwise. */
private fun entryCountSummary(raw: String?): String {
    if (raw == null) return "unset"
    return runCatching {
        val element = Json.parseToJsonElement(raw)
        if (element is JsonArray) "${element.size} entries" else "malformed"
    }.getOrDefault("malformed")
}

private const val EM_DASH = "—"
private const val MSG_UNKNOWN_PARAM = "Unknown or non-editable parameter."
