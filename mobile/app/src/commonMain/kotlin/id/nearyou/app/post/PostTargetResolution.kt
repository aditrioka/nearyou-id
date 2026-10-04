package id.nearyou.app.post

/**
 * Neutral resolution of a post looked up by id through the full-projection `single-post-read` — the ONE
 * shared by-id resolution behind every pre-push lookup (the notification deep-link, `mobile-notifications-list`;
 * the search-result tap, `mobile-search`). NOT a screens type: the data seams stay free of `PostDetailTarget`,
 * and the screens map [Resolved] → `PostDetailTarget(distanceM = null, …)` via `toPostDetailTarget()`. What a
 * consumer does on [Unavailable] is its own rule (a notification shows "tidak tersedia" and does not navigate;
 * a search hit falls back to its own payload). No author UUID / coordinate is carried (the by-id projection
 * has none — no-PII).
 */
sealed interface PostTargetResolution {
    data class Resolved(
        val postId: String,
        val authorUsername: String,
        val authorDisplayName: String,
        val content: String,
        val cityName: String,
        val createdAtIso: String,
        val likedByViewer: Boolean,
        val replyCount: Int,
        // image-attached-posts (#388): the post's public image URL from the by-id read, or null for a
        // text-only post. NO default — every mapper states it explicitly (a silent default-null is how
        // the notification deep-link lost the image in the first place).
        val imageUrl: String?,
    ) : PostTargetResolution

    /** The endpoint's single direction-less `404 post_not_found`, any other non-200, or a transport failure. */
    data object Unavailable : PostTargetResolution
}

/** The one [SinglePostFullResult] → [PostTargetResolution] mapping every by-id consumer's repository shares. */
fun SinglePostFullResult.toPostTargetResolution(): PostTargetResolution =
    when (this) {
        is SinglePostFullResult.Success ->
            PostTargetResolution.Resolved(
                postId = post.id,
                authorUsername = post.authorUsername,
                authorDisplayName = post.authorDisplayName,
                content = post.content,
                cityName = post.cityName,
                createdAtIso = post.createdAt,
                likedByViewer = post.likedByViewer,
                replyCount = post.replyCount,
                imageUrl = post.imageUrl,
            )
        SinglePostFullResult.Unavailable -> PostTargetResolution.Unavailable
    }
