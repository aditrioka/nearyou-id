package id.nearyou.app.infra.fcm

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.measureTimeMillis

/**
 * Deterministic coverage for `FcmDispatcherScope.shutdown(drainMillis)` —
 * `fcm-push-dispatch` spec § "Dispatcher coroutine scope SHALL drain on JVM
 * shutdown" (tasks 7.7 + 7.7.a), previously deferred via the earlier
 * `fcm-shutdown-drain-deterministic-tests` follow-up.
 *
 * Mechanism note: the deferred entry proposed `runTest` + `TestScheduler`
 * virtual time. That does NOT fit the production code — `shutdown()` wraps
 * `withTimeoutOrNull(drainMillis) { job.cancelAndJoin() }` in a real
 * `runBlocking`, which spins its own real event loop on the calling thread, so
 * a `TestScheduler` cannot drive its timeout. The drain ceiling is therefore a
 * real wall-clock bound. These tests instead model an in-flight FCM dispatch
 * as a BLOCKING `Thread.sleep` (faithful — the SDK's `FirebaseMessaging.send`
 * is a blocking call that coroutine cancellation cannot abort mid-flight) and
 * assert the three behavioural outcomes with 15-50x time margins so they stay
 * deterministic under CI load:
 *   - work finishing within the budget drains (its effect lands),
 *   - work exceeding the budget is abandoned,
 *   - the budget deterministically bounds the shutdown wait.
 */
class FcmDispatcherScopeShutdownTest : StringSpec(
    {
        "in-flight dispatch finishing within the drain budget completes (drained) — task 7.7" {
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val fcmScope = FcmDispatcherScope.forTest(scope)
            val started = AtomicBoolean(false)
            val completed = AtomicBoolean(false)
            scope.launch {
                started.set(true)
                // Models a blocking FCM send: cancellation cannot abort it, so it
                // runs to completion and its effect (completed=true) lands.
                Thread.sleep(100)
                completed.set(true)
            }
            while (!started.get()) Thread.sleep(5)

            val elapsed = measureTimeMillis { fcmScope.shutdown(drainMillis = 5_000L) }

            completed.get() shouldBe true
            elapsed shouldBeLessThan 5_000L
        }

        "in-flight dispatch exceeding the drain budget is abandoned — task 7.7" {
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val fcmScope = FcmDispatcherScope.forTest(scope)
            val started = AtomicBoolean(false)
            val completed = AtomicBoolean(false)
            scope.launch {
                started.set(true)
                // 3s "hung" send vs a 200ms drain budget → abandoned.
                Thread.sleep(3_000)
                completed.set(true)
            }
            while (!started.get()) Thread.sleep(5)

            val elapsed = measureTimeMillis { fcmScope.shutdown(drainMillis = 200L) }

            completed.get() shouldBe false
            // Returned ~at the 200ms budget, NOT after the 3s work.
            elapsed shouldBeLessThan 2_000L
        }

        "the drain budget deterministically bounds the shutdown wait — task 7.7.a boundary" {
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val fcmScope = FcmDispatcherScope.forTest(scope)
            val started = AtomicBoolean(false)
            scope.launch {
                started.set(true)
                Thread.sleep(3_000)
            }
            while (!started.get()) Thread.sleep(5)

            val elapsed = measureTimeMillis { fcmScope.shutdown(drainMillis = 300L) }

            // Waited ~the budget (lower bound allows timer granularity slack),
            // but nowhere near the 3s work → the budget is the deterministic ceiling.
            elapsed shouldBeGreaterThanOrEqualTo 250L
            elapsed shouldBeLessThan 2_000L
        }

        "shutdown emits the drain-complete log event" {
            val logger = LoggerFactory.getLogger(FcmDispatcherScope::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            try {
                val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                FcmDispatcherScope.forTest(scope).shutdown(drainMillis = 100L)

                appender.list.any {
                    it.formattedMessage.contains("event=fcm_dispatcher_scope_shutdown")
                } shouldBe true
            } finally {
                logger.detachAppender(appender)
            }
        }
    },
)
