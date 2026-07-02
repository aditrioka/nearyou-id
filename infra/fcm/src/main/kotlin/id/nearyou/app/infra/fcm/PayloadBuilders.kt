package id.nearyou.app.infra.fcm

import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.ApnsConfig
import com.google.firebase.messaging.Aps
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import id.nearyou.data.repository.NotificationRow

// Per-platform FCM `Message` builders. Both share the same `body_data`
// source-of-truth — only the wire format differs (Android = data-only;
// iOS = alert + mutable-content with `body_full` data field clamped to APNs
// 4 KB limit). See `openspec/changes/fcm-push-dispatch/design.md` D3 + D6.

/**
 * Android push: data-only message with `priority = HIGH` per
 * docs/04-Architecture.md:506. The app handles rendering locally with the
 * user's preview-toggle preference check.
 *
 * [actorUsername] is the `ActorUsernameLookup` result for the row's
 * `actor_user_id` — the SAME masked value `PushCopy.bodyFor` interpolates into
 * the iOS alert body. The dispatcher-side masking (`mobile-push-message-handling`
 * MODIFY) is applied here via [maskActorUsername] so the data-only Android
 * client renders faithful copy without a render-time network call and without
 * re-deriving the shadow-ban masking.
 */
fun buildAndroidMessage(
    notification: NotificationRow,
    actorUsername: String?,
    token: String,
): Message =
    Message.builder()
        .setToken(token)
        .setAndroidConfig(
            AndroidConfig.builder()
                .setPriority(AndroidConfig.Priority.HIGH)
                .build(),
        )
        .putData("type", notification.type.wire)
        .putData("actor_user_id", notification.actorUserId?.toString().orEmpty())
        .putData("actor_username", maskActorUsername(notification.actorUserId, actorUsername))
        .putData("target_type", notification.targetType.orEmpty())
        .putData("target_id", notification.targetId?.toString().orEmpty())
        // Spec: "" ONLY when body_data IS NULL; any real value — including the
        // `followed` type's literal {} — is passed through JSON-stringified.
        .putData("body_data", notification.bodyDataJson.orEmpty())
        .build()

/**
 * `actor_username` masking per the `fcm-push-dispatch` MODIFY: a resolved name
 * passes through; a **non-null** actor whose lookup returned null/blank
 * (shadow-banned / deleted / not visible via `visible_users`) masks to
 * [PushCopy.UNKNOWN_ACTOR] — NEVER the empty string and NEVER the real handle
 * (the Shadow-ban-safety seam); a null actor (system-emitted notification)
 * maps to `""` so the client renders actor-less copy.
 */
internal fun maskActorUsername(
    actorUserId: java.util.UUID?,
    resolvedUsername: String?,
): String =
    when {
        actorUserId == null -> ""
        resolvedUsername.isNullOrBlank() -> PushCopy.UNKNOWN_ACTOR
        else -> resolvedUsername
    }

/**
 * Outcome of attempting to clamp + build an iOS payload. The pathology case
 * (`body_data` exceeds APNs budget AND no single field can absorb the
 * overflow) returns [OversizedPayload] — `FcmDispatcher` skips the FCM call
 * and logs `event="fcm_dispatch_failed" error_code="payload_too_large"`.
 */
sealed interface IosPayloadResult {
    /** The built FCM message, with `body_full` clamped to fit APNs 4 KB. */
    data class Built(val message: Message, val bodyFull: String) : IosPayloadResult

    /** No single `body_data` field is large enough to truncate down to fit. */
    data object OversizedPayload : IosPayloadResult
}

/**
 * iOS push: alert + `mutable-content: 1` + `body_full` data field carrying the
 * un-truncated `body_data` JSON, PLUS the tap-routing custom data fields
 * `type` / `target_type` / `target_id` (same string/empty-string semantics as
 * the Android data block — `mobile-push-message-handling` MODIFY) so the iOS
 * `UNUserNotificationCenterDelegate` can feed the shared deep-link resolver
 * from `userInfo`. The NSE consumes `mutable-content` + `body_full` to
 * optionally rewrite the body based on the on-device preview-toggle.
 *
 * The assembled APNs payload is clamped to ≤ 4 KB by truncating the longest
 * `body_data` field on a UTF-8 codepoint boundary (Kotlin `String.take(n)`
 * operates on `Char` units; an extra guard avoids splitting surrogate pairs
 * that represent astral-plane codepoints like emoji). The clamp budget
 * accounts for the routing fields' bytes. Returns
 * [IosPayloadResult.OversizedPayload] when even the longest field cannot be
 * truncated enough to fit.
 */
fun buildIosMessage(
    notification: NotificationRow,
    actorUsername: String?,
    token: String,
): IosPayloadResult {
    val title = PushCopy.titleFor(notification.type.wire)
    val body = PushCopy.bodyFor(notification.type.wire, actorUsername)
    val routingType = notification.type.wire
    val routingTargetType = notification.targetType.orEmpty()
    val routingTargetId = notification.targetId?.toString().orEmpty()
    val routingActorUserId = notification.actorUserId?.toString().orEmpty()
    // Same NULL-vs-{} contract as the Android arm above.
    val rawBodyFull = notification.bodyDataJson.orEmpty()
    val clampedBodyFull =
        clampBodyFullForApns(
            rawBodyFull,
            title = title,
            body = body,
            routingFieldBytes =
                (routingType + routingTargetType + routingTargetId + routingActorUserId)
                    .toByteArray(Charsets.UTF_8).size,
        ) ?: return IosPayloadResult.OversizedPayload

    val message =
        Message.builder()
            .setToken(token)
            .setNotification(
                Notification.builder()
                    .setTitle(title)
                    .setBody(body)
                    .build(),
            )
            .setApnsConfig(
                ApnsConfig.builder()
                    .setAps(Aps.builder().setMutableContent(true).build())
                    .putCustomData("body_full", clampedBodyFull)
                    .putCustomData("type", routingType)
                    .putCustomData("target_type", routingTargetType)
                    .putCustomData("target_id", routingTargetId)
                    // The resolver's 5-tuple needs the actor for the `followed` → profile and
                    // chat → partner-identity paths (opaque routing id — never rendered by iOS).
                    .putCustomData("actor_user_id", routingActorUserId)
                    .build(),
            )
            .build()
    return IosPayloadResult.Built(message = message, bodyFull = clampedBodyFull)
}

/**
 * APNs hard limit on the assembled payload (notification + custom data) is
 * 4 KB. We target a conservative ceiling that leaves headroom for the
 * notification block + APNs envelope overhead.
 */
internal const val APNS_PAYLOAD_BUDGET_BYTES = 4096
internal const val APNS_NOTIFICATION_OVERHEAD_BYTES = 256

/**
 * Truncate [bodyFull] (JSON-stringified `body_data`) so the assembled APNs
 * payload stays under [APNS_PAYLOAD_BUDGET_BYTES]. Cuts on a UTF-8 codepoint
 * boundary (Kotlin `Char` unit + surrogate-pair guard) so multi-byte emoji
 * and CJK characters are never sliced mid-codepoint.
 *
 * [routingFieldBytes] is the UTF-8 byte size of the routing custom-data
 * VALUES (`type` + `target_type` + `target_id`) added by the
 * `mobile-push-message-handling` MODIFY — subtracted from the budget so the
 * clamp accounts for them (their key names ride inside the fixed
 * [APNS_NOTIFICATION_OVERHEAD_BYTES] envelope headroom).
 *
 * If [bodyFull] is below threshold, returns it unchanged. If the JSON has no
 * single field large enough to truncate down to fit (e.g., 20 small fields
 * totaling >4 KB with no single dominant field), returns `null` — the caller
 * treats this as a payload-too-large error and skips the FCM send entirely.
 */
internal fun clampBodyFullForApns(
    bodyFull: String,
    title: String,
    body: String,
    routingFieldBytes: Int = 0,
): String? {
    if (bodyFull.isEmpty()) return bodyFull
    val titleBytes = title.toByteArray(Charsets.UTF_8).size
    val bodyBytes = body.toByteArray(Charsets.UTF_8).size
    val budget =
        APNS_PAYLOAD_BUDGET_BYTES - APNS_NOTIFICATION_OVERHEAD_BYTES -
            titleBytes - bodyBytes - routingFieldBytes
    val rawBytes = bodyFull.toByteArray(Charsets.UTF_8).size
    if (rawBytes <= budget) return bodyFull

    // Try truncating the longest string-valued field first (typically
    // `post_excerpt` / `reply_excerpt`). Returns null on pathology — caller
    // drops the dispatch with `error_code="payload_too_large"`.
    return truncateLongestStringFieldUtf8Safe(bodyFull, budget)
}

/**
 * Best-effort truncation of the longest top-level string value in a JSON
 * object so the resulting JSON-stringified blob is at-or-under [byteBudget]
 * UTF-8 bytes. Preserves JSON structural validity. Cuts on a Kotlin `Char`
 * boundary AND guards against splitting a surrogate pair (4-byte UTF-8
 * codepoints like emoji are represented as a `Char` pair in Kotlin Strings).
 *
 * Returns `null` when no single field is large enough to absorb the overflow
 * — the caller treats that as the pathology case.
 */
private fun truncateLongestStringFieldUtf8Safe(
    original: String,
    byteBudget: Int,
): String? {
    val parsed = kotlinx.serialization.json.Json.parseToJsonElement(original)
    if (parsed !is kotlinx.serialization.json.JsonObject) return null

    val stringEntries =
        parsed.entries
            .mapNotNull { (k, v) ->
                val prim = v as? kotlinx.serialization.json.JsonPrimitive
                if (prim != null && prim.isString) k to prim.content else null
            }
            .sortedByDescending { it.second.toByteArray(Charsets.UTF_8).size }

    if (stringEntries.isEmpty()) return null
    val (longestKey, longestValue) = stringEntries.first()

    // Compute the surplus we need to shed.
    val originalBytes = original.toByteArray(Charsets.UTF_8).size
    val surplus = originalBytes - byteBudget
    val longestBytes = longestValue.toByteArray(Charsets.UTF_8).size
    if (surplus >= longestBytes) {
        // Even removing the longest value entirely wouldn't fit. Pathology.
        return null
    }

    // Binary-shrink the longest value on Char boundaries until UTF-8 size <=
    // (longestBytes - surplus). Then reassemble.
    val targetBytes = longestBytes - surplus
    val shrunk = takeUtf8(longestValue, targetBytes)

    val rebuiltMap = parsed.toMutableMap()
    rebuiltMap[longestKey] = kotlinx.serialization.json.JsonPrimitive(shrunk)
    val rebuilt = kotlinx.serialization.json.JsonObject(rebuiltMap).toString()
    if (rebuilt.toByteArray(Charsets.UTF_8).size > byteBudget) return null
    return rebuilt
}

/**
 * Take the longest prefix of [s] whose UTF-8 byte length is `<= targetBytes`,
 * cutting only on Kotlin `Char` boundaries AND never splitting a surrogate
 * pair. This guarantees the returned string is valid UTF-16 / UTF-8 and never
 * produces an orphan surrogate.
 */
private fun takeUtf8(
    s: String,
    targetBytes: Int,
): String {
    if (targetBytes <= 0) return ""
    var bytes = 0
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val ch = s[i]
        val codePoint: Int
        val charsConsumed: Int
        if (ch.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
            codePoint = Character.toCodePoint(ch, s[i + 1])
            charsConsumed = 2
        } else {
            codePoint = ch.code
            charsConsumed = 1
        }
        val cpBytes =
            when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
        if (bytes + cpBytes > targetBytes) break
        repeat(charsConsumed) { sb.append(s[i + it]) }
        bytes += cpBytes
        i += charsConsumed
    }
    return sb.toString()
}
