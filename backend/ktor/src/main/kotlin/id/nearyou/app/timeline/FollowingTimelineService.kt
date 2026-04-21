package id.nearyou.app.timeline

import id.nearyou.app.common.Cursor
import id.nearyou.app.infra.repo.PostsFollowingRepository
import id.nearyou.app.infra.repo.TimelineRow
import java.util.UUID

/**
 * Following timeline orchestration: viewer → repo → page with `next_cursor`. Repository
 * owns the SQL; this service owns the page-size + 1 probe pattern for keyset pagination
 * (identical shape to [NearbyTimelineService]).
 *
 * No envelope / radius validation — the endpoint is chronological-over-follows and
 * accepts no geo params.
 */
class FollowingTimelineService(
    private val timeline: PostsFollowingRepository,
) {
    fun following(
        viewerId: UUID,
        cursor: Cursor?,
    ): FollowingPage {
        val rows =
            timeline.following(
                viewerId = viewerId,
                cursorCreatedAt = cursor?.createdAt,
                cursorPostId = cursor?.id,
                limit = PAGE_SIZE + 1,
            )
        return if (rows.size > PAGE_SIZE) {
            val page = rows.take(PAGE_SIZE)
            val last = page.last()
            FollowingPage(rows = page, nextCursor = Cursor(createdAt = last.createdAt, id = last.id))
        } else {
            FollowingPage(rows = rows, nextCursor = null)
        }
    }

    companion object {
        const val PAGE_SIZE: Int = 30
    }
}

data class FollowingPage(
    val rows: List<TimelineRow>,
    val nextCursor: Cursor?,
)
