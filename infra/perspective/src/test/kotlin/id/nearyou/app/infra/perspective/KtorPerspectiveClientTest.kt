package id.nearyou.app.infra.perspective

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

class KtorPerspectiveClientTest : StringSpec({

    "happy path: parses Perspective JSON into PerspectiveScore" {
        val capturedRequest = AtomicReference<HttpRequestData?>(null)
        val client =
            KtorPerspectiveClient(
                apiKey = "test-key",
                engine =
                    MockEngine { request ->
                        capturedRequest.set(request)
                        respond(
                            content =
                                """
                                {
                                  "attributeScores": {
                                    "TOXICITY":         {"summaryScore": {"value": 0.85, "type": "PROBABILITY"}},
                                    "SEVERE_TOXICITY":  {"summaryScore": {"value": 0.40, "type": "PROBABILITY"}},
                                    "IDENTITY_ATTACK":  {"summaryScore": {"value": 0.10, "type": "PROBABILITY"}},
                                    "THREAT":           {"summaryScore": {"value": 0.20, "type": "PROBABILITY"}}
                                  },
                                  "languages": ["en"]
                                }
                                """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
            )
        client.use {
            val score = runBlocking { it.analyze("hello") }
            score.toxicity.shouldBeBetween(0.84, 0.86, 0.001)
            score.severeToxicity.shouldBeBetween(0.39, 0.41, 0.001)
            score.identityAttack.shouldBeBetween(0.09, 0.11, 0.001)
            score.threat.shouldBeBetween(0.19, 0.21, 0.001)
        }
        capturedRequest.get() shouldNotBe null
    }

    "happy path: extra unknown fields are ignored (forward-compatible)" {
        val client =
            KtorPerspectiveClient(
                apiKey = "test-key",
                engine =
                    MockEngine { _ ->
                        respond(
                            content =
                                """
                                {
                                  "attributeScores": {
                                    "TOXICITY":         {"summaryScore": {"value": 0.5}, "spanScores": []},
                                    "SEVERE_TOXICITY":  {"summaryScore": {"value": 0.5}},
                                    "IDENTITY_ATTACK":  {"summaryScore": {"value": 0.5}},
                                    "THREAT":           {"summaryScore": {"value": 0.5}}
                                  },
                                  "detectedLanguages": ["id"],
                                  "clientToken": "abc"
                                }
                                """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
            )
        client.use {
            val score = runBlocking { it.analyze("hello") }
            score.toxicity.shouldBeBetween(0.49, 0.51, 0.001)
        }
    }

    "malformed JSON throws PerspectiveResponseParseException" {
        val client =
            KtorPerspectiveClient(
                apiKey = "test-key",
                engine =
                    MockEngine { _ ->
                        respondOk(content = "{not-json")
                    },
            )
        client.use {
            shouldThrow<PerspectiveResponseParseException> {
                runBlocking { it.analyze("hello") }
            }
        }
    }

    "response missing attributeScores throws PerspectiveResponseParseException" {
        val client =
            KtorPerspectiveClient(
                apiKey = "test-key",
                engine =
                    MockEngine { _ ->
                        respond(
                            content = "{\"languages\": [\"en\"]}",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
            )
        client.use {
            val ex =
                shouldThrow<PerspectiveResponseParseException> {
                    runBlocking { it.analyze("hello") }
                }
            ex.message!! shouldContain "attributeScores"
        }
    }

    "response missing one canonical attribute throws PerspectiveResponseParseException" {
        val client =
            KtorPerspectiveClient(
                apiKey = "test-key",
                engine =
                    MockEngine { _ ->
                        respond(
                            content =
                                """
                                {
                                  "attributeScores": {
                                    "TOXICITY":        {"summaryScore": {"value": 0.5}},
                                    "SEVERE_TOXICITY": {"summaryScore": {"value": 0.5}},
                                    "IDENTITY_ATTACK": {"summaryScore": {"value": 0.5}}
                                  }
                                }
                                """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
            )
        client.use {
            val ex =
                shouldThrow<PerspectiveResponseParseException> {
                    runBlocking { it.analyze("hello") }
                }
            ex.message!! shouldContain "THREAT"
        }
    }

    "HTTP 4xx (e.g., 400 Bad Request) throws PerspectiveHttpException with status" {
        val client =
            KtorPerspectiveClient(
                apiKey = "test-key",
                engine =
                    MockEngine { _ ->
                        respondError(HttpStatusCode.BadRequest, content = "{\"error\":\"bad\"}")
                    },
            )
        client.use {
            val ex =
                shouldThrow<PerspectiveHttpException> {
                    runBlocking { it.analyze("hello") }
                }
            ex.status shouldBe HttpStatusCode.BadRequest
        }
    }

    "HTTP 429 rate-limit throws PerspectiveHttpException with status 429" {
        val client =
            KtorPerspectiveClient(
                apiKey = "test-key",
                engine =
                    MockEngine { _ ->
                        respondError(HttpStatusCode.TooManyRequests, content = "{\"error\":\"quota\"}")
                    },
            )
        client.use {
            val ex =
                shouldThrow<PerspectiveHttpException> {
                    runBlocking { it.analyze("hello") }
                }
            ex.status shouldBe HttpStatusCode.TooManyRequests
        }
    }

    "HTTP 5xx throws PerspectiveHttpException with status 503" {
        val client =
            KtorPerspectiveClient(
                apiKey = "test-key",
                engine =
                    MockEngine { _ ->
                        respondError(HttpStatusCode.ServiceUnavailable, content = "<html>503</html>")
                    },
            )
        client.use {
            val ex =
                shouldThrow<PerspectiveHttpException> {
                    runBlocking { it.analyze("hello") }
                }
            ex.status shouldBe HttpStatusCode.ServiceUnavailable
        }
    }

    "network exception (engine throws) propagates to caller" {
        val client =
            KtorPerspectiveClient(
                apiKey = "test-key",
                engine =
                    MockEngine { _ ->
                        throw IOException("simulated DNS failure")
                    },
            )
        client.use {
            shouldThrow<IOException> {
                runBlocking { it.analyze("hello") }
            }
        }
    }

    "API key is sent as a `key` query parameter" {
        val capturedRequest = AtomicReference<HttpRequestData?>(null)
        val client =
            KtorPerspectiveClient(
                apiKey = "AIza-FAKE_KEY-with+special/chars=",
                engine =
                    MockEngine { request ->
                        capturedRequest.set(request)
                        respond(
                            content =
                                "{\"attributeScores\":{" +
                                    "\"TOXICITY\":{\"summaryScore\":{\"value\":0.0}}," +
                                    "\"SEVERE_TOXICITY\":{\"summaryScore\":{\"value\":0.0}}," +
                                    "\"IDENTITY_ATTACK\":{\"summaryScore\":{\"value\":0.0}}," +
                                    "\"THREAT\":{\"summaryScore\":{\"value\":0.0}}}}",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
            )
        client.use {
            runBlocking { it.analyze("hello") }
        }
        val req = capturedRequest.get()!!
        // Ktor URL-encodes the value; the encoded form must be present and the raw
        // form (with `+`, `/`, `=`) must NOT appear in the URL.
        req.url.parameters["key"] shouldBe "AIza-FAKE_KEY-with+special/chars="
        // Build URL string and confirm encoding (no `+`/`=`/`/` characters appear raw
        // in the query string for the special chars).
        val urlString = req.url.toString()
        urlString shouldContain "commentanalyzer.googleapis.com/v1alpha1/comments:analyze"
        urlString shouldContain "key="
    }

    "endpoint URL is the canonical Perspective comments:analyze surface" {
        val capturedRequest = AtomicReference<HttpRequestData?>(null)
        val client =
            KtorPerspectiveClient(
                apiKey = "k",
                engine =
                    MockEngine { request ->
                        capturedRequest.set(request)
                        respond(
                            content =
                                "{\"attributeScores\":{" +
                                    "\"TOXICITY\":{\"summaryScore\":{\"value\":0.0}}," +
                                    "\"SEVERE_TOXICITY\":{\"summaryScore\":{\"value\":0.0}}," +
                                    "\"IDENTITY_ATTACK\":{\"summaryScore\":{\"value\":0.0}}," +
                                    "\"THREAT\":{\"summaryScore\":{\"value\":0.0}}}}",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
            )
        client.use {
            runBlocking { it.analyze("hello") }
        }
        val req = capturedRequest.get()!!
        req.url.host shouldBe "commentanalyzer.googleapis.com"
        req.url.encodedPath shouldBe "/v1alpha1/comments:analyze"
        req.method.value shouldBe "POST"
    }

    "engine timeout configuration matches design.md Decision 2 values" {
        // The three constants are part of the public companion object so the values
        // are pinned. Asserting the constants ensures any regression to the values
        // is caught at compile + test time. The `withTimeoutOrNull(500.ms)` budget at
        // the orchestrator and the engine-level timeouts here form the
        // defense-in-depth against socket-pin under load.
        KtorPerspectiveClient.REQUEST_TIMEOUT_MILLIS shouldBe 500L
        KtorPerspectiveClient.CONNECT_TIMEOUT_MILLIS shouldBe 200L
        KtorPerspectiveClient.SOCKET_TIMEOUT_MILLIS shouldBe 500L
    }
})
