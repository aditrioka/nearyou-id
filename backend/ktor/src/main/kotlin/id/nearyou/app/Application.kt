package id.nearyou.app

import id.nearyou.app.account.AccountDeletionRepository
import id.nearyou.app.account.AccountDeletionService
import id.nearyou.app.account.AccountHardDeleteWorker
import id.nearyou.app.account.DataExportArchiveService
import id.nearyou.app.account.DataExportGatherRepository
import id.nearyou.app.account.DataExportRequestRepository
import id.nearyou.app.account.DataExportService
import id.nearyou.app.account.DataExportWorker
import id.nearyou.app.account.PeerIdHasher
import id.nearyou.app.account.accountDataExportRoutes
import id.nearyou.app.account.accountHardDeleteWorkerRoute
import id.nearyou.app.account.accountRoutes
import id.nearyou.app.account.dataExportWorkerRoute
import id.nearyou.app.admin.PrivacyFlipWorker
import id.nearyou.app.admin.SuspensionUnbanWorker
import id.nearyou.app.admin.admin
import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.auth.AdminUserRepository
import id.nearyou.app.admin.auth.SessionRepository
import id.nearyou.app.admin.privacyFlipWorkerRoute
import id.nearyou.app.admin.retention.JdbcRetentionCleanupRepository
import id.nearyou.app.admin.retention.RetentionCleanupWorker
import id.nearyou.app.admin.retention.retentionCleanupRoutes
import id.nearyou.app.admin.unbanWorkerRoute
import id.nearyou.app.auth.installAuth
import id.nearyou.app.auth.jwks.jwksRoutes
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.loginhistory.JdbcLoginEventRepository
import id.nearyou.app.auth.loginhistory.LoginEventRecorder
import id.nearyou.app.auth.provider.APPLE_JWKS_URL_DEFAULT
import id.nearyou.app.auth.provider.AppleIdTokenVerifier
import id.nearyou.app.auth.provider.GOOGLE_JWKS_URL_DEFAULT
import id.nearyou.app.auth.provider.GoogleIdTokenVerifier
import id.nearyou.app.auth.provider.JwksCache
import id.nearyou.app.auth.routes.InMemoryDedup
import id.nearyou.app.auth.routes.Providers
import id.nearyou.app.auth.routes.RealtimeTokenIssuer
import id.nearyou.app.auth.routes.appleS2SRoutes
import id.nearyou.app.auth.routes.authRoutes
import id.nearyou.app.auth.routes.realtimeRoutes
import id.nearyou.app.auth.session.RefreshTokenService
import id.nearyou.app.auth.signup.InviteCodePrefixDeriver
import id.nearyou.app.auth.signup.SignupService
import id.nearyou.app.auth.signup.UsernameGenerator
import id.nearyou.app.auth.signup.UsernameHistoryRepository
import id.nearyou.app.auth.signup.WordPairResource
import id.nearyou.app.auth.signup.signupRoutes
import id.nearyou.app.block.BlockRateLimiter
import id.nearyou.app.block.BlockService
import id.nearyou.app.block.blockRoutes
import id.nearyou.app.chat.ChatRepository
import id.nearyou.app.chat.ChatService
import id.nearyou.app.chat.chatRoutes
import id.nearyou.app.common.AppJson
import id.nearyou.app.common.ClientIpExtractorPlugin
import id.nearyou.app.common.DbDispatchers
import id.nearyou.app.common.installAppStatusPages
import id.nearyou.app.config.EnvVarSecretResolver
import id.nearyou.app.config.RemoteConfig
import id.nearyou.app.config.RemoteConfigClientAdapter
import id.nearyou.app.config.SecretResolver
import id.nearyou.app.config.StubRemoteConfig
import id.nearyou.app.config.secretKey
import id.nearyou.app.core.domain.chat.ChatRealtimeClient
import id.nearyou.app.core.domain.health.PostgresProbe
import id.nearyou.app.core.domain.health.ProbeResult
import id.nearyou.app.core.domain.health.RedisProbe
import id.nearyou.app.core.domain.health.SupabaseRealtimeProbe
import id.nearyou.app.core.domain.oidc.OidcTokenVerifier
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import id.nearyou.app.engagement.LikeService
import id.nearyou.app.engagement.ReplyService
import id.nearyou.app.engagement.likeRoutes
import id.nearyou.app.engagement.replyRoutes
import id.nearyou.app.follow.FollowRateLimiter
import id.nearyou.app.follow.FollowService
import id.nearyou.app.follow.followRoutes
import id.nearyou.app.follow.userSocialRoutes
import id.nearyou.app.guard.ContentLengthGuard
import id.nearyou.app.guard.installContentLengthGuard
import id.nearyou.app.health.JdbcPostgresProbe
import id.nearyou.app.health.KtorSupabaseRealtimeProbe
import id.nearyou.app.health.healthRoutes
import id.nearyou.app.image.ImageUploadFlagGate
import id.nearyou.app.image.ImageUploadRateLimiter
import id.nearyou.app.image.ImageUploadService
import id.nearyou.app.image.JdbcImageUploadRepository
import id.nearyou.app.image.imageRoutes
import id.nearyou.app.infra.cloudflareimages.CloudflareImagesConfig
import id.nearyou.app.infra.cloudflareimages.imageStore
import id.nearyou.app.infra.cloudvision.imageModerator
import id.nearyou.app.infra.db.DataSourceFactory
import id.nearyou.app.infra.db.DbConfig
import id.nearyou.app.infra.fcm.FcmDispatcherScope
import id.nearyou.app.infra.fcm.FcmInitException
import id.nearyou.app.infra.fcm.buildFcmComposite
import id.nearyou.app.infra.oidc.GoogleOidcTokenVerifier
import id.nearyou.app.infra.oidc.googleJwkProvider
import id.nearyou.app.infra.openaimoderation.ModerationClient
import id.nearyou.app.infra.openaimoderation.OpenAiModerationClient
import id.nearyou.app.infra.otel.OtelBootstrap
import id.nearyou.app.infra.otel.OtelInstrumentation
import id.nearyou.app.infra.otel.httpClientWithOtel
import id.nearyou.app.infra.otel.installKtorServerTelemetry
import id.nearyou.app.infra.r2.ObjectStore
import id.nearyou.app.infra.r2.R2Config
import id.nearyou.app.infra.r2.R2ObjectStore
import id.nearyou.app.infra.r2.objectStore
import id.nearyou.app.infra.redis.NoOpRateLimiter
import id.nearyou.app.infra.redis.NoOpRedisStringCache
import id.nearyou.app.infra.redis.RedisStringCache
import id.nearyou.app.infra.redis.redisHandlesFromUrl
import id.nearyou.app.infra.remoteconfig.NoOpRemoteConfigPublisher
import id.nearyou.app.infra.remoteconfig.RemoteConfigClient
import id.nearyou.app.infra.remoteconfig.RemoteConfigInitException
import id.nearyou.app.infra.remoteconfig.RemoteConfigPublisher
import id.nearyou.app.infra.remoteconfig.firebaseRemoteConfigClient
import id.nearyou.app.infra.remoteconfig.remoteConfigServerPublisher
import id.nearyou.app.infra.repo.JdbcLayer3ModerationWriter
import id.nearyou.app.infra.repo.JdbcModerationQueueRepository
import id.nearyou.app.infra.repo.JdbcNotificationRepository
import id.nearyou.app.infra.repo.JdbcPostAutoHideRepository
import id.nearyou.app.infra.repo.JdbcPostLikeRepository
import id.nearyou.app.infra.repo.JdbcPostReplyRepository
import id.nearyou.app.infra.repo.JdbcPostRepository
import id.nearyou.app.infra.repo.JdbcPostsFollowingRepository
import id.nearyou.app.infra.repo.JdbcPostsGlobalRepository
import id.nearyou.app.infra.repo.JdbcPostsTimelineRepository
import id.nearyou.app.infra.repo.JdbcRefreshTokenRepository
import id.nearyou.app.infra.repo.JdbcRejectedIdentifierRepository
import id.nearyou.app.infra.repo.JdbcReportRepository
import id.nearyou.app.infra.repo.JdbcReservedUsernameRepository
import id.nearyou.app.infra.repo.JdbcSearchRepository
import id.nearyou.app.infra.repo.JdbcSinglePostRepository
import id.nearyou.app.infra.repo.JdbcUserBlockRepository
import id.nearyou.app.infra.repo.JdbcUserFollowsRepository
import id.nearyou.app.infra.repo.JdbcUserRepository
import id.nearyou.app.infra.repo.JdbcUsernameFlagOverrideRepository
import id.nearyou.app.infra.repo.PostEditHistoryQuery
import id.nearyou.app.infra.repo.PostRepository
import id.nearyou.app.infra.repo.PostsFollowingRepository
import id.nearyou.app.infra.repo.PostsGlobalRepository
import id.nearyou.app.infra.repo.PostsTimelineRepository
import id.nearyou.app.infra.repo.RefreshTokenRepository
import id.nearyou.app.infra.repo.RejectedIdentifierRepository
import id.nearyou.app.infra.repo.ReservedUsernameRepository
import id.nearyou.app.infra.repo.SinglePostRepository
import id.nearyou.app.infra.repo.UserBlockRepository
import id.nearyou.app.infra.repo.UserRepository
import id.nearyou.app.infra.resend.EmailSender
import id.nearyou.app.infra.resend.ResendConfig
import id.nearyou.app.infra.resend.ResendEmailSender
import id.nearyou.app.infra.resend.emailSender
import id.nearyou.app.infra.revenuecatapi.referralEntitlementGranter
import id.nearyou.app.infra.supabase.realtime.NoopChatRealtimeClient
import id.nearyou.app.infra.supabase.realtime.SupabaseBroadcastChatClient
import id.nearyou.app.moderation.CachingLayer3ConfigLoader
import id.nearyou.app.moderation.CachingModerationListLoader
import id.nearyou.app.moderation.DefaultLayer3Moderator
import id.nearyou.app.moderation.Layer3ConfigLoader
import id.nearyou.app.moderation.Layer3DispatcherScope
import id.nearyou.app.moderation.Layer3Moderator
import id.nearyou.app.moderation.ModerationList
import id.nearyou.app.moderation.ModerationListLoader
import id.nearyou.app.moderation.ReportRateLimiter
import id.nearyou.app.moderation.ReportService
import id.nearyou.app.moderation.TextModerator
import id.nearyou.app.moderation.csam.CsamDetectionService
import id.nearyou.app.moderation.csam.CsamMetadataEncryptor
import id.nearyou.app.moderation.csam.CsamRepository
import id.nearyou.app.moderation.csam.csamArchivePurgeRoute
import id.nearyou.app.moderation.csam.csamWebhookRoutes
import id.nearyou.app.moderation.reportRoutes
import id.nearyou.app.notifications.DbNotificationEmitter
import id.nearyou.app.notifications.NoopNotificationDispatcher
import id.nearyou.app.notifications.NotificationEmitter
import id.nearyou.app.notifications.NotificationService
import id.nearyou.app.notifications.notificationRoutes
import id.nearyou.app.post.CreatePostService
import id.nearyou.app.post.PostEditRateLimiter
import id.nearyou.app.post.PostEditService
import id.nearyou.app.post.PostRateLimiter
import id.nearyou.app.post.PostReadService
import id.nearyou.app.post.postEditRoutes
import id.nearyou.app.post.postRoutes
import id.nearyou.app.post.singlePostRoutes
import id.nearyou.app.referral.ReferralActivityCheckWorker
import id.nearyou.app.referral.ReferralGrantRepository
import id.nearyou.app.referral.ReferralRepository
import id.nearyou.app.referral.ReferralService
import id.nearyou.app.referral.ReferralTicketRateLimiter
import id.nearyou.app.referral.referralActivityCheckRoute
import id.nearyou.app.search.SearchRateLimiter
import id.nearyou.app.search.SearchService
import id.nearyou.app.search.searchRoutes
import id.nearyou.app.subscription.SubscriptionEventRepository
import id.nearyou.app.subscription.SubscriptionService
import id.nearyou.app.subscription.revenueCatWebhookRoutes
import id.nearyou.app.timeline.FollowingTimelineService
import id.nearyou.app.timeline.GlobalTimelineService
import id.nearyou.app.timeline.NearbyTimelineService
import id.nearyou.app.timeline.TimelineReadRateLimiter
import id.nearyou.app.timeline.followingTimelineRoutes
import id.nearyou.app.timeline.globalTimelineRoutes
import id.nearyou.app.timeline.timelineRoutes
import id.nearyou.app.user.ConsentRepository
import id.nearyou.app.user.FcmTokenRepository
import id.nearyou.app.user.HideDistanceRepository
import id.nearyou.app.user.JdbcActorUsernameLookup
import id.nearyou.app.user.JdbcUserFcmTokenReader
import id.nearyou.app.user.JdbcUserProfileReader
import id.nearyou.app.user.JdbcUsernameHistoryRepository
import id.nearyou.app.user.UserProfileService
import id.nearyou.app.user.UsernameChangeService
import id.nearyou.app.user.UsernameRateLimiter
import id.nearyou.app.user.consentRoutes
import id.nearyou.app.user.fcmTokenRoutes
import id.nearyou.app.user.hideDistanceRoutes
import id.nearyou.app.user.userProfileRoutes
import id.nearyou.app.user.userUsernameRoutes
import id.nearyou.data.repository.ActorUsernameLookup
import id.nearyou.data.repository.Layer3ModerationWriter
import id.nearyou.data.repository.ModerationQueueRepository
import id.nearyou.data.repository.NotificationDispatcher
import id.nearyou.data.repository.NotificationRepository
import id.nearyou.data.repository.PostAutoHideRepository
import id.nearyou.data.repository.PostLikeRepository
import id.nearyou.data.repository.PostReplyRepository
import id.nearyou.data.repository.ReportRepository
import id.nearyou.data.repository.SearchRepository
import id.nearyou.data.repository.UserFcmTokenReader
import id.nearyou.data.repository.UserFollowsRepository
import id.nearyou.data.repository.UserProfileReader
import id.nearyou.data.repository.UsernameFlagOverrideRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.launch
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.exception.FlywayValidateException
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

fun main(args: Array<String>) {
    EngineMain.main(args)
}

// Inbound X-Request-Id values must be short header-safe tokens (UUIDs, Cloudflare ray
// ids, Cloud Run trace ids all fit); anything else is regenerated server-side.
private val CALL_ID_PATTERN = Regex("^[A-Za-z0-9_.-]{1,128}$")

fun Application.module() {
    // OTel bootstrap MUST be the first call inside `Application.module()` so
    // every subsequent install + DI binding is auto-instrumented. The
    // bootstrap is exception-safe: missing endpoint/token secrets fall back
    // to a no-op LoggingSpanExporter (DEBUG severity, dropped by default
    // Logback). Per `observability-otel-foundation` capability spec.
    val otelEnv = environment.config.propertyOrNull("ktor.environment")?.getString() ?: "production"
    val otelSecrets = EnvVarSecretResolver()
    val otelHandle = OtelBootstrap.start(env = otelEnv, secretResolver = otelSecrets::resolve)
    // Flush + close the span pipeline during graceful stop — BatchSpanProcessor buffers
    // up to ~5 s of spans, so without this every SIGTERM/deploy drops the tail spans
    // (including the errors that explain why the instance died).
    monitor.subscribe(ApplicationStopped) { otelHandle.shutdown() }

    // Ktor server OTel plugin: emits a server span per inbound request with
    // `http.route` = Ktor route pattern, `http.status_code`, etc. Forbidden
    // attributes (`client.address`, `net.peer.ip`, `net.sock.peer.addr`,
    // `http.client_ip`) are stripped at export time by
    // `ForbiddenAttributeStripper` registered in the OTel SDK pipeline.
    installKtorServerTelemetry()

    // ClientIpExtractor MUST run before auth, rate-limit, and any business handler.
    // It populates `call.clientIp` via the canonical CF-Connecting-IP →
    // XFF-first → remoteHost ladder. Direct `X-Forwarded-For` reads outside
    // ClientIpExtractor.kt are forbidden by the `RawXForwardedForRule` Detekt rule.
    install(ClientIpExtractorPlugin)

    install(ContentNegotiation) {
        // ONE shared Json (docs/11 §3.3; 01-#14) — see common/AppJson.kt.
        json(AppJson)
    }

    // Request-correlation id on every log line (docs/11 §3.3). Honors an inbound
    // X-Request-Id (Cloudflare/Cloud Run propagation), generates one otherwise,
    // and reflects it on the response for client-side correlation. The
    // CallId × CallLogging pairing is safe ≥ Ktor 3.4.3 (cascading-failure bug
    // fixed upstream; we're pinned to 3.4.3 — KTOR-9546 blocks the 3.5.x line).
    install(CallId) {
        retrieveFromHeader(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        // Bounded charset/length: the id is reflected into the response header and
        // (via callIdMdc) into every log line — an unconstrained inbound value is a
        // log-forging / header-injection vector. Rejection falls through to generate.
        verify { CALL_ID_PATTERN.matches(it) }
        replyToHeader(HttpHeaders.XRequestId)
    }
    install(CallLogging) {
        callIdMdc("call_id")
    }
    // Timeline/chat JSON compresses well and Cloud Run egress is billed (docs/11 §3.3).
    install(Compression)

    installAppStatusPages()

    val dbConfig =
        DbConfig(
            url = environment.config.property("db.url").getString(),
            user = environment.config.property("db.user").getString(),
            password = environment.config.property("db.password").getString(),
            maxPoolSize = environment.config.propertyOrNull("db.maxPoolSize")?.getString()?.toInt() ?: 10,
        )
    // JDBC OTel instrumentation: every PreparedStatement gets a span with
    // `db.system="postgresql"` and parameterized `db.statement` (raw values
    // stripped to `?` placeholders via the statement-sanitizer flag). The
    // wrap happens post-construction in the smaller-blast-radius shape so
    // `:infra:supabase`'s DataSourceFactory stays decoupled from OTel.
    val dataSource: DataSource = OtelInstrumentation.wrapDataSource(DataSourceFactory.create(dbConfig))
    // Single pool-bounded dispatcher for ALL blocking JDBC work (docs/11 §3.2
    // rule #1) — sized to the Hikari pool so floods queue as suspended
    // coroutines instead of starving Dispatchers.IO threads on pool waits.
    val dbDispatchers = DbDispatchers(dbConfig.maxPoolSize)

    // Staging-simplified bootstrap: Ktor runs Flyway migrations at startup on the same
    // data source the app will serve requests against. The `RUN_FLYWAY_ON_STARTUP` env
    // var gates it — Cloud Run staging sets it `true`; tests use their own
    // KotestProjectConfig.beforeProject() to avoid a double migration; prod later
    // splits this into a dedicated Cloud Run Job (`nearyou-migrate`) per the
    // docs/04-Architecture.md deployment plan.
    if (System.getenv("RUN_FLYWAY_ON_STARTUP") == "true") {
        val flyway =
            Flyway
                .configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
        // Validate-first: checksum drift on an applied migration is a
        // checksum-immutability violation (CLAUDE.md invariant) that MUST surface
        // loudly — the previous unconditional repair() before every migrate()
        // silently realigned checksums and would have deployed edited history.
        // repair() now runs ONLY on the failed/drifted path, logged at ERROR,
        // preserving the original intent (retry a previously-FAILED migration
        // on staging after its SQL is fixed).
        try {
            flyway.migrate()
        } catch (e: FlywayValidateException) {
            org.slf4j.LoggerFactory.getLogger("id.nearyou.app.Application").error(
                "event=flyway_validate_failed action=repair_and_retry message={}",
                e.message,
            )
            flyway.repair()
            flyway.migrate()
        }
    }

    val secrets: SecretResolver = EnvVarSecretResolver()
    val rsaPem =
        environment.config.propertyOrNull("auth.rsaPrivateKey")?.getString()
            ?.takeIf { it.isNotBlank() }
            ?: error("Missing required config auth.rsaPrivateKey (set KTOR_RSA_PRIVATE_KEY)")
    // kid is the publicly-served JWKS key id; env-configurable ahead of the first
    // prod key event (docs/05's rotation plan is kid-based — 2026-06-10 audit, 01-#23).
    val rsaKid =
        environment.config.propertyOrNull("auth.rsaKid")?.getString()?.takeIf { it.isNotBlank() }
            ?: "dev-1"
    val rsaKeys = RsaKeyLoader(rsaPem, kid = rsaKid)
    val jwtIssuer = JwtIssuer(rsaKeys)

    val userRepository: UserRepository = JdbcUserRepository(dataSource)
    val refreshTokenRepository: RefreshTokenRepository = JdbcRefreshTokenRepository(dataSource)
    val refreshTokenService = RefreshTokenService(refreshTokenRepository, userRepository)
    // Durable login-history (V34) — written best-effort from the auth routes (clientIp +
    // request body live there); RefreshTokenService stays untouched. Security-purpose data,
    // not analytics-consent-gated (login-history-tracking).
    val loginEventRecorder = LoginEventRecorder(JdbcLoginEventRepository(dataSource, dbDispatchers.db))

    // Outbound HTTP client with OTel KtorClientTelemetry plugin pre-installed
    // so every outbound request carries `traceparent` populated from the
    // active span context. Consumed by `JwksCache` (Google + Apple JWKS),
    // `KtorSupabaseRealtimeProbe`, `appleS2SJwks`. Per spec § "W3C Trace
    // Context propagation on outbound HTTP from `:backend:ktor` (excluding FCM)".
    val httpClient: HttpClient =
        httpClientWithOtel {
            // A hung upstream (Google/Apple JWKS, Supabase probe) must not suspend
            // signins/probes indefinitely — the shared client previously had NO
            // timeout (docs/11 §3.3).
            install(HttpTimeout) {
                requestTimeoutMillis = 3_000
                connectTimeoutMillis = 3_000
                socketTimeoutMillis = 3_000
            }
        }
    val googleJwksUrl =
        environment.config.propertyOrNull("auth.google.jwksUrl")?.getString()?.takeIf { it.isNotBlank() }
            ?: GOOGLE_JWKS_URL_DEFAULT
    val appleJwksUrl =
        environment.config.propertyOrNull("auth.apple.jwksUrl")?.getString()?.takeIf { it.isNotBlank() }
            ?: APPLE_JWKS_URL_DEFAULT

    val googleAudiences = csvAudiences("auth.google.audiences")
    val appleAudiences = csvAudiences("auth.apple.audiences")

    val googleVerifier = GoogleIdTokenVerifier(JwksCache(httpClient, googleJwksUrl), googleAudiences)
    // ONE Apple JWKS cache shared by the signin verifier AND the S2S webhook —
    // two independent caches double-fetched the same URL with divergent state.
    val appleJwks = JwksCache(httpClient, appleJwksUrl)
    val appleVerifier = AppleIdTokenVerifier(appleJwks, appleAudiences)

    val supabaseSecret =
        environment.config.propertyOrNull("auth.supabaseJwtSecret")?.getString()?.takeIf { it.isNotBlank() }
            ?: error("Missing required config auth.supabaseJwtSecret (set SUPABASE_JWT_SECRET)")
    val realtimeIssuer = RealtimeTokenIssuer(supabaseSecret)

    val supabaseUrl =
        environment.config.propertyOrNull("auth.supabaseUrl")?.getString()?.takeIf { it.isNotBlank() }
            ?: error("Missing required config auth.supabaseUrl (set SUPABASE_URL)")

    // Boot-time validation for the OIDC audience binding used by /internal/* (5.3).
    // Missing / blank / non-URL → IllegalStateException before the HTTP server starts,
    // so Cloud Run's startup probe fails and traffic doesn't flip to a degraded revision.
    val internalOidcAudience = resolveInternalOidcAudience(environment.config)
    // Construct the OIDC verifier eagerly so any wiring failure surfaces at boot
    // before the route("/internal") subtree is mounted (5.4).
    val oidcTokenVerifier: OidcTokenVerifier =
        GoogleOidcTokenVerifier(audience = internalOidcAudience, jwkProvider = googleJwkProvider())
    val suspensionUnbanWorker = SuspensionUnbanWorker(dataSource)
    val privacyFlipWorker = PrivacyFlipWorker(dataSource)
    // account-deletion-tombstone: user-facing request/cancel/status API + the daily
    // hard-delete worker (Cloud Scheduler → /internal/account-hard-delete-worker).
    val accountDeletionRepository = AccountDeletionRepository(dataSource, dbDispatchers.db)
    val accountDeletionService = AccountDeletionService(accountDeletionRepository)
    val accountHardDeleteWorker = AccountHardDeleteWorker(dataSource, dbDispatchers.db)
    // scheduled-retention-cleanup: daily Cloud-Scheduler worker (→ /internal/cleanup)
    // running the three retention sweeps (refresh_tokens / notifications / user_fcm_tokens).
    val retentionCleanupRepository = JdbcRetentionCleanupRepository(dataSource, dbDispatchers.db)
    val retentionCleanupWorker = RetentionCleanupWorker(retentionCleanupRepository)

    // account-data-export: user-facing request/status API + the Cloud-Scheduler-invoked
    // worker (/internal/data-export-worker). R2 (object storage) + Resend (email) are
    // vendor-neutral substrates behind interfaces; an un-provisioned slot fails soft to the
    // NoOp binding (boot never fails; the worker maps the unconfigured outcome to `failed`).
    // ktorEnv is declared here (the earliest secret-slot-deriving consumer) and reused
    // throughout the file below (moderation pipeline, FCM, chat-realtime, image delivery).
    val ktorEnv = environment.config.propertyOrNull("ktor.environment")?.getString() ?: "production"
    val objectStore: ObjectStore = objectStore(R2Config.fromSecrets(secrets::resolve))
    val emailSender: EmailSender =
        emailSender(
            ResendConfig(
                apiKey = secrets.resolve("resend-api-key").orEmpty(),
                // Staging recipient guard (docs/10 §3.9): redirect ALL mail to Resend's
                // always-delivered test inbox so a synthetic/stale staging user can't email a
                // real address. Null in production → real recipient.
                recipientOverride = if (ktorEnv == "staging") "delivered@resend.dev" else null,
            ),
        )
    // Graceful-stop: release the R2 S3 client's HTTP engine + the Resend Ktor client's
    // connection pool on SIGTERM/deploy (mirrors the OTel `ApplicationStopped` hook above).
    // NoOp bindings have no `close()`, so the `as?` casts no-op for them.
    monitor.subscribe(ApplicationStopped) {
        (objectStore as? R2ObjectStore)?.close()
        (emailSender as? ResendEmailSender)?.close()
    }
    // Server-keyed peer-id HMAC for the scope-matrix gather (dev/test default when the slot is
    // un-provisioned, so offline gather works; staging/prod resolve a real secret).
    val peerIdHasher = PeerIdHasher.fromSecret(secrets.resolve("export-peer-hash-secret"))
    val dataExportRequestRepository = DataExportRequestRepository(dataSource, dbDispatchers.db)
    val dataExportGatherRepository = DataExportGatherRepository(dataSource, peerIdHasher, dbDispatchers.db)
    val dataExportArchiveService = DataExportArchiveService()
    val dataExportService = DataExportService(dataExportRequestRepository, objectStore)

    val reservedUsernames: ReservedUsernameRepository = JdbcReservedUsernameRepository(dataSource)
    // Real username_history binding (premium-username-customization) — shared by
    // the signup generator (release-hold collision check) and the change service.
    val usernameHistoryRepository: UsernameHistoryRepository = JdbcUsernameHistoryRepository()
    val rejectedIdentifiers: RejectedIdentifierRepository = JdbcRejectedIdentifierRepository(dataSource)
    val wordPairs = WordPairResource.loadFromClasspath()
    val usernameGenerator =
        UsernameGenerator(
            words = wordPairs,
            reserved = reservedUsernames,
            history = usernameHistoryRepository,
            users = userRepository,
        )
    val inviteSecretBase64 =
        secrets.resolve("invite-code-secret")
            ?: error("Missing required secret 'invite-code-secret' (set INVITE_CODE_SECRET)")
    val inviteDeriver = InviteCodePrefixDeriver(Base64.getDecoder().decode(inviteSecretBase64))

    val jitterSecretBase64 =
        secrets.resolve("jitter-secret")
            ?: error("Missing required secret 'jitter-secret' (set JITTER_SECRET)")
    val jitterSecret = Base64.getDecoder().decode(jitterSecretBase64)
    require(jitterSecret.size == 32) {
        "jitter-secret must decode to 32 bytes, got ${jitterSecret.size}"
    }

    val contentLengthGuard: ContentLengthGuard = installContentLengthGuard()

    // ktorEnv is declared earlier (alongside the account-data-export substrate wiring, the
    // earliest secret-slot-deriving consumer) and reused by the moderation-pipeline
    // scaffolding directly below and the FCM init / chat-realtime wiring further down.

    // redisUrl is consumed by the moderation pipeline (Redis cache) AND further down
    // by the rate-limiter / probe wiring. Resolved once here so the same value backs
    // both. The conditional rate-limiter wiring later in this file reuses this
    // variable rather than re-resolving.
    val redisUrl = secrets.resolve("redis-url")

    // ---- Text-moderation pipeline (content-moderation-keyword-lists) ---------
    // Wired ABOVE the post / reply / chat services so they can take the
    // TextModerator as a constructor argument. The loader and moderator are
    // singletons; their internal Redis cache + Remote Config network costs are
    // amortized across all moderate(...) calls.
    //
    // Test profile binds a no-op Remote Config client + no-op Redis cache so the
    // moderator falls all the way through to the repo-file Tier 3 (the
    // `__seed_*_placeholder__` sentinels), producing Verdict.Allow on every call.
    // Production bootstraps the Firebase Admin SDK Remote Config FirebaseApp.
    // ONE Lettuce client (one Netty event-loop group, one Upstash connection)
    // backs the cache, rate limiter, AND probe — previously three *FromUrl
    // factories each built a private client per instance (docs/11 §3.3).
    val redisHandles = redisUrl?.let { redisHandlesFromUrl(it) }
    val redisStringCache: RedisStringCache =
        if (redisHandles != null) {
            redisHandles.stringCache
        } else {
            require(ktorEnv != "staging" && ktorEnv != "production") {
                "Required env var 'REDIS_URL' is unset (env=$ktorEnv) — Redis is a hard startup requirement"
            }
            NoOpRedisStringCache()
        }
    val remoteConfigClient: RemoteConfigClient =
        when (ktorEnv) {
            "test" ->
                // Test profile: stub client that always returns null. The loader cascades to
                // Tier 3 (repo file with the seed placeholder), producing Verdict.Allow on
                // any non-sentinel content. Tests that need specific Reject/Flag behavior
                // override the Koin binding via test modules.
                object : RemoteConfigClient {
                    override fun fetchStringList(parameterName: String): List<String>? = null

                    override fun fetchInt(parameterName: String): Int? = null

                    override fun fetchDouble(parameterName: String): Double? = null

                    override fun fetchBoolean(parameterName: String): Boolean? = null
                }
            else -> {
                // Reuse the firebase-admin-sa secret slot already consumed by :infra:fcm —
                // see openspec/specs/content-moderation-keyword-lists/spec.md
                // `### Requirement: :infra:remote-config is the sole owner of the Firebase
                // Remote Config Admin SDK` Scenario "resolves the service-account secret via
                // the precedent call shape". The secret was already validated in the FCM
                // boot path above; re-reading it here is fine because the resolver caches
                // env reads (and the secret value is identical).
                val rcSlot = secretKey(ktorEnv, "firebase-admin-sa")
                val rcSecret =
                    secrets.resolve("firebase-admin-sa")
                        ?: run {
                            org.slf4j.LoggerFactory.getLogger("id.nearyou.app.Application").error(
                                "event=remote_config_init_failed reason=missing_secret slot={} env={}",
                                rcSlot,
                                ktorEnv,
                            )
                            error(
                                "Required secret '$rcSlot' is unset (env=$ktorEnv) — " +
                                    "Firebase Remote Config requires the same firebase-admin-sa " +
                                    "service account as :infra:fcm.",
                            )
                        }
                try {
                    firebaseRemoteConfigClient(rcSecret)
                } catch (e: RemoteConfigInitException) {
                    org.slf4j.LoggerFactory.getLogger("id.nearyou.app.Application").error(
                        "event=remote_config_init_failed reason=parse_or_credential_error slot={} env={} message={}",
                        rcSlot,
                        ktorEnv,
                        e.message,
                        e,
                    )
                    throw e
                }
            }
        }
    val remoteConfigPublisher: RemoteConfigPublisher =
        when (ktorEnv) {
            "test" -> NoOpRemoteConfigPublisher
            else ->
                // Same firebase-admin-sa credential as the read client above; the
                // operator grants that SA the Remote Config write role (no new slot).
                // remoteConfigServerPublisher returns NoOp (panel renders read-only)
                // on a blank/unparseable secret rather than throwing — the read
                // client already fail-fasted on a truly-missing secret above.
                remoteConfigServerPublisher(secrets.resolve("firebase-admin-sa").orEmpty())
        }
    val moderationListLoader: ModerationListLoader =
        CachingModerationListLoader(
            redisCache = redisStringCache,
            remoteConfigClient = remoteConfigClient,
            secretResolver = secrets,
            env = ktorEnv,
        )
    val textModerator = TextModerator(loader = moderationListLoader)

    // Layer 3 (toxicity classifier — OpenAI Moderation API) async dispatch wiring
    // per the `text-moderation-perspective-api-layer` capability. The change name
    // retains the historical "perspective" branding from the original proposal;
    // the vendor pivoted to OpenAI Moderation mid-implementation when Perspective
    // announced sunset (end-of-2026). See proposal.md § Vendor-swap amendment.
    //
    // Test profile: bind `layer3Moderator = null` and a `forTest` scope. The
    // dispatch call sites in `CreatePostService` / `ReplyService` no-op on null
    // collaborators — Layer 1+2 still run, Layer 3 is opt-out. Production fail-fasts
    // on a missing API key (mirrors the firebase-admin-sa precedent).
    val layer3DispatcherScope: Layer3DispatcherScope = Layer3DispatcherScope.production()
    val layer3ConfigLoader: Layer3ConfigLoader =
        CachingLayer3ConfigLoader(
            redisCache = redisStringCache,
            remoteConfigClient = remoteConfigClient,
        )
    val layer3ModerationWriter: Layer3ModerationWriter = JdbcLayer3ModerationWriter(dataSource)
    val layer3Moderator: Layer3Moderator? =
        when (ktorEnv) {
            "test" -> null
            else -> {
                val openAiSecretSlot = secretKey(ktorEnv, "openai-api-key")
                val openAiApiKey =
                    secrets.resolve("openai-api-key")
                        ?: run {
                            org.slf4j.LoggerFactory.getLogger("id.nearyou.app.Application").error(
                                "event=layer3_init_failed reason=missing_secret slot={} env={}",
                                openAiSecretSlot,
                                ktorEnv,
                            )
                            error(
                                "Required secret '$openAiSecretSlot' is unset (env=$ktorEnv) — " +
                                    "OpenAI Moderation API is a hard startup requirement when ktor.environment != 'test'. " +
                                    "Verify GCP Secret Manager slot exists and is populated.",
                            )
                        }
                val moderationClient: ModerationClient = OpenAiModerationClient(apiKey = openAiApiKey)
                DefaultLayer3Moderator(
                    client = moderationClient,
                    configLoader = layer3ConfigLoader,
                    writer = layer3ModerationWriter,
                )
            }
        }

    // JVM shutdown hook for the Layer 3 dispatcher scope (per
    // `text-moderation-perspective-api-layer/spec.md` § dispatcher-scope shutdown
    // contract). Drains in-flight dispatches up to 5 seconds; cancelled dispatches
    // emit `event=layer3_dispatch_drain_exceeded`. New dispatches arriving after
    // shutdown emit `event=layer3_dispatch_after_shutdown` (silently no-op).
    Runtime.getRuntime().addShutdownHook(Thread { layer3DispatcherScope.shutdown() })

    val postRepository: PostRepository = JdbcPostRepository()
    // moderationQueueRepository is a singleton shared by createPostService (Layer 2
    // soft-flag) and reportService (V9 auto_hide_3_reports). Declared here near the
    // moderation pipeline scaffolding instead of further down with the report
    // wiring so both consumers reach it without forward references.
    val moderationQueueRepository: ModerationQueueRepository = JdbcModerationQueueRepository()
    // Shared by UsernameChangeService (consult/consume the one-shot per-candidate
    // approval) and, out-of-session, the admin-premium-username-oversight write path
    // (upsertApproval on accept). Stateless above the connection seam — one binding.
    val usernameFlagOverrideRepository: UsernameFlagOverrideRepository = JdbcUsernameFlagOverrideRepository()
    // createPostService is constructed AFTER the shared rateLimiter (below) so its
    // PostRateLimiter (docs/05 daily cap; 02-M2) rides the same Redis seam.
    val userBlockRepository: UserBlockRepository = JdbcUserBlockRepository(dataSource)
    // blockService is constructed AFTER the shared rateLimiter (below) so its
    // BlockRateLimiter rides the same Redis seam — see the social-graph wiring block.
    val notificationRepository: NotificationRepository = JdbcNotificationRepository(dataSource)
    val notificationEmitter: NotificationEmitter = DbNotificationEmitter(notificationRepository)
    val notificationService = NotificationService(notificationRepository, dbDispatchers.db)
    // account-data-export worker — wired here because it depends on notificationEmitter (the
    // durable `data_export_ready` channel). The user-facing repos/service were wired earlier.
    val dataExportWorker =
        DataExportWorker(
            dataSource = dataSource,
            requests = dataExportRequestRepository,
            gather = dataExportGatherRepository,
            archiveService = dataExportArchiveService,
            objectStore = objectStore,
            emailSender = emailSender,
            notificationEmitter = notificationEmitter,
            dbDispatcher = dbDispatchers.db,
        )
    val userFcmTokenReader: UserFcmTokenReader = JdbcUserFcmTokenReader(dataSource)
    val actorUsernameLookup: ActorUsernameLookup = JdbcActorUsernameLookup(dataSource)
    val inAppDispatcher: NotificationDispatcher = NoopNotificationDispatcher()

    // Per `fcm-push-dispatch` design D7: production fail-fasts on missing or
    // malformed `firebase-admin-sa`. Test profile binds `inAppDispatcher` only
    // (no FCM) — Cloud Run never sees the test branch because tests run
    // through a different ApplicationEngine entrypoint that overrides Koin.
    val fcmDispatcherScope: FcmDispatcherScope?
    val notificationDispatcher: NotificationDispatcher
    when (ktorEnv) {
        "test" -> {
            fcmDispatcherScope = null
            notificationDispatcher = inAppDispatcher
        }
        else -> {
            val secretSlot = secretKey(ktorEnv, "firebase-admin-sa")
            val secretValue =
                secrets.resolve("firebase-admin-sa")
                    ?: run {
                        org.slf4j.LoggerFactory.getLogger("id.nearyou.app.Application").error(
                            "event=fcm_init_failed reason=missing_secret slot={} env={}",
                            secretSlot,
                            ktorEnv,
                        )
                        error(
                            "Required secret '$secretSlot' is unset (env=$ktorEnv) — " +
                                "Firebase Admin SDK is a hard startup requirement when ktor.environment != 'test'. " +
                                "Verify GCP Secret Manager slot exists and is populated.",
                        )
                    }
            val composite =
                try {
                    buildFcmComposite(
                        serviceAccountJson = secretValue,
                        notificationRepository = notificationRepository,
                        userFcmTokenReader = userFcmTokenReader,
                        actorUsernameLookup = actorUsernameLookup,
                        inAppDispatcher = inAppDispatcher,
                    )
                } catch (e: FcmInitException) {
                    org.slf4j.LoggerFactory.getLogger("id.nearyou.app.Application").error(
                        "event=fcm_init_failed reason=parse_or_credential_error slot={} env={} message={}",
                        secretSlot,
                        ktorEnv,
                        e.message,
                        e,
                    )
                    throw e
                }
            // Shutdown hook: drain in-flight dispatches up to 5s, then close
            // the scope. New emits during shutdown observe a closed scope and
            // log WARN `event="fcm_dispatch_after_shutdown"` per spec.
            Runtime.getRuntime().addShutdownHook(Thread(composite.onShutdown))
            fcmDispatcherScope = composite.scope
            notificationDispatcher = composite.dispatcher
        }
    }
    val userFollowsRepository: UserFollowsRepository = JdbcUserFollowsRepository(dataSource)
    // followService is constructed AFTER the shared rateLimiter (below) so its
    // FollowRateLimiter rides the same Redis seam — see the social-graph wiring block.
    val userProfileReader: UserProfileReader = JdbcUserProfileReader(dataSource)
    val userProfileService = UserProfileService(userProfileReader, dbDispatchers.db)
    val postLikeRepository: PostLikeRepository = JdbcPostLikeRepository(dataSource)
    // Conditional Redis wiring (task 4.6 of like-rate-limit):
    //  - In staging/production: fail-fast on missing `REDIS_URL` env var — Redis is
    //    a hard dependency for the like rate limiter (per the spec, missing it is a
    //    deployment defect, not a runtime fallback).
    //  - In dev/test: if `secrets.resolve` returns null, bind a NoOpRateLimiter
    //    that always admits. Local dev that doesn't run Redis-via-compose still
    //    boots; tests that don't exercise the limiter still pass. Tests that DO
    //    need Redis inject `REDIS_URL` via `KotestProjectConfig.beforeProject()`
    //    (mirror of the Postgres bootstrap).
    //
    // Resolution: `secrets.resolve("redis-url")` reads env var `REDIS_URL` (per
    // EnvVarSecretResolver's name.uppercase().replace('-','_') convention).
    // Cloud Run injects the env var as `REDIS_URL=staging-redis-url:latest` per
    // deploy-staging.yml — the staging slot value is bound to the prod-style env
    // var name, matching how every other staging secret in this app resolves
    // (KTOR_RSA_PRIVATE_KEY, JITTER_SECRET, INVITE_CODE_SECRET, etc.). Earlier
    // versions used `secretKey(ktorEnv, "redis-url")` to compose the slot name
    // `staging-redis-url`, but that produced env var lookup `STAGING_REDIS_URL`
    // which Cloud Run never sets — first staging deploy of the like-rate-limit
    // change failed at startup with that exact mismatch.
    // `redisUrl` is resolved earlier in this file (above the moderation pipeline)
    // and reused here.
    val rateLimiter: RateLimiter =
        if (redisHandles != null) {
            // `:infra:redis` owns the Lettuce client lifecycle so `:backend:ktor`
            // never imports `io.lettuce.core.*` (the "no vendor SDK outside
            // :infra:*" invariant). The limiter shares the single RedisHandles
            // client built above. Process termination closes the underlying
            // Netty event loop; explicit shutdown is not needed for the V1
            // rollout (matches V9 ReportRateLimiter precedent). OTel Lettuce
            // tracing enabled by default via the factory.
            redisHandles.rateLimiter
        } else {
            require(ktorEnv != "staging" && ktorEnv != "production") {
                "Required env var 'REDIS_URL' is unset (env=$ktorEnv) — " +
                    "Redis is a hard startup requirement in staging and production. " +
                    "Verify deploy-staging.yml binds REDIS_URL=staging-redis-url:latest " +
                    "(or the prod equivalent) and that the GCP Secret Manager slot " +
                    "exists and is populated."
            }
            org.slf4j.LoggerFactory.getLogger("id.nearyou.app.Application").warn(
                "event=ratelimiter_noop_fallback env={} reason=redis_url_unset",
                ktorEnv,
            )
            NoOpRateLimiter()
        }

    // Health probes. RedisProbe is Redis-backed when REDIS_URL is present
    // (staging/prod always-on); a no-op probe reports ok=true in dev/test mode
    // when the rate-limiter falls back to NoOpRateLimiter — same always-admit
    // semantics, intentionally lying that Redis is healthy in dev so Application
    // boot succeeds without a running Redis container. Production paths require
    // REDIS_URL → Redis-backed probe by construction.
    val redisProbe: RedisProbe =
        if (redisHandles != null) {
            redisHandles.probe
        } else {
            object : RedisProbe {
                override suspend fun ping(timeout: java.time.Duration): ProbeResult = ProbeResult(ok = true, latencyMs = 0L, error = null)
            }
        }
    val supabaseProbe: SupabaseRealtimeProbe = KtorSupabaseRealtimeProbe(httpClient, supabaseUrl)
    val postgresProbe: PostgresProbe = JdbcPostgresProbe(dataSource)

    // Real Remote Config in staging/prod: the `search_enabled` kill switch +
    // `premium_*_cap_override` flags are now LIVE (StubRemoteConfig had stayed
    // bound after the Firebase Admin SDK client landed in-process for the
    // moderation pipeline, leaving them permanently inert). Test env keeps the
    // stub (null → call-site defaults). AUDIT-FLAGGED 2026-06-10: behavior
    // activation — see dev/audits/2026-06-10-holistic-audit/PROGRESS.md.
    val remoteConfig: RemoteConfig =
        when (ktorEnv) {
            "test" -> StubRemoteConfig()
            else -> RemoteConfigClientAdapter(remoteConfigClient)
        }
    // Post + social-graph services — constructed here (not at their repo
    // declarations above) because their mutation limiters (docs/05; 2026-06-10
    // audit, findings 02-M2 + 03-#3) wrap the shared Redis-backed rateLimiter
    // built just above.
    // premium-image-upload-pipeline — shared ledger repo, consumed by both the upload
    // service (insert) and the post-attach in CreatePostService (find + conditional flip).
    val imageUploadRepository = JdbcImageUploadRepository(dataSource)
    val createPostService =
        CreatePostService(
            dataSource = dataSource,
            posts = postRepository,
            contentGuard = contentLengthGuard,
            textModerator = textModerator,
            moderationQueue = moderationQueueRepository,
            jitterSecret = jitterSecret,
            layer3DispatcherScope = layer3DispatcherScope,
            layer3Moderator = layer3Moderator,
            rateLimiter = PostRateLimiter(rateLimiter),
            imageUploads = imageUploadRepository,
            dbDispatcher = dbDispatchers.db,
        )
    // premium-post-editing: PATCH /api/v1/posts/{post_id} + GET .../edits. Mirrors
    // createPostService wiring — same shared Redis-backed rateLimiter (a DISTINCT
    // PostEditRateLimiter scope key), the same Layer 3 scope/moderator + moderation
    // queue + content guard, and the bounded dbDispatcher.
    val postEditService =
        PostEditService(
            dataSource = dataSource,
            posts = postRepository,
            contentGuard = contentLengthGuard,
            textModerator = textModerator,
            moderationQueue = moderationQueueRepository,
            rateLimiter = PostEditRateLimiter(rateLimiter),
            remoteConfig = remoteConfig,
            layer3DispatcherScope = layer3DispatcherScope,
            layer3Moderator = layer3Moderator,
            dbDispatcher = dbDispatchers.db,
        )
    val postEditHistoryQuery = PostEditHistoryQuery(dataSource)
    val followService =
        FollowService(
            dataSource = dataSource,
            follows = userFollowsRepository,
            notifications = notificationEmitter,
            dispatcher = notificationDispatcher,
            rateLimiter = FollowRateLimiter(rateLimiter),
            dbDispatcher = dbDispatchers.db,
        )
    val blockService =
        BlockService(
            blocks = userBlockRepository,
            rateLimiter = BlockRateLimiter(rateLimiter),
            dbDispatcher = dbDispatchers.db,
        )
    val likeService =
        LikeService(
            dataSource = dataSource,
            likes = postLikeRepository,
            notifications = notificationEmitter,
            dispatcher = notificationDispatcher,
            rateLimiter = rateLimiter,
            remoteConfig = remoteConfig,
            dbDispatcher = dbDispatchers.db,
        )
    val subscriptionEventRepository = SubscriptionEventRepository()
    val subscriptionService =
        SubscriptionService(
            dataSource = dataSource,
            repository = subscriptionEventRepository,
            notifications = notificationEmitter,
            dispatcher = notificationDispatcher,
            dbDispatcher = dbDispatchers.db,
        )
    val referralGrantRepository = ReferralGrantRepository()
    val referralActivityCheckWorker =
        ReferralActivityCheckWorker(
            dataSource = dataSource,
            repository = referralGrantRepository,
            granter = referralEntitlementGranter(secrets.resolve(secretKey(ktorEnv, "revenuecat-secret-api-key"))),
            dbDispatcher = dbDispatchers.db,
        )
    val postReplyRepository: PostReplyRepository = JdbcPostReplyRepository(dataSource)
    val replyService =
        ReplyService(
            dataSource = dataSource,
            replies = postReplyRepository,
            notifications = notificationEmitter,
            dispatcher = notificationDispatcher,
            rateLimiter = rateLimiter,
            remoteConfig = remoteConfig,
            textModerator = textModerator,
            moderationQueue = moderationQueueRepository,
            layer3DispatcherScope = layer3DispatcherScope,
            layer3Moderator = layer3Moderator,
            dbDispatcher = dbDispatchers.db,
        )
    val chatRepository = ChatRepository(dataSource)
    val chatService =
        ChatService(
            repository = chatRepository,
            notifications = notificationEmitter,
            dispatcher = notificationDispatcher,
            rateLimiter = rateLimiter,
            remoteConfig = remoteConfig,
            textModerator = textModerator,
            moderationQueue = moderationQueueRepository,
            dbDispatcher = dbDispatchers.db,
        )
    // chat-realtime-broadcast wiring per design § D8 (extends `:infra:supabase`).
    // Test profile binds NoopChatRealtimeClient — local tests have no real Supabase
    // project. Non-test profiles fail-fast on missing `supabase-service-role-key`
    // (matches the FCM `firebase-admin-sa` precedent: deploy-time secret-slot
    // misconfiguration surfaces here, NOT silently downgrades to a no-op).
    val chatRealtimeClient: ChatRealtimeClient =
        when (ktorEnv) {
            "test" -> NoopChatRealtimeClient()
            else -> {
                val slot = secretKey(ktorEnv, "supabase-service-role-key")
                val key =
                    secrets.resolve("supabase-service-role-key")
                        ?: run {
                            org.slf4j.LoggerFactory.getLogger("id.nearyou.app.Application").error(
                                "event=chat_realtime_broadcast_init_failed reason=missing_secret slot={} env={}",
                                slot,
                                ktorEnv,
                            )
                            error(
                                "Required secret '$slot' is unset (env=$ktorEnv) — " +
                                    "Supabase service role key is a hard startup requirement when ktor.environment != 'test'. " +
                                    "Verify GCP Secret Manager slot exists and is populated.",
                            )
                        }
                // Per spec § "Supabase Realtime broadcast publish carries `traceparent`":
                // pass an OTel-instrumented HttpClient so the publish HTTP request
                // carries `traceparent` populated from the active span context.
                // Replicates SupabaseBroadcastChatClient.defaultHttpClient settings
                // (`expectSuccess=false`, 500ms-per-attempt HttpTimeout) since
                // overriding the constructor's default client opts out of the
                // default. The retry loop's backoff schedule is unchanged.
                SupabaseBroadcastChatClient(
                    projectUrl = supabaseUrl,
                    serviceRoleKey = key,
                    httpClient =
                        httpClientWithOtel {
                            expectSuccess = false
                            install(HttpTimeout) {
                                requestTimeoutMillis = 500L
                                connectTimeoutMillis = 500L
                                socketTimeoutMillis = 500L
                            }
                        },
                )
            }
        }
    val postsTimelineRepository: PostsTimelineRepository = JdbcPostsTimelineRepository(dataSource)
    val nearbyTimelineService = NearbyTimelineService(postsTimelineRepository, dbDispatchers.db)
    val postsFollowingRepository: PostsFollowingRepository = JdbcPostsFollowingRepository(dataSource)
    val followingTimelineService = FollowingTimelineService(postsFollowingRepository, dbDispatchers.db)
    val postsGlobalRepository: PostsGlobalRepository = JdbcPostsGlobalRepository(dataSource)
    val singlePostRepository: SinglePostRepository = JdbcSinglePostRepository(dataSource)
    val postReadService = PostReadService(singlePostRepository, dbDispatchers.db)
    val globalTimelineService = GlobalTimelineService(postsGlobalRepository, dbDispatchers.db)
    // Per `timeline-read-rate-limit` capability: shared across all three timeline
    // routes. Stateless above the Redis seam, so a single Koin binding suffices.
    val timelineReadRateLimiter = TimelineReadRateLimiter(rateLimiter, dbDispatchers.db)
    val reportRepository: ReportRepository = JdbcReportRepository()
    val postAutoHideRepository: PostAutoHideRepository = JdbcPostAutoHideRepository()
    // Wrap the shared `rateLimiter` (Redis or NoOp/InMemory fallback per the
    // env-aware wiring above) so V9's ReportRateLimiter surface (cap / window /
    // keyFor / Outcome) keeps working byte-for-byte. Section 7 of like-rate-limit:
    // the in-process ConcurrentHashMap that V9 shipped is now the
    // InMemoryRateLimiter test double; production routes through Redis.
    val reportRateLimiter = ReportRateLimiter(rateLimiter = rateLimiter)
    val searchRateLimiter = SearchRateLimiter(rateLimiter = rateLimiter)
    val searchRepository: SearchRepository = JdbcSearchRepository(dataSource)
    val searchService =
        SearchService(
            repository = searchRepository,
            rateLimiter = searchRateLimiter,
            remoteConfig = remoteConfig,
            dbDispatcher = dbDispatchers.db,
        )
    // premium-image-upload-pipeline — fail-soft infra (Vision + Cloudflare Images return
    // NoOp when their secret slots are unset; the endpoint then 503s and the feature stays
    // dark behind the default-FALSE image_upload_enabled flag). Delivery base is env-derived.
    val imageUploadService =
        ImageUploadService(
            repository = imageUploadRepository,
            flagGate = ImageUploadFlagGate(redisStringCache, remoteConfig),
            rateLimiter = ImageUploadRateLimiter(rateLimiter),
            moderator = imageModerator(secrets.resolve(secretKey(ktorEnv, "gcp-vision-sa"))),
            store =
                imageStore(
                    CloudflareImagesConfig(
                        apiToken = secrets.resolve(secretKey(ktorEnv, "cloudflare-images-api-token")).orEmpty(),
                        accountId = secrets.resolve(secretKey(ktorEnv, "cloudflare-images-account-id")).orEmpty(),
                        accountHash = secrets.resolve(secretKey(ktorEnv, "cloudflare-images-account-hash")).orEmpty(),
                        deliveryBaseUrl = if (ktorEnv == "staging") "https://img-staging.nearyou.id" else "https://img.nearyou.id",
                    ),
                ),
            remoteConfig = remoteConfig,
            dbDispatcher = dbDispatchers.db,
        )
    val usernameChangeService =
        UsernameChangeService(
            dataSource = dataSource,
            users = userRepository,
            reserved = reservedUsernames,
            history = usernameHistoryRepository,
            moderationQueue = moderationQueueRepository,
            flagOverrides = usernameFlagOverrideRepository,
            textModerator = textModerator,
            notificationEmitter = notificationEmitter,
            remoteConfig = remoteConfig,
            rateLimiter = UsernameRateLimiter(rateLimiter = rateLimiter),
            dbDispatcher = dbDispatchers.db,
        )
    val reportService =
        ReportService(
            dataSource = dataSource,
            reports = reportRepository,
            moderationQueue = moderationQueueRepository,
            postAutoHide = postAutoHideRepository,
            rateLimiter = reportRateLimiter,
            notifications = notificationEmitter,
            dispatcher = notificationDispatcher,
        )
    val fcmTokenRepository = FcmTokenRepository(dataSource, dbDispatchers.db)
    val consentRepository = ConsentRepository(dataSource, dbDispatchers.db)
    val hideDistanceRepository = HideDistanceRepository(dataSource, dbDispatchers.db)
    val referralRepository = ReferralRepository(dataSource)
    val referralService =
        ReferralService(
            users = userRepository,
            referrals = referralRepository,
            rateLimiter = ReferralTicketRateLimiter(rateLimiter = rateLimiter),
            dbDispatcher = dbDispatchers.db,
        )
    val signupService =
        SignupService(
            dataSource = dataSource,
            providers = SignupService.SignupProviders(google = googleVerifier, apple = appleVerifier),
            users = userRepository,
            rejected = rejectedIdentifiers,
            usernameGenerator = usernameGenerator,
            inviteDeriver = inviteDeriver,
            refreshTokens = refreshTokenService,
            jwtIssuer = jwtIssuer,
            referral = referralService,
        )

    install(Koin) {
        slf4jLogger()
        modules(
            module {
                single<DataSource> { dataSource }
                single { dbDispatchers }
                single<SecretResolver> { secrets }
                single { rsaKeys }
                single { jwtIssuer }
                single<UserRepository> { userRepository }
                single<RefreshTokenRepository> { refreshTokenRepository }
                single<ReservedUsernameRepository> { reservedUsernames }
                single<RejectedIdentifierRepository> { rejectedIdentifiers }
                single { wordPairs }
                single { usernameGenerator }
                single { inviteDeriver }
                single { refreshTokenService }
                single { signupService }
                single { contentLengthGuard }
                single<PostRepository> { postRepository }
                single { createPostService }
                single<UserBlockRepository> { userBlockRepository }
                single { blockService }
                single<UserFollowsRepository> { userFollowsRepository }
                single { followService }
                single<UserProfileReader> { userProfileReader }
                single { userProfileService }
                single<PostLikeRepository> { postLikeRepository }
                single<RateLimiter> { rateLimiter }
                single<PostgresProbe> { postgresProbe }
                single<RedisProbe> { redisProbe }
                single<SupabaseRealtimeProbe> { supabaseProbe }
                single<RemoteConfig> { remoteConfig }
                single<RemoteConfigClient> { remoteConfigClient }
                single<RedisStringCache> { redisStringCache }
                single<ModerationListLoader> { moderationListLoader }
                single { textModerator }
                single { likeService }
                single<PostReplyRepository> { postReplyRepository }
                single { replyService }
                single { subscriptionEventRepository }
                single { subscriptionService }
                single<PostsTimelineRepository> { postsTimelineRepository }
                single { nearbyTimelineService }
                single<PostsFollowingRepository> { postsFollowingRepository }
                single { followingTimelineService }
                single<PostsGlobalRepository> { postsGlobalRepository }
                single { globalTimelineService }
                single { timelineReadRateLimiter }
                single<ReportRepository> { reportRepository }
                single<ModerationQueueRepository> { moderationQueueRepository }
                single<UsernameFlagOverrideRepository> { usernameFlagOverrideRepository }
                single<PostAutoHideRepository> { postAutoHideRepository }
                single { reportRateLimiter }
                single { reportService }
                single<SearchRepository> { searchRepository }
                single { searchRateLimiter }
                single { searchService }
                single<NotificationRepository> { notificationRepository }
                single<NotificationDispatcher> { notificationDispatcher }
                single<NotificationEmitter> { notificationEmitter }
                single { notificationService }
                single<UserFcmTokenReader> { userFcmTokenReader }
                single<ActorUsernameLookup> { actorUsernameLookup }
                single { fcmTokenRepository }
                single { consentRepository }
                single { hideDistanceRepository }
                single<OidcTokenVerifier> { oidcTokenVerifier }
                single { suspensionUnbanWorker }
                single { accountDeletionRepository }
                single { accountDeletionService }
                single { accountHardDeleteWorker }
                single<ObjectStore> { objectStore }
                single<EmailSender> { emailSender }
                single { peerIdHasher }
                single { dataExportRequestRepository }
                single { dataExportGatherRepository }
                single { dataExportArchiveService }
                single { dataExportService }
                single { dataExportWorker }
                single { retentionCleanupWorker }
            },
        )
    }

    installAuth(rsaKeys, userRepository, dbDispatcher = dbDispatchers.db)

    jwksRoutes()
    healthRoutes()
    authRoutes(Providers(googleVerifier, appleVerifier), userRepository, refreshTokenService, jwtIssuer, loginEventRecorder)
    signupRoutes(signupService)
    realtimeRoutes(realtimeIssuer)
    appleS2SRoutes(appleJwks, appleAudiences, userRepository, InMemoryDedup())
    revenueCatWebhookRoutes(subscriptionService, secrets, ktorEnv)

    // --- CSAM detection (csam-detection capability) ---
    // The AES key for `csam_detection_archive.encrypted_metadata` is FAIL-SOFT: the
    // slot is operator-provisioned at the Month-6 image launch, so a missing key
    // degrades encryption to a no-op (the safety-critical takedown is never blocked).
    // The admin-session repos reuse the admin-auth seam (stateless above the connection
    // seam, safe to re-instantiate — AdminModule precedent) for the admin-internal path.
    val csamRepository = CsamRepository(dataSource)
    val csamMetadataEncryptor =
        CsamMetadataEncryptor { secrets.resolve("csam-archive-aes-key")?.let { Base64.getDecoder().decode(it) } }
    val csamDetectionService =
        CsamDetectionService(
            dataSource = dataSource,
            dbDispatcher = dbDispatchers.db,
            csamRepository = csamRepository,
            moderationQueueRepository = moderationQueueRepository,
            encryptor = csamMetadataEncryptor,
            auditLogger = AdminAuditLogger(dataSource),
        )
    csamWebhookRoutes(
        service = csamDetectionService,
        sessionRepository = SessionRepository(dataSource),
        adminUserRepository = AdminUserRepository(dataSource),
        rateLimiter = rateLimiter,
        secrets = secrets,
        env = ktorEnv,
    )
    postRoutes(createPostService)
    imageRoutes(imageUploadService)
    singlePostRoutes(postReadService)
    postEditRoutes(postEditService, postEditHistoryQuery)
    blockRoutes(blockService)
    followRoutes(followService)
    userSocialRoutes(followService)
    userProfileRoutes(userProfileService)
    likeRoutes(likeService)
    replyRoutes(replyService, contentLengthGuard)
    chatRoutes(chatService, contentLengthGuard, chatRealtimeClient)
    timelineRoutes(nearbyTimelineService, timelineReadRateLimiter)
    followingTimelineRoutes(followingTimelineService, timelineReadRateLimiter)
    globalTimelineRoutes(globalTimelineService, timelineReadRateLimiter)
    reportRoutes(reportService)
    searchRoutes(searchService)
    userUsernameRoutes(usernameChangeService)
    notificationRoutes(notificationService)
    fcmTokenRoutes(fcmTokenRepository)
    consentRoutes(consentRepository)
    accountRoutes(accountDeletionService)
    accountDataExportRoutes(dataExportService)
    hideDistanceRoutes(hideDistanceRepository)

    // /internal/* — Cloud-Scheduler-invoked job endpoints. The OIDC gate is
    // installed PER JOB SUBTREE inside unbanWorkerRoute (internal-endpoint-auth
    // spec scenario: "mounted on /internal/unban-worker") — NEVER on this shared
    // /internal node: Ktor merges identical path segments across separate
    // routing blocks, so a plugin here would ALSO gate the vendor-auth Apple S2S
    // webhook at /internal/apple/s2s-notifications (Apple sends no Google-OIDC
    // bearer → every notification would 401 before its signed-payload
    // verification ran). Regression guard: InternalRoutingIsolationTest.
    routing {
        route("/internal") {
            unbanWorkerRoute(suspensionUnbanWorker, oidcTokenVerifier)
            privacyFlipWorkerRoute(privacyFlipWorker, oidcTokenVerifier)
            accountHardDeleteWorkerRoute(accountHardDeleteWorker, oidcTokenVerifier)
            referralActivityCheckRoute(referralActivityCheckWorker, oidcTokenVerifier)
            csamArchivePurgeRoute(csamDetectionService, oidcTokenVerifier)
            dataExportWorkerRoute(dataExportWorker, oidcTokenVerifier)
            retentionCleanupRoutes(retentionCleanupWorker, oidcTokenVerifier)
        }
    }

    // /admin/ — admin panel route subtree. Cookie-based session + CSRF
    // gate per admin-login-argon2-totp (Admin #3). Mounted via an
    // Application extension function so the eventual extraction to a
    // separate Cloud Run service for admin.nearyou.id (per
    // docs/07-Operations.md § Stack) is mechanical.
    //
    // AES key for `admin_users.totp_secret_encrypted` decryption is
    // sourced lazily — the lambda is only invoked at login-verify time,
    // so a missing secret slot fails the FIRST login attempt with a
    // clear error but does NOT block app boot. Once provisioned, the
    // secret resolution is cached at the slot lookup level by
    // EnvVarSecretResolver behavior (env vars don't change at runtime).
    admin(
        dataSource = dataSource,
        aesKeyProvider = {
            // secretKey() computes the env-namespaced SLOT name for
            // diagnostics; secrets.resolve() takes the UN-prefixed logical
            // name (→ env var ADMIN_TOTP_SECRET_AES_KEY, which the deploy
            // populates from the staging-/prod- slot) — same shape as the
            // firebase-admin-sa precedent above.
            val slot = secretKey(ktorEnv, "admin-totp-secret-aes-key")
            val base64 =
                secrets.resolve("admin-totp-secret-aes-key")
                    ?: error("Missing required secret '$slot' (set ADMIN_TOTP_SECRET_AES_KEY)")
            Base64.getDecoder().decode(base64)
        },
        csrfHmacKeyProvider = {
            // Server-side HMAC key for the Signed Double-Submit CSRF token
            // (HashUtil.deriveCsrfFromSessionToken). Distinct slot from the
            // TOTP AES key (key separation). Lazy — resolved at login/render
            // time, so a missing slot fails the first admin page load with a
            // clear error but does NOT block app boot.
            val slot = secretKey(ktorEnv, "admin-csrf-hmac-key")
            val base64 =
                secrets.resolve("admin-csrf-hmac-key")
                    ?: error("Missing required secret '$slot' (set ADMIN_CSRF_HMAC_KEY)")
            Base64.getDecoder().decode(base64)
        },
        environmentName = ktorEnv,
        remoteConfigPublisher = remoteConfigPublisher,
        // Pass the SAME override-store instance the username-change gate uses, so
        // the admin "accept" approvals and the live consult/consume share one repo
        // (admin-premium-username-oversight Decision 2).
        usernameFlagOverrideRepository = usernameFlagOverrideRepository,
    )

    // Boot-time moderation-list prime (per `### Requirement: Boot-time loader prime
    // exercises Tier 3 fallback per list`). Fires once each for ProfanityList +
    // UuIteList in a non-blocking coroutine. Primes the Redis cache so first-traffic
    // requests don't pay the Tier 2/3/4 cascade cost AND surfaces a pre-traffic
    // Sentry WARN if Tier 3 (the repo `*.default.txt` files) is missing or empty.
    // The prime is fire-and-forget; cascade failures land in Sentry but never block
    // startup (fail-open posture per design.md D6).
    if (ktorEnv != "test") {
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                moderationListLoader.load(ModerationList.ProfanityList)
                moderationListLoader.load(ModerationList.UuIteList)
                org.slf4j.LoggerFactory.getLogger("id.nearyou.app.Application").info(
                    "event=moderation_loader_boot_prime_complete env={}",
                    ktorEnv,
                )
            } catch (t: Throwable) {
                org.slf4j.LoggerFactory.getLogger("id.nearyou.app.Application").warn(
                    "event=moderation_loader_boot_prime_failed reason={} env={}",
                    t.javaClass.simpleName,
                    ktorEnv,
                )
            }
        }
    }
}

/**
 * Reads `oidc.internalAudience` from the Ktor application config and validates
 * the value. Throws `IllegalStateException` (via `error(...)`) if the property
 * is absent or blank, and `IllegalArgumentException` (via `require(...)`) if
 * the value is not a syntactically valid URL. Both cause boot to fail-fast
 * before the HTTP server starts so Cloud Run's startup probe rejects the new
 * revision (5.3 / spec § "Configured audience is required at boot").
 *
 * Exposed as `internal` so tests can exercise the validation rules directly
 * without booting the full application graph.
 */
internal fun resolveInternalOidcAudience(config: io.ktor.server.config.ApplicationConfig): String {
    val raw =
        config.propertyOrNull("oidc.internalAudience")?.getString()?.takeIf { it.isNotBlank() }
            ?: error("Missing required config oidc.internalAudience (set INTERNAL_OIDC_AUDIENCE)")
    require(isLikelyUrl(raw)) {
        "Config oidc.internalAudience must be a syntactically valid URL (was '$raw')"
    }
    return raw
}

/**
 * Loose syntactic URL check used by the boot-time fail-fast guard for
 * `oidc.internalAudience`. We don't validate the value resolves DNS — that's a
 * runtime concern and would couple boot to network availability. Just confirm
 * it's a parseable URL with a non-empty scheme + host.
 */
private fun isLikelyUrl(value: String): Boolean =
    runCatching {
        val parsed = java.net.URI(value)
        !parsed.scheme.isNullOrBlank() && !parsed.host.isNullOrBlank()
    }.getOrElse { false }

private fun Application.csvAudiences(key: String): Set<String> =
    environment.config.propertyOrNull(key)?.getString()?.takeIf { it.isNotBlank() }
        ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        ?: emptySet()
