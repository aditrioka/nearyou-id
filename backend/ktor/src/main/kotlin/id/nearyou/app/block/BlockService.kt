package id.nearyou.app.block

import id.nearyou.app.common.Cursor
import id.nearyou.app.infra.repo.UserBlockRepository
import id.nearyou.app.infra.repo.UserBlockRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    // docs/02 §4 + docs/05 § Block 30/h cap (2026-06-10 audit, finding 03-#3).
    private val rateLimiter: BlockRateLimiter = BlockRateLimiter(),
    // Pool-bounded JDBC dispatcher (docs/11 §3.2); production passes
    // DbDispatchers.db — the default keeps direct-construction tests working.
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun block(
        blockerId: UUID,
        targetId: UUID,
    ) {
        if (blockerId == targetId) throw CannotBlockSelfException()
        checkRateLimit(blockerId)
        try {
            withContext(dbDispatcher) { blocks.create(blockerId, targetId) }
        } catch (ex: SQLException) {
            if (ex.sqlState == "23503") throw TargetUserNotFoundException()
            throw ex
        }
    }

    suspend fun unblock(
        blockerId: UUID,
        targetId: UUID,
    ) {
        // Shares the block bucket — both legs of a block↔unblock loop burn quota.
        checkRateLimit(blockerId)
        // Idempotent: 204 regardless of whether a row was deleted.
        withContext(dbDispatcher) { blocks.delete(blockerId, targetId) }
    }

    private fun checkRateLimit(userId: UUID) {
        when (val outcome = rateLimiter.tryAcquire(userId)) {
            is BlockRateLimiter.Outcome.Allowed -> Unit
            is BlockRateLimiter.Outcome.RateLimited ->
                throw BlockRateLimitedException(outcome.retryAfterSeconds)
        }
    }

    suspend fun listOutbound(
        blockerId: UUID,
        cursor: Cursor?,
    ): OutboundBlocksPage = withContext(dbDispatcher) { listOutboundBlocking(blockerId, cursor) }

    private fun listOutboundBlocking(
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

class BlockRateLimitedException(
    val retryAfterSeconds: Long,
) : RuntimeException("block rate limit exceeded")

class TargetUserNotFoundException : RuntimeException("target user not found")
