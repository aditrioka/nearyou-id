## Context

The `mobile-location` capability (shipped via `mobile-location-permission-flow`, PR #136) supplies the production `LocationProvider` binding — `AndroidLocationProvider` (`FusedLocationProviderClient`, coarse) and `IosLocationProvider` (`CLLocationManager`, when-in-use, reduced accuracy) — consumed by `NearbyTimelineRepository.loadFirstPage()` and `CreatePostRepository.submit()`. The spec specifies accuracy (coarse-only), permission-gating (granted-first), and the no-coordinate-logging discipline, but is **silent on acquisition latency, staleness, and timeout**. That gap surfaced on-device: a cold-cache fix on the composer / Nearby first-load takes 30–46s+ and, with no `setDurationMillis`, can hang until backgrounded ([`AndroidLocationProvider.kt:38`](../../../mobile/app/src/androidMain/kotlin/id/nearyou/app/location/AndroidLocationProvider.kt) calls 2-arg `getCurrentLocation` with no `CurrentLocationRequest`). The seam is `suspend fun current(): LatLng` with `LocationUnavailableException` for the granted-but-no-fix path; this change tunes acquisition **behind that unchanged seam** so the two consuming repositories and their outcome mappings need no edits.

**Library-direction re-check (verified 2026-06-05, dated WebSearch):**
- Android: `CurrentLocationRequest.Builder.setMaxUpdateAgeMillis()` / `setDurationMillis()` / `setGranularity()` are the **current canonical** API per the [official Google Play services reference](https://developers.google.com/android/reference/com/google/android/gms/location/CurrentLocationRequest.Builder). `setDurationMillis` expires the request with no further locations (bounded wait); `setMaxUpdateAgeMillis` bounds acceptable cached-fix age (stale reuse). All present in the already-pinned `play-services-location 21.3.0` ([`docs/09-Versions.md:41`](../../../docs/09-Versions.md)) — **no new dependency, no pin bump**.
- iOS: `requestLocation()` **already self-bounds at ~10s internally** (runs `startUpdatingLocation` → pick-best → `stopUpdatingLocation`, erroring via `didFailWithError` after ~10s) per [Apple's docs](https://developer.apple.com/documentation/corelocation/cllocationmanager/1620548-requestlocation) + [fluffy.es](https://fluffy.es/current-location/). So the *unbounded* hang is Android-specific; iOS's value-add here is cached-fix reuse + an explicit defensive ceiling. Adopting `kotlinx.coroutines.withTimeout` over a wrapper library (e.g. SwiftLocation) keeps zero new dependencies.

## Goals / Non-Goals

**Goals:**
- Eliminate the unbounded-hang failure mode: a granted-but-slow acquisition surfaces `LocationUnavailableException` within a bounded duration, mapping to the **existing** retryable states (no new `NearbyTimelineOutcome` / `PostCreationOutcome` members).
- Return a recent fix ~instantly instead of cold-acquiring (system-cached fix on Android via `maxUpdateAgeMillis`; `CLLocationManager.location` on iOS).
- Share one warm fix across screens so the composer doesn't re-acquire right after Nearby did.
- Keep the staleness decision pure / `commonTest`-able (injected clock seam); keep coarse-only / never-background / coordinate-never-logged intact.

**Non-Goals:**
- Composer "Mengambil lokasi…" acquiring sub-state — not in `docs/03-UX-Design.md`; deferred (needs a UX anchor first).
- Premium location-refresh-speed differentiation — `docs/02-Product.md` § Nearby differentiates Premium on radius, not refresh; product decision for `docs/02` first.
- Background location / "Always" authorization — explicitly excluded by the UU-PDP coarse/when-in-use posture (`docs/06-Security-Privacy.md`).
- Changing the `LocationProvider` seam signature, the `LocationUnavailableException` contract, or either consumer repository's outcome mapping.

## Decisions

### D1 — A `commonMain` caching decorator, not tuning-only in the actuals
The cross-screen warm fix + staleness window lives in a new `commonMain` `CachingLocationProvider` (in `id.nearyou.app.location`, co-located with the platform actuals it wraps + `LocationUnavailableException`) that implements `LocationProvider`, wraps the platform provider, and holds `(LatLng, TimeMark)`. Concurrent access is single-flighted (D7). **Why over tuning-only-in-actuals:** the warm-reuse + staleness logic is platform-free and is the part most worth unit-testing; putting it in `commonMain` makes it `commonTest`-able with a fake device source (the native tuning can't run in `commonTest` anyway). The platform actuals keep only the native acquisition (now tuned). Alternative considered — duplicate a per-platform cache in each actual — rejected: duplicated logic, untestable in `commonTest`, and no shared cross-screen fix.

### D2 — Koin qualifier to avoid a double `LocationProvider` binding
Today each `platformModule` binds `LocationProvider` directly (the real provider), and consumers inject it ([`MobileModule.kt:56-72`](../../../mobile/app/src/commonMain/kotlin/id/nearyou/app/di/MobileModule.kt)). Introducing the decorator would create two `LocationProvider` bindings. Resolution: each `platformModule` binds the real provider with a **qualifier** (`named("deviceLocation")`); `mobileModule` (commonMain) binds the unqualified `LocationProvider` to `CachingLocationProvider(get(named("deviceLocation")), timeSource, stalenessWindow)`. Consumers (`NearbyTimelineRepository`, `CreatePostRepository`) inject the unqualified type unchanged and transparently get the decorator. `StubLocationProvider` stays the unqualified test double in `commonTest` (no decorator in tests unless a decorator test wraps a fake explicitly).

### D3 — Injected clock seam for testable staleness
`CachingLocationProvider` takes a `kotlin.time.TimeSource` (default `TimeSource.Monotonic`); it stamps each acquired fix with `timeSource.markNow()` and reuses the held fix when `mark.elapsedNow() < stalenessWindow`. Tests pass a `kotlin.time.TestTimeSource` to advance time deterministically — no wall-clock, no `Clock.System.now()`, no flakiness. **Why monotonic over `Clock.System`:** staleness is an elapsed-duration question; a monotonic source is immune to wall-clock jumps and is the idiomatic KMP-pure choice.

### D4 — Concrete tunables (nested layers), expressed as named constants
- **In-process warm staleness: 60s** (decorator) — "same session, just navigated screens"; avoids even an IPC to Fused for back-to-back reads (composer right after Nearby).
- **Android `maxUpdateAgeMillis`: 90s** — when the decorator *does* delegate (>60s, or cold process), Fused may return a system-cached fix up to 90s old instantly.
- **`durationMillis` / iOS `withTimeout`: 12s** — bounded wait; on iOS sits just above `requestLocation()`'s internal ~10s so the native `didFailWithError` path normally fires first.

The layers nest (60s in-process ⊂ 90s system-cache ⊂ 12s cold-acquire ceiling). All are well under the coarse-fix usefulness horizon — a coarse (~city-block) "near me" coordinate doesn't move meaningfully across 1–2 minutes of normal use. Values are named constants with this rationale in KDoc, so a future tune is one edit; spec scenarios assert the **behavior** (bounded / reused / re-acquired), not the exact milliseconds, to avoid brittle specs.

### D5 — Bounded wait reuses the existing failure contract
On Android `setDurationMillis` expiry, `getCurrentLocation` resolves null → the existing `lastLocation` fallback → `LocationUnavailableException` if still none. On iOS `withTimeout` expiry (or `didFailWithError`) → `LocationUnavailableException`. No new exception type, no new outcome member: Nearby maps it at its **screen collector** ([`NearbyTimelineScreen.kt:143`](../../../mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/NearbyTimelineScreen.kt) catches the thrown exception off the bare `loadFirstPage()` call) to its existing retryable error state, and the composer maps it in its **repository** ([`CreatePostRepository.kt:57`](../../../mobile/app/src/commonMain/kotlin/id/nearyou/app/post/CreatePostRepository.kt)) to `PostCreationOutcome.LocationUnavailable`. Both paths are already wired and unchanged.

### D6 — Decorator preserves the no-coordinate-logging invariant structurally **and** test-enforced
`CachingLocationProvider` makes no logging/diagnostic call; it holds the coordinate in a private field and returns it to the caller only. The no-logging scenario from the existing spec is re-asserted to cover the decorator, not just the actuals. Critically, the existing `PostCreationSourceGuardTest` scans a **hardcoded file list** and would NOT pick up the new file — so this change extends that comment-stripping source-scan guard (or adds a sibling `LocationSourceGuardTest`) to scan `CachingLocationProvider` **and** the tuned `AndroidLocationProvider` / `IosLocationProvider` for any `println` / `Log.` / `NSLog` / `Napier` / `LogLevel.BODY|ALL` and any coordinate-or-timestamp-bearing diagnostic call. This makes the strongest privacy claim in the change (a new in-memory coordinate holder never leaks) **test-enforced**, not merely review-enforced. (The iOS cached-fix-reuse branch must likewise not log `location.timestamp` — the same guard covers it.)

### D7 — Single-flight so concurrent cold-start consumers share one acquisition
The decorator guards its check-delegate-store critical section with a `kotlinx.coroutines.sync.Mutex`. Without it, the real concurrent trigger — the Nearby first-load and the composer FAB both calling `current()` on a *cold* holder before either stores — would double-acquire, defeating the "share one warm fix" guarantee D1 exists for and re-incurring the cold-acquire latency on the second caller (the exact bug this change fixes). With the `Mutex`, the second concurrent caller serializes behind the first, then observes the freshly-stored warm fix and returns instantly. The critical section is short (a staleness check + at most one delegated acquisition); genuinely-distinct post-staleness reads still proceed in order, which is correct. **Why `Mutex` over a shared in-flight `Deferred`:** both dedup concurrent acquisitions, but a `Deferred` adds cancellation/lifecycle handling; a `Mutex` is the simpler fit for a single-slot holder and is trivially `commonTest`-able (two `async` callers on a delegate that suspends → assert exactly one delegate invocation).

## Risks / Trade-offs

- **A 60–90s-old coarse fix is "slightly stale."** → Acceptable: the radius is coarse (Free 20 km; the fuzz/floor in `:shared:distance` already rounds), and a user rarely moves >1 city block in 60–90s of foreground use. Tunables are one-edit if field data disagrees.
- **`maxUpdateAgeMillis` could return a fix from *before* the app had permission.** → Not a privacy regression: the fix is only requested after permission is granted; a system-cached coarse fix is the same data class the OS already held and is still coarse/when-in-use.
- **iOS cached `CLLocationManager.location` may be `nil` or arbitrarily old on a fresh install.** → Guard with a `timestamp` staleness check; fall through to `requestLocation()` when absent/stale (no behavior change vs today in that case).
- **`TestTimeSource` is `@ExperimentalTime`-adjacent.** → Stable across the pinned Kotlin; opt-in annotation localized to the decorator + its test. Low risk; the API has been stable for years.
- **Decorator + Android `maxUpdateAge` overlap (double caching).** → Intentional, complementary layers (D4); the in-process holder avoids the IPC, the system cache covers cold-process. No correctness hazard — both return a coarse fix; the decorator is authoritative within 60s.

## Migration Plan

Pure additive refactor behind the unchanged seam; no schema, no API, no data migration. Rollback = revert the change (consumers and the seam are untouched). Ships in one PR through the standard lifecycle; iOS verified locally (CI is Linux-only for iOS) per the standing `mobile-ios-ci-link-task` follow-up.

## Open Questions

- Exact tunables (60s / 90s / 12s) are best-effort engineering picks; confirm against a real cold-cache device during apply-phase on-device verification and adjust the named constants if the lived latency disagrees. Not blocking — the behavior (bounded / reused) is correct at any sane value.
