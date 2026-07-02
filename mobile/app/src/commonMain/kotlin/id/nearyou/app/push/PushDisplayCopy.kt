package id.nearyou.app.push

import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.notif_chat_message
import id.nearyou.resources.generated.resources.notif_followed
import id.nearyou.resources.generated.resources.notif_generic
import id.nearyou.resources.generated.resources.notif_post_auto_hidden
import id.nearyou.resources.generated.resources.notif_post_liked
import id.nearyou.resources.generated.resources.notif_post_replied
import id.nearyou.resources.generated.resources.push_channel_name
import id.nearyou.resources.generated.resources.push_chat_batched
import id.nearyou.resources.generated.resources.push_chat_batched_no_actor
import id.nearyou.resources.generated.resources.push_chat_embedded_fallback
import id.nearyou.resources.generated.resources.push_chat_message_private
import id.nearyou.resources.generated.resources.push_followed
import id.nearyou.resources.generated.resources.push_post_liked
import id.nearyou.resources.generated.resources.push_post_replied
import id.nearyou.resources.generated.resources.push_title
import org.jetbrains.compose.resources.getString

/**
 * The type-keyed incoming-push display copy (`mobile-push-message-handling` § "Android renders an
 * incoming data-only FCM push"; docs/03 §163–176), sourced from `:shared:resources` — the platform
 * display paths (the Android notification builder; iOS gets its base copy server-built) call these
 * suspend accessors, so no user-visible notification string literal lives in platform source.
 *
 * `actorUsername` arrives ALREADY masked by the dispatcher (a resolved name, `"Seseorang"`, or `""`
 * for actor-less) — it is substituted verbatim, never re-resolved. A blank value degrades to the
 * username-free `notif_*` forms (no orphaned "dari " / leading-space interpolation).
 */
object PushDisplayCopy {
    suspend fun title(): String = getString(Res.string.push_title)

    /** The user-visible (OS notification settings) channel name. */
    suspend fun channelName(): String = getString(Res.string.push_channel_name)

    /**
     * The notification body for [type]. For `chat_message`, [showPreview] is the content-privacy
     * gate — the caller passes `false` for a malformed `body_data` too (the defensive
     * render-the-private-form posture) — and [preview] is `body_data.preview` (`null` = the
     * embedded-only message → the localized fallback, never raw content).
     */
    suspend fun body(
        type: String,
        actorUsername: String?,
        showPreview: Boolean = false,
        preview: String? = null,
    ): String {
        val actor = actorUsername?.takeIf { it.isNotBlank() }
        return when (type) {
            "chat_message" ->
                when {
                    showPreview && preview != null -> preview
                    showPreview ->
                        actor?.let { getString(Res.string.push_chat_embedded_fallback, it) }
                            ?: getString(Res.string.notif_chat_message)
                    else ->
                        actor?.let { getString(Res.string.push_chat_message_private, it) }
                            ?: getString(Res.string.notif_chat_message)
                }
            "post_liked" ->
                actor?.let { getString(Res.string.push_post_liked, it) }
                    ?: getString(Res.string.notif_post_liked)
            "post_replied" ->
                actor?.let { getString(Res.string.push_post_replied, it) }
                    ?: getString(Res.string.notif_post_replied)
            "followed" ->
                actor?.let { getString(Res.string.push_followed, it) }
                    ?: getString(Res.string.notif_followed)
            "post_auto_hidden" -> getString(Res.string.notif_post_auto_hidden)
            else -> getString(Res.string.notif_generic)
        }
    }

    /** The per-conversation batched form: "{n} pesan baru dari {username}" (username-free when blank). */
    suspend fun batchedChatBody(
        count: Int,
        actorUsername: String?,
    ): String {
        val actor = actorUsername?.takeIf { it.isNotBlank() }
        return actor?.let { getString(Res.string.push_chat_batched, count, it) }
            ?: getString(Res.string.push_chat_batched_no_actor, count)
    }
}
