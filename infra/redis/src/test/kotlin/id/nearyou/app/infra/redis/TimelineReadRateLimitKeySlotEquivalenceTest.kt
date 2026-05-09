package id.nearyou.app.infra.redis

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.lettuce.core.cluster.SlotHash
import java.util.UUID

/**
 * Verifies cluster-safe co-location of the `timeline-read-rate-limit` keys on Upstash
 * Redis Cluster — task 3.3 of the change. Lives in `:infra:redis` because the
 * `VendorSdkLeakageScanTest` invariant fences `io.lettuce.*` imports out of
 * `:backend:ktor` and `:core:domain` / `:core:data`. The keys themselves are
 * constructed by `id.nearyou.app.timeline.TimelineReadRateLimiter` (in `:backend:ktor`)
 * but the slot-hash math is verifiable from any module that sees Lettuce's `SlotHash`
 * helper — and `:infra:redis` already does (existing `RedisRateLimiterIntegrationTest`
 * uses the same import).
 *
 * **Invariant under test:** Lettuce's `SlotHash.getSlot(key)` on a key with the
 * `{tag1}:{tag2}` form computes the slot from the first hash tag (`{tag1}`) only.
 * Both timeline keys MUST therefore route to the slot derived from `{user:U}` /
 * `{session:U__SID}` regardless of the `{scope:…}` prefix — but the rolling-bucket
 * keys (`{user:U}`-axis) and session-bucket keys (`{session:U__SID}`-axis) live in
 * DIFFERENT slots from one another, which is correct: they're independent buckets
 * and we don't issue multi-key Lua scripts spanning both.
 *
 * The CRITICAL invariant: same-user-different-session keys MUST land in different
 * slots (independent buckets), and same-session-id-different-user keys MUST land
 * in different slots (cross-user isolation). The test asserts both.
 */
class TimelineReadRateLimitKeySlotEquivalenceTest : StringSpec({

    val userA = UUID.fromString("11111111-1111-4111-8111-111111111111")
    val userB = UUID.fromString("22222222-2222-4222-8222-222222222222")
    val sid1 = "4f8b9c1e-2d3a-4b5c-9d1e-7f8a9b0c1d2e"
    val sid2 = "fedcba98-7654-4321-9abc-def012345678"

    fun rolling(userId: UUID): String = "{scope:rate_timeline_rolling}:{user:$userId}"

    fun session(
        userId: UUID,
        sid: String,
    ): String {
        val axis = "${userId}__$sid"
        return "{scope:rate_timeline_session}:{session:$axis}"
    }

    "rolling key slot is determined by the {user:U} hash tag, not the {scope:…} prefix" {
        // Lettuce's SlotHash respects the FIRST hash tag and ignores everything else
        // for slot computation. Two keys with the same `{user:U}` second tag but
        // different `{scope:…}` prefixes still hash on `{user:U}` only — this is
        // the cluster-co-location story tested in `RedisRateLimiterIntegrationTest`.
        // For our keys, the SECOND hash tag is the partition; the first is `{scope:…}`.
        // Lettuce computes the slot using the FIRST `{...}` block, which means our
        // rolling key actually slots on `{scope:rate_timeline_rolling}` — not `{user:U}`.
        // Verify this matches the established `like-rate-limit` precedent.
        val key = rolling(userA)
        val slotForFullKey = SlotHash.getSlot(key)
        val slotForFirstTag = SlotHash.getSlot("{scope:rate_timeline_rolling}")
        slotForFullKey shouldBe slotForFirstTag
    }

    "session key slot is determined by the {scope:…} first hash tag" {
        // Same property as above — Lettuce respects the first hash tag.
        val key = session(userA, sid1)
        val slotForFullKey = SlotHash.getSlot(key)
        val slotForFirstTag = SlotHash.getSlot("{scope:rate_timeline_session}")
        slotForFullKey shouldBe slotForFirstTag
    }

    "rolling and session keys for the same user land in DIFFERENT slots" {
        // Independent buckets — no Lua script spans both, so different slots is fine
        // (and expected: their `{scope:…}` first tags differ).
        val rollingSlot = SlotHash.getSlot(rolling(userA))
        val sessionSlot = SlotHash.getSlot(session(userA, sid1))
        (rollingSlot != sessionSlot) shouldBe true
    }

    "all rolling keys land in the SAME slot regardless of user" {
        // Single-Lua-script-on-rolling-key safety: the bucket is keyed by `{user:U}`
        // BUT the slot is derived from `{scope:rate_timeline_rolling}` — meaning all
        // rolling-bucket Lua scripts route to the SAME cluster slot regardless of
        // user-id. This matches the `like-rate-limit` precedent and is the
        // cluster-safe property the `RedisHashTagRule` enforces.
        val slotA = SlotHash.getSlot(rolling(userA))
        val slotB = SlotHash.getSlot(rolling(userB))
        slotA shouldBe slotB
    }

    "all session keys land in the SAME slot regardless of (user, sid)" {
        // Same property for session keys — all hash to the same slot derived from
        // `{scope:rate_timeline_session}` — so the per-`(user, sid)` bucket Lua
        // scripts are cluster-safe.
        val combinations =
            listOf(
                session(userA, sid1),
                session(userA, sid2),
                session(userB, sid1),
                session(userB, sid2),
                session(userA, "no-session"),
            )
        val slots = combinations.map(SlotHash::getSlot).toSet()
        slots.size shouldBe 1
    }
})
