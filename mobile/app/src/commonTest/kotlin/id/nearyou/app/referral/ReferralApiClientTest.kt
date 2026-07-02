package id.nearyou.app.referral

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private val JSON_HEADERS = headersOf("Content-Type", "application/json")

/** MockEngine-backed coverage of [ReferralApiClient]: GET state parse (200 → Success) + failure mapping. */
class ReferralApiClientTest {
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

    @Test
    fun `fetchState parses the invite code and progress on 200`() =
        runTest {
            var captured: HttpRequestData? = null
            val api =
                ReferralApiClient(
                    client { request ->
                        captured = request
                        respond(
                            """{"inviteCode":"a3f7k2mq","grantedReferrals":2,"milestone":5,"inviterRewardClaimed":false}""",
                            HttpStatusCode.OK,
                            JSON_HEADERS,
                        )
                    },
                )

            val result = api.fetchState()

            assertEquals("/api/v1/user/referral", requireNotNull(captured).url.encodedPath)
            assertEquals(HttpMethod.Get, requireNotNull(captured).method)
            assertEquals(
                ReferralStateResult.Success(
                    inviteCode = "a3f7k2mq",
                    grantedReferrals = 2,
                    milestone = 5,
                    inviterRewardClaimed = false,
                ),
                result,
            )
        }

    @Test
    fun `fetchState maps a non-2xx to Failure`() =
        runTest {
            val api = ReferralApiClient(client { respond("", HttpStatusCode.InternalServerError) })
            assertEquals(ReferralStateResult.Failure, api.fetchState())
        }

    @Test
    fun `fetchState maps a malformed 200 body to Failure`() =
        runTest {
            val api = ReferralApiClient(client { respond("not json", HttpStatusCode.OK, JSON_HEADERS) })
            assertEquals(ReferralStateResult.Failure, api.fetchState())
        }
}
