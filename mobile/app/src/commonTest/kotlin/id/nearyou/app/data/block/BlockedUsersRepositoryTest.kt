package id.nearyou.app.data.block

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val JSON_HEADERS = headersOf("Content-Type", "application/json")

/** A 200 block-list body using the SHIPPED wire (`BlockRoutes.kt:34-44`): bare camelCase
 *  `userId`/`username`/`displayName`/`isPremium`/`createdAt` + camelCase `nextCursor`. An extra
 *  unknown key (`futureField`) verifies `ignoreUnknownKeys` tolerance. */
private const val BLOCKS_BODY =
    """{"blocks":[{"userId":"11111111-1111-1111-1111-111111111111","username":"raka.jkt",""" +
        """"displayName":"Raka Pratama","isPremium":false,"createdAt":"2026-06-01T00:00:00Z",""" +
        """"futureField":"ignored"}],"nextCursor":"tok"}"""

/**
 * MockEngine-backed coverage of [BlockedUsersRepository] / [BlockedUsersApiClient] (tasks 2.4): the
 * `GET /api/v1/blocks` path + cursor handling, the shipped camelCase wire parse + unknown-key
 * tolerance + the DTO→[BlockedUser] mapping, the unblock `DELETE` path, and the status-driven
 * outcome mapping for both calls.
 */
class BlockedUsersRepositoryTest {
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

    private fun repo(handler: MockRequestHandler) = BlockedUsersRepository(BlockedUsersApiClient(client(handler)))

    @Test
    fun `fetchBlocks targets the canonical path and omits the first-page cursor`() =
        runTest {
            var captured: HttpRequestData? = null
            repo { request ->
                captured = request
                respond("""{"blocks":[]}""", HttpStatusCode.OK, JSON_HEADERS)
            }.fetchBlocks()

            val req = requireNotNull(captured)
            assertEquals("/api/v1/blocks", req.url.encodedPath)
            assertFalse(req.url.parameters.contains("cursor"), "first-page request must omit cursor")
        }

    @Test
    fun `fetchBlocks forwards a non-null cursor as a query param`() =
        runTest {
            var captured: HttpRequestData? = null
            repo { request ->
                captured = request
                respond("""{"blocks":[]}""", HttpStatusCode.OK, JSON_HEADERS)
            }.fetchBlocks(cursor = "page2")

            assertEquals("page2", requireNotNull(captured).url.parameters["cursor"])
        }

    @Test
    fun `200 parses the shipped camelCase wire with unknown-key tolerance and maps to BlockedUser`() =
        runTest {
            val outcome = repo { respond(BLOCKS_BODY, HttpStatusCode.OK, JSON_HEADERS) }.fetchBlocks()

            assertTrue(outcome is BlockedUsersOutcome.Loaded)
            val loaded = outcome
            assertEquals("tok", loaded.nextCursor)
            assertEquals(1, loaded.blocks.size)
            val user = loaded.blocks.single()
            assertEquals("11111111-1111-1111-1111-111111111111", user.userId)
            assertEquals("raka.jkt", user.username)
            assertEquals("Raka Pratama", user.displayName)
            assertFalse(user.isPremium)
        }

    @Test
    fun `empty block list maps to Loaded with no rows and a null cursor`() =
        runTest {
            val outcome = repo { respond("""{"blocks":[],"nextCursor":null}""", HttpStatusCode.OK, JSON_HEADERS) }.fetchBlocks()

            val loaded = outcome as BlockedUsersOutcome.Loaded
            assertTrue(loaded.blocks.isEmpty())
            assertNull(loaded.nextCursor)
        }

    @Test
    fun `fetchBlocks 500 maps to RetryableError`() =
        runTest {
            val outcome = repo { respond("", HttpStatusCode.InternalServerError) }.fetchBlocks()
            assertEquals(BlockedUsersOutcome.RetryableError, outcome)
        }

    @Test
    fun `fetchBlocks 401 maps to TokenInvalid`() =
        runTest {
            val outcome = repo { respond("", HttpStatusCode.Unauthorized) }.fetchBlocks()
            assertEquals(BlockedUsersOutcome.TokenInvalid, outcome)
        }

    @Test
    fun `unblock issues a DELETE to the per-user path and a 204 maps to Success`() =
        runTest {
            var captured: HttpRequestData? = null
            val outcome =
                repo { request ->
                    captured = request
                    respond("", HttpStatusCode.NoContent)
                }.unblock("11111111-1111-1111-1111-111111111111")

            val req = requireNotNull(captured)
            assertEquals("DELETE", req.method.value)
            assertEquals("/api/v1/blocks/11111111-1111-1111-1111-111111111111", req.url.encodedPath)
            assertEquals(UnblockOutcome.Success, outcome)
        }

    @Test
    fun `unblock 500 maps to RetryableError so the row stays`() =
        runTest {
            val outcome = repo { respond("", HttpStatusCode.InternalServerError) }.unblock("u-1")
            assertEquals(UnblockOutcome.RetryableError, outcome)
        }

    @Test
    fun `unblock 401 maps to TokenInvalid`() =
        runTest {
            val outcome = repo { respond("", HttpStatusCode.Unauthorized) }.unblock("u-1")
            assertEquals(UnblockOutcome.TokenInvalid, outcome)
        }
}
