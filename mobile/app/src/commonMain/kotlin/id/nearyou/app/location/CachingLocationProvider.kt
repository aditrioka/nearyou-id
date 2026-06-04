package id.nearyou.app.location

import id.nearyou.app.timeline.LocationProvider
import id.nearyou.distance.LatLng
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * In-process warm-fix decorator over a real device [LocationProvider]
 * (`mobile-location-acquisition-tuning` design D1 / D3 / D6 / D7). Holds the last-good [LatLng] with
 * the [TimeMark] it was acquired at; [current] returns the held fix while it is younger than
 * [stalenessWindow] (exclusive `<` bound), otherwise delegates to [delegate] and stores the fresh
 * result. So a second consumer — the composer FAB right after the Nearby first-load — reuses one warm
 * fix instead of cold-acquiring again.
 *
 * - **Injected clock seam** ([timeSource], default [TimeSource.Monotonic], D3): staleness is an
 *   elapsed-duration question decided via a monotonic mark, never a wall-clock / `Clock.System.now()`
 *   call — immune to wall-clock jumps and `commonTest`-able with a `TestTimeSource`.
 * - **Single-flight** ([mutex], D7): concurrent [current] calls on a cold/expired holder serialise
 *   through one critical section, so they share exactly one underlying acquisition rather than each
 *   delegating independently — the real Nearby-and-composer-at-cold-start trigger this decorator
 *   exists to fix. The second caller, once it takes the lock, observes the freshly-stored warm fix and
 *   returns it without re-acquiring.
 * - **No coordinate logging** (D6): the fix is held in a private field and returned to the caller only;
 *   this class makes no logging/diagnostic call (test-enforced by `LocationSourceGuardTest`).
 */
class CachingLocationProvider(
    private val delegate: LocationProvider,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val stalenessWindow: Duration = LocationTuning.inProcessWarmStaleness,
) : LocationProvider {
    private val mutex = Mutex()
    private var heldFix: LatLng? = null
    private var heldAt: TimeMark? = null

    override suspend fun current(): LatLng =
        mutex.withLock {
            val fix = heldFix
            val mark = heldAt
            if (fix != null && mark != null && mark.elapsedNow() < stalenessWindow) {
                fix
            } else {
                delegate.current().also {
                    heldFix = it
                    heldAt = timeSource.markNow()
                }
            }
        }
}
