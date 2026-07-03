package id.nearyou.app.screens.notifications

import id.nearyou.app.notifications.NotificationsFlow
import id.nearyou.app.notifications.PartnerResolution
import id.nearyou.app.notifications.PostTargetResolution
import id.nearyou.app.screens.home.PostDetailTarget
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The ONE shared notification deep-link resolver (`mobile-notifications-list`, lifted to a shared
 * commonMain surface by `mobile-push-message-handling` task 4.1 so the push-tap path feeds the SAME
 * resolution — docs/11 §2.8 anti-patchwork: no second resolver). Consumed by NotificationsViewModel
 * (the in-app list row tap) and PushTapNavigationEffect (the notification tap on both platforms).
 */

/** Pre-fetch resolution of a notification's destination (the pure [resolveNotificationNavIntent] output). */
sealed interface NotificationNavIntent {
    data class OpenPost(val postId: String) : NotificationNavIntent

    data class OpenProfile(val userId: String) : NotificationNavIntent

    data class OpenChat(val conversationId: String, val actorUserId: String) : NotificationNavIntent

    data object None : NotificationNavIntent
}

/**
 * Pure mapping of a notification's canonical addressing to a typed nav intent (PII-free; no fetch).
 * `post` target → post detail by [targetId]; `message` target with an actor + a `conversation_id` in
 * [bodyData] → chat thread (`chat_message_redacted` has actor=NULL → none); null target + actor =
 * `followed` → profile; `reply` targets + unknown/future target types → no destination.
 */
fun resolveNotificationNavIntent(
    targetType: String?,
    targetId: String?,
    actorUserId: String?,
    bodyData: JsonElement,
): NotificationNavIntent =
    when (targetType) {
        "post" -> targetId?.let { NotificationNavIntent.OpenPost(it) } ?: NotificationNavIntent.None
        "message" -> {
            val conversationId = resolveConversationId(bodyData)
            // chat_message has actor (= the 1:1 sender = partner); chat_message_redacted has actor=NULL
            // → None. A message row missing conversation_id → None.
            if (conversationId != null && actorUserId != null) {
                NotificationNavIntent.OpenChat(conversationId, actorUserId)
            } else {
                NotificationNavIntent.None
            }
        }
        // null target + actor present = `followed` (the only such type in the V10 catalog) → profile.
        null -> actorUserId?.let { NotificationNavIntent.OpenProfile(it) } ?: NotificationNavIntent.None
        // "reply" (post_auto_hidden dynamic case) + any unknown/future target_type → no destination.
        else -> NotificationNavIntent.None
    }

private fun resolveConversationId(bodyData: JsonElement): String? {
    val obj = bodyData as? JsonObject ?: return null
    val primitive = obj["conversation_id"] as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.content.ifBlank { null }
}

/**
 * The consumed-once deep-link target the consumers forward to the matching nav push. Carries display
 * payload only — [Profile.userId] / [ChatThread.conversationId] are route keys (never rendered), and
 * the chat partner fields are display strings (no UUID).
 */
sealed interface NotificationNavTarget {
    data class Post(val target: PostDetailTarget) : NotificationNavTarget

    data class Profile(val userId: String) : NotificationNavTarget

    data class ChatThread(
        val conversationId: String,
        val partnerUsername: String,
        val partnerDisplayName: String,
    ) : NotificationNavTarget
}

/**
 * The fetch-following half of the shared resolution: [NotificationNavIntent] → [NotificationNavTarget]
 * via [NotificationsFlow] (`mobile-notifications-deep-link-targets` behavior inherited verbatim —
 * `post` → full-projection fetch (Unavailable → [Resolution.PostUnavailable], no nav); chat → partner
 * display-identity fetch whose failure still opens the thread with blank partner fields).
 */
class NotificationNavTargetResolver(
    private val flow: NotificationsFlow,
) {
    sealed interface Resolution {
        data class Target(val target: NotificationNavTarget) : Resolution

        data object PostUnavailable : Resolution

        data object None : Resolution
    }

    suspend fun resolve(intent: NotificationNavIntent): Resolution =
        when (intent) {
            is NotificationNavIntent.OpenProfile ->
                Resolution.Target(NotificationNavTarget.Profile(intent.userId))
            is NotificationNavIntent.OpenPost ->
                when (val res = flow.resolvePostTarget(intent.postId)) {
                    is PostTargetResolution.Resolved ->
                        Resolution.Target(NotificationNavTarget.Post(res.toPostDetailTarget()))
                    PostTargetResolution.Unavailable -> Resolution.PostUnavailable
                }
            is NotificationNavIntent.OpenChat -> {
                val (username, displayName) =
                    when (val partner = flow.resolvePartner(intent.actorUserId)) {
                        is PartnerResolution.Resolved -> partner.username to partner.displayName
                        // A failed partner lookup does NOT block the (valid) conversation — open
                        // the thread with blank partner fields (top bar degrades to placeholder).
                        PartnerResolution.Unavailable -> "" to ""
                    }
                Resolution.Target(
                    NotificationNavTarget.ChatThread(intent.conversationId, username, displayName),
                )
            }
            NotificationNavIntent.None -> Resolution.None
        }

    private fun PostTargetResolution.Resolved.toPostDetailTarget(): PostDetailTarget =
        PostDetailTarget(
            postId = postId,
            content = content,
            cityName = cityName,
            // The by-id single-post-read projection omits coordinates → no distance for a deep-linked post.
            distanceM = null,
            createdAtIso = createdAtIso,
            likedByViewer = likedByViewer,
            replyCount = replyCount,
            authorUsername = authorUsername,
            authorDisplayName = authorDisplayName,
        )
}
