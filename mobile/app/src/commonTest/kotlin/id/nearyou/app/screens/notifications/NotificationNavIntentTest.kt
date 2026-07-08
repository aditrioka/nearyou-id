package id.nearyou.app.screens.notifications

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `mobile-push-message-handling` task 4.3 — the lifted PURE resolver
 * ([resolveNotificationNavIntent], shared by the notifications list AND the
 * push tap): post → post-detail, `followed` → profile, `chat_message` →
 * chat-thread by `body_data.conversation_id`, actor-less / reply-target → no
 * destination (mirrors the `NotificationsViewModelNavTest` intent coverage,
 * exercised here without a ViewModel so the push path's usage is pinned).
 */
class NotificationNavIntentTest {
    private val emptyBody = JsonObject(emptyMap())

    private fun convBody(id: String) = buildJsonObject { put("conversation_id", id) }

    @Test
    fun postTarget_mapsToOpenPost() {
        val intent = resolveNotificationNavIntent("post", "p1", "actor-1", emptyBody)
        assertEquals(NotificationNavIntent.OpenPost("p1"), intent)
    }

    @Test
    fun postTargetMissingId_mapsToNone() {
        assertEquals(NotificationNavIntent.None, resolveNotificationNavIntent("post", null, "actor-1", emptyBody))
    }

    @Test
    fun followed_nullTargetWithActor_mapsToOpenProfile() {
        val intent = resolveNotificationNavIntent(null, null, "actor-1", emptyBody)
        assertEquals(NotificationNavIntent.OpenProfile("actor-1"), intent)
    }

    @Test
    fun chatMessage_mapsToOpenChatByConversationId() {
        val intent = resolveNotificationNavIntent("message", null, "actor-1", convBody("conv-1"))
        val chat = assertIs<NotificationNavIntent.OpenChat>(intent)
        assertEquals("conv-1", chat.conversationId)
        assertEquals("actor-1", chat.actorUserId)
    }

    @Test
    fun chatMessageRedacted_actorless_mapsToNone() {
        assertEquals(NotificationNavIntent.None, resolveNotificationNavIntent("message", null, null, convBody("conv-1")))
    }

    @Test
    fun messageMissingConversationId_mapsToNone() {
        assertEquals(NotificationNavIntent.None, resolveNotificationNavIntent("message", null, "actor-1", emptyBody))
    }

    @Test
    fun replyTarget_mapsToNone() {
        assertEquals(NotificationNavIntent.None, resolveNotificationNavIntent("reply", "r1", null, emptyBody))
    }

    @Test
    fun informationalActorlessNullTarget_mapsToNone() {
        assertEquals(NotificationNavIntent.None, resolveNotificationNavIntent(null, null, null, emptyBody))
    }
}
