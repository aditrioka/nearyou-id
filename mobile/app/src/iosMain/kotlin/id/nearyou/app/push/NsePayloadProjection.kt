package id.nearyou.app.push

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The PURE body-rewrite projection the iOS Notification Service Extension applies
 * (`mobile-push-message-handling` tasks 6.2–6.4): given the server-built private alert body, the
 * `body_full` data field (= JSON-stringified `body_data`), and the App-Group content-privacy
 * preference, decide the delivered body + the OS-grouping thread identifier. Extracted here (no
 * `UserNotifications` / Firebase import) so it is unit-testable on `iosSimulatorArm64Test` without
 * a device; the NSE target source (`iosApp/NotificationService/NotificationService.swift`,
 * operator-added per #430) implements exactly this projection.
 *
 * Rules (design D3): ONLY when the preference is ON and the payload is a chat message
 * (`body_full` parses to an object carrying `conversation_id`) with a non-null string `preview`
 * does the body rewrite to the preview; otherwise the server-built private body stands (a
 * malformed `body_full`, a null preview, or preference OFF/unset all leave it untouched — the NSE
 * never invents copy). The preview is never logged.
 */
object NsePayloadProjection {
    fun projectedBody(
        serverBody: String,
        bodyFull: String?,
        previewEnabled: Boolean,
    ): String {
        if (!previewEnabled) return serverBody
        val obj = parse(bodyFull) ?: return serverBody
        if (conversationId(obj) == null) return serverBody
        // Blank-guarded like the Android render: an empty-string preview must not blank the body.
        val preview =
            (obj["preview"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.ifBlank { null }
        return preview ?: serverBody
    }

    /**
     * The OS-grouping key (task 6.3): `thread-id` = `conversation_id` so iOS visually groups a
     * conversation's notifications. Best-effort — unlike Android's durable count-merge, the NSE
     * has no persisted counter, so the merge is the OS's stacking only (declared limitation).
     */
    fun threadIdentifier(bodyFull: String?): String? = parse(bodyFull)?.let(::conversationId)

    private fun parse(bodyFull: String?): JsonObject? {
        if (bodyFull.isNullOrBlank()) return null
        return try {
            Json.parseToJsonElement(bodyFull) as? JsonObject
        } catch (_: Throwable) {
            null
        }
    }

    private fun conversationId(obj: JsonObject): String? =
        (obj["conversation_id"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.ifBlank { null }
}
