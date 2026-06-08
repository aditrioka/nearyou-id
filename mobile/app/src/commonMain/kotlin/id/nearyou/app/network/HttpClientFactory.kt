package id.nearyou.app.network

import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.auth.TokenRefresher
import id.nearyou.app.auth.TokenStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpHeaders
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
 * Masks the VALUE of any `lat` / `lng` / `coord…` URL query parameter in a log line, so the
 * now-real viewer coordinate never reaches debug logs via the `HttpClient`'s request-line logging
 * (`LogLevel.HEADERS` logs the URL incl. its query string; the `Authorization`-header `sanitizeHeader`
 * does NOT cover query params). Per `openspec/specs/mobile-location` § "Coordinate query parameters
 * are masked in HTTP-client logs". Anchored to a `?`/`&` boundary so only whole param names match
 * (e.g. `radius_m` is untouched); case-insensitive.
 */
private val COORDINATE_QUERY_PARAM_REGEX = Regex("([?&](?:lat|lng|coord[a-z]*)=)[^&#\\s]*", RegexOption.IGNORE_CASE)

private fun maskCoordinateQueryParams(message: String): String =
    COORDINATE_QUERY_PARAM_REGEX.replace(message) { match -> match.groupValues[1] + "***" }

/**
 * Decorates a [Logger] so every emitted line has its coordinate query-param values masked before it
 * reaches the underlying logger (incl. a capturing test logger). Composes with the
 * `Authorization`-header `sanitizeHeader`: the header sanitizer never sees the URL query string, so
 * this closes the coordinate leak that sanitizer cannot.
 */
private class CoordinateMaskingLogger(
    private val delegate: Logger,
) : Logger {
    override fun log(message: String) {
        delegate.log(maskCoordinateQueryParams(message))
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
 *    queue is the canonical mechanism; callers MUST NOT reimplement 401-retry. The
 *    `refreshTokens` callback **delegates the round-trip to [TokenRefresher]** (single-flight
 *    shared with the proactive on-resume trigger, mobile-session-expiry-and-proactive-refresh D2);
 *    on refresh failure [TokenRefresher] invokes [SessionInvalidator.invalidate] (clears tokens +
 *    signals re-route) and the callback returns `null` so the plugin surfaces a terminal 401.
 *  - `Logging` — installed ONLY when [installLogging] (debug builds). `LogLevel.HEADERS`
 *    (never `ALL`, which would log the raw refresh-token request body) + a mandatory
 *    `sanitizeHeader` masking `Authorization` to `***` + a [CoordinateMaskingLogger] wrapper that
 *    masks `lat`/`lng`/`coord…` query-param VALUES (the Nearby coordinate travels in the URL query,
 *    which the header sanitizer cannot reach) — `mobile-location-permission-flow`.
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
        // The single-flight refresh round-trip (D2). Defaulted so existing call sites/tests that only
        // exercise the reactive path need no change; MobileModule passes the shared Koin single so the
        // proactive trigger and the bearer plugin funnel through ONE instance.
        tokenRefresher: TokenRefresher = TokenRefresher(tokenStore, sessionInvalidator, nowMillis),
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
                        // Delegate the round-trip to the shared single-flight TokenRefresher (D2) — do
                        // NOT reimplement 401-retry; the Ktor plugin's request-queue stays. `client` is
                        // `RefreshTokensParams.client` (the shared client); TokenRefresher tags the POST
                        // with the AuthCircuitBreaker attribute (== markAsRefreshTokenRequest) so it does
                        // not recurse, writes the new TokenPair on success, and invalidates on failure.
                        tokenRefresher.refresh(client)?.let { BearerTokens(it.accessToken, it.refreshToken) }
                    }
                }
            }

            if (installLogging) {
                install(Logging) {
                    // Wrap the (possibly test-capturing) logger so coordinate query-param values are
                    // masked in the logged request line, alongside the Authorization-header sanitizer.
                    this.logger = CoordinateMaskingLogger(logger)
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
