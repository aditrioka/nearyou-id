## 1. Backend — GET /api/v1/user/consent

- [ ] 1.1 `ConsentRepository.readConsent(userId)`: own-row `SELECT` with per-key `COALESCE` to the V2 default, `@AllowMissingBlockJoin` own-row annotation, pool-bounded dispatcher; a missing row throws (→ `500`)
- [ ] 1.2 `ConsentRoutes`: `get("/api/v1/user/consent")` in the existing `authenticate` block → `200 ConsentResponse`; read failure → `500` + content-free `event=analytics_consent_read_failed error_class=…` log
- [ ] 1.3 `ConsentRoutesTest`: fresh account → V2 default; PATCH-then-GET round-trip; own-row only (A vs B); partial stored object → per-key defaults; missing token → 401; invalid token → 401; the existing source log-discipline scan still covers the GET handler

## 2. Mobile — consent read seam

- [ ] 2.1 `ConsentApiClient.fetchConsent()`: `GET /api/v1/user/consent`, `ConsentResponse` DTO (snake_case), sealed `ConsentReadResult` (`Success` / `HttpError` / `NetworkError`), never swallow cancellation
- [ ] 2.2 `ConsentFlow.fetchConsent(): ConsentSnapshot?` + `ConsentRepository` mapping (type/status-only diagnostics; `null` = unavailable)
- [ ] 2.3 `FakeConsentFlow`: configurable read result (default `null`), optional read gate, read counter
- [ ] 2.4 commonTest (`ConsentRepositoryTest`): GET 200 parses the triple; non-200 → `null` + diagnostic; transport error → `null` + type-only diagnostic; unknown keys ignored

## 3. Mobile — reconciler + sign-in reconcile

- [ ] 3.1 `consent/ConsentReconciler` (flow, store, onCrashConsentChanged): `reconcile(isStillWanted)` reads, re-checks the guard after the suspension, writes the snapshot, applies `crash`
- [ ] 3.2 `AuthRepository`: optional `consentReconciler`; on sign-in success run it bounded by `CONSENT_RECONCILE_TIMEOUT` (3 s) concurrently with the billing identity bind, before returning `Success`; sign-up does not reconcile
- [ ] 3.3 `MobileModule`: bind `ConsentReconciler` (crash callback → `CrashReportingController::applyConsent`) and pass it to `AuthRepository`
- [ ] 3.4 commonTest: `ConsentReconcilerTest` (success writes + applies; null → no-op; guard false after read → no write/apply); `AuthRepository` sign-in scenarios (writes + applies crash decline; unavailable read still `Success`; hung read times out under virtual time; overwrites a foreign snapshot; sign-up issues zero reads)

## 4. Mobile — consent Settings

- [ ] 4.1 `ConsentSettingsViewModel`: consolidate to ONE `StateFlow<ConsentSettingsUiState>` (private `VmState` + `stateIn(WhileSubscribed(5_000))`); track `touched` (any toggle change or save); on init `reconciler.reconcile { !touched }` and apply the result to the toggles
- [ ] 4.2 `ConsentSettingsScreen`: collect the single `uiState` (no visual change, no new strings)
- [ ] 4.3 commonTest `ConsentSettingsViewModelTest`: update the existing cases to the single `uiState`; add server-read-replaces-seed (+ snapshot write + crash callback), late-read-after-toggle discarded, late-read-after-save discarded, unavailable read keeps the seed
- [ ] 4.4 Robolectric `ConsentSettingsScreenTest`: toggles reflect the server read when the fake returns one

## 5. Mobile — onboarding / re-gate ConsentScreen

- [ ] 5.1 `ConsentViewModel`: on init read via `consentFlow.fetchConsent()`; apply to the toggles only while untouched (no toggle change, no continue); never write the snapshot from the read
- [ ] 5.2 commonTest `ConsentViewModelTest`: read shown before acting and NOT persisted; read after toggle discarded; unavailable read keeps defaults
- [ ] 5.3 Keep `ConsentScreenTest` / iOS `ConsentFlowIosTest` MockEngine handlers correct now that entry issues a GET (route the GET explicitly; assert the canonical PATCH body unchanged)

## 6. Mobile — logout clears the consent snapshot

- [ ] 6.1 `ConsentSnapshotStore.clear()`: interface + Android (`SharedPreferences`) + iOS (`NSUserDefaults`) actuals + `InMemoryConsentSnapshotStore`
- [ ] 6.2 `SettingsViewModel`: optional `consentSnapshotStore`, cleared inside the `NonCancellable` logout wipe; wire it in DI
- [ ] 6.3 Tests: Android `DurableConsentSnapshotStoreTest` + iOS `DurableConsentSnapshotStoreIosTest` clear-then-fresh-instance reads `null`; `SettingsViewModel` logout clears the snapshot (also on a failed revoke)

## 7. Mobile — found-while: drop the cached bearer on a new session

- [ ] 7.1 `AuthApiClient.clearCachedBearerTokens()` (clears every `BearerAuthProvider` on the shared client); `ProactiveTokenRefreshTrigger` reuses it
- [ ] 7.2 `AuthRepository`: call it right after `tokenStore.write(...)` on sign-in success and sign-up success
- [ ] 7.3 commonTest over the production `HttpClientFactory` config + MockEngine: cached `at-A` → clear store → write `at-B` → `clearCachedBearerTokens()` → next request carries `Bearer at-B` (and a control showing the stale `at-A` without the clear); `AuthRepository` sign-in and sign-up invoke the clear

## 8. Docs

- [ ] 8.1 docs/06 § Analytics & Tracking Consent: the server value is authoritative across devices (reconciled on sign-in and Settings entry; logout clears the device mirror)
- [ ] 8.2 At archive: refresh the `analytics-consent-update` and `mobile-settings` Purpose lines (PATCH-only / "no GET endpoint" wording)

## 9. Verification

- [ ] 9.1 Backend gate on a throwaway PostGIS container: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test`
- [ ] 9.2 Mobile gate: `:mobile:app:ktlintCheck :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest`
- [ ] 9.3 iOS: `:mobile:app:iosSimulatorArm64Test`
- [ ] 9.4 Manual verify (verify-loop): consent Settings reflects a server-side change on Android emulator + iOS simulator; screenshots in the PR body
- [ ] 9.5 Staging branch deploy + smoke: unauth `GET /api/v1/user/consent` → 401; authed GET matches the stored triple
