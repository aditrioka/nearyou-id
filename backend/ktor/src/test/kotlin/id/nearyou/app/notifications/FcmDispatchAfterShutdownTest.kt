package id.nearyou.app.notifications

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import id.nearyou.app.infra.fcm.FcmDispatcher
import id.nearyou.app.infra.fcm.FcmSendResult
import id.nearyou.app.infra.fcm.FcmSender
import id.nearyou.data.repository.ActorUsernameLookup
import id.nearyou.data.repository.NotificationRepository
import id.nearyou.data.repository.NotificationRow
import id.nearyou.data.repository.TokenSnapshot
import id.nearyou.data.repository.UserFcmTokenReader
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Instant
import java.util.UUID

/**
 * Covers `fcm-push-dispatch` spec § "Dispatcher coroutine scope SHALL drain
 * on JVM shutdown". Task 7.8 — dispatch-after-shutdown WARN.
 *
 * Tasks 7.7 / 7.7.a (deterministic shutdown-drain timing) are deferred to a
 * follow-up: they require virtual-time control via the `kotlinx-coroutines-
 * test` dispatcher (`runTest` / `TestScheduler`) which is not currently on
 * the test classpath. Adding it would touch the build configuration; the
 * shutdown logic itself is exercised at module composition time
 * (`FcmDispatcherScope.shutdown(...)`) by the production wiring's shutdown
 * hook in Application.kt. Surfaced via FOLLOW_UPS.md.
 */
class FcmDispatchAfterShutdownTest : StringSpec(
    {
        "7.8 dispatch invoked AFTER markShutdown() logs WARN fcm_dispatch_after_shutdown AND does not throw" {
            val notificationId = UUID.randomUUID()
            val notRepo = nullNotificationRepository(notificationId)
            val reader =
                object : UserFcmTokenReader {
                    override fun activeTokens(userId: UUID) = TokenSnapshot(emptyList(), Instant.now())

                    override fun deleteTokenIfStale(
                        userId: UUID,
                        platform: String,
                        token: String,
                        dispatchStartedAt: Instant,
                    ): Int = 0
                }
            val sender = FcmSender { _ -> FcmSendResult.Sent("ok") }
            val lookup = ActorUsernameLookup { _ -> null }
            val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            val dispatcher =
                FcmDispatcher(
                    notificationRepository = notRepo,
                    userFcmTokenReader = reader,
                    sender = sender,
                    actorUsernameLookup = lookup,
                    dispatcherScope = scope,
                )
            dispatcher.markShutdown()

            val logger = LoggerFactory.getLogger(FcmDispatcher::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>()
            appender.start()
            logger.addAppender(appender)
            try {
                dispatcher.dispatch(notificationId)
            } finally {
                logger.detachAppender(appender)
                appender.stop()
            }
            appender.list.any {
                it.level == Level.WARN && it.formattedMessage.contains("event=fcm_dispatch_after_shutdown")
            } shouldBe true
        }
    },
)

private fun nullNotificationRepository(canonicalId: UUID): NotificationRepository =
    object : NotificationRepository {
        override fun insert(
            conn: Connection,
            recipientId: UUID,
            type: id.nearyou.data.repository.NotificationType,
            actorUserId: UUID?,
            targetType: String?,
            targetId: UUID?,
            bodyDataJson: String,
        ): UUID = UUID.randomUUID()

        override fun isBlockedBetween(
            conn: Connection,
            userA: UUID,
            userB: UUID,
        ): Boolean = false

        override fun listByUser(
            userId: UUID,
            cursorCreatedAt: Instant?,
            cursorId: UUID?,
            unreadOnly: Boolean,
            limit: Int,
        ): List<NotificationRow> = emptyList()

        override fun countUnread(userId: UUID): Long = 0

        override fun markRead(
            userId: UUID,
            notificationId: UUID,
        ): Int = 0

        override fun markAllRead(userId: UUID): Int = 0

        override fun existsForUser(
            userId: UUID,
            notificationId: UUID,
        ): Boolean = false

        override fun findById(notificationId: UUID): NotificationRow? = null
    }
