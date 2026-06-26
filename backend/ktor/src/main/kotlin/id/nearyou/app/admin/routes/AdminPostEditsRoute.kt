package id.nearyou.app.admin.routes

import id.nearyou.app.admin.postedits.PostEditHistoryService
import id.nearyou.app.admin.postedits.PostVersionView
import io.ktor.server.application.call
import io.ktor.server.pebble.PebbleContent
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.net.URLEncoder
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * `GET /admin/posts/{post_id}/edits` — the read-only Post Edit History viewer
 * (`admin-post-edit-history` capability, admin mockup frame 8). Renders one
 * post's complete temporal version history: the live `posts` row as the newest
 * ("Versi terbaru") version, then every prior `post_edits` snapshot newest-first.
 *
 * Wired INSIDE the `authenticate(ADMIN_AUTH_NAME) { ... }` block so the
 * `admin-login` session middleware gates it (302 → `/admin/login` when
 * unauthenticated). Read-only: ONLY `get` is wired — no mutation handler on the
 * path (a `POST`/`PUT`/`PATCH`/`DELETE` surfaces as a routing-layer 405 because
 * no handler runs). Serving the page writes no `admin_actions_log` row and
 * mutates nothing (reads are not auditable; content-moderation actions stay in
 * the Report Queue).
 *
 * The `{post_id}` path segment is parsed as a `UUID`; a non-UUID value resolves
 * to the not-found empty state (literal, never an exception, never interpolated
 * into SQL — the repository binds positionally). Accessible to EVERY
 * authenticated admin role including `read_only` (post content is public-facing,
 * so no private-content disclosure justifies a stricter gate — unlike chat
 * redaction).
 *
 * Dual-mode rendering (mirrors [adminBlockRegistry]):
 *  - `HX-Request: true` → respond with ONLY the `post-edits-table.peb` fragment
 *    (the page chrome stays; the `#post-edits-table` region swaps in place). The
 *    live-version cardlet lives inside the fragment but renders on the first page
 *    only, so paginating "older" drops it.
 *  - otherwise → render the full `post-edits.peb` page (extends `layout.peb`,
 *    includes the same fragment) so the viewer works without JavaScript and a
 *    paginated URL stays shareable.
 *
 * Every value reaches the template through Pebble autoescaping (content,
 * username, ids) — no `raw` filter — so markup in user content is entity-encoded,
 * never executed.
 */
fun Route.adminPostEdits(
    service: PostEditHistoryService,
    layout: AdminLayout,
) {
    get("/posts/{post_id}/edits") {
        // Parse the path segment to a UUID; a non-UUID (or absent) value → null,
        // which the service maps to the not-found empty state (no 500, no
        // injection — the value never reaches SQL).
        val postId = call.parameters["post_id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val cursorParam = call.request.queryParameters["cursor"]

        val view = service.history(postId, cursorParam)

        // The "older" control preserves the post id and carries the opaque
        // cursor. Only built when the service returned a next page (found + more
        // snapshots), so `postId` is non-null here.
        val olderUrl =
            view.nextCursor?.let { next ->
                "/admin/posts/${view.postId}/edits?cursor=${URLEncoder.encode(next, Charsets.UTF_8)}"
            }

        val model =
            buildMap<String, Any> {
                put("found", view.found)
                view.postId?.let { put("postId", it.toString()) }
                view.authorUsername?.let { put("authorUsername", it) }
                // Page-sub author deep-link to the SHIPPED /admin/users?q= lookup
                // (which is lenient UUID-or-username), so it degrades gracefully
                // when the username is absent.
                view.authorId?.let { id ->
                    val q = view.authorUsername ?: id.toString()
                    put("authorUrl", "/admin/users?q=${URLEncoder.encode(q, Charsets.UTF_8)}")
                }
                view.liveVersion?.let { put("liveVersion", it.toViewMap()) }
                put("snapshots", view.snapshots.map { it.toViewMap() })
                put("showNoHistoryNote", view.showNoHistoryNote)
                olderUrl?.let { put("olderUrl", it) }

                val isHtmx = call.request.headers["HX-Request"] == "true"
                if (!isHtmx) {
                    layout.putShellModel(
                        call,
                        this,
                        pageTitle = "Post edit history",
                        // Highlight Reports — this viewer is the read pair of the
                        // Report-Queue triage loop and is reached from there.
                        activePath = "/admin/reports",
                    )
                }
            }

        val template =
            if (call.request.headers["HX-Request"] == "true") {
                "post-edits-table.peb"
            } else {
                "post-edits.peb"
            }
        call.respond(PebbleContent(template, model))
    }
}

private val EDITED_AT_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

/**
 * Pre-format a version row for the template. `editedAt` renders via
 * [DateTimeFormatter.ISO_INSTANT] — always UTC with a `Z` suffix, never a
 * host-local interpretation. `authorUrl` deep-links to the SHIPPED
 * `/admin/users?q=` lookup; it falls back to the user id when the username is
 * absent (a hard-deleted author — referentially guarded but handled defensively)
 * so the link degrades rather than breaking the page. Pebble autoescaping
 * escapes `content` + the author display on output — load-bearing, as both are
 * user-controlled. The view map carries ONLY the boolean `locationChanged`; no
 * coordinate value is present (spec — the geography guard is at the type
 * boundary).
 *
 * `internal` (not `private`) so [id.nearyou.app.admin.postedits] unit tests can
 * assert the author-display degradation (task 4.7 — a referentially-impossible
 * but defensively-handled null username falls back to the user id, keeping the
 * deep-link intact) and the type-boundary geography guard (task 4.8) without a
 * DB round-trip.
 */
internal fun PostVersionView.toViewMap(): Map<String, Any> {
    val display = authorUsername ?: authorId.toString()
    return mapOf(
        "versionLabel" to versionLabel,
        "isLive" to isLive,
        "content" to content,
        "editedAt" to EDITED_AT_FORMAT.format(timestamp),
        "authorDisplay" to display,
        "authorUrl" to "/admin/users?q=${URLEncoder.encode(display, Charsets.UTF_8)}",
        "locationChanged" to locationChanged,
    )
}
