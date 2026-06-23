package id.nearyou.app.admin.routes

import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.auth.AdminCsrfGate
import id.nearyou.app.admin.auth.AdminPrincipal
import id.nearyou.app.admin.auth.AdminRoleGate
import id.nearyou.app.admin.ratelimit.CsamKominfoReportRateLimiter
import id.nearyou.app.admin.ratelimit.CsamMetadataDecryptRateLimiter
import id.nearyou.app.admin.ratelimit.DestructiveActionRateLimiter
import id.nearyou.app.common.AppJson
import id.nearyou.app.common.clientIp
import id.nearyou.app.moderation.csam.CsamArchiveCursor
import id.nearyou.app.moderation.csam.CsamArchiveQuery
import id.nearyou.app.moderation.csam.CsamArchiveRow
import id.nearyou.app.moderation.csam.CsamDetectionService
import id.nearyou.app.moderation.csam.CsamKominfoStatus
import id.nearyou.app.moderation.csam.CsamMetadataEncryptor
import id.nearyou.app.moderation.csam.CsamRepository
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * The `admin-csam-detection-log` surface (admin board frame f13) — a CHILD-SAFETY
 * operator panel, all wired INSIDE `authenticate(ADMIN_AUTH_NAME)` (AdminModule), so the
 * session middleware gates every route (302 → `/admin/login` when unauthenticated):
 *
 *  - `GET  /admin/csam`                    — read-only keyset detection-log viewer (ANY admin role).
 *  - `POST /admin/csam/takedown`           — invoke the shipped fixed-policy takedown in-process (owner/admin).
 *  - `POST /admin/csam/{id}/kominfo-report`— record the Kominfo filing, releasing the row to purge (owner/admin).
 *  - `POST /admin/csam/{id}/decrypt`       — audited on-demand metadata decrypt (owner/admin).
 *
 * **HARD INVARIANT (design D3):** no code path here fetches, proxies, or renders image
 * bytes or any image content URL. The viewer projects only scalar columns; the decrypt
 * fragment surfaces only the JSON metadata #358 archived (uploader id / post id / source /
 * cascade count — the raw pseudonymous uploader UUID is intended, design D4).
 *
 * The GET mirrors the read-only-viewer pattern ([adminDeletionQueue] / [adminActionsLog]):
 * dual-mode render — `HX-Request: true` swaps only the `csam-log-table.peb` fragment,
 * otherwise the full `csam-log.peb` page (works without JS; a filtered/paginated URL stays
 * shareable). The three POSTs mirror the gate order of [adminDeletionQueue] verbatim:
 * CSRF FIRST ([AdminCsrfGate.validateCsrf]; 403 + `admin_csrf_violation` on miss, BEFORE
 * the role gate so the violation is audited, not masked by a silent role-403) →
 * [AdminRoleGate.requireOwnerOrAdmin] (owner/admin only; 403 otherwise) → field validation
 * → the action.
 */
fun Route.adminCsam(
    repo: CsamRepository,
    detectionService: CsamDetectionService,
    encryptor: CsamMetadataEncryptor,
    destructiveRateLimiter: DestructiveActionRateLimiter,
    kominfoRateLimiter: CsamKominfoReportRateLimiter,
    decryptRateLimiter: CsamMetadataDecryptRateLimiter,
    auditLogger: AdminAuditLogger,
    layout: AdminLayout,
    dataSource: javax.sql.DataSource,
) {
    get("/csam") {
        call.respondCsamLog(repo, layout, message = null)
    }

    post("/csam/takedown") {
        if (!AdminCsrfGate.validateCsrf(call, auditLogger)) return@post
        if (!AdminRoleGate.requireOwnerOrAdmin(call)) return@post
        val body = AdminCsrfGate.formParametersAfterValidation(call)
        val imageId = body[FIELD_IMAGE_ID]?.trim()?.takeIf { it.isNotEmpty() }
        val imageHash = body[FIELD_IMAGE_HASH]?.trim()?.takeIf { it.isNotEmpty() }
        if (imageId == null || imageHash == null) {
            call.respond(HttpStatusCode.BadRequest, MSG_TAKEDOWN_FIELDS_REQUIRED)
            return@post
        }
        val principal =
            call.principal<AdminPrincipal>() ?: run {
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }

        // Route-level PRE-FLIGHT destructive-budget check (design D5): because the
        // service owns its own transaction + writes its own audit row, the in-tx
        // limiter placement used by suspend/ban does not apply here — read the
        // `admin_actions_log` trail (the SAME shared DestructiveActionRateLimiter)
        // BEFORE invoking the service. At/over the 20/hr cap → reject, service NOT invoked.
        val overBudget =
            dataSource.connection.use { conn -> destructiveRateLimiter.isAtOrOverCap(conn, principal.adminId) }
        if (overBudget) {
            call.respondCsamLog(repo, layout, message = MSG_TAKEDOWN_RATE_LIMITED)
            return@post
        }

        val result =
            detectionService.handleDetection(
                CsamDetectionService.Input(
                    cfImageId = imageId,
                    imageHash = imageHash,
                    cfMatchId = null,
                    ncmecReference = null,
                    source = CsamDetectionService.Source.ADMIN_MANUAL,
                    actorAdminId = principal.adminId,
                    ip = call.clientIp,
                    userAgent = call.request.headers[HttpHeaders.UserAgent],
                ),
            )
        val message = if (result.uploaderResolved) MSG_TAKEDOWN_ACTIONED else MSG_TAKEDOWN_ARCHIVED
        if (call.isHtmx()) {
            call.respondCsamLog(repo, layout, message = message)
        } else {
            // PRG for the no-JS path: redirect back to the list (the new archive row shows).
            call.response.headers.append(HttpHeaders.Location, "/admin/csam")
            call.respond(HttpStatusCode.SeeOther)
        }
    }

    post("/csam/{id}/kominfo-report") {
        if (!AdminCsrfGate.validateCsrf(call, auditLogger)) return@post
        if (!AdminRoleGate.requireOwnerOrAdmin(call)) return@post
        val body = AdminCsrfGate.formParametersAfterValidation(call)
        val archiveId =
            call.parseArchiveId() ?: run {
                call.respond(HttpStatusCode.BadRequest, MSG_INVALID_ID)
                return@post
            }
        val reportId =
            body[FIELD_REPORT_ID]?.trim()?.takeIf { it.isNotEmpty() }?.take(REPORT_ID_MAX) ?: run {
                call.respond(HttpStatusCode.BadRequest, MSG_REPORT_ID_REQUIRED)
                return@post
            }
        val principal =
            call.principal<AdminPrincipal>() ?: run {
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }

        val outcome = fileKominfo(dataSource, repo, kominfoRateLimiter, auditLogger, call, principal, archiveId, reportId)
        val message =
            when (outcome) {
                KominfoOutcome.Filed -> MSG_KOMINFO_FILED
                KominfoOutcome.AlreadyFiled -> MSG_KOMINFO_ALREADY_FILED
                KominfoOutcome.RateLimited -> MSG_KOMINFO_RATE_LIMITED
            }
        call.respondCsamLog(repo, layout, message = message)
    }

    post("/csam/{id}/decrypt") {
        if (!AdminCsrfGate.validateCsrf(call, auditLogger)) return@post
        if (!AdminRoleGate.requireOwnerOrAdmin(call)) return@post
        val archiveId =
            call.parseArchiveId() ?: run {
                call.respond(HttpStatusCode.BadRequest, MSG_INVALID_ID)
                return@post
            }
        val principal =
            call.principal<AdminPrincipal>() ?: run {
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }

        val view = decryptMetadata(dataSource, repo, encryptor, decryptRateLimiter, auditLogger, call, principal, archiveId)
        call.respond(PebbleContent(FRAGMENT_DECRYPT, view))
    }
}

// --- GET render ---

private fun ApplicationCall.isHtmx(): Boolean = request.headers[HX_REQUEST] == "true"

/** Parse `{id}` as a UUID; null when malformed (→ 400, no write). */
private fun ApplicationCall.parseArchiveId(): UUID? = parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }

/**
 * Query + render the detection log for THIS call's query parameters: the HTMX fragment
 * for an `HX-Request`, otherwise the full page (shell + fragment). An optional in-band
 * [message] surfaces a takedown/Kominfo outcome. Filter parsing is lenient (blank values
 * ignored; an out-of-enum `source` reaches the repository as a bound literal matching
 * zero rows). All rendered values are Pebble-autoescaped (the reflected filter inputs +
 * every row value), so a filter carrying HTML metacharacters is escaped, never live markup.
 */
private suspend fun ApplicationCall.respondCsamLog(
    repo: CsamRepository,
    layout: AdminLayout,
    message: String?,
) {
    val params = request.queryParameters

    val source = params["source"]?.trim()?.takeIf { it.isNotEmpty() }?.take(SOURCE_MAX)
    val kominfoStatus =
        when (params["kominfo"]?.trim()?.lowercase()) {
            "pending" -> CsamKominfoStatus.PENDING
            "filed" -> CsamKominfoStatus.FILED
            else -> null
        }
    // Date boundaries are interpreted in UTC: `from` is inclusive from the start of that
    // UTC day; `to` is inclusive of the whole UTC day via an exclusive `< to + 1 day`
    // upper bound (mirrors AdminActionsLogRoute).
    val from = params["from"]?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val to = params["to"]?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val fromInclusive = from?.atStartOfDay(ZoneOffset.UTC)?.toInstant()
    val toExclusive = to?.plusDays(1)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()
    val cursor = CsamArchiveCursor.decode(params["cursor"])

    val query =
        CsamArchiveQuery(
            source = source,
            kominfoStatus = kominfoStatus,
            fromInclusive = fromInclusive,
            toExclusive = toExclusive,
            cursor = cursor,
        )
    val page = repo.query(query)
    val pendingCount = repo.pendingKominfoCount()

    // Echo the active filters back (canonical, post-parse) so the form repopulates and
    // the pager carries them. `kominfo`/`source` values are enum-bounded; `from`/`to`
    // are ISO dates — none is user-free-text, but the template autoescapes regardless.
    val activeFilters =
        buildList {
            source?.let { add("source" to it) }
            kominfoStatus?.let { add("kominfo" to it.name.lowercase()) }
            from?.let { add("from" to it.toString()) }
            to?.let { add("to" to it.toString()) }
        }
    val filters = activeFilters.toMap()

    val nextUrl =
        page.nextCursor?.let { next ->
            val pairs = activeFilters + ("cursor" to next.encode())
            "/admin/csam?" + pairs.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        }

    val model =
        buildMap<String, Any> {
            put("rows", page.rows.map { it.toViewMap() })
            put("hasRows", page.rows.isNotEmpty())
            put("filters", filters)
            put("pendingCount", pendingCount)
            nextUrl?.let { put("nextUrl", it) }
            message?.let { put("message", it) }
            if (!isHtmx()) {
                layout.putShellModel(
                    this@respondCsamLog,
                    this,
                    pageTitle = "CSAM detection log",
                    activePath = "/admin/csam",
                )
            }
        }

    val template = if (isHtmx()) FRAGMENT_TABLE else PAGE_TEMPLATE
    respond(PebbleContent(template, model))
}

/**
 * Pre-format a row into display strings for the template. Pebble autoescaping escapes
 * every value on output. The decrypt/Kominfo form actions are built from the row id (a
 * UUID — not user-controlled). No image bytes / content URL exist in any field
 * (image-content-free invariant, design D3). The unblock link-out is surfaced ONLY for
 * `cf_worker` rows (design D8); `admin_manual` rows carry no `unblockUrl`.
 */
private fun CsamArchiveRow.toViewMap(): Map<String, Any> =
    buildMap {
        put("id", id.toString())
        put("imageHashShort", imageHash.take(HASH_DISPLAY_LEN))
        put("source", source)
        put("isCfWorker", source == "cf_worker")
        put("detectedAt", TS_FORMAT.format(createdAt))
        put("kominfoFiled", kominfoReportedAt != null)
        kominfoReportId?.let { put("kominfoReportId", it) }
        put("hasMetadata", hasMetadata)
        put("kominfoAction", "/admin/csam/$id/kominfo-report")
        put("decryptAction", "/admin/csam/$id/decrypt")
        // Enforcement / archive state, derived from the ledger-resolved columns we hold.
        // A cf_worker row's CF review path is the link-out (design D8); the cf_match_id
        // is the opaque CF identifier (never a renderable image URL).
        if (source == "cf_worker" && cfMatchId != null) {
            put("unblockUrl", "$CF_REVIEW_BASE${URLEncoder.encode(cfMatchId, Charsets.UTF_8)}")
        }
    }

// --- Kominfo filing (one transaction: idempotent update + audit row + cap check) ---

private enum class KominfoOutcome { Filed, AlreadyFiled, RateLimited }

/**
 * File the Kominfo report for [archiveId] in ONE transaction (spec Req "Kominfo report
 * tracking" + design D6): gate the dedicated per-admin cap on the same ledger snapshot,
 * run the idempotent `UPDATE … WHERE kominfo_reported_at IS NULL` (0 rows ⇒ already-filed
 * /unknown → rollback, NO mutation, NO audit row, original timestamp preserved), then
 * write exactly one immutable `csam_kominfo_reported` audit row. Commit-or-rollback together.
 */
private fun fileKominfo(
    dataSource: javax.sql.DataSource,
    repo: CsamRepository,
    rateLimiter: CsamKominfoReportRateLimiter,
    auditLogger: AdminAuditLogger,
    call: ApplicationCall,
    principal: AdminPrincipal,
    archiveId: UUID,
    reportId: String,
): KominfoOutcome =
    dataSource.connection.use { conn ->
        conn.autoCommit = false
        try {
            // Idempotency guard FIRST (mirrors the deletion-queue "ineligible before cap"
            // order): the conditional UPDATE only affects a still-pending row, so a 0-row
            // result = already-filed/unknown → rollback, no mutation, no cap consumed, no
            // audit row — the original `kominfo_reported_at` is preserved (design D6). The
            // write is not committed until the cap also passes.
            val affected = repo.fileKominfoReport(conn, archiveId, reportId)
            if (affected == 0) {
                conn.rollback()
                return KominfoOutcome.AlreadyFiled
            }
            // Dedicated per-admin cap checked on the same snapshot (design D5); at/over →
            // reject and roll the filing back (no mutation, no audit row).
            if (rateLimiter.isAtOrOverCap(conn, principal.adminId)) {
                conn.rollback()
                return KominfoOutcome.RateLimited
            }
            auditLogger.logCsamKominfoReported(
                conn = conn,
                adminId = principal.adminId,
                archiveId = archiveId,
                beforeState = buildJsonObject { put("kominfo_reported", false) },
                afterState =
                    buildJsonObject {
                        put("kominfo_report_id", reportId)
                        put("kominfo_reported", true)
                    },
                ip = call.clientIp,
                userAgent = call.request.headers[HttpHeaders.UserAgent],
            )
            conn.commit()
            KominfoOutcome.Filed
        } catch (e: Throwable) {
            runCatching { conn.rollback() }
            throw e
        } finally {
            runCatching { conn.autoCommit = true }
        }
    }

// --- Metadata decrypt (one transaction: cap check + audit row; fail-soft) ---

/**
 * Decrypt [archiveId]'s metadata fail-soft (spec Req "Audit-logged metadata decrypt" +
 * design D4). Runs the dedicated decrypt cap + exactly one `csam_metadata_decrypted`
 * audit row in ONE transaction. The audit row is written for EVERY authorized invocation
 * (this function is reached only after the owner/admin + CSRF gate passes), including the
 * two fail-soft cases (key unset / `encrypted_metadata` NULL → "metadata unavailable",
 * never a 500). The decrypted plaintext is shown transiently in the returned fragment and
 * never persisted to the audit row. Returns the template model for `csam-metadata.peb`.
 */
private fun decryptMetadata(
    dataSource: javax.sql.DataSource,
    repo: CsamRepository,
    encryptor: CsamMetadataEncryptor,
    rateLimiter: CsamMetadataDecryptRateLimiter,
    auditLogger: AdminAuditLogger,
    call: ApplicationCall,
    principal: AdminPrincipal,
    archiveId: UUID,
): Map<String, Any> {
    // Fetch + decrypt OUTSIDE the audit transaction (read-only; no ledger contention).
    val stored = repo.fetchEncryptedMetadata(archiveId)
    val decrypted: JsonObject? =
        stored?.encryptedMetadata?.let { blob ->
            runCatching {
                val plaintext = encryptor.decrypt(blob, stored.imageHash)
                AppJson.decodeFromString(JsonObject.serializer(), String(plaintext, Charsets.UTF_8))
            }.getOrElse {
                // Key unset (decrypt throws) or a tag/AAD/parse failure → fail-soft.
                log.warn("event=csam_metadata_decrypt_failed error_class={}", it::class.simpleName)
                null
            }
        }
    val available = decrypted != null

    dataSource.connection.use { conn ->
        conn.autoCommit = false
        try {
            // Over the dedicated decrypt cap → still an AUTHORIZED attempt, but record it
            // as rate-limited (one audit row) and surface the cap message; no plaintext.
            val rateLimited = rateLimiter.isAtOrOverCap(conn, principal.adminId)
            auditLogger.logCsamMetadataDecrypted(
                conn = conn,
                adminId = principal.adminId,
                archiveId = archiveId,
                afterState =
                    buildJsonObject {
                        put(
                            "outcome",
                            when {
                                rateLimited -> "rate_limited"
                                available -> "decrypted"
                                else -> "metadata_unavailable"
                            },
                        )
                    },
                ip = call.clientIp,
                userAgent = call.request.headers[HttpHeaders.UserAgent],
            )
            conn.commit()
            if (rateLimited) {
                return mapOf("id" to archiveId.toString(), "unavailable" to true, "message" to MSG_DECRYPT_RATE_LIMITED)
            }
        } catch (e: Throwable) {
            runCatching { conn.rollback() }
            throw e
        } finally {
            runCatching { conn.autoCommit = true }
        }
    }

    if (!available) {
        return mapOf("id" to archiveId.toString(), "unavailable" to true, "message" to MSG_METADATA_UNAVAILABLE)
    }

    // Scalar metadata fields only — the raw pseudonymous uploader UUID is intended
    // (design D4); NO image bytes / content URL. Missing keys render as "—".
    val meta = decrypted!!

    fun str(key: String): String = meta[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: "—"
    return buildMap {
        put("id", archiveId.toString())
        put("unavailable", false)
        put("uploaderUserId", str("uploader_user_id"))
        put("affectedPostId", str("affected_post_id"))
        put("source", str("source"))
        put("cascadeCount", str("posts_cascade_tombstoned"))
    }
}

// --- Constants ---

private val log = LoggerFactory.getLogger("id.nearyou.app.admin.routes.AdminCsamRoute")

private const val HX_REQUEST = "HX-Request"
private const val PAGE_TEMPLATE = "csam-log.peb"
private const val FRAGMENT_TABLE = "csam-log-table.peb"
private const val FRAGMENT_DECRYPT = "csam-metadata.peb"

private const val FIELD_IMAGE_ID = "image_id"
private const val FIELD_IMAGE_HASH = "image_hash"
private const val FIELD_REPORT_ID = "kominfo_report_id"

// Display truncation for the image hash (the full hash is plaintext-sensitive Kominfo
// data; a short prefix is enough for operator scanning, mirroring the f13 `a3f4…e2` cell).
private const val HASH_DISPLAY_LEN = 12

// Hygiene cap for the pasted Kominfo report id (flows into the unbounded TEXT column;
// hygiene/consistency with the read caps, not an overflow guard).
private const val REPORT_ID_MAX = 200
private const val SOURCE_MAX = 32

// The Cloudflare CSAM review/unblock console base (design D8 — link-out only; CF owns
// the unblock decision). The cf_match_id is appended as the opaque CF match reference.
private const val CF_REVIEW_BASE = "https://dash.cloudflare.com/?to=/:account/images/csam-review/"

private val TS_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

// User-facing informational messages (admin panel is English-only).
private const val MSG_INVALID_ID = "Invalid archive-row id."
private const val MSG_REPORT_ID_REQUIRED = "A Kominfo report id is required."
private const val MSG_TAKEDOWN_FIELDS_REQUIRED = "Both image_id and image_hash are required."
private const val MSG_TAKEDOWN_ACTIONED =
    "Takedown actioned: the uploader was permanently banned, the post and the uploader's other posts were " +
        "tombstoned, and the match was archived. Fully audit-logged."
private const val MSG_TAKEDOWN_ARCHIVED =
    "Match archived (no live upload found for that image_id, so no ban or cascade). The Kominfo record exists. Fully audit-logged."
private const val MSG_TAKEDOWN_RATE_LIMITED =
    "Destructive-action quota exceeded (20/hour). Try again later. Nothing was changed."
private const val MSG_KOMINFO_FILED =
    "Kominfo filing recorded. The row is now eligible for the daily purge worker after its 90-day deadline."
private const val MSG_KOMINFO_ALREADY_FILED =
    "This row already has a Kominfo filing (or the id is unknown). Nothing was changed; the original filing timestamp is preserved."
private const val MSG_KOMINFO_RATE_LIMITED =
    "Kominfo-report quota exceeded (30/hour). Try again later. Nothing was changed."
private const val MSG_METADATA_UNAVAILABLE =
    "Metadata unavailable (the archive key is unprovisioned or this row has no encrypted metadata). The decrypt attempt was audit-logged."
private const val MSG_DECRYPT_RATE_LIMITED =
    "Metadata-decrypt quota exceeded (30/hour). Try again later. The attempt was audit-logged."
