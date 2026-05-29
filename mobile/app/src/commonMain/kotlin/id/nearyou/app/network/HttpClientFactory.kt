package id.nearyou.app.network

import id.nearyou.app.auth.RefreshRequest
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.auth.SignInResponse
import id.nearyou.app.auth.TokenPair
import id.nearyou.app.auth.TokenStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * commonMain-safe default [Logger]. Ktor's `Logger.SIMPLE` / `Logger.DEFAULT` are declared in
 * platform source sets only, so a shared default must be defined here. Only ever used when
 * `installLogging` is true (debug builds) — release builds never install the Logging plugin.
 */
private object PrintlnLogger : Logger {
    override fun log(message: String) {
        println(message)
    }
}

/**
 * Builds the single shared [HttpClient] for `:mobile:app`.
 *
 * 5.5a test strategy: [create] takes `apiBaseUrl` as a **parameter** (option (b)) rather than
 * reading the `expect val apiBaseUrl` directly, so commonTest can pass a fixed
 * `"http://test.local"` while production passes the platform value via Koin. No commonTest
 * source-set `actual` for `apiBaseUrl` is needed.
 *
 * Plugins (per `openspec/specs/mobile-auth-signin/spec.md` § "Ktor HTTP client attaches
 * Bearer token and handles 401 refresh"):
 *  - `ContentNegotiation(Json { ignoreUnknownKeys = true; explicitNulls = false })`.
 *  - `Auth { bearer { loadTokens … ; refreshTokens … } }` — the built-in concurrent-refresh
 *    queue is the canonical mechanism; callers MUST NOT reimplement 401-retry. On refresh
 *    failure the callback invokes [SessionInvalidator.invalidate] (clears tokens + signals
 *    re-route) and returns `null` so the plugin surfaces a terminal 401.
 *  - `Logging` — installed ONLY when [installLogging] (debug builds). `LogLevel.HEADERS`
 *    (never `ALL`, which would log the raw refresh-token request body) + a mandatory
 *    `sanitizeHeader` masking `Authorization` to `***`.
 *  - `DefaultRequest { url(apiBaseUrl) }`.
 */
object HttpClientFactory {
    fun create(
        apiBaseUrl: String,
        tokenStore: TokenStore,
        sessionInvalidator: SessionInvalidator,
        engine: HttpClientEngine,
        installLogging: Boolean,
        logger: Logger = PrintlnLogger,
        nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    ): HttpClient {
        val jsonConfig =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }

        return HttpClient(engine) {
            install(ContentNegotiation) {
                json(jsonConfig)
            }

            install(Auth) {
                bearer {
                    loadTokens {
                        tokenStore.read()?.let { BearerTokens(it.accessToken, it.refreshToken) }
                    }
                    refreshTokens {
                        val refreshToken = tokenStore.read()?.refreshToken
                        if (refreshToken == null) {
                            sessionInvalidator.invalidate()
                            return@refreshTokens null
                        }
                        val response =
                            client.post("/api/v1/auth/refresh") {
                                markAsRefreshTokenRequest()
                                contentType(ContentType.Application.Json)
                                setBody(RefreshRequest(refreshToken))
                            }
                        if (response.status.isSuccess()) {
                            val body = response.body<SignInResponse>()
                            tokenStore.write(
                                TokenPair(
                                    accessToken = body.accessToken,
                                    refreshToken = body.refreshToken,
                                    accessExpiresAtEpochMillis = nowMillis() + body.expiresIn * 1000L,
                                ),
                            )
                            BearerTokens(body.accessToken, body.refreshToken)
                        } else {
                            sessionInvalidator.invalidate()
                            null
                        }
                    }
                }
            }

            if (installLogging) {
                install(Logging) {
                    this.logger = logger
                    level = LogLevel.HEADERS
                    sanitizeHeader { header -> header.equals(HttpHeaders.Authorization, ignoreCase = true) }
                }
            }

            defaultRequest {
                url(apiBaseUrl)
            }
        }
    }
}
