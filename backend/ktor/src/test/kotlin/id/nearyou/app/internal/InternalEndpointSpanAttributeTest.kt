package id.nearyou.app.internal

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkException
import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import id.nearyou.app.auth.AUTH_PROVIDER_USER
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.core.domain.oidc.OidcTokenVerifier
import id.nearyou.app.core.domain.oidc.VerifiedClaims
import id.nearyou.app.infra.oidc.GoogleOidcTokenVerifier
import id.nearyou.app.infra.otel.ServiceAccountIdHasher
import id.nearyou.app.infra.otel.installKtorServerTelemetry
import id.nearyou.app.infra.otel.testing.FailingSpanProcessor
import id.nearyou.app.infra.otel.testing.SpanRecorder
import id.nearyou.app.infra.repo.IdentifierType
import id.nearyou.app.infra.repo.NewUserRow
import id.nearyou.app.infra.repo.UserRepository
import id.nearyou.app.infra.repo.UserRow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.samplers.Sampler
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import io.ktor.server.routing.post as routingPost
import java.util.Date as UtilDate

/**
 * Integration test for the `internal-endpoint-auth-otel-attributes` capability —
 * verifies that successful /internal requests produce server spans with the
 * sanctioned `service.account.id` attribute (hashed OIDC `sub` claim), that
 * raw `sub` values never appear on the span, that the writer's best-effort
 * silent-fail posture holds across three failure modes (SDK uninitialised,
 * helper throw on blank `sub`, FailingSpanProcessor), and that the
 * server-span-level mutual-exclusion contract with `user.id` is respected
 * in both directions (/internal never carries `user.id`; /api/v1 never
 * carries `service.account.id`).
 *
 * No database tag — uses a stub `/internal/unban-worker` handler that does
 * not need a DataSource. The mutual-exclusion converse scenario uses an
 * in-memory [FakeUserRepository] so the AuthPlugin's UserPrincipal path
 * resolves without Postgres.
 */
class InternalEndpointSpanAttributeTest : StringSpec({

    beforeEach {
        try {
            GlobalOpenTelemetry.resetForTest()
        } catch (_: Throwable) {
            // Tolerate older SDKs without resetForTest.
        }
    }

    val testAudience = "https://api-staging.nearyou.id"
    val testKid = "test-route-kid"
    val sentinelSub = "SENTINEL_SUB_LITERAL_VALUE_FOR_INTERNAL_AUTH_SPAN_TEST_42"

    val (pubKey, privKey) = rsaKeypair()
    val defaultVerifier: OidcTokenVerifier =
        GoogleOidcTokenVerifier(
            audience = testAudience,
            jwkProvider = StaticJwkProvider(mapOf(testKid to FakeJwk(testKid, pubKey))),
        )

    fun signedJwt(
        audience: String = testAudience,
        kid: String = testKid,
        expiresAt: Instant = Instant.now().plus(1, ChronoUnit.HOURS),
        subject: String = sentinelSub,
    ): String =
        JWT.create()
            .withKeyId(kid)
            .withSubject(subject)
            .withAudience(audience)
            .withIssuedAt(UtilDate.from(Instant.now()))
            .withExpiresAt(UtilDate.from(expiresAt))
            .sign(Algorithm.RSA256(pubKey, privKey))

    "4.2a Successful /internal/* request produces span with hashed service.account.id" {
        val (sdk, recorder) = SpanRecorder.newPipeline()
        GlobalOpenTelemetry.set(sdk)
        testApplication {
            application {
                installKtorServerTelemetry(sdk)
                routing {
                    route("/internal") {
                        install(InternalEndpointAuth) { verifier = defaultVerifier }
                        stubUnbanWorkerRoute()
                    }
                }
            }
            val token = signedJwt(subject = sentinelSub)
            val resp =
                client.post("/internal/unban-worker") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            resp.status shouldBe HttpStatusCode.OK
        }
        val span = recorder.serverSpan()
        span shouldNotBe null
        val expected = ServiceAccountIdHasher.hash(sentinelSub)
        span!!.serviceAccountId() shouldBe expected
        span.serviceAccountId()!! shouldMatch Regex("^[0-9a-f]{16}$")
    }

    "4.2b Successful request span MUST NOT carry the raw sub claim (sentinel scan)" {
        val (sdk, recorder) = SpanRecorder.newPipeline()
        GlobalOpenTelemetry.set(sdk)
        testApplication {
            application {
                installKtorServerTelemetry(sdk)
                routing {
                    route("/internal") {
                        install(InternalEndpointAuth) { verifier = defaultVerifier }
                        stubUnbanWorkerRoute()
                    }
                }
            }
            val token = signedJwt(subject = sentinelSub)
            client.post("/internal/unban-worker") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        }
        for (span in recorder.recordedSpans()) {
            span.name shouldNotContain sentinelSub
            span.attributes.forEach { key, value ->
                key.key shouldNotContain sentinelSub
                value.toString() shouldNotContain sentinelSub
            }
            for (event in span.events) {
                event.name shouldNotContain sentinelSub
                event.attributes.forEach { _, value ->
                    value.toString() shouldNotContain sentinelSub
                }
            }
        }
    }

    "4.3 401-rejected request span does NOT carry service.account.id or user.id" {
        val (sdk, recorder) = SpanRecorder.newPipeline()
        GlobalOpenTelemetry.set(sdk)
        testApplication {
            application {
                installKtorServerTelemetry(sdk)
                routing {
                    route("/internal") {
                        install(InternalEndpointAuth) { verifier = defaultVerifier }
                        stubUnbanWorkerRoute()
                    }
                }
            }
            // Expired token → 401 expired_token before OidcSubjectKey is set.
            val expiredToken =
                signedJwt(expiresAt = Instant.now().minus(5, ChronoUnit.MINUTES))
            val resp =
                client.post("/internal/unban-worker") {
                    header(HttpHeaders.Authorization, "Bearer $expiredToken")
                }
            resp.status shouldBe HttpStatusCode.Unauthorized
        }
        // Spec contract is "attribute absence" — whether a span is produced for
        // the 401 path is implementation-detail of KtorServerTelemetry.
        for (span in recorder.recordedSpans()) {
            span.serviceAccountId() shouldBe null
            span.userId() shouldBe null
        }
    }

    "4.4 Server-span-level mutual exclusion: /internal/* span never carries user.id" {
        val (sdk, recorder) = SpanRecorder.newPipeline()
        GlobalOpenTelemetry.set(sdk)
        testApplication {
            application {
                installKtorServerTelemetry(sdk)
                routing {
                    route("/internal") {
                        install(InternalEndpointAuth) { verifier = defaultVerifier }
                        stubUnbanWorkerRoute()
                    }
                }
            }
            val token = signedJwt(subject = sentinelSub)
            client.post("/internal/unban-worker") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        }
        val span = recorder.serverSpan()
        span shouldNotBe null
        span!!.serviceAccountId() shouldNotBe null
        span.userId() shouldBe null
    }

    "4.4b Converse mutual exclusion: /api/v1/* span never carries service.account.id" {
        val (sdk, recorder) = SpanRecorder.newPipeline()
        GlobalOpenTelemetry.set(sdk)
        val userKeys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-user")
        val userJwtIssuer = JwtIssuer(userKeys)
        val userId = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val fakeUsers = FakeUserRepository.withSingleUser(userId)
        testApplication {
            application {
                installKtorServerTelemetry(sdk)
                install(Authentication) {
                    configureUserJwt(userKeys, fakeUsers, Instant::now)
                }
                routing {
                    route("/api/v1") {
                        authenticate(AUTH_PROVIDER_USER) {
                            routingPost("/test-mutex") {
                                call.respondText("ok")
                            }
                        }
                    }
                }
            }
            val token = userJwtIssuer.issueAccessToken(userId, tokenVersion = 0)
            val resp =
                client.post("/api/v1/test-mutex") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            resp.status shouldBe HttpStatusCode.OK
        }
        val span = recorder.serverSpan()
        span shouldNotBe null
        span!!.userId() shouldNotBe null
        span.serviceAccountId() shouldBe null
    }

    "4.5 Best-effort write silently no-ops when OTel SDK uninitialised" {
        // Intentionally do NOT install KtorServerTelemetry and do NOT set
        // GlobalOpenTelemetry — Span.current() returns the no-op span, the
        // writer's setAttribute is a defensive no-op (no throw), and the
        // handler dispatches normally.
        testApplication {
            application {
                routing {
                    route("/internal") {
                        install(InternalEndpointAuth) { verifier = defaultVerifier }
                        stubUnbanWorkerRoute()
                    }
                }
            }
            val token = signedJwt(subject = sentinelSub)
            val resp =
                client.post("/internal/unban-worker") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            resp.status shouldBe HttpStatusCode.OK
            resp.bodyAsText() shouldBe "stub-ok"
        }
    }

    "4.5b Best-effort write silently no-ops on helper throw (blank sub)" {
        val (sdk, recorder) = SpanRecorder.newPipeline()
        GlobalOpenTelemetry.set(sdk)
        // Fake verifier returns a VerifiedClaims with sub = "" — mirrors the
        // GoogleOidcTokenVerifier null-substitution at line 90. The writer's
        // helper invocation throws IllegalArgumentException; the catch-all
        // swallows it and the handler dispatches normally.
        val blankSubVerifier =
            object : OidcTokenVerifier {
                override suspend fun verify(token: String): VerifiedClaims =
                    VerifiedClaims(
                        sub = "",
                        aud = testAudience,
                        exp = Instant.now().plus(1, ChronoUnit.HOURS),
                        iat = Instant.now(),
                    )
            }
        testApplication {
            application {
                installKtorServerTelemetry(sdk)
                routing {
                    route("/internal") {
                        install(InternalEndpointAuth) { verifier = blankSubVerifier }
                        stubUnbanWorkerRoute()
                    }
                }
            }
            val resp =
                client.post("/internal/unban-worker") {
                    header(HttpHeaders.Authorization, "Bearer anything-the-fake-ignores-it")
                }
            resp.status shouldBe HttpStatusCode.OK
            resp.bodyAsText() shouldBe "stub-ok"
        }
        val span = recorder.serverSpan()
        span shouldNotBe null
        span!!.serviceAccountId() shouldBe null
    }

    "4.5c Best-effort write silently no-ops on SpanProcessor failure" {
        val recorder = SpanRecorder()
        val provider =
            SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(recorder)
                .addSpanProcessor(FailingSpanProcessor("simulated SDK failure (4.5c)"))
                .build()
        val sdk: OpenTelemetry =
            OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .setPropagators(
                    io.opentelemetry.context.propagation.ContextPropagators.create(
                        io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance(),
                    ),
                )
                .build()
        GlobalOpenTelemetry.set(sdk)
        testApplication {
            application {
                installKtorServerTelemetry(sdk)
                routing {
                    route("/internal") {
                        install(InternalEndpointAuth) { verifier = defaultVerifier }
                        stubUnbanWorkerRoute()
                    }
                }
            }
            val token = signedJwt(subject = sentinelSub)
            val resp =
                client.post("/internal/unban-worker") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            // Handler must dispatch unblocked even when the SDK pipeline throws.
            resp.status shouldBe HttpStatusCode.OK
            resp.bodyAsText() shouldBe "stub-ok"
        }
        // The recorder still captured spans before the FailingSpanProcessor
        // throws — both processors fire on onEnd, the failing one throws after
        // the recorder appends. We just verify at least one span made it
        // through; the spec scenario covers the EFFECT (handler unblocked),
        // not the precise SDK ordering.
        recorder.recordedSpans() shouldHaveAtLeastSize 1
    }

    "4.6 Vendor-webhook routes opt out and produce no service.account.id" {
        val (sdk, recorder) = SpanRecorder.newPipeline()
        GlobalOpenTelemetry.set(sdk)
        testApplication {
            application {
                installKtorServerTelemetry(sdk)
                routing {
                    // Vendor-webhook route mounted under /internal/* but
                    // WITHOUT InternalEndpointAuth — mirrors the canonical
                    // AppleS2SRoutes mounting shape at line 54 of
                    // AppleS2SRoutes.kt.
                    routingPost("/internal/apple/s2s-notifications") {
                        call.respondText("ok")
                    }
                }
            }
            val resp = client.post("/internal/apple/s2s-notifications")
            resp.status shouldBe HttpStatusCode.OK
        }
        val span = recorder.serverSpan()
        span shouldNotBe null
        span!!.serviceAccountId() shouldBe null
        span.userId() shouldBe null
    }
})

/** Stub /internal/unban-worker that returns 200 "stub-ok" with no DB dependency. */
private fun io.ktor.server.routing.Route.stubUnbanWorkerRoute() {
    routingPost("/unban-worker") {
        call.respondText("stub-ok")
    }
}

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
 * Minimal in-memory [UserRepository] for the 4.4b converse-mutual-exclusion
 * scenario. Only [findById] is exercised by [configureUserJwt]; the other
 * methods throw to make missing-coverage obvious.
 */
private class FakeUserRepository(private val row: UserRow) : UserRepository {
    override fun findById(id: UUID): UserRow? = if (id == row.id) row else null

    override fun findByGoogleIdHash(hash: String): UserRow? = error("unused")

    override fun findByAppleIdHash(hash: String): UserRow? = error("unused")

    override fun incrementTokenVersion(id: UUID): Int = error("unused")

    override fun setAppleRelayEmail(
        appleIdHash: String,
        enabled: Boolean,
    ): Int = error("unused")

    override fun existsByProviderHash(
        conn: java.sql.Connection,
        hash: String,
        type: IdentifierType,
    ): Boolean = error("unused")

    override fun existsByInviteCodePrefix(prefix: String): Boolean = error("unused")

    override fun create(
        conn: java.sql.Connection,
        row: NewUserRow,
    ): UUID = error("unused")

    companion object {
        fun withSingleUser(id: UUID): FakeUserRepository =
            FakeUserRepository(
                UserRow(
                    id = id,
                    username = "test-user",
                    displayName = "Test User",
                    email = null,
                    googleIdHash = null,
                    appleIdHash = null,
                    appleRelayEmail = false,
                    isShadowBanned = false,
                    isBanned = false,
                    suspendedUntil = null,
                    tokenVersion = 0,
                    deletedAt = null,
                    subscriptionStatus = "free",
                ),
            )
    }
}

/** Captures the most recent SERVER span from the recorder. */
private fun SpanRecorder.serverSpan(): SpanData? = recordedSpans().lastOrNull { it.kind == io.opentelemetry.api.trace.SpanKind.SERVER }

private fun SpanData.serviceAccountId(): String? = attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("service.account.id"))

private fun SpanData.userId(): String? = attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("user.id"))
