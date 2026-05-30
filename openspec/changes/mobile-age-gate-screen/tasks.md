## 1. Setup & pre-flight

- [x] 1.1 Confirm no new library pin is introduced — `kotlinx-datetime` (`mobile/app/build.gradle.kts:77`) and Material 3 (`material3 = "1.10.0-alpha05"`, exposing `DatePicker`) are already on the `:mobile:app` classpath. Substrate re-check is skip-eligible per `openspec/project.md` § Pre-implementation library re-check (extending an already-active library is not substrate selection). Record the skip rationale in the first feat commit body.
- [x] 1.2 Re-read the backend contracts this change consumes: `openspec/specs/auth-signup/spec.md` (endpoint shape, snake_case wire, error taxonomy) and `openspec/specs/age-gate/spec.md` (privacy-preserving `403`, anti-DOB-shopping). Confirm `AuthWireFormatTest` still pins snake_case for `/signup` (grep `backend/ktor/.../auth/signup/`).
- [x] 1.3 Confirm `AgeGateScreen` will live in `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/auth/` so it falls inside the `mobile-app-scaffold` carve-out paths Mobile #3 established (no `mobile-app-scaffold` spec change needed).

## 2. Strings (`:shared:resources`)

- [x] 2.1 Add the Mobile #4 strings to `shared/resources/src/commonMain/composeResources/values/strings.xml`: `age_gate_title`, `age_gate_explainer`, `age_gate_dob_label`, `age_gate_dob_picker_cta`, `cta_create_account`, `age_gate_under18_blocked`, `signup_error_account_exists`, `signup_loading` — with the exact Bahasa Indonesia copy from the `shared-resources` MODIFIED spec.
- [x] 2.2 Verify `age_gate_under18_blocked` is byte-identical to the under-18 reject copy in `docs/06-Security-Privacy.md` / `docs/02-Product.md` / `docs/03-UX-Design.md` ("Platform ini hanya tersedia untuk pengguna usia 18 tahun ke atas.").
- [x] 2.3 Do NOT rewrite any Mobile #2/#3 string; do NOT remove `signin_error_no_account` in this section (its retirement is handled by the routing swap, not a string delete).
- [x] 2.4 Build `:shared:resources` so the CMP Resources codegen regenerates the `Res.string.*` accessors for the new keys.

## 3. AgeGateScreen UI (commonMain)

- [x] 3.1 Create `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/auth/AgeGateScreen.kt` as a Voyager `Screen` rendering: title (`age_gate_title`), explainer (`age_gate_explainer`), DOB field (`age_gate_dob_label` + `age_gate_dob_picker_cta`), and the create-account CTA (`cta_create_account`). Render under `NearYouTheme`; reuse the theme-aware brand-logo pattern from `SignInScreen`.
- [x] 3.2 Wire a Material 3 `DatePicker` (`rememberDatePickerState`) opened from the DOB field. Do NOT constrain `selectableDates`/year range to 18+ (per design D1); reject only future dates and non-dates (format/sanity validation). Enable the create-account CTA once a valid (non-future) date is picked. Both `today−18y` (exactly 18) and `today−18y+1day` (one day under) MUST be submittable — the boundary is the server's call.
- [x] 3.2a Give the DOB sanity-validation an injectable notion of "today" (`Clock` / `today: LocalDate` seam, mirroring Mobile #3's `nowMillis: () -> Long` in `AuthApiClient`) so the under-18 / exactly-18-boundary / future-date checks are deterministically testable (per design D1).
- [x] 3.3 Ensure every UI string flows through `stringResource(Res.string.X)` — zero hardcoded literals. Never render the Google `email`/`displayName` (PII discipline, design D7).
- [x] 3.4 Show the in-flight state via `signup_loading` while a signup call is running; render outcome states (blocked / account-exists / token-invalid / retryable) per the `mobile-age-gate` spec.

## 4. AuthRepository signup orchestration + DTO

- [x] 4.1 Add a signup request DTO to `mobile/app/src/commonMain/kotlin/id/nearyou/app/auth/` (`AuthApiClient` or sibling) with snake_case `@SerialName` (`id_token`, `date_of_birth`) and `provider`; do NOT include a `device_fingerprint_hash` field (design D4).
- [x] 4.2 Add `suspend fun signUpWithGoogle(dateOfBirth: LocalDate): SignUpOutcome` to `AuthRepository` (the existing Koin singleton). Convert the picked DOB to `"YYYY-MM-DD"` via `kotlinx-datetime`.
- [x] 4.3 Reuse the verified Google `id_token` carried from the sign-in `404` (do not re-run the Google ceremony on age-gate entry); never log the `id_token` (design D3).
- [x] 4.4 Define the `SignUpOutcome` sealed type (`Success`, `Blocked`, `AccountExists`, `InvalidIdToken`, plus a retryable error outcome) in `auth/**`; register/consume via `mobileModule`.
- [x] 4.5 Add an in-flight guard to `signUpWithGoogle` (`isInFlight` / `Mutex.tryLock`, or a CTA disabled-while-loading `signup_loading` state) so a double-tap cannot fire two concurrent `/signup` calls or two token writes (per design D8, mirroring Mobile #3's sign-in CTA defense).

## 5. Routing (RootRouterScreen branch + 404 swap)

- [x] 5.1 In `RootRouterScreen` / the navigator, add the branch that pushes `AgeGateScreen` carrying the verified Google identity (per the `mobile-auth-signin` MODIFIED `404` handling).
- [x] 5.2 Swap the `AuthRepository` `404 user_not_found` handling: from `NoAccount → signin_error_no_account` banner on `SignInScreen` to navigation to `AgeGateScreen` carrying the `id_token`. No on-`SignInScreen` banner on `404`.
- [x] 5.3 On signup `201`, persist `TokenPair` and route to `HomeScreen` via `RootRouterScreen` (same terminus as sign-in). On `409 user_exists`, route back to `SignInScreen`.

## 6. Outcome / error mapping

- [x] 6.1 Map every signup result to exactly one `SignUpOutcome` with no generic fallthrough, **keyed on HTTP status (and transport-failure type), NOT on a parsed `error.code`** (per design D2/D8): `201`→Success; `403`→Blocked (`age_gate_under18_blocked`, no token write, no nav); `409`→AccountExists (`signup_error_account_exists`, route to SignIn); `401`→one fresh `GoogleSignInClient.signIn()` + retry, second `401`→terminal (`signin_error_token_invalid`); `5xx`/`503`/IO/`GoogleSignInResult.Failed`→retryable error (`signin_error_network` + `cta_retry`); `400`→retryable error with logged diagnostic.
- [x] 6.1a CRITICAL (per security review): `/signup`'s `403` is the FLAT body `{"error":"user_blocked","message":"..."}` (no `code` field) — the shipped `AuthApiClient.BackendErrorBody` nested parser yields `null` on it. Map `403`→Blocked on **HTTP status**, render the local `age_gate_under18_blocked` string, ignore the server `message`, and do NOT log the `403` body. A code-based mapping would misroute the rejection to the retryable fallthrough.
- [x] 6.2 Emit the `Failed(message)` payload to Sentry/OTel logs only (never to user-facing UI).

## 7. Tests (commonTest)

- [x] 7.1 `AgeGateScreen` render test: title + DOB label + create-account CTA present via `stringResource`; brand logo present.
- [x] 7.2 No-hardcoded-strings test/grep scenario for `AgeGateScreen.kt`.
- [x] 7.3 DOB picker permits selecting an under-18 date (not constrained to 18+); future date not submittable. Using the injected clock (3.2a): assert BOTH `today−18y` (exactly 18) and `today−18y+1day` (one day under) are selectable AND enable the CTA — the client never accepts exactly-18 while rejecting one-day-under (boundary delegated to the server, guards D1).
- [x] 7.4 `signUpWithGoogle` MockEngine tests: `201`→canonical snake_case body (`provider`,`id_token`,`date_of_birth`, NO `device_fingerprint_hash`) + token write + route Home.
- [x] 7.5 `403`→Blocked: MockEngine returns the ACTUAL FLAT body `{"error":"user_blocked","message":"Akun tidak dapat dibuat dengan data ini."}` → maps to Blocked (`age_gate_under18_blocked`), NOT the retryable fallthrough (proves status-driven mapping, not `error.code` parse); no token write, no nav.
- [x] 7.6 `409 user_exists`→route to `SignInScreen`, no token write.
- [x] 7.7 `401 invalid_id_token`→exactly one `GoogleSignInClient.signIn()` re-invocation + retry; second `401`→terminal, no third invocation.
- [x] 7.8 Retryable-error mapping: bare `5xx`/IO → retryable (`signin_error_network` + `cta_retry`); typed `503 {error:{code:"username_generation_failed"}}` → retryable (status-driven); `400 {error:{code:"invalid_request"}}` → retryable + logged diagnostic (not silent, not crash).
- [x] 7.9 Outcome-coverage test: every documented result (`201/400/401/403/409/503/5xx`/IO/`Failed`) maps to a `SignUpOutcome` member; no `else`/wildcard generic-failure branch.
- [x] 7.10 PII test: no age-gate / signup error UI renders the Google `email` or `displayName`.
- [x] 7.11 `mobile-auth-signin` `404` test (MODIFIED): `404 user_not_found` navigates to `AgeGateScreen` carrying `idToken`; assert NO `SignInScreen` error banner — specifically that no node renders `Res.string.signin_error_no_account`; no token write.
- [x] 7.12 `shared-resources` string tests: all Mobile #2+#3+#4 keys declared; `age_gate_under18_blocked` exact text; `cta_create_account`/`signup_error_account_exists`/`age_gate_title` exact text.
- [x] 7.13 Double-submit guard: a second concurrent `signUpWithGoogle` invocation (double-tap) results in exactly ONE `/signup` call and at most ONE `SecureTokenStore.write` (per 4.5).
- [x] 7.14 Process-death mid-signup: with no `TokenPair` persisted, relaunch → `RootRouterScreen` reads `null` → routes to `SignInScreen` (no stale token, no crash, no stuck splash).
- [x] 7.15 No-second-ceremony-on-entry: reaching / composing `AgeGateScreen` does NOT invoke `GoogleSignInClient.signIn()` (the `id_token` is reused from the sign-in `404`) — distinct from the 401-refresh re-invocation count in 7.7.

## 8. Lint + verification (local, before push)

- [x] 8.1 Run the no-hardcoded-UI-strings grep step (per `shared-resources` § "No hardcoded UI strings" requirement) against `mobile/app/src/commonMain/`, `androidMain/`, `iosMain/` — expect zero offending matches.
- [x] 8.2 Run `./gradlew ktlintCheck detekt` (CI runs BOTH frameworks — `ktlintCheck` alone is insufficient per `openspec/project.md`).
- [x] 8.3 Run the mobile test suite (e.g., `:mobile:app:testDebugUnitTest` / `allTests` per the Mobile #3 precedent) and `:lint:detekt-rules:test`; all green.
- [x] 8.4 Run `openspec validate mobile-age-gate-screen --strict` — green.

## 9. FOLLOW_UPS.md bookkeeping

- [x] 9.1 Delete the `mobile-auth-signin-404-route-to-age-gate` entry from `FOLLOW_UPS.md` once this change's `404→AgeGateScreen` navigation is implemented + tested (the entry's action item is satisfied).
- [x] 9.2 Add a new `FOLLOW_UPS.md` entry `mobile-age-gate-stronger-verification` (design D6): self-declared DOB ships now; Apple Declared Age Range API (iOS 18+) + Google Play Families SDK cross-checks (per `docs/06-Security-Privacy.md` § Verification) deferred — note the PP 17/2025 age-assurance regulatory context as the landing motivation.
- [x] 9.3 Confirm the net FOLLOW_UPS open-entry count does not worsen the over-limit state (one deleted + one added = flat); if a triage sweep is warranted, surface it rather than silently exceeding the cap.

## 10. Pre-archive staging smoke (per `openspec/project.md` § Staging deploy timing)

- [ ] 10.1 Deploy the change branch to staging: `gh workflow run deploy-staging.yml --ref mobile-age-gate-screen`; poll the run to green.
- [ ] 10.2 Install the staging-flavored APK; with a Google account that has NO existing `users` row: sign in → land on `AgeGateScreen` → pick an 18+ DOB → verify backend `/signup` logs `201` + app routes to `HomeScreen` + token persisted across relaunch.
- [ ] 10.3 Under-18 path: pick an under-18 DOB → verify `403` + the `age_gate_under18_blocked` copy renders; verify a `rejected_identifiers` row was written server-side; verify a retry with a different DOB on the same identity is still blocked.
- [ ] 10.4 Existing-account path: with a Google account that DOES have a `users` row, force the signup call (or confirm `/signin` routes straight to Home) → verify `409 user_exists` is handled by routing to sign-in (no crash).
- [ ] 10.5 Tick the smoke results into this section; capture any deploy-config surprises as a new change (not a retro-edit of the squash).

## 11. Archive

- [ ] 11.1 Update the PR body to the merge-ready shape (final test counts, capability deltas, FOLLOW_UPS resolved/added).
- [ ] 11.2 Run `openspec archive mobile-age-gate-screen` locally; commit the resulting `openspec/specs/**` updates + the move under `archive/`.
- [ ] 11.3 Run `openspec validate --specs mobile-age-gate --specs mobile-auth-signin --specs shared-resources --strict` — green.
- [ ] 11.4 Push the archive commit; squash-merge once CI is green (one commit on `main` per the one-PR-per-change convention).
