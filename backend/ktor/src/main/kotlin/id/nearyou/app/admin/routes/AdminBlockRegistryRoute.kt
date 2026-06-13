package id.nearyou.app.admin.routes

import id.nearyou.app.admin.blockregistry.AdminBlockRegistryRepository
import id.nearyou.app.admin.blockregistry.BlockRegistryCursor
import id.nearyou.app.admin.blockregistry.BlockRegistryQuery
import id.nearyou.app.admin.blockregistry.BlockRegistryRow
import io.ktor.server.application.call
import io.ktor.server.pebble.PebbleContent
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.net.URLEncoder
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * `GET /admin/blocks` — the read-only Block User Registry viewer
 * (`admin-block-registry` capability, admin mockup frame 12).
 *
 * Wired INSIDE the `authenticate(ADMIN_AUTH_NAME) { ... }` block so the
 * `admin-login` session middleware gates it (302 → `/admin/login` when
 * unauthenticated). Read-only: only GET is wired — no mutation handlers. The
 * admin never blocks/unblocks on behalf of a user; enforcement stays in the
 * product path via the bidirectional NOT-IN join (`BlockExclusionJoinRule`).
 *
 * Dual-mode rendering (mirrors [adminRejectedIdentifiers]):
 *  - `HX-Request: true` → respond with ONLY the `block-registry-table.peb`
 *    fragment (the search form + layout stay put; the table swaps in place).
 *  - otherwise → render the full `block-registry.peb` page (extends
 *    `layout.peb`, includes the same fragment) so the feature works without
 *    JavaScript and a filtered/paginated URL stays shareable.
 *
 * Search parsing is lenient (design.md D4): the single `q` parameter is trimmed
 * and length-bounded; if it parses as a UUID it matches `blocker_id`/`blocked_id`
 * on either side, otherwise it is an EXACT case-insensitive username match on
 * either side. A blank `q` is unfiltered; a `q` matching nothing yields the
 * empty state, never an error. All applied values reach the repository as
 * positionally-bound `PreparedStatement` parameters, never interpolated SQL.
 *
 * CSRF token: the full-page render derives `csrfToken` from the session cookie
 * via [AdminLayout.putShellModel], so `layout.peb`'s CSRF meta tag + htmx hook +
 * logout-form token render. The fragment response omits it. Reads are not
 * auditable, so the handler writes NO audit row.
 */
fun Route.adminBlockRegistry(
    repo: AdminBlockRegistryRepository,
    layout: AdminLayout,
) {
    get("/blocks") {
        val params = call.request.queryParameters

        // Lenient search parse: trim, drop-if-blank, length-bound to the
        // username column width (an over-long value is bounded first, then —
        // failing UUID parse and matching no ≤60-char username — yields the
        // empty state rather than a 400/500).
        val rawSearch = params["q"]?.trim()?.takeIf { it.isNotEmpty() }?.take(USERNAME_MAX)
        val searchUserId = rawSearch?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        // If the term is a UUID it searches by id; otherwise by exact username.
        val searchUsername = if (searchUserId == null) rawSearch else null

        val cursor = BlockRegistryCursor.decode(params["cursor"])

        val query =
            BlockRegistryQuery(
                searchUserId = searchUserId,
                searchUsername = searchUsername,
                cursor = cursor,
            )
        val page = repo.query(query)

        // Echo the active search back (the canonical, post-parse value) so the
        // form repopulates + the "older" link preserves it.
        val activeFilters =
            buildList {
                rawSearch?.let { add("q" to it) }
            }
        val filters = activeFilters.toMap()

        val olderUrl =
            page.nextCursor?.let { next ->
                val pairs = activeFilters + ("cursor" to next.encode())
                "/admin/blocks?" +
                    pairs.joinToString("&") { (k, v) ->
                        "$k=${URLEncoder.encode(v, Charsets.UTF_8)}"
                    }
            }

        val model =
            buildMap<String, Any> {
                put("rows", page.rows.map { it.toViewMap() })
                put("hasRows", page.rows.isNotEmpty())
                put("filters", filters)
                olderUrl?.let { put("olderUrl", it) }

                val isHtmx = call.request.headers["HX-Request"] == "true"
                if (!isHtmx) {
                    layout.putShellModel(
                        call,
                        this,
                        pageTitle = "Block registry",
                        activePath = "/admin/blocks",
                    )
                }
            }

        val template =
            if (call.request.headers["HX-Request"] == "true") {
                "block-registry-table.peb"
            } else {
                "block-registry.peb"
            }
        call.respond(PebbleContent(template, model))
    }
}

private const val USERNAME_MAX = 60 // users.username VARCHAR(60) column width

private val CREATED_AT_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

/**
 * Pre-format a row into display values for the template. `createdAt` renders via
 * [DateTimeFormatter.ISO_INSTANT] — always UTC with a `Z` suffix, never a
 * host-local interpretation. The `blockerUrl` / `blockedUrl` deep-links target
 * the SHIPPED `/admin/users?q=` lookup (design.md D5), NOT the in-flight
 * `/admin/users/{id}` profile page. Pebble autoescaping escapes the usernames on
 * output — load-bearing here, because `username` is user-controlled free text.
 */
private fun BlockRegistryRow.toViewMap(): Map<String, Any> =
    mapOf(
        "blockerUsername" to blockerUsername,
        "blockedUsername" to blockedUsername,
        "blockerUrl" to "/admin/users?q=${URLEncoder.encode(blockerUsername, Charsets.UTF_8)}",
        "blockedUrl" to "/admin/users?q=${URLEncoder.encode(blockedUsername, Charsets.UTF_8)}",
        "createdAt" to CREATED_AT_FORMAT.format(createdAt),
        "bidirectional" to bidirectional,
    )
