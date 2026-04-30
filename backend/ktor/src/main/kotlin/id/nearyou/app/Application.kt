package id.nearyou.app

import id.nearyou.app.admin.SuspensionUnbanWorker
import id.nearyou.app.admin.unbanWorkerRoute
import id.nearyou.app.auth.installAuth
import id.nearyou.app.auth.jwks.jwksRoutes
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
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
import id.nearyou.app.auth.signup.NoopUsernameHistoryRepository
import id.nearyou.app.auth.signup.SignupService
import id.nearyou.app.auth.signup.UsernameGenerator
import id.nearyou.app.auth.signup.WordPairResource
import id.nearyou.app.auth.signup.signupRoutes
import id.nearyou.app.block.BlockService
import id.nearyou.app.block.blockRoutes
import id.nearyou.app.chat.ChatRepository
import id.nearyou.app.chat.ChatService
import id.nearyou.app.chat.chatRoutes
import id.nearyou.app.common.ClientIpExtractorPlugin
import id.nearyou.app.config.EnvVarSecretResolver
import id.nearyou.app.config.RemoteConfig
import id.nearyou.app.config.SecretResolver
import id.nearyou.app.config.StubRemoteConfig
import id.nearyou.app.config.secretKey
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
import id.nearyou.app.follow.FollowService
import id.nearyou.app.follow.followRoutes
import id.nearyou.app.follow.userSocialRoutes
import id.nearyou.app.guard.ContentEmptyException
import id.nearyou.app.guard.ContentLengthGuard
import id.nearyou.app.guard.ContentTooLongException
import id.nearyou.app.guard.installContentLengthGuard
import id.nearyou.app.health.JdbcPostgresProbe
import id.nearyou.app.health.KtorSupabaseRealtimeProbe
import id.nearyou.app.health.healthRoutes
import id.nearyou.app.infra.db.DataSourceFactory
import id.nearyou.app.infra.db.DbConfig
import id.nearyou.app.infra.fcm.FcmDispatcherScope
import id.nearyou.app.infra.fcm.FcmInitException
import id.nearyou.app.infra.fcm.buildFcmComposite
import id.nearyou.app.infra.oidc.GoogleOidcTokenVerifier
import id.nearyou.app.infra.oidc.googleJwkProvider
import id.nearyou.app.infra.redis.NoOpRateLimiter
import id.nearyou.app.infra.redis.lettuceRedisProbeFromUrl
import id.nearyou.app.infra.redis.redisRateLimiterFromUrl
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
import id.nearyou.app.infra.repo.JdbcUserBlockRepository
import id.nearyou.app.infra.repo.JdbcUserFollowsRepository
import id.nearyou.app.infra.repo.JdbcUserRepository
import id.nearyou.app.infra.repo.PostRepository
import id.nearyou.app.infra.repo.PostsFollowingRepository
import id.nearyou.app.infra.repo.PostsGlobalRepository
import id.nearyou.app.infra.repo.PostsTimelineRepository
import id.nearyou.app.infra.repo.RefreshTokenRepository
import id.nearyou.app.infra.repo.RejectedIdentifierRepository
import id.nearyou.app.infra.repo.ReservedUsernameRepository
import id.nearyou.app.infra.repo.UserBlockRepository
import id.nearyou.app.infra.repo.UserRepository
import id.nearyou.app.internal.InternalEndpointAuth
import id.nearyou.app.moderation.ReportRateLimiter
import id.nearyou.app.moderation.ReportService
import id.nearyou.app.moderation.reportRoutes
import id.nearyou.app.notifications.DbNotificationEmitter
import id.nearyou.app.notifications.NoopNotificationDispatcher
import id.nearyou.app.notifications.NotificationEmitter
import id.nearyou.app.notifications.NotificationService
import id.nearyou.app.notifications.notificationRoutes
import id.nearyou.app.post.CreatePostService
import id.nearyou.app.post.LocationOutOfBoundsException
import id.nearyou.app.post.postRoutes
import id.nearyou.app.search.SearchRateLimiter
import id.nearyou.app.search.SearchService
import id.nearyou.app.search.searchRoutes
import id.nearyou.app.timeline.FollowingTimelineService
import id.nearyou.app.timeline.GlobalTimelineService
import id.nearyou.app.timeline.NearbyTimelineService
import id.nearyou.app.timeline.followingTimelineRoutes
import id.nearyou.app.timeline.globalTimelineRoutes
import id.nearyou.app.timeline.timelineRoutes
import id.nearyou.app.user.FcmTokenRepository
import id.nearyou.app.user.JdbcActorUsernameLookup
import id.nearyou.app.user.JdbcUserFcmTokenReader
import id.nearyou.app.user.fcmTokenRoutes
import id.nearyou.data.repository.ActorUsernameLookup
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
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import java.util.Base64
import javax.sql.DataSource

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    // ClientIpExtractor MUST run before auth, rate-limit, and any business handler.
    // It populates `call.clientIp` via the canonical CF-Connecting-IP →
    // XFF-first → remoteHost ladder. Direct `X-Forwarded-For` reads outside
    // ClientIpExtractor.kt are forbidden by the `RawXForwardedForRule` Detekt rule.
    install(ClientIpExtractorPlugin)

    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            },
        )
    }

    install(CallLogging)

    install(StatusPages) {
        exception<ContentEmptyException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf(
                    "error" to
                        mapOf(
                            "code" to "content_empty",
                            "message" to "Content is required.",
                        ),
                ),
            )
        }
        exception<ContentTooLongException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf(
                    "error" to
                        mapOf(
                            "code" to "content_too_long",
                            "message" to "Content exceeds the maximum of ${cause.limit} characters.",
                        ),
                ),
            )
        }
        exception<LocationOutOfBoundsException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf(
                    "error" to
                        mapOf(
                            "code" to "location_out_of_bounds",
                            "message" to "Coordinate is outside the supported region.",
                        ),
                ),
            )
        }
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf(
                    "error" to
                        mapOf(
                            "code" to "internal_error",
                            "message" to (cause.message ?: "Internal server error"),
                        ),
                ),
            )
        }
    }

    val dbConfig =
        DbConfig(
            url = environment.config.property("db.url").getString(),
            user = environment.config.property("db.user").getString(),
            password = environment.config.property("db.password").getString(),
            maxPoolSize = environment.config.propertyOrNull("db.maxPoolSize")?.getString()?.toInt() ?: 20,
        )
    val dataSource: DataSource = DataSourceFactory.create(dbConfig)

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
        // Clears any failed entries + realigns checksums so a previously-broken
        // migration can be retried after the SQL is fixed. No-op on a clean DB.
        flyway.repair()
        flyway.migrate()
    }

    val secrets: SecretResolver = EnvVarSecretResolver()
    val rsaPem =
        environment.config.propertyOrNull("auth.rsaPrivateKey")?.getString()
            ?.takeIf { it.isNotBlank() }
            ?: error("Missing required config auth.rsaPrivateKey (set KTOR_RSA_PRIVATE_KEY)")
    val rsaKeys = RsaKeyLoader(rsaPem)
    val jwtIssuer = JwtIssuer(rsaKeys)

    val userRepository: UserRepository = JdbcUserRepository(dataSource)
    val refreshTokenRepository: RefreshTokenRepository = JdbcRefreshTokenRepository(dataSource)
    val refreshTokenService = RefreshTokenService(refreshTokenRepository, userRepository)

    val httpClient = HttpClient(CIO)
    val googleJwksUrl =
        environment.config.propertyOrNull("auth.google.jwksUrl")?.getString()?.takeIf { it.isNotBlank() }
            ?: GOOGLE_JWKS_URL_DEFAULT
    val appleJwksUrl =
        environment.config.propertyOrNull("auth.apple.jwksUrl")?.getString()?.takeIf { it.isNotBlank() }
            ?: APPLE_JWKS_URL_DEFAULT

    val googleAudiences = csvAudiences("auth.google.audiences")
    val appleAudiences = csvAudiences("auth.apple.audiences")

    val googleVerifier = GoogleIdTokenVerifier(JwksCache(httpClient, googleJwksUrl), googleAudiences)
    val appleVerifier = AppleIdTokenVerifier(JwksCache(httpClient, appleJwksUrl), appleAudiences)
    val appleS2SJwks = JwksCache(httpClient, appleJwksUrl)

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

    val reservedUsernames: ReservedUsernameRepository = JdbcReservedUsernameRepository(dataSource)
    val rejectedIdentifiers: RejectedIdentifierRepository = JdbcRejectedIdentifierRepository(dataSource)
    val wordPairs = WordPairResource.loadFromClasspath()
    val usernameGenerator =
        UsernameGenerator(
            words = wordPairs,
            reserved = reservedUsernames,
            history = NoopUsernameHistoryRepository(),
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

    val postRepository: PostRepository = JdbcPostRepository()
    val createPostService =
        CreatePostService(
            dataSource = dataSource,
            posts = postRepository,
            contentGuard = contentLengthGuard,
            jitterSecret = jitterSecret,
        )
    val userBlockRepository: UserBlockRepository = JdbcUserBlockRepository(dataSource)
    val blockService = BlockService(userBlockRepository)
    val ktorEnv = environment.config.propertyOrNull("ktor.environment")?.getString() ?: "production"
    val notificationRepository: NotificationRepository = JdbcNotificationRepository(dataSource)
    val notificationEmitter: NotificationEmitter = DbNotificationEmitter(notificationRepository)
    val notificationService = NotificationService(notificationRepository)
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
    val followService =
        FollowService(dataSource, userFollowsRepository, notificationEmitter, notificationDispatcher)
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
    val redisUrl = secrets.resolve("redis-url")
    val rateLimiter: RateLimiter =
        if (redisUrl != null) {
            // The factory in `:infra:redis` owns the Lettuce client lifecycle so
            // `:backend:ktor` does not need to import `io.lettuce.core.*` (would
            // violate the "no vendor SDK outside :infra:*" invariant). Process
            // termination closes the underlying Netty event loop; explicit
            // shutdown is not needed for the V1 rollout (matches V9
            // ReportRateLimiter precedent).
            redisRateLimiterFromUrl(redisUrl)
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
        if (redisUrl != null) {
            lettuceRedisProbeFromUrl(redisUrl)
        } else {
            object : RedisProbe {
                override suspend fun ping(timeout: java.time.Duration): ProbeResult = ProbeResult(ok = true, latencyMs = 0L, error = null)
            }
        }
    val supabaseProbe: SupabaseRealtimeProbe = KtorSupabaseRealtimeProbe(httpClient, supabaseUrl)
    val postgresProbe: PostgresProbe = JdbcPostgresProbe(dataSource)

    val remoteConfig: RemoteConfig = StubRemoteConfig()
    val likeService =
        LikeService(
            dataSource = dataSource,
            likes = postLikeRepository,
            notifications = notificationEmitter,
            dispatcher = notificationDispatcher,
            rateLimiter = rateLimiter,
            remoteConfig = remoteConfig,
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
        )
    val chatRepository = ChatRepository(dataSource)
    val chatService =
        ChatService(
            repository = chatRepository,
            rateLimiter = rateLimiter,
            remoteConfig = remoteConfig,
        )
    val postsTimelineRepository: PostsTimelineRepository = JdbcPostsTimelineRepository(dataSource)
    val nearbyTimelineService = NearbyTimelineService(postsTimelineRepository)
    val postsFollowingRepository: PostsFollowingRepository = JdbcPostsFollowingRepository(dataSource)
    val followingTimelineService = FollowingTimelineService(postsFollowingRepository)
    val postsGlobalRepository: PostsGlobalRepository = JdbcPostsGlobalRepository(dataSource)
    val globalTimelineService = GlobalTimelineService(postsGlobalRepository)
    val reportRepository: ReportRepository = JdbcReportRepository()
    val moderationQueueRepository: ModerationQueueRepository = JdbcModerationQueueRepository()
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
    val fcmTokenRepository = FcmTokenRepository(dataSource)
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
        )

    install(Koin) {
        slf4jLogger()
        modules(
            module {
                single<DataSource> { dataSource }
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
                single<PostLikeRepository> { postLikeRepository }
                single<RateLimiter> { rateLimiter }
                single<PostgresProbe> { postgresProbe }
                single<RedisProbe> { redisProbe }
                single<SupabaseRealtimeProbe> { supabaseProbe }
                single<RemoteConfig> { remoteConfig }
                single { likeService }
                single<PostReplyRepository> { postReplyRepository }
                single { replyService }
                single<PostsTimelineRepository> { postsTimelineRepository }
                single { nearbyTimelineService }
                single<PostsFollowingRepository> { postsFollowingRepository }
                single { followingTimelineService }
                single<PostsGlobalRepository> { postsGlobalRepository }
                single { globalTimelineService }
                single<ReportRepository> { reportRepository }
                single<ModerationQueueRepository> { moderationQueueRepository }
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
                single<OidcTokenVerifier> { oidcTokenVerifier }
                single { suspensionUnbanWorker }
            },
        )
    }

    installAuth(rsaKeys, userRepository)

    jwksRoutes()
    healthRoutes()
    authRoutes(Providers(googleVerifier, appleVerifier), userRepository, refreshTokenService, jwtIssuer)
    signupRoutes(signupService)
    realtimeRoutes(realtimeIssuer)
    appleS2SRoutes(appleS2SJwks, appleAudiences, userRepository, InMemoryDedup())
    postRoutes(createPostService)
    blockRoutes(blockService)
    followRoutes(followService)
    userSocialRoutes(followService)
    likeRoutes(likeService)
    replyRoutes(replyService, contentLengthGuard)
    chatRoutes(chatService, contentLengthGuard)
    timelineRoutes(nearbyTimelineService)
    followingTimelineRoutes(followingTimelineService)
    globalTimelineRoutes(globalTimelineService)
    reportRoutes(reportService)
    searchRoutes(searchService)
    notificationRoutes(notificationService)
    fcmTokenRoutes(fcmTokenRepository)

    // /internal/* — Cloud-Scheduler-invoked endpoints. The OIDC plugin is mounted
    // here exactly once on the route subtree, so every nested route inherits OIDC
    // verification by default. Vendor-webhook endpoints (revenuecat-webhook,
    // csam-webhook) MUST live in a sibling route block that does NOT install
    // InternalEndpointAuth — see the internal-endpoint-auth capability spec.
    routing {
        route("/internal") {
            install(InternalEndpointAuth) { verifier = oidcTokenVerifier }
            unbanWorkerRoute(suspensionUnbanWorker)
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
