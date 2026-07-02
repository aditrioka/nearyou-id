package id.nearyou.app.ui.components

/**
 * A timeline feed entry — either a real [PostItem] or a [NativeAdSlot] (mobile-admob-ads-foundation). The
 * sealed shape lets the canonical [PostFeedList] render a heterogeneous list with a stable per-item [key]
 * and `contentType` (docs/11 §2.4) WITHOUT forking the list pattern — ads compose OVER the post feed
 * (design D5), they don't change post fetch/order.
 */
sealed interface FeedItem<out T> {
    /** Stable, distinct `LazyColumn` key for this entry. */
    val key: String

    data class PostItem<out T>(
        val post: T,
        override val key: String,
    ) : FeedItem<T>

    data class NativeAdSlot(
        override val key: String,
    ) : FeedItem<Nothing>
}

/**
 * Interleave a native-ad slot after every [frequency] posts (the `ads-config` `timelineFrequency`), preserving
 * post order. Pure + Compose-free so the placement math is unit-testable (task 5.1).
 *
 * Boundary definition (the off-by-one the spec asks us to pin down):
 *  - [frequency] `null` / `<= 0`, or `posts.size < frequency` → NO slots (every entry is a [FeedItem.PostItem]).
 *  - otherwise a [FeedItem.NativeAdSlot] is inserted after EVERY `frequency`-th post, INCLUDING a trailing
 *    slot when the post count is an exact multiple of [frequency]. So a feed of exactly N yields 1 slot
 *    (trailing), and a feed of ≥2N yields ≥2 slots at the N-th and 2N-th boundaries.
 *
 * Slot keys are `ad:<postCountAtInsertion>` (`ad:6`, `ad:12`, …) — distinct + stable across recomposition
 * and pagination appends (duplicate keys crash `LazyColumn`, docs/11 §2.4).
 */
fun <T> interleaveNativeAds(
    posts: List<T>,
    frequency: Int?,
    keyOf: (T) -> String,
): List<FeedItem<T>> {
    val items = ArrayList<FeedItem<T>>(posts.size)
    if (frequency == null || frequency <= 0 || posts.size < frequency) {
        posts.forEach { items.add(FeedItem.PostItem(it, keyOf(it))) }
        return items
    }
    posts.forEachIndexed { index, post ->
        items.add(FeedItem.PostItem(post, keyOf(post)))
        val placed = index + 1
        if (placed % frequency == 0) {
            items.add(FeedItem.NativeAdSlot("ad:$placed"))
        }
    }
    return items
}
