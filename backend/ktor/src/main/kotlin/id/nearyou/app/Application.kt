package id.nearyou.app

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
import id.nearyou.app.config.EnvVarSecretResolver
import id.nearyou.app.config.SecretResolver
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
import id.nearyou.app.health.healthRoutes
import id.nearyou.app.infra.db.DataSourceFactory
import id.nearyou.app.infra.db.DbConfig
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
import id.nearyou.app.timeline.FollowingTimelineService
import id.nearyou.app.timeline.GlobalTimelineService
import id.nearyou.app.timeline.NearbyTimelineService
import id.nearyou.app.timeline.followingTimelineRoutes
import id.nearyou.app.timeline.globalTimelineRoutes
import id.nearyou.app.timeline.timelineRoutes
import id.nearyou.data.repository.ModerationQueueRepository
import id.nearyou.data.repository.NotificationDispatcher
import id.nearyou.data.repository.NotificationRepository
import id.nearyou.data.repository.PostAutoHideRepository
import id.nearyou.data.repository.PostLikeRepository
import id.nearyou.data.repository.PostReplyRepository
import id.nearyou.data.repository.ReportRepository
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
    val notificationRepository: NotificationRepository = JdbcNotificationRepository(dataSource)
    val notificationDispatcher: NotificationDispatcher = NoopNotificationDispatcher()
    val notificationEmitter: NotificationEmitter = DbNotificationEmitter(notificationRepository)
    val notificationService = NotificationService(notificationRepository)
    val userFollowsRepository: UserFollowsRepository = JdbcUserFollowsRepository(dataSource)
    val followService =
        FollowService(dataSource, userFollowsRepository, notificationEmitter, notificationDispatcher)
    val postLikeRepository: PostLikeRepository = JdbcPostLikeRepository(dataSource)
    val likeService =
        LikeService(dataSource, postLikeRepository, notificationEmitter, notificationDispatcher)
    val postReplyRepository: PostReplyRepository = JdbcPostReplyRepository(dataSource)
    val replyService =
        ReplyService(dataSource, postReplyRepository, notificationEmitter, notificationDispatcher)
    val postsTimelineRepository: PostsTimelineRepository = JdbcPostsTimelineRepository(dataSource)
    val nearbyTimelineService = NearbyTimelineService(postsTimelineRepository)
    val postsFollowingRepository: PostsFollowingRepository = JdbcPostsFollowingRepository(dataSource)
    val followingTimelineService = FollowingTimelineService(postsFollowingRepository)
    val postsGlobalRepository: PostsGlobalRepository = JdbcPostsGlobalRepository(dataSource)
    val globalTimelineService = GlobalTimelineService(postsGlobalRepository)
    val reportRepository: ReportRepository = JdbcReportRepository()
    val moderationQueueRepository: ModerationQueueRepository = JdbcModerationQueueRepository()
    val postAutoHideRepository: PostAutoHideRepository = JdbcPostAutoHideRepository()
    val reportRateLimiter = ReportRateLimiter()
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
                single<NotificationRepository> { notificationRepository }
                single<NotificationDispatcher> { notificationDispatcher }
                single<NotificationEmitter> { notificationEmitter }
                single { notificationService }
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
    timelineRoutes(nearbyTimelineService)
    followingTimelineRoutes(followingTimelineService)
    globalTimelineRoutes(globalTimelineService)
    reportRoutes(reportService)
    notificationRoutes(notificationService)
}

private fun Application.csvAudiences(key: String): Set<String> =
    environment.config.propertyOrNull(key)?.getString()?.takeIf { it.isNotBlank() }
        ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        ?: emptySet()
