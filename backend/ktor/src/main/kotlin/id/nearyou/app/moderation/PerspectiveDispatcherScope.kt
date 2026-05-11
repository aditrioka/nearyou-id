package id.nearyou.app.moderation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import kotlin.coroutines.CoroutineContext

/**
 * Long-lived supervised coroutine scope owning the async Layer 3 (Perspective API)
 * dispatch lifecycle for `text-moderation-perspective-api-layer`. Single class owning
 * BOTH the lifecycle (long-lived `SupervisorJob`-rooted scope, bounded shutdown drain)
 * AND the dispatch-after-shutdown WARN behavior — deliberate divergence from the
 * `:infra:fcm` `FcmDispatcherScope` + `FcmDispatcher` split per design.md Decision 4
 * (FCM has substantial per-dispatch logic; Perspective dispatch is a single timed HTTP
 * call so collapsing both responsibilities keeps the surface lean).
 *
 * Internal shape:
 *  - `scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("perspective-dispatch"))`
 *  - `@Volatile shutdown: Boolean` flag prevents new dispatches after shutdown initiates.
 *
 * `dispatch(parentContext, block)` launches the block in `scope`, **propagating the
 * caller's coroutine context as a parent context** so OTel trace context (and the
 * `traceparent` header on the outbound Perspective HTTP call) inherits per design.md
 * Decision 13. The Layer 3 dispatch span is parented under the originating request span
 * (e.g., `POST /api/v1/posts`).
 *
 * Post-shutdown semantics:
 *  - `dispatch(...)` after shutdown emits `event=perspective_dispatch_after_shutdown`
 *    WARN and returns `null` (silent no-op; does NOT throw to the caller).
 *  - `shutdown(drainMillis)` flips the volatile flag THEN cancel-and-joins the scope
 *    with a 5-second budget; in-flight dispatches that exceed the budget are cancelled
 *    and a `event=perspective_dispatch_drain_exceeded cancelled_count=<N>` WARN is
 *    emitted.
 */
class PerspectiveDispatcherScope private constructor(
    val scope: CoroutineScope,
) {
    @Volatile
    private var shutdownInitiated: Boolean = false

    /**
     * Launches [block] in the dispatcher scope, propagating the caller's
     * [parentContext] so the OTel trace context inherits. Returns the [Job] for
     * caller-side observation (typically NOT awaited — Layer 3 is fire-and-forget),
     * or `null` if the dispatcher has already been shut down.
     *
     * Pre-shutdown: the block is launched with `parentContext` as the parent context
     * (so `traceparent` flows to the outbound HTTP call) and the scope's coroutine
     * context (`SupervisorJob + Dispatchers.IO + CoroutineName`).
     *
     * Post-shutdown: emits a structured WARN log + returns `null`. Does NOT throw.
     */
    fun dispatch(
        parentContext: CoroutineContext,
        block: suspend () -> Unit,
    ): Job? {
        if (shutdownInitiated) {
            log.warn(
                "event={}",
                EVENT_DISPATCH_AFTER_SHUTDOWN,
            )
            return null
        }
        return try {
            scope.launch(parentContext) {
                block()
            }
        } catch (t: Throwable) {
            // Scope rejected the launch (Job already cancelled by a shutdown that
            // crossed our flag check). Suppress + log; mirror the FcmDispatcher
            // launch-rejected pattern.
            log.warn(
                "event={} reason=launch_rejected error_class={}",
                EVENT_DISPATCH_AFTER_SHUTDOWN,
                t.javaClass.simpleName,
            )
            null
        }
    }

    /**
     * Cancel-and-join the scope with a [drainMillis] budget. In-flight dispatches
     * that complete within the budget reach their COMMIT normally; dispatches that
     * exceed the budget are cancelled (cooperative cancellation via standard
     * coroutine semantics — Ktor HTTP client respects cancellation; engine-level
     * timeouts ensure socket close).
     *
     * Emits `event=perspective_dispatch_drain_exceeded cancelled_count=<N>` WARN if
     * any in-flight coroutines were cancelled mid-dispatch. Always emits an INFO log
     * with the drain budget for ops visibility.
     */
    fun shutdown(drainMillis: Long = DEFAULT_DRAIN_MILLIS) {
        // 1. Set the flag FIRST so concurrent dispatch(...) calls observe shutdown.
        shutdownInitiated = true

        // 2. Snapshot in-flight children before drain.
        val rootJob = scope.coroutineContext[Job]
        val inFlightBefore = rootJob?.children?.count { it.isActive } ?: 0

        // 3. Drain with budget.
        runBlocking {
            withTimeoutOrNull(drainMillis) {
                rootJob?.cancelAndJoin()
            }
        }

        // 4. Count children cancelled mid-dispatch (still alive after drain budget).
        val cancelledCount = rootJob?.children?.count { it.isCancelled } ?: 0
        if (cancelledCount > 0) {
            log.warn(
                "event={} cancelled_count={} drain_ms={}",
                EVENT_DISPATCH_DRAIN_EXCEEDED,
                cancelledCount,
                drainMillis,
            )
        }
        log.info(
            "event=perspective_dispatcher_scope_shutdown drain_ms={} in_flight_before={}",
            drainMillis,
            inFlightBefore,
        )
    }

    /** Read-only diagnostic for tests. */
    internal val isShutdown: Boolean
        get() = shutdownInitiated

    companion object {
        const val DEFAULT_DRAIN_MILLIS: Long = 5_000L
        const val EVENT_DISPATCH_AFTER_SHUTDOWN: String = "perspective_dispatch_after_shutdown"
        const val EVENT_DISPATCH_DRAIN_EXCEEDED: String = "perspective_dispatch_drain_exceeded"
        const val EVENT_DISPATCH_UNHANDLED_EXCEPTION: String = "perspective_dispatch_unhandled_exception"

        private val log = LoggerFactory.getLogger(PerspectiveDispatcherScope::class.java)

        /**
         * Defense-in-depth [CoroutineExceptionHandler] for the dispatcher scope. The
         * orchestrator's contract is to absorb every non-cancellation failure and return
         * `Outcome.NoAction`, so an unhandled exception escaping to the scope's handler
         * is a contract bug. We log it as ERROR rather than letting it propagate to the
         * JVM's default uncaught-exception handler (which `kotlinx-coroutines-test`
         * surfaces as `UncaughtExceptionsBeforeTest` on the next test's start, causing
         * cross-test pollution in CI).
         *
         * `CancellationException` is allowed to propagate per coroutine convention.
         */
        private val UNHANDLED_HANDLER: CoroutineExceptionHandler =
            CoroutineExceptionHandler { _, throwable ->
                if (throwable is CancellationException) return@CoroutineExceptionHandler
                log.error(
                    "event={} error_class={} reason={}",
                    EVENT_DISPATCH_UNHANDLED_EXCEPTION,
                    throwable.javaClass.simpleName,
                    throwable.message ?: "(no message)",
                    throwable,
                )
            }

        /**
         * Production factory. Returns a long-lived scope rooted in `SupervisorJob() +
         * Dispatchers.IO + CoroutineName("perspective-dispatch") + [UNHANDLED_HANDLER]`.
         * Caller wires `shutdown(...)` into the JVM shutdown hook.
         */
        fun production(): PerspectiveDispatcherScope {
            val scope =
                CoroutineScope(
                    SupervisorJob() +
                        Dispatchers.IO +
                        CoroutineName("perspective-dispatch") +
                        UNHANDLED_HANDLER,
                )
            return PerspectiveDispatcherScope(scope = scope)
        }

        /**
         * Test factory. The caller supplies the scope; useful with `runTest` +
         * `TestScheduler`. Note: callers SHOULD include [UNHANDLED_HANDLER] in the
         * supplied scope's context if the test relies on the scope to absorb stray
         * exceptions (e.g., testApplication-based integration tests) — see
         * [forTestWithDefensiveHandler] below for a pre-wired variant.
         */
        fun forTest(scope: CoroutineScope): PerspectiveDispatcherScope = PerspectiveDispatcherScope(scope = scope)

        /**
         * Test factory that pre-installs [UNHANDLED_HANDLER] on the supplied scope's
         * coroutine context. Use this in testApplication-based integration tests so
         * any unhandled exception in a dispatched coroutine doesn't leak to the JVM's
         * default uncaught-exception handler (which would surface as
         * `UncaughtExceptionsBeforeTest` on a subsequent test's start).
         */
        fun forTestWithDefensiveHandler(scope: CoroutineScope): PerspectiveDispatcherScope {
            val wrappedScope = CoroutineScope(scope.coroutineContext + UNHANDLED_HANDLER)
            return PerspectiveDispatcherScope(scope = wrappedScope)
        }
    }
}
