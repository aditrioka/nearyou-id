package id.nearyou.app.location

import id.nearyou.app.timeline.LocationProvider
import id.nearyou.distance.LatLng
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** Counting fake device [LocationProvider] for the decorator tests: returns a fixed (distinctive) fix
 *  and records how many times `current()` actually reaches the device, so a test can assert reuse vs
 *  re-acquisition. Optionally parks at [gate] (single-flight test) or throws (failure-propagation
 *  test) before returning. */
private class CountingLocationProvider(
    private val fix: LatLng = LatLng(lat = -7.25, lng = 112.75),
    private val gate: CompletableDeferred<Unit>? = null,
    private val throwOnCurrent: Throwable? = null,
) : LocationProvider {
    var count = 0
        private set

    override suspend fun current(): LatLng {
        count++
        gate?.await()
        throwOnCurrent?.let { throw it }
        return fix
    }
}

/**
 * `commonTest` coverage of [CachingLocationProvider] (tasks 6.1/6.2/6.3/6.6/6.7) via a counting fake
 * device source and an injected `kotlin.time.TestTimeSource` (stable since Kotlin 2.3 — no opt-in).
 * Asserts the warm-fix reuse window (exclusive `<` bound), staleness expiry, failure propagation, and
 * the single-flight guarantee — without touching any platform location API.
 */
class CachingLocationProviderTest {
    private val fix = LatLng(lat = -7.25, lng = 112.75)
    private val window = 60.seconds

    @Test
    fun `warm fix is reused across consumers within the window`() =
        runTest {
            val time = TestTimeSource()
            val device = CountingLocationProvider(fix)
            val provider = CachingLocationProvider(device, time, window)

            val first = provider.current()
            time += window - 1.seconds // elapsed still inside the window
            val second = provider.current()

            assertEquals(1, device.count, "a warm fix within the window must not re-acquire")
            assertEquals(fix, first, "the first call returns the device fix")
            assertEquals(fix, second, "the second call returns the same warm fix")
        }

    @Test
    fun `an expired fix is re-acquired`() =
        runTest {
            val time = TestTimeSource()
            val device = CountingLocationProvider(fix)
            val provider = CachingLocationProvider(device, time, window)

            provider.current()
            time += window + 1.seconds // elapsed past the window
            provider.current()

            assertEquals(2, device.count, "a fix past the window must delegate again")
        }

    @Test
    fun `a fix exactly at the window is re-acquired pinning the exclusive bound`() =
        runTest {
            val time = TestTimeSource()
            val device = CountingLocationProvider(fix)
            val provider = CachingLocationProvider(device, time, window)

            provider.current()
            time += window // elapsed == window → expired under the exclusive `elapsed < window` bound
            provider.current()

            assertEquals(2, device.count, "elapsed == window must be treated as expired (exclusive bound)")
        }

    @Test
    fun `a delegate failure propagates and no stale or sentinel fix is returned`() =
        runTest {
            val device = CountingLocationProvider(fix, throwOnCurrent = LocationUnavailableException())
            val provider = CachingLocationProvider(device, TestTimeSource(), window)

            assertFailsWith<LocationUnavailableException> { provider.current() }
            assertEquals(1, device.count, "the delegate was invoked exactly once and its failure propagated")
        }

    @OptIn(ExperimentalCoroutinesApi::class) // runCurrent() forces the cold-holder contention window
    @Test
    fun `concurrent cold holder calls share one acquisition`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val device = CountingLocationProvider(fix, gate = gate)
            val provider = CachingLocationProvider(device, TestTimeSource(), window)

            val a = async { provider.current() }
            val b = async { provider.current() }
            // One coroutine enters the critical section and parks at the gate; the other blocks on the
            // mutex (it never reaches the device). Then release the parked acquisition.
            runCurrent()
            gate.complete(Unit)
            val results = awaitAll(a, b)

            assertEquals(1, device.count, "concurrent cold-holder calls must single-flight to one acquisition")
            assertEquals(listOf(fix, fix), results, "both callers observe the one freshly-acquired fix")
        }
}
