## Context

The Nearby read path shipped through `mobile-nearby-timeline-screen` (PR [#128](https://github.com/aditrioka/nearyou-id/pull/128)) and was made real-location by `mobile-location-permission-flow` (PR [#136](https://github.com/aditrioka/nearyou-id/pull/136)). The backend `POST /api/v1/posts` (`post-creation` capability) has been live since Phase 1: it validates content length (NFKC-normalize + trim, 1..280 code points), checks the Indonesia coordinate envelope, runs Layer-1/Layer-3 moderation, fuzzes the coordinate (HMAC), and INSERTs in one transaction. Nothing on mobile calls it.

This change is a **thin client over that shipped endpoint** — no backend, no migration, no new library. The dominant constraint is consistency: it must mirror the exact layered pattern the prior mobile screens established (`PostCreationApiClient → CreatePostRepository`/`CreatePostFlow` → pure `PostCreationUiState` → Voyager `PostCreationScreen`), so the codebase stays uniform and the screen is unit-testable without a device. The closest existing template is `AgeGateScreen` (a form screen with injected flow, in-flight/outcome state, a pure `UiState` projection, and outcome→`stringResource` error banners).

Key load-bearing facts established by reading the shipped code:
- `backend/.../post/PostRoutes.kt` — request `{ content, latitude, longitude }`; 201 body `{ id, content, latitude, longitude, distance_m, created_at }` where **`distance_m` and `created_at` are snake_case** (manual `buildJsonObject`), unlike the Nearby timeline's camelCase `distanceM`/`createdAt`.
- `backend/.../post/CreatePostService.kt` — 400 codes `content_empty`, `content_too_long`, `location_out_of_bounds`, `content_moderated_profanity`; `Verdict.Flag` still returns 201; **no `RateLimiter`** is injected (no post daily-quota exists).
- `mobile/.../network/HttpClientFactory.kt` — `LogLevel.HEADERS` + an `Authorization` `sanitizeHeader` + a `CoordinateMaskingLogger` that masks `lat`/`lng` **query params** only (bodies are not logged at HEADERS).
- `mobile/.../timeline/LocationProvider.kt` — `suspend fun current(): LatLng`, throws `LocationUnavailableException` when permission/fix is unavailable; bound per-platform in `platformModule`.

## Goals / Non-Goals

**Goals:**
- Ship the first content-creation screen, closing the create→read core loop, as one PR-sized change with zero backend churn.
- Mirror the established mobile layering so the screen, the outcome mapping, and the networking are independently unit-testable (Robolectric screen test + commonTest projection + MockEngine client/repository).
- Enforce PII discipline: the post-body coordinate is never logged; the echoed actual coordinate is never rendered.

**Non-Goals:**
- Manual map-pin location selection (device-location-only this change → `mobile-post-creation-manual-location`).
- Image attachment (Phase 4, `image_upload_enabled`-gated).
- Any post daily-quota / rate-limit UI (no backend post rate-limiter exists).
- Premium 30-minute edit window; auto-refreshing the Nearby feed on return; the multi-tab Home host.

## Decisions

### D1 — Device-location-only, permission-gated acquire-at-submit
The coordinate comes from the device (`LocationProvider.current()`) at submit time, not from a map the user manipulates — but the submit MUST first consult `LocationPermissionController.status()` and acquire the fix only when permission is granted.

**Reconciliation note (why the gate is mandatory).** The shipped `AndroidLocationProvider.current()` calls `FusedLocationProviderClient.getCurrentLocation(...)` with no permission guard and is documented as "*invoked only after permission is confirmed granted (the screen gates the fetch)*" — it handles only the granted-but-no-fix path (throwing `LocationUnavailableException`). Calling it **un-gated under a denied permission risks a synchronous `SecurityException` (crash), not a clean exception**. So a "blind acquire-at-submit that relies on `current()` throwing for no-permission" — which an earlier draft of this design assumed — is unsafe on Android. The composer therefore reuses the `LocationPermissionController` seam that `mobile-location-permission-flow` shipped (the same seam the Nearby `LocationGate` uses: `status()` is cheap and shows no prompt; `request()` fires the OS prompt; `openAppSettings()` deep-links to settings).

**Submit sequence** (implemented in `CreatePostRepository.submit()`, so it is fully unit-testable with a `FakeLocationPermissionController`): `controller.status()` → **GRANTED** ⇒ `current()` (now only the granted-but-no-fix case → `LocationUnavailableException` → `LocationUnavailable`) ⇒ `POST`; **NOT_DETERMINED** ⇒ `controller.request()` (the contextual OS prompt) ⇒ if granted proceed to `current()` + `POST`, else `LocationUnavailable`; **DENIED** ⇒ `LocationUnavailable` immediately (NO `current()`, NO `POST`, NO prompt — the OS shows nothing for a terminal denial). The `LocationUnavailable` UI carries an "enable location" message + a "Buka Pengaturan" CTA reusing `LocationPermissionController.openAppSettings()` + the existing `location_open_settings` string.

**Alternatives considered:** (a) a manual map-pin picker — rejected for this change (needs a map-rendering SDK = new substrate + library re-check gate; materially enlarges scope); deferred to `mobile-post-creation-manual-location`. (b) Gating the whole composer *entry* behind a permission wall (mirroring the Nearby `LocationGate` pre-fetch gate) before the form renders — rejected as heavier UX: the user can type without permission, so the gate lives in `submit()`, not at screen entry. (c) The earlier "blind acquire-at-submit" with no status check — rejected as Android-crash-unsafe per the reconciliation note above. By the time the user reaches the composer the Nearby feed has usually already resolved permission, so the common path is `status() == GRANTED`.

### D2 — Layered architecture mirroring the house pattern
`PostCreationApiClient` (HTTP, sealed `PostCreationApiResult`) → `CreatePostRepository` bound behind a `CreatePostFlow` interface (acquires the coordinate, maps to a sealed `PostCreationOutcome`) → pure `postCreationUiState(content, outcome, inFlight)` projection → `PostCreationScreen`. This matches `NearbyTimelineApiClient`/`NearbyTimelineRepository`/`NearbyTimelineFlow` and `AgeGateUiState`, so a `FakeCreatePostFlow` can drive the screen test and the projection is unit-tested in commonTest. The client MUST NOT reimplement Bearer attachment or 401 refresh (the shipped `Auth` plugin owns both); a terminal 401 flows through `SessionInvalidator` → `SignInScreen` exactly as for the other authenticated calls.

### D3 — Outcome mapping keys on HTTP status AND `error.code`
Unlike `NearbyTimelineRepository` (which keys purely on status because its 400s are all "shouldn't happen" diagnostics), the composer's 400s are user-actionable and each needs distinct copy. So `PostCreationApiResult.HttpError` carries the parsed `errorCode: String?` (read from the `{ "error": { "code" } }` envelope), and `CreatePostRepository` maps `content_empty → ContentEmpty`, `content_too_long → ContentTooLong`, `location_out_of_bounds → LocationOutOfBounds`, `content_moderated_profanity → ContentRejected`, any other/absent 400 code → a generic retryable `Error`. The mapping is exhaustive over `PostCreationOutcome` with no generic fallthrough (design parity with the timeline repo). `Verdict.Flag` is **not** an error path — it returns 201 and is indistinguishable from a clean success on the wire (correct: the post was created and silently queued).

### D4 — Minimal success DTO; record the snake_case trap
The composer only needs the success signal, so the 201 body parses into a minimal `CreatedPostDto(id: String)` relying on the shared `Json`'s `ignoreUnknownKeys`. This deliberately sidesteps the create-response casing trap: `distance_m`/`created_at` are snake_case here but camelCase (`distanceM`/`createdAt`) on the Nearby timeline wire — a future reader "harmonizing" them would break one or the other. The trap is documented here and in a code comment so the minimal DTO is understood as intentional, not lazy. The echoed `latitude`/`longitude` (the actual coordinate) are never read into a rendered field.

### D5 — Client length pre-check counts Unicode code points; NFKC stays server-side
The live `N/280` counter and the submit-enable gate count **Unicode code points** (so a 280-emoji string is valid and a 281-emoji string is not — UTF-16 unit counting would wrongly reject a 140-emoji string). The client does **not** attempt NFKC normalization: `java.text.Normalizer` is JVM-only and a cross-platform NFKC implementation is out of proportion for a counter. The server remains authoritative (it NFKC-normalizes + trims), so the client gate is a UX nicety that prevents the obvious doomed round-trip; the rare client/server divergence (a string the client counts ≤280 but the server's NFKC counts >280, or vice-versa) is handled defensively by mapping the server's `content_too_long`/`content_empty` to their banner states. **Alternative considered:** an `expect/actual` NFKC normalizer — rejected as scope-disproportionate for a character counter.

### D6 — FAB entry point at the HomeScreen level; `mobile-nearby-timeline` unmodified
A `FloatingActionButton` opens the composer via `LocalNavigator.currentOrThrow.push(PostCreationScreen())`. It is hosted by `HomeScreen` (which gains a `Scaffold { floatingActionButton = … }` wrapping the existing `NearbyTimelineScreen.Content()` body), so `NearbyTimelineScreen` — which is deliberately Voyager-free — is **not** touched and `mobile-nearby-timeline` needs no delta. **Trade-off:** this nests the Home `Scaffold` around the Nearby `Scaffold` (the inner one supplies the `TopAppBar`); acceptable for MVP and consolidated when the Nearby/Following/Global tab-host lands (the FAB is conceptually a home-level affordance across all three tabs, which is why it belongs above the Nearby screen, not inside it). **Alternative considered:** push an `onComposePost: () -> Unit` lambda into `NearbyTimelineScreen`'s existing `Scaffold` for a single flat Scaffold — cleaner Compose structure but it modifies the shipped `mobile-nearby-timeline` capability (a FAB-affordance requirement) and couples the composer entry to the Nearby screen specifically. Defaulted to the no-modification route; flagged in Open Questions for the review pass.

### D7 — Coordinate logging discipline: keep `LogLevel.HEADERS`, never widen
The device coordinate travels in the POST **request body**. At `LogLevel.HEADERS` the body is not logged, so the existing query-param `CoordinateMaskingLogger` (which only reaches the URL) is sufficient *as long as the level is not widened*. This change therefore MUST NOT change the log level to `BODY`/`ALL` and the new client MUST NOT `println`/log the coordinate or the serialized body. This is asserted as an explicit spec requirement (a source-inspection scenario), because widening logging is the realistic way this leak would be introduced.

### D8 — Success pops back; Nearby auto-refresh deferred
On `Success`, the screen calls `navigator.pop()` to return to Home. Showing the just-created post in the Nearby feed immediately would require a cross-screen reload signal (the feed re-fetches only on its own reload key / `ON_RESUME` gate refresh, not on a child-screen pop). That is deferred to a follow-up; for MVP the user pulls-to-refresh. A transient success confirmation (toast/snackbar) is optional (Open Questions) — the minimal contract is "pop on success".

### D9 — New strings are derived BI copy, flagged for UX review
`docs/03-UX-Design.md` has Report/Block/Search/etc. copy but **no post-composer section**. So every new string (title, placeholder, counter, "Posting", loading, the per-error messages, location-unavailable) is derived Bahasa Indonesia consistent with the Mobile #3/#4/#5 register — exactly like the timeline rate-limit strings were — and is flagged for UX review rather than claimed byte-identical to docs. The `content_moderated_profanity` message must be generic ("contains a disallowed word") and MUST NOT echo the matched keyword (the server already omits it from the body to avoid tipping off bypass attempts).

## Risks / Trade-offs

- **Nested `Scaffold` (D6) padding/FAB-overlap quirks** → keep the inner Nearby `Scaffold` as-is and let the outer Home `Scaffold` own only the FAB; verify on the Robolectric render + the manual device pass. If it misbehaves, fall back to the D6 alternative (single Scaffold via a nav lambda) — a localized change.
- **Client/server length divergence under NFKC (D5)** → server is authoritative; the client maps `content_too_long`/`content_empty` defensively, so the worst case is a corrected error banner, never a crash or a malformed post.
- **Permission denied / revoked between Nearby and the composer** → `submit()` checks `controller.status()` FIRST (DENIED ⇒ `LocationUnavailable` without calling `current()`), so the Android `getCurrentLocation` no-permission `SecurityException` path is never reached (D1 reconciliation note); granted-but-no-fix is then the only `current()` failure mode and also maps to `LocationUnavailable`.
- **`content_moderated_profanity` copy leaking signal** → the message is generic and keyword-free by spec (matches the server's body discipline).
- **Coordinate-in-body logging regression (D7)** → guarded by the never-widen-logging spec requirement + a source-inspection scenario.

## Migration Plan

Pure additive client change. No migration, no flag, no rollback choreography: the new screen + FAB + Koin bindings + strings ship together; reverting is removing them. Staging deploy is N/A (no backend artifact); the runtime check is the manual Android-device + iOS-sim pass (device location actuals are not unit-testable in commonTest).

## Open Questions

1. **Final BI copy** for the ~10 new composer strings (D9) — the proposed wordings are flagged for UX review; none are docs-canonical.
2. **Success confirmation UX** (D8) — silent `pop()` vs a brief success toast/snackbar before pop. Default: silent pop (minimal); a snackbar can be a trivial follow-up.
3. **FAB placement** (D6) — HomeScreen-level nested Scaffold (default, zero cross-spec churn) vs a nav-lambda single Scaffold that modifies `mobile-nearby-timeline`. Surfaced for the review pass to confirm the default.
