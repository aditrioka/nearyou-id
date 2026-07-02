package id.nearyou.app.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The raw routing fields of a tapped push notification, exactly as delivered on the wire (Android
 * `PendingIntent` extras / iOS `userInfo`). Empty-string wire values are normalized to `null` via
 * [fromWire] so the shared resolver's null semantics apply. IDs are opaque route payload — never
 * rendered, never logged (`mobile-push-message-handling` § no-PII discipline).
 */
data class PushTapRouting(
    val type: String,
    val targetType: String?,
    val targetId: String?,
    val actorUserId: String?,
    val bodyDataJson: String?,
) {
    companion object {
        /** Maps the wire's empty-string-when-null convention back to nulls. */
        fun fromWire(
            type: String?,
            targetType: String?,
            targetId: String?,
            actorUserId: String?,
            bodyDataJson: String?,
        ): PushTapRouting =
            PushTapRouting(
                type = type.orEmpty(),
                targetType = targetType?.ifBlank { null },
                targetId = targetId?.ifBlank { null },
                actorUserId = actorUserId?.ifBlank { null },
                bodyDataJson = bodyDataJson?.ifBlank { null },
            )
    }
}

/**
 * The consumed-once nav-signal entry point the push tap feeds (docs/11 §2.2 — a nullable
 * `StateFlow` field + an explicit [consume], the `EditPostUiState` one-shot pattern; NO
 * Channel/SharedFlow event bus). The platform tap handlers ([offer] from `MainActivity` intent
 * extras on Android / the `UNUserNotificationCenterDelegate` on iOS) write it; the app-root
 * `PushTapNavigationEffect` resolves the destination via the shared resolver, navigates once, then
 * [consume]s — so the signal never re-fires on recomposition or configuration change. A newer tap
 * simply replaces an unconsumed older one (latest-tap-wins).
 */
class PushTapNavSignal {
    private val _pending = MutableStateFlow<PushTapRouting?>(null)
    val pending: StateFlow<PushTapRouting?> = _pending.asStateFlow()

    fun offer(routing: PushTapRouting) {
        _pending.value = routing
    }

    fun consume() {
        _pending.value = null
    }
}
