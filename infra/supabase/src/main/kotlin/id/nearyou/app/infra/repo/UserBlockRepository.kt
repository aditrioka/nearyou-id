package id.nearyou.app.infra.repo

import java.time.Instant
import java.util.UUID

data class UserBlockRow(
    val blockedId: UUID,
    val createdAt: Instant,
)

interface UserBlockRepository {
    /**
     * INSERT (blocker, blocked) into user_blocks with `ON CONFLICT DO NOTHING`. Returns
     * `true` if a new row was inserted, `false` if the pair already existed (idempotent).
     * Throws `java.sql.SQLException` with SQLState 23503 if either user does not exist
     * (FK violation) — caller maps to 404 `user_not_found`.
     */
    fun create(
        blockerId: UUID,
        blockedId: UUID,
    ): Boolean

    /**
     * DELETE WHERE blocker_id = ? AND blocked_id = ?. Returns the number of rows
     * affected (0 or 1). Caller treats 0 as a no-op success.
     */
    fun delete(
        blockerId: UUID,
        blockedId: UUID,
    ): Int

    /**
     * Keyset-paginated outbound block listing. Returns up to [limit] rows ordered by
     * `(created_at DESC, blocked_id DESC)`. The cursor, if non-null, advances past the
     * `(createdAt, blockedId)` pair (strict less-than).
     */
    fun listOutbound(
        blockerId: UUID,
        cursorCreatedAt: Instant?,
        cursorBlockedId: UUID?,
        limit: Int,
    ): List<UserBlockRow>
}
