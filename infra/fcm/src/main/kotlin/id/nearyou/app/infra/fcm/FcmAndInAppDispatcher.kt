package id.nearyou.app.infra.fcm

import id.nearyou.data.repository.NotificationDispatcher
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Composite [NotificationDispatcher] that invokes the FCM delegate and the
 * in-app log delegate in call-order. "First" / "second" describe call-order,
 * NOT completion-order:
 *
 *   - The FCM delegate enqueues coroutines on the dispatcher scope and returns
 *     synchronously (per `openspec/changes/fcm-push-dispatch/design.md` D2).
 *   - The in-app delegate is a synchronous log line (the audit trail).
 *
 * Per `design.md` D14: each delegate call is wrapped in its own try/catch.
 * Any caught exception is logged at ERROR severity with the structured event
 * `fcm_composite_dispatcher_unexpected_error`, the affected delegate, and the
 * notification id. A counter metric is incremented so a Cloud Monitoring
 * "rate > 5/min" alert pages on noisy regressions while a one-shot ERROR does
 * not. The exception is NEVER propagated back to the emit site — silent
 * swallowing is forbidden, but the audit-trail log line MUST always fire even
 * when one delegate misbehaves.
 *
 * Round-3 review of PR #60 downgraded this from FATAL to ERROR + counter
 * metric — FATAL would page on-call for every emit on a latent NPE in the
 * in-app log path, which is wrong-shaped for a defense-in-depth catch.
 */
class FcmAndInAppDispatcher(
    private val fcm: NotificationDispatcher,
    private val inApp: NotificationDispatcher,
    private val errorCounter: (delegate: String) -> Unit = { _ -> },
) : NotificationDispatcher {
    override fun dispatch(notificationId: UUID) {
        // Call-order: FCM enqueue first (returns synchronously per D2);
        // in-app log second.
        invokeSafely("FcmDispatcher", notificationId) { fcm.dispatch(notificationId) }
        invokeSafely("InAppOnlyDispatcher", notificationId) { inApp.dispatch(notificationId) }
    }

    private inline fun invokeSafely(
        delegateName: String,
        notificationId: UUID,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (t: Throwable) {
            log.error(
                "event=fcm_composite_dispatcher_unexpected_error delegate={} notification_id={} error_class={}",
                delegateName,
                notificationId,
                t.javaClass.simpleName,
                t,
            )
            try {
                errorCounter(delegateName)
            } catch (counterError: Throwable) {
                log.warn(
                    "event=fcm_composite_dispatcher_counter_error delegate={} notification_id={} error_class={}",
                    delegateName,
                    notificationId,
                    counterError.javaClass.simpleName,
                )
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(FcmAndInAppDispatcher::class.java)

        /**
         * Default in-process counter for the unexpected-error metric, keyed by
         * delegate name. Production wiring MAY swap this for an OTEL /
         * Cloud-Monitoring-backed counter via the constructor parameter.
         */
        private val counters = mutableMapOf<String, AtomicLong>()

        @JvmStatic
        @Synchronized
        fun incrementInProcess(delegate: String) {
            counters.getOrPut(delegate) { AtomicLong(0L) }.incrementAndGet()
        }

        @JvmStatic
        @Synchronized
        fun snapshotCounter(delegate: String): Long = counters[delegate]?.get() ?: 0L
    }
}
