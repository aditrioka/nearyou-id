# Tasks — mobile-analytics-consent-screen

## 1. Setup & pre-flight

- [x] 1.1 Confirm NO new library pin is introduced — Ktor KMP client + `Auth { bearer }`, Material 3 `Switch`, Navigation 3 serializable `NavKey`, kotlinx.serialization, and CMP Resources are all already on the `:mobile:app` / `:backend:ktor` classpaths. Substrate re-check is skip-eligible per `openspec/project.md` § Pre-implementation library re-check (extending an already-active library is not substrate selection). Record the skip rationale in the first feat commit body.
- [x] 1.2 Confirm `users.analytics_consent` exists at `backend/ktor/src/main/resources/db/migration/V2__auth_foundation.sql:22` with default `{"analytics": false, "crash": true, "ads_personalization": false}` — NO new Flyway migration in this change (verify the next `V<N>__*.sql` slot is untouched, so there is zero migration-number contention with in-flight changes).
- [x] 1.3 Confirm the consent `UPDATE users SET analytics_consent` needs no write-allowlist annotation. Note: the username-write + privacy-flag-write allowlists are **comment-convention only — there is NO custom Detekt rule** enforcing them (`NearYouRuleSetProvider` registers 9 rules; none is a column write-allowlist; the convention lives as prose in `BlockExclusionJoinRule.kt`). The block/shadow-ban rules (`RawFromPostsRule`, `BlockExclusionJoinRule`) match only `FROM/JOIN <table>`, NOT `UPDATE users`, so the own-row consent write trips neither (verified at proposal review). So: the grep-miss is EXPECTED — do not conclude from it that the username/privacy allowlists are unenforced for their own columns. NB this is the first non-admin `UPDATE users` write in the codebase (existing `UPDATE users` sites are all in `admin/`).
- [x] 1.4 Re-read the contracts this change consumes/mirrors: `backend/ktor/.../user/FcmTokenRoutes.kt` (authed-self `/api/v1/user/*` route pattern + its test), the mobile `AuthApiClient` + `Auth { bearer }` interceptor (Mobile #3), and `screens/routing/RootRouterScreen.kt` + `NavKeys.kt` + `AppNavSerialization.kt` (the `replaceAll` routing + polymorphic NavKey registration).

## 2. Backend — `PATCH /api/v1/user/consent`

- [x] 2.1 Create `backend/ktor/src/main/kotlin/id/nearyou/app/user/ConsentRoutes.kt` with `PATCH /api/v1/user/consent` inside the authenticated route block (same `authenticate` guard the other `/api/v1/user/*` routes use). Resolve the user identity from the JWT `sub`.
- [x] 2.2 Add the request DTO `@Serializable data class ConsentUpdateRequest(analytics: Boolean, crash: Boolean, adsPersonalization: Boolean)` with `@SerialName` snake_case (`analytics`, `crash`, `ads_personalization`) — **non-nullable `Boolean` fields, no defaults** — and the response DTO echoing the stored triple. A missing key (`MissingFieldException`) or a non-boolean value (type-mismatch `SerializationException`) → `400` with no write. Do NOT special-case extra unknown keys: they are ignored by the app-wide `ContentNegotiation { Json { ignoreUnknownKeys = true } }` (Application.kt) and an extra key can't corrupt the write (only the 3 canonical fields are read) — this matches the `FcmTokenRoutes` precedent; do NOT add a route-local strict `Json`.
- [x] 2.2a Mirror the `FcmTokenRoutes` transport guard: a `call.request.contentLength()` check rejecting bodies over a small cap (`MAX_BODY_BYTES`, e.g. 4096) with `400`/`413` before deserialization (consistency with the sibling authed-self route; the triple is tiny so this is a cheap belt-and-suspenders).
- [x] 2.3 Implement the write path (a `ConsentRepository`/service method on the user/data layer) that full-object-writes `users.analytics_consent` for `WHERE id = :jwtSub` only — `UPDATE users SET analytics_consent = :jsonb WHERE id = :sub`. Own-content write (raw allowed); serialize the triple to the canonical 3-key JSONB object. Return the stored value for the `200` response.
- [x] 2.4 Wire the route + repository into Koin (mirror the FCM-token wiring) and register the route in the app's routing module.
- [x] 2.5 Ensure the handler logs NO token / `Authorization` header / JWT `sub` / request-or-response body (per the `analytics-consent-update` PII requirement).

## 3. Backend tests (`:backend:ktor`)

- [x] 3.1 `200` happy path: seeded user at V2 default + valid token → `PATCH` with `{analytics:true, crash:false, ads_personalization:true}` → `200`, response echoes the triple, and `SELECT analytics_consent FROM users WHERE id = U` equals the written object (DB-tagged test against the service-container Postgres).
- [x] 3.2 Full-object-replace: a user at all-`true` → `PATCH` all-`false` → stored object is exactly all-`false` with exactly the 3 canonical keys (no merge, no stray keys).
- [x] 3.3 Own-row authz: user `A`'s `PATCH` updates only `A`; user `B`'s consent is unchanged.
- [x] 3.4 `401`: missing bearer → `401`; invalid token → `401`. For BOTH, explicitly `SELECT analytics_consent FROM users WHERE id = U` after the call and assert it equals the pre-call value (prove non-mutation — the security-relevant half).
- [x] 3.5 `400`: missing a key → `400`; non-boolean value → `400`. For BOTH, explicitly `SELECT`-assert the row is unchanged from its pre-call value. (No extra-unknown-key test — extra keys are ignored by design per 2.2, not a `400`.)
- [x] 3.6 Source-scan guard: assert the `ConsentRoutes.kt` source contains no log call site passing the token / `Authorization` / `sub` / body — **strip comments before scanning** (so the file's own KDoc mentioning those words doesn't trip the scan, per the source-scan-guard precedent).

## 4. Strings (`:shared:resources`)

- [x] 4.1 Add the `consent_*` strings to `shared/resources/src/commonMain/composeResources/values/strings.xml` with the exact text from the `shared-resources` ADDED spec (`consent_title`, `consent_explainer`, `consent_analytics_label`/`_desc`, `consent_crash_label`/`_desc`, `consent_ads_label`/`_desc`, `consent_cta_continue`, `consent_error_retryable`, `consent_skip`).
- [x] 4.2 Verify `consent_analytics_desc` / `consent_crash_desc` / `consent_ads_desc` are byte-identical to the three data-summary bullets in `docs/03-UX-Design.md` § "Analytics & Tracking Consent Screen (UU PDP)".
- [x] 4.3 Do NOT rewrite or remove any earlier (Mobile #2–#7) string.
- [x] 4.4 Build `:shared:resources` so CMP Resources codegen regenerates the `Res.string.*` accessors for the new keys.

## 5. ConsentScreen UI (commonMain)

- [x] 5.1 Create `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/consent/ConsentScreen.kt` rendering: title (`consent_title`), explainer (`consent_explainer`), three Material 3 `Switch` rows with label + description (`consent_analytics_*`, `consent_crash_*`, `consent_ads_*`), and the continue CTA (`consent_cta_continue`). Render under `NearYouTheme`, consistent with `AgeGateScreen`.
- [x] 5.2 Create `ConsentUiState.kt` with the toggle triple + screen state (Editing / Submitting / RetryableError / TokenInvalid / Success). Initial toggle values = analytics OFF, crash ON, ads OFF — injectable (a default-values parameter), NOT read from platform state, and NOT fetched via a GET.
- [x] 5.3 Ensure every UI string flows through `stringResource(Res.string.X)` — zero hardcoded literals.
- [x] 5.4 Render `Submitting` (disable CTA / show progress), `RetryableError` (`consent_error_retryable` + `cta_retry` + — only now — `consent_skip`), and `TokenInvalid` (`signin_error_token_invalid`) per the `mobile-analytics-consent` spec. The skip affordance MUST NOT be present before a failed submit.

## 6. Consent flow — ApiClient + repository + outcome mapping (commonMain)

- [x] 6.1 Create `mobile/app/src/commonMain/kotlin/id/nearyou/app/consent/ConsentApiClient.kt` issuing `PATCH /api/v1/user/consent` with the snake_case triple body via the existing `Auth { bearer }`-interceptor `HttpClient`.
- [x] 6.2 Create `ConsentRepository.submitConsent(analytics, crash, adsPersonalization): ConsentOutcome` + the `ConsentOutcome` sealed type (`Success`, `Retryable`, `TokenInvalid`). Map status-driven (NOT on a parsed `error.code`): `200`→Success; `401`→TokenInvalid; `5xx`/`503`/IO→Retryable; `400`→Retryable + logged diagnostic. No generic else-branch routing Home.
- [x] 6.3 Add the in-flight guard (`isInFlight` / `Mutex.tryLock` or CTA-disabled-while-loading) so a double-tap fires exactly one `PATCH`.
- [x] 6.4 Never log the token / `Authorization` / `sub` / response body in any consent source file.
- [x] 6.5 Koin-wire `ConsentApiClient` + `ConsentRepository` + `ConsentViewModel` in `di/MobileModule.kt`.

## 7. Routing — ConsentRoute + age-gate 201-terminus swap

- [x] 7.1 Add `@Serializable data object ConsentRoute : NavKey` to `screens/routing/NavKeys.kt` (parameterless, no identity payload) and register it in the `AppNavSerialization` polymorphic `SerializersModule`.
- [x] 7.2 Add `entry<ConsentRoute> { ConsentScreen(onDone = { backStack.replaceAll(HomeRoute) }) }` to `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/AppEntryProvider.kt` (the NavKey→composable mapping) — NOT `RootRouterScreen`.
- [x] 7.3 Swap the age-gate signup-`201` terminus **in `AppEntryProvider.kt`**: the `entry<AgeGateRoute> { AgeGateScreen(onSignedUp = { backStack.replaceAll(HomeRoute) }) }` callback today `replaceAll(HomeRoute)`s on signup success — change it to `replaceAll(ConsentRoute)` (per the `mobile-age-gate` MODIFIED requirement). Use `replaceAll` (NOT `push`/append) so `AgeGateRoute` is cleared from the back stack and back-press on `ConsentScreen` cannot re-enter the age gate (per the `mobile-analytics-consent` "ConsentRoute REPLACES the age-gate entry" requirement). `RootRouterScreen` is NOT touched (cold-start token routing only).
- [x] 7.4 Confirm the returning-user sign-in (`/signin 200`) terminus is UNCHANGED (still `HomeRoute` directly — consent is signup-path-only).
- [x] 7.5 Audit + update the existing age-gate terminus tests for the Home→Consent change: `AgeGateOutcomeHandlerTest` (commonTest — asserts `onSignedUp` fires on Success; the *handler* contract is unchanged but confirm), `AgeGateScreenTest` (androidUnitTest), and `AgeGateFlowIosTest` (iosTest) — any that assert the post-signup destination is `HomeRoute` must now expect `ConsentRoute`. Do not leave a stale Home-terminus assertion.

## 8. Mobile tests

- [x] 8.1 `androidUnitTest` (Robolectric `runComposeUiTest` — the established screen-test sourceset; `commonTest` would compile a Compose UI test into the iOS target and collide with the Robolectric host) `ConsentScreenTest` render: title + three toggle labels + continue CTA present; default toggle states (analytics OFF, crash ON, ads OFF).
- [x] 8.2 No-hardcoded-strings source scan over the `screens/consent/**` + `consent/**` package (JVM file I/O → `androidUnitTest`/`jvmTest`, not `commonTest`/K-N) — **strip comments first** (per the source-scan-guard precedent). Globs the package so `ConsentFlow` and any sibling are covered (per the `mobile-analytics-consent` PII requirement).
- [x] 8.3 Flow test (MockEngine): continue with toggles {analytics ON, crash OFF, ads ON} → captured `PATCH /api/v1/user/consent` body parses as `{"analytics":true,"crash":false,"ads_personalization":true}` → routes Home on `200`. Toggling a switch changes the submitted value.
- [x] 8.4 Flow test: no consent-read (`GET`) request is issued on screen entry.
- [x] 8.5 Outcome mapping tests: `503`/IO → `Retryable` (no nav, `consent_error_retryable` shown); `401` → `TokenInvalid` (no nav); `400` → `Retryable` + diagnostic. Double-tap continue → exactly one `PATCH`.
- [x] 8.6 Non-trapping test: skip affordance absent before failure; after a `503` it appears and routes Home. For Robolectric `*ScreenTest` exercising the real MockEngine submit, poll the end-state with `waitUntil` (NOT `waitForIdle` — the network submit isn't awaited by idle, per the async-repo-screen-test precedent).
- [x] 8.7 `ConsentRoute` serialization round-trips through `AppNavSerialization`; declares no identity property.
- [x] 8.8 If `ConsentScreenTest` uses the Robolectric ui-test-manifest host activity, ADD it to the Release-variant test exclude (the host activity is debug-only → `testDevReleaseUnitTest` would throw). Verify with `:mobile:app:testDevReleaseUnitTest`, not just Debug.
- [x] 8.9 iOS: a `src/iosTest` flow test (kotlin.test `@Test`, NOT Kotest — commonTest Kotest doesn't run on Kotlin/Native) covering the PATCH-body + route-on-200 path; run `:mobile:app:iosSimulatorArm64Test`. Confirm `ConsentRoute` + `ConsentScreen` compose on iOS (`linkDebugFrameworkIosSimulatorArm64` locally — CI/Linux can't catch K/N link issues).
- [x] 8.10 Inter-capability seam test (`androidUnitTest` flow): thread signup-`201` → the `AppEntryProvider` `onSignedUp` `replaceAll(ConsentRoute)` → assert `ConsentScreen` actually composes from that terminus (closes the gap between the `mobile-age-gate` MODIFIED nav-event assertion and the `ConsentRoute`→`ConsentScreen` mapping in 7.2).
- [x] 8.11 Back-stack/replaceAll test: after the signup-`201` transition, assert the back stack contains `ConsentRoute` (top) with NO `AgeGateRoute` beneath (replaced, not pushed) — so a back gesture cannot re-enter the age gate (per the `mobile-analytics-consent` "ConsentRoute REPLACES the age-gate entry" requirement).

## 9. Deferred-as-explicit-requirement + FOLLOW_UPS

- [x] 9.1 Add `FOLLOW_UPS.md` entry `mobile-analytics-consent-settings-toggle` — the Settings-screen consent re-edit path (`GET /api/v1/user/consent` per design OQ2 + a Settings screen). Depends on a Settings screen that does not exist yet.
- [x] 9.2 Add `FOLLOW_UPS.md` entry `mobile-analytics-consent-persist-hardening` — reliable persist (retry/queue) so a failed PATCH cannot leave a future tracking SDK mismatched. Becomes load-bearing only once the suppress-wrappers land. (Tracked by an explicit spec requirement so the follow-up has something to MODIFY.)
- [x] 9.3 Add `FOLLOW_UPS.md` entry `mobile-analytics-consent-rootrouter-regate` — `consent_completed_at` flag + RootRouter consent re-gate for returning token-bearing users. Deferred; safe-defaults make it benign for MVP.
- [x] 9.4 Verify each deferred behavior has BOTH a positive + negative-guard scenario in the `mobile-analytics-consent` spec (it does — re-confirm at archive that the FOLLOW_UPS entries still match the spec wording).

## 10. Validation, lint, pre-push gate

- [x] 10.1 `openspec validate mobile-analytics-consent-screen --strict` green.
- [x] 10.2 Pre-push gate (per CLAUDE.md): `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` green locally (CI runs BOTH ktlint + detekt — passing only detekt is insufficient).
- [x] 10.3 Mobile gate (flavor-qualified): `:mobile:app:testDevDebugUnitTest` + `:mobile:app:testDevReleaseUnitTest` (NOT the ambiguous `testDebugUnitTest`); `:shared:resources` Robolectric tests; detekt is root-level. Worktree needs a copied `local.properties` SDK pointer.
- [x] 10.4 Confirm the mobile no-hardcoded-UI-strings grep guard passes against the new `ConsentScreen` (all strings via `Res.string.*`).

## 11. Pre-archive staging smoke (backend endpoint has runtime impact)

- [ ] 11.1 Manual branch deploy: `gh workflow run deploy-staging.yml --ref mobile-analytics-consent-screen`; poll the deploy run.
- [ ] 11.2 Smoke `PATCH /api/v1/user/consent` against the branch deploy with a real staging token (200 + DB round-trip; 401 unauth; 400 malformed). Add/extend `dev/scripts/smoke-mobile-analytics-consent-screen.sh` if a smoke script is warranted. Tick this Section before `/opsx:archive`.

## 12. Docs / PR upkeep

- [ ] 12.1 Keep PR #157 title + body current at each phase boundary (proposal-review complete → first feat commit retitle `feat(mobile): mobile-analytics-consent-screen` → section landings → archive-ready), per `openspec/project.md` § "PR title and body MUST stay current".
- [x] 12.2 No new Gradle module is added, so no README module-list sync is needed (verify: this change adds files to existing modules only).
