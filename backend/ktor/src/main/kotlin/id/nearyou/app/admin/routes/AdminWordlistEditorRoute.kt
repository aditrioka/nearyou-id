package id.nearyou.app.admin.routes

import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.auth.AdminCsrfGate
import id.nearyou.app.admin.auth.AdminPrincipal
import id.nearyou.app.admin.auth.AdminRoleGate
import id.nearyou.app.admin.wordlist.WordlistDiff
import id.nearyou.app.admin.wordlist.WordlistEditOutcome
import id.nearyou.app.admin.wordlist.WordlistEditRateLimiter
import id.nearyou.app.admin.wordlist.WordlistEditRequest
import id.nearyou.app.admin.wordlist.WordlistEditorService
import id.nearyou.app.admin.wordlist.WordlistPreview
import id.nearyou.app.admin.wordlist.WordlistTarget
import id.nearyou.app.admin.wordlist.WordlistViewState
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

/**
 * `GET /admin/feature-flags/wordlists/{list}` + `POST …/{list}/preview` + `POST
 * …/{list}` — the moderation wordlist editor (`admin-moderation-wordlist-editor`
 * capability, the frame-20 "edit" sub-surface). `{list}` ∈ `profanity` | `uu_ite`.
 *
 * GET renders the current list (read-only when write credentials are absent OR the
 * role is below owner/admin). `/preview` merges the staged entries + add-single +
 * bulk import and renders the diff WITHOUT publishing. The base POST is the gated
 * write: CSRF gate → owner/admin role gate (NOT [AdminRoleGate.requireWriteRole],
 * which admits moderator) → the [WordlistEditorService] gate chain (validation →
 * empty-list → no-op → rate-limit → publish → audit). Dual-mode rendering mirrors
 * the shipped admin viewers (`HX-Request` → the content fragment).
 */
fun Route.adminWordlistEditor(
    service: WordlistEditorService,
    auditLogger: AdminAuditLogger,
    layout: AdminLayout,
) {
    get("/feature-flags/wordlists/{list}") {
        val target = WordlistTarget.fromSlug(call.parameters["list"]) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondEditor(layout, service, target, service.viewState(target))
    }

    post("/feature-flags/wordlists/{list}/preview") {
        val target = WordlistTarget.fromSlug(call.parameters["list"]) ?: return@post call.respond(HttpStatusCode.NotFound)
        if (!AdminCsrfGate.validateCsrf(call, auditLogger)) return@post
        if (!AdminRoleGate.requireOwnerOrAdmin(call)) return@post
        val form = AdminCsrfGate.formParametersAfterValidation(call)
        val preview =
            service.preview(
                target = target,
                entriesText = form["entries"].orEmpty(),
                addText = form["add"].orEmpty(),
                importText = form["import"].orEmpty(),
            )
        call.respondEditor(layout, service, target, preview.viewState, preview = preview)
    }

    post("/feature-flags/wordlists/{list}") {
        val target = WordlistTarget.fromSlug(call.parameters["list"]) ?: return@post call.respond(HttpStatusCode.NotFound)
        if (!AdminCsrfGate.validateCsrf(call, auditLogger)) return@post
        if (!AdminRoleGate.requireOwnerOrAdmin(call)) return@post
        val principal =
            call.principal<AdminPrincipal>() ?: run {
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }
        val form = AdminCsrfGate.formParametersAfterValidation(call)
        val outcome =
            service.publish(
                WordlistEditRequest(
                    target = target,
                    entriesText = form["entries"].orEmpty(),
                    addText = form["add"].orEmpty(),
                    importText = form["import"].orEmpty(),
                    reason = form["reason"].orEmpty(),
                    expectedEtag = form["etag"].orEmpty(),
                    actingAdminId = principal.adminId,
                    ip = call.clientIp,
                    userAgent = call.request.headers[HttpHeaders.UserAgent],
                ),
            )
        val (message, status) = outcome.toMessage(target)
        // Re-fetch state so a successful publish renders the updated list.
        call.respondEditor(layout, service, target, service.viewState(target), message = message, status = status)
    }
}

/** Map a [WordlistEditOutcome] to a user-facing inline message + HTTP status. */
private fun WordlistEditOutcome.toMessage(target: WordlistTarget): Pair<String, HttpStatusCode> =
    when (this) {
        is WordlistEditOutcome.Applied ->
            "Updated ${target.label} — ${diff.added} added, ${diff.removed} removed (${diff.resultingTotal} total). " +
                "Takes effect after the ≤5-minute moderation cache TTL." to HttpStatusCode.OK
        is WordlistEditOutcome.Invalid -> message to HttpStatusCode.BadRequest
        WordlistEditOutcome.NoOp -> "No change — the resulting list matches the current one." to HttpStatusCode.OK
        WordlistEditOutcome.RateLimited ->
            "Wordlist-edit quota exceeded (${WordlistEditRateLimiter.WORDLIST_EDIT_CAP}/hour). Try again later. No change made." to
                HttpStatusCode.OK
        WordlistEditOutcome.Stale ->
            "The template changed since this page loaded. Reload and re-apply. No change made." to HttpStatusCode.OK
        WordlistEditOutcome.WriteUnavailable -> WordlistEditorService.WRITE_UNAVAILABLE_MESSAGE to HttpStatusCode.OK
        WordlistEditOutcome.Failed -> "Publish failed. No change made." to HttpStatusCode.OK
    }

/**
 * Render the wordlist editor: the `wordlist-editor-content.peb` fragment for an
 * `HX-Request`, otherwise the full `wordlist-editor.peb` page (layout shell + CSRF
 * meta). Every value is Pebble-autoescaped on output. When a [preview] is supplied,
 * the staged list + diff + import report render; otherwise the current list renders.
 */
private suspend fun ApplicationCall.respondEditor(
    layout: AdminLayout,
    service: WordlistEditorService,
    target: WordlistTarget,
    state: WordlistViewState,
    preview: WordlistPreview? = null,
    message: String? = null,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    val role = principal<AdminPrincipal>()?.role
    val canWrite = state.writeAvailable && role in AdminRoleGate.OWNER_ADMIN_ROLES
    val entries = preview?.staged ?: state.entries
    val isHtmx = request.headers["HX-Request"] == "true"
    val quotaUsed = principal<AdminPrincipal>()?.let { service.quotaUsed(it.adminId) } ?: 0

    val model =
        buildMap<String, Any> {
            put("listSlug", target.slug)
            put("listParam", target.parameterName)
            put("listLabel", target.label)
            put("entries", entries)
            put("entriesText", entries.joinToString("\n"))
            put("entryCount", entries.size)
            put("writeAvailable", state.writeAvailable)
            put("canWrite", canWrite)
            state.etag?.let { put("etag", it) }
            put("version", state.version ?: EM_DASH)
            put("quotaUsed", quotaUsed)
            put("quotaCap", WordlistEditRateLimiter.WORDLIST_EDIT_CAP)
            preview?.diff?.let { putDiff(this, it) }
            preview?.report?.let { r ->
                put("importAdded", r.importAdded)
                put("importDuplicatesSkipped", r.importDuplicatesSkipped)
                put("importCommentBlankSkipped", r.importCommentBlankSkipped)
            }
            (message ?: preview?.message)?.let { put("message", it) }
            if (!isHtmx) {
                layout.putShellModel(this@respondEditor, this, pageTitle = "Feature Flags", activePath = "/admin/feature-flags")
            }
        }
    val template = if (isHtmx) "wordlist-editor-content.peb" else "wordlist-editor.peb"
    respond(status, PebbleContent(template, model))
}

private fun putDiff(
    model: MutableMap<String, Any>,
    diff: WordlistDiff,
) {
    model["diffAdded"] = diff.added
    model["diffRemoved"] = diff.removed
    model["diffResulting"] = diff.resultingTotal
}

private const val EM_DASH = "—"
