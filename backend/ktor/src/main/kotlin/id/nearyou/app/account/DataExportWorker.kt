package id.nearyou.app.account

import id.nearyou.app.infra.r2.ObjectStore
import id.nearyou.app.infra.r2.ObjectStoreUnconfiguredException
import id.nearyou.app.infra.resend.EmailSender
import id.nearyou.app.infra.resend.SendResult
import id.nearyou.app.notifications.NotificationEmitter
import id.nearyou.data.repository.NotificationType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource
import kotlin.time.Duration.Companion.hours

private val logger = LoggerFactory.getLogger("id.nearyou.app.account.DataExportWorker")

/** Result of one [DataExportWorker.execute] run. */
data class DataExportRunResult(
    val processedCount: Int,
    val readyCount: Int,
    val failedCount: Int,
    val durationMs: Long,
)

/**
 * Worker (Cloud-Scheduler-invoked via `/internal/data-export-worker`) that packages and
 * delivers pending data exports (capability `account-data-export`, Phase 7).
 *
 * For each `pending` request:
 *  1. Claim it optimistically (`status='pending' → 'processing'`, affected-rows guard) so
 *     concurrent invocations never double-process.
 *  2. Gather the requester's own data (scope matrix) → serialize to a ZIP.
 *  3. Upload to [ObjectStore] under `exports/<userId>/<requestId>.zip`, presign a 24h GET URL.
 *  4. Set the request `ready` (+ `r2_object_key` + `download_expires_at = NOW()+24h`).
 *  5. Emit the **durable** `data_export_ready` notification (`body_data {signed_url, expires_at}`,
 *     NULL target) inside its own transaction.
 *  6. THEN best-effort [EmailSender] send (idempotency key `SHA256(userId+'data_export_ready'+minute)`).
 *
 * Fail-soft (design D5 / spec): a gather/upload failure (incl. [ObjectStoreUnconfiguredException])
 * sets the request `failed` (+ non-PII error + `attempt_count++`) and produces NO ready/notification/
 * email. An email failure on an ALREADY-ready export does NOT revert it — the notification already
 * carries the signed URL; the email failure is logged (no PII) and is independently retryable.
 * No signed URL, email address, or content is ever logged.
 */
class DataExportWorker(
    private val dataSource: DataSource,
    private val requests: DataExportRequestRepository,
    private val gather: DataExportGatherRepository,
    private val archiveService: DataExportArchiveService,
    private val objectStore: ObjectStore,
    private val emailSender: EmailSender,
    private val notificationEmitter: NotificationEmitter,
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun execute(): DataExportRunResult {
        val startNanos = System.nanoTime()
        val pending = requests.snapshotPending()
        var processed = 0
        var ready = 0
        var failed = 0
        for (requestId in pending) {
            when (processOne(requestId)) {
                ProcessOutcome.READY -> {
                    processed++
                    ready++
                }
                ProcessOutcome.FAILED -> {
                    processed++
                    failed++
                }
                ProcessOutcome.SKIPPED -> Unit // claimed by another runner since the snapshot
            }
        }
        return DataExportRunResult(
            processedCount = processed,
            readyCount = ready,
            failedCount = failed,
            durationMs = (System.nanoTime() - startNanos) / 1_000_000L,
        )
    }

    private enum class ProcessOutcome { READY, FAILED, SKIPPED }

    private suspend fun processOne(requestId: UUID): ProcessOutcome {
        val claim = requests.claimPending(requestId) ?: return ProcessOutcome.SKIPPED

        // --- gather + serialize + upload: failure here → soft `failed` --------
        val readyState =
            try {
                val generatedAt = Instant.now()
                val bundle = gather.gather(claim.userId)
                val archive = archiveService.serialize(bundle, generatedAt)
                val objectKey = "exports/${claim.userId}/$requestId.zip"
                objectStore.put(objectKey, archive.bytes, archive.contentType)
                val signedUrl = objectStore.presignedGetUrl(objectKey, 24.hours)
                val expiresAt = requests.setReady(requestId, objectKey)
                ReadyState(objectKey = objectKey, signedUrl = signedUrl, expiresAt = expiresAt, generatedAt = generatedAt)
            } catch (e: ObjectStoreUnconfiguredException) {
                markFailed(requestId, "object_store_unconfigured", e)
                return ProcessOutcome.FAILED
            } catch (e: Exception) {
                markFailed(requestId, "gather_or_upload_failed", e)
                return ProcessOutcome.FAILED
            }

        // --- durable notification: emit inside its own transaction -----------
        emitReadyNotification(claim.userId, readyState)

        // --- best-effort email: a failure here does NOT revert `ready` -------
        sendReadyEmail(claim.userId, readyState)

        logger.info(
            "event=data_export_ready request_id={} user_id={}",
            requestId,
            claim.userId,
        )
        return ProcessOutcome.READY
    }

    private suspend fun markFailed(
        requestId: UUID,
        error: String,
        cause: Throwable,
    ) {
        // No user_id-content/URL in the log line beyond the request id + a non-PII classification.
        logger.warn(
            "event=data_export_failed request_id={} error={} error_class={}",
            requestId,
            error,
            cause::class.simpleName,
            cause,
        )
        runCatching { requests.setFailed(requestId, error) }
            .onFailure { logger.warn("event=data_export_set_failed_errored request_id={} error_class={}", requestId, it::class.simpleName) }
    }

    /** Emits the durable `data_export_ready` notification in its own transaction. */
    private suspend fun emitReadyNotification(
        userId: UUID,
        readyState: ReadyState,
    ) = withContext(dbDispatcher) {
        val bodyData =
            buildJsonObject {
                put("signed_url", JsonPrimitive(readyState.signedUrl))
                put("expires_at", JsonPrimitive(readyState.expiresAt.toString()))
            }
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                // actorUserId = null: system-originated emit; a NULL actor skips the block check.
                notificationEmitter.emit(
                    conn = conn,
                    recipientId = userId,
                    actorUserId = null,
                    type = NotificationType.DATA_EXPORT_READY,
                    targetType = null,
                    targetId = null,
                    bodyData = bodyData,
                )
                conn.commit()
            } catch (e: Exception) {
                runCatching { conn.rollback() }
                // The notification is durable delivery; if it fails the request still reads `ready`
                // (the GET re-presigns), so log without PII and continue — do NOT crash the batch.
                logger.warn("event=data_export_notification_failed user_id={} error_class={}", userId, e::class.simpleName, e)
            }
        }
    }

    /** Best-effort Resend email. Never reverts a ready export; never logs the recipient or URL. */
    private suspend fun sendReadyEmail(
        userId: UUID,
        readyState: ReadyState,
    ) {
        val email = runCatching { gather.emailForUser(userId) }.getOrNull()
        if (email.isNullOrBlank()) {
            logger.info("event=data_export_email_skipped reason=no_email user_id={}", userId)
            return
        }
        val template = DataExportEmailTemplate.render(readyState.signedUrl, readyState.expiresAt.toString())
        val idempotencyKey = idempotencyKey(userId, readyState.generatedAt)
        val result =
            runCatching { emailSender.send(to = email, template = template, idempotencyKey = idempotencyKey) }
                .getOrElse { SendResult.Failed(it::class.simpleName ?: "unknown") }
        when (result) {
            is SendResult.Sent ->
                logger.info("event=data_export_email_sent user_id={} message_id={}", userId, result.id)
            is SendResult.Failed ->
                // Already ready — the in-app notification carries the link; email retried independently.
                logger.warn("event=data_export_email_failed user_id={} reason={}", userId, result.reason)
            SendResult.Skipped ->
                logger.info("event=data_export_email_skipped reason=unconfigured user_id={}", userId)
        }
    }

    private data class ReadyState(
        val objectKey: String,
        val signedUrl: String,
        val expiresAt: Instant,
        val generatedAt: Instant,
    )

    companion object {
        /** `SHA256(userId + 'data_export_ready' + timestamp_minute)` — the canonical idempotency key (docs/04 §210). */
        fun idempotencyKey(
            userId: UUID,
            at: Instant,
        ): String {
            val minute = at.truncatedTo(ChronoUnit.MINUTES).toString()
            val material = "$userId" + "data_export_ready" + minute
            val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
