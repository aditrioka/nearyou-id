package id.nearyou.app.moderation.csam

import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.common.AppJson
import id.nearyou.data.repository.ModerationQueueRepository
import id.nearyou.data.repository.ReportTargetType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * The `csam-detection` takedown service: the fixed-policy response to a CSAM match,
 * executed in ONE transaction (docs/11 §3.1 service = transaction boundary).
 *
 * Sequence (docs/02 § Image Upload Flow + docs/06 § Media Moderation, identical):
 * resolve the uploader from the matched image via the `image_uploads` ledger →
 * tombstone the affected post → permanent-ban the uploader + bump `token_version` →
 * cascade-tombstone the uploader's other posts → AES-256-GCM-archive the metadata →
 * enqueue a `moderation_queue` `csam_detected` row → audit. Idempotent (re-trigger is
 * a no-op convergence) and ledger-miss-resilient (archive-only when the image has no
 * ledger row). The uploader identity is captured synchronously into the encrypted blob
 * within this tx — the `image_uploads` row is `ON DELETE CASCADE` to users and is gone
 * after the uploader's hard-delete, so it is NEVER re-resolved later (design D3).
 */
class CsamDetectionService(
    private val dataSource: DataSource,
    private val dbDispatcher: CoroutineDispatcher,
    private val csamRepository: CsamRepository,
    private val moderationQueueRepository: ModerationQueueRepository,
    private val encryptor: CsamMetadataEncryptor,
    private val auditLogger: AdminAuditLogger,
) {
    enum class Source(val wire: String) {
        ADMIN_MANUAL("admin_manual"),
        CF_WORKER("cf_worker"),
    }

    /** The verified, parsed takedown request. [actorAdminId] is the acting admin (admin-manual) or the `system` sentinel (CF-Worker). */
    data class Input(
        val cfImageId: String,
        val imageHash: String,
        val cfMatchId: String?,
        val ncmecReference: String?,
        val source: Source,
        val actorAdminId: UUID,
        val ip: String,
        val userAgent: String?,
    )

    data class Result(
        val uploaderResolved: Boolean,
        val newlyBanned: Boolean,
        val affectedPostId: UUID?,
        val postsCascadeTombstoned: Int,
    )

    /** Daily retention purge — delete archive rows past their 90-day deadline whose Kominfo report is filed. Returns the count purged. */
    suspend fun purge(): Int = withContext(dbDispatcher) { csamRepository.purgeExpiredReported() }

    suspend fun handleDetection(input: Input): Result =
        withContext(dbDispatcher) {
            dataSource.connection.use { conn ->
                conn.autoCommit = false
                try {
                    val result = runTakedown(conn, input)
                    conn.commit()
                    result
                } catch (e: Throwable) {
                    runCatching { conn.rollback() }
                    throw e
                } finally {
                    runCatching { conn.autoCommit = true }
                }
            }
        }

    private fun runTakedown(
        conn: Connection,
        input: Input,
    ): Result {
        val uploaderId = csamRepository.resolveUploaderId(conn, input.cfImageId)

        if (uploaderId == null) {
            // Ledger-miss: the image has no `image_uploads` row (already cleaned / stale).
            // Still archive the match so the Kominfo record exists; nothing to ban/tombstone.
            csamRepository.archive(
                conn = conn,
                imageHash = input.imageHash,
                cfMatchId = input.cfMatchId,
                ncmecReference = input.ncmecReference,
                source = input.source.wire,
                encryptedMetadata = encryptMetadata(input, uploaderId = null, affectedPostId = null, cascadeCount = 0),
            )
            return Result(uploaderResolved = false, newlyBanned = false, affectedPostId = null, postsCascadeTombstoned = 0)
        }

        val affectedPostId = csamRepository.tombstoneAffectedPost(conn, input.cfImageId)
        val newlyBanned = csamRepository.banUploaderPermanently(conn, uploaderId)
        val cascadeCount = csamRepository.cascadeTombstoneUploaderPosts(conn, uploaderId)

        csamRepository.archive(
            conn = conn,
            imageHash = input.imageHash,
            cfMatchId = input.cfMatchId,
            ncmecReference = input.ncmecReference,
            source = input.source.wire,
            encryptedMetadata = encryptMetadata(input, uploaderId, affectedPostId, cascadeCount),
        )

        // Admin-awareness queue row for the affected post (skipped when the image was
        // uploaded but never attached — there is no post target).
        if (affectedPostId != null) {
            moderationQueueRepository.upsertCsamDetectedRow(conn, ReportTargetType.POST, affectedPostId)
        }

        auditLogger.logCsamTakedown(
            conn = conn,
            adminId = input.actorAdminId,
            uploaderId = uploaderId,
            beforeState = buildJsonObject { put("is_banned", !newlyBanned) },
            afterState =
                buildJsonObject {
                    put("is_banned", true)
                    put("token_version_bumped", newlyBanned)
                    put("affected_post_id", affectedPostId?.toString())
                    put("posts_cascade_tombstoned", cascadeCount)
                    put("archive_source", input.source.wire)
                },
            ip = input.ip,
            userAgent = input.userAgent,
        )

        return Result(
            uploaderResolved = true,
            newlyBanned = newlyBanned,
            affectedPostId = affectedPostId,
            postsCascadeTombstoned = cascadeCount,
        )
    }

    /**
     * Build the sensitive metadata blob (uploader id, post id, source) and encrypt it
     * AAD-bound to the `image_hash`. Returns `null` when the AES key is unprovisioned
     * (fail-soft — the archive row is still written with its plaintext essentials).
     * Never contains image bytes.
     */
    private fun encryptMetadata(
        input: Input,
        uploaderId: UUID?,
        affectedPostId: UUID?,
        cascadeCount: Int,
    ): ByteArray? {
        val plaintext: JsonObject =
            buildJsonObject {
                put("uploader_user_id", uploaderId?.toString())
                put("affected_post_id", affectedPostId?.toString())
                put("cf_image_id", input.cfImageId)
                put("source", input.source.wire)
                put("posts_cascade_tombstoned", cascadeCount)
            }
        return encryptor.encrypt(
            plaintext = AppJson.encodeToString(JsonObject.serializer(), plaintext).toByteArray(Charsets.UTF_8),
            imageHash = input.imageHash,
        )
    }
}
