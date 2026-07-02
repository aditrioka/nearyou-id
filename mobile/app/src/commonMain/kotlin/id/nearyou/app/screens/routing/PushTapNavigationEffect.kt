package id.nearyou.app.screens.routing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import id.nearyou.app.notifications.NotificationsFlow
import id.nearyou.app.push.PushTapNavSignal
import id.nearyou.app.push.PushTapRouting
import id.nearyou.app.screens.notifications.NotificationNavTarget
import id.nearyou.app.screens.notifications.NotificationNavTargetResolver
import id.nearyou.app.screens.notifications.resolveNotificationNavIntent
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.koin.compose.koinInject

/**
 * Consumes the push-tap nav signal (`mobile-push-message-handling` § "Tapping a push deep-links to
 * the in-app destination via the shared resolver"): observes [PushTapNavSignal.pending] for the
 * lifetime of the [backStack], resolves the routing fields through the SAME
 * [resolveNotificationNavIntent] + [NotificationNavTargetResolver] the in-app notifications list
 * uses (no second resolver / nav pattern), pushes the destination route, then consumes the signal
 * exactly once (no re-fire on recomposition / configuration change).
 *
 * Navigation waits until [HomeRoute] is on the stack (the authenticated shell): a cold start from a
 * tap parks the signal through RootRouter routing (and an unauthenticated session parks it until
 * sign-in) instead of pushing a detail route over the auth flow. No-destination taps (resolver
 * `None` / post `Unavailable`) consume silently — the tap just opens the app.
 */
@Composable
fun PushTapNavigationEffect(backStack: NavBackStack<NavKey>) {
    val signal = koinInject<PushTapNavSignal>()
    val notificationsFlow = koinInject<NotificationsFlow>()
    val resolver = remember(notificationsFlow) { NotificationNavTargetResolver(notificationsFlow) }
    LaunchedEffect(Unit) {
        combine(
            signal.pending,
            snapshotFlow { backStack.any { it is HomeRoute } },
        ) { pending, homePresent -> pending.takeIf { homePresent } }
            .filterNotNull()
            .collect { routing ->
                val intent =
                    resolveNotificationNavIntent(
                        targetType = routing.targetType,
                        targetId = routing.targetId,
                        actorUserId = routing.actorUserId,
                        bodyData = parseBodyData(routing),
                    )
                when (val res = resolver.resolve(intent)) {
                    is NotificationNavTargetResolver.Resolution.Target -> backStack.push(res.target)
                    // The tap only opens the app — no navigation, no blocking affordance.
                    NotificationNavTargetResolver.Resolution.PostUnavailable,
                    NotificationNavTargetResolver.Resolution.None,
                    -> Unit
                }
                // CAS-consume: a newer tap offered while resolve() was suspended stays pending
                // (the collector processes it next); only THIS routing is cleared.
                signal.consume(routing)
            }
    }
}

/** Defensive parse of the wire `body_data` — empty / non-JSON / non-object degrades to `{}` (→ no chat
 *  destination), mirroring the display handler's malformed-payload posture. */
private fun parseBodyData(routing: PushTapRouting): JsonElement {
    val raw = routing.bodyDataJson ?: return JsonObject(emptyMap())
    return try {
        Json.parseToJsonElement(raw)
    } catch (_: Throwable) {
        JsonObject(emptyMap())
    }
}

private fun NavBackStack<NavKey>.push(target: NotificationNavTarget) {
    when (target) {
        is NotificationNavTarget.Post ->
            add(
                PostDetailRoute(
                    postId = target.target.postId,
                    content = target.target.content,
                    cityName = target.target.cityName,
                    distanceM = target.target.distanceM,
                    createdAtIso = target.target.createdAtIso,
                    likedByViewer = target.target.likedByViewer,
                    replyCount = target.target.replyCount,
                    authorUsername = target.target.authorUsername,
                    authorDisplayName = target.target.authorDisplayName,
                    imageUrl = target.target.imageUrl,
                ),
            )
        is NotificationNavTarget.Profile -> add(ProfileRoute(userId = target.userId))
        is NotificationNavTarget.ChatThread ->
            add(
                ChatThreadRoute(
                    conversationId = target.conversationId,
                    partnerUsername = target.partnerUsername,
                    partnerDisplayName = target.partnerDisplayName,
                ),
            )
    }
}
