package id.nearyou.app.account

import java.time.Instant
import java.util.UUID

/**
 * Thin orchestration layer for the user-initiated account-deletion lifecycle
 * (docs/11 §3.1 Routes → Service → Repository; design D3). The idempotency and
 * cancel guards are enforced atomically in SQL by [AccountDeletionRepository], so
 * this layer is currently a pass-through seam — the place future business rules
 * (e.g. an emit on schedule, an anomaly check) would land without touching the
 * route or the SQL.
 */
class AccountDeletionService(
    private val repository: AccountDeletionRepository,
) {
    /** Idempotently schedule deletion with a 30-day grace; returns the hard-delete deadline. */
    suspend fun requestDeletion(userId: UUID): Instant = repository.requestDeletion(userId)

    /** Cancel/restore within grace; `true` if a pending request was cancelled, `false` if there was nothing to cancel. */
    suspend fun cancelDeletion(userId: UUID): Boolean = repository.cancelDeletion(userId)

    /** The caller's pending hard-delete deadline, or `null` when none is scheduled. */
    suspend fun getStatus(userId: UUID): Instant? = repository.getStatus(userId)
}
