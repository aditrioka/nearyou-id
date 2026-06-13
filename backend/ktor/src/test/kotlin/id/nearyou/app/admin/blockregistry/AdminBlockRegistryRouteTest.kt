package id.nearyou.app.admin.blockregistry

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuthProvider
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import id.nearyou.app.admin.auth.AdminCsrfGate
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.UUID

/**
 * Integration tests for the `GET /admin/blocks` route — the Block User Registry
 * viewer (`admin-block-registry` capability, tasks.md Section 7). DB-backed;
 * tagged `database`. Uses `AdminAuthTestSupport` for authenticated-session
 * wiring + `BlockRegistryTestSupport` for user/block seeding. Asserts the auth
 * gate, render + deep-link href, UTC rendering, HTMX/plain dual-mode, either-
 * side search, injection safety, output escaping, the bidirectional indicator,
 * the read-only/no-mutation/no-notification guards, role access, and the empty
 * states.
 */
@Tags("database")
class AdminBlockRegistryRouteTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val seededAdmins = mutableListOf<UUID>()
    val seededUsers = mutableListOf<UUID>()
    beforeEach { BlockRegistryTestSupport.deleteAllBlocks(dataSource) }
    afterEach {
        BlockRegistryTestSupport.deleteAllBlocks(dataSource)
        seededUsers.forEach { BlockRegistryTestSupport.deleteUser(dataSource, it) }
        seededUsers.clear()
        seededAdmins.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seededAdmins.clear()
    }

    fun seedAdmin(role: String = "owner"): AdminAuthTestSupport.SeededAdmin =
        AdminAuthTestSupport.seedAdmin(dataSource, role = role).also { seededAdmins.add(it.id) }

    fun cookie(token: String) = "${AdminAuthProvider.COOKIE_NAME}=$token"

    fun user(username: String? = null): UUID = BlockRegistryTestSupport.seedUser(dataSource, username).also { seededUsers.add(it) }

    fun block(
        blocker: UUID,
        blocked: UUID,
        at: Instant,
    ) = BlockRegistryTestSupport.seedBlock(dataSource, blocker, blocked, at)

    val base = Instant.parse("2026-05-20T00:00:00Z")

    "7.1 — authenticated GET renders the table with both usernames + base layout" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val blocker = user("blk_blocker")
        val blocked = user("blk_blocked")
        block(blocker, blocked, base)

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/blocks") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "blk_blocker"
            body shouldContain "blk_blocked"
            body shouldContain "<header>" // full page extends layout
            body shouldContain "<nav>" // frame-2 shell
        }
    }

    "7.1 — unauthenticated GET redirects (302) to /admin/login" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/blocks")
            res.status shouldBe HttpStatusCode.Found
            res.headers[HttpHeaders.Location] shouldBe "/admin/login"
        }
    }

    "7.1a — usernames deep-link to /admin/users?q=<username>, NOT /admin/users/{id}" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val blocker = user("blk_blocker")
        val blocked = user("blk_blocked")
        block(blocker, blocked, base)

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            // Use the HTMX fragment so the nav's own /admin/users link is absent
            // and the assertion is scoped to the table-row deep-links.
            val fragment =
                client.get("/admin/blocks") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("HX-Request", "true")
                }.bodyAsText()
            fragment shouldContain "href=\"/admin/users?q=blk_blocker\""
            fragment shouldContain "href=\"/admin/users?q=blk_blocked\""
            // the NEGATIVE guard against coupling to the in-flight profile page
            fragment shouldNotContain "/admin/users/$blocker"
            fragment shouldNotContain "/admin/users/$blocked"
        }
    }

    "7.2a — created_at renders in UTC (ISO-instant Z), not host-local" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val blocker = user()
        val blocked = user()
        // A near-day-boundary UTC instant: a +07:00 (WIB) interpretation would
        // wrongly push it to the 26th; the UTC rendering keeps it on the 25th.
        block(blocker, blocked, Instant.parse("2026-05-25T23:30:00Z"))

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.get("/admin/blocks") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "2026-05-25T23:30:00Z"
        }
    }

    "7.6 — HX-Request returns only the table fragment (no full-page layout)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        block(user("blk_a"), user("blk_b"), base)

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/blocks") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("HX-Request", "true")
                }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "id=\"block-registry-table\""
            body shouldNotContain "<html"
            body shouldNotContain "<header>"
        }
    }

    "7.6 — plain GET with a search returns the full page reflecting the filter" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val budi = user("budi_kopi")
        block(budi, user("blk_other"), base.plusSeconds(1))
        block(user("blk_z"), user("blk_w"), base.plusSeconds(2)) // unrelated

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/blocks?q=budi_kopi") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "id=\"block-registry-table\""
            body shouldContain "<header>" // full page
            body shouldContain "budi_kopi"
            body shouldNotContain "blk_z"
        }
    }

    "7.4 — search by username matches pairs on EITHER side" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val budi = user("budi_kopi")
        block(budi, user("blk_x"), base.plusSeconds(1)) // budi is blocker
        block(user("blk_y"), budi, base.plusSeconds(2)) // budi is blocked
        block(user("blk_z"), user("blk_w"), base.plusSeconds(3)) // unrelated

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.get("/admin/blocks?q=budi_kopi") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "blk_x" // budi-as-blocker pair
            body shouldContain "blk_y" // budi-as-blocked pair
            body shouldNotContain "blk_z" // unrelated pair excluded
        }
    }

    "7.4 — username search is case-insensitive and EXACT (substring does not match)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        block(user("Budi_Kopi"), user("blk_partner"), base)

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            // case-insensitive hit
            client.get("/admin/blocks?q=budi_kopi") { header(HttpHeaders.Cookie, cookie(token)) }
                .bodyAsText() shouldContain "blk_partner"
            // substring is NOT a match → empty state
            client.get("/admin/blocks?q=budi") { header(HttpHeaders.Cookie, cookie(token)) }
                .bodyAsText() shouldContain "No block pairs match"
        }
    }

    "7.4 — search by user-ID UUID matches pairs on either side" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val target = user("blk_target")
        block(target, user("blk_p"), base.plusSeconds(1))
        block(user("blk_q"), target, base.plusSeconds(2))

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.get("/admin/blocks?q=$target") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "blk_p"
            body shouldContain "blk_q"
        }
    }

    "7.4 — SQL-metacharacter q is bound as a literal (200) and the table survives" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        block(user("blk_survivor"), user("blk_other2"), base)
        val before = BlockRegistryTestSupport.countBlocks(dataSource)

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            // URL-encoded `'; DROP TABLE user_blocks;--`
            val res =
                client.get("/admin/blocks?q=%27%3B+DROP+TABLE+user_blocks%3B--") {
                    header(HttpHeaders.Cookie, cookie(token))
                }
            res.status shouldBe HttpStatusCode.OK
        }
        BlockRegistryTestSupport.countBlocks(dataSource) shouldBe before // table intact
    }

    "7.4 — over-long q is bounded → 200 empty state (no 400/500)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        block(user("blk_present"), user("blk_present2"), base)

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/blocks?q=${"x".repeat(200)}") {
                    header(HttpHeaders.Cookie, cookie(token))
                }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "No block pairs match"
        }
    }

    "7.7 — a username containing <script> renders escaped, not as a live tag" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        block(user("<script>alert(1)</script>"), user("blk_victim"), base)

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.get("/admin/blocks") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "&lt;script&gt;"
            body shouldNotContain "<script>alert(1)</script>"
        }
    }

    "7.8 — POST /admin/blocks returns 405 (only GET is wired)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/blocks") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                }
            res.status shouldBe HttpStatusCode.MethodNotAllowed
        }
    }

    "7.8 — serving the viewer mutates no block row, writes no audit row, notifies no one" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        block(user("blk_m1"), user("blk_m2"), base)
        val blocksBefore = BlockRegistryTestSupport.countBlocks(dataSource)
        val auditBefore = AdminAuthTestSupport.countAuditRows(dataSource, admin.id)
        val notifsBefore = BlockRegistryTestSupport.countNotifications(dataSource)

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.get("/admin/blocks") { header(HttpHeaders.Cookie, cookie(token)) }
            client.get("/admin/blocks") { header(HttpHeaders.Cookie, cookie(token)) }
        }
        BlockRegistryTestSupport.countBlocks(dataSource) shouldBe blocksBefore
        AdminAuthTestSupport.countAuditRows(dataSource, admin.id) shouldBe auditBefore
        BlockRegistryTestSupport.countNotifications(dataSource) shouldBe notifsBefore
    }

    "7.5 — bidirectional indicator: mutual pair shows 'yes (mutual)', one-directional shows 'no'" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val a = user("blk_mutual_a")
        val b = user("blk_mutual_b")
        block(a, b, base.plusSeconds(1))
        block(b, a, base.plusSeconds(2)) // mutual

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val mutualBody =
                client.get("/admin/blocks?q=blk_mutual_a") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            mutualBody shouldContain "yes (mutual)"
            mutualBody shouldNotContain ">no<"
        }

        // a separate one-directional pair shows "no"
        BlockRegistryTestSupport.deleteAllBlocks(dataSource)
        block(user("blk_one_a"), user("blk_one_b"), base)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val oneBody =
                client.get("/admin/blocks?q=blk_one_a") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            oneBody shouldContain ">no<" // the Bidirectional? cell renders <td>no</td>
            oneBody shouldNotContain "yes (mutual)"
        }
    }

    "7.9 — read_only admin can view the block registry" {
        val admin = seedAdmin(role = "read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        block(user("blk_ro1"), user("blk_ro2"), base)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/blocks") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
        }
    }

    "7.10 — no-match search renders the empty state (full page AND HTMX fragment)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        block(user("blk_exists"), user("blk_exists2"), base)

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val full =
                client.get("/admin/blocks?q=nobody_has_this_username") { header(HttpHeaders.Cookie, cookie(token)) }
            full.status shouldBe HttpStatusCode.OK
            full.bodyAsText() shouldContain "No block pairs match"

            val fragment =
                client.get("/admin/blocks?q=nobody_has_this_username") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("HX-Request", "true")
                }
            fragment.status shouldBe HttpStatusCode.OK
            val fragBody = fragment.bodyAsText()
            fragBody shouldContain "id=\"block-registry-table\""
            fragBody shouldContain "No block pairs match"
        }
    }

    "7.10 — a truly-empty user_blocks table renders the empty state (not an error)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        BlockRegistryTestSupport.deleteAllBlocks(dataSource) // zero rows, unfiltered

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/blocks") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "No block pairs match"
        }
    }

    "7.3 — malformed cursor falls back to the newest page (200, no error)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        block(user("blk_cursor"), user("blk_cursor2"), base)

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/blocks?cursor=not-a-valid-cursor") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "blk_cursor" // malformed cursor ignored, newest page rendered
        }
    }

    "7.3c — following the rendered older-cursor link retains the q filter + returns the next-older, non-overlapping page" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        // One hub user blocks 51 targets → 51 pairs, all with hub as blocker, so
        // a q=hub search matches every pair. Page size 50 → page 1 = tgt-50..tgt-01,
        // page 2 = tgt-00. created_at ascending so tgt-50 is newest.
        val hub = user("blkhub")
        repeat(51) { i ->
            block(hub, user("blktgt_%02d".format(i)), base.plusSeconds(i.toLong()))
        }

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val page1 =
                client.get("/admin/blocks?q=blkhub") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            page1 shouldContain "blktgt_50"
            page1 shouldContain "blktgt_01"
            page1 shouldNotContain "blktgt_00" // spilled to page 2

            // Extract the older-cursor link and follow it over HTTP — exercises the
            // full cursor encode → query-param → decode round-trip.
            val olderUrl =
                Regex("class=\"pagination-older\"\\s+href=\"([^\"]+)\"")
                    .find(page1)!!
                    .groupValues[1]
                    .replace("&amp;", "&")
            olderUrl shouldContain "q=blkhub" // the cursor link retains the active search

            val page2 = client.get(olderUrl) { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            page2 shouldContain "blktgt_00"
            page2 shouldNotContain "blktgt_50"
            page2 shouldNotContain "blktgt_01"
        }
    }
})
