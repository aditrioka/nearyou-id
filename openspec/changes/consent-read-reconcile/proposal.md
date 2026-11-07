## Why

The server holds each account's consent (`users.analytics_consent`), but the app can only write it (`PATCH /api/v1/user/consent`); nothing reads it back. On a new device or after a reinstall the device snapshot is empty, so:

- the crash startup gate falls back to "crash ON";
- the RootRouter re-gate shows the V2 defaults;
- one tap on "continue" then overwrites a crash decline the server already holds;
- Settings shows the defaults instead of the account's real choices.

Logout also never clears the device snapshot, so the next account that signs in on the device inherits the previous account's consent. That breaks the per-account consent model in docs/06 (UU PDP). This is GitHub issue [#266](https://github.com/aditrioka/nearyou-id/issues/266), part 2. Part 1 (durable snapshot) already shipped.

## What Changes

- **Backend:** add `GET /api/v1/user/consent`. It is JWT-authenticated and reads only the caller's own row. It returns the stored `{analytics, crash, ads_personalization}` triple, which is the same wire shape the PATCH echoes. It returns `401` for a missing or invalid token. No migration.
- **Mobile data seam:** add `ConsentApiClient.fetchConsent()` and `ConsentFlow.fetchConsent()`. The read returns the server triple, or `null` when it is unavailable; a failed read never surfaces an error.
- **Mobile reconcile:** add a `ConsentReconciler`. It reads the server triple, writes it to the durable `ConsentSnapshotStore`, and applies the `crash` value to `CrashReportingController` at once. It runs:
  - on a successful sign-in, bounded by a timeout so it never holds sign-in;
  - on entry to consent Settings.
- **Consent Settings:** seeds from the snapshot as before (instant render), then takes the server values if the user has not touched a toggle or saved yet. The ViewModel moves to one `uiState` StateFlow (docs/11 §2.2: consolidate a multi-flow VM when it is next touched).
- **Onboarding / re-gate `ConsentScreen`:** keeps the V2 defaults as its first render. It then shows the server values when the user has not acted yet, so a re-gated user re-confirms their real choices instead of the defaults. This read never writes the snapshot, so it cannot satisfy the re-gate by itself.
- **Logout:** the explicit logout wipe now also clears the device consent snapshot.
- **Found-while (auth):** a successful sign-in or sign-up now clears the shared `HttpClient`'s cached bearer tokens. Without it, after a logout → sign-in in the same process, the new session's requests (the reconcile included) carry the previous account's still-valid access token until it expires.
- **Docs:** one line in docs/06 § Analytics & Tracking Consent: the server value is authoritative across devices.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `analytics-consent-update`: ADD the authenticated `GET /api/v1/user/consent` own-row read requirement.
- `mobile-settings`:
  - MODIFY "Consent settings initialize from the last-submitted snapshot…": seed from the snapshot, then refresh from the server read; drop the "no GET endpoint" claim.
  - MODIFY "Logout clears the token store…": the wipe also clears the consent snapshot.
- `mobile-analytics-consent`:
  - MODIFY "Toggles default to … with no GET round-trip": defaults first, then the server read when the user has not acted.
  - MODIFY "RootRouter re-gates…": the snapshot is also written by the sign-in reconcile.
  - ADD "Sign-in reconciles the consent snapshot from the server".
- `mobile-auth-signin`: ADD "A new session drops the shared client's cached bearer tokens" (found-while, design D10).

## Impact

- **Backend:** `backend/ktor/.../user/ConsentRoutes.kt`, `ConsentRepository.kt` and `ConsentRoutesTest.kt`. The route is added inside the existing `consentRoutes(...)`, so `Application.kt` does not change.
- **Mobile:**
  - consent seam: `consent/ConsentApiClient.kt`, `ConsentFlow.kt`, new `consent/ConsentReconciler.kt`;
  - snapshot store: `data/consent/ConsentSnapshotStore.kt` plus the Android and iOS `DurableConsentSnapshotStore` actuals (new `clear()`);
  - callers: `auth/AuthRepository.kt` + `auth/AuthApiClient.kt` + `screens/routing/ProactiveTokenRefreshTrigger.kt` (shared bearer-cache clear), `screens/settings/ConsentSettingsViewModel.kt` + `ConsentSettingsScreen.kt`, `screens/consent/ConsentViewModel.kt`, `screens/settings/SettingsViewModel.kt` + its DI wiring, `di/MobileModule.kt`;
  - test doubles and tests: `FakeConsentFlow`, plus tests in commonTest, androidUnitTest and iosTest.
- **Unchanged:** `KoinInit.kt` and `RootRouterScreen.kt` code. Their gates read the snapshot that the reconcile now fills.
- **Wire:** one new read endpoint, so existing clients are unaffected. Staging needs a branch deploy and smoke before archive (docs/11 §5).
