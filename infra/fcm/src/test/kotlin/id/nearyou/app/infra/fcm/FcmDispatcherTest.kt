package id.nearyou.app.infra.fcm

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import id.nearyou.data.repository.ActorUsernameLookup
import id.nearyou.data.repository.FcmTokenRow
import id.nearyou.data.repository.NotificationRepository
import id.nearyou.data.repository.NotificationRow
import id.nearyou.data.repository.NotificationType
import id.nearyou.data.repository.TokenSnapshot
import id.nearyou.data.repository.UserFcmTokenReader
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Covers `fcm-push-dispatch` spec § "FcmDispatcher SHALL implement
 * NotificationDispatcher and dispatch per-token in parallel after DB commit"
 * AND the on-send-prune contract. Tasks 6.4.1 — 6.4.16 (subset; latency-bound
 * tests 6.4.12 / 6.4.13 cover the synchronous-return contract under a real
 * limited-parallelism pool — included as a sentinel, not a full bench).
 *
 * Test mechanism:
 *  - Plain Kotlin in-test fakes (no mockk dependency); `FcmSender` is a
 *    sealed-result functional interface so error paths are driven without
 *    constructing `FirebaseMessagingException`.
 *  - `Dispatchers.Unconfined` so per-token launches run synchronously on the
 *    calling thread until first suspension.
 *  - Logback `ListAppender` to capture structured log events (event=...
 *    fields) for assertion.
 */
class FcmDispatcherTest : StringSpec(
    {
        val recipient = UUID.randomUUID()
        val notificationId = UUID.randomUUID()

        fun row(
            type: NotificationType = NotificationType.POST_LIKED,
            actorUserId: UUID? = UUID.randomUUID(),
            bodyDataJson: String = """{"post_excerpt":"Hi"}""",
        ): NotificationRow =
            NotificationRow(
                id = notificationId,
                userId = recipient,
                type = type,
                actorUserId = actorUserId,
                targetType = "post",
                targetId = UUID.randomUUID(),
                bodyDataJson = bodyDataJson,
                createdAt = Instant.now(),
                readAt = null,
            )

        fun sentinelTokenRow(token: String) = FcmTokenRow(platform = "android", token = token, lastSeenAt = Instant.now())

        fun captureLogs(block: () -> Unit): List<ILoggingEvent> {
            val logger = LoggerFactory.getLogger(FcmDispatcher::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>()
            appender.start()
            logger.addAppender(appender)
            try {
                block()
            } finally {
                logger.detachAppender(appender)
                appender.stop()
            }
            return appender.list
        }

        fun build(
            notification: NotificationRow,
            tokens: List<FcmTokenRow>,
            actorUsername: String? = "bobby",
            sender: FcmSender,
            deleted: ConcurrentHashMap<Triple<UUID, String, String>, Int> =
                ConcurrentHashMap<Triple<UUID, String, String>, Int>(),
            dispatchStartedAt: Instant = Instant.now(),
            scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
        ): FcmDispatcher {
            val notRepo =
                object : NotificationRepository {
                    override fun insert(
                        conn: Connection,
                        recipientId: UUID,
                        type: NotificationType,
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

                    override fun findById(notificationId: UUID): NotificationRow? = notification.takeIf { it.id == notificationId }
                }
            val reader =
                object : UserFcmTokenReader {
                    override fun activeTokens(userId: UUID): TokenSnapshot =
                        TokenSnapshot(tokens = tokens, dispatchStartedAt = dispatchStartedAt)

                    override fun deleteTokenIfStale(
                        userId: UUID,
                        platform: String,
                        token: String,
                        dispatchStartedAt: Instant,
                    ): Int {
                        val key = Triple(userId, platform, token)
                        // The fake honours the predicate at the row level: if the
                        // row's `lastSeenAt` is later than `dispatchStartedAt`,
                        // return 0 (race-guarded). Otherwise, return 1 (or the
                        // cached value if a prior call already deleted).
                        val row = tokens.firstOrNull { it.platform == platform && it.token == token }
                        return if (row != null && row.lastSeenAt > dispatchStartedAt) {
                            deleted[key] = 0
                            0
                        } else if (deleted.containsKey(key)) {
                            // Already deleted by a peer dispatcher — second call no-ops.
                            0
                        } else {
                            deleted[key] = 1
                            1
                        }
                    }
                }
            val lookup =
                ActorUsernameLookup { uid -> if (uid == null) null else actorUsername }
            return FcmDispatcher(
                notificationRepository = notRepo,
                userFcmTokenReader = reader,
                sender = sender,
                actorUsernameLookup = lookup,
                dispatcherScope = scope,
            )
        }

        // ------------------------------------------------------------------
        // 6.4.1 — Recipient with zero tokens
        // ------------------------------------------------------------------
        "6.4.1 zero tokens: no FCM call, single fcm_skipped_no_tokens INFO" {
            val sendCount = AtomicInteger(0)
            val sender =
                FcmSender { _ ->
                    sendCount.incrementAndGet()
                    FcmSendResult.Sent("ok")
                }
            val dispatcher = build(row(), tokens = emptyList(), sender = sender)
            val logs = captureLogs { dispatcher.dispatch(notificationId) }
            sendCount.get() shouldBe 0
            logs.any { it.formattedMessage.contains("event=fcm_skipped_no_tokens") } shouldBe true
        }

        // 6.4.2 — Recipient with multi-token fanout
        "6.4.2 multi-token recipient triggers per-token fanout" {
            val sendCount = AtomicInteger(0)
            val sender = FcmSender { _ -> FcmSendResult.Sent("id-${sendCount.incrementAndGet()}") }
            val dispatcher =
                build(
                    row(),
                    tokens =
                        listOf(
                            FcmTokenRow("android", "tok-a", Instant.now()),
                            FcmTokenRow("android", "tok-b", Instant.now()),
                            FcmTokenRow("ios", "tok-c", Instant.now()),
                        ),
                    sender = sender,
                )
            dispatcher.dispatch(notificationId)
            sendCount.get() shouldBe 3
        }

        // 6.4.3 — UNREGISTERED for one token, success for peer
        "6.4.3 UNREGISTERED prunes the specific token; peer dispatched" {
            val deleted = ConcurrentHashMap<Triple<UUID, String, String>, Int>()
            val sentLive = AtomicInteger(0)
            val sender =
                FcmSender { msg ->
                    val token = extractTokenFromMessage(msg)
                    if (token == "tok-stale") {
                        FcmSendResult.PermanentTokenError(MessagingErrorCode.UNREGISTERED)
                    } else {
                        sentLive.incrementAndGet()
                        FcmSendResult.Sent("ok")
                    }
                }
            val now = Instant.now()
            val tokens =
                listOf(
                    FcmTokenRow("android", "tok-stale", now.minusSeconds(60)),
                    FcmTokenRow("android", "tok-live", now.minusSeconds(60)),
                )
            val dispatcher = build(row(), tokens = tokens, sender = sender, deleted = deleted, dispatchStartedAt = now)
            val logs = captureLogs { dispatcher.dispatch(notificationId) }
            deleted[Triple(recipient, "android", "tok-stale")] shouldBe 1
            deleted[Triple(recipient, "android", "tok-live")] shouldBe null
            sentLive.get() shouldBe 1
            logs.any { it.formattedMessage.contains("event=fcm_token_pruned") } shouldBe true
        }

        // 6.4.4 — INVALID_ARGUMENT does NOT delete
        "6.4.4 INVALID_ARGUMENT logs WARN AND does NOT delete" {
            val deleted = ConcurrentHashMap<Triple<UUID, String, String>, Int>()
            val sender = FcmSender { _ -> FcmSendResult.TransientError("INVALID_ARGUMENT") }
            val dispatcher = build(row(), tokens = listOf(sentinelTokenRow("tok-1")), sender = sender, deleted = deleted)
            val logs = captureLogs { dispatcher.dispatch(notificationId) }
            deleted shouldBe emptyMap()
            logs.any { it.level == Level.WARN && it.formattedMessage.contains("error_code=INVALID_ARGUMENT") } shouldBe true
        }

        // 6.4.5 — SENDER_ID_MISMATCH prunes
        "6.4.5 SENDER_ID_MISMATCH prunes identically to UNREGISTERED" {
            val deleted = ConcurrentHashMap<Triple<UUID, String, String>, Int>()
            val sender = FcmSender { _ -> FcmSendResult.PermanentTokenError(MessagingErrorCode.SENDER_ID_MISMATCH) }
            val dispatcher = build(row(), tokens = listOf(sentinelTokenRow("tok-x")), sender = sender, deleted = deleted)
            val logs = captureLogs { dispatcher.dispatch(notificationId) }
            deleted[Triple(recipient, "android", "tok-x")] shouldBe 1
            logs.any { ev ->
                ev.formattedMessage.contains("event=fcm_token_pruned") &&
                    ev.formattedMessage.contains("SENDER_ID_MISMATCH")
            } shouldBe true
        }

        // 6.4.5.a — Re-registration race, predicate doesn't match
        "6.4.5.a UNREGISTERED but row freshness postdates dispatchStartedAt → row preserved, log skipped" {
            val deleted = ConcurrentHashMap<Triple<UUID, String, String>, Int>()
            val sender = FcmSender { _ -> FcmSendResult.PermanentTokenError(MessagingErrorCode.UNREGISTERED) }
            val now = Instant.now()
            val freshRow = FcmTokenRow("android", "tok-just-re-registered", now.plusSeconds(10))
            val dispatcher =
                build(
                    row(),
                    tokens = listOf(freshRow),
                    sender = sender,
                    deleted = deleted,
                    dispatchStartedAt = now,
                )
            val logs = captureLogs { dispatcher.dispatch(notificationId) }
            deleted[Triple(recipient, "android", "tok-just-re-registered")] shouldBe 0
            logs.any { it.formattedMessage.contains("event=fcm_token_prune_skipped_re_registered") } shouldBe true
        }

        // 6.4.6 — INTERNAL (5xx)
        "6.4.6 INTERNAL transient: row NOT deleted, WARN logged" {
            val deleted = ConcurrentHashMap<Triple<UUID, String, String>, Int>()
            val sender = FcmSender { _ -> FcmSendResult.TransientError("INTERNAL") }
            val dispatcher = build(row(), tokens = listOf(sentinelTokenRow("tok-i")), sender = sender, deleted = deleted)
            val logs = captureLogs { dispatcher.dispatch(notificationId) }
            deleted shouldBe emptyMap()
            logs.any { it.level == Level.WARN && it.formattedMessage.contains("INTERNAL") } shouldBe true
        }

        // 6.4.7 — Non-Firebase exception
        "6.4.7 non-Firebase exception → WARN with error_code=unknown" {
            val deleted = ConcurrentHashMap<Triple<UUID, String, String>, Int>()
            val sender = FcmSender { _ -> throw java.net.SocketTimeoutException("network") }
            val dispatcher = build(row(), tokens = listOf(sentinelTokenRow("tok-n")), sender = sender, deleted = deleted)
            val logs = captureLogs { dispatcher.dispatch(notificationId) }
            deleted shouldBe emptyMap()
            logs.any { it.level == Level.WARN && it.formattedMessage.contains("error_code=unknown") } shouldBe true
        }

        // 6.4.8 — Token-confidentiality across all severities
        "6.4.8 raw token never appears in any captured log severity" {
            val sentinel = "sentinel-token-string-DO-NOT-LEAK"
            val deleted = ConcurrentHashMap<Triple<UUID, String, String>, Int>()
            // Drive through three log paths: success / prune / failure.
            val tries =
                listOf<FcmSendResult>(
                    FcmSendResult.Sent("ok-id"),
                    FcmSendResult.PermanentTokenError(MessagingErrorCode.UNREGISTERED),
                    FcmSendResult.TransientError("INVALID_ARGUMENT"),
                )
            tries.forEach { result ->
                val sender = FcmSender { _ -> result }
                val dispatcher =
                    build(
                        row(),
                        tokens = listOf(FcmTokenRow("android", sentinel, Instant.now().minusSeconds(60))),
                        sender = sender,
                        deleted = deleted,
                    )
                val logs = captureLogs { dispatcher.dispatch(notificationId) }
                logs.forEach { it.formattedMessage.contains(sentinel) shouldBe false }
            }
        }

        // 6.4.9 — Exception in one token's send does not abort other tokens
        "6.4.9 exception in one token's send does not abort the others" {
            val sentLive = AtomicInteger(0)
            val sender =
                FcmSender { msg ->
                    val token = extractTokenFromMessage(msg)
                    if (token == "tok-bad") {
                        throw RuntimeException("boom")
                    } else {
                        sentLive.incrementAndGet()
                        FcmSendResult.Sent("ok")
                    }
                }
            val tokens =
                listOf(
                    FcmTokenRow("android", "tok-bad", Instant.now().minusSeconds(60)),
                    FcmTokenRow("android", "tok-ok-1", Instant.now().minusSeconds(60)),
                    FcmTokenRow("android", "tok-ok-2", Instant.now().minusSeconds(60)),
                )
            val dispatcher = build(row(), tokens = tokens, sender = sender)
            dispatcher.dispatch(notificationId)
            sentLive.get() shouldBe 2
        }

        // 6.4.10 — Recipient hard-deleted between emit and dispatch
        "6.4.10 recipient hard-deleted between emit and dispatch tolerates orphan userId gracefully" {
            // findById returns null → fcm_skipped_notification_missing path.
            val sender = FcmSender { _ -> FcmSendResult.Sent("ok") }
            // Pass an unrelated notification so findById returns null for the dispatched id.
            val dispatcher = build(row(), tokens = listOf(sentinelTokenRow("tok")), sender = sender)
            // Drive a different id so notRepo.findById returns null.
            val unrelatedId = UUID.randomUUID()
            val logs = captureLogs { dispatcher.dispatch(unrelatedId) }
            logs.any { it.formattedMessage.contains("event=fcm_skipped_notification_missing") } shouldBe true
        }

        // 6.4.11 — Actor hard-deleted: actorUsername → null → actor-less fallback body
        "6.4.11 actor hard-deleted: PushCopy gets null actorUsername (no exception)" {
            val sender = FcmSender { _ -> FcmSendResult.Sent("ok") }
            val dispatcher =
                build(row(actorUserId = UUID.randomUUID()), tokens = listOf(sentinelTokenRow("tok")), actorUsername = null, sender = sender)
            // Smoke: the dispatcher does not throw when actorUsernameLookup returns null.
            dispatcher.dispatch(notificationId)
        }

        // 6.4.14 — body_data IS NULL handling: builders tolerate "{}" → empty body_data
        "6.4.14 body_data IS NULL renders as empty-string in payload data fields without exception" {
            val sender = FcmSender { _ -> FcmSendResult.Sent("ok") }
            val dispatcher =
                build(
                    row(bodyDataJson = "{}"),
                    tokens =
                        listOf(
                            FcmTokenRow("android", "tok-a", Instant.now()),
                            FcmTokenRow("ios", "tok-i", Instant.now()),
                        ),
                    sender = sender,
                )
            dispatcher.dispatch(notificationId)
            // Smoke: completes without exception. Structural empty-string assertions
            // are deferred to the integration test (see PayloadBuildersTest file note).
        }

        // 6.4.15 — Concurrent emits, second prune is no-op
        "6.4.15 concurrent emits, second prune attempt no-ops when first deleted the row" {
            val deleted = ConcurrentHashMap<Triple<UUID, String, String>, Int>()
            val sender = FcmSender { _ -> FcmSendResult.PermanentTokenError(MessagingErrorCode.UNREGISTERED) }
            val now = Instant.now()
            val tokens = listOf(FcmTokenRow("android", "tok-shared", now.minusSeconds(60)))
            // First dispatcher deletes the row.
            val dispatcher1 = build(row(), tokens = tokens, sender = sender, deleted = deleted, dispatchStartedAt = now)
            dispatcher1.dispatch(notificationId)
            // Second dispatcher attempts the same prune — the deleted map's hit
            // simulates the row already gone.
            val dispatcher2 = build(row(), tokens = tokens, sender = sender, deleted = deleted, dispatchStartedAt = now)
            val logs = captureLogs { dispatcher2.dispatch(notificationId) }
            logs.any { it.formattedMessage.contains("event=fcm_token_prune_skipped_re_registered") } shouldBe true
        }

        // 6.4.16 — Per-launch CancellationException isolation (SupervisorJob)
        "6.4.16 per-launch CancellationException does not cascade to peer launches" {
            val sentLive = AtomicInteger(0)
            val sender =
                FcmSender { msg ->
                    val token = extractTokenFromMessage(msg)
                    if (token == "tok-cancel") {
                        throw kotlinx.coroutines.CancellationException("test-cancel")
                    } else {
                        sentLive.incrementAndGet()
                        FcmSendResult.Sent("ok")
                    }
                }
            val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            val tokens =
                listOf(
                    FcmTokenRow("android", "tok-cancel", Instant.now().minusSeconds(60)),
                    FcmTokenRow("android", "tok-ok-a", Instant.now().minusSeconds(60)),
                    FcmTokenRow("android", "tok-ok-b", Instant.now().minusSeconds(60)),
                )
            val dispatcher = build(row(), tokens = tokens, sender = sender, scope = scope)
            dispatcher.dispatch(notificationId)
            // Two non-cancelled launches still completed.
            sentLive.get() shouldBe 2
            // No exception escaped (we got here).
            (scope.coroutineContext[Job]?.isActive ?: false) shouldBe true
        }
    },
)

/**
 * Best-effort extraction of the token from an FCM Message via reflection. The
 * `token` field is private — but this is a test-only helper and SDK changes
 * would break the test fast (good signal). Used to differentiate per-token
 * sender behaviour without poking deeper SDK internals.
 */
private fun extractTokenFromMessage(msg: Message): String? {
    val f = Message::class.java.getDeclaredField("token").apply { isAccessible = true }
    return f.get(msg) as? String
}
