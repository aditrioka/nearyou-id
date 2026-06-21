package id.nearyou.app.screens.notifications

import id.nearyou.app.notifications.FakeNotificationsFlow
import id.nearyou.app.notifications.NotificationsOutcome
import id.nearyou.app.notifications.PartnerResolution
import id.nearyou.app.notifications.PostTargetResolution
import id.nearyou.app.notifications.fakeNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit coverage of [NotificationsViewModel]'s deep-link resolution (`mobile-notifications-deep-link-targets`
 * task 6.2): the per-type intent mapping, the post-target full-projection fetch (→ `PostDetailTarget` with
 * `distanceM = null`; Unavailable → the non-blocking `postUnavailable` signal, no nav), the chat partner
 * fetch (→ `ChatThread`; partner-fetch failure → empty partner fields), the non-navigating cases
 * (`chat_message_redacted` actor-null / reply-target / informational / missing conversation_id), the
 * supersede-in-flight behavior, that mark-read fires on every tap, and the consumed-once `onNavConsumed`.
 *
 * An [UnconfinedTestDispatcher] is installed as Main so the resolution coroutines run eagerly against the
 * (non-suspending) fake — `onRowTap` returns with `pendingNavTarget` already set (mirrors the sibling test).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsViewModelNavTest {
    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun vm(
        vararg items: id.nearyou.app.notifications.NotificationDto,
        postTargetResolution: PostTargetResolution =
            PostTargetResolution.Resolved(
                postId = "p-resolved",
                authorUsername = "budi",
                authorDisplayName = "Budi",
                content = "halo dunia",
                cityName = "Jakarta",
                createdAtIso = "2026-05-31T10:00:00Z",
                likedByViewer = true,
                replyCount = 5,
            ),
        partnerResolution: PartnerResolution = PartnerResolution.Resolved("budi", "Budi"),
        suspendResolveOnCall: Int? = null,
    ): Pair<NotificationsViewModel, FakeNotificationsFlow> {
        val fake =
            FakeNotificationsFlow(
                outcome = NotificationsOutcome.Loaded(items.toList(), null),
                postTargetResolution = postTargetResolution,
                partnerResolution = partnerResolution,
                suspendResolveOnCall = suspendResolveOnCall,
            )
        return NotificationsViewModel(fake) to fake
    }

    private fun convBody(id: String) = buildJsonObject { put("conversation_id", id) }

    @Test
    fun postTargetTap_fetchesThenSetsPostNavTarget_withNullDistance() {
        val (viewModel, fake) =
            vm(fakeNotification(id = "n1", type = "post_liked", targetType = "post", targetId = "p1"))

        viewModel.onRowTap("n1")

        assertEquals(listOf("p1"), fake.resolvePostTargetIds, "the by-id fetch fired for the tapped row's target")
        val target = assertIs<NotificationNavTarget.Post>(viewModel.pendingNavTarget.value)
        assertNull(target.target.distanceM, "the by-id projection omits coordinates → distanceM is null")
        assertEquals("Jakarta", target.target.cityName)
        assertEquals(5, target.target.replyCount)
        assertTrue(target.target.likedByViewer)
        assertFalse(viewModel.postUnavailable.value)
    }

    @Test
    fun postTargetTap_unavailable_setsAffordance_noNav_rowStillRead() {
        val (viewModel, _) =
            vm(
                fakeNotification(id = "n1", type = "post_liked", targetType = "post", targetId = "p1"),
                postTargetResolution = PostTargetResolution.Unavailable,
            )

        viewModel.onRowTap("n1")

        assertTrue(viewModel.postUnavailable.value, "an unavailable post-target shows the non-blocking affordance")
        assertNull(viewModel.pendingNavTarget.value, "no navigation on an unavailable post-target")
        val loaded = viewModel.outcome.value as NotificationsOutcome.Loaded
        assertNotNull(loaded.items.first { it.id == "n1" }.readAt, "the row is still marked read")
    }

    @Test
    fun followedTap_setsProfileNavTarget_noFetch() {
        val (viewModel, fake) =
            vm(fakeNotification(id = "n1", type = "followed", targetType = null, actorUserId = "actor-1"))

        viewModel.onRowTap("n1")

        val target = assertIs<NotificationNavTarget.Profile>(viewModel.pendingNavTarget.value)
        assertEquals("actor-1", target.userId)
        assertTrue(fake.resolvePostTargetIds.isEmpty(), "followed does not fetch a post")
        assertTrue(fake.resolvePartnerIds.isEmpty(), "followed does not fetch a partner profile")
    }

    @Test
    fun chatMessageTap_fetchesPartner_setsChatThreadNavTarget() {
        val (viewModel, fake) =
            vm(
                fakeNotification(
                    id = "n1",
                    type = "chat_message",
                    targetType = "message",
                    actorUserId = "actor-1",
                    bodyData = convBody("conv-1"),
                ),
            )

        viewModel.onRowTap("n1")

        assertEquals(listOf("actor-1"), fake.resolvePartnerIds, "the partner profile is fetched via the actor (= 1:1 sender)")
        val target = assertIs<NotificationNavTarget.ChatThread>(viewModel.pendingNavTarget.value)
        assertEquals("conv-1", target.conversationId)
        assertEquals("budi", target.partnerUsername)
        assertEquals("Budi", target.partnerDisplayName)
    }

    @Test
    fun chatMessageTap_partnerUnavailable_opensThreadWithBlankPartner() {
        val (viewModel, _) =
            vm(
                fakeNotification(
                    id = "n1",
                    type = "chat_message",
                    targetType = "message",
                    actorUserId = "actor-1",
                    bodyData = convBody("conv-1"),
                ),
                partnerResolution = PartnerResolution.Unavailable,
            )

        viewModel.onRowTap("n1")

        val target = assertIs<NotificationNavTarget.ChatThread>(viewModel.pendingNavTarget.value)
        assertEquals("conv-1", target.conversationId, "the conversation is reachable even when the partner lookup fails")
        assertEquals("", target.partnerUsername)
        assertEquals("", target.partnerDisplayName)
    }

    @Test
    fun chatMessageRedacted_noActor_doesNotNavigate() {
        val (viewModel, fake) =
            vm(
                fakeNotification(
                    id = "n1",
                    type = "chat_message_redacted",
                    targetType = "message",
                    actorUserId = null,
                    bodyData = convBody("conv-1"),
                ),
            )

        viewModel.onRowTap("n1")

        assertNull(viewModel.pendingNavTarget.value, "no actor → no resolvable partner → non-navigating")
        assertTrue(fake.resolvePartnerIds.isEmpty())
        assertNotNull((viewModel.outcome.value as NotificationsOutcome.Loaded).items.first { it.id == "n1" }.readAt)
    }

    @Test
    fun replyTargetAutoHidden_doesNotNavigate() {
        val (viewModel, fake) =
            vm(fakeNotification(id = "n1", type = "post_auto_hidden", targetType = "reply", targetId = "r1"))

        viewModel.onRowTap("n1")

        assertNull(viewModel.pendingNavTarget.value, "a reply-target has no parent-post endpoint → non-navigating")
        assertTrue(fake.resolvePostTargetIds.isEmpty())
    }

    @Test
    fun informationalNoTarget_doesNotNavigate() {
        val (viewModel, _) =
            vm(fakeNotification(id = "n1", type = "subscription_expired", targetType = null, actorUserId = null))

        viewModel.onRowTap("n1")

        assertNull(viewModel.pendingNavTarget.value, "an informational no-target row navigates nowhere")
    }

    @Test
    fun messageMissingConversationId_doesNotNavigate() {
        val (viewModel, _) =
            vm(
                fakeNotification(
                    id = "n1",
                    type = "chat_message",
                    targetType = "message",
                    actorUserId = "actor-1",
                    bodyData = buildJsonObject { },
                ),
            )

        viewModel.onRowTap("n1")

        assertNull(viewModel.pendingNavTarget.value, "a message row missing conversation_id navigates nowhere")
    }

    @Test
    fun secondTapSupersedesInFlightResolution() {
        val (viewModel, fake) =
            vm(
                fakeNotification(id = "n1", type = "post_liked", targetType = "post", targetId = "p1"),
                fakeNotification(id = "n2", type = "post_liked", targetType = "post", targetId = "p2"),
                // the first resolution hangs; the second resolves
                suspendResolveOnCall = 1,
            )

        // resolution #1 suspends (no nav yet)
        viewModel.onRowTap("n1")
        assertNull(viewModel.pendingNavTarget.value, "the first resolution is still in flight")
        viewModel.onRowTap("n2") // supersedes #1; #2 resolves

        assertEquals(listOf("p1", "p2"), fake.resolvePostTargetIds, "both fetches were attempted")
        assertIs<NotificationNavTarget.Post>(viewModel.pendingNavTarget.value)
    }

    @Test
    fun onRowTap_alwaysMarksRead() {
        val (viewModel, _) =
            vm(fakeNotification(id = "n1", type = "post_liked", targetType = "post", targetId = "p1", readAt = null))

        viewModel.onRowTap("n1")

        assertNotNull(
            (viewModel.outcome.value as NotificationsOutcome.Loaded).items.first { it.id == "n1" }.readAt,
            "tapping marks the row read independently of navigation",
        )
    }

    @Test
    fun onNavConsumed_clearsPendingNavTarget() {
        val (viewModel, _) =
            vm(fakeNotification(id = "n1", type = "followed", targetType = null, actorUserId = "actor-1"))
        viewModel.onRowTap("n1")
        assertNotNull(viewModel.pendingNavTarget.value)

        viewModel.onNavConsumed()

        assertNull(viewModel.pendingNavTarget.value, "the consumed-once signal clears so it does not re-fire")
    }
}
