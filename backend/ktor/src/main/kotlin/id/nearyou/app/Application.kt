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
import id.nearyou.app.config.EnvVarSecretResolver
import id.nearyou.app.config.SecretResolver
import id.nearyou.app.guard.ContentEmptyException
import id.nearyou.app.guard.ContentLengthGuard
import id.nearyou.app.guard.ContentTooLongException
import id.nearyou.app.guard.installContentLengthGuard
import id.nearyou.app.health.healthRoutes
import id.nearyou.app.infra.db.DataSourceFactory
import id.nearyou.app.infra.db.DbConfig
import id.nearyou.app.infra.repo.JdbcPostRepository
import id.nearyou.app.infra.repo.JdbcRefreshTokenRepository
import id.nearyou.app.infra.repo.JdbcRejectedIdentifierRepository
import id.nearyou.app.infra.repo.JdbcReservedUsernameRepository
import id.nearyou.app.infra.repo.JdbcUserRepository
import id.nearyou.app.infra.repo.PostRepository
import id.nearyou.app.infra.repo.RefreshTokenRepository
import id.nearyou.app.infra.repo.RejectedIdentifierRepository
import id.nearyou.app.infra.repo.ReservedUsernameRepository
import id.nearyou.app.infra.repo.UserRepository
import id.nearyou.app.post.CreatePostService
import id.nearyou.app.post.LocationOutOfBoundsException
import id.nearyou.app.post.postRoutes
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
}

private fun Application.csvAudiences(key: String): Set<String> =
    environment.config.propertyOrNull(key)?.getString()?.takeIf { it.isNotBlank() }
        ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        ?: emptySet()
