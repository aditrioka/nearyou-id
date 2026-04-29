package id.nearyou.app.infra.fcm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import kotlin.coroutines.CoroutineContext

/**
 * Production [CoroutineScope] for [FcmDispatcher]. Uses
 * `Dispatchers.IO.limitedParallelism(8)` so the FCM Admin SDK's blocking
 * sync API (`FirebaseMessaging.send(message)`) runs on a bounded worker pool,
 * isolated from the Ktor request-handling dispatcher. A [SupervisorJob] root
 * means a per-launch cancellation does NOT cascade to peer launches —
 * required by spec § "Per-launch CancellationException does not cascade to
 * peer launches".
 *
 * Test profiles inject a different scope (typically `Dispatchers.Unconfined`)
 * so dispatch effects are observable synchronously.
 */
class FcmDispatcherScope private constructor(
    private val ctx: CoroutineContext,
    val scope: CoroutineScope,
) {
    /**
     * Cancel + join the scope with a 5-second drain budget. Called by the
     * JVM shutdown hook installed by production wiring. In-flight FCM
     * dispatches that complete within the timeout reach FCM normally;
     * dispatches that exceed the timeout are abandoned (the recipient sees
     * the in-app notification on next app open per the
     * docs/04-Architecture.md:558 fallback).
     */
    fun shutdown(drainMillis: Long = 5_000L) {
        runBlocking {
            withTimeoutOrNull(drainMillis) {
                ctx[kotlinx.coroutines.Job]?.cancelAndJoin()
            }
        }
        log.info("event=fcm_dispatcher_scope_shutdown drain_ms={}", drainMillis)
    }

    companion object {
        private val log = LoggerFactory.getLogger(FcmDispatcherScope::class.java)

        @OptIn(ExperimentalCoroutinesApi::class)
        @JvmStatic
        fun production(parallelism: Int = 8): FcmDispatcherScope {
            val job = SupervisorJob()
            val ctx = Dispatchers.IO.limitedParallelism(parallelism) + job
            return FcmDispatcherScope(ctx = ctx, scope = CoroutineScope(ctx))
        }

        @JvmStatic
        fun forTest(scope: CoroutineScope): FcmDispatcherScope = FcmDispatcherScope(ctx = scope.coroutineContext, scope = scope)
    }
}
