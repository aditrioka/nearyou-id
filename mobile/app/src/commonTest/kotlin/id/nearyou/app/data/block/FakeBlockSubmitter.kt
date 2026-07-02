package id.nearyou.app.data.block

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode

/**
 * Test-only [BlockSubmitter] returning a pre-programmed [BlockOutcome] and recording the submitted
 * target user ids so a test can drive a specific block path WITHOUT a backend and assert the target
 * (e.g. "the reply block submitted the reply `author_id`" / "Batal submits zero times"). The super's
 * client is a never-called MockEngine (the override returns the programmed outcome before any HTTP
 * call), so this fake exercises zero transport — mirroring `FakeReportSubmitter`.
 */
class FakeBlockSubmitter(
    private val outcome: BlockOutcome = BlockOutcome.Blocked,
) : BlockSubmitter(neverCalledClient()) {
    var submitCount: Int = 0
        private set
    var lastUserId: String? = null
        private set

    override suspend fun submit(userId: String): BlockOutcome {
        submitCount++
        lastUserId = userId
        return outcome
    }
}

private fun neverCalledClient() =
    HttpClientFactory.create(
        installTimeouts = false,
        apiBaseUrl = "http://test.local",
        tokenStore = InMemoryTokenStore(),
        sessionInvalidator = SessionInvalidator(InMemoryTokenStore()),
        engine = MockEngine { respond("", HttpStatusCode.NoContent) },
        installLogging = false,
        nowMillis = { 0L },
    )
