package id.nearyou.app.admin.csam

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuthProvider
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeLessThan
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
import java.time.Instant
import java.util.UUID

/**
 * Integration tests for the `admin-csam-detection-log` surface — the CHILD-SAFETY
 * operator panel (`GET /admin/csam` + `POST /admin/csam/takedown` +
 * `POST /admin/csam/{id}/kominfo-report` + `POST /admin/csam/{id}/decrypt`), tasks.md
 * Section 7. One test per spec scenario (31 scenarios, no compression). DB-backed; tagged
 * `database`. Uses `AdminAuthTestSupport` for the authenticated-session wiring +
 * `CsamLogTestSupport` for `csam_detection_archive` / takedown-fixture / audit seeding.
 *
 * Covers — viewer (10): newest-first, source/Kominfo/date filters, keyset-stable-across-
 * insert, composable-ANDed filters, filter-input HTML-escape (XSS), plain-GET fallback,
 * pending-count, image-bytes-never-rendered; takedown (7): owner/admin actioned,
 * ledger-miss archived, idempotent count-stable, read-only 403, missing/cross-session
 * CSRF 403, missing-field, over-budget pre-flight; Kominfo (5): files (columns + audit
 * row), re-file rejected (original timestamp byte-equal, no audit row), blank-id, read-only
 * 403, dedicated-counter rate-limit; decrypt (4): owner/admin decrypts (fragment + one
 * audit row), fail-soft one-row-for-both-triggers, read-only 403 writes no decrypt row,
 * no-image-bytes; unblock (2): cf_worker link / admin_manual none; security invariants (3):
 * cross-session CSRF replay on every write, unauth→302 on GET + every write route,
 * no-image-content-anywhere.
 *
 * Pool is size 2 + closed in afterSpec (CI connection budget); it is NOT autoClose()d,
 * because the per-test afterEach cleanup hook uses this same DataSource — autoClose would
 * tear the pool down before the final cleanup ran (it closes in afterSpec, AFTER the last
 * afterEach).
 */
@Tags("database")
class AdminCsamRouteTest : StringSpec({

    val dataSource: HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
                username = System.getenv("DB_USER") ?: "postgres"
                password = System.getenv("DB_PASSWORD") ?: "postgres"
                maximumPoolSize = 2
                initializationFailTimeout = -1
            },
        )

    // Per-test tracking for deterministic cleanup by id / prefix.
    val seededHashes = mutableListOf<String>()
    val seededArchiveIds = mutableListOf<UUID>()
    val seededUserIds = mutableListOf<UUID>()
    val seededAdmins = mutableListOf<UUID>()
    var runTag = "csamlog"

    afterEach {
        CsamLogTestSupport.cleanup(dataSource, seededHashes, seededArchiveIds, seededUserIds, "$runTag-post")
        seededHashes.clear()
        seededArchiveIds.clear()
        seededUserIds.clear()
        seededAdmins.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seededAdmins.clear()
    }
    afterSpec { dataSource.close() }

    fun seedAdmin(role: String = "owner") = AdminAuthTestSupport.seedAdmin(dataSource, role = role).also { seededAdmins.add(it.id) }

    fun cookie(token: String) = "${AdminAuthProvider.COOKIE_NAME}=$token"

    fun seedArchive(
        hash: String,
        source: String = "cf_worker",
        createdAt: Instant = Instant.now(),
        kominfoReportedAt: Instant? = null,
        kominfoReportId: String? = null,
        cfMatchId: String? = null,
        encryptedMetadata: ByteArray? = null,
    ): UUID {
        seededHashes.add(hash)
        return CsamLogTestSupport.seedArchive(
            dataSource,
            imageHash = hash,
            source = source,
            createdAt = createdAt,
            kominfoReportedAt = kominfoReportedAt,
            kominfoReportId = kominfoReportId,
            cfMatchId = cfMatchId,
            encryptedMetadata = encryptedMetadata,
        ).also { seededArchiveIds.add(it) }
    }

    // ============================ 7.1 — Viewer (10) ============================

    "7.1 — newest-first listing orders by created_at DESC (ties broken by id)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedArchive("$runTag-older", createdAt = Instant.parse("2026-01-01T00:00:00Z"))
        seedArchive("$runTag-newer", createdAt = Instant.parse("2026-06-01T00:00:00Z"))

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/csam") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            // both rows render with detected-date + hash prefix + an enforcement/archive pill
            body shouldContain "2026-06-01 00:00 UTC"
            body shouldContain "2026-01-01 00:00 UTC"
            body shouldContain "archived"
            // newest detected timestamp appears before the older one in the table
            body.indexOf("2026-06-01 00:00") shouldBeLessThan body.indexOf("2026-01-01 00:00")
        }
    }

    "7.1 — filter by source narrows to the requested source" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val cfw = seedArchive("$runTag-cfw", source = "cf_worker", cfMatchId = "$runTag-cf-1")
        val man = seedArchive("$runTag-man", source = "admin_manual")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            // Use the HTMX fragment (table only — no filter <select>/info-banner chrome
            // that mentions both source names) and assert by the rows' archive-id prefixes.
            val body =
                client.get("/admin/csam?source=admin_manual") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("HX-Request", "true")
                }.bodyAsText()
            body shouldContain man.toString().take(8)
            body shouldNotContain cfw.toString().take(8)
        }
    }

    "7.1 — filter by Kominfo status pending lists only unfiled rows" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val pending = seedArchive("$runTag-pending", source = "admin_manual")
        val filed =
            seedArchive(
                "$runTag-filed",
                source = "admin_manual",
                kominfoReportedAt = Instant.now().minusSeconds(3600),
                kominfoReportId = "KMF-FILED-1",
            )

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/csam?kominfo=pending") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            // the pending row's decrypt/kominfo affordance markers vs the filed row's id
            body shouldContain pending.toString().take(8)
            body shouldNotContain filed.toString().take(8)
            body shouldNotContain "KMF-FILED-1"
        }
    }

    "7.1 — filter by created-at date range lists only in-range rows" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedArchive("$runTag-inrange", createdAt = Instant.parse("2026-03-15T12:00:00Z"))
        seedArchive("$runTag-before", createdAt = Instant.parse("2026-01-05T12:00:00Z"))
        seedArchive("$runTag-after", createdAt = Instant.parse("2026-09-20T12:00:00Z"))

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.get("/admin/csam?from=2026-03-01&to=2026-03-31") {
                    header(HttpHeaders.Cookie, cookie(token))
                }.bodyAsText()
            body shouldContain "2026-03-15 12:00 UTC"
            body shouldNotContain "2026-01-05 12:00 UTC"
            body shouldNotContain "2026-09-20 12:00 UTC"
        }
    }

    "7.1 — keyset pagination is stable across an insert between pages" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        // Four rows with DISTINCT minute-level detected timestamps so each is identifiable
        // in the rendered table. p1 is the newest, p4 the oldest.
        val p2At = Instant.parse("2026-05-01T03:00:00Z")
        seedArchive("$runTag-p1", source = "admin_manual", createdAt = Instant.parse("2026-05-01T04:00:00Z")) // newest
        val p2 = seedArchive("$runTag-p2", source = "admin_manual", createdAt = p2At)
        seedArchive("$runTag-p3", source = "admin_manual", createdAt = Instant.parse("2026-05-01T02:00:00Z"))
        seedArchive("$runTag-p4", source = "admin_manual", createdAt = Instant.parse("2026-05-01T01:00:00Z")) // oldest

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            // Simulate "the admin already paged past p1+p2, then a newer row was inserted":
            // request the next page with the cursor positioned AT p2 (its real (created_at, id),
            // exactly what the prior page's nextCursor would carry). The keyset predicate is
            // (created_at, id) < (p2.created_at, p2.id), so p2 itself is excluded (no dup),
            // p3+p4 follow, and the newer p1 (04:00) never shifts into this window (stable).
            val cursor = id.nearyou.app.moderation.csam.CsamArchiveCursor(p2At, p2)
            val body =
                client.get(
                    "/admin/csam?source=admin_manual&from=2026-05-01&to=2026-05-02&cursor=${cursor.encode()}",
                ) { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            // older-than-cursor rows present, newer/equal absent — no skip, no dup.
            body shouldContain "2026-05-01 02:00 UTC" // p3
            body shouldContain "2026-05-01 01:00 UTC" // p4
            body shouldNotContain "2026-05-01 04:00 UTC" // p1 (newer) does not shift in
            body shouldNotContain "2026-05-01 03:00 UTC" // p2 (the cursor row) not duplicated
        }
    }

    "7.1 — composable filters combine (ANDed, not last-wins)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        // matches ALL three: admin_manual + pending + in March
        val match = seedArchive("$runTag-all3", source = "admin_manual", createdAt = Instant.parse("2026-03-10T08:00:00Z"))
        // fails source (cf_worker) but matches the rest
        seedArchive("$runTag-wrongsrc", source = "cf_worker", createdAt = Instant.parse("2026-03-11T08:00:00Z"), cfMatchId = "$runTag-cf-x")
        // fails kominfo (filed) but matches the rest
        seedArchive(
            "$runTag-wrongkom",
            source = "admin_manual",
            createdAt = Instant.parse("2026-03-12T08:00:00Z"),
            kominfoReportedAt = Instant.now(),
            kominfoReportId = "KMF-WRONG",
        )
        // fails date (September) but matches the rest
        seedArchive("$runTag-wrongdate", source = "admin_manual", createdAt = Instant.parse("2026-09-12T08:00:00Z"))

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.get("/admin/csam?source=admin_manual&kominfo=pending&from=2026-03-01&to=2026-03-31") {
                    header(HttpHeaders.Cookie, cookie(token))
                }.bodyAsText()
            body shouldContain match.toString().take(8)
            body shouldNotContain "KMF-WRONG"
            body shouldNotContain "2026-09-12 08:00 UTC"
            body shouldNotContain "2026-03-11 08:00 UTC" // the cf_worker row excluded by source
        }
    }

    "7.1 — filter inputs are HTML-escaped (XSS guard)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedArchive("$runTag-xss", source = "admin_manual")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            // source is enum-bounded; the date fields are the free-est reflected inputs.
            // Feed a <script> payload in `from` (it won't parse as a date so it's dropped,
            // but the chip/echo path must never emit it live). Also probe `source` directly.
            val body =
                client.get("/admin/csam?source=%3Cscript%3Ealert(1)%3C%2Fscript%3E") {
                    header(HttpHeaders.Cookie, cookie(token))
                }.bodyAsText()
            // An out-of-enum source reaches the repo as a bound literal (zero rows) and is
            // echoed back into the chip + reflected — escaped, never live markup.
            body shouldNotContain "<script>alert(1)</script>"
            body shouldContain "&lt;script&gt;"
        }
    }

    "7.1 — plain GET (no HX-Request) returns a full HTML page" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedArchive("$runTag-fullpage", source = "admin_manual")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/csam") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "<header>"
            body shouldContain "<nav>"
            body shouldContain "id=\"csam-log-table\""
            body shouldContain "CSAM Detection Log"
        }
    }

    "7.1 — HX-Request returns only the table fragment (no full-page layout)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedArchive("$runTag-frag", source = "admin_manual")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body =
                client.get("/admin/csam") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("HX-Request", "true")
                }.bodyAsText()
            body shouldContain "id=\"csam-log-table\""
            body shouldNotContain "<html"
            body shouldNotContain "<header>"
        }
    }

    "7.1 — pending-count summary reflects unfiled rows" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val before =
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT count(*) FROM csam_detection_archive WHERE kominfo_reported_at IS NULL").use { ps ->
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            }
        seedArchive("$runTag-pc1", source = "admin_manual")
        seedArchive("$runTag-pc2", source = "cf_worker", cfMatchId = "$runTag-cf-pc")
        seedArchive("$runTag-pc-filed", source = "admin_manual", kominfoReportedAt = Instant.now(), kominfoReportId = "KMF-PC")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/csam") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "Pending Kominfo filing: ${before + 2}"
        }
    }

    "7.1 — image bytes are never rendered in the viewer" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedArchive("$runTag-noimg", source = "cf_worker", cfMatchId = "$runTag-cf-noimg")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/csam") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldNotContain "<img"
            body shouldNotContain "img.nearyou.id"
            body shouldNotContain "data:image"
        }
    }

    // ============================ 7.2 — Takedown (7) ============================

    fun seedTakedownFixture(): Triple<UUID, String, String> {
        val uploader = CsamLogTestSupport.seedUser(dataSource, "${runTag}up").also { seededUserIds.add(it) }
        val cfImageId = "$runTag-img-${UUID.randomUUID().toString().take(6)}"
        CsamLogTestSupport.seedImageUpload(dataSource, cfImageId, uploader)
        CsamLogTestSupport.seedPost(dataSource, uploader, imageId = cfImageId, contentTag = "$runTag-post")
        val hash = "$runTag-tdhash-${UUID.randomUUID().toString().take(6)}"
        seededHashes.add(hash) // takedown writes the archive row by hash
        return Triple(uploader, cfImageId, hash)
    }

    "7.2 — owner/admin invokes takedown on a live upload (actioned)" {
        val admin = seedAdmin(role = "admin")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val (uploader, cfImageId, hash) = seedTakedownFixture()

        AdminAuthTestSupport.withAdminApp(dataSource, csamMetadataEncryptor = CsamLogTestSupport.encryptorWithKey()) { client ->
            val res =
                client.post("/admin/csam/takedown") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    header("HX-Request", "true")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("image_id=$cfImageId&image_hash=$hash")
                }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "actioned"
            CsamLogTestSupport.userBanState(dataSource, uploader).first shouldBe true
            CsamLogTestSupport.archiveCount(dataSource, hash) shouldBe 1
            // the service writes the takedown audit row attributed to the acting admin
            CsamLogTestSupport.auditCountForTarget(dataSource, "csam_takedown", uploader.toString()) shouldBe 1
        }
    }

    "7.2 — ledger-miss (no image_uploads row) archives only" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val cfImageId = "$runTag-img-absent"
        val hash = "$runTag-misshash"
        seededHashes.add(hash)

        AdminAuthTestSupport.withAdminApp(dataSource, csamMetadataEncryptor = CsamLogTestSupport.encryptorWithKey()) { client ->
            val res =
                client.post("/admin/csam/takedown") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    header("HX-Request", "true")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("image_id=$cfImageId&image_hash=$hash")
                }
            res.bodyAsText() shouldContain "archived"
            CsamLogTestSupport.archiveCount(dataSource, hash) shouldBe 1
        }
    }

    "7.2 — idempotent re-invocation is count-stable (one ban, no 2nd cascade/archive)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val (uploader, cfImageId, hash) = seedTakedownFixture()
        // a benign 2nd post by the same uploader, to prove the cascade fires exactly once
        CsamLogTestSupport.seedPost(dataSource, uploader, imageId = null, contentTag = "$runTag-post")

        AdminAuthTestSupport.withAdminApp(dataSource, csamMetadataEncryptor = CsamLogTestSupport.encryptorWithKey()) { client ->
            suspend fun invoke() =
                client.post("/admin/csam/takedown") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    header("HX-Request", "true")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("image_id=$cfImageId&image_hash=$hash")
                }
            invoke().status shouldBe HttpStatusCode.OK
            val (_, vAfterFirst) = CsamLogTestSupport.userBanState(dataSource, uploader)
            invoke() // second invocation converges
            val (banned, vAfterSecond) = CsamLogTestSupport.userBanState(dataSource, uploader)

            banned shouldBe true
            // token_version bumped exactly once (no second ban event)
            vAfterSecond shouldBe vAfterFirst
            // exactly ONE archive row by image_hash (ON CONFLICT enrich, no duplicate)
            CsamLogTestSupport.archiveCount(dataSource, hash) shouldBe 1
        }
    }

    "7.2 — a read_only admin cannot invoke takedown (403, no takedown)" {
        val admin = seedAdmin(role = "read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val (uploader, cfImageId, hash) = seedTakedownFixture()

        AdminAuthTestSupport.withAdminApp(dataSource, csamMetadataEncryptor = CsamLogTestSupport.encryptorWithKey()) { client ->
            val res =
                client.post("/admin/csam/takedown") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("image_id=$cfImageId&image_hash=$hash")
                }
            res.status shouldBe HttpStatusCode.Forbidden
            CsamLogTestSupport.userBanState(dataSource, uploader).first shouldBe false
            CsamLogTestSupport.archiveCount(dataSource, hash) shouldBe 0
        }
    }

    "7.2 — missing or cross-session CSRF token is rejected (403, no takedown)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        // a SECOND admin session whose CSRF token we replay (cross-session)
        val other = seedAdmin()
        val otherToken = AdminAuthTestSupport.seedSession(dataSource, other.id)
        val (uploader, cfImageId, hash) = seedTakedownFixture()

        AdminAuthTestSupport.withAdminApp(dataSource, csamMetadataEncryptor = CsamLogTestSupport.encryptorWithKey()) { client ->
            // cross-session: session A cookie + session B's CSRF token
            val replay =
                client.post("/admin/csam/takedown") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(otherToken))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("image_id=$cfImageId&image_hash=$hash")
                }
            replay.status shouldBe HttpStatusCode.Forbidden

            // missing token
            val missing =
                client.post("/admin/csam/takedown") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("image_id=$cfImageId&image_hash=$hash")
                }
            missing.status shouldBe HttpStatusCode.Forbidden

            CsamLogTestSupport.userBanState(dataSource, uploader).first shouldBe false
            CsamLogTestSupport.archiveCount(dataSource, hash) shouldBe 0
        }
    }

    "7.2 — a blank image_id or image_hash is rejected (validation error, no takedown)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)

        AdminAuthTestSupport.withAdminApp(dataSource, csamMetadataEncryptor = CsamLogTestSupport.encryptorWithKey()) { client ->
            val res =
                client.post("/admin/csam/takedown") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("image_id=&image_hash=somehash")
                }
            res.status shouldBe HttpStatusCode.BadRequest
        }
    }

    "7.2 — takedown rejected when over the shared destructive budget (pre-flight, service not invoked)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val (uploader, cfImageId, hash) = seedTakedownFixture()
        // exhaust the 20/hr destructive budget with punitive rows (counted by the shared limiter)
        repeat(20) { CsamLogTestSupport.seedAuditRow(dataSource, admin.id, "user_banned") }

        AdminAuthTestSupport.withAdminApp(dataSource, csamMetadataEncryptor = CsamLogTestSupport.encryptorWithKey()) { client ->
            val res =
                client.post("/admin/csam/takedown") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    header("HX-Request", "true")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("image_id=$cfImageId&image_hash=$hash")
                }
            res.bodyAsText() shouldContain "quota exceeded"
            // the service was NOT invoked: no ban, no archive row
            CsamLogTestSupport.userBanState(dataSource, uploader).first shouldBe false
            CsamLogTestSupport.archiveCount(dataSource, hash) shouldBe 0
        }
    }

    // ============================ 7.3 — Kominfo (5) ============================

    "7.3 — owner/admin files the Kominfo report (columns set + one audit row)" {
        val admin = seedAdmin(role = "admin")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val archiveId = seedArchive("$runTag-komfile", source = "admin_manual")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/csam/$archiveId/kominfo-report") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    header("HX-Request", "true")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("kominfo_report_id=KMF-2026-0312")
                }
            res.status shouldBe HttpStatusCode.OK
            val state = CsamLogTestSupport.kominfoStateOf(dataSource, archiveId)!!
            state.reportId shouldBe "KMF-2026-0312"
            (state.reportedAtMicros != null) shouldBe true
            CsamLogTestSupport.auditCountForTarget(dataSource, "csam_kominfo_reported", archiveId.toString()) shouldBe 1
        }
    }

    "7.3 — re-filing an already-filed row is rejected (no mutation, no audit row, original timestamp preserved)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val originalAt = Instant.parse("2026-04-01T09:30:00Z")
        val archiveId =
            seedArchive("$runTag-komrefile", source = "admin_manual", kominfoReportedAt = originalAt, kominfoReportId = "KMF-ORIGINAL")
        val before = CsamLogTestSupport.kominfoStateOf(dataSource, archiveId)!!

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/csam/$archiveId/kominfo-report") {
                header(HttpHeaders.Cookie, cookie(token))
                header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                header("HX-Request", "true")
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("kominfo_report_id=KMF-SECOND-ATTEMPT")
            }.bodyAsText() shouldContain "already"
            val after = CsamLogTestSupport.kominfoStateOf(dataSource, archiveId)!!
            // original report id + timestamp byte-equal (micros) — never overwritten
            after.reportId shouldBe "KMF-ORIGINAL"
            after.reportedAtMicros shouldBe before.reportedAtMicros
            // NO audit row written for the rejected re-file
            CsamLogTestSupport.auditCountForTarget(dataSource, "csam_kominfo_reported", archiveId.toString()) shouldBe 0
        }
    }

    "7.3 — a blank Kominfo report id is rejected (validation error, row unchanged)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val archiveId = seedArchive("$runTag-komblank", source = "admin_manual")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/csam/$archiveId/kominfo-report") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("kominfo_report_id=")
                }
            res.status shouldBe HttpStatusCode.BadRequest
            CsamLogTestSupport.kominfoStateOf(dataSource, archiveId)!!.reportedAtMicros shouldBe null
        }
    }

    "7.3 — a read_only admin cannot file Kominfo (403, row unchanged)" {
        val admin = seedAdmin(role = "read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val archiveId = seedArchive("$runTag-komro", source = "admin_manual")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/csam/$archiveId/kominfo-report") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("kominfo_report_id=KMF-RO")
                }
            res.status shouldBe HttpStatusCode.Forbidden
            CsamLogTestSupport.kominfoStateOf(dataSource, archiveId)!!.reportedAtMicros shouldBe null
        }
    }

    "7.3 — Kominfo write is rate-limited on its own counter (independent of the 20/hr destructive budget)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val archiveId = seedArchive("$runTag-komrl", source = "admin_manual")
        // saturate the dedicated 30/hr Kominfo counter
        repeat(
            30,
        ) { CsamLogTestSupport.seedAuditRow(dataSource, admin.id, "csam_kominfo_reported", targetId = UUID.randomUUID().toString()) }

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/csam/$archiveId/kominfo-report") {
                header(HttpHeaders.Cookie, cookie(token))
                header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                header("HX-Request", "true")
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("kominfo_report_id=KMF-OVER")
            }.bodyAsText() shouldContain "quota exceeded"
            // the filing was NOT recorded
            CsamLogTestSupport.kominfoStateOf(dataSource, archiveId)!!.reportedAtMicros shouldBe null
        }
    }

    "7.3 — Kominfo write succeeds when the destructive budget is exhausted (independent counters)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val archiveId = seedArchive("$runTag-komindep", source = "admin_manual")
        repeat(20) { CsamLogTestSupport.seedAuditRow(dataSource, admin.id, "user_banned") }

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/csam/$archiveId/kominfo-report") {
                header(HttpHeaders.Cookie, cookie(token))
                header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                header("HX-Request", "true")
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("kominfo_report_id=KMF-INDEP")
            }
            CsamLogTestSupport.kominfoStateOf(dataSource, archiveId)!!.reportId shouldBe "KMF-INDEP"
        }
    }

    // ============================ 7.4 — Decrypt (4) ============================

    "7.4 — owner/admin decrypts metadata (fragment + exactly one audit row)" {
        val admin = seedAdmin(role = "admin")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uploader = CsamLogTestSupport.seedUser(dataSource, "${runTag}dec").also { seededUserIds.add(it) }
        val hash = "$runTag-dechash"
        val blob = CsamLogTestSupport.encryptMetadataBlob(hash, uploader, affectedPostId = null, source = "admin_manual", cascadeCount = 2)
        val archiveId = seedArchive(hash, source = "admin_manual", encryptedMetadata = blob)

        AdminAuthTestSupport.withAdminApp(dataSource, csamMetadataEncryptor = CsamLogTestSupport.encryptorWithKey()) { client ->
            val res =
                client.post("/admin/csam/$archiveId/decrypt") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    header("HX-Request", "true")
                }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            // raw pseudonymous uploader UUID surfaced (design D4) + cascade count
            body shouldContain uploader.toString()
            body shouldContain "Uploader user id"
            body shouldNotContain "<img"
            CsamLogTestSupport.auditCountForTarget(dataSource, "csam_metadata_decrypted", archiveId.toString()) shouldBe 1
        }
    }

    "7.4 — decrypt is fail-soft and writes exactly one audit row for BOTH triggers (key unset AND metadata NULL)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        // Trigger A — key UNSET (encryptor with no key) but the row HAS a blob.
        val uploaderA = CsamLogTestSupport.seedUser(dataSource, "${runTag}nokey").also { seededUserIds.add(it) }
        val hashA = "$runTag-nokeyhash"
        val blobA = CsamLogTestSupport.encryptMetadataBlob(hashA, uploaderA, affectedPostId = null, source = "cf_worker", cascadeCount = 0)
        val archiveA = seedArchive(hashA, source = "cf_worker", cfMatchId = "$runTag-cf-nokey", encryptedMetadata = blobA)
        // Trigger B — encrypted_metadata NULL (key present, but no blob).
        val archiveB = seedArchive("$runTag-nullmeta", source = "admin_manual", encryptedMetadata = null)

        // A: key UNSET
        AdminAuthTestSupport.withAdminApp(dataSource, csamMetadataEncryptor = CsamLogTestSupport.encryptorNoKey()) { client ->
            val res =
                client.post("/admin/csam/$archiveA/decrypt") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    header("HX-Request", "true")
                }
            res.status shouldBe HttpStatusCode.OK // never a 500
            res.bodyAsText() shouldContain "unavailable"
        }
        // B: metadata NULL (key present)
        AdminAuthTestSupport.withAdminApp(dataSource, csamMetadataEncryptor = CsamLogTestSupport.encryptorWithKey()) { client ->
            val res =
                client.post("/admin/csam/$archiveB/decrypt") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    header("HX-Request", "true")
                }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "unavailable"
        }
        // BOTH authorized fail-soft attempts logged exactly one row each.
        CsamLogTestSupport.auditCountForTarget(dataSource, "csam_metadata_decrypted", archiveA.toString()) shouldBe 1
        CsamLogTestSupport.auditCountForTarget(dataSource, "csam_metadata_decrypted", archiveB.toString()) shouldBe 1
    }

    "7.4 — a read_only admin is rejected from decrypt (403, NO csam_metadata_decrypted audit row)" {
        val admin = seedAdmin(role = "read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uploader = CsamLogTestSupport.seedUser(dataSource, "${runTag}decro").also { seededUserIds.add(it) }
        val hash = "$runTag-decrohash"
        val blob = CsamLogTestSupport.encryptMetadataBlob(hash, uploader, affectedPostId = null, source = "admin_manual", cascadeCount = 0)
        val archiveId = seedArchive(hash, source = "admin_manual", encryptedMetadata = blob)

        AdminAuthTestSupport.withAdminApp(dataSource, csamMetadataEncryptor = CsamLogTestSupport.encryptorWithKey()) { client ->
            val res =
                client.post("/admin/csam/$archiveId/decrypt") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    header("HX-Request", "true")
                }
            res.status shouldBe HttpStatusCode.Forbidden
            // a 403 authZ rejection writes NO decrypt audit row (design D4)
            CsamLogTestSupport.auditCountForTarget(dataSource, "csam_metadata_decrypted", archiveId.toString()) shouldBe 0
        }
    }

    "7.4 — the decrypt fragment contains no image bytes or content URL" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uploader = CsamLogTestSupport.seedUser(dataSource, "${runTag}decnoimg").also { seededUserIds.add(it) }
        val hash = "$runTag-decnoimghash"
        val blob =
            CsamLogTestSupport.encryptMetadataBlob(
                hash,
                uploader,
                affectedPostId = UUID.randomUUID(),
                source = "cf_worker",
                cascadeCount = 1,
            )
        val archiveId = seedArchive(hash, source = "cf_worker", cfMatchId = "$runTag-cf-decnoimg", encryptedMetadata = blob)

        AdminAuthTestSupport.withAdminApp(dataSource, csamMetadataEncryptor = CsamLogTestSupport.encryptorWithKey()) { client ->
            val body =
                client.post("/admin/csam/$archiveId/decrypt") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    header("HX-Request", "true")
                }.bodyAsText()
            body shouldNotContain "<img"
            body shouldNotContain "img.nearyou.id"
            body shouldNotContain "data:image"
        }
    }

    // ============================ 7.5 — Unblock surfacing (2) ============================

    "7.5 — a cf_worker row surfaces the CF review/unblock link-out" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedArchive("$runTag-cfunblock", source = "cf_worker", cfMatchId = "$runTag-cf-unblock")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/csam?source=cf_worker") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "Review"
            body shouldContain "cloudflare.com"
        }
    }

    "7.5 — an admin_manual row shows no unblock affordance" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedArchive("$runTag-manunblock", source = "admin_manual")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/csam?source=admin_manual") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldNotContain "cloudflare.com"
            // the unblock cell renders the em-dash placeholder for admin_manual
            body shouldContain "—"
        }
    }

    // ============================ 7.6 — Security invariants (3) ============================

    "7.6 — cross-session CSRF replay is rejected on EVERY write (takedown + kominfo + decrypt)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val other = seedAdmin()
        val otherToken = AdminAuthTestSupport.seedSession(dataSource, other.id)
        val (uploader, cfImageId, hash) = seedTakedownFixture()
        val archiveId = seedArchive("$runTag-csrf3", source = "admin_manual", encryptedMetadata = null)

        AdminAuthTestSupport.withAdminApp(dataSource, csamMetadataEncryptor = CsamLogTestSupport.encryptorWithKey()) { client ->
            // session A cookie + session B CSRF token on each write route
            client.post("/admin/csam/takedown") {
                header(HttpHeaders.Cookie, cookie(token))
                header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(otherToken))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("image_id=$cfImageId&image_hash=$hash")
            }.status shouldBe HttpStatusCode.Forbidden
            client.post("/admin/csam/$archiveId/kominfo-report") {
                header(HttpHeaders.Cookie, cookie(token))
                header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(otherToken))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("kominfo_report_id=KMF-X")
            }.status shouldBe HttpStatusCode.Forbidden
            client.post("/admin/csam/$archiveId/decrypt") {
                header(HttpHeaders.Cookie, cookie(token))
                header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(otherToken))
            }.status shouldBe HttpStatusCode.Forbidden

            // no state changed
            CsamLogTestSupport.userBanState(dataSource, uploader).first shouldBe false
            CsamLogTestSupport.archiveCount(dataSource, hash) shouldBe 0
            CsamLogTestSupport.kominfoStateOf(dataSource, archiveId)!!.reportedAtMicros shouldBe null
            CsamLogTestSupport.auditCountForTarget(dataSource, "csam_metadata_decrypted", archiveId.toString()) shouldBe 0
        }
    }

    "7.6 — unauthenticated access is redirected (302 /admin/login) on GET AND every write route" {
        // a real archive id (no session sent) — the redirect must precede any handler logic
        val archiveId = seedArchive("$runTag-unauth", source = "admin_manual")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val get = client.get("/admin/csam")
            get.status shouldBe HttpStatusCode.Found
            get.headers[HttpHeaders.Location] shouldBe "/admin/login"

            val takedown =
                client.post("/admin/csam/takedown") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("image_id=x&image_hash=y")
                }
            takedown.status shouldBe HttpStatusCode.Found
            takedown.headers[HttpHeaders.Location] shouldBe "/admin/login"

            val kominfo =
                client.post("/admin/csam/$archiveId/kominfo-report") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("kominfo_report_id=z")
                }
            kominfo.status shouldBe HttpStatusCode.Found
            kominfo.headers[HttpHeaders.Location] shouldBe "/admin/login"

            val decrypt = client.post("/admin/csam/$archiveId/decrypt")
            decrypt.status shouldBe HttpStatusCode.Found
            decrypt.headers[HttpHeaders.Location] shouldBe "/admin/login"
        }
    }

    "7.6 — no image content anywhere on the surface (read + decrypt responses)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val uploader = CsamLogTestSupport.seedUser(dataSource, "${runTag}noimgall").also { seededUserIds.add(it) }
        val hash = "$runTag-noimgallhash"
        val blob = CsamLogTestSupport.encryptMetadataBlob(hash, uploader, affectedPostId = null, source = "cf_worker", cascadeCount = 0)
        val archiveId = seedArchive(hash, source = "cf_worker", cfMatchId = "$runTag-cf-noimgall", encryptedMetadata = blob)

        AdminAuthTestSupport.withAdminApp(dataSource, csamMetadataEncryptor = CsamLogTestSupport.encryptorWithKey()) { client ->
            val list = client.get("/admin/csam") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            val decrypt =
                client.post("/admin/csam/$archiveId/decrypt") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    header("HX-Request", "true")
                }.bodyAsText()
            for (body in listOf(list, decrypt)) {
                body shouldNotContain "<img"
                body shouldNotContain "img.nearyou.id"
                body shouldNotContain "data:image"
            }
        }
    }
})
