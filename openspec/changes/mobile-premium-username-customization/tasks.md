## 1. Pre-flight & reconciliation

- [x] 1.1 Render mockup frame 16 (`dev/mockups/nearyou-screens-mockup.html`, the "Ganti username" entry row) per docs/11 §2.8 — confirm no dedicated customization-screen frame exists; the screen derives from docs/03 §117–134 over the design-system substrate (design.md D9). Generate the frame-16 measurement annex (`dev/scripts/mockup-measure.sh`) for the Settings touchpoint.
- [x] 1.2 Re-read the shipped wire (`backend/.../user/UserUsernameRoutes.kt`) + `openspec/specs/premium-username-customization/spec.md` to lock the DTOs/statuses/error codes the client maps. (No new `libs.versions.toml` pin — propose-time substrate WebSearch gate does not fire; record "no new substrate" in the first feat commit.)
- [x] 1.3 File the reconciliation/deferral `follow-up` issues (label `follow-up` + `mobile`): (a) proactive cooldown entry-state — needs `username_last_changed_at` on the self-profile read; (b) three distinct unavailable messages — needs a `409`/probe reason discriminator; (c) distinct downgrade banner — needs a "previously customized" signal; (d) username autocomplete — needs a backend endpoint; (e) docs/03 §119/§121–123/§129 wording clarification vs the shipped wire. **Filed: #333 (proactive cooldown), #334 (distinct unavailable msgs), #335 (downgrade banner), #336 (autocomplete), #337 (docs/03 reconcile).**

## 2. Data layer (`id.nearyou.app.data.username`)

- [x] 2.1 `UsernameApiClient`: `PATCH /api/v1/user/username` (body `UsernameChangeRequest{@SerialName("new_username") newUsername}`) + `GET /api/v1/username/check?candidate=`; wire-truth DTOs `UsernameChangeResponse{username}`, `UsernameCheckResponse{available}`; Bearer via the shared `Auth`-plugin `HttpClient` (no ad-hoc client).
- [x] 2.2 Sealed `UsernameChangeOutcome` (Success/PremiumGate/Unavailable/InvalidFormat/Moderated/CooldownActive/RateLimited/Disabled/Error/SessionExpired/NetworkError) + `UsernameCheckOutcome` (Available/CheckPremiumGate/CheckInvalidFormat/ProbeExhausted/CheckDisabled/CheckSessionExpired/CheckNetworkError).
- [x] 2.3 `UsernameRepository`: status-driven mapping with the `error`-code split on `429` (cooldown_active/rate_limited) and `422` (invalid_username/username_rejected); `Retry-After` parse with non-positive floor; terminal-401 → SessionExpired ahead of the NetworkError fallback. Bind `single<UsernameFlow> { get<UsernameRepository>() }` in `MobileModule.kt`.

## 3. Pure helpers (commonMain, no UI)

- [x] 3.1 `usernameFormatValid(candidate): Boolean` — trim + 3–30 code points + charset `^[a-z0-9][a-z0-9_.]*[a-z0-9_]$` + `!contains("..")` (mirrors the backend accept/reject matrix).
- [x] 3.2 `usernameUiState(...)` pure projection over (input, format-validity, latest check outcome, latest change outcome, in-flight flags) → the documented states; day-countdown + minute-countdown formatters (reuse `capCountdownMinutes`); deterministic, monotonic-clock-driven (no wall-clock).

## 4. Route & navigation

- [x] 4.1 Add `UsernameCustomizationRoute` (`@Serializable data object`, no payload) to `NavKeys.kt`; register it in the `navSavedStateConfiguration` polymorphic `SerializersModule`.
- [x] 4.2 Map `entry<UsernameCustomizationRoute>` → `UsernameCustomizationScreen` in `AppEntryProvider`; wire the hoisted `onBack` (pop), `onChanged` (pop), and `onActivatePremium` → push `PaywallRoute` (introduced by #309 — design.md D8; until #309 merges, a documented TODO no-op).

## 5. Screen (`id.nearyou.app.screens.username`)

- [x] 5.1 `UsernameCustomizationScreen`: own `Scaffold` + top bar (title `username_title`, back), current `@handle`, single-line field (hint `username_field_hint`, cap 30 code points), inline status slot, primary "Ganti"; navigation-free (hoisted callbacks only); no hardcoded strings; light/dark via theme tokens.
- [x] 5.2 Live local format validation (debounced 500 ms) → inline `username_error_format`; gate probe + submit on format-validity.
- [x] 5.3 Render each state from the projection (Editing·available/unavailable/probe-deferred, Submitting, Unavailable, Moderated, CooldownActive, RateLimited, PremiumGate, Disabled, SessionExpired, Error) per the design-system loading-state contract.
- [x] 5.4 Submit-confirmation modal (`username_confirm_body` formatted @old→@new, `username_confirm_primary`/`_dismiss`) — `PATCH` only on confirm; "Batal" issues nothing.
- [x] 5.5 Premium-gate panel (`username_premium_gate_body` + `username_premium_gate_cta` → hoisted `onActivatePremium`); rendered as the INITIAL state for a not-Premium on-entry self-read AND reactively on `403` (change or probe); no in-screen back-stack ref.
- [x] 5.6 Success path: success toast (`username_success_toast`) + `onChanged` pop. Do NOT call a self-`ProfileFlow` refresh/invalidate — none exists (stateless `ProfileFlow`); propagation is the profile/settings surface re-fetching on next read. One-shot nullable UiState field cleared via `onSuccessShown()`.

## 6. ViewModel

- [x] 6.1 `UsernameCustomizationViewModel` (commonMain androidx `ViewModel`, route-scoped via `koinViewModel()`): one `StateFlow<UsernameUiState>` (`stateIn` WhileSubscribed 5s); owns input, the 500 ms debounced probe (coalescing rapid keystrokes into one `check` — protects the 3/day budget), submit, success one-shot, AND the on-entry self-Premium resolution via `ProfileFlow.loadProfile(selfUserId)` (`selfUserId` from `SelfUserIdProvider`) → initial gate/editor (read failure → editor, reactive 403 governs); talks to `UsernameFlow` + the self-profile read, never an ApiClient directly.

## 7. Strings (`:shared:resources` — CMP Resources, Bahasa Indonesia)

- [x] 7.1 Add the screen + state strings (verbatim docs/03 where specified): `username_title`, `username_field_hint`, `username_error_format`, `username_available`, `username_unavailable_generic` ("Username ini tidak tersedia. Coba username lain."), `username_probe_deferred` ("akan dicek saat kamu simpan"), `username_error_moderated` (docs/03 §124), `username_cooldown_countdown` ("Ganti username berikutnya tersedia dalam %1$d hari."), `username_rate_limited`, `username_disabled`, `username_confirm_body`/`_primary`/`_dismiss` (docs/03 §133), `username_success_toast` ("Username berhasil diganti"), `username_premium_gate_body` (docs/03 §114) / `_cta` ("Aktifkan Premium"). Reuse `timeline_session_redirect`, `signin_error_network`, `cta_retry`. Verify the no-hardcoded-UI-strings grep.

## 8. Settings entry wiring (MODIFIED `mobile-settings`)

- [x] 8.1 Move AKUN > "Ganti username" from the deferred set to a backed row in `SettingsScreen.kt`: activation pushes `UsernameCustomizationRoute` **unconditionally** (drop the "Segera hadir" path). Settings adds NO `isPremium` read / NO `onActivatePremium` param — the route-scoped screen owns the Premium gate (design.md D2). Update the settings deferred-row follow-up bookkeeping for #267 (the "Ganti username" line is now closed).

## 9. Tests

- [x] 9.1 commonTest: `usernameFormatValid` accept/reject matrix incl. the **30-accept / 31-reject** length boundary + field-cap; `UsernameUiStateTest` projection (every state, incl. the **non-positive countdown floor** → "1 hari" / "1 menit", no flash-clear); `UsernameCustomizationRoute` serialized round-trip; a ViewModel debounce-coalescing test (rapid keystrokes → one `check`, test-advanceable dispatcher).
- [x] 9.2 commonTest MockEngine: `UsernameApiClient`/`UsernameRepository` — `PATCH` body + `GET candidate` param, success/available parse, each change-status → outcome (incl. the 429/422 error-code splits + `Retry-After` parse, **incl. absent/unparseable → `0` floor**), each probe-status → outcome (incl. `ProbeExhausted`), terminal-401 → SessionExpired.
- [x] 9.3 Robolectric `UsernameCustomizationScreenTest` (added to the `mobile/app/build.gradle.kts` Release-variant exclude): field + live format feedback, each visual state, the **on-entry gate resolution** (not-Premium self-read → initial gate; Premium → editor) + the **probe-path gate** (`CheckPremiumGate`), the confirm modal (confirm fires / dismiss doesn't), success toast + `onChanged` pop, gate CTA → `onActivatePremium`. Update the existing (already Release-excluded) `SettingsScreenTest` to assert the "Ganti username" row pushes `UsernameCustomizationRoute` **unconditionally**.
- [x] 9.4 `iosTest` `UsernameFlowIosTest` (mirror `SearchFlowIosTest`) over a `FakeUsernameFlow` on the Native target.

## 10. Verification & gates

- [x] 10.1 `./gradlew :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest` green; `:mobile:app:linkDebugFrameworkIosSimulatorArm64` green locally (new route + iosTest touch Kotlin/Native).
- [x] 10.2 `./gradlew ktlintCheck detekt` green (mobile module).
- [ ] 10.3 docs/11 §5 DoD: manual `verify-loop` bring-up of the Ganti Username screen (Settings entry → screen → typing/validation → confirm modal → states) with screenshot evidence in the PR body BEFORE archive (context-routed run via `scripts/run_on_device.sh` / `scripts/test_android.sh`).

## 11. Deferred-scope bookkeeping

- [x] 11.1 Confirm every spec'd deferral (proactive cooldown entry-state, distinct unavailable messages, downgrade banner, autocomplete) has its `follow-up` issue from 1.3 referenced in the PR body — none silently dropped. **Tracked: #333–#337.**
