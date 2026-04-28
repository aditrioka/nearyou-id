package id.nearyou.app.infra.fcm

import com.google.firebase.messaging.MessagingErrorCode
import id.nearyou.data.repository.ActorUsernameLookup
import id.nearyou.data.repository.FcmTokenRow
import id.nearyou.data.repository.NotificationDispatcher
import id.nearyou.data.repository.NotificationRepository
import id.nearyou.data.repository.NotificationRow
import id.nearyou.data.repository.UserFcmTokenReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Production [NotificationDispatcher] implementation backed by the Firebase
 * Admin SDK. Per `openspec/specs/fcm-push-dispatch/spec.md`:
 *
 *   - Invoked by `NotificationService.emit()` AFTER the DB commit succeeds
 *     (the seam contract is preserved verbatim — see `in-app-notifications`).
 *   - Reads active tokens AND the DB-clock `dispatchStartedAt` atomically via
 *     [UserFcmTokenReader.activeTokens] (both ends of the race-guard predicate
 *     come from the DB clock domain — no JVM `Instant.now()` for predicate
 *     comparisons; see `design.md` D12).
 *   - Resolves `actor_user_id → username` via [ActorUsernameLookup] reading
 *     from `visible_users` (NOT raw `users`) so shadow-banned + deleted actors
 *     mask to `null` (see `design.md` D13).
 *   - Fans out parallel sends — one coroutine per token — on
 *     [dispatcherScope]; the synchronous return blocks the request thread for
 *     no measurable time (FCM round-trips run on the background pool).
 *   - On `UNREGISTERED` or `SENDER_ID_MISMATCH` only, prunes via
 *     [UserFcmTokenReader.deleteTokenIfStale]. `INVALID_ARGUMENT` is treated
 *     as transient because the FCM Admin SDK overloads the code between
 *     stale-token and oversized-payload (see `design.md` D6).
 *   - NEVER logs the raw `token` value at any severity. Token-bearing log
 *     lines carry `token_length` and `token_hash_prefix` (8-char hex of
 *     SHA-256) only.
 */
class FcmDispatcher(
    private val notificationRepository: NotificationRepository,
    private val userFcmTokenReader: UserFcmTokenReader,
    private val sender: FcmSender,
    private val actorUsernameLookup: ActorUsernameLookup,
    private val dispatcherScope: CoroutineScope,
) : NotificationDispatcher {
    @Volatile
    private var shutdownInitiated: Boolean = false

    /**
     * Marks the dispatcher as shut down. After this, [dispatch] logs WARN
     * `event="fcm_dispatch_after_shutdown"` and returns without enqueuing.
     * Called by the JVM-shutdown hook installed by the production wiring.
     */
    fun markShutdown() {
        shutdownInitiated = true
    }

    override fun dispatch(notificationId: UUID) {
        if (shutdownInitiated) {
            log.warn(
                "event=fcm_dispatch_after_shutdown notification_id={}",
                notificationId,
            )
            return
        }
        // Enqueue all work on the background scope; the request thread returns
        // immediately. Per design D2: dispatch errors do NOT propagate back to
        // the emit site — the primary write transaction has already committed.
        try {
            dispatcherScope.launch {
                try {
                    runDispatch(notificationId)
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    log.warn(
                        "event=fcm_dispatch_failed notification_id={} error_code=dispatch_setup_failed error_class={}",
                        notificationId,
                        t.javaClass.simpleName,
                    )
                }
            }
        } catch (t: Throwable) {
            // The scope rejected the launch (e.g., the Job was already cancelled
            // by a shutdown that crossed our shutdown-flag check). Suppress and
            // log — never propagate.
            log.warn(
                "event=fcm_dispatch_after_shutdown notification_id={} reason=launch_rejected error_class={}",
                notificationId,
                t.javaClass.simpleName,
            )
        }
    }

    private suspend fun runDispatch(notificationId: UUID) {
        val notification =
            notificationRepository.findById(notificationId) ?: run {
                // Recipient hard-deleted between emit and dispatch: CASCADE
                // removed the row. Tolerate gracefully.
                log.info(
                    "event=fcm_skipped_notification_missing notification_id={}",
                    notificationId,
                )
                return
            }
        val recipientId = notification.userId
        val snapshot = userFcmTokenReader.activeTokens(recipientId)
        if (snapshot.tokens.isEmpty()) {
            log.info(
                "event=fcm_skipped_no_tokens user_id={} notification_type={}",
                recipientId,
                notification.type.wire,
            )
            return
        }
        val actorUsername = actorUsernameLookup.lookup(notification.actorUserId)

        for (row in snapshot.tokens) {
            dispatcherScope.launch {
                sendOne(notification, row, actorUsername, snapshot.dispatchStartedAt)
            }
        }
    }

    private fun sendOne(
        notification: NotificationRow,
        row: FcmTokenRow,
        actorUsername: String?,
        dispatchStartedAt: java.time.Instant,
    ) {
        val buildResult =
            try {
                buildPlatformMessage(notification, row, actorUsername)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                logWarn(
                    "fcm_dispatch_failed",
                    user = notification.userId,
                    platform = row.platform,
                    token = row.token,
                    notificationType = notification.type.wire,
                    errorCode = "payload_build_failed",
                    cause = t,
                )
                return
            }
        val message =
            when (buildResult) {
                is BuildResult.Built -> buildResult.message
                BuildResult.OversizedPayload -> {
                    logWarn(
                        "fcm_dispatch_failed",
                        user = notification.userId,
                        platform = row.platform,
                        token = row.token,
                        notificationType = notification.type.wire,
                        errorCode = "payload_too_large",
                        cause = null,
                    )
                    return
                }
                BuildResult.UnknownPlatform -> {
                    logWarn(
                        "fcm_dispatch_failed",
                        user = notification.userId,
                        platform = row.platform,
                        token = row.token,
                        notificationType = notification.type.wire,
                        errorCode = "unknown_platform",
                        cause = null,
                    )
                    return
                }
            }
        val result =
            try {
                sender.send(message)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                // FcmSender contract is to never throw, but defense in depth:
                // any escapee is treated transient.
                FcmSendResult.TransientError(code = "unknown", cause = t)
            }
        when (result) {
            is FcmSendResult.Sent ->
                log.info(
                    "event=fcm_dispatched user_id={} platform={} notification_type={} message_id={} token_length={} token_hash_prefix={}",
                    notification.userId,
                    row.platform,
                    notification.type.wire,
                    result.messageId,
                    row.token.length,
                    tokenHashPrefix(row.token),
                )
            is FcmSendResult.PermanentTokenError ->
                handlePermanentTokenError(result.code, notification, row, dispatchStartedAt)
            is FcmSendResult.TransientError ->
                logWarn(
                    "fcm_dispatch_failed",
                    user = notification.userId,
                    platform = row.platform,
                    token = row.token,
                    notificationType = notification.type.wire,
                    errorCode = result.code,
                    cause = result.cause,
                )
        }
    }

    private fun handlePermanentTokenError(
        code: MessagingErrorCode,
        notification: NotificationRow,
        row: FcmTokenRow,
        dispatchStartedAt: java.time.Instant,
    ) {
        val codeName = code.toString()
        val deleted =
            userFcmTokenReader.deleteTokenIfStale(
                userId = notification.userId,
                platform = row.platform,
                token = row.token,
                dispatchStartedAt = dispatchStartedAt,
            )
        if (deleted > 0) {
            log.info(
                "event=fcm_token_pruned user_id={} platform={} error_code={} rows_deleted={} token_length={} token_hash_prefix={}",
                notification.userId,
                row.platform,
                codeName,
                deleted,
                row.token.length,
                tokenHashPrefix(row.token),
            )
        } else {
            log.info(
                "event=fcm_token_prune_skipped_re_registered user_id={} " +
                    "platform={} error_code={} rows_deleted=0 token_length={} token_hash_prefix={}",
                notification.userId,
                row.platform,
                codeName,
                row.token.length,
                tokenHashPrefix(row.token),
            )
        }
    }

    private sealed interface BuildResult {
        data class Built(val message: com.google.firebase.messaging.Message) : BuildResult

        data object OversizedPayload : BuildResult

        data object UnknownPlatform : BuildResult
    }

    private fun buildPlatformMessage(
        notification: NotificationRow,
        row: FcmTokenRow,
        actorUsername: String?,
    ): BuildResult =
        when (row.platform) {
            "android" -> BuildResult.Built(buildAndroidMessage(notification, row.token))
            "ios" ->
                when (val result = buildIosMessage(notification, actorUsername, row.token)) {
                    is IosPayloadResult.Built -> BuildResult.Built(result.message)
                    IosPayloadResult.OversizedPayload -> BuildResult.OversizedPayload
                }
            else -> BuildResult.UnknownPlatform
        }

    private fun logWarn(
        event: String,
        user: UUID,
        platform: String,
        token: String,
        notificationType: String,
        errorCode: String,
        cause: Throwable?,
    ) {
        if (cause != null) {
            log.warn(
                "event={} user_id={} platform={} notification_type={} error_code={} token_length={} token_hash_prefix={} error_class={}",
                event,
                user,
                platform,
                notificationType,
                errorCode,
                token.length,
                tokenHashPrefix(token),
                cause.javaClass.simpleName,
            )
        } else {
            log.warn(
                "event={} user_id={} platform={} notification_type={} error_code={} token_length={} token_hash_prefix={}",
                event,
                user,
                platform,
                notificationType,
                errorCode,
                token.length,
                tokenHashPrefix(token),
            )
        }
    }

    private fun tokenHashPrefix(token: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(token.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(8)
        for (i in 0 until 4) {
            sb.append(String.format("%02x", digest[i]))
        }
        return sb.toString()
    }

    companion object {
        private val log = LoggerFactory.getLogger(FcmDispatcher::class.java)
    }
}
