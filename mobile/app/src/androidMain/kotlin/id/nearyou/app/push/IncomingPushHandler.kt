package id.nearyou.app.push

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import id.nearyou.app.MainActivity
import id.nearyou.app.R
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Renders an incoming data-only FCM push as a local notification
 * (`mobile-push-message-handling` § "Android renders an incoming data-only FCM push"). Invoked by
 * `NearYouFirebaseMessagingService.onMessageReceived` with the raw data map; exercised directly by
 * Robolectric tests (the SDK never invokes the service without operator Firebase config — the PR
 * #250 precedent).
 *
 * Defensive contract: [handle] NEVER throws (an uncaught throw in the high-priority FCM callback
 * silently drops the push); a malformed `body_data` renders the non-content private form; a blank
 * `actor_username` degrades to the username-free copy (see [PushDisplayCopy]). Nothing here logs —
 * `actor_user_id` / `target_id` / `conversation_id` / the preview ride only as opaque
 * notification-tap extras (the no-PII-in-logs guard, `FcmPushSourceGuardTest`).
 */
class IncomingPushHandler(
    private val context: Context,
    private val preference: NotificationContentPreference =
        PersistedNotificationContentPreference(AndroidNotificationContentPreferenceStore(context)),
    private val batchTracker: PushBatchTracker = PushBatchTracker(context),
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    fun handle(data: Map<String, String>) {
        try {
            runBlocking { render(data) }
        } catch (_: Throwable) {
            // Never throw out of the FCM callback — a failed render drops this push's display,
            // never the process (the in-app notifications list is the fallback surface).
        }
    }

    private suspend fun render(data: Map<String, String>) {
        val type = data["type"].orEmpty().ifBlank { return }
        val actorUsername = data["actor_username"].orEmpty()
        val targetType = data["target_type"].orEmpty()
        val targetId = data["target_id"].orEmpty()
        val bodyDataJson = data["body_data"].orEmpty()
        val bodyData = parseChatBodyData(bodyDataJson)

        val previewEnabled =
            try {
                preference.previewEnabled()
            } catch (_: Throwable) {
                false // an unreadable preference degrades to the private default
            }
        // Malformed body_data forces the private form (never the embedded-only fallback, which
        // would misdescribe an unparseable payload).
        val showPreview = previewEnabled && !bodyData.malformed
        val body = PushDisplayCopy.body(type, actorUsername, showPreview, bodyData.preview)

        val chatConversationId = if (type == "chat_message") bodyData.conversationId else null
        val tag: String
        val displayBody: String
        val suppressSound: Boolean
        if (chatConversationId != null) {
            val batch = batchTracker.recordChatPush(chatConversationId, now())
            tag = chatConversationId
            if (batch.withinWindow) {
                displayBody = PushDisplayCopy.batchedChatBody(batch.count, actorUsername)
                suppressSound = true
            } else {
                displayBody = body
                suppressSound = false
            }
        } else {
            // Non-chat: a per-(type, target_id) tag so unrelated notifications never overwrite.
            tag = "$type:$targetId"
            displayBody = body
            suppressSound = false
        }

        ensureChannel()
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(PushDisplayCopy.title())
                .setContentText(displayBody)
                .setAutoCancel(true)
                // Within the batching window the tagged notification is REPLACED without a fresh
                // alert sound (docs/04 §484–486) — FLAG_ONLY_ALERT_ONCE is the canonical
                // update-without-re-alerting bit for a same-tag re-post.
                .setOnlyAlertOnce(suppressSound)
                .setContentIntent(tapIntent(tag, data))
                .build()
        val manager = NotificationManagerCompat.from(context)
        // POST_NOTIFICATIONS not granted (Android 13+) → the OS drops it anyway; skip the
        // SecurityException path. The runtime prompt is the chat surface's (#257).
        if (manager.areNotificationsEnabled()) {
            manager.notify(tag, NOTIFICATION_ID, notification)
        }
    }

    /** The tap `PendingIntent` → [MainActivity], carrying the routing fields as opaque extras
     *  (consumed once by `MainActivity` into the `PushTapNavSignal`). */
    private fun tapIntent(
        tag: String,
        data: Map<String, String>,
    ): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java).apply {
                // A per-tag data URI makes the intents filterEquals-DISTINCT: PendingIntent
                // equality ignores extras, so without it two tags with colliding hashCodes would
                // share one PendingIntent and FLAG_UPDATE_CURRENT would cross-wire their routing.
                // (setData, not `data =` — the apply lambda would resolve `data` to the function
                // parameter shadowing Intent.data.)
                setData(android.net.Uri.parse("nearyou://push/$tag"))
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_TYPE, data["type"].orEmpty())
                putExtra(EXTRA_TARGET_TYPE, data["target_type"].orEmpty())
                putExtra(EXTRA_TARGET_ID, data["target_id"].orEmpty())
                putExtra(EXTRA_ACTOR_USER_ID, data["actor_user_id"].orEmpty())
                putExtra(EXTRA_BODY_DATA, data["body_data"].orEmpty())
            }
        return PendingIntent.getActivity(
            context,
            tag.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private suspend fun ensureChannel() {
        // Idempotent — createNotificationChannel is a no-op when the channel exists.
        val channel =
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(PushDisplayCopy.channelName())
                .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    /** The `chat_message` keys of the parsed `body_data` (`{conversation_id, preview}`); tolerant of
     *  added keys (forward-compat with `chat-embedded-posts`, in-app-notifications §286). */
    private data class ChatBodyData(
        val conversationId: String?,
        val preview: String?,
        val malformed: Boolean,
    )

    private fun parseChatBodyData(raw: String): ChatBodyData {
        if (raw.isBlank()) return ChatBodyData(null, null, malformed = true)
        val obj =
            try {
                Json.parseToJsonElement(raw) as? JsonObject
            } catch (_: Throwable) {
                null
            } ?: return ChatBodyData(null, null, malformed = true)
        val conversationId =
            (obj["conversation_id"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.ifBlank { null }
        // JSON null = the embedded-only message (a VALID shape → the localized fallback when the
        // preview is ON); a MISSING conversation_id/preview key is malformed (→ private form).
        val previewElement = obj["preview"]
        val preview = (previewElement as? JsonPrimitive)?.takeIf { it.isString }?.content
        return ChatBodyData(
            conversationId = conversationId,
            preview = preview,
            malformed = conversationId == null || previewElement == null,
        )
    }

    companion object {
        const val CHANNEL_ID = "nearyou_notifications"

        /** One id per tag — the tag carries the identity (conversation / type+target). */
        const val NOTIFICATION_ID = 1

        const val EXTRA_TYPE = "id.nearyou.push.type"
        const val EXTRA_TARGET_TYPE = "id.nearyou.push.target_type"
        const val EXTRA_TARGET_ID = "id.nearyou.push.target_id"
        const val EXTRA_ACTOR_USER_ID = "id.nearyou.push.actor_user_id"
        const val EXTRA_BODY_DATA = "id.nearyou.push.body_data"
    }
}
