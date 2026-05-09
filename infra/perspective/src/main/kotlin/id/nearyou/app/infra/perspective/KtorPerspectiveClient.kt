package id.nearyou.app.infra.perspective

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
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
import kotlinx.serialization.json.JsonObject

/**
 * Production [PerspectiveClient] implementation backed by the Ktor HTTP client (CIO
 * engine) calling Google's `commentanalyzer.googleapis.com` Perspective API.
 *
 * The endpoint URL is `https://commentanalyzer.googleapis.com/v1alpha1/comments:analyze`
 * with the API key supplied as a `?key=<api-key>` query parameter (per Google's
 * documented auth pattern). The request body asks for the four canonical attributes
 * (TOXICITY, SEVERE_TOXICITY, IDENTITY_ATTACK, THREAT).
 *
 * **Engine timeouts** are explicitly configured per `text-moderation-perspective-api-layer`
 * design.md Decision 2 — `requestTimeoutMillis = 500`, `connectTimeoutMillis = 200`,
 * `socketTimeoutMillis = 500`. Without engine-level timeouts, a slow socket read can
 * pin the underlying connection past the orchestrator's `withTimeoutOrNull(500.ms)`
 * cancellation; the explicit values force socket close on cancellation.
 *
 * The class throws on non-2xx HTTP responses ([PerspectiveHttpException]) and on
 * malformed response bodies ([PerspectiveResponseParseException]). Network exceptions
 * (DNS failure, TLS handshake failure, connection refused) propagate from the underlying
 * Ktor client. The orchestrator absorbs every failure mode at the
 * `:backend:ktor` boundary.
 *
 * @param apiKey resolved by the caller (typically `:backend:ktor`'s `Application.module()`
 *   via `secrets.resolve("perspective-api-key")`). Per design.md Decision 1, secret
 *   resolution is the caller's responsibility — `:infra:perspective` SHALL NOT depend
 *   on `:backend:ktor`'s `SecretResolver` interface.
 */
class KtorPerspectiveClient(
    private val apiKey: String,
    engine: HttpClientEngine? = null,
) : PerspectiveClient, AutoCloseable {
    private val httpClient: HttpClient =
        if (engine != null) {
            HttpClient(engine) { configure() }
        } else {
            HttpClient(CIO) { configure() }
        }

    /**
     * POSTs the canonical request body to the Perspective endpoint and returns the
     * parsed [PerspectiveScore]. Throws [PerspectiveHttpException] on non-2xx HTTP
     * status; throws [PerspectiveResponseParseException] on malformed response body.
     * Network exceptions propagate from Ktor (consumed by the orchestrator).
     */
    override suspend fun analyze(content: String): PerspectiveScore {
        val response: HttpResponse =
            httpClient.post(ENDPOINT_URL) {
                parameter("key", apiKey)
                contentType(ContentType.Application.Json)
                headers { append(HttpHeaders.Accept, ContentType.Application.Json.toString()) }
                setBody(buildRequestBody(content))
            }
        if (!response.status.isSuccess()) {
            val bodyText =
                runCatching { response.bodyAsText() }
                    .getOrDefault("(unable to read body)")
            throw PerspectiveHttpException(status = response.status, body = bodyText)
        }
        val rawBody =
            try {
                response.bodyAsText()
            } catch (t: PerspectiveHttpException) {
                throw t
            } catch (t: Throwable) {
                throw PerspectiveResponseParseException(
                    "failed to read response body: ${t.message ?: t.javaClass.simpleName}",
                    t,
                )
            }
        return parseScore(rawBody)
    }

    override fun close() {
        httpClient.close()
    }

    private fun buildRequestBody(content: String): PerspectiveRequest =
        PerspectiveRequest(
            comment = PerspectiveRequestComment(text = content),
            requestedAttributes = REQUESTED_ATTRIBUTES,
        )

    private fun parseScore(rawBody: String): PerspectiveScore {
        val root: PerspectiveResponse =
            try {
                JSON.decodeFromString(PerspectiveResponse.serializer(), rawBody)
            } catch (t: Throwable) {
                throw PerspectiveResponseParseException(
                    "failed to deserialize Perspective response: ${t.message ?: t.javaClass.simpleName}",
                    t,
                )
            }
        val attrScores: Map<String, PerspectiveAttributeBlock>? = root.attributeScores
        if (attrScores.isNullOrEmpty()) {
            throw PerspectiveResponseParseException(
                "Perspective response missing attributeScores block",
            )
        }
        return PerspectiveScore(
            toxicity = readScore(attrScores, ATTR_TOXICITY),
            severeToxicity = readScore(attrScores, ATTR_SEVERE_TOXICITY),
            identityAttack = readScore(attrScores, ATTR_IDENTITY_ATTACK),
            threat = readScore(attrScores, ATTR_THREAT),
        )
    }

    private fun readScore(
        attrScores: Map<String, PerspectiveAttributeBlock>,
        name: String,
    ): Double {
        val block =
            attrScores[name]
                ?: throw PerspectiveResponseParseException(
                    "Perspective response missing attribute '$name'",
                )
        return block.summaryScore.value
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
    private data class PerspectiveRequest(
        val comment: PerspectiveRequestComment,
        val requestedAttributes: Map<String, JsonObject>,
    )

    @Serializable
    private data class PerspectiveRequestComment(
        val text: String,
    )

    @Serializable
    private data class PerspectiveResponse(
        val attributeScores: Map<String, PerspectiveAttributeBlock>? = null,
    )

    @Serializable
    private data class PerspectiveAttributeBlock(
        val summaryScore: PerspectiveSummaryScore,
    )

    @Serializable
    private data class PerspectiveSummaryScore(
        val value: Double,
        val type: String? = null,
    )

    companion object {
        const val REQUEST_TIMEOUT_MILLIS: Long = 500L
        const val CONNECT_TIMEOUT_MILLIS: Long = 200L
        const val SOCKET_TIMEOUT_MILLIS: Long = 500L

        internal const val ENDPOINT_URL: String =
            "https://commentanalyzer.googleapis.com/v1alpha1/comments:analyze"

        private const val ATTR_TOXICITY = "TOXICITY"
        private const val ATTR_SEVERE_TOXICITY = "SEVERE_TOXICITY"
        private const val ATTR_IDENTITY_ATTACK = "IDENTITY_ATTACK"
        private const val ATTR_THREAT = "THREAT"

        private val REQUESTED_ATTRIBUTES: Map<String, JsonObject> =
            mapOf(
                ATTR_TOXICITY to JsonObject(emptyMap()),
                ATTR_SEVERE_TOXICITY to JsonObject(emptyMap()),
                ATTR_IDENTITY_ATTACK to JsonObject(emptyMap()),
                ATTR_THREAT to JsonObject(emptyMap()),
            )

        private val JSON =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
    }
}

/**
 * Thrown by [KtorPerspectiveClient.analyze] when the Perspective endpoint returns a
 * non-2xx HTTP status. The orchestrator translates this into a Sentry WARN with
 * `failure_kind = "http_4xx"` or `"http_5xx"` and the integer `status_code`.
 */
class PerspectiveHttpException(
    val status: HttpStatusCode,
    val body: String,
) : RuntimeException("Perspective HTTP ${status.value} ${status.description}")

/**
 * Thrown by [KtorPerspectiveClient.analyze] when the Perspective response body is
 * missing required fields or fails JSON deserialization. The orchestrator translates
 * this into a Sentry WARN with `failure_kind = "parse"`.
 */
class PerspectiveResponseParseException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
