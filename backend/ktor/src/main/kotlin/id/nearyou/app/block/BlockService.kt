package id.nearyou.app.block

import id.nearyou.app.common.Cursor
import id.nearyou.app.infra.repo.UserBlockRepository
import id.nearyou.app.infra.repo.UserBlockRow
import java.sql.SQLException
import java.util.UUID

/**
 * Block lifecycle: create / delete / list. Self-block is rejected at this layer
 * with [CannotBlockSelfException]; the V5 CHECK constraint backs that up at the DB level.
 *
 * Target-user existence: the V5 FK from `user_blocks.blocked_id` to `users(id)` raises
 * SQLState 23503 on INSERT against a non-existent UUID — we map that to
 * [TargetUserNotFoundException]. No pre-INSERT existence query, so:
 *  - one DB round-trip instead of two
 *  - no race window between the lookup and the INSERT.
 */
class BlockService(
    private val blocks: UserBlockRepository,
) {
    fun block(
        blockerId: UUID,
        targetId: UUID,
    ) {
        if (blockerId == targetId) throw CannotBlockSelfException()
        try {
            blocks.create(blockerId, targetId)
        } catch (ex: SQLException) {
            if (ex.sqlState == "23503") throw TargetUserNotFoundException()
            throw ex
        }
    }

    fun unblock(
        blockerId: UUID,
        targetId: UUID,
    ) {
        // Idempotent: 204 regardless of whether a row was deleted.
        blocks.delete(blockerId, targetId)
    }

    fun listOutbound(
        blockerId: UUID,
        cursor: Cursor?,
    ): OutboundBlocksPage {
        val rows =
            blocks.listOutbound(
                blockerId = blockerId,
                cursorCreatedAt = cursor?.createdAt,
                cursorBlockedId = cursor?.id,
                limit = PAGE_SIZE + 1,
            )
        val pageRows: List<UserBlockRow>
        val nextCursor: Cursor?
        if (rows.size > PAGE_SIZE) {
            pageRows = rows.take(PAGE_SIZE)
            val last = pageRows.last()
            nextCursor = Cursor(createdAt = last.createdAt, id = last.blockedId)
        } else {
            pageRows = rows
            nextCursor = null
        }
        return OutboundBlocksPage(rows = pageRows, nextCursor = nextCursor)
    }

    companion object {
        const val PAGE_SIZE: Int = 30
    }
}

data class OutboundBlocksPage(
    val rows: List<UserBlockRow>,
    val nextCursor: Cursor?,
)

class CannotBlockSelfException : RuntimeException("cannot block self")

class TargetUserNotFoundException : RuntimeException("target user not found")
