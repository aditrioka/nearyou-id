package id.nearyou.app.admin.usernameoversight

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuthProvider
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import id.nearyou.app.admin.ratelimit.UsernameOversightActionRateLimiter
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import java.time.Instant
import java.util.UUID

/**
 * Route-level integration tests for `adminUsernameOversight` — the HTTP auth /
 * CSRF / write-role gate + gate ORDER, malformed-id 400s, the server-side
 * resolution allowlist, the accept→override + reject→no-override behavior, the
 * conditional idempotent no-ops, manual release, the 10/5 rate caps, atomicity, the
 * dual-mode rendering + HTML escaping, and the two-admin race
 * (`admin-premium-username-oversight` tasks.md §8.1-8.3, §8.5-8.9 — §8.4 the live
 * PATCH gate is covered by UsernameCustomizationEndpointsTest, the backend half).
 *
 * DB-backed; tagged `database`. Owns a single bounded Hikari pool (`autoClose` +
 * size 2, per §8.8 / the CI connection budget).
 */
@Tags("database")
class AdminUsernameOversightRouteTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val seededAdmins = mutableListOf<UUID>()
    val seededUsers = mutableListOf<UUID>()
    afterEach {
        UsernameOversightTestSupport.cleanup(dataSource, seededUsers)
        seededUsers.clear()
        seededAdmins.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seededAdmins.clear()
    }

    fun seedAdmin(role: String = "owner"): AdminAuthTestSupport.SeededAdmin =
        AdminAuthTestSupport.seedAdmin(dataSource, role = role).also { seededAdmins += it.id }

    fun seedUser(username: String? = null): UUID = UsernameOversightTestSupport.seedUser(dataSource, username).also { seededUsers += it }

    fun cookie(token: String) = "${AdminAuthProvider.COOKIE_NAME}=$token"

    fun form(vararg pairs: Pair<String, String>): String = pairs.toList().formUrlEncode()

    fun csrfViolated(adminId: UUID): Boolean =
        AdminAuthTestSupport.latestAuditRows(dataSource, adminId).any { it.actionType == "admin_csrf_violation" }

    // ====================================================================
    // §8.1 — GET render: three sections / unauth / empty / candidate / excludes
    // ====================================================================

    "8.1 — unauthenticated GET redirects (302) to /admin/login" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/username-oversight")
            res.status shouldBe HttpStatusCode.Found
            res.headers[HttpHeaders.Location] shouldBe "/admin/login"
        }
    }

    "8.1 — GET renders the three sections + full page extends layout" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/username-oversight") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "<header>" // full page extends layout
            body shouldContain "Flagged candidates"
            body shouldContain "username_history"
            body shouldContain "30-day hold"
        }
    }

    "8.1 — empty data renders empty states with 200 (no 404/500)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        // The flagged-queue + holds sections are GLOBAL (not per-admin), so clear any
        // orphan/concurrent rows first to assert the empty-state deterministically.
        // The history search is scoped to an impossible token so its section is empty.
        UsernameOversightTestSupport.clearGlobalSections(dataSource)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/username-oversight?q=zzznonexistenttokenzzz") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "No pending flagged candidates."
            body shouldContain "No username history matches the current search."
            body shouldContain "No handles are currently under hold."
        }
    }

    "8.1 — a pending flag renders the candidate (from notes) + a user deep-link" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser(username = "budi_kopi")
        UsernameOversightTestSupport.seedQueueRow(dataSource, u, candidate = "borderlinehandle")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.get("/admin/username-oversight") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "borderlinehandle"
            body shouldContain "/admin/users?q=budi_kopi"
        }
    }

    "8.1 — non-username_flagged triggers + resolved flags are excluded" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        // moderation_queue has UNIQUE(target_type, target_id, trigger): one standing
        // row per (user, trigger). Use DISTINCT users so the three rows coexist.
        val uContent = seedUser(username = "ucontentexcl")
        val uResolved = seedUser(username = "uresolvedexcl")
        val uPending = seedUser(username = "upendingexcl")
        UsernameOversightTestSupport.seedQueueRow(dataSource, uContent, candidate = "contenttrig", trigger = "auto_hide_3_reports")
        UsernameOversightTestSupport.seedQueueRow(dataSource, uResolved, candidate = "resolvedcand", status = "resolved")
        UsernameOversightTestSupport.seedQueueRow(dataSource, uPending, candidate = "pendingcand")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.get("/admin/username-oversight") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "pendingcand"
            body shouldNotContain "contenttrig"
            body shouldNotContain "resolvedcand"
        }
    }

    "8.1 — a flag for a hard-deleted user renders the target_id with no deep-link" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser(username = "udeleted")
        UsernameOversightTestSupport.seedQueueRow(dataSource, u, candidate = "ghostcand")
        UsernameOversightTestSupport.hardDeleteUser(dataSource, u) // queue row survives (no FK)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.get("/admin/username-oversight") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "ghostcand"
            body shouldContain u.toString() // target_id rendered as text
            body shouldNotContain "/admin/users?q=udeleted"
        }
    }

    "8.1 — history search matches old AND new handle case-insensitively + no edit control" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser(username = "uhist")
        UsernameOversightTestSupport.seedHistory(dataSource, u, oldUsername = "kopibudi", newUsername = "budi_kopi")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            // old-handle match (case-insensitive)
            client.get("/admin/username-oversight?q=KOPIBUDI") { header(HttpHeaders.Cookie, cookie(token)) }
                .bodyAsText() shouldContain "kopibudi"
            // new-handle match (case-insensitive)
            client.get("/admin/username-oversight?q=BUDI_KOPI") { header(HttpHeaders.Cookie, cookie(token)) }
                .bodyAsText() shouldContain "budi_kopi"
            // strictly read-only: no form posting to a username-edit endpoint
            val body = client.get("/admin/username-oversight") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldNotContain "edit-username"
        }
    }

    "8.1 — holds list excludes an elapsed hold; lists a future hold with its release date" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser(username = "uholds")
        UsernameOversightTestSupport.seedHistory(
            dataSource,
            u,
            oldUsername = "sore_rina",
            newUsername = "rina_pagi",
            releasedAt = Instant.now().plusSeconds(10L * 24 * 3600),
        )
        UsernameOversightTestSupport.seedHistory(
            dataSource,
            u,
            oldUsername = "elapsedhandle",
            newUsername = "rina_now",
            releasedAt = Instant.now().minusSeconds(3600),
        )
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.get("/admin/username-oversight") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            // The held handle appears (and specifically in the 30-day hold section).
            body shouldContain "sore_rina"
            // The elapsed handle is excluded from the HOLDS section (it still shows in
            // the read-only history viewer, so scope the exclusion to the holds card).
            val holdsSection = body.substringAfter("30-day hold")
            holdsSection shouldContain "sore_rina"
            holdsSection shouldNotContain "elapsedhandle"
        }
    }

    "8.1 — read available to any role (read_only)" {
        val admin = seedAdmin(role = "read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.get("/admin/username-oversight") { header(HttpHeaders.Cookie, cookie(token)) }
                    .also { it.status shouldBe HttpStatusCode.OK }
                    .bodyAsText()
            // a read_only role sees the queue but NO write controls
            body shouldNotContain "accept_flagged_username"
            body shouldNotContain "Release now"
        }
    }

    // ====================================================================
    // §8.2 — pagination / escaping / HTMX vs plain / GET mutates nothing
    // ====================================================================

    "8.2 — history pagination caps the page + exposes a non-overlapping older page" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser(username = "upage")
        val base = Instant.parse("2026-01-01T00:00:00Z")
        // PAGE_SIZE + 5 history rows. Use a RUN-UNIQUE handle prefix (so a crashed
        // prior run's leftover rows can't collide) + scope every query to that prefix
        // (the history list is global). The search token lives ONLY in old_username
        // (new_username uses a different prefix) so `q=<token>` selects exactly these
        // rows, and the 2-digit padding makes "<tok>05" not a substring of "<tok>54".
        val tok = "p" + UUID.randomUUID().toString().replace("-", "").take(10)
        val total = UsernameOversightRepository.PAGE_SIZE + 5
        repeat(total) { i ->
            UsernameOversightTestSupport.seedHistory(
                dataSource,
                u,
                oldUsername = "$tok%02d".format(i),
                newUsername = "newh%02d".format(i),
                changedAt = base.plusSeconds(i.toLong()),
                // PAST released_at so these rows stay OUT of the (unpaginated, unfiltered)
                // holds section — the assertions target only the paginated history viewer.
                releasedAt = base.plusSeconds(i.toLong()),
            )
        }
        val newest = "$tok%02d".format(total - 1)
        val oldest = "$tok%02d".format(0)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val first =
                client.get("/admin/username-oversight?q=$tok") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            first shouldContain "Load older"
            first shouldContain newest // newest row present on page 1
            first shouldNotContain oldest // the oldest is past the first page

            // follow the history older cursor (carries q=<tok>)
            val cursor = Regex("history_cursor=([^&\"]+)").find(first)!!.groupValues[1]
            val older =
                client.get("/admin/username-oversight?q=$tok&history_cursor=$cursor") { header(HttpHeaders.Cookie, cookie(token)) }
                    .bodyAsText()
            older shouldContain oldest // the oldest rows show on the next page
            older shouldNotContain newest // no first-page overlap
        }
    }

    "8.2 — malformed cursors fall back to the first page (200, not an error)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.get("/admin/username-oversight?history_cursor=not-valid&flags_cursor=also-bad") {
                header(HttpHeaders.Cookie, cookie(token))
            }.status shouldBe HttpStatusCode.OK
        }
    }

    "8.2 — a markup-bearing candidate + handle are HTML-escaped" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser(username = "uescape")
        UsernameOversightTestSupport.seedQueueRow(dataSource, u, candidate = "<script>alert(1)</script>")
        UsernameOversightTestSupport.seedHistory(
            dataSource,
            u,
            oldUsername = "<b>old</b>",
            newUsername = "newh",
        )
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.get("/admin/username-oversight") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "&lt;script&gt;"
            body shouldNotContain "<script>alert(1)</script>"
            body shouldContain "&lt;b&gt;old&lt;/b&gt;"
        }
    }

    "8.2 — HTMX request returns only the fragment; plain GET returns the full page" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val frag =
                client.get("/admin/username-oversight") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("HX-Request", "true")
                }.bodyAsText()
            frag shouldContain "id=\"username-oversight\""
            frag shouldNotContain "<header>" // fragment only

            val full = client.get("/admin/username-oversight") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            full shouldContain "<header>"
        }
    }

    "8.2 — serving the GET listing writes no audit row + mutates nothing" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser(username = "ugetnomut")
        val flagId = UsernameOversightTestSupport.seedQueueRow(dataSource, u, candidate = "noopcand")
        val before = UsernameOversightTestSupport.auditCount(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.get("/admin/username-oversight?q=x&flags_cursor=&history_cursor=") { header(HttpHeaders.Cookie, cookie(token)) }
            client.get("/admin/username-oversight") { header(HttpHeaders.Cookie, cookie(token)) }
        }
        UsernameOversightTestSupport.auditCount(dataSource, admin.id) shouldBe before
        UsernameOversightTestSupport.queueState(dataSource, flagId)!!.status shouldBe "pending"
    }

    // ====================================================================
    // §8.3 — flag resolution
    // ====================================================================

    "8.3 — accept resolves the flag + writes a per-candidate override + one audit row" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser(username = "uaccept")
        val flagId = UsernameOversightTestSupport.seedQueueRow(dataSource, u, candidate = "BorderlineHandle")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/username-oversight/flags/$flagId/resolve") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token), "resolution" to "accept_flagged_username"))
                }
            res.status shouldBe HttpStatusCode.SeeOther
        }
        val state = UsernameOversightTestSupport.queueState(dataSource, flagId)!!
        state.status shouldBe "resolved"
        state.resolution shouldBe "accept_flagged_username"
        state.resolvedBy shouldBe admin.id
        val overrides = UsernameOversightTestSupport.overridesFor(dataSource, u)
        overrides.size shouldBe 1
        overrides[0].candidate shouldBe "borderlinehandle" // normalized lowercase
        overrides[0].approvedBy shouldBe admin.id
        overrides[0].consumed shouldBe false
        UsernameOversightTestSupport.auditCount(dataSource, admin.id, "username_flag_resolved") shouldBe 1
        // after_state records the resolution + approved candidate
        val audit = UsernameOversightTestSupport.auditRows(dataSource, admin.id).first { it.actionType == "username_flag_resolved" }
        audit.targetId shouldBe u.toString()
        audit.afterState!! shouldContain "accept_flagged_username"
        audit.afterState!! shouldContain "borderlinehandle"
    }

    "8.3 — reject resolves the flag + writes NO override + one audit row" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser(username = "ureject")
        val flagId = UsernameOversightTestSupport.seedQueueRow(dataSource, u, candidate = "rejectcand")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/username-oversight/flags/$flagId/resolve") {
                header(HttpHeaders.Cookie, cookie(token))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token), "resolution" to "reject_flagged_username"))
            }.status shouldBe HttpStatusCode.SeeOther
        }
        val state = UsernameOversightTestSupport.queueState(dataSource, flagId)!!
        state.status shouldBe "resolved"
        state.resolution shouldBe "reject_flagged_username"
        UsernameOversightTestSupport.overrideCount(dataSource, u) shouldBe 0
        UsernameOversightTestSupport.auditCount(dataSource, admin.id, "username_flag_resolved") shouldBe 1
    }

    "8.3 — resolving a non-username_flagged queue row is rejected (no mutation, no audit)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser(username = "ucontentres")
        val contentId = UsernameOversightTestSupport.seedQueueRow(dataSource, u, candidate = null, trigger = "auto_hide_3_reports")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/username-oversight/flags/$contentId/resolve") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token), "resolution" to "accept_flagged_username"))
                }
            res.status shouldBe HttpStatusCode.OK // in-band message, not a 4xx
            res.bodyAsText() shouldContain "not a username flag"
        }
        UsernameOversightTestSupport.queueState(dataSource, contentId)!!.status shouldBe "pending"
        UsernameOversightTestSupport.auditCount(dataSource, admin.id, "username_flag_resolved") shouldBe 0
    }

    "8.3 — out-of-allowlist resolution is rejected BEFORE any DB write (no 5xx)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser(username = "uallowlist")
        val flagId = UsernameOversightTestSupport.seedQueueRow(dataSource, u, candidate = "allowcand")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            // a content resolution (owned by admin-report-queue) + raw garbage → 400
            client.post("/admin/username-oversight/flags/$flagId/resolve") {
                header(HttpHeaders.Cookie, cookie(token))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token), "resolution" to "ban_author"))
            }.status shouldBe HttpStatusCode.BadRequest
            client.post("/admin/username-oversight/flags/$flagId/resolve") {
                header(HttpHeaders.Cookie, cookie(token))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token), "resolution" to "garbage"))
            }.status shouldBe HttpStatusCode.BadRequest
        }
        UsernameOversightTestSupport.queueState(dataSource, flagId)!!.status shouldBe "pending"
        UsernameOversightTestSupport.auditCount(dataSource, admin.id, "username_flag_resolved") shouldBe 0
    }

    "8.3 — malformed UUID → 400, no write" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/username-oversight/flags/not-a-uuid/resolve") {
                header(HttpHeaders.Cookie, cookie(token))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token), "resolution" to "accept_flagged_username"))
            }.status shouldBe HttpStatusCode.BadRequest
        }
        UsernameOversightTestSupport.auditCount(dataSource, admin.id, "username_flag_resolved") shouldBe 0
    }

    "8.3 — re-resolving an already-resolved flag is a safe no-op (no 2nd audit, no 2nd override)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser(username = "ureresolve")
        val flagId = UsernameOversightTestSupport.seedQueueRow(dataSource, u, candidate = "reresolvecand")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            // first accept → resolved + override + 1 audit
            client.post("/admin/username-oversight/flags/$flagId/resolve") {
                header(HttpHeaders.Cookie, cookie(token))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token), "resolution" to "accept_flagged_username"))
            }.status shouldBe HttpStatusCode.SeeOther
            // second resolve (reject) → no-op
            val res =
                client.post("/admin/username-oversight/flags/$flagId/resolve") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token), "resolution" to "reject_flagged_username"))
                }
            res.status shouldBe HttpStatusCode.OK // in-band "already resolved"
            res.bodyAsText() shouldContain "already resolved"
        }
        // resolution unchanged (still the first accept), exactly one audit + one override
        UsernameOversightTestSupport.queueState(dataSource, flagId)!!.resolution shouldBe "accept_flagged_username"
        UsernameOversightTestSupport.auditCount(dataSource, admin.id, "username_flag_resolved") shouldBe 1
        UsernameOversightTestSupport.overrideCount(dataSource, u) shouldBe 1
    }

    "8.3 — accept on an empty-notes flag resolves + audits but writes no override (no 5xx)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser(username = "uemptynotes")
        val flagId = UsernameOversightTestSupport.seedQueueRow(dataSource, u, candidate = null) // legacy pre-V23 flag
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/username-oversight/flags/$flagId/resolve") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token), "resolution" to "accept_flagged_username"))
                }
            res.status shouldBe HttpStatusCode.OK // in-band note, not a 303 and not a 5xx
            res.bodyAsText() shouldContain "no candidate to approve"
        }
        UsernameOversightTestSupport.queueState(dataSource, flagId)!!.status shouldBe "resolved"
        UsernameOversightTestSupport.overrideCount(dataSource, u) shouldBe 0
        UsernameOversightTestSupport.auditCount(dataSource, admin.id, "username_flag_resolved") shouldBe 1
    }

    // ====================================================================
    // §8.5 — manual release
    // ====================================================================

    "8.5 — release clears the hold + one audit row with prior released_at in before_state" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser(username = "urelease")
        val histId =
            UsernameOversightTestSupport.seedHistory(
                dataSource,
                u,
                oldUsername = "sore_rina",
                newUsername = "rina_x",
                releasedAt = Instant.now().plusSeconds(10L * 24 * 3600),
            )
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/username-oversight/holds/$histId/release") {
                header(HttpHeaders.Cookie, cookie(token))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token)))
            }.status shouldBe HttpStatusCode.SeeOther
        }
        UsernameOversightTestSupport.isHeld(dataSource, histId) shouldBe false
        val audit = UsernameOversightTestSupport.auditRows(dataSource, admin.id).first { it.actionType == "username_hold_released" }
        audit.targetId shouldBe histId.toString()
        audit.beforeState!! shouldContain "released_at"
        UsernameOversightTestSupport.auditCount(dataSource, admin.id, "username_hold_released") shouldBe 1
    }

    "8.5 — releasing an already-elapsed hold is a safe no-op (no mutation, no audit)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser(username = "uelapsed")
        val past = Instant.now().minusSeconds(3600)
        val histId =
            UsernameOversightTestSupport.seedHistory(
                dataSource,
                u,
                oldUsername = "gone_handle",
                newUsername = "x_now",
                releasedAt = past,
            )
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/username-oversight/holds/$histId/release") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token)))
                }
            res.status shouldBe HttpStatusCode.OK // in-band, not an error
        }
        UsernameOversightTestSupport.auditCount(dataSource, admin.id, "username_hold_released") shouldBe 0
    }

    "8.5 — release malformed UUID → 400, no write" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/username-oversight/holds/not-a-uuid/release") {
                header(HttpHeaders.Cookie, cookie(token))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token)))
            }.status shouldBe HttpStatusCode.BadRequest
        }
        UsernameOversightTestSupport.auditCount(dataSource, admin.id, "username_hold_released") shouldBe 0
    }

    // ====================================================================
    // §8.6 — CSRF / role ordering
    // ====================================================================

    "8.6 — write without a CSRF token → 403 + admin_csrf_violation, no mutation" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser(username = "ucsrf")
        val flagId = UsernameOversightTestSupport.seedQueueRow(dataSource, u, candidate = "csrfcand")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/username-oversight/flags/$flagId/resolve") {
                header(HttpHeaders.Cookie, cookie(token))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(form("resolution" to "accept_flagged_username"))
            }.status shouldBe HttpStatusCode.Forbidden
        }
        csrfViolated(admin.id) shouldBe true
        UsernameOversightTestSupport.queueState(dataSource, flagId)!!.status shouldBe "pending"
        UsernameOversightTestSupport.overrideCount(dataSource, u) shouldBe 0
    }

    "8.6 — read_only role with a valid CSRF token → 403, no mutation, no CSRF violation" {
        val admin = seedAdmin(role = "read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser(username = "ureadonlywrite")
        val flagId = UsernameOversightTestSupport.seedQueueRow(dataSource, u, candidate = "rocand")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/username-oversight/flags/$flagId/resolve") {
                header(HttpHeaders.Cookie, cookie(token))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token), "resolution" to "accept_flagged_username"))
            }.status shouldBe HttpStatusCode.Forbidden
        }
        UsernameOversightTestSupport.queueState(dataSource, flagId)!!.status shouldBe "pending"
        csrfViolated(admin.id) shouldBe false // the role gate (not CSRF) rejected
    }

    "8.6 — CSRF is checked before the role gate (read_only + no CSRF → CSRF rejection)" {
        val admin = seedAdmin(role = "read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            // also malformed id — proves CSRF runs before role AND before path parsing
            client.post("/admin/username-oversight/holds/not-a-uuid/release") {
                header(HttpHeaders.Cookie, cookie(token))
            }.status shouldBe HttpStatusCode.Forbidden
        }
        csrfViolated(admin.id) shouldBe true
    }

    "8.6 — unauthenticated write → 302 + no write" {
        val u = seedUser(username = "uunauthwrite")
        val flagId = UsernameOversightTestSupport.seedQueueRow(dataSource, u, candidate = "unauthcand")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/username-oversight/flags/$flagId/resolve") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(form("resolution" to "accept_flagged_username"))
                }
            res.status shouldBe HttpStatusCode.Found
            res.headers[HttpHeaders.Location] shouldBe "/admin/login"
        }
        UsernameOversightTestSupport.queueState(dataSource, flagId)!!.status shouldBe "pending"
    }

    // ====================================================================
    // §8.7 — rate limits (10 / 5) + per-admin + counts only its own action
    // ====================================================================

    "8.7 — flag resolution under the 10/hour cap proceeds; at the cap is rejected in-band" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        // 9 prior resolutions in-window → the 10th proceeds, the 11th is capped
        repeat(9) { UsernameOversightTestSupport.seedAudit(dataSource, admin.id, UsernameOversightActionRateLimiter.ACTION_FLAG_RESOLVED) }
        // Distinct users — moderation_queue UNIQUE(target_type, target_id, trigger).
        val u1 = seedUser(username = "ucapok")
        val u2 = seedUser(username = "ucapped")
        val flag1 = UsernameOversightTestSupport.seedQueueRow(dataSource, u1, candidate = "capokcand")
        val flag2 = UsernameOversightTestSupport.seedQueueRow(dataSource, u2, candidate = "cappedcand")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            // 10th (count 9 → under) proceeds
            client.post("/admin/username-oversight/flags/$flag1/resolve") {
                header(HttpHeaders.Cookie, cookie(token))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token), "resolution" to "reject_flagged_username"))
            }.status shouldBe HttpStatusCode.SeeOther
            // now count is 10 → the next is capped, in-band
            val res =
                client.post("/admin/username-oversight/flags/$flag2/resolve") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token), "resolution" to "reject_flagged_username"))
                }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "quota exceeded (10/hour)"
        }
        // the capped flag stays pending, no extra audit beyond the one that proceeded
        UsernameOversightTestSupport.queueState(dataSource, flag2)!!.status shouldBe "pending"
        UsernameOversightTestSupport.auditCount(dataSource, admin.id, "username_flag_resolved") shouldBe 10 // 9 seeded + 1 real
    }

    "8.7 — the flag cap is per-admin + counts only username_flag_resolved" {
        val adminA = seedAdmin()
        val adminB = seedAdmin()
        val tokenB = AdminAuthTestSupport.seedSession(dataSource, adminB.id)
        // admin A at the cap; admin B has 0 (but B has 10 hold-releases — must NOT count)
        repeat(
            10,
        ) { UsernameOversightTestSupport.seedAudit(dataSource, adminA.id, UsernameOversightActionRateLimiter.ACTION_FLAG_RESOLVED) }
        repeat(
            10,
        ) { UsernameOversightTestSupport.seedAudit(dataSource, adminB.id, UsernameOversightActionRateLimiter.ACTION_HOLD_RELEASED) }
        val u = seedUser(username = "uperabmin")
        val flag = UsernameOversightTestSupport.seedQueueRow(dataSource, u, candidate = "perabcand")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            // admin B resolves normally — A's exhausted quota + B's hold-releases don't block
            client.post("/admin/username-oversight/flags/$flag/resolve") {
                header(HttpHeaders.Cookie, cookie(tokenB))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(tokenB), "resolution" to "reject_flagged_username"))
            }.status shouldBe HttpStatusCode.SeeOther
        }
        UsernameOversightTestSupport.queueState(dataSource, flag)!!.resolution shouldBe "reject_flagged_username"
    }

    "8.7 — manual release at the 5/hour cap is rejected in-band; under proceeds" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        repeat(5) { UsernameOversightTestSupport.seedAudit(dataSource, admin.id, UsernameOversightActionRateLimiter.ACTION_HOLD_RELEASED) }
        val u = seedUser(username = "uholdcap")
        val histId =
            UsernameOversightTestSupport.seedHistory(
                dataSource,
                u,
                oldUsername = "held_at_cap",
                newUsername = "y",
                releasedAt = Instant.now().plusSeconds(10L * 24 * 3600),
            )
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/username-oversight/holds/$histId/release") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(token)))
                }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "quota exceeded (5/hour)"
        }
        UsernameOversightTestSupport.isHeld(dataSource, histId) shouldBe true // unchanged
        UsernameOversightTestSupport.auditCount(dataSource, admin.id, "username_hold_released") shouldBe 5 // no new row
    }

    // ====================================================================
    // §8.9 — two-admin concurrency on the SAME row → exactly one wins
    // ====================================================================

    "8.9 — two admins resolving the SAME pending flag → one wins, loser no-op" {
        val adminA = seedAdmin()
        val adminB = seedAdmin()
        val tokenA = AdminAuthTestSupport.seedSession(dataSource, adminA.id)
        val tokenB = AdminAuthTestSupport.seedSession(dataSource, adminB.id)
        val u = seedUser(username = "uraceflag")
        val flagId = UsernameOversightTestSupport.seedQueueRow(dataSource, u, candidate = "racecand")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            // sequential calls model the serialized outcome (the FOR UPDATE + the
            // WHERE status='pending' conditional guarantee the second is a no-op).
            client.post("/admin/username-oversight/flags/$flagId/resolve") {
                header(HttpHeaders.Cookie, cookie(tokenA))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(tokenA), "resolution" to "reject_flagged_username"))
            }.status shouldBe HttpStatusCode.SeeOther
            val loser =
                client.post("/admin/username-oversight/flags/$flagId/resolve") {
                    header(HttpHeaders.Cookie, cookie(tokenB))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(tokenB), "resolution" to "accept_flagged_username"))
                }
            loser.status shouldBe HttpStatusCode.OK
            loser.bodyAsText() shouldContain "already resolved"
        }
        // A's reject won; B wrote no audit + no override
        UsernameOversightTestSupport.queueState(dataSource, flagId)!!.resolution shouldBe "reject_flagged_username"
        UsernameOversightTestSupport.auditCount(dataSource, adminA.id, "username_flag_resolved") shouldBe 1
        UsernameOversightTestSupport.auditCount(dataSource, adminB.id, "username_flag_resolved") shouldBe 0
        UsernameOversightTestSupport.overrideCount(dataSource, u) shouldBe 0
    }

    "8.9 — two admins releasing the SAME held hold → one wins, loser no-op" {
        val adminA = seedAdmin()
        val adminB = seedAdmin()
        val tokenA = AdminAuthTestSupport.seedSession(dataSource, adminA.id)
        val tokenB = AdminAuthTestSupport.seedSession(dataSource, adminB.id)
        val u = seedUser(username = "uracehold")
        val histId =
            UsernameOversightTestSupport.seedHistory(
                dataSource,
                u,
                oldUsername = "race_held",
                newUsername = "z",
                releasedAt = Instant.now().plusSeconds(10L * 24 * 3600),
            )
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/username-oversight/holds/$histId/release") {
                header(HttpHeaders.Cookie, cookie(tokenA))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(tokenA)))
            }.status shouldBe HttpStatusCode.SeeOther
            val loser =
                client.post("/admin/username-oversight/holds/$histId/release") {
                    header(HttpHeaders.Cookie, cookie(tokenB))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(form("_csrf" to AdminAuthTestSupport.csrfFor(tokenB)))
                }
            loser.status shouldBe HttpStatusCode.OK
            loser.bodyAsText() shouldContain "already been released"
        }
        UsernameOversightTestSupport.auditCount(dataSource, adminA.id, "username_hold_released") shouldBe 1
        UsernameOversightTestSupport.auditCount(dataSource, adminB.id, "username_hold_released") shouldBe 0
    }
})
