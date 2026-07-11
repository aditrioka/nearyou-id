package id.nearyou.app.internal

import id.nearyou.app.account.AccountDeletionRepository
import id.nearyou.app.account.AccountHardDeleteWorker
import id.nearyou.app.admin.PrivacyFlipWorker
import id.nearyou.app.admin.SuspensionUnbanWorker
import id.nearyou.app.admin.privacyFlipWorkerRoute
import id.nearyou.app.admin.retention.JdbcRetentionCleanupRepository
import id.nearyou.app.admin.retention.RetentionCleanupWorker
import id.nearyou.app.admin.retention.retentionCleanupRoutes
import id.nearyou.app.admin.unbanWorkerRoute
import id.nearyou.app.auth.anomaly.JdbcLoginAnomalyRepository
import id.nearyou.app.auth.anomaly.LoginAnomalyDetectionService
import id.nearyou.app.auth.anomaly.loginAnomalyCheckRoutes
import id.nearyou.app.auth.provider.JwksCache
import id.nearyou.app.auth.routes.InMemoryDedup
import id.nearyou.app.auth.routes.appleS2SRoutes
import id.nearyou.app.auth.session.InMemoryUsers
import id.nearyou.app.core.domain.oidc.OidcTokenVerifier
import id.nearyou.app.core.domain.oidc.VerifiedClaims
import id.nearyou.app.image.JdbcOrphanImageCleanupRepository
import id.nearyou.app.image.OrphanImageCleanupWorker
import id.nearyou.app.image.orphanImageCleanupRoutes
import id.nearyou.app.infra.revenuecatapi.NoOpReferralEntitlementGranter
import id.nearyou.app.notifications.NoopNotificationDispatcher
import id.nearyou.app.notifications.NotificationEmitter
import id.nearyou.app.referral.ReferralActivityCheckWorker
import id.nearyou.app.referral.ReferralGrantRepository
import id.nearyou.app.referral.referralActivityCheckRoute
import id.nearyou.app.subscription.SubscriptionEventRepository
import id.nearyou.app.subscription.SubscriptionService
import id.nearyou.app.subscription.revenueCatWebhookRoutes
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.io.PrintWriter
import java.security.interfaces.RSAPublicKey
import javax.sql.DataSource

/**
 * Regression guard for the /internal route-tree merge hazard (2026-06-10 audit,
 * finding 01-#1): Ktor merges identical path segments across SEPARATE
 * `routing {}` blocks, so installing `InternalEndpointAuth` on the shared
 * `route("/internal")` node ALSO gated the vendor-auth Apple S2S webhook
 * (`/internal/apple/s2s-notifications`) mounted by `appleS2SRoutes` — Apple
 * sends no Google-OIDC bearer, so every notification would 401 before its
 * signed-payload verification ran. The fix installs the gate per JOB subtree
 * inside `unbanWorkerRoute` (matching the internal-endpoint-auth spec scenario
 * "mounted on /internal/unban-worker").
 *
 * This test co-mounts BOTH blocks in the production shape — the combination no
 * prior test exercised — and pins the two properties the spec requires.
 */
class InternalRoutingIsolationTest : StringSpec({

    fun io.ktor.server.testing.ApplicationTestBuilder.mountProductionShape() {
        application {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        explicitNulls = false
                    },
                )
            }
            // Production shape: webhook in its own routing block (appleS2SRoutes
            // opens `routing { post("/internal/apple/s2s-notifications") ... }`).
            appleS2SRoutes(
                NullJwksCache,
                setOf("id.nearyou.app"),
                InMemoryUsers(),
                AccountDeletionRepository(UnusedDataSource),
                AccountHardDeleteWorker(UnusedDataSource),
                InMemoryDedup(),
            )
            // Production shape: the RevenueCat vendor webhook in its OWN routing
            // block, mounted OUTSIDE the OIDC gate (revenueCatWebhookRoutes opens
            // its own `routing { post("/internal/revenuecat-webhook") ... }`).
            revenueCatWebhookRoutes(StubRevenueCatService, StubRevenueCatSecrets, "test")
            // Production shape: job endpoints under the shared /internal node,
            // each owning its OIDC gate (UnbanWorkerRoute installs it).
            routing {
                route("/internal") {
                    unbanWorkerRoute(SuspensionUnbanWorker(UnusedDataSource), NeverCalledVerifier)
                    privacyFlipWorkerRoute(PrivacyFlipWorker(UnusedDataSource), NeverCalledVerifier)
                    referralActivityCheckRoute(
                        ReferralActivityCheckWorker(UnusedDataSource, ReferralGrantRepository(), NoOpReferralEntitlementGranter),
                        NeverCalledVerifier,
                    )
                    retentionCleanupRoutes(
                        RetentionCleanupWorker(JdbcRetentionCleanupRepository(UnusedDataSource)),
                        NeverCalledVerifier,
                    )
                    orphanImageCleanupRoutes(
                        OrphanImageCleanupWorker(
                            JdbcOrphanImageCleanupRepository(UnusedDataSource),
                            id.nearyou.app.infra.cloudflareimages.NoOpImageStore,
                        ),
                        NeverCalledVerifier,
                    )
                    loginAnomalyCheckRoutes(
                        LoginAnomalyDetectionService(JdbcLoginAnomalyRepository(UnusedDataSource)),
                        NeverCalledVerifier,
                    )
                }
            }
        }
    }

    "Apple S2S webhook is NOT captured by the job-endpoint OIDC gate" {
        testApplication {
            mountProductionShape()
            // Garbage payload, no Authorization header. The webhook's own
            // signed-payload validation must answer (4xx from payload handling)
            // — NOT the OIDC plugin's 401 (which would mean the gate captured it).
            val response =
                client.post("/internal/apple/s2s-notifications") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"signedPayload":"garbage"}""")
                }
            response.status shouldNotBe HttpStatusCode.Unauthorized
        }
    }

    "unban-worker without a bearer token is still rejected 401 by its own gate" {
        testApplication {
            mountProductionShape()
            val response = client.post("/internal/unban-worker")
            response.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    "privacy-flip-worker without a bearer token is still rejected 401 by its own gate" {
        testApplication {
            mountProductionShape()
            val response = client.post("/internal/privacy-flip-worker")
            response.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    "referral-activity-check without a bearer token is still rejected 401 by its own gate" {
        testApplication {
            mountProductionShape()
            val response = client.post("/internal/referral-activity-check")
            response.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    "retention-cleanup worker without a bearer token is still rejected 401 by its own gate" {
        testApplication {
            mountProductionShape()
            val response = client.post("/internal/cleanup")
            response.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    "orphan-image-cleanup worker without a bearer token is still rejected 401 by its own gate" {
        testApplication {
            mountProductionShape()
            val response = client.post("/internal/cleanup-orphan-images")
            response.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    "login-anomaly-check worker without a bearer token is still rejected 401 by its own gate" {
        testApplication {
            mountProductionShape()
            val response = client.post("/internal/login-anomaly-check")
            response.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    "RevenueCat webhook is NOT captured by the job-endpoint OIDC gate" {
        testApplication {
            mountProductionShape()
            // Correct VENDOR bearer (NOT a Google-OIDC token) + a malformed body.
            // If the shared /internal OIDC gate had captured this route, a non-OIDC
            // bearer would 401 before any handler logic. Instead the vendor route
            // answers from its OWN auth + body validation (400) — proving it does
            // NOT inherit OIDC. Fulfils the internal-endpoint-auth "Vendor-webhook
            // route does NOT inherit OIDC" scenario for /internal/revenuecat-webhook.
            val response =
                client.post("/internal/revenuecat-webhook") {
                    header(HttpHeaders.Authorization, "Bearer rc-test-bearer")
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            response.status shouldNotBe HttpStatusCode.Unauthorized
        }
    }
})

/** Vendor bearer resolver for the revenuecat-webhook isolation case. */
private val StubRevenueCatSecrets =
    object : id.nearyou.app.config.SecretResolver {
        override fun resolve(name: String): String? = if (name == "revenuecat-webhook-secret") "rc-test-bearer" else null
    }

/** Never invoked — the malformed `{}` body 400s in the route before the service runs. */
private val StubRevenueCatService =
    SubscriptionService(
        dataSource = UnusedDataSource,
        repository = SubscriptionEventRepository(),
        notifications =
            object : NotificationEmitter {
                override fun emit(
                    conn: java.sql.Connection,
                    recipientId: java.util.UUID,
                    actorUserId: java.util.UUID?,
                    type: id.nearyou.data.repository.NotificationType,
                    targetType: String?,
                    targetId: java.util.UUID?,
                    bodyData: kotlinx.serialization.json.JsonObject,
                ): java.util.UUID? = error("not used")
            },
        dispatcher = NoopNotificationDispatcher(),
    )

/** keyFor never matches — the webhook 4xxes on payload validation before key use. */
private object NullJwksCache : JwksCache(io.ktor.client.HttpClient(), "stub://") {
    override suspend fun keyFor(kid: String): RSAPublicKey? = null
}

/** The 401-on-missing-Authorization path must reject BEFORE verification runs. */
private object NeverCalledVerifier : OidcTokenVerifier {
    override suspend fun verify(token: String): VerifiedClaims = error("verifier must not be invoked without a bearer token")
}

/** Worker ctor needs a DataSource; the 401 path never touches it. */
private object UnusedDataSource : DataSource {
    override fun getConnection(): java.sql.Connection = error("not used")

    override fun getConnection(
        username: String?,
        password: String?,
    ): java.sql.Connection = error("not used")

    override fun getLogWriter(): PrintWriter = error("not used")

    override fun setLogWriter(out: PrintWriter?) = error("not used")

    override fun setLoginTimeout(seconds: Int) = error("not used")

    override fun getLoginTimeout(): Int = error("not used")

    override fun getParentLogger(): java.util.logging.Logger = error("not used")

    override fun <T : Any?> unwrap(iface: Class<T>?): T = error("not used")

    override fun isWrapperFor(iface: Class<*>?): Boolean = error("not used")
}
