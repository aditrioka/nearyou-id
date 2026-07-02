package id.nearyou.app.admin.postedits

import java.time.Instant
import java.util.UUID

/**
 * Composes one post's edit history for the viewer (`admin-post-edit-history`,
 * design.md D1 / D5). Sits between [id.nearyou.app.admin.routes.adminPostEdits]
 * and [PostEditHistoryRepository] — the read-only viewers usually go
 * route→repository, but this capability has real composition (live row +
 * snapshots, version labelling, empty/edge branches), so it earns a service.
 *
 * The composed list is `[live "Versi terbaru"] + [snapshots newest-first]`
 * (design D1). N snapshots → N+1 versions; the oldest version row is the original
 * pre-first-edit content (the oldest `post_edits.content_snapshot`). The live
 * version appears on the FIRST page only (cursor == null); paginating "older"
 * returns snapshots alone, so the live version never repeats on a later page.
 *
 * Location (design D4): the per-version view model exposes ONLY a boolean
 * [PostVersionView.locationChanged]. There is no `GEOGRAPHY` / latitude /
 * longitude / coordinate field anywhere on the types that leave this service, so
 * coordinates cannot leak via the template, an HTMX `hx-vals`, a debug attribute,
 * or a serialized JSON island. The boolean itself is computed upstream in SQL
 * (the geography never even reaches Kotlin).
 */
class PostEditHistoryService(
    private val repository: PostEditHistoryRepository,
) {
    /**
     * Builds the history view for [postId] at the given opaque [cursor].
     *
     * Edge handling (design D5 — never a 500):
     *  - [postId] null (the route's UUID parse failed on a non-UUID segment) →
     *    [PostEditHistoryView.found] = false (the not-found empty state).
     *  - post absent / hard-deleted (no live row) → not-found empty state.
     *  - post present with zero snapshots → a single live version plus the
     *    "no prior edits" note ([PostEditHistoryView.showNoHistoryNote]).
     */
    fun history(
        postId: UUID?,
        cursor: String?,
    ): PostEditHistoryView {
        if (postId == null) return NOT_FOUND
        val live = repository.liveVersion(postId) ?: return NOT_FOUND

        val decodedCursor = PostEditCursor.decode(cursor)
        val isFirstPage = decodedCursor == null
        val page = repository.snapshotPage(postId, decodedCursor)

        // Live version is part of the first page only (design D1): on a later page
        // (cursor present) we render snapshots alone, so "Versi terbaru" never
        // reappears on a subsequent page.
        val liveView =
            if (isFirstPage) {
                PostVersionView(
                    versionLabel = LIVE_LABEL,
                    isLive = true,
                    content = live.content,
                    timestamp = live.timestamp,
                    authorId = live.authorId,
                    authorUsername = live.authorUsername,
                    // The live version is the newest; nothing newer exists to
                    // compare against, so no location-change indicator.
                    locationChanged = false,
                )
            } else {
                null
            }

        val snapshotViews =
            page.snapshots.map { s ->
                PostVersionView(
                    versionLabel = "Versi ke-${s.versionNumber}",
                    isLive = false,
                    content = s.content,
                    timestamp = s.editedAt,
                    authorId = s.editedById,
                    authorUsername = s.editorUsername,
                    locationChanged = s.locationChanged,
                )
            }

        return PostEditHistoryView(
            found = true,
            postId = postId,
            authorId = live.authorId,
            authorUsername = live.authorUsername,
            liveVersion = liveView,
            snapshots = snapshotViews,
            // The "no prior edit history" note is for the first page of a post
            // that has never been edited (a single live version). On later pages
            // there are by definition snapshots, so it never shows there.
            showNoHistoryNote = isFirstPage && page.snapshots.isEmpty(),
            nextCursor = page.nextCursor?.encode(),
        )
    }

    private companion object {
        const val LIVE_LABEL = "Versi terbaru"
        val NOT_FOUND =
            PostEditHistoryView(
                found = false,
                postId = null,
                authorId = null,
                authorUsername = null,
                liveVersion = null,
                snapshots = emptyList(),
                showNoHistoryNote = false,
                nextCursor = null,
            )
    }
}

/**
 * One rendered version row. Carries ONLY a boolean [locationChanged] — no
 * geography/coordinate field (spec: "the view model carries no geography or
 * coordinate field"). [timestamp] is the live `posts` `COALESCE(updated_at,
 * created_at)` for the live version, or the snapshot's `edited_at` otherwise.
 */
data class PostVersionView(
    val versionLabel: String,
    val isLive: Boolean,
    val content: String,
    val timestamp: Instant,
    val authorId: UUID,
    val authorUsername: String?,
    val locationChanged: Boolean,
)

/**
 * The composed history for the route to render. [found] = false drives the
 * not-found empty state (unknown / non-UUID / hard-deleted). [liveVersion] is
 * present on the first page only; [showNoHistoryNote] flags a post with no prior
 * edits; [nextCursor] is the encoded "older" cursor (null = last/only page).
 */
data class PostEditHistoryView(
    val found: Boolean,
    val postId: UUID?,
    val authorId: UUID?,
    val authorUsername: String?,
    val liveVersion: PostVersionView?,
    val snapshots: List<PostVersionView>,
    val showNoHistoryNote: Boolean,
    val nextCursor: String?,
)
