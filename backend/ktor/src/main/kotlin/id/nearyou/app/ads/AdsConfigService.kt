package id.nearyou.app.ads

import id.nearyou.app.config.RemoteConfig
import id.nearyou.app.subscription.PREMIUM_STATES

/**
 * The resolved, per-viewer ad-serving configuration the `GET /api/v1/config/ads` route returns.
 *
 * [adsEnabled] is the EFFECTIVE gate for THIS viewer (the global `ads_enabled` kill-switch AND the
 * premium suppression folded in), and [timelineFrequency] is the number of feed posts between native-ad
 * slots (docs/01 § Placement: every 5–7 posts; default [DEFAULT_TIMELINE_FREQUENCY]).
 */
data class AdsConfig(
    val adsEnabled: Boolean,
    val timelineFrequency: Int,
)

/**
 * Server-authoritative ads-config resolution for the `ads-config` capability (docs/11 §3.1 thin-route →
 * service layering). Folds two decisions into the viewer's effective config:
 *
 * 1. **Premium suppression** — a viewer with active premium ACCESS sees zero ads, so [resolve] returns
 *    `adsEnabled = false` regardless of the global flag. The test is the **access-control** formula
 *    `subscription_status ∈ {premium_active, premium_billing_retry}` ([PREMIUM_STATES]) — the same set
 *    `PostEditService` / `CreatePostService` use — NOT the stricter `premium_active`-only *badge* formula
 *    (`user-profile-read` / `FollowRoutes`). A `premium_billing_retry` user is in the 7-day grace window
 *    where Premium access REMAINS active (docs/08 Phase 4 item 4 BILLING_ISSUE), so they keep the ad-free
 *    benefit; the badge formula would wrongly show them ads (design D6).
 * 2. **Global kill-switch** — for a Free viewer, [adsEnabledGate] reads `ads_enabled` through the
 *    Remote-Config→Redis short-TTL seam ([AdsEnabledFlagGate]); default FALSE (BUILT-but-OFF).
 *
 * [timelineFrequency] is read from the `ads_timeline_frequency` Remote-Config int flag (server-tunable
 * without a release per design D4 / Open Questions), defaulting to [DEFAULT_TIMELINE_FREQUENCY] and
 * clamped into the docs/01 5–7 band so a malformed override can't push ads too dense or too sparse.
 *
 * No SQL and no DB pool: premium status comes from the verified JWT principal (passed by the route), the
 * flag from Redis/Remote-Config — this change adds no migration (spec § "adds no schema").
 */
class AdsConfigService(
    private val adsEnabledGate: AdsEnabledFlagGate,
    private val remoteConfig: RemoteConfig,
) {
    suspend fun resolve(subscriptionStatus: String): AdsConfig {
        if (subscriptionStatus in PREMIUM_STATES) {
            return AdsConfig(adsEnabled = false, timelineFrequency = timelineFrequency())
        }
        return AdsConfig(adsEnabled = adsEnabledGate.isEnabled(), timelineFrequency = timelineFrequency())
    }

    private fun timelineFrequency(): Int {
        val configured =
            try {
                remoteConfig.getLong(TIMELINE_FREQUENCY_FLAG_KEY)?.toInt()
            } catch (t: Throwable) {
                null
            }
        return (configured ?: DEFAULT_TIMELINE_FREQUENCY).coerceIn(MIN_TIMELINE_FREQUENCY, MAX_TIMELINE_FREQUENCY)
    }

    companion object {
        const val TIMELINE_FREQUENCY_FLAG_KEY: String = "ads_timeline_frequency"
        const val DEFAULT_TIMELINE_FREQUENCY: Int = 6
        const val MIN_TIMELINE_FREQUENCY: Int = 5
        const val MAX_TIMELINE_FREQUENCY: Int = 7
    }
}
