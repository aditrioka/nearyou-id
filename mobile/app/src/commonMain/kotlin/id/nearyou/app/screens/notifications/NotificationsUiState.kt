package id.nearyou.app.screens.notifications

import id.nearyou.app.notifications.NotificationDto
import id.nearyou.app.notifications.NotificationsOutcome
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The display-only projection of one notification — the PII-stripped model the rows render. It carries
 * ONLY what a row shows: the notification's own [id] (its UUID, NOT actor PII — kept as a stable
 * `LazyColumn` key + the mark-read target), the raw [type] wire string (the row composable maps it to
 * type-keyed `stringResource` copy), a [read] flag derived from `read_at`, and an optional non-PII
 * [excerpt] pulled from `body_data`. There is deliberately NO `actorUserId` and NO `targetId`, so the
 * rendered state STRUCTURALLY cannot leak either UUID (design D4).
 */
data class NotificationRow(
    val id: String,
    val type: String,
    val read: Boolean,
    val excerpt: String?,
)

/** The `body_data` keys that carry a human-readable excerpt (content, never PII). The first present
 *  string value is shown under the type copy; an absent/empty key yields no excerpt (base copy only). */
private val EXCERPT_KEYS = listOf("post_excerpt", "reply_excerpt", "preview")

private fun extractExcerpt(bodyData: JsonElement): String? {
    val obj = bodyData as? JsonObject ?: return null
    for (key in EXCERPT_KEYS) {
        val primitive = obj[key] as? JsonPrimitive ?: continue
        if (!primitive.isString) continue
        val content = primitive.content
        if (content.isNotBlank()) return content
    }
    return null
}

private fun NotificationDto.toRow(): NotificationRow =
    NotificationRow(
        id = id,
        type = type,
        read = readAt != null,
        // body_data excerpt only (post/reply text or chat preview) — NEVER actor_user_id / target_id.
        excerpt = extractExcerpt(bodyData),
    )

/**
 * Pure, Compose-free projection of the notifications screen UI state, mirroring `globalTimelineUiState`.
 * The four states are deterministic functions of the [NotificationsOutcome] + in-flight flag (no
 * wall-clock / platform dependency), and carry NO PII — [Content] rows are [NotificationRow] (actor /
 * target UUIDs already dropped). There are NO rate-limit states (the notifications read endpoint carries
 * no per-endpoint rate limit / `upsell`).
 */
sealed interface NotificationsUiState {
    /** Fetch in-flight (incl. the pre-first-load window) → skeleton + loading copy. */
    data object Loading : NotificationsUiState

    /** `Loaded`, non-empty items → the notification-row list. */
    data class Content(val rows: List<NotificationRow>) : NotificationsUiState

    /** `Loaded`, empty items → the "Belum ada notifikasi" empty copy. */
    data object Empty : NotificationsUiState

    /** `NetworkError` or retryable `Error` → network copy + a retry control. */
    data object Error : NotificationsUiState
}

/**
 * Maps the current [outcome] (null = not yet loaded) + [inFlight] to the screen state. Exhaustive over
 * [NotificationsOutcome] — no generic fallthrough (design D8):
 *
 * - in-flight (or not-yet-loaded) ⇒ [Loading].
 * - `Loaded` empty ⇒ [Empty]; `Loaded` non-empty ⇒ [Content].
 * - `NetworkError` / `Error` ⇒ [Error].
 */
fun notificationsUiState(
    outcome: NotificationsOutcome?,
    inFlight: Boolean,
): NotificationsUiState {
    if (inFlight) return NotificationsUiState.Loading
    return when (outcome) {
        null -> NotificationsUiState.Loading
        is NotificationsOutcome.Loaded -> {
            val rows = outcome.items.map { it.toRow() }
            if (rows.isEmpty()) NotificationsUiState.Empty else NotificationsUiState.Content(rows)
        }
        NotificationsOutcome.NetworkError, NotificationsOutcome.Error -> NotificationsUiState.Error
    }
}
