package id.nearyou.app.location

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Tunable location-acquisition windows (`mobile-location-acquisition-tuning` design D4).
 *
 * The three layers **nest** — `60s` in-process ⊂ `90s` system-cache ⊂ `12s` cold-acquire ceiling — and
 * all sit well under the coarse-fix usefulness horizon: a city-block "near me" coordinate does not move
 * meaningfully across 1–2 minutes of foreground use. The specs assert the *behaviour* (bounded /
 * reused / re-acquired), not these exact milliseconds, so a future tune against lived on-device latency
 * is a one-edit here without churning spec scenarios.
 *
 * `internal` so the commonMain decorator + Koin binding and the androidMain / iosMain platform actuals
 * share one source of truth. Values are [Duration]s converted at each use site: `inWholeMilliseconds`
 * for the Android `CurrentLocationRequest`, whole seconds for the iOS `NSDate` `timestamp` comparison,
 * and the [Duration] directly for the decorator window + the iOS `withTimeout`.
 */
internal object LocationTuning {
    /**
     * In-process warm-fix staleness window for [CachingLocationProvider]: a held fix younger than this
     * is reused across screens (the composer FAB right after the Nearby first-load) without even an IPC
     * to the platform provider. Exclusive bound — a fix whose age has *reached* the window is expired
     * and re-acquired.
     */
    val inProcessWarmStaleness: Duration = 60.seconds

    /**
     * Max acceptable age of a *system*-cached device fix returned without a cold acquisition — the
     * Android `CurrentLocationRequest.setMaxUpdateAgeMillis` + the age-gate on `lastLocation`, and the
     * iOS `CLLocationManager.location` `timestamp` reuse window. Wider than [inProcessWarmStaleness]
     * because it also covers the cold-process case where the in-process holder is empty.
     */
    val maxCachedFixAge: Duration = 90.seconds

    /**
     * Cold-acquisition ceiling — the Android `CurrentLocationRequest.setDurationMillis` (the request
     * **expires** instead of hanging unboundedly) and the iOS `withTimeout` sitting just above
     * `requestLocation()`'s internal ~10s self-timeout (the native `didFailWithError` path normally
     * fires first). On expiry the provider surfaces the existing [LocationUnavailableException].
     */
    val acquisitionTimeout: Duration = 12.seconds
}
