package id.nearyou.app.account

import id.nearyou.app.infra.r2.ObjectStore
import id.nearyou.app.infra.r2.ObjectStoreUnconfiguredException
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Duration.Companion.hours

private val log = LoggerFactory.getLogger("id.nearyou.app.account.DataExportService")

/** The status read's view of the caller's latest export (no other user's row/key/URL ever leaks). */
data class DataExportStatusView(
    val status: DataExportStatus,
    val downloadExpiresAt: java.time.Instant?,
    /** A freshly-presigned 24h URL, present ONLY for a `ready`-and-unexpired request. */
    val downloadUrl: String?,
)

/**
 * Thin orchestration for the user-facing data-export producer (docs/11 §3.1 Routes → Service →
 * Repository). The single-active idempotency is enforced structurally in SQL by
 * [DataExportRequestRepository] (the one-active partial-unique index → catch-and-return-existing),
 * so the request path is a pass-through. The status read derives `expired` past the deadline and
 * re-presigns a fresh URL for a still-ready request via [ObjectStore].
 */
class DataExportService(
    private val requests: DataExportRequestRepository,
    private val objectStore: ObjectStore,
) {
    /** Enqueue an export (or return the caller's existing active request). Returns (id, status). */
    suspend fun requestExport(userId: UUID): DataExportRequestRow = requests.insertPendingOrReturnActive(userId)

    /**
     * The caller's latest export status. For a `ready`-and-unexpired request it re-presigns a
     * fresh 24h URL; for an expired/absent/in-progress request it returns no URL. Filtered on
     * `user_id` upstream — never another user's state.
     */
    suspend fun status(userId: UUID): DataExportStatusView? {
        val row = requests.latestForUser(userId) ?: return null
        val url =
            if (row.status == DataExportStatus.READY && row.r2ObjectKey != null) {
                // Freshly presign so the client always gets a live URL (never the persisted one).
                runCatching { objectStore.presignedGetUrl(row.r2ObjectKey, 24.hours) }
                    .getOrElse { e ->
                        if (e !is ObjectStoreUnconfiguredException) {
                            log.warn("event=data_export_presign_failed error_class={}", e::class.simpleName)
                        }
                        null
                    }
            } else {
                null
            }
        return DataExportStatusView(
            status = row.status,
            downloadExpiresAt = row.downloadExpiresAt,
            downloadUrl = url,
        )
    }
}
