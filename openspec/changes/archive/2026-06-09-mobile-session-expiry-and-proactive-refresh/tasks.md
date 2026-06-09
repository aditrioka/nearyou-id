## 1. Resources & strings (foundational)

- [x] 1.1 Add CMP Resources string `signin_session_expired` ("Sesi kamu berakhir. Masuk lagi untuk lanjut.") to `shared/resources/src/commonMain/composeResources/values/strings.xml` (+ any localized variants present).
- [x] 1.2 Add CMP Resources string `timeline_session_redirect` ("Mengalihkan ke halaman masuk…") for the neutral terminal-401 placeholder shared by Nearby + Global.
- [x] 1.3 Confirm no hardcoded UI text is introduced anywhere in this change (mobile-strings invariant) — all new copy via `stringResource`.

## 2. Reliable re-route signal (D1)

- [x] 2.1 Replace `SessionInvalidator`'s `MutableSharedFlow<Unit>(replay=0, extraBufferCapacity=1)` with a buffered consume-once carrier (`Channel<Unit>(Channel.CONFLATED)` exposed via `receiveAsFlow()`); `invalidate()` still clears the store first, then offers the signal.
- [x] 2.2 Confirm `SessionExpiryEffect` collects the new carrier unchanged (still `replaceAll(SignInRoute)`), and that an emit-before-subscribe is delivered to the first collector.
- [x] 2.3 Verify a re-subscription after re-login does NOT replay a stale invalidation (consume-once semantics).

## 3. Single-flight TokenRefresher (D2)

- [x] 3.1 Extract the refresh round-trip (`POST /api/v1/auth/refresh` → write new `TokenPair` → `SessionInvalidator.invalidate()` on failure) into a `TokenRefresher` guarded by a `Mutex` (a concurrent caller awaits the in-flight refresh and reuses its result).
- [x] 3.2 Rewire `HttpClientFactory`'s `Auth { bearer { refreshTokens { … } } }` to delegate to `TokenRefresher` (do NOT reimplement 401-retry; the Ktor plugin's request-queue stays). Keep `markAsRefreshTokenRequest()`, `LogLevel.HEADERS`, the `Authorization` `sanitizeHeader`, and the `CoordinateMaskingLogger` intact.
- [x] 3.3 Register `TokenRefresher` as a Koin `single` in `MobileModule`; ensure exactly one instance is shared by the HTTP client and the proactive trigger.

## 4. Proactive preemptive refresh on resume (D3)

- [x] 4.1 Add an app-root `LifecycleResumeEffect` / `LifecycleEventEffect(Lifecycle.Event.ON_RESUME)` (commonMain, via `lifecycle-runtime-compose`) hosted where `SessionExpiryEffect` lives (`AppEntryProvider` / app root) — no expect/actual, no new dependency.
- [x] 4.2 On `ON_RESUME`: read `TokenPair.accessExpiresAtEpochMillis`; if a token pair exists AND it expires within 5 min (or already expired), launch `TokenRefresher.refresh()` async, non-blocking (never blocks composition/first frame). Launch on a **lifecycle-bound** scope (app-root `rememberCoroutineScope()` or a Koin-`single` `CoroutineScope(SupervisorJob() + Dispatchers.Main)`) — **NOT `GlobalScope`**. Compare expiry using the existing injectable `nowMillis: () -> Long` seam (precedent: `AuthApiClient.kt:93`), NOT `TimeSource.Monotonic` (monotonic time can't compare against an absolute epoch). Define the 5-min window as a named constant.
- [x] 4.3 Ensure a proactive refresh whose refresh token is rejected funnels through `SessionInvalidator.invalidate()` → the reliable re-route (no separate error surface).

## 5. Timeline terminal-401 → SessionExpired (D4)

- [x] 5.1 Add `SessionExpired` to the sealed `NearbyTimelineOutcome` and `GlobalTimelineOutcome`.
- [x] 5.2 In `NearbyTimelineRepository`: add an explicit `HttpError(401)` → `SessionExpired` branch **ahead of** the existing fallback (keep 400 → `Error`, 5xx → `NetworkError`, IO → `NetworkError`, and a DEFINED fallback for any other unenumerated non-2xx → `NetworkError`). Do NOT delete the `when` fallback — the match is over an `Int`, so removing it is a non-exhaustive compile error; the "no generic fallthrough" rule bans a generic "load failed" *copy*, not a defined branch. Same edit in `GlobalTimelineRepository`.
- [x] 5.3 Extend the UI-state projections (`nearbyTimelineUiState` / `globalTimelineUiState`) to map `SessionExpired` → a new neutral redirect state (exhaustive `when`, no `else`).
- [x] 5.4 Render the redirect state in `NearbyTimelineScreen` / `GlobalTimelineScreen` as `stringResource(Res.string.timeline_session_redirect)` with NO retry control; ensure `NetworkError` still renders `signin_error_network` + `cta_retry` unchanged.

## 6. SignInScreen session-expired notice (D5)

- [x] 6.1 Carry an "involuntary entry" reason to `SignInScreen` without persistence — a `SignInRoute(reason = SessionExpired)` NavKey arg (or read the re-auth holder). Fresh-launch entry carries no reason.
- [x] 6.2 Render `stringResource(Res.string.signin_session_expired)` only on involuntary entry; never on a fresh launch; never `signin_error_network`.

## 7. Destination preservation (D5)

- [x] 7.1 Add an in-memory `PendingReturnDestination` Koin `single` (mirror `PendingSignupIdentity` — never persisted, never on a NavKey).
- [x] 7.2 On `invalidate()` / before `replaceAll(SignInRoute)`, capture the current top destination into the holder; skip capture (or fall back to Home) if it is an auth/sign-in route.
- [x] 7.3 On sign-in success, if a pending destination exists navigate there (else `HomeRoute`), then clear the holder.

## 8. Diagnostic sink wiring (D6)

- [x] 8.1 Wire `NearbyTimelineRepository`'s `diagnosticLog` in `MobileModule` to the real coordinate-free sink other repositories use (replace the no-op default); confirm no coordinate/token reaches it. (Check `GlobalTimelineRepository`'s sink too and wire if it is also defaulting.)

## 9. Tests (commonTest unless noted)

- [x] 9.1 `SessionInvalidator`: emit-before-subscribe is delivered to the first collector; consume-once (no stale replay after a second subscribe). (regression for the dropped-signal bug)
- [x] 9.2 `TokenRefresher`: single-flight — launch a proactive `refresh()` *into* the in-flight window of a reactive 401 refresh (reuse the proven `AuthApiClientTest` "concurrent 401s queue behind a single refresh" counter pattern over a `delay`-widened MockEngine) and assert exactly ONE `POST /api/v1/auth/refresh` (the second caller awaits-and-reuses, does not re-POST). Assert BOTH `invalidate()` call sites funnel through `TokenRefresher` (null-refresh-token AND non-success-refresh). Preserve the existing mobile-auth-signin "Concurrent 401s … retry once" scenario.
- [x] 9.3 Proactive refresh: `<5 min` to expiry on `ON_RESUME` → one non-blocking refresh; `>5 min` → no refresh; rejected refresh token → `invalidate()` + reliable re-route. Drive the expiry window with the injected `nowMillis: () -> Long` seam (precedent `AuthApiClient.kt:93`) + a call-counting MockEngine.
- [x] 9.4 `NearbyTimelineRepository` + `GlobalTimelineRepository`: terminal 401 (401 to fetch AND 401 to refresh) → `SessionExpired`, NOT `NetworkError`/`Error`; transport `IOException` → still `NetworkError` (connectivity copy retained). Update the "every fetch result maps to exactly one outcome" assertions.
- [x] 9.5 Projection tests: `SessionExpired` → redirect state; existing outcome→state mappings unchanged.
- [x] 9.6 `NearbyTimelineScreen` / `GlobalTimelineScreen` (Robolectric, `androidUnitTest/`): `SessionExpired` renders the redirect notice with no retry and not `signin_error_network`; `NetworkError` still renders the connectivity copy + retry. These extend the EXISTING `NearbyTimelineScreenTest`/`GlobalTimelineScreenTest` (already in the Release-variant exclude, `build.gradle.kts:237-252`) — only a brand-NEW `*ScreenTest` class needs a new exclude entry; verify with `:mobile:app:testDevReleaseUnitTest`. Add the same `SessionExpired`-render assertions to the iOS flow tests (`NearbyTimelineFlowIosTest`, and the Global equivalent) for platform parity.
- [x] 9.7 `SignInScreen` (Robolectric, `androidUnitTest/`): involuntary entry renders `signin_session_expired` (not `signin_error_network`); fresh launch renders neither; source scan finds no hardcoded session-expired literal (strip comments first). `SignInScreenTest` is ALREADY in the Release-exclude, so no new exclude entry is needed; add the parity assertion to `SignInFlowIosTest`.
- [x] 9.8 Destination preservation: non-auth destination restored after re-auth; captured auth route falls back to Home; holder is in-memory only.
- [x] 9.9 iOS actual: an `iosTest` exercising the `ON_RESUME` proactive-refresh hook (CMP common Lifecycle on iOS) — `:mobile:app:iosSimulatorArm64Test`, kotlin.test `@Test` (not Kotest), K/N-legal test-fn names. **Parity risk (design § "iOS CMP lifecycle parity"):** existing iOS tests drive Compose screens via `runComposeUiTest`; none yet pumps a raw `LifecycleResumeEffect`. First PROVE the harness emits `ON_RESUME` on the iOS sim (host the app-root effect in a test composable, assert a fake `TokenRefresher` counter increments); if `runComposeUiTest` cannot pump iOS lifecycle, drive the `LifecycleOwner`/`LifecycleRegistry` directly. Do NOT degrade this to assertion-by-inspection (per CLAUDE.md § Engineering judgment).
- [x] 9.10 Diagnostic-sink wiring (covers the `MobileModule wires a non-no-op diagnostic sink` spec scenario): assert the `MobileModule` Koin graph resolves `NearbyTimelineRepository` (and `GlobalTimelineRepository`) with a non-no-op `diagnosticLog` (DI-graph/sink-capture spy, NOT just a repository-level fake), plus a comments-stripped source scan confirming the sink call sites pass no coordinate/token. Mirrors the existing `*NoFetchScanTest` source-scan precedent.

## 10. Verify & gate

- [x] 10.1 `openspec validate mobile-session-expiry-and-proactive-refresh --strict` passes.
- [x] 10.2 Mobile gate green: `./gradlew :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest` + root `./gradlew detekt ktlintCheck` (worktree needs a copied `local.properties`).
- [x] 10.3 Manual on-device (staging flavor, Galaxy A17 / SM-A176B, 2026-06-09): verified via the staging test-login rig (`dev/scripts/mint-staging-jwt.sh`; `refresh`=access JWT is a bogus refresh token → deterministic terminal 401, no DB write). **Scenario A (proactive refresh isolated):** valid `token_version=0` + client `exp=now+90s` → cold-start `ON_RESUME` fires a proactive `POST /auth/refresh` as the FIRST request (logcat, before any 401) → bogus refresh → 401 → re-route to `SignInScreen` with "Sesi kamu berakhir…" (unread-badge fetch 200'd → re-route driven by the proactive refresh alone). **Scenario B (fetch terminal-401, the original mislabel bug):** `token_version=99` + `exp=now+900s` (>5min → proactive stays out) → timeline fetch 401 → ONE reactive `/auth/refresh` (single-flight coalesced the concurrent unread-badge+timeline 401s) → 401 → terminal → `SignInScreen` notice, **never** "Tidak bisa terhubung. Periksa koneksi internet kamu." Fresh-launch baseline showed NO notice; coordinate masked (`lat=***&lng=***`) in logs. **Test-login limits (covered by green automated suite, not reproducible on-device):** successful proactive refresh / "no 401 flash" needs a REAL refresh token (`ProactiveTokenRefreshTriggerTest` + iOS-sim ON_RESUME actual); sub-second timeline redirect placeholder (Robolectric/iOS screen tests); return-to-non-Home destination (`PendingReturnDestinationTest`). Recipe captured in `.claude/skills/verify-loop/SKILL.md` § B.
- [x] 10.4 Confirm no `gradle/libs.versions.toml`, backend, schema, or migration change crept in (mobile-only invariant).
