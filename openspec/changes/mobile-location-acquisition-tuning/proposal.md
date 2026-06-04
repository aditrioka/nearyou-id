## Why

On a cold-cache device the post composer and the Nearby first-load sit on a spinner **30–46s+** (root-caused live on-device during `mobile-post-creation-screen` apply §8.4). The shipped real providers acquire a fresh fix one-shot with no tuning: `AndroidLocationProvider.current()` ([`AndroidLocationProvider.kt:38`](../../../mobile/app/src/androidMain/kotlin/id/nearyou/app/location/AndroidLocationProvider.kt)) calls the 2-arg `getCurrentLocation(PRIORITY_BALANCED_POWER_ACCURACY, token)` with **no `CurrentLocationRequest`** — so no `maxUpdateAgeMillis` (a recent cached fix is rejected) and no `setDurationMillis` (the wait is **unbounded**; a genuine no-signal case hangs until the user backgrounds the app). The `lastLocation` fallback only fires when `getCurrentLocation` returns `null`, which it does not, so the cached fix is effectively never used. iOS ([`IosLocationProvider.kt`](../../../mobile/app/src/iosMain/kotlin/id/nearyou/app/location/IosLocationProvider.kt)) has the same one-shot `requestLocation()` pattern with no cached-fix check (so it still cold-waits — though `requestLocation()` self-bounds at ~10s internally, so the *unbounded*-hang is Android-specific). And each `current()` re-acquires independently — there is no shared warm fix between Nearby and the composer, so the composer cold-acquires again right after Nearby just did. For a location-first app this makes the core write/read loop *feel* broken even though it is functionally correct — a pre-launch quality concern. Promoted from `FOLLOW_UPS.md § mobile-location-acquisition-latency` (verified still-valid in the 2026-06-04 triage; the entry stays open until this ships).

## What Changes

Three behaviors, all within the existing `mobile-location` capability. The `LocationProvider` seam (`suspend fun current(): LatLng`) and its `LocationUnavailableException` error contract stay **unchanged**, so **no new `NearbyTimelineOutcome` / `PostCreationOutcome` members** are introduced and the two consuming repositories' status→outcome mapping is untouched.

- **Bounded wait** (headline correctness fix): Android passes a `CurrentLocationRequest` with `setDurationMillis(~12s)` so `getCurrentLocation` can no longer hang indefinitely; iOS wraps the one-shot acquisition in a `withTimeout(~12s)` as a **defensive ceiling** just above `requestLocation()`'s internal ~10s timeout (which normally fires first via `didFailWithError`). On timeout the provider throws the **existing** `LocationUnavailableException` → the existing retryable states (Nearby retryable error; composer `PostCreationOutcome.LocationUnavailable`). The unbounded hang is eliminated.
- **Stale-fix reuse**: Android adds `setMaxUpdateAgeMillis(~90s)` + `setGranularity(GRANULARITY_COARSE)` (keeping `PRIORITY_BALANCED_POWER_ACCURACY`) and tries an age-checked `lastLocation` first; iOS reuses `CLLocationManager.location` when its `timestamp` is within the staleness window before calling `requestLocation()`. A recent fix returns ~instantly instead of cold-acquiring. **No new dependency** — `play-services-location 21.3.0` is already pinned ([`docs/09-Versions.md:41`](../../../docs/09-Versions.md)) and `CurrentLocationRequest` ships in it.
- **Shared in-process warm-fix holder**: a new `commonMain` caching decorator implementing the existing `LocationProvider` seam wraps the platform provider and holds the last-good `LatLng` + its acquisition instant; `current()` returns the held fix when still within an in-process staleness window (~60s), else delegates and stores. The platform provider is re-bound in each `platformModule` behind a Koin qualifier; the unqualified `LocationProvider` consumers inject is bound (in `mobileModule`) to the decorator — so **both** `NearbyTimelineRepository` and `CreatePostRepository` share one warm fix. The staleness clock is an **injected seam** (not a wall-clock call inside the class), keeping the decision pure and `commonTest`-able.

**Invariants preserved** (asserted as negative scenarios): coarse-only (Android `ACCESS_COARSE_LOCATION` + `GRANULARITY_COARSE`, never `ACCESS_FINE_LOCATION`; iOS when-in-use + `kCLLocationAccuracyReduced`, never background/Always); the acquired coordinate is **never logged** (decorator + tuned actuals make no logging call carrying `lat`/`lng`); provider invoked only after permission is confirmed granted.

**Non-goals (deferred):**
- The composer "Mengambil lokasi…" acquiring sub-state — **not** specified in `docs/03-UX-Design.md`; adding it invents UX copy + a new string. Deferred to a follow-up (needs a docs/03 UX anchor first). With bounded-wait + warm reuse, cold acquisition is ≤~12s, so the progress sub-state is no longer urgent.
- Any Premium location-refresh-speed differentiation — `docs/02-Product.md` § Nearby differentiates Premium on **radius** (10/20/50/100 km), not refresh speed; a new product decision that must land in `docs/02` first.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `mobile-location`: the "Real device-location provider replaces the stub" requirement is extended (and sibling requirements added) to specify **bounded acquisition** (a granted-but-no-timely-fix surfaces `LocationUnavailableException` within a bounded duration — no unbounded wait), **stale-fix reuse** (a sufficiently-recent cached fix is returned without a cold acquisition), and the **shared in-process warm-fix holder** (a second consumer within the staleness window reuses the first consumer's fix without re-acquiring). The coarse-only / never-background / coordinate-never-logged invariants are unchanged and re-asserted.

## Impact

- **Specs:** `openspec/specs/mobile-location/spec.md` (MODIFIED + ADDED requirements).
- **Code (new, `commonMain`):** a caching `LocationProvider` decorator + an injected clock/`TimeSource` seam in `id.nearyou.app.timeline` (or `id.nearyou.app.location`).
- **Code (modified, `commonMain`):** `MobileModule.kt` Koin wiring (decorator binds unqualified `LocationProvider`; platform provider moves behind a qualifier).
- **Code (modified, platform actuals):** `AndroidLocationProvider.kt` (`CurrentLocationRequest` tuning), `IosLocationProvider.kt` (timeout + cached-fix reuse), and each `platformModule` (qualifier on the device-provider binding).
- **Dependencies:** none added — uses the already-pinned `play-services-location 21.3.0` `CurrentLocationRequest` API; iOS uses the existing `CLLocationManager` framework.
- **Tests:** `commonTest` for the decorator (warm reuse, staleness expiry, bounded-wait propagation) via a counting fake device source + injected clock; source-inspection scenarios for the platform-native tuning (CI can't run the OS APIs); iOS verified locally (CI is Linux-only).
- **Consumers unchanged:** `NearbyTimelineRepository` / `CreatePostRepository` keep their current `LocationProvider` injection and outcome mapping.
- **Follow-up:** closes `FOLLOW_UPS.md § mobile-location-acquisition-latency` once shipped.
