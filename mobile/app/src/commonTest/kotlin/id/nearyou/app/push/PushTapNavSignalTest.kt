package id.nearyou.app.push

import id.nearyou.app.screens.notifications.NotificationNavTarget
import id.nearyou.app.screens.routing.AppealRoute
import id.nearyou.app.screens.routing.ChatThreadRoute
import id.nearyou.app.screens.routing.ProfileRoute
import id.nearyou.app.screens.routing.toNavKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `mobile-push-message-handling` § "the nav signal is consumed exactly once (it does not re-fire
 * on recomposition / configuration change)" — the consumed-once + latest-tap-wins contract of
 * [PushTapNavSignal] (docs/11 §2.2 one-shot-signal pattern).
 */
class PushTapNavSignalTest {
    private fun routing(type: String) = PushTapRouting.fromWire(type, "post", "p-$type", "a-1", "{}")

    @Test
    fun offerThenConsume_clearsThePending_soItDoesNotReFire() {
        val signal = PushTapNavSignal()
        val tap = routing("post_liked")
        signal.offer(tap)
        assertEquals(tap, signal.pending.value)

        signal.consume(tap)

        assertNull(signal.pending.value, "consumed once — a re-collection sees null, never a re-fire")
    }

    @Test
    fun newerOffer_replacesAnUnconsumedOlderTap_latestWins() {
        val signal = PushTapNavSignal()
        signal.offer(routing("post_liked"))
        val newer = routing("chat_message")
        signal.offer(newer)

        assertEquals(newer, signal.pending.value)
    }

    @Test
    fun consumingAStaleTap_doesNotClearANewerOne() {
        // The effect resolves tap A over a suspended fetch; tap B arrives meanwhile. A's consume
        // must NOT clear B (latest-tap-wins, not latest-tap-lost).
        val signal = PushTapNavSignal()
        val older = routing("post_liked")
        signal.offer(older)
        val newer = routing("chat_message")
        signal.offer(newer)

        signal.consume(older)

        assertEquals(newer, signal.pending.value, "a stale consume must leave the newer tap pending")
        signal.consume(newer)
        assertNull(signal.pending.value)
    }

    // The route a resolved push-tap target pushes: a chat target keeps the partner's display identity AND the
    // Premium flag (the thread header badge, #286); the profile and appeal targets map to their shipped routes.
    @Test
    fun resolvedTargets_pushTheirRootStackRoutes_andAChatTargetKeepsThePremiumFlag() {
        assertEquals(
            ChatThreadRoute("conv-1", "budi", "Budi", partnerIsPremium = true),
            NotificationNavTarget.ChatThread("conv-1", "budi", "Budi", partnerIsPremium = true).toNavKey(),
        )
        assertEquals(ProfileRoute(userId = "u-1"), NotificationNavTarget.Profile("u-1").toNavKey())
        assertEquals(AppealRoute, NotificationNavTarget.Appeal.toNavKey())
    }

    @Test
    fun fromWire_normalizesEmptyStringsToNull() {
        val r = PushTapRouting.fromWire("followed", "", "", "actor-1", "")
        assertNull(r.targetType)
        assertNull(r.targetId)
        assertNull(r.bodyDataJson)
        assertEquals("actor-1", r.actorUserId)
    }
}
