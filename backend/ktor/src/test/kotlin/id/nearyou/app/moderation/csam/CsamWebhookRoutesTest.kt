package id.nearyou.app.moderation.csam

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkException
import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.auth.AdminAuthProvider
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import id.nearyou.app.admin.auth.AdminUserRepository
import id.nearyou.app.admin.auth.SessionRepository
import id.nearyou.app.config.SecretResolver
import id.nearyou.app.core.domain.oidc.OidcTokenVerifier
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import id.nearyou.app.infra.oidc.GoogleOidcTokenVerifier
import id.nearyou.app.infra.repo.JdbcModerationQueueRepository
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Date as UtilDate

/**
 * Auth + protocol coverage for the CSAM webhook routes (`csam-detection`) over a
 * minimal testApplication. Mounts the real [Application.csamWebhookRoutes] (sibling
 * `routing {}` block at `/internal/csam-webhook`) and the OIDC-gated
 * [Route.csamArchivePurgeRoute], over the test DataSource and controllable fakes.
 *
 * Scenarios:
 *  - 7.8  malformed payload (missing image_hash) with valid CF auth → 400, no mutation.
 *  - 7.12 auth matrix:
 *      (a) bare valid OIDC token on the webhook → NOT admitted (no cookie, no Bearer-secret).
 *      (b) CF path: valid Bearer + valid HMAC → 200; valid Bearer + missing/invalid HMAC → 401.
 *      (c) CF path rate-limited → 429.
 *      (d) admin path: owner session + CSRF → 200; read_only role → 403; cross-session
 *          CSRF replay → 403.
 *      (purge) missing OIDC → 401; valid OIDC → 200.
 *
 * Security-critical: the auth assertions are exact status codes; no over-broad matchers.
 */
@Tags("database")
class CsamWebhookRoutesTest : StringSpec({

    val cfSecret = "test-cf-worker-csam-secret-value"
    val webhookPath = "/internal/csam-webhook"
    val purgePath = "/internal/csam-archive-purge"

    // afterSpec cleanup present → do NOT autoClose; close in afterSpec.
    val dataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
                username = System.getenv("DB_USER") ?: "postgres"
                password = System.getenv("DB_PASSWORD") ?: "postgres"
                maximumPoolSize = 3
                initializationFailTimeout = -1
            },
        )

    val runTag = "csamhook_${UUID.randomUUID().toString().take(8)}"
    val seededUserIds = mutableListOf<UUID>()
    val seededAdminIds = mutableListOf<UUID>()
    val seededImageHashes = mutableListOf<String>()

    // ---- OIDC test rig (mirrors UnbanWorkerRouteTest) ----
    val (oidcPub, oidcPriv) = rsaKeypair()
    val oidcAudience = "https://api-staging.nearyou.id"
    val oidcKid = "csam-purge-kid"
    val oidcVerifier: OidcTokenVerifier =
        GoogleOidcTokenVerifier(
            audience = oidcAudience,
            jwkProvider = StaticJwkProvider(mapOf(oidcKid to FakeJwk(oidcKid, oidcPub))),
        )

    fun oidcToken(
        audience: String = oidcAudience,
        kid: String = oidcKid,
    ): String =
        JWT.create()
            .withKeyId(kid)
            .withSubject("scheduler@nearyou-staging.iam.gserviceaccount.com")
            .withAudience(audience)
            .withIssuedAt(UtilDate.from(Instant.now()))
            .withExpiresAt(UtilDate.from(Instant.now().plus(1, ChronoUnit.HOURS)))
            .sign(Algorithm.RSA256(oidcPub, oidcPriv))

    // ---- fakes ----

    /** SecretResolver returning the known CF-Worker secret. */
    val secrets =
        object : SecretResolver {
            override fun resolve(name: String): String? = if (name == "cf-worker-csam-secret") cfSecret else null
        }

    /** Always-allow rate limiter (Redis not needed for the happy/auth paths). */
    val allowRateLimiter =
        object : RateLimiter {
            override fun tryAcquire(
                userId: UUID,
                key: String,
                capacity: Int,
                ttl: Duration,
            ): RateLimiter.Outcome = RateLimiter.Outcome.Allowed(capacity)

            override fun tryAcquireByKey(
                key: String,
                capacity: Int,
                ttl: Duration,
            ): RateLimiter.Outcome = RateLimiter.Outcome.Allowed(capacity)

            override fun releaseMostRecent(
                userId: UUID,
                key: String,
            ) = Unit
        }

    /** Rate limiter that always reports RateLimited (for the 429 assertion). */
    val deniedRateLimiter =
        object : RateLimiter {
            override fun tryAcquire(
                userId: UUID,
                key: String,
                capacity: Int,
                ttl: Duration,
            ): RateLimiter.Outcome = RateLimiter.Outcome.RateLimited(42)

            override fun tryAcquireByKey(
                key: String,
                capacity: Int,
                ttl: Duration,
            ): RateLimiter.Outcome = RateLimiter.Outcome.RateLimited(42)

            override fun releaseMostRecent(
                userId: UUID,
                key: String,
            ) = Unit
        }

    fun service() =
        CsamDetectionService(
            dataSource = dataSource,
            dbDispatcher = kotlinx.coroutines.Dispatchers.IO,
            csamRepository = CsamRepository(dataSource),
            moderationQueueRepository = JdbcModerationQueueRepository(),
            // fail-soft; encryption is exercised in CsamDetectionServiceTest, not here.
            encryptor = CsamMetadataEncryptor { null },
            auditLogger = AdminAuditLogger(dataSource),
        )

    /**
     * Boot a testApplication with both CSAM routes wired over the test DB, the given
     * rate limiter, the known-secret resolver, and the OIDC verifier for the purge
     * route. A test-only plugin pre-seeds the ClientIp attribute to a valid INET so
     * IpHasher (non-blank) and the audit `?::inet` cast both succeed.
     */
    suspend fun withApp(
        rateLimiter: RateLimiter = allowRateLimiter,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) {
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(id.nearyou.app.common.AppJson)
                }
                install(testClientIpPlugin())
                csamWebhookRoutes(
                    service = service(),
                    sessionRepository = SessionRepository(dataSource),
                    adminUserRepository = AdminUserRepository(dataSource),
                    rateLimiter = rateLimiter,
                    secrets = secrets,
                    env = "test",
                )
                routing {
                    route("/internal") {
                        csamArchivePurgeRoute(service(), oidcVerifier)
                    }
                }
            }
            block()
        }
    }

    fun hmacHex(
        secret: String,
        body: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun seedUser(): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "ch_$short")
                ps.setString(3, "CSAM Hook $short")
                ps.setObject(4, LocalDate.of(1990, 1, 1))
                ps.setString(5, "CH${short.take(5).uppercase()}")
                ps.executeUpdate()
            }
        }
        seededUserIds += id
        return id
    }

    fun seedImageUpload(
        cfImageId: String,
        uploaderId: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO image_uploads (cf_image_id, uploader_user_id, status) VALUES (?, ?, 'attached')",
            ).use { ps ->
                ps.setString(1, cfImageId)
                ps.setObject(2, uploaderId)
                ps.executeUpdate()
            }
        }
    }

    fun archiveCount(imageHash: String): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT count(*) FROM csam_detection_archive WHERE image_hash = ?").use { ps ->
                ps.setString(1, imageHash)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    /** Build a valid CF webhook JSON body for a fresh ledger-backed image. */
    fun freshBody(): Pair<String, String> {
        val uploader = seedUser()
        val cfImageId = "$runTag-img-${UUID.randomUUID().toString().take(6)}"
        seedImageUpload(cfImageId, uploader)
        val hash = "$runTag-hash-${UUID.randomUUID().toString().take(6)}"
        seededImageHashes += hash
        val body = """{"image_id":"$cfImageId","image_hash":"$hash"}"""
        return body to hash
    }

    afterSpec {
        dataSource.connection.use { conn ->
            if (seededUserIds.isNotEmpty()) {
                conn.prepareStatement(
                    "DELETE FROM admin_actions_log WHERE action_type = 'csam_takedown' AND target_id = ANY(?)",
                ).use { ps ->
                    ps.setArray(1, conn.createArrayOf("text", seededUserIds.map { it.toString() }.toTypedArray()))
                    ps.executeUpdate()
                }
                conn.prepareStatement(
                    "DELETE FROM moderation_queue WHERE target_id IN (SELECT id FROM posts WHERE author_id = ANY(?))",
                ).use { ps ->
                    ps.setArray(1, conn.createArrayOf("uuid", seededUserIds.toTypedArray()))
                    ps.executeUpdate()
                }
            }
            if (seededImageHashes.isNotEmpty()) {
                conn.prepareStatement("DELETE FROM csam_detection_archive WHERE image_hash = ANY(?)").use { ps ->
                    ps.setArray(1, conn.createArrayOf("text", seededImageHashes.toTypedArray()))
                    ps.executeUpdate()
                }
            }
            if (seededUserIds.isNotEmpty()) {
                conn.prepareStatement("DELETE FROM image_uploads WHERE uploader_user_id = ANY(?)").use { ps ->
                    ps.setArray(1, conn.createArrayOf("uuid", seededUserIds.toTypedArray()))
                    ps.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM users WHERE id = ANY(?)").use { ps ->
                    ps.setArray(1, conn.createArrayOf("uuid", seededUserIds.toTypedArray()))
                    ps.executeUpdate()
                }
            }
        }
        seededAdminIds.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        dataSource.close()
    }

    // ---------- 7.12 (b) CF-Worker auth path ----------

    "7.12b CF path — valid Bearer + valid HMAC → 200" {
        val (body, _) = freshBody()
        withApp {
            val resp =
                client.post(webhookPath) {
                    header(HttpHeaders.Authorization, "Bearer $cfSecret")
                    header("X-CSAM-Signature", hmacHex(cfSecret, body))
                    setBody(body)
                }
            resp.status shouldBe HttpStatusCode.OK
        }
    }

    "7.12b CF path — valid Bearer but MISSING HMAC signature → 401" {
        val (body, hash) = freshBody()
        withApp {
            val resp =
                client.post(webhookPath) {
                    header(HttpHeaders.Authorization, "Bearer $cfSecret")
                    setBody(body)
                }
            resp.status shouldBe HttpStatusCode.Unauthorized
        }
        archiveCount(hash) shouldBe 0 // no mutation on a rejected request
    }

    "7.12b CF path — valid Bearer but INVALID HMAC signature → 401" {
        val (body, hash) = freshBody()
        withApp {
            val resp =
                client.post(webhookPath) {
                    header(HttpHeaders.Authorization, "Bearer $cfSecret")
                    header("X-CSAM-Signature", hmacHex("the-wrong-secret", body))
                    setBody(body)
                }
            resp.status shouldBe HttpStatusCode.Unauthorized
        }
        archiveCount(hash) shouldBe 0
    }

    "7.12b CF path — WRONG Bearer secret → 401" {
        val (body, hash) = freshBody()
        withApp {
            val resp =
                client.post(webhookPath) {
                    header(HttpHeaders.Authorization, "Bearer the-wrong-bearer")
                    header("X-CSAM-Signature", hmacHex("the-wrong-bearer", body))
                    setBody(body)
                }
            resp.status shouldBe HttpStatusCode.Unauthorized
        }
        archiveCount(hash) shouldBe 0
    }

    // ---------- 7.12 (c) rate limit ----------

    "7.12c CF path — rate-limited → 429 with Retry-After" {
        val (body, hash) = freshBody()
        withApp(rateLimiter = deniedRateLimiter) {
            val resp =
                client.post(webhookPath) {
                    header(HttpHeaders.Authorization, "Bearer $cfSecret")
                    header("X-CSAM-Signature", hmacHex(cfSecret, body))
                    setBody(body)
                }
            resp.status shouldBe HttpStatusCode.TooManyRequests
            resp.headers[HttpHeaders.RetryAfter] shouldBe "42"
        }
        archiveCount(hash) shouldBe 0
    }

    // ---------- 7.8 malformed payload ----------

    "7.8 malformed payload — missing image_hash with valid CF auth → 400, no mutation" {
        val uploader = seedUser()
        val cfImageId = "$runTag-img-malformed"
        seedImageUpload(cfImageId, uploader)
        // Body omits image_hash entirely.
        val body = """{"image_id":"$cfImageId"}"""
        withApp {
            val resp =
                client.post(webhookPath) {
                    header(HttpHeaders.Authorization, "Bearer $cfSecret")
                    header("X-CSAM-Signature", hmacHex(cfSecret, body))
                    setBody(body)
                }
            resp.status shouldBe HttpStatusCode.BadRequest
        }
        // No archive row for this uploader's image (no takedown happened).
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT is_banned FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, uploader)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getBoolean("is_banned") shouldBe false
                }
            }
        }
    }

    // ---------- 7.12 (a) bare OIDC token NOT admitted on the webhook ----------

    "7.12a bare valid OIDC token (no cookie, no Bearer-secret) → NOT authorized on the webhook" {
        // The webhook's OWN auth only admits the admin cookie OR the CF Bearer secret.
        // A Google-OIDC bearer matches the Bearer branch but is NOT the configured
        // cf-worker secret → 401 (the OIDC plugin never runs on this route).
        val (body, hash) = freshBody()
        withApp {
            val resp =
                client.post(webhookPath) {
                    header(HttpHeaders.Authorization, "Bearer ${oidcToken()}")
                    setBody(body)
                }
            resp.status shouldBe HttpStatusCode.Unauthorized
        }
        archiveCount(hash) shouldBe 0
    }

    // ---------- 7.12 (d) admin-internal path ----------

    "7.12d admin path — owner session + matching CSRF → 200" {
        val admin = AdminAuthTestSupport.seedAdmin(dataSource, role = "owner")
        seededAdminIds += admin.id
        val sessionToken = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val csrf = AdminAuthTestSupport.csrfFor(sessionToken)
        val (body, _) = freshBody()

        withApp {
            val resp =
                client.post(webhookPath) {
                    header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$sessionToken")
                    header("X-CSRF-Token", csrf)
                    setBody(body)
                }
            resp.status shouldBe HttpStatusCode.OK
        }
    }

    "7.12d admin path — admin role + matching CSRF → 200 (owner/admin tier)" {
        val admin = AdminAuthTestSupport.seedAdmin(dataSource, role = "admin")
        seededAdminIds += admin.id
        val sessionToken = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val csrf = AdminAuthTestSupport.csrfFor(sessionToken)
        val (body, _) = freshBody()

        withApp {
            val resp =
                client.post(webhookPath) {
                    header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$sessionToken")
                    header("X-CSRF-Token", csrf)
                    setBody(body)
                }
            resp.status shouldBe HttpStatusCode.OK
        }
    }

    "7.12d admin path — read_only role + valid session + CSRF → 403" {
        val admin = AdminAuthTestSupport.seedAdmin(dataSource, role = "read_only")
        seededAdminIds += admin.id
        val sessionToken = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val csrf = AdminAuthTestSupport.csrfFor(sessionToken)
        val (body, hash) = freshBody()

        withApp {
            val resp =
                client.post(webhookPath) {
                    header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$sessionToken")
                    header("X-CSRF-Token", csrf)
                    setBody(body)
                }
            resp.status shouldBe HttpStatusCode.Forbidden
        }
        archiveCount(hash) shouldBe 0
    }

    "7.12d admin path — CSRF token from a DIFFERENT session (replay) → 403" {
        val admin = AdminAuthTestSupport.seedAdmin(dataSource, role = "owner")
        seededAdminIds += admin.id
        // Two sessions for the same admin; present session-2's cookie with session-1's CSRF.
        val session1 = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val session2 = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val csrf1 = AdminAuthTestSupport.csrfFor(session1)
        val (body, hash) = freshBody()

        withApp {
            val resp =
                client.post(webhookPath) {
                    header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$session2")
                    header("X-CSRF-Token", csrf1) // CSRF bound to session1, presented with session2's cookie
                    setBody(body)
                }
            resp.status shouldBe HttpStatusCode.Forbidden
        }
        archiveCount(hash) shouldBe 0
    }

    "7.12d admin path — owner session but MISSING CSRF header → 403" {
        val admin = AdminAuthTestSupport.seedAdmin(dataSource, role = "owner")
        seededAdminIds += admin.id
        val sessionToken = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val (body, hash) = freshBody()

        withApp {
            val resp =
                client.post(webhookPath) {
                    header(HttpHeaders.Cookie, "${AdminAuthProvider.COOKIE_NAME}=$sessionToken")
                    setBody(body)
                }
            resp.status shouldBe HttpStatusCode.Forbidden
        }
        archiveCount(hash) shouldBe 0
    }

    // ---------- purge route OIDC gate ----------

    "purge route — missing OIDC token → 401" {
        withApp {
            val resp = client.post(purgePath)
            resp.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    "purge route — valid OIDC token → 200 with purged_count" {
        withApp {
            val resp =
                client.post(purgePath) {
                    header(HttpHeaders.Authorization, "Bearer ${oidcToken()}")
                }
            resp.status shouldBe HttpStatusCode.OK
            resp.bodyAsText().contains("purged_count") shouldBe true
        }
    }

    "purge route — OIDC token with wrong audience → 401" {
        withApp {
            val resp =
                client.post(purgePath) {
                    header(HttpHeaders.Authorization, "Bearer ${oidcToken(audience = "https://example.com/other")}")
                }
            resp.status shouldBe HttpStatusCode.Unauthorized
        }
    }
})

// ---- top-level OIDC test helpers (mirror UnbanWorkerRouteTest) ----

private fun rsaKeypair(): Pair<RSAPublicKey, RSAPrivateKey> {
    val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
    val kp = gen.generateKeyPair()
    return kp.public as RSAPublicKey to kp.private as RSAPrivateKey
}

private class FakeJwk(keyId: String, private val pubKey: RSAPublicKey) :
    Jwk(keyId, "RSA", "RS256", null, emptyList(), null, null, null, null) {
    override fun getPublicKey(): java.security.PublicKey = pubKey
}

private class StaticJwkProvider(private val mapping: Map<String, Jwk>) : JwkProvider {
    override fun get(keyId: String): Jwk = mapping[keyId] ?: throw JwkException("kid not found: $keyId")
}

/**
 * Pre-seed the ClientIp request attribute to a valid INET so both [IpHasher] (requires
 * non-blank) and the audit `?::inet` cast succeed under testApplication (whose
 * `remoteHost` is the non-INET "localhost"). Mirrors AdminAuthTestSupport's private
 * `installTestClientIpDefault`.
 */
private fun testClientIpPlugin() =
    createApplicationPlugin("CsamTestClientIp") {
        onCall { call ->
            if (!call.attributes.contains(id.nearyou.app.common.ClientIpAttributeKey)) {
                call.attributes.put(id.nearyou.app.common.ClientIpAttributeKey, "203.0.113.9")
            }
        }
    }
