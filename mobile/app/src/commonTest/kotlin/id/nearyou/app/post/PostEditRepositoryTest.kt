package id.nearyou.app.post

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val JSON_HEADERS = headersOf("Content-Type", "application/json")

/**
 * MockEngine-backed coverage of [PostEditRepository] (task 7.1): the HTTP status + `error.code` →
 * [PostEditOutcome] mapping with no generic fallthrough (design D7), the history snake_case wire binding
 * (the PR #128 casing guard), and the freshness `refreshPost` mapping. Every documented edit failure is
 * exercised, and the no-op `400` / `404` are asserted DISTINCT from the network/window outcomes.
 */
class PostEditRepositoryTest {
    private fun repository(
        diagnosticLog: (Int, String?) -> Unit = { _, _ -> },
        handler: MockRequestHandler,
    ): PostEditRepository {
        val httpClient =
            HttpClientFactory.create(
                installTimeouts = false,
                apiBaseUrl = "http://test.local",
                tokenStore = InMemoryTokenStore(),
                sessionInvalidator = SessionInvalidator(InMemoryTokenStore()),
                engine = MockEngine(handler),
                installLogging = false,
                nowMillis = { 0L },
            )
        return PostEditRepository(
            editApiClient = PostEditApiClient(httpClient),
            singlePostApiClient = SinglePostApiClient(httpClient),
            diagnosticLog = diagnosticLog,
        )
    }

    @Test
    fun `200 maps to Success carrying the updated content`() =
        runTest {
            val repo = repository { respond("""{"id":"p1","content":"isi baru","updated_at":"t"}""", HttpStatusCode.OK, JSON_HEADERS) }
            assertEquals(PostEditOutcome.Success("isi baru"), repo.editPost("p1", "isi baru"))
        }

    @Test
    fun `403 maps to PremiumRequired`() =
        runTest {
            val repo = repository { respond("""{"error":{"code":"premium_required"}}""", HttpStatusCode.Forbidden, JSON_HEADERS) }
            assertEquals(PostEditOutcome.PremiumRequired, repo.editPost("p1", "x"))
        }

    @Test
    fun `409 edit_window_expired maps to WindowExpired - distinct from the temporal Conflict`() =
        runTest {
            val window = repository { respond("""{"error":{"code":"edit_window_expired"}}""", HttpStatusCode.Conflict, JSON_HEADERS) }
            assertEquals(PostEditOutcome.WindowExpired, window.editPost("p1", "x"))
            val conflict = repository { respond("""{"error":{"code":"edit_conflict"}}""", HttpStatusCode.Conflict, JSON_HEADERS) }
            assertEquals(PostEditOutcome.Conflict, conflict.editPost("p1", "x"))
        }

    @Test
    fun `400 no_changes maps to NoChanges - distinct from moderation and a defensive 400`() =
        runTest {
            val noChanges = repository { respond("""{"error":{"code":"no_changes"}}""", HttpStatusCode.BadRequest, JSON_HEADERS) }
            assertEquals(PostEditOutcome.NoChanges, noChanges.editPost("p1", "x"))

            val moderated =
                repository { respond("""{"error":{"code":"content_moderated_profanity"}}""", HttpStatusCode.BadRequest, JSON_HEADERS) }
            assertEquals(PostEditOutcome.ContentModerated, moderated.editPost("p1", "x"))

            // A defensive 400 (the client gate already blocks empty/over-length) → retryable Network + a diagnostic.
            val logged = mutableListOf<Pair<Int, String?>>()
            val other =
                repository(diagnosticLog = { s, c -> logged.add(s to c) }) {
                    respond("""{"error":{"code":"content_empty"}}""", HttpStatusCode.BadRequest, JSON_HEADERS)
                }
            assertEquals(PostEditOutcome.Network, other.editPost("p1", "x"))
            assertTrue(logged.any { it.first == 400 }, "an unknown 400 must log a diagnostic, not silently no-op: $logged")
        }

    @Test
    fun `429 maps to RateLimited carrying the Retry-After - and does not retry`() =
        runTest {
            var requestCount = 0
            val repo =
                repository {
                    requestCount++
                    respond(
                        """{"error":{"code":"rate_limited"}}""",
                        HttpStatusCode.TooManyRequests,
                        headersOf("Content-Type" to listOf("application/json"), "Retry-After" to listOf("42")),
                    )
                }
            assertEquals(PostEditOutcome.RateLimited(42L), repo.editPost("p1", "x"))
            assertEquals(1, requestCount, "the repository issues exactly one PATCH — no silent client retry on 429")
        }

    @Test
    fun `404 maps to NotFound - distinct from the network fallthrough`() =
        runTest {
            val repo = repository { respond("""{"error":{"code":"post_not_found"}}""", HttpStatusCode.NotFound, JSON_HEADERS) }
            assertEquals(PostEditOutcome.NotFound, repo.editPost("p1", "x"))
        }

    @Test
    fun `5xx and transport failures map to Network`() =
        runTest {
            val server = repository { respond("", HttpStatusCode.InternalServerError, JSON_HEADERS) }
            assertEquals(PostEditOutcome.Network, server.editPost("p1", "x"))
            val transport = repository { throw RuntimeException("connection refused") }
            assertEquals(PostEditOutcome.Network, transport.editPost("p1", "x"))
        }

    @Test
    fun `history binds the snake_case wire keys and preserves order`() =
        runTest {
            val body =
                """{"edits":[{"version_label":"Versi ke-1","content":"lama","edited_at":"2026-01-01T00:00:00Z"},""" +
                    """{"version_label":"Versi ke-2","content":"baru","edited_at":"2026-01-01T00:05:00Z"}]}"""
            val repo = repository { respond(body, HttpStatusCode.OK, JSON_HEADERS) }
            val outcome = repo.loadHistory("p1")
            assertTrue(outcome is EditHistoryOutcome.Loaded)
            val versions = (outcome as EditHistoryOutcome.Loaded).versions
            assertEquals(2, versions.size)
            assertEquals("Versi ke-1", versions[0].versionLabel)
            assertEquals("lama", versions[0].content)
            assertEquals("2026-01-01T00:00:00Z", versions[0].editedAt)
            assertEquals("Versi ke-2", versions[1].versionLabel)
        }

    @Test
    fun `history camelCase body does NOT bind - the snake keys are required`() =
        runTest {
            // A camelCase decoy lacks the REQUIRED snake_case keys (version_label / edited_at) → the DTO
            // fails to deserialize → Network (NOT silently-wrong data). The PR 128 casing guard: a
            // camelCase-keyed client history DTO would not parse this wire.
            val body = """{"edits":[{"versionLabel":"WRONG","content":"c","editedAt":"WRONG"}]}"""
            val repo = repository { respond(body, HttpStatusCode.OK, JSON_HEADERS) }
            assertEquals(EditHistoryOutcome.Network, repo.loadHistory("p1"))
        }

    @Test
    fun `history non-200 and transport map to Network`() =
        runTest {
            val notFound = repository { respond("", HttpStatusCode.NotFound, JSON_HEADERS) }
            assertEquals(EditHistoryOutcome.Network, notFound.loadHistory("p1"))
            val transport = repository { throw RuntimeException("io") }
            assertEquals(EditHistoryOutcome.Network, transport.loadHistory("p1"))
        }

    @Test
    fun `refreshPost maps 200 to Loaded with content editedAt and isAuthor`() =
        runTest {
            val repo =
                repository {
                    respond(
                        """{"content":"segar","editedAt":"2026-01-01T00:05:00Z","isAuthor":true}""",
                        HttpStatusCode.OK,
                        JSON_HEADERS,
                    )
                }
            assertEquals(PostRefreshOutcome.Loaded("segar", "2026-01-01T00:05:00Z", isAuthor = true), repo.refreshPost("p1"))
        }

    @Test
    fun `refreshPost tolerates an omitted editedAt - never-edited - and defaults isAuthor`() =
        runTest {
            val repo = repository { respond("""{"content":"segar"}""", HttpStatusCode.OK, JSON_HEADERS) }
            assertEquals(PostRefreshOutcome.Loaded("segar", editedAt = null, isAuthor = false), repo.refreshPost("p1"))
        }

    @Test
    fun `refreshPost degrades to Unavailable on non-200 or transport`() =
        runTest {
            val notFound = repository { respond("", HttpStatusCode.NotFound, JSON_HEADERS) }
            assertEquals(PostRefreshOutcome.Unavailable, notFound.refreshPost("p1"))
            val transport = repository { throw RuntimeException("io") }
            assertEquals(PostRefreshOutcome.Unavailable, transport.refreshPost("p1"))
        }
}
