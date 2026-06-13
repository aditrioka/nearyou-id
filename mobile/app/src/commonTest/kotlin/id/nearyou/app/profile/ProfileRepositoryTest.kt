package id.nearyou.app.profile

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private val JSON = headersOf("Content-Type", "application/json")

class ProfileRepositoryTest {
    private fun repo(handler: MockRequestHandler): ProfileRepository {
        val client: HttpClient =
            HttpClientFactory.create(
                installTimeouts = false,
                apiBaseUrl = "http://test.local",
                tokenStore = InMemoryTokenStore(),
                sessionInvalidator = SessionInvalidator(InMemoryTokenStore()),
                engine = MockEngine(handler),
                installLogging = false,
                nowMillis = { 0L },
            )
        return ProfileRepository(ProfileApiClient(client))
    }

    @Test
    fun `loadProfile 200 maps to Loaded with the domain projection`() =
        runTest {
            val outcome =
                repo {
                    respond(
                        """
                        {"userId":"u1","username":"raka.jkt","displayName":"Raka","bio":null,"followerCount":3,
                         "followingCount":5,"isSelf":false,"followedByViewer":true,"isPremium":true}
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        JSON,
                    )
                }.loadProfile("u1")
            val loaded = assertIs<ProfileOutcome.Loaded>(outcome)
            assertEquals("raka.jkt", loaded.profile.username)
            assertEquals(3, loaded.profile.followerCount)
            assertEquals(true, loaded.profile.isPremium)
            assertEquals(true, loaded.profile.followedByViewer)
        }

    @Test
    fun `loadProfile constant 404 maps to the single NotFound - no per-cause branch`() =
        runTest {
            val outcome =
                repo { respond("""{"error":{"code":"user_not_found"}}""", HttpStatusCode.NotFound, JSON) }
                    .loadProfile("u1")
            assertEquals(ProfileOutcome.NotFound, outcome)
        }

    @Test
    fun `loadProfile 5xx maps to NetworkError`() =
        runTest {
            val outcome = repo { respond("", HttpStatusCode.InternalServerError) }.loadProfile("u1")
            assertEquals(ProfileOutcome.NetworkError, outcome)
        }

    @Test
    fun `follow 204 maps to Followed`() =
        runTest {
            assertEquals(FollowToggleOutcome.Followed, repo { respond("", HttpStatusCode.NoContent) }.follow("u1"))
        }

    @Test
    fun `follow constant 404 maps to TargetGone`() =
        runTest {
            val outcome =
                repo { respond("""{"error":{"code":"user_not_found"}}""", HttpStatusCode.NotFound, JSON) }.follow("u1")
            assertEquals(FollowToggleOutcome.TargetGone, outcome)
        }

    @Test
    fun `follow 429 maps to RateLimited with the retry-after`() =
        runTest {
            val outcome =
                repo {
                    respond(
                        """{"error":{"code":"rate_limited"}}""",
                        HttpStatusCode.TooManyRequests,
                        headersOf("Content-Type" to listOf("application/json"), "Retry-After" to listOf("90")),
                    )
                }.follow("u1")
            assertEquals(FollowToggleOutcome.RateLimited(90L), outcome)
        }

    @Test
    fun `unfollow 204 maps to Unfollowed`() =
        runTest {
            assertEquals(FollowToggleOutcome.Unfollowed, repo { respond("", HttpStatusCode.NoContent) }.unfollow("u1"))
        }

    @Test
    fun `block 204 maps to Blocked`() =
        runTest {
            assertEquals(BlockOutcome.Blocked, repo { respond("", HttpStatusCode.NoContent) }.block("u1"))
        }

    @Test
    fun `report 409 duplicate_report maps to Duplicate`() =
        runTest {
            val outcome =
                repo { respond("""{"error":{"code":"duplicate_report"}}""", HttpStatusCode.Conflict, JSON) }
                    .report("u1", ReportReasonCategory.SPAM, null)
            assertEquals(ReportOutcome.Duplicate, outcome)
        }

    @Test
    fun `report 409 with the stale reports_duplicate code does NOT map to Duplicate`() =
        runTest {
            // Guard against re-introducing the stale `reports.duplicate` code: a 409 carrying it is NOT
            // the shipped duplicate signal → it falls through to the retryable NetworkError, never Duplicate.
            val outcome =
                repo { respond("""{"error":{"code":"reports.duplicate"}}""", HttpStatusCode.Conflict, JSON) }
                    .report("u1", ReportReasonCategory.SPAM, null)
            assertEquals(ReportOutcome.NetworkError, outcome)
        }

    @Test
    fun `report 429 maps to RateLimited`() =
        runTest {
            val outcome =
                repo {
                    respond(
                        "",
                        HttpStatusCode.TooManyRequests,
                        headersOf("Retry-After", "60"),
                    )
                }.report("u1", ReportReasonCategory.OTHER, "note")
            assertEquals(ReportOutcome.RateLimited(60L), outcome)
        }
}
