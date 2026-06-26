package id.nearyou.app.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure placement-math coverage for [interleaveNativeAds] (task 5.1). */
class FeedItemInterleaveTest {
    private fun posts(n: Int): List<String> = (1..n).map { "p$it" }

    private val keyOf: (String) -> String = { it }

    @Test
    fun `null frequency yields only posts`() {
        val result = interleaveNativeAds(posts(10), null, keyOf)
        assertEquals(10, result.size)
        assertTrue(result.all { it is FeedItem.PostItem })
    }

    @Test
    fun `zero or negative frequency yields no slots`() {
        assertFalse(interleaveNativeAds(posts(10), 0, keyOf).any { it is FeedItem.NativeAdSlot })
        assertFalse(interleaveNativeAds(posts(10), -3, keyOf).any { it is FeedItem.NativeAdSlot })
    }

    @Test
    fun `feed shorter than frequency yields no slots`() {
        val result = interleaveNativeAds(posts(5), 6, keyOf)
        assertEquals(5, result.size)
        assertFalse(result.any { it is FeedItem.NativeAdSlot })
    }

    @Test
    fun `feed exactly N yields one trailing slot (boundary definition)`() {
        val result = interleaveNativeAds(posts(6), 6, keyOf)
        assertEquals(7, result.size) // 6 posts + 1 trailing ad
        assertTrue(result.last() is FeedItem.NativeAdSlot)
        assertEquals(1, result.count { it is FeedItem.NativeAdSlot })
    }

    @Test
    fun `feed of 2N yields two slots at the N-th and 2N-th boundaries`() {
        val result = interleaveNativeAds(posts(12), 6, keyOf)
        // [p1..p6, ad:6, p7..p12, ad:12]
        assertTrue(result[6] is FeedItem.NativeAdSlot)
        assertTrue(result[13] is FeedItem.NativeAdSlot)
        val slots = result.filterIsInstance<FeedItem.NativeAdSlot>()
        assertEquals(2, slots.size)
        assertEquals(listOf("ad:6", "ad:12"), slots.map { it.key })
    }

    @Test
    fun `ge 2N feed yields ge 2 slots with distinct stable keys`() {
        val result = interleaveNativeAds(posts(13), 6, keyOf)
        val slotKeys = result.filterIsInstance<FeedItem.NativeAdSlot>().map { it.key }
        assertTrue(slotKeys.size >= 2)
        assertEquals(slotKeys.size, slotKeys.toSet().size) // distinct (duplicate keys crash LazyColumn)
    }

    @Test
    fun `post order and keys are preserved through interleave`() {
        val result = interleaveNativeAds(posts(13), 6, keyOf)
        val postKeys = result.filterIsInstance<FeedItem.PostItem<String>>().map { it.key }
        assertEquals(posts(13), postKeys)
    }

    @Test
    fun `large feed keeps every slot key distinct`() {
        val slotKeys = interleaveNativeAds(posts(40), 6, keyOf).filterIsInstance<FeedItem.NativeAdSlot>().map { it.key }
        assertEquals(slotKeys.size, slotKeys.toSet().size)
    }
}
