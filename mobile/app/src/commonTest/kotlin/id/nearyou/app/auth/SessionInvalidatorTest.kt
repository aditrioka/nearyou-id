package id.nearyou.app.auth

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 9.1 — regression for the dropped-signal bug (mobile-session-expiry-and-proactive-refresh, D1).
 * [SessionInvalidator] now carries the re-route signal over a buffered consume-once `Channel`
 * (`CONFLATED`) rather than a `MutableSharedFlow(replay = 0)`. These assert the two delivery
 * guarantees the prior carrier violated:
 *  - an `invalidate()` that fires BEFORE any collector subscribes is buffered and delivered to the
 *    first collector (the cold-start strand bug), and
 *  - the signal is consume-once: a second collector after re-login does NOT replay a stale invalidate
 *    (no spurious re-route loop).
 */
class SessionInvalidatorTest {
    @Test
    fun invalidateBeforeSubscribe_isDeliveredToTheFirstCollector() =
        runTest {
            val store = InMemoryTokenStore(TokenPair("at", "rt", 123L))
            val invalidator = SessionInvalidator(store)

            // Fire BEFORE anyone collects (cold start / no screen hosting the observer).
            invalidator.invalidate()

            // The first collector still observes the buffered signal (a replay=0 SharedFlow would drop it).
            val signal = withTimeoutOrNull(1_000) { invalidator.sessionExpired.first() }
            assertNotNull(signal, "a pre-subscription invalidate must be delivered to the first collector")
            assertNull(store.read(), "invalidate() clears the whole token pair before signalling")
        }

    @Test
    fun consumeOnce_noStaleReplayAfterTheSignalIsConsumed() =
        runTest {
            val invalidator = SessionInvalidator(InMemoryTokenStore(TokenPair("at", "rt", 1L)))

            invalidator.invalidate()
            // First collector consumes the one buffered signal…
            assertNotNull(withTimeoutOrNull(1_000) { invalidator.sessionExpired.first() })
            // …and a later (re-login) collector does NOT see a replayed invalidate (consume-once).
            assertNull(
                withTimeoutOrNull(200) { invalidator.sessionExpired.first() },
                "a re-mounted observer must NOT replay the prior (consumed) invalidation",
            )
        }

    @Test
    fun carrierBuffersWhenNoSubscriber_provingItIsNotAReplayZeroSharedFlow() =
        runTest {
            // Behavioral proof of the "carrier is not a replay=0 SharedFlow" scenario: a replay=0
            // SharedFlow's tryEmit with no active subscriber is lost, so a subsequent collector would
            // hang here; the buffered Channel delivers the signal instead.
            val invalidator = SessionInvalidator(InMemoryTokenStore(TokenPair("at", "rt", 1L)))
            invalidator.invalidate()
            assertNotNull(
                withTimeoutOrNull(1_000) { invalidator.sessionExpired.first() },
                "the carrier must buffer a no-subscriber emission (not a replay=0 SharedFlow)",
            )
        }
}
