package id.nearyou.app.push

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import id.nearyou.app.screens.notifications.NotificationNavIntent
import id.nearyou.app.screens.notifications.resolveNotificationNavIntent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `mobile-push-message-handling` task 5.6 — the Android `onMessageReceived` display path, exercised
 * directly through [IncomingPushHandler] (the SDK never invokes the service without operator
 * Firebase config — the PR #250 precedent): default-private chat body; masked-actor verbatim
 * render; blank-actor username-free degradation; malformed `body_data` never throwing; the
 * preview-ON projections; type-keyed post-interaction copy; the tap `PendingIntent` extras feeding
 * the SHARED resolver; and the per-conversation batching window (merge + only-alert-once within,
 * fresh outside, state surviving a handler cold start).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class IncomingPushHandlerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private val notificationManager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private class FakePreference(var enabled: Boolean = false) : NotificationContentPreference {
        override suspend fun previewEnabled(): Boolean = enabled

        override suspend fun setPreviewEnabled(v: Boolean) {
            enabled = v
        }
    }

    private var nowMs: Long = 1_000_000L

    // The batching DataStore is a process-wide singleton that outlives Robolectric's per-test
    // Application, so each test uses its own conversation id to stay isolated.
    private val convId = "conv-" + System.nanoTime()

    private fun handler(previewEnabled: Boolean = false): IncomingPushHandler =
        IncomingPushHandler(
            context = context,
            preference = FakePreference(previewEnabled),
            batchTracker = PushBatchTracker(context),
            now = { nowMs },
        )

    private fun chatData(
        conversationId: String = convId,
        preview: String? = "halo apa kabar",
        actorUsername: String = "bobby",
        bodyDataOverride: String? = null,
    ): Map<String, String> {
        val bodyData =
            bodyDataOverride ?: buildString {
                append("""{"conversation_id":"$conversationId","preview":""")
                append(if (preview == null) "null" else """"$preview"""")
                append("}")
            }
        return mapOf(
            "type" to "chat_message",
            "actor_user_id" to "actor-uuid-1",
            "actor_username" to actorUsername,
            "target_type" to "message",
            "target_id" to "msg-uuid-1",
            "body_data" to bodyData,
        )
    }

    private fun postedNotification(tag: String): Notification? =
        shadowOf(notificationManager).getNotification(tag, IncomingPushHandler.NOTIFICATION_ID)

    private fun bodyOf(n: Notification): String? = n.extras.getString(Notification.EXTRA_TEXT)

    // ---- content privacy ------------------------------------------------

    @Test
    fun chatPush_defaultPrivate_rendersSenderNonContentBody_withoutPreview() {
        handler(previewEnabled = false).handle(chatData())

        val posted = assertNotNull(postedNotification(convId))
        assertEquals("Pesan baru dari bobby", bodyOf(posted))
        assertFalse(bodyOf(posted)!!.contains("halo apa kabar"), "the preview must not leak at the private default")
    }

    @Test
    fun maskedActor_isRenderedVerbatim_notReResolved() {
        handler().handle(chatData(actorUsername = "Seseorang"))

        assertEquals("Pesan baru dari Seseorang", bodyOf(assertNotNull(postedNotification(convId))))
    }

    @Test
    fun blankActorUsername_degradesToUsernameFreeForm_noOrphanedSpace() {
        handler().handle(chatData(actorUsername = ""))

        val body = assertNotNull(bodyOf(assertNotNull(postedNotification(convId))))
        assertEquals("Pesan baru", body)
        assertEquals(body.trim(), body, "no orphaned leading/trailing space")
    }

    @Test
    fun previewOn_surfacesTheChatPreview() {
        handler(previewEnabled = true).handle(chatData(preview = "halo apa kabar"))

        assertEquals("halo apa kabar", bodyOf(assertNotNull(postedNotification(convId))))
    }

    @Test
    fun previewOn_nullPreview_rendersEmbeddedFallback_neverRawContent() {
        handler(previewEnabled = true).handle(chatData(preview = null))

        assertEquals("bobby mengirim sebuah postingan", bodyOf(assertNotNull(postedNotification(convId))))
    }

    // ---- malformed body_data --------------------------------------------

    @Test
    fun malformedBodyData_emptyString_rendersPrivateForm_andNeverThrows() {
        handler(previewEnabled = true).handle(chatData(bodyDataOverride = ""))

        // No conversation_id → the non-chat (type,target_id) tag is used.
        val posted = assertNotNull(postedNotification("chat_message:msg-uuid-1"))
        assertEquals("Pesan baru dari bobby", bodyOf(posted))
    }

    @Test
    fun malformedBodyData_nonJsonGarbage_rendersPrivateForm_andNeverThrows() {
        handler(previewEnabled = true).handle(chatData(bodyDataOverride = "not{json[at,all"))

        assertEquals(
            "Pesan baru dari bobby",
            bodyOf(assertNotNull(postedNotification("chat_message:msg-uuid-1"))),
        )
    }

    @Test
    fun malformedBodyData_missingKeys_rendersPrivateForm_andNeverThrows() {
        // conversation_id present but preview key MISSING (≠ JSON null) → malformed → private form
        // even with the preference ON.
        handler(previewEnabled = true).handle(chatData(bodyDataOverride = """{"conversation_id":"$convId"}"""))

        assertEquals("Pesan baru dari bobby", bodyOf(assertNotNull(postedNotification(convId))))
    }

    // ---- type-keyed copy -------------------------------------------------

    @Test
    fun postLiked_rendersTypeKeyedCopyWithActorUsername() {
        handler().handle(
            mapOf(
                "type" to "post_liked",
                "actor_user_id" to "actor-uuid-1",
                "actor_username" to "bobby",
                "target_type" to "post",
                "target_id" to "post-uuid-1",
                "body_data" to """{"post_excerpt":"Hi"}""",
            ),
        )

        assertEquals(
            "bobby menyukai postingan kamu",
            bodyOf(assertNotNull(postedNotification("post_liked:post-uuid-1"))),
        )
    }

    // ---- tap routing ------------------------------------------------------

    @Test
    fun tapPendingIntent_carriesRoutingExtras_thatResolveToThePostDetailDestination() {
        handler().handle(
            mapOf(
                "type" to "post_liked",
                "actor_user_id" to "actor-uuid-1",
                "actor_username" to "bobby",
                "target_type" to "post",
                "target_id" to "post-uuid-1",
                "body_data" to "{}",
            ),
        )

        val posted = assertNotNull(postedNotification("post_liked:post-uuid-1"))
        val tapped = shadowOf(posted.contentIntent).savedIntent
        val routing =
            PushTapRouting.fromWire(
                type = tapped.getStringExtra(IncomingPushHandler.EXTRA_TYPE),
                targetType = tapped.getStringExtra(IncomingPushHandler.EXTRA_TARGET_TYPE),
                targetId = tapped.getStringExtra(IncomingPushHandler.EXTRA_TARGET_ID),
                actorUserId = tapped.getStringExtra(IncomingPushHandler.EXTRA_ACTOR_USER_ID),
                bodyDataJson = tapped.getStringExtra(IncomingPushHandler.EXTRA_BODY_DATA),
            )
        val intent =
            resolveNotificationNavIntent(
                targetType = routing.targetType,
                targetId = routing.targetId,
                actorUserId = routing.actorUserId,
                bodyData = Json.parseToJsonElement(routing.bodyDataJson ?: "{}"),
            )
        assertEquals(NotificationNavIntent.OpenPost("post-uuid-1"), intent)
    }

    @Test
    fun tapPendingIntent_chatMessage_resolvesToTheConversationThread() {
        handler().handle(chatData(conversationId = convId))

        val tapped = shadowOf(assertNotNull(postedNotification(convId)).contentIntent).savedIntent
        val intent =
            resolveNotificationNavIntent(
                targetType = tapped.getStringExtra(IncomingPushHandler.EXTRA_TARGET_TYPE)!!.ifBlank { null },
                targetId = tapped.getStringExtra(IncomingPushHandler.EXTRA_TARGET_ID)!!.ifBlank { null },
                actorUserId = tapped.getStringExtra(IncomingPushHandler.EXTRA_ACTOR_USER_ID)!!.ifBlank { null },
                bodyData = Json.parseToJsonElement(tapped.getStringExtra(IncomingPushHandler.EXTRA_BODY_DATA)!!),
            )
        val chat = assertIs<NotificationNavIntent.OpenChat>(intent)
        assertEquals(convId, chat.conversationId)
    }

    @Test
    fun noDestinationPush_tapResolvesToNone_navigatesNowhere() {
        // A reply-target post_auto_hidden: rendered, but the resolver yields no destination.
        handler().handle(
            mapOf(
                "type" to "post_auto_hidden",
                "actor_user_id" to "",
                "actor_username" to "",
                "target_type" to "reply",
                "target_id" to "reply-uuid-1",
                "body_data" to "{}",
            ),
        )

        val posted = assertNotNull(postedNotification("post_auto_hidden:reply-uuid-1"))
        val tapped = shadowOf(posted.contentIntent).savedIntent
        val intent =
            resolveNotificationNavIntent(
                targetType = tapped.getStringExtra(IncomingPushHandler.EXTRA_TARGET_TYPE),
                targetId = tapped.getStringExtra(IncomingPushHandler.EXTRA_TARGET_ID),
                actorUserId = tapped.getStringExtra(IncomingPushHandler.EXTRA_ACTOR_USER_ID)!!.ifBlank { null },
                bodyData = JsonObject(emptyMap()),
            )
        assertEquals(NotificationNavIntent.None, intent)
    }

    // ---- batching ---------------------------------------------------------

    @Test
    fun secondChatPushWithinWindow_replacesWithMergedBody_andOnlyAlertsOnce() {
        val h = handler()
        h.handle(chatData())
        nowMs += 5_000 // within the 10 s window
        h.handle(chatData())

        val posted = assertNotNull(postedNotification(convId))
        assertEquals("2 pesan baru dari bobby", bodyOf(posted))
        assertTrue(
            posted.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0,
            "the in-window replacement must not raise a fresh alert sound",
        )
        // Only ONE notification for the conversation (same tag replaced, not stacked).
        assertEquals(1, shadowOf(notificationManager).allNotifications.size)
    }

    @Test
    fun chatPushOutsideWindow_postsFresh_withWindowStatePersistedAcrossColdStart() {
        handler().handle(chatData())
        nowMs += 60_000 // well outside the window

        // A NEW handler instance = the service cold-started; the window state must come from
        // persistence, not handler memory.
        handler().handle(chatData())

        val posted = assertNotNull(postedNotification(convId))
        assertEquals("Pesan baru dari bobby", bodyOf(posted), "outside the window the body is fresh (count reset)")
        assertEquals(0, posted.flags and Notification.FLAG_ONLY_ALERT_ONCE, "a fresh post alerts normally")
    }

    @Test
    fun batchingMerge_survivesHandlerColdStartWithinWindow() {
        handler().handle(chatData())
        nowMs += 3_000
        handler().handle(chatData()) // new instance, same persisted window

        assertEquals("2 pesan baru dari bobby", bodyOf(assertNotNull(postedNotification(convId))))
    }

    @Test
    fun nonChatTypes_useTypeAndTargetTags_soUnrelatedNotificationsDoNotOverwrite() {
        val h = handler()
        h.handle(
            mapOf(
                "type" to "post_liked",
                "actor_user_id" to "a",
                "actor_username" to "bobby",
                "target_type" to "post",
                "target_id" to "p1",
                "body_data" to "{}",
            ),
        )
        h.handle(
            mapOf(
                "type" to "post_replied",
                "actor_user_id" to "a",
                "actor_username" to "budi",
                "target_type" to "post",
                "target_id" to "p1",
                "body_data" to "{}",
            ),
        )

        assertNotNull(postedNotification("post_liked:p1"))
        assertNotNull(postedNotification("post_replied:p1"))
        assertEquals(2, shadowOf(notificationManager).allNotifications.size)
    }

    @Test
    fun missingType_rendersNothing_andDoesNotThrow() {
        handler().handle(mapOf("body_data" to "{}"))

        assertEquals(0, shadowOf(notificationManager).allNotifications.size)
        assertNull(postedNotification(":"))
    }
}
