package id.nearyou.app.infra.openaimoderation

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

class OpenAiModerationClientTest : StringSpec({

    "happy path: parses OpenAI Moderation JSON into ModerationScore" {
        val capturedRequest = AtomicReference<HttpRequestData?>(null)
        val client =
            OpenAiModerationClient(
                apiKey = "test-key",
                engine =
                    MockEngine { request ->
                        capturedRequest.set(request)
                        respond(
                            content =
                                """
                                {
                                  "id": "modr-test",
                                  "model": "omni-moderation-latest",
                                  "results": [{
                                    "flagged": true,
                                    "categories": {
                                      "harassment": true,
                                      "harassment/threatening": true,
                                      "hate": false,
                                      "hate/threatening": false,
                                      "illicit": false,
                                      "illicit/violent": false,
                                      "self-harm": false,
                                      "self-harm/intent": false,
                                      "self-harm/instructions": false,
                                      "sexual": false,
                                      "sexual/minors": false,
                                      "violence": true,
                                      "violence/graphic": false
                                    },
                                    "category_scores": {
                                      "harassment": 0.85,
                                      "harassment/threatening": 0.72,
                                      "hate": 0.10,
                                      "hate/threatening": 0.05,
                                      "illicit": 0.01,
                                      "illicit/violent": 0.01,
                                      "self-harm": 0.02,
                                      "self-harm/intent": 0.01,
                                      "self-harm/instructions": 0.01,
                                      "sexual": 0.03,
                                      "sexual/minors": 0.01,
                                      "violence": 0.65,
                                      "violence/graphic": 0.04
                                    }
                                  }]
                                }
                                """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
            )
        client.use {
            val score = runBlocking { it.analyze("hello") }
            // Categories preserved verbatim with slash-separated subcategory keys.
            score.categoryScores["harassment"]!!.shouldBeBetween(0.84, 0.86, 0.001)
            score.categoryScores["harassment/threatening"]!!.shouldBeBetween(0.71, 0.73, 0.001)
            score.categoryScores["violence"]!!.shouldBeBetween(0.64, 0.66, 0.001)
            score.maxScore().shouldBeBetween(0.84, 0.86, 0.001)
        }
        capturedRequest.get() shouldNotBe null
    }

    "happy path: extra unknown fields are ignored (forward-compatible)" {
        val client =
            OpenAiModerationClient(
                apiKey = "test-key",
                engine =
                    MockEngine { _ ->
                        respond(
                            content =
                                """
                                {
                                  "id": "modr-fwd",
                                  "model": "omni-moderation-latest",
                                  "results": [{
                                    "flagged": false,
                                    "categories": {"harassment": false},
                                    "category_scores": {"harassment": 0.5},
                                    "category_applied_input_types": {"harassment": ["text"]},
                                    "future_field_added_by_openai": "ignore-me"
                                  }],
                                  "another_top_level_field": 42
                                }
                                """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
            )
        client.use {
            val score = runBlocking { it.analyze("hello") }
            score.categoryScores["harassment"]!!.shouldBeBetween(0.49, 0.51, 0.001)
        }
    }

    "malformed JSON throws ModerationResponseParseException" {
        val client =
            OpenAiModerationClient(
                apiKey = "test-key",
                engine =
                    MockEngine { _ ->
                        respondOk(content = "{not-json")
                    },
            )
        client.use {
            shouldThrow<ModerationResponseParseException> {
                runBlocking { it.analyze("hello") }
            }
        }
    }

    "response with empty results array throws ModerationResponseParseException" {
        val client =
            OpenAiModerationClient(
                apiKey = "test-key",
                engine =
                    MockEngine { _ ->
                        respond(
                            content = "{\"id\":\"modr-x\",\"model\":\"omni-moderation-latest\",\"results\":[]}",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
            )
        client.use {
            val ex =
                shouldThrow<ModerationResponseParseException> {
                    runBlocking { it.analyze("hello") }
                }
            ex.message!! shouldContain "results"
        }
    }

    "response with empty category_scores throws ModerationResponseParseException" {
        val client =
            OpenAiModerationClient(
                apiKey = "test-key",
                engine =
                    MockEngine { _ ->
                        respond(
                            content =
                                """
                                {
                                  "id": "modr-empty",
                                  "model": "omni-moderation-latest",
                                  "results": [{"flagged": false, "categories": {}, "category_scores": {}}]
                                }
                                """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
            )
        client.use {
            val ex =
                shouldThrow<ModerationResponseParseException> {
                    runBlocking { it.analyze("hello") }
                }
            ex.message!! shouldContain "category_scores"
        }
    }

    "HTTP 401 (invalid API key) throws ModerationHttpException with status 401" {
        val client =
            OpenAiModerationClient(
                apiKey = "bad-key",
                engine =
                    MockEngine { _ ->
                        respondError(HttpStatusCode.Unauthorized, content = "{\"error\":{\"code\":\"invalid_api_key\"}}")
                    },
            )
        client.use {
            val ex =
                shouldThrow<ModerationHttpException> {
                    runBlocking { it.analyze("hello") }
                }
            ex.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    "HTTP 429 (rate limit) throws ModerationHttpException with status 429" {
        val client =
            OpenAiModerationClient(
                apiKey = "test-key",
                engine =
                    MockEngine { _ ->
                        respondError(HttpStatusCode.TooManyRequests, content = "{\"error\":\"rate_limited\"}")
                    },
            )
        client.use {
            val ex =
                shouldThrow<ModerationHttpException> {
                    runBlocking { it.analyze("hello") }
                }
            ex.status shouldBe HttpStatusCode.TooManyRequests
        }
    }

    "HTTP 5xx throws ModerationHttpException with status 503" {
        val client =
            OpenAiModerationClient(
                apiKey = "test-key",
                engine =
                    MockEngine { _ ->
                        respondError(HttpStatusCode.ServiceUnavailable, content = "<html>503</html>")
                    },
            )
        client.use {
            val ex =
                shouldThrow<ModerationHttpException> {
                    runBlocking { it.analyze("hello") }
                }
            ex.status shouldBe HttpStatusCode.ServiceUnavailable
        }
    }

    "network exception (engine throws) propagates to caller" {
        val client =
            OpenAiModerationClient(
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

    "API key is sent as Bearer Authorization header (NOT as query parameter)" {
        val capturedRequest = AtomicReference<HttpRequestData?>(null)
        val client =
            OpenAiModerationClient(
                apiKey = "sk-FAKE_TEST_KEY_with_special-chars",
                engine =
                    MockEngine { request ->
                        capturedRequest.set(request)
                        respond(
                            content =
                                "{\"id\":\"x\",\"model\":\"omni-moderation-latest\"," +
                                    "\"results\":[{\"flagged\":false," +
                                    "\"categories\":{\"harassment\":false}," +
                                    "\"category_scores\":{\"harassment\":0.0}}]}",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
            )
        client.use {
            runBlocking { it.analyze("hello") }
        }
        val req = capturedRequest.get()!!
        // OpenAI auth: `Authorization: Bearer <key>` (NOT `?key=` query param like Perspective).
        req.headers[HttpHeaders.Authorization] shouldBe "Bearer sk-FAKE_TEST_KEY_with_special-chars"
        // Verify key is NOT leaking into the URL.
        val urlString = req.url.toString()
        urlString shouldContain "api.openai.com/v1/moderations"
        // The string "key=" should NOT appear in the URL — confirms no query-param leak.
        urlString.contains("key=") shouldBe false
    }

    "endpoint URL is the canonical OpenAI Moderations endpoint" {
        val capturedRequest = AtomicReference<HttpRequestData?>(null)
        val client =
            OpenAiModerationClient(
                apiKey = "k",
                engine =
                    MockEngine { request ->
                        capturedRequest.set(request)
                        respond(
                            content =
                                "{\"id\":\"x\",\"model\":\"omni-moderation-latest\"," +
                                    "\"results\":[{\"flagged\":false," +
                                    "\"categories\":{\"harassment\":false}," +
                                    "\"category_scores\":{\"harassment\":0.0}}]}",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
            )
        client.use {
            runBlocking { it.analyze("hello") }
        }
        val req = capturedRequest.get()!!
        req.url.host shouldBe "api.openai.com"
        req.url.encodedPath shouldBe "/v1/moderations"
        req.method.value shouldBe "POST"
    }

    "engine timeout configuration matches design.md Decision 2 values" {
        // The three constants are part of the public companion object so the values
        // are pinned. Asserting the constants ensures any regression to the values
        // is caught at compile + test time. The `withTimeoutOrNull(500.ms)` budget at
        // the orchestrator and the engine-level timeouts here form the
        // defense-in-depth against socket-pin under load.
        OpenAiModerationClient.REQUEST_TIMEOUT_MILLIS shouldBe 500L
        OpenAiModerationClient.CONNECT_TIMEOUT_MILLIS shouldBe 200L
        OpenAiModerationClient.SOCKET_TIMEOUT_MILLIS shouldBe 500L
    }

    "default model is the omni-moderation-latest variant" {
        OpenAiModerationClient.DEFAULT_MODEL shouldBe "omni-moderation-latest"
    }

    "canonical category set matches OpenAI's 13 published categories" {
        OpenAiModerationClient.CANONICAL_CATEGORIES.size shouldBe 13
        OpenAiModerationClient.CANONICAL_CATEGORIES shouldBe
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
    }
})
