package id.nearyou.app.admin.wordlist

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuthProvider
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import id.nearyou.app.admin.auth.AdminCsrfGate
import id.nearyou.app.infra.remoteconfig.PublishResult
import id.nearyou.app.infra.remoteconfig.RemoteConfigPublisher
import id.nearyou.app.infra.remoteconfig.RemoteConfigStringList
import id.nearyou.app.infra.remoteconfig.ServerTemplateSnapshot
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
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
import java.util.UUID

/**
 * Route-level integration tests for `adminWordlistEditor` (`admin-moderation-
 * wordlist-editor`, the frame-20 "edit" sub-surface): render + search (7.2), the
 * audited publish + no-op (7.3), the CSRF / owner-admin role / empty-list / stale /
 * write-unconfigured guards (7.4), the distinct 10/hour rate limit (7.5), the
 * `admin-feature-flags` edit-affordance regression (7.6), and the Server-template-
 * only scope boundary (7.7). Pure normalization/diff/import is in
 * [WordlistNormalizerTest].
 *
 * DB-backed; tagged `database`. Uses [AdminAuthTestSupport] + a
 * [FakeWordlistPublisher] so writes are exercised without a real Firebase project.
 */
@Tags("database")
class AdminWordlistEditorRouteTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val seededAdmins = mutableListOf<UUID>()
    afterEach {
        seededAdmins.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seededAdmins.clear()
    }

    fun seedAdmin(role: String = "owner"): AdminAuthTestSupport.SeededAdmin =
        AdminAuthTestSupport.seedAdmin(dataSource, role = role).also { seededAdmins += it.id }

    fun cookie(token: String) = "${AdminAuthProvider.COOKIE_NAME}=$token"

    fun formBody(vararg pairs: Pair<String, String>): String = pairs.toList().formUrlEncode()

    fun seedAuditRows(
        adminId: UUID,
        actionType: String,
        n: Int,
    ) {
        dataSource.connection.use { conn ->
            repeat(n) {
                conn.prepareStatement(
                    """
                    INSERT INTO admin_actions_log (admin_id, action_type, target_type, target_id, ip)
                    VALUES (?, ?, 'moderation_wordlist', 'moderation_profanity_list', '127.0.0.1'::inet)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, adminId)
                    ps.setString(2, actionType)
                    ps.executeUpdate()
                }
            }
        }
    }

    fun wordlistAuditRows(adminId: UUID) =
        AdminAuthTestSupport.latestAuditRows(dataSource, adminId).filter { it.actionType == "moderation_wordlist_edited" }

    // ============================ 7.2 render ==============================

    "authenticated GET renders the current list with version + search filter + count" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val fake = FakeWordlistPublisher(profanity = listOf("alpha", "beta", "gamma"))
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            val res = client.get("/admin/feature-flags/wordlists/profanity") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "moderation_profanity_list"
            body shouldContain "alpha"
            body shouldContain "gamma"
            body shouldContain "wl-filter"
            body shouldContain "3 entries"
            // GET is read-only — no audit row
            AdminAuthTestSupport.countAuditRows(dataSource, admin.id) shouldBe 0
        }
    }

    "rendered entries are HTML-escaped" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val fake = FakeWordlistPublisher(profanity = listOf("<script>alert(1)</script>"))
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            val body = client.get("/admin/feature-flags/wordlists/profanity") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "&lt;script&gt;"
            body shouldNotContain "<script>alert(1)"
        }
    }

    "unauthenticated GET redirects (302) to /admin/login" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/feature-flags/wordlists/profanity")
            res.status shouldBe HttpStatusCode.Found
            res.headers[HttpHeaders.Location] shouldBe "/admin/login"
        }
    }

    "an unknown list slug is 404" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = FakeWordlistPublisher()) { client ->
            client.get("/admin/feature-flags/wordlists/notalist") {
                header(HttpHeaders.Cookie, cookie(token))
            }.status shouldBe HttpStatusCode.NotFound
        }
    }

    "HX-Request returns only the content fragment" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = FakeWordlistPublisher()) { client ->
            val body =
                client.get("/admin/feature-flags/wordlists/profanity") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("HX-Request", "true")
                }.bodyAsText()
            body shouldContain "id=\"wl-content\""
            body shouldNotContain "<header>"
        }
    }

    "a moderator sees the editor read-only with no edit form" {
        val admin = seedAdmin(role = "moderator")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = FakeWordlistPublisher()) { client ->
            val body = client.get("/admin/feature-flags/wordlists/profanity") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "Read-only"
            body shouldNotContain "name=\"entries\""
        }
    }

    // ============================ 7.3 audited publish ==============================

    "an owner publish updates the list and writes exactly one diff-summary audit row" {
        val admin = seedAdmin(role = "owner")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val fake = FakeWordlistPublisher(profanity = listOf("a", "b"))
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            val res =
                client.post("/admin/feature-flags/wordlists/profanity") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("entries" to "a\nb\nc", "reason" to "add c for launch", "etag" to fake.etag))
                }
            res.status shouldBe HttpStatusCode.OK
            fake.publishedKeys shouldBe listOf("moderation_profanity_list")
            RemoteConfigStringList.decode(fake["moderation_profanity_list"])!! shouldContain "c"
            val rows = wordlistAuditRows(admin.id)
            rows shouldHaveSize 1
            rows[0].targetType shouldBe "moderation_wordlist"
            rows[0].reason shouldBe "add c for launch"
            rows[0].afterState!! shouldContain "added"
            rows[0].afterState!! shouldContain "moderation_profanity_list"
        }
    }

    "an admin (not just owner) publish is permitted" {
        val admin = seedAdmin(role = "admin")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val fake = FakeWordlistPublisher(uuIte = listOf("x"))
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            client.post("/admin/feature-flags/wordlists/uu_ite") {
                header(HttpHeaders.Cookie, cookie(token))
                header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(formBody("entries" to "x\ny", "reason" to "extend uu ite", "etag" to fake.etag))
            }
            fake.publishedKeys shouldBe listOf("moderation_uu_ite_list")
            wordlistAuditRows(admin.id) shouldHaveSize 1
        }
    }

    "a blank reason is rejected with no publish and no audit row" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val fake = FakeWordlistPublisher(profanity = listOf("a", "b"))
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            client.post("/admin/feature-flags/wordlists/profanity") {
                header(HttpHeaders.Cookie, cookie(token))
                header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(formBody("entries" to "a\nb\nc", "reason" to "   ", "etag" to fake.etag))
            }
            fake.publishedKeys.shouldBeEmpty()
            wordlistAuditRows(admin.id).shouldBeEmpty()
        }
    }

    "a no-op publish (resulting equals current) is rejected" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val fake = FakeWordlistPublisher(profanity = listOf("a", "b"))
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            val res =
                client.post("/admin/feature-flags/wordlists/profanity") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("entries" to "a\nb", "reason" to "no change", "etag" to fake.etag))
                }
            res.bodyAsText() shouldContain "No change"
            fake.publishedKeys.shouldBeEmpty()
            wordlistAuditRows(admin.id).shouldBeEmpty()
        }
    }

    "a post-normalization no-op (looks changed, normalizes back) is rejected" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val fake = FakeWordlistPublisher(profanity = listOf("anjing"))
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            val res =
                client.post("/admin/feature-flags/wordlists/profanity") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    // "Anjing" normalizes to the already-present "anjing" → no-op
                    setBody(formBody("entries" to "Anjing", "reason" to "case shuffle", "etag" to fake.etag))
                }
            res.bodyAsText() shouldContain "No change"
            fake.publishedKeys.shouldBeEmpty()
            wordlistAuditRows(admin.id).shouldBeEmpty()
        }
    }

    // ============================ 7.4 guards ==============================

    "an empty-list publish is rejected (BadRequest, no publish, no audit)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val fake = FakeWordlistPublisher(profanity = listOf("a", "b"))
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            val res =
                client.post("/admin/feature-flags/wordlists/profanity") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("entries" to "   \n  ", "reason" to "empty it", "etag" to fake.etag))
                }
            res.status shouldBe HttpStatusCode.BadRequest
            res.bodyAsText() shouldContain "cannot be emptied"
            fake.publishedKeys.shouldBeEmpty()
            wordlistAuditRows(admin.id).shouldBeEmpty()
        }
    }

    "a publish with no CSRF token is 403 with no publish" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val fake = FakeWordlistPublisher(profanity = listOf("a"))
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            val res =
                client.post("/admin/feature-flags/wordlists/profanity") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("entries" to "a\nb", "reason" to "x", "etag" to fake.etag))
                }
            res.status shouldBe HttpStatusCode.Forbidden
            fake.publishedKeys.shouldBeEmpty()
        }
    }

    "a token-less write from a moderator is CSRF-rejected (before the role check)" {
        val admin = seedAdmin(role = "moderator")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val fake = FakeWordlistPublisher(profanity = listOf("a"))
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            val res =
                client.post("/admin/feature-flags/wordlists/profanity") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, "wrong-token")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("entries" to "a\nb", "reason" to "x", "etag" to fake.etag))
                }
            res.status shouldBe HttpStatusCode.Forbidden
            fake.publishedKeys.shouldBeEmpty()
            // CSRF runs before the role check → the violation is recorded even for a moderator
            AdminAuthTestSupport.latestAuditRows(dataSource, admin.id).map { it.actionType } shouldContain "admin_csrf_violation"
        }
    }

    "a moderator publish (valid CSRF) is 403 with no publish and no audit row" {
        val admin = seedAdmin(role = "moderator")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val fake = FakeWordlistPublisher(profanity = listOf("a"))
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            val res =
                client.post("/admin/feature-flags/wordlists/profanity") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("entries" to "a\nb", "reason" to "x", "etag" to fake.etag))
                }
            res.status shouldBe HttpStatusCode.Forbidden
            fake.publishedKeys.shouldBeEmpty()
            wordlistAuditRows(admin.id).shouldBeEmpty()
        }
    }

    "a stale render etag is rejected without clobber or audit" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val fake = FakeWordlistPublisher(profanity = listOf("a", "b")) // current etag = "etag-1"
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            val res =
                client.post("/admin/feature-flags/wordlists/profanity") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("entries" to "a\nb\nc", "reason" to "stale", "etag" to "etag-OLD"))
                }
            res.bodyAsText() shouldContain "Reload"
            RemoteConfigStringList.decode(fake["moderation_profanity_list"])!! shouldBe listOf("a", "b") // not clobbered
            wordlistAuditRows(admin.id).shouldBeEmpty()
        }
    }

    "write-unconfigured renders read-only and a write fails safely (no 500, no audit)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val fake = FakeWordlistPublisher(configured = false)
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            val getBody = client.get("/admin/feature-flags/wordlists/profanity") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            getBody shouldContain "Writes unavailable"
            getBody shouldNotContain "name=\"entries\""

            val res =
                client.post("/admin/feature-flags/wordlists/profanity") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("entries" to "a\nb", "reason" to "x", "etag" to "etag-1"))
                }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "Writes unavailable"
            wordlistAuditRows(admin.id).shouldBeEmpty()
        }
    }

    // ============================ 7.5 rate limit ==============================

    "the eleventh wordlist publish within an hour is rejected (no publish, no new audit row)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedAuditRows(admin.id, "moderation_wordlist_edited", 10)
        val fake = FakeWordlistPublisher(profanity = listOf("a", "b"))
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            val res =
                client.post("/admin/feature-flags/wordlists/profanity") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("entries" to "a\nb\nc", "reason" to "eleventh", "etag" to fake.etag))
                }
            res.bodyAsText() shouldContain "quota exceeded"
            fake.publishedKeys.shouldBeEmpty()
            wordlistAuditRows(admin.id) shouldHaveSize 10 // the 10 seeded, no new row
        }
    }

    "the tenth wordlist publish within an hour succeeds" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedAuditRows(admin.id, "moderation_wordlist_edited", 9)
        val fake = FakeWordlistPublisher(profanity = listOf("a", "b"))
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            client.post("/admin/feature-flags/wordlists/profanity") {
                header(HttpHeaders.Cookie, cookie(token))
                header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(formBody("entries" to "a\nb\nc", "reason" to "tenth", "etag" to fake.etag))
            }
            fake.publishedKeys shouldBe listOf("moderation_profanity_list")
            wordlistAuditRows(admin.id) shouldHaveSize 10 // 9 seeded + the applied 10th
        }
    }

    "the wordlist bucket is independent of the feature-flag-toggle bucket" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        // exhausting the 5/hour feature-flag bucket must NOT consume the wordlist budget
        seedAuditRows(admin.id, "feature_flag_toggled", 10)
        val fake = FakeWordlistPublisher(profanity = listOf("a", "b"))
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            client.post("/admin/feature-flags/wordlists/profanity") {
                header(HttpHeaders.Cookie, cookie(token))
                header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(formBody("entries" to "a\nb\nc", "reason" to "independent", "etag" to fake.etag))
            }
            fake.publishedKeys shouldBe listOf("moderation_profanity_list")
        }
    }

    // ============================ 7.7 scope boundary ==============================

    "a publish targets only the named Server-template parameter" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val fake = FakeWordlistPublisher(profanity = listOf("a"), uuIte = listOf("x"))
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            client.post("/admin/feature-flags/wordlists/profanity") {
                header(HttpHeaders.Cookie, cookie(token))
                header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(formBody("entries" to "a\nb", "reason" to "scope", "etag" to fake.etag))
            }
            // ONLY the profanity param is published; the uu_ite list is untouched
            fake.publishedKeys shouldBe listOf("moderation_profanity_list")
            RemoteConfigStringList.decode(fake["moderation_uu_ite_list"])!! shouldBe listOf("x")
        }
    }

    // ============================ 7.6 admin-feature-flags MODIFIED regression ==============================

    "the feature-flags page renders an edit affordance linking to each wordlist editor" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = FakeWordlistPublisher()) { client ->
            val body = client.get("/admin/feature-flags") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "/admin/feature-flags/wordlists/profanity"
            body shouldContain "/admin/feature-flags/wordlists/uu_ite"
            // match-threshold stays inline-editable on the feature-flags surface
            body shouldContain "moderation_match_threshold"
        }
    }
})

/**
 * In-memory [RemoteConfigPublisher] for the wordlist route tests. Stores the two
 * list params as JSON-array strings; `publishServerStringList` (the extension the
 * service calls) routes through [publishServerParameter], so `publishedKeys`
 * records exactly which Server-template params were written (the scope-boundary
 * spy point), and the value map mutates on success so no-op / before-state paths
 * are exercised end-to-end.
 */
private class FakeWordlistPublisher(
    private val configured: Boolean = true,
    profanity: List<String> = listOf("a", "b"),
    uuIte: List<String> = listOf("x"),
    var etag: String = "etag-1",
    private val version: String? = "1",
) : RemoteConfigPublisher {
    private val values: MutableMap<String, String?> =
        mutableMapOf(
            "moderation_profanity_list" to RemoteConfigStringList.encode(profanity),
            "moderation_uu_ite_list" to RemoteConfigStringList.encode(uuIte),
        )
    val publishedKeys = mutableListOf<String>()

    operator fun get(key: String): String? = values[key]

    override fun isConfigured(): Boolean = configured

    override fun fetchServerTemplate(parameterNames: Set<String>): ServerTemplateSnapshot? {
        if (!configured) return null
        return ServerTemplateSnapshot(etag = etag, version = version, parameters = parameterNames.associateWith { values[it] })
    }

    override fun publishServerParameter(
        parameterName: String,
        rawValue: String,
        expectedEtag: String,
    ): PublishResult {
        if (!configured) return PublishResult.WriteUnavailable
        if (expectedEtag != etag) return PublishResult.StaleVersion
        values[parameterName] = rawValue
        etag = "$etag+"
        publishedKeys += parameterName
        return PublishResult.Published(etag)
    }
}
