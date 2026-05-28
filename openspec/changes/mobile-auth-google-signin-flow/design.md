## Context

`:mobile:app` ships a Compose Multiplatform scaffold today (`mobile-app-scaffold-replace-wizard` PR [#105](https://github.com/aditrioka/nearyou-id/pull/105) + the two follow-ups `shared-resources-moko-bootstrap` PR [#116](https://github.com/aditrioka/nearyou-id/pull/116) and `shared-resources-swap-to-cmp-resources` PR [#119](https://github.com/aditrioka/nearyou-id/pull/119)). The current `App()` composable wraps a Voyager `Navigator` with `HomeScreen` as the start destination, Koin DI is initialized via `initKoin()` from both platform entry points, and `:shared:resources` exposes `NearYouColorScheme` + `NearYouTypography` + a 10-string foundational catalog via Compose Multiplatform Resources. No networking, no auth, no FCM, no hardcoded API base URLs — Mobile #1's negative requirements explicitly forbid them and the present spec carves out "shipped in later mobile changes per project.md § Mobile + Admin Scaffolding Priority (#3 Google Sign-In ...)".

Backend `/signin` (`auth-signin` capability, archived `2026-04-20-auth-foundation`), `/refresh` (`auth-session`), and JWKS (`auth-jwt`) have shipped + been hardened. The backend has had no mobile caller wired against it yet — Mobile #3 closes that gap.

The user chose "Google Sign-In on both Android + iOS" at proposal time (see `/next-change` Phase A.4 transcript) — TWO canonical docs prescribe iOS primary = Apple Sign-In at the eventual state: `docs/03-UX-Design.md` § Auth Flow line 38 ("iOS: Masuk dengan Apple (primary, user-facing)") AND `docs/04-Architecture.md` § Tech Stack table line 15 ("Auth | Google Sign-In (Android Credential Manager) + Apple Sign-In (backend verify + ...)"). The menu (`openspec/project.md` § Mobile + Admin Scaffolding Priority) literally specifies `mobile-auth-google-signin-flow` with "Keychain on iOS, EncryptedSharedPreferences on Android" implying both platforms ship the same flow. The reconciliation: Google end-to-end first as the substrate-proving change (one Google SDK on both platforms is simpler to integrate end-to-end + carries less Apple-Developer-cert-related setup risk for the first auth integration); Apple Sign-In iOS follows as a later change that swaps iOS primary (logged in `FOLLOW_UPS.md` as `docs-ios-primary-auth-mobile-3-vs-eventual-state` covering both docs amendments). The eventual-state docs reflect the eventual state and are not being amended in this change.

Substrate constraints already in play: Kotlin 2.3.20, Ktor 3.4.1 (backend currently uses the `-jvm` artifact set; KMP client needs the non-`-jvm` variants), Voyager 1.1.0-beta03, Koin 4.1.0, Compose Multiplatform 1.10.3, Material 3 1.10.0-alpha05, android-minSdk = 24, android-targetSdk = 36.

## Goals / Non-Goals

**Goals:**
- First end-to-end mobile auth flow on both Android + iOS, exercising the canonical backend `/signin` + `/refresh` contracts.
- Authoritative substrate selection for: Google Sign-In ceremony (Android: Credential Manager; iOS: GoogleSignIn iOS SDK), secure-at-rest token storage (Android: DataStore + Tink; iOS: Keychain Services), KMP HTTP client (Ktor 3 with OkHttp/Darwin engines), environment-aware API base URL injection.
- Clean error-handling taxonomy mapping backend HTTP statuses (404 / 403 / 401 / 5xx) to user-facing copy + UI states.
- Routing skeleton (`RootRouterScreen`) that generalizes for Mobile #4 (age gate before Home) and Mobile #5 (Home becomes the timeline screen) without rework.
- Selective lift of Mobile #1's negative requirements (Ktor client allowed, auth-flow identifiers allowed in this change's files only, env-aware API base URL allowed inside `id.nearyou.app.config`).

**Non-Goals:**
- Apple Sign-In on iOS (separate later change — log entry in `FOLLOW_UPS.md` for `mobile-auth-signin-apple-ios`).
- Signup flow (`POST /api/v1/auth/signup` with DOB + age gate) — Mobile #4.
- Age-gate UI — Mobile #4.
- Attestation (Play Integrity / App Attest) — `docs/06-Security-Privacy.md` § Attestation; `device_fingerprint_hash` payload field bundled with that future change (sign-in spec accepts the field as optional today, so Mobile #3 omits it).
- Analytics & Tracking Consent screen, location permission flow, FCM token registration — all later mobile changes per `docs/03-UX-Design.md` onboarding sequence.
- Logout flow (`POST /api/v1/auth/logout` / `/logout-all`) — no Settings screen ships in Mobile #3; logout wires up with the Settings change.
- Splitting auth + networking into a new module (`:shared:auth`, `:shared:network`, etc.) — keep everything inside `:mobile:app` for now per rule of three; module split deferred until a second consumer emerges.
- `production` environment URL wired with a real hostname — placeholder until production infra is provisioned.

## Decisions

### Decision 1 — Google Sign-In substrate per platform

**Choice:** Android = **Credential Manager API** (`androidx.credentials` + `androidx.credentials:credentials-play-services-auth` + `com.google.android.libraries.identity.googleid:googleid` helper). iOS = **Google Sign-In iOS SDK** (`GoogleSignIn` Pod via CocoaPods integration in the existing iOS Xcode project).

**Rationale:**
- **Android Credential Manager** is Google's canonical sign-in API as of 2026. Per [About Sign in with Google | Android Developers](https://developer.android.com/identity/sign-in/credential-manager-siwg), "the legacy Google Sign-In for Android is deprecated and will be removed from the Google Play services Auth SDK in a future release." Credential Manager provides a unified surface for Google Sign-In + passkeys + saved-password autofill — the Google ID helper returns the same ID token shape that the backend `/signin` endpoint expects. Verified 2026-05-28 via propose-time WebSearch.
- **iOS GoogleSignIn SDK via CocoaPods** is the only Google-supported iOS path. The KMP-wrapped `KMPAuth` community library (per [proandroiddev integration guide](https://proandroiddev.com/integrating-google-sign-in-into-kotlin-multiplatform-8381c189a891)) is an attractive convenience layer but introduces a 3rd-party maintainer dependency and a Firebase requirement that this project doesn't need. CocoaPods is already in the iOS toolchain via the existing `iosApp` project. Verified 2026-05-28.

**Alternatives considered:**
- **(rejected) Legacy `GoogleSignInClient` on Android.** Deprecated by Google. Older Android devices (API < 28 with corrupted Play Services) reportedly fail on Credential Manager — that's a follow-up concern (`FOLLOW_UPS.md` entry `mobile-auth-signin-credential-manager-legacy-fallback`), not a substrate-flip reason today. The `GoogleSignInResult.Failed(message)` sealed result surface handles this gracefully (CTA shows "Tidak bisa terhubung ke Google. Coba lagi nanti.") until the fallback ships.
- **(rejected) `KMPAuth` community wrapper.** Adds a third-party-maintainer risk + a Firebase dependency. This project deliberately holds off on Firebase Auth (per Architecture decision — backend issues its own RS256 JWT) so introducing Firebase for sign-in alone is dead weight.
- **(rejected) Raw OAuth2 browser intent.** Worse UX (browser switch), Apple-policy-fragile on iOS, less secure (no platform-level credential binding). Rejected.
- **(rejected) Apple Sign-In on iOS as Mobile #3 primary.** UX-doc-canonical but adds Apple Developer cert + entitlements + Apple Developer Program enrollment to this change's setup. User chose Google-on-both at Phase A.4. Apple Sign-In iOS follows as a separate change.

### Decision 2 — expect/actual structure for the Google Sign-In wrapper

**Choice:** Single `commonMain` interface `GoogleSignInClient` with `actual` impls in `androidMain` + `iosMain`. Sealed result type:

```kotlin
sealed interface GoogleSignInResult {
    data class Success(
        val idToken: String,
        val displayName: String?,
        val email: String?,
    ) : GoogleSignInResult
    data object UserCancelled : GoogleSignInResult
    data class Failed(val message: String) : GoogleSignInResult
}
```

`commonMain` exposes `expect class GoogleSignInClient { suspend fun signIn(): GoogleSignInResult }`. Both actuals are coroutine-suspending wrappers around the platform SDK's callback shape (Credential Manager `prepareGetCredentialRequest` + `getCredential` on Android; `GIDSignIn.sharedInstance.signIn(withPresenting:)` on iOS — wrapped in `suspendCancellableCoroutine`).

**Rationale:** Single commonMain entry point + sealed result type lets `AuthRepository` consume the ceremony uniformly across platforms. Coroutine-suspending shape matches the rest of the mobile codebase + composes cleanly into Voyager screen state.

**Alternatives considered:**
- **(rejected) `Flow<GoogleSignInResult>` instead of suspend.** Overkill for a one-shot ceremony — sign-in is request/response, not a stream.
- **(rejected) `Result<...>` instead of sealed interface.** Loses the explicit `UserCancelled` semantic differentiation (cancel is not an error).
- **(rejected) Wrap the iOS SDK via the iOS Swift host's `ContentView` + bridge.** Possible but introduces a Swift-side state machine for the ceremony. Kotlin-side `iosMain` wraps the GoogleSignIn ObjC interop directly — cleaner.

### Decision 3 — Token storage substrate per platform

**Choice:** Android = **Preferences DataStore + Tink AEAD encryption** with the master key wrapped via Android Keystore. iOS = **Keychain Services** (Security framework) with accessibility `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`. Stored payload: access token + refresh token + access-token expiration timestamp (epoch millis).

**Rationale (propose-time WebSearch surfaced a substrate shift):**
- **Android: `EncryptedSharedPreferences` is officially deprecated** as of `androidx.security:security-crypto:1.1.0-alpha07`. Per [the official Android Developers reference](https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences) + the [2026 Migration Guide](https://proandroiddev.com/goodbye-encryptedsharedpreferences-a-2026-migration-guide-4b819b4a537a), the canonical replacement is **DataStore + Google Tink** for secure key-value storage (encrypts the entire file rather than per-value, fixes the OEM-fragility + main-thread strict-mode issues). The Tink AEAD primitive (`AesGcmKey`) is wrapped via the Android Keystore (`MasterKeys.AES256_GCM` style key, but constructed via Tink's `AndroidKeysetManager`). Verified 2026-05-28 via propose-time WebSearch.
- **iOS: Keychain Services** is the canonical iOS token-at-rest path, no deprecation. `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` balances availability (token usable after device unlock) with security (token bound to device, not synced via iCloud Keychain). This matches the OAuth-token-storage recommendation in Apple's [Keychain Services Programming Guide](https://developer.apple.com/documentation/security/keychain_services).

**Substrate-direction shift documented:** The original proposal-time plan (Mobile #3 menu language + canonical convention) said "EncryptedSharedPreferences on Android". Propose-time WebSearch surfaced the deprecation. This Decision revises to DataStore + Tink. The pre-implementation library re-check gate at `/opsx:apply` step 1 should drop a `re-check 2026-MM-DD confirms` note when implementation begins.

**Alternatives considered:**
- **(rejected) `EncryptedSharedPreferences`.** Deprecated. Known OEM brittleness + main-thread perf issues per the migration guides.
- **(rejected) Keystore-direct (`KeyStore.getInstance("AndroidKeyStore")` + manual key wrap).** Lower-level than needed; DataStore + Tink wraps this canonically.
- **(rejected) Plain DataStore without encryption.** Tokens at rest must be encrypted per `docs/05-Implementation.md` § Authentication Implementation.
- **(rejected) Room database with `SQLCipher`.** Overkill for 3 fields + adds a database substrate to the mobile app.

### Decision 4 — Ktor HTTP client setup

**Choice:** Single shared `HttpClient` constructed in `:mobile:app` commonMain Koin module. Engine: `ktor-client-okhttp` on Android, `ktor-client-darwin` on iOS. Plugins: `ContentNegotiation(Json { ignoreUnknownKeys = true })`, `DefaultRequest { url(apiBaseUrl) }`, `Auth { bearer { ... } }` with a `loadTokens` callback reading from `SecureTokenStore` + a `refreshTokens` callback invoking `POST /api/v1/auth/refresh`, `Logging { level = LogLevel.HEADERS }` (dev only, gated by `BuildConfig.DEBUG`).

The Ktor `Auth { bearer { ... } }` plugin already handles the 401-detect → refresh → retry flow per the Ktor 3 client docs; the `refreshTokens` callback returns a fresh `BearerTokens(accessToken, refreshToken)` after successfully exchanging the refresh token for a new pair, or `null` if the refresh fails (which surfaces as a 401 to the caller — the `AuthRepository` catches this and triggers store-clear + redirect to `SignInScreen`).

**Rationale:**
- **Ktor 3 KMP client + OkHttp + Darwin** is the canonical KMP HTTP-client stack per [Ktor's KMP guide](https://ktor.io/docs/client-create-multiplatform-application.html). Verified 2026-05-28 via propose-time WebSearch: "Ktor uses platform-native engines under the hood — OkHttp on Android, Darwin (URLSession) on iOS — so you get native performance without writing platform-specific networking code."
- **Single shared client** matches the Koin idiom + avoids per-screen client construction. The `Auth { bearer { ... } }` plugin centralizes the 401-refresh logic in one place.
- **`ignoreUnknownKeys = true`** is defensive — backend may add response fields in future without breaking mobile.

**Alternatives considered:**
- **(rejected) `ktor-client-cio` engine on Android.** CIO is fine for backend (already pinned for backend `:ktor-clientCio` JVM coordinate) but lacks the OkHttp ecosystem features (interceptors, network state observation) that prove useful on Android.
- **(rejected) Per-screen `HttpClient` instances.** Defeats the Auth plugin's session-wide state.
- **(rejected) Manual interceptor instead of `Auth { bearer { ... } }` plugin.** The plugin's request-queuing-during-refresh behavior is non-trivial to reimplement and a known footgun.
- **(rejected) Retrofit/OkHttp on Android with separate URLSession on iOS.** Defeats the entire KMP shared-networking goal.

### Decision 5 — Environment-aware API base URL

**Choice:** `expect val apiBaseUrl: String` in `commonMain/kotlin/id/nearyou/app/config/`. Android actual reads from `BuildConfig.API_BASE_URL` (gradle product flavors `dev` / `staging` / `production` each inject a different value). iOS actual reads from `NSBundle.mainBundle.objectForInfoDictionaryKey("ApiBaseUrl")` driven by an xcconfig variable per scheme.

Android product flavors land via `productFlavors { create("dev"); create("staging"); create("production") }` in `mobile/app/build.gradle.kts`, each setting `buildConfigField("String", "API_BASE_URL", "\"$URL\"")`. The flavor is selected via gradle property `mobileEnv` (default `dev`) or directly via task name (e.g., `assembleStagingDebug`).

iOS xcconfig wiring lands `Staging.xcconfig` and `Production.xcconfig` files referencing `APP_API_BASE_URL`. The xcconfig variable is injected into Info.plist via `${APP_API_BASE_URL}` placeholder in `Info.plist` source.

**Rationale:** Build-time config (vs runtime fetch from a manifest server) keeps the auth flow self-contained and avoids a circular dependency (the manifest fetch would itself need an unauthenticated endpoint). Per [`openspec/project.md`](../../project.md) § Environments: "Mobile uses Android flavors / iOS xcconfig schemes (`staging` vs `production`)." This change codifies the convention.

**Alternatives considered:**
- **(rejected) Single `BuildConfig.STAGING` boolean + branched URL.** Doesn't scale to 3 envs.
- **(rejected) Runtime config-fetch endpoint.** Auth flow can't depend on auth-less manifest fetch.
- **(rejected) Hardcoded staging URL in commonMain.** Forbidden by `mobile-app-scaffold` negative requirement; this change carves out the `id.nearyou.app.config` package as an exception.

### Decision 6 — `RootRouterScreen` as new Voyager start destination

**Choice:** A new `RootRouterScreen` becomes the Voyager `Navigator(startDestination = ...)` first screen. On entry, it `LaunchedEffect`-suspends a read of `SecureTokenStore`:
- If a non-expired access token (or a refresh token whose `expires_at` is still in the future) exists, `navigator.replaceAll(HomeScreen)`.
- Otherwise `navigator.replaceAll(SignInScreen)`.

While the token check is in-flight, render the brand splash (`Res.drawable.logo_brand_light/dark` centered + `CircularProgressIndicator`).

`HomeScreen` stays as the post-auth placeholder (Mobile #5 replaces it with the timeline). `SignInScreen` is the new screen this change introduces.

**Rationale:** Decoupling the routing concern from the screens themselves means Mobile #4 can interject an `AgeGateScreen` between `SignInScreen` and `HomeScreen` by adding one branch to the router. Mobile #5 doesn't touch `RootRouterScreen` at all — it just replaces `HomeScreen`'s body.

**Alternatives considered:**
- **(rejected) `SignInScreen` as the new start destination + token-check happens inside its `onCompose`.** Mixes routing logic with screen UI; harder to add Age Gate cleanly.
- **(rejected) Composing the token check in `App()` and conditionally rendering Navigator with different start destinations.** Loses Voyager back-stack consistency.

### Decision 7 — Backend error mapping + "no account exists" temporary copy

**Choice:** Map backend HTTP status codes to specific user-facing strings + UI states:

| Backend response | UI state | Copy (Bahasa Indonesia) | Recovery action |
|---|---|---|---|
| `200` success | Token persisted, navigate to `RootRouterScreen → HomeScreen` | — | — |
| `404 user_not_found` | SignInScreen with error banner | "Akun belum terdaftar. Daftar dulu lewat pembaruan aplikasi berikutnya." | Stay on SignInScreen; CTA to retry sign-in |
| `403 account_banned` | SignInScreen with error banner | "Akun kamu telah dinonaktifkan. Hubungi support jika ini keliru." | Stay on SignInScreen; no retry (banned) |
| `401 invalid_id_token` | SignInScreen with error banner + auto-retry Google ceremony once | "Sesi Google bermasalah. Coba lagi." | Re-invoke Google Sign-In ceremony once |
| `5xx` / network failure | SignInScreen with error banner + retry button | "Tidak bisa terhubung. Periksa koneksi internet kamu." | "Coba lagi" button re-invokes the whole flow |
| User cancels Google ceremony | SignInScreen unchanged | — (no error) | — |

The `404 user_not_found` copy is **temporary** — it tells the user "registration not available in this build" rather than directing them through age-gate signup. Mobile #4 will replace this branch with a navigation to `AgeGateScreen`. `FOLLOW_UPS.md` entry `mobile-auth-signin-404-route-to-age-gate` tracks the swap.

**Rationale:** Mapping HTTP statuses to specific copy + UI states centralizes the error-handling decisions and keeps the screen file free of inline status checks. The temporary "no registration available" copy is honest about the current build's capability — users see a clear "try again later" message rather than a confusing redirect loop.

**Alternatives considered:**
- **(rejected) Generic "Sign-in failed" copy for all errors.** Loses signal — banned users will keep retrying; users hitting `404` should know what to do.
- **(rejected) Show "Coming soon" message for `404`.** Same intent, less actionable.
- **(rejected) Block the SignInScreen behind a maintenance gate when registration is unavailable.** Over-engineered for a 2-week window before Mobile #4 ships.

### Decision 8 — Refresh-token rotation handled by Ktor `Auth { bearer { ... } }` plugin

**Choice:** Use Ktor's built-in `Auth { bearer { ... } }` plugin with:
- `loadTokens { SecureTokenStore.read()?.let { BearerTokens(it.accessToken, it.refreshToken) } }`
- `refreshTokens { val response = client.post("/api/v1/auth/refresh") { ... markAsRefreshTokenRequest() }; ... }`

The plugin queues subsequent requests during an in-flight refresh + retries them with the new token. On refresh failure (`401` from the refresh endpoint, i.e., `token_reuse_detected` or `token_revoked` per [`openspec/specs/auth-session/spec.md`](../../specs/auth-session/spec.md)), the `refreshTokens` callback returns `null`, which surfaces `401` to the caller — the `AuthRepository` catches this signal, clears the store, and triggers a re-render of `RootRouterScreen` (which then routes to `SignInScreen`).

**Rationale:** Ktor's plugin already implements the harder bits (concurrent-request queuing during refresh, retry-with-new-token). The 30-second overlap window per `auth-session` spec is server-side; the client doesn't need to special-case it.

**Alternatives considered:**
- **(rejected) Manual `HttpRequestRetry` plugin + custom 401 handler.** Reimplements the refresh queue.
- **(rejected) Background refresh on a fixed schedule.** Wasteful + harder to test.

### Decision 9 — Mobile #3 omits `device_fingerprint_hash`

**Choice:** The `POST /api/v1/auth/signin` request body sent from mobile in this change includes only `{ provider: "google", id_token: <google-id-token> }` — `device_fingerprint_hash` is omitted (sent as `null` / absent field).

Per [`openspec/specs/auth-signin/spec.md`](../../specs/auth-signin/spec.md): "The optional `device_fingerprint_hash` field on the request body SHALL be persisted to `refresh_tokens.device_fingerprint_hash` when present. It MUST NOT be required for sign-in to succeed (attestation lands later)." Compatible with omission.

**Rationale:** Fingerprint generation requires platform-specific entropy collection (hardware identifiers, app install ID, etc.) that lands canonically alongside Play Integrity / App Attest attestation per `docs/06-Security-Privacy.md` § Attestation. Bundling fingerprint generation into Mobile #3 would import the attestation-design surface ahead of its time.

`FOLLOW_UPS.md` entry `mobile-auth-signin-attestation-fingerprint-hash` tracks the addition.

**Alternatives considered:**
- **(rejected) Ship a placeholder fingerprint (e.g., SHA-256 of `ANDROID_ID` / iOS `identifierForVendor`).** Would persist a non-canonical value in `refresh_tokens.device_fingerprint_hash` that the future attestation work would have to migrate or ignore. Not worth the complexity.

### Decision 10 — Keep all auth + networking inside `:mobile:app` (no new modules)

**Choice:** All auth scaffold + Ktor client + token storage code lives inside `:mobile:app`'s source tree. No new `:shared:auth`, `:shared:network`, or `:mobile:auth-google` modules introduced. The `expect/actual` boundary works fine within `:mobile:app`.

**Rationale:**
- **Rule of three.** A single consumer (the mobile app) doesn't justify a module split. When/if a second consumer emerges (e.g., a future watch app, or a shared-secured-storage need across mobile + desktop), the split becomes worth doing.
- **Reduces cognitive overhead.** New contributors don't have to trace through 3 module boundaries to understand the sign-in flow.
- **Faster iteration.** No cross-module API design effort during the substrate-proving phase.

**Alternatives considered:**
- **(rejected) New `:shared:auth` module.** Premature abstraction. The interface designs (`GoogleSignInClient`, `SecureTokenStore`, `AuthApiClient`) are stable enough to extract later if a second consumer emerges.
- **(rejected) New `:infra:google-signin` module.** Mobile-only consumers ≠ backend `:infra:*` pattern. The `:infra:*` convention is "vendor SDK leaks contained for backend code"; the mobile equivalent is just commonMain hygiene.

## Risks / Trade-offs

| Risk | Likelihood | Severity | Mitigation |
|---|---|---|---|
| Credential Manager fails on older Android devices (API 24-27 with corrupted Play Services) | Medium | Low (subset of users; alternative path exists) | Sealed result type catches `GetCredentialException` → `GoogleSignInResult.Failed(message)`. `FOLLOW_UPS.md` entry `mobile-auth-signin-credential-manager-legacy-fallback` tracks adding the legacy `GoogleSignInClient` fallback once the user-feedback signal materializes. |
| iOS GoogleSignIn SDK Pod fails to resolve in CI | Medium at first integration | High (blocks staging smoke) | Mobile #1 already ships the iOS framework path + CocoaPods integration; this change adds one Pod. Smoke step exercises the link task. If CI fails, manual iOS workstation build covers the smoke until CI catches up. |
| Tink + DataStore master-key lifecycle bug on Android re-install / data-clear | Medium | Medium (user can re-sign-in, no data loss) | Test scenario: simulate `clear app data` → on next launch the store is empty → `RootRouterScreen` routes to `SignInScreen` → user re-signs-in. Verified in `tasks.md` Section 6 acceptance tests. Tink's `AndroidKeysetManager` handles the keyset-corrupted case by regenerating; recurring corruption surfaces as Sentry warning. |
| 401-refresh-fails infinite loop | Low | High (DoS) | Ktor `Auth` plugin's `refreshTokens` returning `null` is the terminal state — no further refresh attempts. `AuthRepository` catches the post-refresh-fail 401 and immediately store-clears + re-routes. Test scenario covers this. |
| API base URL placeholder leaks into production build | Low (gated by gradle config) | High (broken production) | `production` flavor sets URL to a deliberately-broken placeholder (`https://api.nearyou.id.PLACEHOLDER`); production build itself isn't shipped until production infra is provisioned, at which point a production-specific change (or a follow-up `mobile-production-base-url-wire-up`) flips the placeholder to the real URL. |
| Mobile-side commonTest can't easily exercise Credential Manager / GoogleSignIn iOS SDK | High (commonTest can't mock native APIs) | Medium (lower test coverage on platform-binding paths) | Acceptance: commonTest mocks `GoogleSignInClient` via expect/actual test-actual that returns canned `GoogleSignInResult.Success / UserCancelled / Failed`. Platform-actual code path is exercised by the §10 manual smoke (real Google account on test device). Document this trade-off in `tasks.md` Section 6. |
| Apple App Store / Play Store review rejects build for missing privacy manifest entries | Medium (iOS 17+ requires PrivacyInfo.xcprivacy) | Medium (blocks store submission, not function) | Mobile #3 ships before any store submission; PrivacyInfo.xcprivacy + Android Data Safety form land with the launch-readiness change closer to soft launch. `docs/03-UX-Design.md` § iOS Privacy Manifest already notes this. Not a Mobile #3 blocker. |
| User-facing copy diverges from `docs/03-UX-Design.md` canonical wording | Low | Low (copy text only) | Spec scenarios assert exact strings; Phase D multi-lens review validates against `docs/03-UX-Design.md` quotes. |

## Migration Plan

This change introduces a brand-new flow — there is no existing mobile sign-in to migrate from. Deploy path:

1. **Branch + commits land on `mobile-auth-google-signin-flow` branch** per `openspec/project.md` § Change Delivery Workflow.
2. **CI green** on `ktlintCheck` + `detekt` + `:backend:ktor:test` + `:lint:detekt-rules:test` + Android assemble + iOS link.
3. **Pre-archive staging smoke** per `tasks.md` Section 10: install the staging-flavored APK on a test device → tap "Masuk dengan Google" → complete Google ceremony with a real Google account → verify backend logs show `/signin` 200 with the expected Google ID token sub-hash → kill + relaunch app → verify token persisted (skips sign-in screen, lands on Home). Run banned-user smoke (test user pre-flipped `is_banned = TRUE` in staging Supabase) → verify 403 + banner copy. Run 404 smoke (test Google account that has no existing user row) → verify 404 + "no registration" copy.
4. **Archive commit lands** + `openspec validate --strict` green.
5. **Squash-merge** → main-branch staging auto-deploy (backend unaffected; mobile build artifacts not auto-deployed because mobile is not a Cloud Run target — distribution happens via Play Console / TestFlight when those land).

**No rollback story needed**: mobile builds are not auto-deployed; any version pulled from a store gets the working flow. Backend behavior unchanged.

## Open Questions

1. **OAuth client ID provisioning per environment.** Mobile #3 ships the code but the actual Google Cloud OAuth client IDs (one per Android flavor + one per iOS scheme) need to be created in the Google Cloud Console under the existing `nearyou-staging` GCP project (and eventually `nearyou-production`). `tasks.md` Section 7 documents the procedure; the IDs themselves are stored in Secret Manager + injected into gradle/xcconfig at build time. Open question: should the IDs be committed to the repo (low-risk per public-repo posture — Android OAuth client IDs are not secrets, the SHA-1 signing cert binding is the security boundary) or pulled from Secret Manager at build time? Recommend: commit Android IDs (matches industry practice), pull iOS IDs at build time (iOS Google SDK reads from `GoogleService-Info.plist` which IS sensitive when paired with the bundle ID). Resolve at design review.
2. **Sign-in screen visual design.** No Figma mock for the screen yet. Default to a minimal layout (brand logo top + headline + "Masuk dengan Google" outlined button mid-screen + account-separation-disclosure footnote). Resolve via Phase D review or post-implementation iteration.
3. **`signin_error_no_account` copy precedence.** docs/03-UX-Design.md doesn't have canonical copy for this case (because UX-doc assumes signup is wired). Decision 7 proposes "Akun belum terdaftar. Daftar dulu lewat pembaruan aplikasi berikutnya." — open to alternatives at review.
