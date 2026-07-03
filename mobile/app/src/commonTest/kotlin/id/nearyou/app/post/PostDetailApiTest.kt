package id.nearyou.app.post

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val JSON_HEADERS = io.ktor.http.headersOf("Content-Type", "application/json")

private fun jsonWithRetryAfter(seconds: String) =
    io.ktor.http.headersOf(
        "Content-Type" to listOf("application/json"),
        "Retry-After" to listOf(seconds),
    )

private const val POST_ID = "11111111-1111-1111-1111-111111111111"

// A reply on the SHIPPED snake_case wire (post_id / author_id / author_username / author_display_name /
// is_auto_hidden / created_at / … — the identity fields shipped with mobile-block-from-content D7).
private const val REPLY_JSON =
    """{"id":"r1","post_id":"p1","author_id":"22222222-2222-2222-2222-222222222222",""" +
        """"author_username":"sinta.mhr","author_display_name":"Sinta Maharani",""" +
        """"content":"hi","is_auto_hidden":false,"created_at":"2026-06-06T00:00:00Z","updated_at":null,"deleted_at":null}"""

// A viewer's-own auto-hidden reply (the only reachable is_auto_hidden = true case) — must parse cleanly.
private const val REPLY_JSON_AUTO_HIDDEN =
    """{"id":"r2","post_id":"p1","author_id":"22222222-2222-2222-2222-222222222222",""" +
        """"author_username":"sinta.mhr","author_display_name":"Sinta Maharani",""" +
        """"content":"mine","is_auto_hidden":true,"created_at":"2026-06-06T00:00:00Z","updated_at":null,"deleted_at":null}"""

// An OLDER-backend body WITHOUT the identity keys — must still decode (nullable-with-default guard).
private const val REPLY_JSON_NO_IDENTITY =
    """{"id":"r3","post_id":"p1","author_id":"22222222-2222-2222-2222-222222222222",""" +
        """"content":"legacy","is_auto_hidden":false,"created_at":"2026-06-06T00:00:00Z","updated_at":null,"deleted_at":null}"""

/**
 * MockEngine-backed coverage of `LikeApiClient` / `ReplyApiClient` / `PostDetailRepository` (task 9.2):
 * the like POST/DELETE → 204 mapping (DELETE never 404), the `GET /likes/count` parse, the reply 201 →
 * `ReplyDto` parse against the SHIPPED snake_case wire (incl. an `is_auto_hidden = true` fixture), the
 * camelCase `nextCursor` negative-guard, the replies-list parse + retained-not-consumed `next_cursor`,
 * each status → outcome (429 retry-after, 404 PostGone, 5xx/IO NetworkError, 400 invalid_request → the
 * single InvalidContent), cancellation rethrow, and the no-wildcard mapping (an unexpected status → a
 * typed member, never a generic "failed" copy).
 */
class PostDetailApiTest {
    private fun client(handler: MockRequestHandler): HttpClient =
        HttpClientFactory.create(
            installTimeouts = false,
            apiBaseUrl = "http://test.local",
            tokenStore = InMemoryTokenStore(),
            sessionInvalidator = SessionInvalidator(InMemoryTokenStore()),
            engine = MockEngine(handler),
            installLogging = false,
            nowMillis = { 0L },
        )

    private fun likeApi(handler: MockRequestHandler) = LikeApiClient(client(handler))

    private fun replyApi(handler: MockRequestHandler) = ReplyApiClient(client(handler))

    private fun repo(handler: MockRequestHandler): PostDetailRepository {
        val c = client(handler)
        return PostDetailRepository(LikeApiClient(c), ReplyApiClient(c))
    }

    // ---- LikeApiClient ----

    @Test
    fun `like POST 204 maps to NoContent on the like path with POST method`() =
        runTest {
            var method: HttpMethod? = null
            var path = ""
            val api =
                likeApi { request ->
                    method = request.method
                    path = request.url.encodedPath
                    respond("", HttpStatusCode.NoContent)
                }
            assertEquals(LikeApiResult.NoContent, api.like(POST_ID))
            assertEquals(HttpMethod.Post, method)
            assertEquals("/api/v1/posts/$POST_ID/like", path)
        }

    @Test
    fun `unlike DELETE 204 maps to NoContent and never 404s`() =
        runTest {
            var method: HttpMethod? = null
            val api =
                likeApi { request ->
                    method = request.method
                    respond("", HttpStatusCode.NoContent)
                }
            assertEquals(LikeApiResult.NoContent, api.unlike(POST_ID))
            assertEquals(HttpMethod.Delete, method)
        }

    @Test
    fun `like 429 carries the parsed Retry-After seconds`() =
        runTest {
            val api =
                likeApi {
                    respond("""{"error":{"code":"rate_limited"}}""", HttpStatusCode.TooManyRequests, jsonWithRetryAfter("3600"))
                }
            val result = api.like(POST_ID)
            assertTrue(result is LikeApiResult.HttpError)
            assertEquals(429, result.status)
            assertEquals(3600L, result.retryAfterSeconds)
        }

    @Test
    fun `like 404 maps to HttpError with a null retry-after`() =
        runTest {
            val api = likeApi { respond("""{"error":{"code":"post_not_found"}}""", HttpStatusCode.NotFound, JSON_HEADERS) }
            val result = api.like(POST_ID)
            assertTrue(result is LikeApiResult.HttpError)
            assertEquals(404, result.status)
            assertNull(result.retryAfterSeconds)
        }

    @Test
    fun `like transport failure maps to NetworkError`() =
        runTest {
            val api = likeApi { throw RuntimeException("connection refused") }
            assertTrue(api.like(POST_ID) is LikeApiResult.NetworkError)
        }

    @Test
    fun `likes count 200 parses the count on the likes-count path`() =
        runTest {
            var path = ""
            val api =
                likeApi { request ->
                    path = request.url.encodedPath
                    respond("""{"count":42}""", HttpStatusCode.OK, JSON_HEADERS)
                }
            val result = api.count(POST_ID)
            assertTrue(result is LikeCountApiResult.Success)
            assertEquals(42L, result.count)
            assertEquals("/api/v1/posts/$POST_ID/likes/count", path)
        }

    @Test
    fun `likes count non-200 maps to HttpError`() =
        runTest {
            val api = likeApi { respond("""{"error":{"code":"post_not_found"}}""", HttpStatusCode.NotFound, JSON_HEADERS) }
            val result = api.count(POST_ID)
            assertTrue(result is LikeCountApiResult.HttpError)
            assertEquals(404, result.status)
        }

    // ---- ReplyApiClient ----

    @Test
    fun `reply POST 201 parses the snake_case ReplyDto`() =
        runTest {
            var method: HttpMethod? = null
            var path = ""
            val api =
                replyApi { request ->
                    method = request.method
                    path = request.url.encodedPath
                    respond(REPLY_JSON, HttpStatusCode.Created, JSON_HEADERS)
                }
            val result = api.postReply(POST_ID, "hi")
            assertTrue(result is ReplyPostApiResult.Created)
            assertEquals("r1", result.reply.id)
            assertEquals("p1", result.reply.postId)
            assertEquals("22222222-2222-2222-2222-222222222222", result.reply.authorId)
            // mobile-block-from-content D7: the identity fields bind from their snake_case keys.
            assertEquals("sinta.mhr", result.reply.authorUsername)
            assertEquals("Sinta Maharani", result.reply.authorDisplayName)
            assertEquals("hi", result.reply.content)
            assertFalse(result.reply.isAutoHidden)
            assertEquals("2026-06-06T00:00:00Z", result.reply.createdAt)
            assertEquals(HttpMethod.Post, method)
            assertEquals("/api/v1/posts/$POST_ID/replies", path)
        }

    @Test
    fun `reply body WITHOUT identity keys still decodes - older-backend guard`() =
        runTest {
            // mobile-block-from-content D7: author_username / author_display_name are
            // nullable-with-default, so an older-backend body decodes with null identity (the card
            // omits the identity row + block item gracefully) — never a parse failure.
            val api = replyApi { respond(REPLY_JSON_NO_IDENTITY, HttpStatusCode.Created, JSON_HEADERS) }
            val result = api.postReply(POST_ID, "legacy")
            assertTrue(result is ReplyPostApiResult.Created)
            assertNull(result.reply.authorUsername)
            assertNull(result.reply.authorDisplayName)
            assertEquals("legacy", result.reply.content)
        }

    @Test
    fun `reply POST 201 with is_auto_hidden true parses cleanly`() =
        runTest {
            val api = replyApi { respond(REPLY_JSON_AUTO_HIDDEN, HttpStatusCode.Created, JSON_HEADERS) }
            val result = api.postReply(POST_ID, "mine")
            assertTrue(result is ReplyPostApiResult.Created)
            assertTrue(result.reply.isAutoHidden, "the viewer's own auto-hidden reply parses with the flag set")
        }

    @Test
    fun `reply POST 400 carries the invalid_request error code`() =
        runTest {
            val api = replyApi { respond("""{"error":{"code":"invalid_request"}}""", HttpStatusCode.BadRequest, JSON_HEADERS) }
            val result = api.postReply(POST_ID, "")
            assertTrue(result is ReplyPostApiResult.HttpError)
            assertEquals(400, result.status)
            assertEquals("invalid_request", result.errorCode)
        }

    @Test
    fun `reply POST 429 carries the Retry-After seconds`() =
        runTest {
            val api =
                replyApi {
                    respond("""{"error":{"code":"rate_limited"}}""", HttpStatusCode.TooManyRequests, jsonWithRetryAfter("7200"))
                }
            val result = api.postReply(POST_ID, "hi")
            assertTrue(result is ReplyPostApiResult.HttpError)
            assertEquals(429, result.status)
            assertEquals(7200L, result.retryAfterSeconds)
        }

    @Test
    fun `replies GET 200 parses the snake_case list and retains next_cursor`() =
        runTest {
            val body = """{"replies":[$REPLY_JSON],"next_cursor":"tok"}"""
            val api = replyApi { respond(body, HttpStatusCode.OK, JSON_HEADERS) }
            val result = api.listReplies(POST_ID)
            assertTrue(result is ReplyListApiResult.Success)
            assertEquals(1, result.body.replies.size)
            assertEquals("hi", result.body.replies.first().content)
            assertEquals("tok", result.body.nextCursor)
        }

    @Test
    fun `replies GET camelCase nextCursor does NOT populate the snake_case field`() =
        runTest {
            // The timelines' camelCase key, NOT the shipped reply wire — the negative guard against the
            // casing-drift trap (PR #128). next_cursor (snake) is the ONLY key that binds.
            val body = """{"replies":[],"nextCursor":"tok"}"""
            val api = replyApi { respond(body, HttpStatusCode.OK, JSON_HEADERS) }
            val result = api.listReplies(POST_ID)
            assertTrue(result is ReplyListApiResult.Success)
            assertNull(result.body.nextCursor, "a camelCase nextCursor must NOT bind under @SerialName(\"next_cursor\")")
        }

    @Test
    fun `reply transport failure maps to NetworkError`() =
        runTest {
            val api = replyApi { throw RuntimeException("connection refused") }
            assertTrue(api.postReply(POST_ID, "hi") is ReplyPostApiResult.NetworkError)
            assertTrue(replyApi { throw RuntimeException("io") }.listReplies(POST_ID) is ReplyListApiResult.NetworkError)
        }

    @Test
    fun `cancellation mid reply POST propagates rather than mapping to NetworkError`() =
        runTest {
            val api =
                replyApi {
                    delay(60_000) // never completes within the job's lifetime
                    respond(REPLY_JSON, HttpStatusCode.Created, JSON_HEADERS)
                }
            var completed = false
            val job =
                launch {
                    api.postReply(POST_ID, "hi")
                    completed = true
                }
            delay(100)
            job.cancel()
            job.join()
            assertTrue(job.isCancelled, "the in-flight postReply job is cancelled, not hung")
            assertFalse(completed, "postReply did not silently complete with a NetworkError after cancellation")
        }

    @Test
    fun `reply 201 with an unparseable body maps to NetworkError not a false Created`() =
        runTest {
            // A 201 whose body fails to parse is a transport/contract failure, never a Created (the
            // casing-drift guard, cf. PR #128) — must NOT surface as a successful reply.
            val api = replyApi { respond("not json at all", HttpStatusCode.Created, JSON_HEADERS) }
            assertTrue(api.postReply(POST_ID, "hi") is ReplyPostApiResult.NetworkError)
        }

    @Test
    fun `likes count 200 with an unparseable body maps to NetworkError`() =
        runTest {
            val api = likeApi { respond("{ not json", HttpStatusCode.OK, JSON_HEADERS) }
            assertTrue(api.count(POST_ID) is LikeCountApiResult.NetworkError)
        }

    @Test
    fun `like 429 with a non-numeric Retry-After yields a null retry-after`() =
        runTest {
            // An HTTP-date-format Retry-After (or any non-integer) must parse to null, not crash — the
            // repository then maps it to RateLimited(0) → the "1 jam" floor.
            val api =
                likeApi {
                    respond(
                        """{"error":{"code":"rate_limited"}}""",
                        HttpStatusCode.TooManyRequests,
                        jsonWithRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"),
                    )
                }
            val result = api.like(POST_ID)
            assertTrue(result is LikeApiResult.HttpError)
            assertEquals(429, result.status)
            assertNull(result.retryAfterSeconds, "a non-numeric Retry-After parses to null, not a crash")
        }

    // ---- PostDetailRepository status → outcome mapping ----

    @Test
    fun `repo toggleLike like and unlike map 204 to Liked and Unliked`() =
        runTest {
            val r = repo { respond("", HttpStatusCode.NoContent) }
            assertEquals(LikeOutcome.Liked, r.toggleLike(POST_ID, currentlyLiked = false))
            assertEquals(LikeOutcome.Unliked, r.toggleLike(POST_ID, currentlyLiked = true))
        }

    @Test
    fun `repo toggleLike maps 429 to RateLimited with retry-after and 404 to PostGone and 5xx to NetworkError`() =
        runTest {
            val rl = repo { respond("""{"error":{"code":"rate_limited"}}""", HttpStatusCode.TooManyRequests, jsonWithRetryAfter("3600")) }
            assertEquals(LikeOutcome.RateLimited(3600L), rl.toggleLike(POST_ID, currentlyLiked = false))

            val gone = repo { respond("""{"error":{"code":"post_not_found"}}""", HttpStatusCode.NotFound, JSON_HEADERS) }
            assertEquals(LikeOutcome.PostGone, gone.toggleLike(POST_ID, currentlyLiked = false))

            val err = repo { respond("", HttpStatusCode.InternalServerError) }
            assertEquals(LikeOutcome.NetworkError, err.toggleLike(POST_ID, currentlyLiked = false))
        }

    @Test
    fun `repo postReply maps 201 to Success and 400 to the single InvalidContent and 429 to RateLimited and 404 to PostGone`() =
        runTest {
            val ok = repo { respond(REPLY_JSON, HttpStatusCode.Created, JSON_HEADERS) }
            val success = ok.postReply(POST_ID, "hi")
            assertTrue(success is ReplyPostOutcome.Success)
            assertEquals("r1", success.reply.id)

            val invalid = repo { respond("""{"error":{"code":"invalid_request"}}""", HttpStatusCode.BadRequest, JSON_HEADERS) }
            assertEquals(ReplyPostOutcome.InvalidContent, invalid.postReply(POST_ID, ""), "400 → the SINGLE InvalidContent, not split")

            val rl = repo { respond("""{"error":{"code":"rate_limited"}}""", HttpStatusCode.TooManyRequests, jsonWithRetryAfter("60")) }
            assertEquals(ReplyPostOutcome.RateLimited(60L), rl.postReply(POST_ID, "hi"))

            val gone = repo { respond("""{"error":{"code":"post_not_found"}}""", HttpStatusCode.NotFound, JSON_HEADERS) }
            assertEquals(ReplyPostOutcome.PostGone, gone.postReply(POST_ID, "hi"))
        }

    @Test
    fun `repo loadReplies maps 200 to Loaded with retained cursor and any error to NetworkError`() =
        runTest {
            val ok = repo { respond("""{"replies":[$REPLY_JSON],"next_cursor":"tok"}""", HttpStatusCode.OK, JSON_HEADERS) }
            val loaded = ok.loadReplies(POST_ID)
            assertTrue(loaded is RepliesOutcome.Loaded)
            assertEquals(1, loaded.replies.size)
            assertEquals("tok", loaded.nextCursor, "next_cursor is retained on Loaded (not consumed for load-more)")

            val gone = repo { respond("""{"error":{"code":"post_not_found"}}""", HttpStatusCode.NotFound, JSON_HEADERS) }
            assertEquals(RepliesOutcome.NetworkError, gone.loadReplies(POST_ID), "a 404 on the list folds into the retryable error state")
        }

    @Test
    fun `repo likeCount maps 200 to Available and any failure to Unavailable - graceful degradation`() =
        runTest {
            val ok = repo { respond("""{"count":42}""", HttpStatusCode.OK, JSON_HEADERS) }
            assertEquals(LikeCountOutcome.Available(42L), ok.likeCount(POST_ID))

            val gone = repo { respond("", HttpStatusCode.NotFound) }
            assertEquals(LikeCountOutcome.Unavailable, gone.likeCount(POST_ID))

            val net = repo { throw RuntimeException("io") }
            assertEquals(LikeCountOutcome.Unavailable, net.likeCount(POST_ID))
        }

    @Test
    fun `repo maps an unexpected status to a typed member - never a generic fallthrough`() =
        runTest {
            // An unenumerated status (418) on like + reply must land on the DEFINED retryable NetworkError,
            // NOT a generic "failed" copy (the no-wildcard guarantee — backed by the exhaustive sealed when).
            val teapot = repo { respond("", HttpStatusCode.fromValue(418)) }
            assertEquals(LikeOutcome.NetworkError, teapot.toggleLike(POST_ID, currentlyLiked = false))
            assertEquals(ReplyPostOutcome.NetworkError, teapot.postReply(POST_ID, "hi"))
        }
}
