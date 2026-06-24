package id.nearyou.app.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * The ONE shared timeline **feed list** (`ui/components/`, audit 05-#11) — collapses the verbatim-twin
 * `PostList` private composables the Nearby / Global / Following feeds each carried. Renders a paginated
 * `LazyColumn` (tagged [listTag]): an optional soft-limit [banner] as the first item, then a [PostCard]
 * per [posts] entry (projected via [cardModelOf], tagged [cardTag]), then the canonical [LoadMoreFooter]
 * as the terminal item. The scroll-end detector ([LoadMoreOnScrollEnd]) drives [onLoadMore]; the
 * footer's spinner/retry are mutually exclusive with the initial-load skeleton (which lives in a separate
 * non-`Content` state). Bottom content padding clears the shell's overlaid composer FAB.
 *
 * Generic over the feed's PII-free post type [T] so every callback ([onOpenPost] / [onToggleLike] /
 * [onReplyShortcut] / [onOpenProfile]) carries the original post — the host resolves the author UUID off
 * the raw outcome, never off the [PostCardModel] (which structurally cannot hold it). [keyOf] supplies the
 * stable list key (docs/11 § 2.4) and [cardModelOf] the display projection.
 */
@Composable
fun <T> PostFeedList(
    posts: List<T>,
    keyOf: (T) -> String,
    cardModelOf: (T) -> PostCardModel,
    isLoadingMore: Boolean,
    loadMoreError: Boolean,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
    onOpenPost: (T) -> Unit,
    onToggleLike: (T) -> Unit,
    onReplyShortcut: (T) -> Unit,
    onOpenProfile: (T) -> Unit,
    listTag: String,
    cardTag: String,
    modifier: Modifier = Modifier,
    banner: String? = null,
) {
    val listState = rememberLazyListState()
    LoadMoreOnScrollEnd(listState = listState, onLoadMore = onLoadMore)
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().testTag(listTag),
        // Bottom clearance for the shell's overlaid composer FAB (56dp + 16 margin + breathing room) so
        // the last card's like/reply row never sits under it at scroll end (2026-06-10 audit, 06 low).
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
    ) {
        if (banner != null) {
            item {
                SoftLimitBanner(text = banner)
            }
        }
        items(items = posts, key = { keyOf(it) }, contentType = { "post" }) { post ->
            // The ONE shared card (mobile-post-card). The whole card is the open-detail tap; the action
            // row's like/reply affordances are the only other targets (mobile-inline-post-actions). All
            // callbacks carry the PII-free post (display identity included, never the author UUID / coords).
            PostCard(
                model = cardModelOf(post),
                onOpen = { onOpenPost(post) },
                onToggleLike = { onToggleLike(post) },
                onReplyShortcut = { onReplyShortcut(post) },
                onOpenProfile = { onOpenProfile(post) },
                modifier = Modifier.testTag(cardTag),
            )
        }
        // Load-more footer: spinner while a page loads, non-destructive retry on error, nothing at end
        // (mobile-design-system § "Canonical list load-more pattern"). The scroll-end detector above drives
        // onLoadMore; the LoadMoreController's guards make an eager trigger on a short list a no-op.
        item(key = "loadMoreFooter", contentType = "footer") {
            LoadMoreFooter(
                isLoadingMore = isLoadingMore,
                loadMoreError = loadMoreError,
                onRetry = onRetryLoadMore,
            )
        }
    }
}
