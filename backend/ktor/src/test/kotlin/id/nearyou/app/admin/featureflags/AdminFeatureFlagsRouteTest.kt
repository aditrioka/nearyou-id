package id.nearyou.app.admin.featureflags

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuthProvider
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import id.nearyou.app.admin.auth.AdminCsrfGate
import id.nearyou.app.infra.remoteconfig.PublishResult
import id.nearyou.app.infra.remoteconfig.RemoteConfigPublisher
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
 * Route-level integration tests for `adminFeatureFlags` (`admin-feature-flags`
 * capability, mockup frame 20): render (R1/R2), the audited write (R3), the CSRF
 * (R4) / owner-admin role (R5) / 5-per-hour rate-limit (R6) gates, optimistic
 * concurrency (R8/now-R9), graceful read-only degradation (R10), and the
 * deferred-wordlist guard (R11). The pure value-validation matrix (R7 + the
 * value-validation requirement) is covered by [FeatureFlagCatalogTest].
 *
 * DB-backed; tagged `database`. Uses [AdminAuthTestSupport] for authenticated
 * sessions + a [FakeRemoteConfigPublisher] so writes are exercised without a real
 * Firebase project.
 */
@Tags("database")
class AdminFeatureFlagsRouteTest : StringSpec({

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

    /** Pre-insert [n] audit rows of [actionType] for [adminId] (rate-limit fixtures). */
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
                    VALUES (?, ?, 'feature_flag', 'search_enabled', '127.0.0.1'::inet)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, adminId)
                    ps.setString(2, actionType)
                    ps.executeUpdate()
                }
            }
        }
    }

    fun featureFlagAuditRows(adminId: UUID) =
        AdminAuthTestSupport.latestAuditRows(dataSource, adminId).filter { it.actionType == "feature_flag_toggled" }

    // ============================ R1/R2 render ==============================

    "authenticated GET renders the catalog with typed controls + env chip" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = FakeRemoteConfigPublisher()) { client ->
            val res = client.get("/admin/feature-flags") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "Feature Flag Admin"
            body shouldContain "search_enabled"
            // attestation_mode rendered as a 3-valued enum select (not a boolean toggle)
            body shouldContain "<select name=\"value\""
            body shouldContain "enforce"
            body shouldContain "off"
            // moderation_match_threshold rendered as an integer input
            body shouldContain "type=\"number\""
            // env chip + full-page shell
            body shouldContain ">TEST<"
            body shouldContain "<header>"
            // GET is read-only — no audit row written
            AdminAuthTestSupport.countAuditRows(dataSource, admin.id) shouldBe 0
        }
    }

    "rendered Remote Config values are HTML-escaped" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val fake = FakeRemoteConfigPublisher(values = mutableMapOf("attestation_mode" to "<script>alert(1)</script>"))
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            val body = client.get("/admin/feature-flags") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "&lt;script&gt;"
            body shouldNotContain "<script>alert(1)"
        }
    }

    "unauthenticated GET redirects (302) to /admin/login" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/feature-flags")
            res.status shouldBe HttpStatusCode.Found
            res.headers[HttpHeaders.Location] shouldBe "/admin/login"
        }
    }

    "HX-Request returns only the content fragment (no full-page wrapper)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = FakeRemoteConfigPublisher()) { client ->
            val res =
                client.get("/admin/feature-flags") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("HX-Request", "true")
                }
            val body = res.bodyAsText()
            body shouldContain "id=\"ff-content\""
            body shouldNotContain "<header>"
            body shouldNotContain "<html"
        }
    }

    // ============================ R3 audited write ==============================

    "an owner write publishes to the Server template and writes exactly one audit row" {
        val admin = seedAdmin(role = "owner")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val fake = FakeRemoteConfigPublisher()
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            val res =
                client.post("/admin/feature-flags/search_enabled") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("value" to "true", "reason" to "enable search for launch", "etag" to fake.etag))
                }
            res.status shouldBe HttpStatusCode.OK
            fake.publishedKeys shouldBe listOf("search_enabled")
            fake["search_enabled"] shouldBe "true"
            val rows = featureFlagAuditRows(admin.id)
            rows shouldHaveSize 1
            rows[0].targetType shouldBe "feature_flag"
            rows[0].reason shouldBe "enable search for launch"
            rows[0].beforeState!! shouldContain "false"
            rows[0].afterState!! shouldContain "true"
        }
    }

    "an admin (not just owner) write is permitted" {
        val admin = seedAdmin(role = "admin")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val fake = FakeRemoteConfigPublisher()
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            client.post("/admin/feature-flags/attestation_mode") {
                header(HttpHeaders.Cookie, cookie(token))
                header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(formBody("value" to "enforce", "reason" to "tighten attestation", "etag" to fake.etag))
            }
            fake["attestation_mode"] shouldBe "enforce"
            featureFlagAuditRows(admin.id) shouldHaveSize 1
        }
    }

    "a blank reason is rejected with no publish and no audit row" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val fake = FakeRemoteConfigPublisher()
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            client.post("/admin/feature-flags/search_enabled") {
                header(HttpHeaders.Cookie, cookie(token))
                header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(formBody("value" to "true", "reason" to "   ", "etag" to fake.etag))
            }
            fake.publishedKeys.shouldBeEmpty()
            featureFlagAuditRows(admin.id).shouldBeEmpty()
        }
    }

    "a no-op write (value unchanged) is rejected with no publish and no audit row" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        // search_enabled current value is "false" in the fake's defaults
        val fake = FakeRemoteConfigPublisher()
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            val res =
                client.post("/admin/feature-flags/search_enabled") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("value" to "false", "reason" to "no change", "etag" to fake.etag))
                }
            res.bodyAsText() shouldContain "No change"
            fake.publishedKeys.shouldBeEmpty()
            featureFlagAuditRows(admin.id).shouldBeEmpty()
        }
    }

    // ============================ R4 CSRF ==============================

    "a write with no CSRF token is 403 with no publish" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val fake = FakeRemoteConfigPublisher()
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            val res =
                client.post("/admin/feature-flags/search_enabled") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("value" to "true", "reason" to "x", "etag" to fake.etag))
                }
            res.status shouldBe HttpStatusCode.Forbidden
            fake.publishedKeys.shouldBeEmpty()
        }
    }

    "a write with a mismatched CSRF token is 403 + admin_csrf_violation audit, no feature-flag publish" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val fake = FakeRemoteConfigPublisher()
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            val res =
                client.post("/admin/feature-flags/search_enabled") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, "wrong-token")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("value" to "true", "reason" to "x", "etag" to fake.etag))
                }
            res.status shouldBe HttpStatusCode.Forbidden
            fake.publishedKeys.shouldBeEmpty()
            val rows = AdminAuthTestSupport.latestAuditRows(dataSource, admin.id)
            rows.map { it.actionType } shouldContain "admin_csrf_violation"
            rows.none { it.actionType == "feature_flag_toggled" } shouldBe true
        }
    }

    // ============================ R5 role ==============================

    "a moderator write is rejected (403) with no publish and no feature-flag audit row" {
        val admin = seedAdmin(role = "moderator")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val fake = FakeRemoteConfigPublisher()
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            val res =
                client.post("/admin/feature-flags/search_enabled") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("value" to "true", "reason" to "x", "etag" to fake.etag))
                }
            res.status shouldBe HttpStatusCode.Forbidden
            fake.publishedKeys.shouldBeEmpty()
            featureFlagAuditRows(admin.id).shouldBeEmpty()
        }
    }

    // ============================ R6 rate limit ==============================

    "the sixth flag write within an hour is rejected (no publish, no new audit row)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedAuditRows(admin.id, "feature_flag_toggled", 5)
        val fake = FakeRemoteConfigPublisher()
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            val res =
                client.post("/admin/feature-flags/search_enabled") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("value" to "true", "reason" to "sixth", "etag" to fake.etag))
                }
            res.bodyAsText() shouldContain "quota exceeded"
            fake.publishedKeys.shouldBeEmpty()
            featureFlagAuditRows(admin.id) shouldHaveSize 5 // the 5 seeded, no new row
        }
    }

    "the fifth flag write within an hour succeeds" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedAuditRows(admin.id, "feature_flag_toggled", 4)
        val fake = FakeRemoteConfigPublisher()
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            client.post("/admin/feature-flags/search_enabled") {
                header(HttpHeaders.Cookie, cookie(token))
                header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(formBody("value" to "true", "reason" to "fifth", "etag" to fake.etag))
            }
            fake.publishedKeys shouldBe listOf("search_enabled")
            featureFlagAuditRows(admin.id) shouldHaveSize 5 // 4 seeded + the applied 5th
        }
    }

    "the feature-flag bucket is independent of the destructive-action cap" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        // 5 destructive actions must NOT consume the feature-flag budget
        seedAuditRows(admin.id, "user_suspended", 5)
        val fake = FakeRemoteConfigPublisher()
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            client.post("/admin/feature-flags/search_enabled") {
                header(HttpHeaders.Cookie, cookie(token))
                header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(formBody("value" to "true", "reason" to "independent", "etag" to fake.etag))
            }
            fake.publishedKeys shouldBe listOf("search_enabled")
        }
    }

    // ============================ R7 validation (route spot-check) ==============================

    "an out-of-range threshold is rejected at the route with no publish or audit" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val fake = FakeRemoteConfigPublisher()
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            val res =
                client.post("/admin/feature-flags/moderation_match_threshold") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("value" to "0", "reason" to "too low", "etag" to fake.etag))
                }
            res.status shouldBe HttpStatusCode.BadRequest
            fake.publishedKeys.shouldBeEmpty()
            featureFlagAuditRows(admin.id).shouldBeEmpty()
        }
    }

    // ============================ R9 concurrency ==============================

    "a stale render ETag is rejected without clobber or audit" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val fake = FakeRemoteConfigPublisher() // current etag = "etag-1"
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            val res =
                client.post("/admin/feature-flags/search_enabled") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("value" to "true", "reason" to "stale", "etag" to "etag-OLD"))
                }
            res.bodyAsText() shouldContain "Reload"
            fake["search_enabled"] shouldBe "false" // not clobbered
            featureFlagAuditRows(admin.id).shouldBeEmpty()
        }
    }

    // ============================ R10 graceful degradation ==============================

    "write-unconfigured renders read-only with a notice and no write forms" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val fake = FakeRemoteConfigPublisher(configured = false)
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            val body = client.get("/admin/feature-flags") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "Writes unavailable"
            body shouldNotContain "action=\"/admin/feature-flags/search_enabled\""
        }
    }

    "a write attempted while write-unconfigured fails safely (no 500, no audit)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val fake = FakeRemoteConfigPublisher(configured = false)
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            val res =
                client.post("/admin/feature-flags/search_enabled") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("value" to "true", "reason" to "x", "etag" to "etag-1"))
                }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "Writes unavailable"
            featureFlagAuditRows(admin.id).shouldBeEmpty()
        }
    }

    // ============================ R11 deferred-wordlist guard ==============================

    "there is no endpoint that mutates a moderation wordlist's content" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val fake = FakeRemoteConfigPublisher()
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            val res =
                client.post("/admin/feature-flags/moderation_profanity_list") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header(AdminCsrfGate.X_CSRF_TOKEN_HEADER, AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("value" to "[\"hacked\"]", "reason" to "x", "etag" to fake.etag))
                }
            res.status shouldBe HttpStatusCode.BadRequest
            fake.publishedKeys.shouldBeEmpty()
        }
    }

    "the two wordlists render read-only with a count summary and no edit form" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val fake =
            FakeRemoteConfigPublisher(
                values =
                    mutableMapOf(
                        "moderation_profanity_list" to "[\"a\",\"b\",\"c\"]",
                        "moderation_uu_ite_list" to "[\"x\"]",
                    ),
            )
        AdminAuthTestSupport.withAdminApp(dataSource, remoteConfigPublisher = fake) { client ->
            val body = client.get("/admin/feature-flags") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "moderation_profanity_list"
            body shouldContain "3 entries"
            body shouldContain "READ-ONLY"
            body shouldNotContain "action=\"/admin/feature-flags/moderation_profanity_list\""
        }
    }
})

/**
 * In-memory [RemoteConfigPublisher] for the route tests. Tracks published keys so
 * a test can assert no-publish on a rejection, and mutates its value map on a
 * successful publish so the no-op / before-state paths are exercised end-to-end.
 */
private class FakeRemoteConfigPublisher(
    private val configured: Boolean = true,
    private val values: MutableMap<String, String?> = defaultValues(),
    var etag: String = "etag-1",
    private val version: String? = "1",
) : RemoteConfigPublisher {
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

    companion object {
        fun defaultValues(): MutableMap<String, String?> =
            mutableMapOf(
                "image_upload_enabled" to "false",
                "attestation_mode" to "warn",
                "search_enabled" to "false",
                "perspective_api_enabled" to "false",
                "premium_username_customization_enabled" to "true",
                "premium_like_cap_override" to "false",
                "premium_image_upload_cap_override" to "50",
                "moderation_match_threshold" to "3",
                "moderation_profanity_list" to "[\"a\",\"b\"]",
                "moderation_uu_ite_list" to "[\"x\"]",
            )
    }
}
