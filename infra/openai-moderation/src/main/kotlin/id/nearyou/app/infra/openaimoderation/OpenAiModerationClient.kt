package id.nearyou.app.infra.openaimoderation

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Production [ModerationClient] implementation backed by the OpenAI Moderation
 * API (`omni-moderation-latest` model). Pivoted from the original Google
 * Perspective API target mid-implementation when Perspective announced sunset
 * (end-of-2026); per the `text-moderation-perspective-api-layer` change's
 * vendor-swap amendment in `proposal.md`.
 *
 * **Endpoint**: `POST https://api.openai.com/v1/moderations`
 *
 * **Auth**: `Authorization: Bearer <api-key>` header (NOT a query parameter —
 * different from Perspective's `?key=` convention).
 *
 * **Request body**: `{"model": "omni-moderation-latest", "input": "<content>"}`.
 *
 * **Response** (relevant subset): `results[0].category_scores` is a map of 13
 * category names → 0..1 probability. Categories cover harassment, hate, illicit,
 * self-harm, sexual, and violence with sub-categories like
 * `"harassment/threatening"` and `"hate/threatening"`. The slash-separated keys
 * are preserved verbatim in [ModerationScore.categoryScores] so consumers can
 * inspect specific sub-categories if desired.
 *
 * **Engine timeouts** are configured per `text-moderation-perspective-api-layer`
 * design.md Decision 2 — `requestTimeoutMillis = 500`, `connectTimeoutMillis = 200`,
 * `socketTimeoutMillis = 500`. Same defense-in-depth as the original Perspective
 * binding; the orchestrator's `withTimeoutOrNull(500.ms)` is the outer budget,
 * engine-level timeouts ensure socket close on cancellation.
 *
 * **Indonesian language**: OpenAI's omni-moderation model explicitly supports
 * Indonesian as a top-tier benchmarked language (per OpenAI's omni-moderation
 * launch announcement). This is a step up from Perspective's "partial ID
 * support" caveat in the original `docs/06-Security-Privacy.md:165`.
 *
 * The class throws on non-2xx HTTP responses ([ModerationHttpException]) and on
 * malformed response bodies ([ModerationResponseParseException]). Network
 * exceptions (DNS failure, TLS handshake failure, connection refused) propagate
 * from the underlying Ktor client. The orchestrator absorbs every failure mode at
 * the `:backend:ktor` boundary.
 *
 * @param apiKey resolved by the caller (typically `:backend:ktor`'s
 *   `Application.module()` via `secrets.resolve("openai-api-key")`). Per design.md
 *   Decision 1, secret resolution is the caller's responsibility — this module
 *   SHALL NOT depend on `:backend:ktor`'s `SecretResolver` interface.
 */
class OpenAiModerationClient(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    engine: HttpClientEngine? = null,
) : ModerationClient, AutoCloseable {
    private val httpClient: HttpClient =
        if (engine != null) {
            HttpClient(engine) { configure() }
        } else {
            HttpClient(CIO) { configure() }
        }

    /**
     * POSTs the canonical request body to the OpenAI Moderation endpoint and
     * returns the parsed [ModerationScore]. Throws [ModerationHttpException] on
     * non-2xx HTTP status; throws [ModerationResponseParseException] on malformed
     * response body. Network exceptions propagate from Ktor (consumed by the
     * orchestrator).
     */
    override suspend fun analyze(content: String): ModerationScore {
        val response: HttpResponse =
            httpClient.post(ENDPOINT_URL) {
                contentType(ContentType.Application.Json)
                headers {
                    append(HttpHeaders.Authorization, "Bearer $apiKey")
                    append(HttpHeaders.Accept, ContentType.Application.Json.toString())
                }
                setBody(ModerationRequest(model = model, input = content))
            }
        if (!response.status.isSuccess()) {
            val bodyText =
                runCatching { response.bodyAsText() }
                    .getOrDefault("(unable to read body)")
            throw ModerationHttpException(status = response.status, body = bodyText)
        }
        val rawBody =
            try {
                response.bodyAsText()
            } catch (t: ModerationHttpException) {
                throw t
            } catch (t: Throwable) {
                throw ModerationResponseParseException(
                    "failed to read response body: ${t.message ?: t.javaClass.simpleName}",
                    t,
                )
            }
        return parseScore(rawBody)
    }

    override fun close() {
        httpClient.close()
    }

    private fun parseScore(rawBody: String): ModerationScore {
        val root: ModerationApiResponse =
            try {
                JSON.decodeFromString(ModerationApiResponse.serializer(), rawBody)
            } catch (t: Throwable) {
                throw ModerationResponseParseException(
                    "failed to deserialize OpenAI Moderation response: ${t.message ?: t.javaClass.simpleName}",
                    t,
                )
            }
        val firstResult =
            root.results.firstOrNull()
                ?: throw ModerationResponseParseException(
                    "OpenAI Moderation response missing 'results' array (or empty)",
                )
        if (firstResult.category_scores.isEmpty()) {
            throw ModerationResponseParseException(
                "OpenAI Moderation response 'category_scores' is empty",
            )
        }
        return ModerationScore(categoryScores = firstResult.category_scores)
    }

    private fun io.ktor.client.HttpClientConfig<*>.configure() {
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
            connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
            socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
        }
        install(ContentNegotiation) {
            json(JSON)
        }
        expectSuccess = false
    }

    @Serializable
    private data class ModerationRequest(
        val model: String,
        val input: String,
    )

    @Serializable
    private data class ModerationApiResponse(
        val id: String? = null,
        val model: String? = null,
        val results: List<ModerationResult> = emptyList(),
    )

    @Serializable
    private data class ModerationResult(
        val flagged: Boolean = false,
        val categories: Map<String, Boolean> = emptyMap(),
        // OpenAI uses snake_case AND slash-separated category names. We declare the
        // wire-format field name verbatim with `@kotlinx.serialization.SerialName`
        // to avoid mis-mapping. Note: the slash-separated KEYS inside the map
        // (`"harassment/threatening"` etc.) flow through to the parsed Kotlin Map
        // intact; only the OUTER field-name `category_scores` needs serialization
        // mapping (kotlinx default would expect `categoryScores` camelCase).
        @kotlinx.serialization.SerialName("category_scores")
        val category_scores: Map<String, Double> = emptyMap(),
    )

    companion object {
        const val REQUEST_TIMEOUT_MILLIS: Long = 500L
        const val CONNECT_TIMEOUT_MILLIS: Long = 200L
        const val SOCKET_TIMEOUT_MILLIS: Long = 500L

        internal const val ENDPOINT_URL: String = "https://api.openai.com/v1/moderations"
        const val DEFAULT_MODEL: String = "omni-moderation-latest"

        /** Canonical OpenAI Moderation API category names (13 total).
         *
         * Exposed as a constant so unit tests + the orchestrator can reason about
         * the expected category set without hardcoding strings throughout the
         * codebase. Kept in sync with OpenAI's documented response shape.
         */
        val CANONICAL_CATEGORIES: Set<String> =
            setOf(
                "harassment",
                "harassment/threatening",
                "hate",
                "hate/threatening",
                "illicit",
                "illicit/violent",
                "self-harm",
                "self-harm/intent",
                "self-harm/instructions",
                "sexual",
                "sexual/minors",
                "violence",
                "violence/graphic",
            )

        private val JSON =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
    }
}

/**
 * Thrown by [OpenAiModerationClient.analyze] when the OpenAI Moderation endpoint
 * returns a non-2xx HTTP status. The orchestrator translates this into a Sentry
 * WARN with `failure_kind = "http_4xx"` or `"http_5xx"` and the integer
 * `status_code`.
 */
class ModerationHttpException(
    val status: HttpStatusCode,
    val body: String,
) : RuntimeException("OpenAI Moderation HTTP ${status.value} ${status.description}")

/**
 * Thrown by [OpenAiModerationClient.analyze] when the OpenAI Moderation response
 * body is missing required fields, contains an empty `results` array, or fails
 * JSON deserialization. The orchestrator translates this into a Sentry WARN with
 * `failure_kind = "parse"`.
 */
class ModerationResponseParseException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
