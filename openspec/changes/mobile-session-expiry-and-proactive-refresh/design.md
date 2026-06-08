## Context

The mobile app's auth lifecycle is **reactive-only** today. The Ktor `Auth { bearer }` plugin attaches the access token and, on a 401, calls `refreshTokens { POST /api/v1/auth/refresh }`; if that refresh fails it returns `null`, the plugin surfaces a terminal 401 to the caller, and `SessionInvalidator.invalidate()` clears the store and signals `SessionExpiryEffect` to `replaceAll(SignInRoute)`. Backend support is already best-practice (15-min access TTL, 30-day rotating refresh, reuse detection) — see `docs/05-Implementation.md` §§ Session management. Three gaps were confirmed on a live 2026-06-08 on-device debug:

1. **Mislabeling.** `NearbyTimelineRepository` / `GlobalTimelineRepository` map a terminal `HttpError(401)` through `else -> NetworkError`, so the screen shows `signin_error_network` ("Tidak bisa terhubung. Periksa koneksi internet kamu.") — a connectivity message for an auth fault, complete with a useless "Coba lagi" retry.
2. **Droppable re-route.** `SessionInvalidator` emits over `MutableSharedFlow(replay=0, extraBufferCapacity=1)`; an `invalidate()` that fires before `SessionExpiryEffect` subscribes is lost (replay=0), stranding the user on the mislabeled error until a manual retry re-emits it.
3. **No preemptive refresh.** `TokenPair.accessExpiresAtEpochMillis` is stored but never read, so every cold start past the 15-min TTL eats a visible 401→refresh round-trip — contradicting `docs/05-Implementation.md` line 38 ("Preemptive refresh: on app wake / cold start, if either JWT expires in <5 min, refresh async non-blocking … client uses single-flight").

This change is mobile-only (no backend/schema change) and is a cross-cutting prerequisite for the remaining demo screens, all of which are authenticated.

## Goals / Non-Goals

**Goals:**
- A terminal 401 (one that survived the bearer refresh) never renders as a connectivity error; it routes to re-auth.
- The terminal-401 → `SignInScreen` re-route cannot be dropped, regardless of subscriber timing.
- `SignInScreen` shows a clear session-expired notice on involuntary logout (CMP Resources string).
- Proactive single-flight refresh on app resume when the access token is within 5 min of expiry, async/non-blocking, preserving the existing "exactly ONE refresh" guarantee.
- After an involuntary re-auth, the user returns to the screen they were on.
- `NearbyTimelineRepository.diagnosticLog` is wired to a real, coordinate-free sink.

**Non-Goals:**
- The **Supabase realtime token** branch of the 401 ladder (`docs/05-Implementation.md` line 36, path 2) — that is chat (`mobile-chat-screen`, unbuilt).
- **Device attestation** on re-auth — a separate pending wiring item (`project.md` line 107); re-auth here = the existing "Masuk dengan Google" flow.
- **User-initiated logout** UI (Settings "Keluar") — stays with `mobile-settings-screen` (FOLLOW_UPS `mobile-logout-ui-deferred`); only the shared `SessionInvalidator` seam is noted here.
- Any backend, schema, migration, or `gradle/libs.versions.toml` change.

## Decisions

### D1 — Re-route signal: a buffered, consume-once `Channel`, not a replay SharedFlow
Replace `SessionInvalidator`'s `MutableSharedFlow(replay=0, extraBufferCapacity=1)` with a `Channel<Unit>(Channel.CONFLATED)` exposed via `receiveAsFlow()`. An `invalidate()` that fires before `SessionExpiryEffect` collects is **buffered** and delivered to the first collector (signal cannot be dropped); receiving **consumes** it, so a freshly-signed-in user's later-mounted `SessionExpiryEffect` does not replay a stale invalidate.
- **Alternatives:** (a) `MutableSharedFlow(replay=1)` — rejected: a new subscriber after re-login immediately replays the previous invalidate → spurious re-route loop. (b) `StateFlow<Boolean>` latch with manual reset — rejected: reset coordination across re-login is fiddlier than consume-once. (c) keep `replay=0` and just start the collector earlier — rejected: still racy; can't prove "before any 401 can fire."

### D2 — One single-flight `TokenRefresher` shared by reactive + proactive paths
Extract the refresh round-trip (`POST /api/v1/auth/refresh` → write new `TokenPair` → `invalidate()` on failure) into a `TokenRefresher` guarded by a `Mutex` (single-flight: a concurrent caller awaits the in-flight refresh and reuses its result). The Ktor `refreshTokens { }` callback delegates to it; the proactive trigger (D3) calls the same instance. This keeps "exactly ONE refresh" true **across** reactive and proactive paths — the mobile-auth-signin "Concurrent 401s … retry once" scenario still holds, and a proactive refresh can't race a reactive one.
- **Alternatives:** rely on Ktor's internal refresh queue only — rejected: it dedups reactive 401s but is blind to a proactive refresh fired outside a request, so the two could overlap and double-rotate the refresh token (the 30-s server overlap window would absorb it, but client single-flight is the spec'd contract).
- **Rotation note:** refresh rotates the refresh token; the in-flight old token stays valid for the backend's 30-s overlap window (`docs/05-Implementation.md` line 38), so a request already on the wire with the pre-refresh access token is unaffected.

### D3 — Unify "wake/cold-start" and "foreground" on a single `ON_RESUME` + <5-min gate
Both the cold-start-wake (item #4) and foreground-return (item #5) triggers collapse into **one** rule: on the app-root `Lifecycle.Event.ON_RESUME`, if `accessExpiresAtEpochMillis - now < 5 min`, call `TokenRefresher.refresh()` async (fire-and-forget on an app-scope coroutine; never blocks composition or the first frame). The `<5-min` window **is** the idle gate — a 3-second app-switch leaves >5 min on the token and does nothing; a resume after the token lapsed refreshes once. Implemented in **commonMain** via `lifecycle-runtime-compose`'s `LifecycleResumeEffect` at the app root (the CMP common `LifecycleOwner` is wired to the iOS UIViewController lifetime and the Android process/activity lifecycle — verified 2026-06-08: `org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose` already pinned at 2.10.0 and consumed by `:mobile:app`, no new dependency, no expect/actual).
- **Alternatives:** (a) a separate background timer / `WorkManager` — rejected: over-engineered for a foreground-only need, and pulls a platform dependency. (b) `androidx.lifecycle:lifecycle-process` `ProcessLifecycleOwner` — rejected: Android-only, would need an iOS actual, when `lifecycle-runtime-compose` already gives a common hook. (c) a distinct idle-threshold timer separate from the expiry check — rejected: redundant; the <5-min window already encodes "worth refreshing."
- **Terminal proactive failure:** if the proactive refresh itself fails (refresh token dead — e.g. resume after >30 days), `TokenRefresher` calls `invalidate()` → D1 re-route. So the >30-day idle re-auth trigger (`docs/05-Implementation.md` line 126) is covered by the same path.

### D4 — Timeline terminal-401 → a dedicated `SessionExpired` outcome, not `NetworkError`
Add `NearbyTimelineOutcome.SessionExpired` (and `GlobalTimelineOutcome.SessionExpired`). The repositories map `HttpError(401)` → `SessionExpired`; a transport failure (`NetworkError(cause)` from a caught `IOException`/timeout) keeps mapping to the existing `NetworkError` outcome (connectivity copy retained). The screen renders `SessionExpired` as a **neutral redirect placeholder** (a short "Mengalihkan ke halaman masuk…" line via a CMP string, **no** retry button, **no** connectivity copy) — a brief, honest state for the sub-second before D1's now-reliable re-route whisks the user to `SignInScreen`.
- **Alternatives:** (a) reuse the existing `Loading`/skeleton for terminal 401 — rejected: a spinner that could persist if the re-route ever lags reads as a hang; an explicit state is testable. (b) map 401 → the existing generic `Error` — rejected: `Error` shows a retry, which would re-hit the dead session.
- **Why both layers:** D1 owns navigation; D4 owns "don't show a wrong message in the gap." They compose — D4 is correct even if D1's re-route is instant.

### D5 — Session-expired copy + destination preservation
- **Copy:** new CMP Resources string `signin_session_expired` = "Sesi kamu berakhir. Masuk lagi untuk lanjut." `SignInScreen` renders it when entered via an involuntary re-route (distinct from a fresh launch, which shows no notice). Drive it off a flag carried by the re-route (e.g. `SignInRoute(reason = SessionExpired)` NavKey arg, or a one-shot in the holder of D5-destination) — **not** a hardcoded string, and **not** `signin_error_network`.
- **Destination preservation:** introduce an in-memory `PendingReturnDestination` holder (a Koin `single`, mirroring the existing `PendingSignupIdentity` pattern — never persisted, never on a NavKey). On `invalidate()`, capture the current top route **before** `replaceAll(SignInRoute)`; on sign-in success, if a pending destination exists, navigate there instead of `HomeRoute`, then clear it. A restored destination that needs fresh data (e.g. a post-detail) simply re-fetches on mount — acceptable.
- **Edge:** if the captured destination is itself an auth/sign-in route, fall back to `HomeRoute` (don't restore into the auth flow).

### D6 — `diagnosticLog` wiring
Wire `NearbyTimelineRepository`'s `diagnosticLog` in `MobileModule` to the same coordinate-free sink other repositories use (currently the no-op default). It carries no coordinate/token (signature is `(String) -> Unit` with pre-redacted messages) — the existing PII discipline and the `CoordinateMaskingLogger` on the HTTP path are untouched.

## Risks / Trade-offs

- **[Proactive refresh fires on every ON_RESUME]** → Mitigated by the `<5-min` gate (D3): the network call only happens when actually near expiry; otherwise the effect is a cheap timestamp compare.
- **[Double single-flight (Ktor queue + TokenRefresher Mutex)]** → Mitigated by D2 funnelling the actual refresh round-trip through the one `Mutex`; Ktor still queues reactive retries, but only one network refresh occurs.
- **[`SessionExpired` placeholder briefly visible]** → Acceptable and honest; with D1 reliable, the window is sub-second. The placeholder has no retry, so it can't loop on a dead session.
- **[Destination preservation adds surface]** → This is the heaviest sub-item; it reuses the established in-memory-holder pattern to stay bounded. Captured as a first-class requirement (operator chose the full flow) with a negative guard so the fallback-to-Home behavior is itself tested. See Open Questions if a v1 split is preferred.
- **[iOS CMP lifecycle parity]** → `ON_RESUME` on iOS depends on the CMP view-controller lifetime hooks; verified present in the project's CMP version. The iOS path is exercised by an `iosTest` (per the project's iOS-actual verification rule) rather than assumed.

## Migration Plan

Pure additive mobile behavior; no migration, no flag, no rollback coordination. Ships in the normal mobile gate (`testDevDebugUnitTest` + `testDevReleaseUnitTest` + root `detekt`). Manual verification: staging-flavor on-device with a deliberately-expired/aged token (re-mint a short-TTL staging JWT, background past expiry, resume) to confirm proactive refresh + the session-expired re-route copy.

## Open Questions

- **Destination preservation in v1 vs. split?** Kept IN scope per the operator's "full ideal flow" choice and designed via `PendingReturnDestination`. If Phase D review or the operator judges it disproportionate to the bug-fix core, it can split to a follow-up — but only as an **explicit** deferral with a spec'd Home-fallback guard, never a silent drop. Surface at handoff.
- **`SessionExpired` placeholder string** — confirm Indonesian copy ("Mengalihkan ke halaman masuk…") with the operator during apply; trivial to adjust.
