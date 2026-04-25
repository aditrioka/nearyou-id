package id.nearyou.app.timeline

import id.nearyou.app.common.Cursor
import id.nearyou.app.infra.repo.PostsGlobalRepository
import id.nearyou.app.infra.repo.TimelineRow
import java.util.UUID

/**
 * Global timeline orchestration: viewer → repo → page with `next_cursor`. Repository owns the
 * SQL; this service owns the page-size + 1 probe pattern for keyset pagination (identical
 * shape to [NearbyTimelineService] / [FollowingTimelineService]).
 *
 * No envelope / radius validation — the endpoint is chronological-over-every-visible-author
 * and accepts no geo params. Guest-access rate limits (10/session, 30/hour) are explicitly
 * out of scope and land with the Redis-backed rate-limit change (Phase 1 item 24).
 */
class GlobalTimelineService(
    private val timeline: PostsGlobalRepository,
) {
    fun global(
        viewerId: UUID,
        cursor: Cursor?,
    ): GlobalPage {
        val rows =
            timeline.global(
                viewerId = viewerId,
                cursorCreatedAt = cursor?.createdAt,
                cursorPostId = cursor?.id,
                limit = PAGE_SIZE + 1,
            )
        return if (rows.size > PAGE_SIZE) {
            val page = rows.take(PAGE_SIZE)
            val last = page.last()
            GlobalPage(rows = page, nextCursor = Cursor(createdAt = last.createdAt, id = last.id))
        } else {
            GlobalPage(rows = rows, nextCursor = null)
        }
    }

    companion object {
        const val PAGE_SIZE: Int = 30
    }
}

data class GlobalPage(
    val rows: List<TimelineRow>,
    val nextCursor: Cursor?,
)
