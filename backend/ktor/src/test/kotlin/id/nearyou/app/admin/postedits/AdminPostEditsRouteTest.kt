package id.nearyou.app.admin.postedits

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuthProvider
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import id.nearyou.app.admin.auth.AdminCsrfGate
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.UUID

/**
 * Integration tests for `GET /admin/posts/{post_id}/edits` — the Post Edit
 * History viewer (`admin-post-edit-history` tasks.md Section 4). DB-backed;
 * tagged `database`; the pool autoCloses in `afterSpec` (CI connection-budget
 * discipline, docs/11 §3.2 — tasks.md 4.11). Asserts the auth gate, the
 * composed newest-first render, the empty/edge branches, dual-mode rendering,
 * output escaping + the author deep-link, the location indicator (without raw
 * coordinates), the read-only/no-mutation/no-audit guards, role access, and the
 * shadow-ban completeness read.
 */
@Tags("database")
class AdminPostEditsRouteTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val seededAdmins = mutableListOf<UUID>()
    val seededUsers = mutableListOf<UUID>()
    afterEach {
        seededUsers.forEach { PostEditsTestSupport.deletePostsByAuthor(dataSource, it) }
        seededUsers.forEach { PostEditsTestSupport.deleteUser(dataSource, it) }
        seededUsers.clear()
        seededAdmins.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seededAdmins.clear()
    }

    fun seedAdmin(role: String = "owner"): AdminAuthTestSupport.SeededAdmin =
        AdminAuthTestSupport.seedAdmin(dataSource, role = role).also { seededAdmins.add(it.id) }

    fun cookie(token: String) = "${AdminAuthProvider.COOKIE_NAME}=$token"

    fun user(
        username: String? = null,
        shadowBanned: Boolean = false,
    ): UUID = PostEditsTestSupport.seedUser(dataSource, username, shadowBanned).also { seededUsers.add(it) }

    val t1 = Instant.parse("2026-06-10T19:58:00Z")
    val t2 = Instant.parse("2026-06-10T20:12:00Z")
    val t3 = Instant.parse("2026-06-10T20:40:00Z")

    "4.1 — authenticated GET renders the version history; live + snapshots with the base layout" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val author = user("rina.sore")
        val postId = PostEditsTestSupport.seedPost(dataSource, author, content = "live text now")
        PostEditsTestSupport.seedEdit(dataSource, postId, author, "older snapshot text", t1)
        PostEditsTestSupport.seedEdit(dataSource, postId, author, "newer snapshot text", t2)

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/posts/$postId/edits") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "Versi terbaru"
            body shouldContain "live text now"
            body shouldContain "newer snapshot text"
            body shouldContain "older snapshot text"
            body shouldContain "<header>" // full page extends layout
            body shouldContain "<nav>"
            // viewer → Report Queue back-link (the report→edit-history triage loop).
            body shouldContain "href=\"/admin/reports\""
        }
    }

    "4.1 — unauthenticated GET redirects (302) to /admin/login" {
        val postId = UUID.randomUUID()
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/posts/$postId/edits")
            res.status shouldBe HttpStatusCode.Found
            res.headers[HttpHeaders.Location] shouldBe "/admin/login"
        }
    }

    "4.2 — live content is the newest version above the snapshots; oldest version is the original content" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val author = user()
        val postId = PostEditsTestSupport.seedPost(dataSource, author, content = "CURRENT")
        PostEditsTestSupport.seedEdit(dataSource, postId, author, "ORIGINAL", t1) // oldest
        PostEditsTestSupport.seedEdit(dataSource, postId, author, "MIDDLE", t2)
        PostEditsTestSupport.seedEdit(dataSource, postId, author, "RECENT", t3) // newest snapshot

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/posts/$postId/edits") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            // composed order, top→bottom: live (terbaru) → RECENT → MIDDLE → ORIGINAL
            val iLive = body.indexOf("Versi terbaru")
            val iRecent = body.indexOf("RECENT")
            val iMiddle = body.indexOf("MIDDLE")
            val iOriginal = body.indexOf("ORIGINAL")
            (iLive in 0 until iRecent) shouldBe true
            (iRecent < iMiddle) shouldBe true
            (iMiddle < iOriginal) shouldBe true
            // N snapshots (3) → N+1 versions: terbaru + ke-2..ke-4
            body shouldContain "Versi ke-2"
            body shouldContain "Versi ke-4"
            body shouldNotContain "Versi ke-5"
        }
    }

    "4.3 — a post with zero edits renders exactly one (live) version + the no-history note" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val author = user()
        val postId = PostEditsTestSupport.seedPost(dataSource, author, content = "only the live one")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/posts/$postId/edits") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "Versi terbaru"
            body shouldContain "only the live one"
            body shouldContain "Belum ada riwayat edit"
            body shouldNotContain "Versi ke-" // no snapshots
        }
    }

    "4.4a — unknown post id renders the empty state (200, not 500)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/posts/${UUID.randomUUID()}/edits") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "Post tidak ditemukan"
        }
    }

    "4.4b — non-UUID + SQL-metacharacter post id is a literal not-found (200, no injection)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val author = user()
        PostEditsTestSupport.seedPost(dataSource, author)
        val postsBefore = PostEditsTestSupport.countPosts(dataSource)

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val plain = client.get("/admin/posts/not-a-uuid/edits") { header(HttpHeaders.Cookie, cookie(token)) }
            plain.status shouldBe HttpStatusCode.OK
            plain.bodyAsText() shouldContain "Post tidak ditemukan"

            // URL-encoded `'; DROP TABLE post_edits;--`
            val inj =
                client.get("/admin/posts/%27%3B+DROP+TABLE+post_edits%3B--/edits") {
                    header(HttpHeaders.Cookie, cookie(token))
                }
            inj.status shouldBe HttpStatusCode.OK
        }
        PostEditsTestSupport.countPosts(dataSource) shouldBe postsBefore // table intact
    }

    "4.4c — a hard-deleted post renders the empty state" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val author = user()
        val postId = PostEditsTestSupport.seedPost(dataSource, author)
        PostEditsTestSupport.seedEdit(dataSource, postId, author, "gone soon", t1)
        PostEditsTestSupport.deletePost(dataSource, postId) // cascades post_edits

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/posts/$postId/edits") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "Post tidak ditemukan"
        }
    }

    "4.5a+b — >pageSize snapshots expose the older control; following it returns the next-older page only" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val author = user()
        val postId = PostEditsTestSupport.seedPost(dataSource, author, content = "live")
        // 51 snapshots (> PAGE_SIZE 50): snap_50 newest, snap_00 oldest.
        val base = Instant.parse("2026-06-01T00:00:00Z")
        repeat(51) { i ->
            PostEditsTestSupport.seedEdit(dataSource, postId, author, "snap_%02d".format(i), base.plusSeconds(i.toLong()))
        }

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val page1 = client.get("/admin/posts/$postId/edits") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            page1 shouldContain "Versi terbaru"
            page1 shouldContain "snap_50" // newest snapshot on page 1
            page1 shouldNotContain "snap_00" // spilled to page 2
            page1 shouldContain "pagination-older"

            val olderUrl =
                Regex("class=\"pagination-older\"\\s+href=\"([^\"]+)\"")
                    .find(page1)!!
                    .groupValues[1]
                    .replace("&amp;", "&")

            val page2 = client.get(olderUrl) { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            page2 shouldContain "snap_00" // the oldest snapshot
            page2 shouldNotContain "snap_50" // no overlap with page 1
            page2 shouldNotContain "Versi terbaru" // live version is page-1-only
        }
    }

    "4.6 — HX-Request returns only the fragment; plain GET returns the full page" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val author = user()
        val postId = PostEditsTestSupport.seedPost(dataSource, author)
        PostEditsTestSupport.seedEdit(dataSource, postId, author, "snap one", t1)

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val fragment =
                client.get("/admin/posts/$postId/edits") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("HX-Request", "true")
                }.bodyAsText()
            fragment shouldContain "id=\"post-edits-table\""
            fragment shouldNotContain "<html"
            fragment shouldNotContain "<header>"

            val full = client.get("/admin/posts/$postId/edits") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            full shouldContain "id=\"post-edits-table\""
            full shouldContain "<header>"
            full shouldContain "<nav>"
        }
    }

    "4.7 — markup content is escaped; the author cell deep-links to /admin/users?q=" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val author = user("budi_kopi")
        val postId = PostEditsTestSupport.seedPost(dataSource, author, content = "live ok")
        PostEditsTestSupport.seedEdit(dataSource, postId, author, "<script>alert(1)</script>", t1)

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val fragment =
                client.get("/admin/posts/$postId/edits") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("HX-Request", "true")
                }.bodyAsText()
            fragment shouldContain "&lt;script&gt;"
            fragment shouldNotContain "<script>alert(1)</script>"
            fragment shouldContain "href=\"/admin/users?q=budi_kopi\""
        }
    }

    "4.8 — a changed location shows 'lokasi berubah'; an unchanged one does not; raw coordinates never appear" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)

        // Post A: the only snapshot's location matches the live post → no indicator.
        val authorA = user()
        val postA = PostEditsTestSupport.seedPost(dataSource, authorA, content = "A live")
        PostEditsTestSupport.seedEdit(dataSource, postA, authorA, "A snap same loc", t1) // default point == live

        // Post B: the newest snapshot differs from the live location → indicator.
        val authorB = user()
        val postB = PostEditsTestSupport.seedPost(dataSource, authorB, content = "B live")
        PostEditsTestSupport.seedEdit(dataSource, postB, authorB, "B snap moved", t1, lon = 110.0, lat = -7.0)

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val bodyA = client.get("/admin/posts/$postA/edits") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            bodyA shouldNotContain "lokasi berubah"
            bodyA shouldNotContain "106.8" // raw coordinate never rendered
            bodyA shouldNotContain "-6.2"

            val bodyB = client.get("/admin/posts/$postB/edits") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            bodyB shouldContain "lokasi berubah"
            bodyB shouldNotContain "110.0" // raw coordinate never rendered
            bodyB shouldNotContain "-7.0"
        }
    }

    "4.9 — POST on the path is unmapped (405); serving the page writes no audit row and mutates nothing" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val author = user()
        val postId = PostEditsTestSupport.seedPost(dataSource, author)
        PostEditsTestSupport.seedEdit(dataSource, postId, author, "snap", t1)
        val postsBefore = PostEditsTestSupport.countPosts(dataSource)
        val editsBefore = PostEditsTestSupport.countEdits(dataSource)
        val auditBefore = AdminAuthTestSupport.countAuditRows(dataSource, admin.id)

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            // No mutation handler is mapped on the path: every mutating verb
            // (POST/PUT/PATCH/DELETE, per the spec) resolves to a client error,
            // never a 2xx. Ktor returns 404 for an unmapped method on a
            // parameterized multi-segment path (405 on a static path); both mean
            // "no such handler" — the spec requires only that it is not handled
            // as a mutation.
            val authed: HttpRequestBuilder.() -> Unit = {
                header(HttpHeaders.Cookie, cookie(token))
                header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
            }
            val path = "/admin/posts/$postId/edits"
            val mutations =
                listOf(
                    client.post(path, authed),
                    client.put(path, authed),
                    client.patch(path, authed),
                    client.delete(path, authed),
                )
            mutations.forEach { it.status shouldBeIn listOf(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed) }

            // serve the read twice
            client.get("/admin/posts/$postId/edits") { header(HttpHeaders.Cookie, cookie(token)) }
            client.get("/admin/posts/$postId/edits") { header(HttpHeaders.Cookie, cookie(token)) }
        }
        PostEditsTestSupport.countPosts(dataSource) shouldBe postsBefore
        PostEditsTestSupport.countEdits(dataSource) shouldBe editsBefore
        AdminAuthTestSupport.countAuditRows(dataSource, admin.id) shouldBe auditBefore
    }

    "4.10 — read_only admin can view; a shadow-banned author's post still renders" {
        val admin = seedAdmin(role = "read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val author = user(shadowBanned = true)
        val postId = PostEditsTestSupport.seedPost(dataSource, author, content = "shadow author live")
        PostEditsTestSupport.seedEdit(dataSource, postId, author, "shadow snap", t1)

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/posts/$postId/edits") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "shadow author live"
            body shouldContain "shadow snap"
        }
    }

    "the report queue deep-links a post report to the edit-history viewer" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val author = user("reported_author")
        val reporter = user("the_reporter")
        val postId = PostEditsTestSupport.seedPost(dataSource, author, content = "reported post")
        // Seed a pending report against the post.
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO reports (reporter_id, target_type, target_id, reason_category)
                VALUES (?, 'post', ?, 'spam')
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, reporter)
                ps.setObject(2, postId)
                ps.executeUpdate()
            }
        }

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/reports") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "/admin/posts/$postId/edits"
            body shouldContain "Lihat riwayat edit"
        }
        // Cleanup is handled by afterEach: deleting the reporter user cascades the
        // report row (reports.reporter_id ON DELETE CASCADE); target_id is
        // polymorphic (no FK), so deleting the post is unblocked.
    }
})
