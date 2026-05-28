## 1. Spec validation + scaffold prep

- [ ] 1.1 Verify proposal + design + specs render correctly: run `openspec validate mobile-auth-google-signin-flow --strict` and confirm zero errors
- [ ] 1.2 **Pre-implementation library re-check** per `openspec/project.md` § Change Delivery Workflow — run dated WebSearches: "Credential Manager API Android 2026", "Google Sign-In iOS SDK KMP 2026", "Ktor 3 KMP client 2026 best practice", "EncryptedSharedPreferences deprecation status 2026"; drop a one-liner in the first feat commit body recording the verification (e.g., `re-check 2026-MM-DD confirms: DataStore + Tink remain canonical per Android Developers reference + 2026 migration guide`). If any search surfaces a material substrate shift, STOP and surface to the user before continuing.
- [ ] 1.3 Confirm branch `mobile-auth-google-signin-flow` is checked out from latest `main` AND `git status` shows clean working tree (proposal artifacts already committed)
- [ ] 1.4 Read `openspec/specs/auth-signin/spec.md`, `openspec/specs/auth-jwt/spec.md`, and `openspec/specs/auth-session/spec.md` end-to-end before implementing to internalize the canonical backend contract

## 2. libs.versions.toml + build-config wiring

- [ ] 2.1 Add new `[versions]` entries to `gradle/libs.versions.toml`: `androidx-credentials`, `androidx-credentials-googleid`, `androidx-datastore`, `google-tink`, `ktor-client-kmp = ` (or reuse existing `ktor = "3.4.1"`)
- [ ] 2.2 Add new `[libraries]` entries: `androidx-credentials`, `androidx-credentials-playServicesAuth`, `googleid` (`com.google.android.libraries.identity.googleid:googleid`), `androidx-datastore-preferences`, `google-tink` (`com.google.crypto.tink:tink-android`), `ktor-client-core` (non-`-jvm`), `ktor-client-okhttp` (non-`-jvm`), `ktor-client-darwin`, `ktor-client-contentNegotiation` (non-`-jvm`), `ktor-serializationKotlinxJson` (non-`-jvm`), `ktor-client-auth`, `ktor-client-logging`, `ktor-client-mock` (non-`-jvm`, for tests)
- [ ] 2.3 Add new entries to `docs/09-Versions.md` Version Decisions table — one row per pin in 2.1 + 2.2 documenting: pin date 2026-MM-DD, rationale (citing this change), next review quarter
- [ ] 2.4 Update `mobile/app/build.gradle.kts`: add product flavors `dev`, `staging`, `production` under `android { productFlavors { ... } }`; each flavor calls `buildConfigField("String", "API_BASE_URL", "\"<url>\"")` — dev: `"http://10.0.2.2:8080"`, staging: `"https://api-staging.nearyou.id"`, production: `"https://api.nearyou.id.PLACEHOLDER"`
- [ ] 2.5 Enable BuildConfig generation in `mobile/app/build.gradle.kts`: add `android { buildFeatures { buildConfig = true } }`
- [ ] 2.6 Add commonMain dependencies to `mobile/app/build.gradle.kts`: `ktor-client-core`, `ktor-client-contentNegotiation`, `ktor-serializationKotlinxJson`, `ktor-client-auth`, `ktor-client-logging` + `kotlinx-serialization-json` (already pinned)
- [ ] 2.7 Add androidMain dependencies: `androidx-credentials`, `androidx-credentials-playServicesAuth`, `googleid`, `androidx-datastore-preferences`, `google-tink`, `ktor-client-okhttp`
- [ ] 2.8 Add iosMain dependencies: `ktor-client-darwin`. Configure CocoaPods integration: in `kotlin { cocoapods { ... pod("GoogleSignIn") { version = "<latest-stable>" } } }` (or the equivalent if `cocoapods` plugin isn't yet applied — apply it as part of this task and document in design.md if needed)
- [ ] 2.9 Add commonTest dependency: `ktor-client-mock`
- [ ] 2.10 Run `./gradlew :mobile:app:dependencies | head -200` and verify each new entry resolves; commit "feat(mobile): add Mobile #3 substrate pins (Ktor KMP client, Credential Manager, DataStore+Tink, GoogleSignIn iOS)" with WebSearch verification note in body

## 3. SecureTokenStore expect/actual + tests

- [ ] 3.1 Create `mobile/app/src/commonMain/kotlin/id/nearyou/app/auth/SecureTokenStore.kt` declaring `expect class SecureTokenStore { suspend fun read(): TokenPair?; suspend fun write(tokens: TokenPair); suspend fun clear() }` + the `TokenPair` data class
- [ ] 3.2 Create `mobile/app/src/androidMain/kotlin/id/nearyou/app/auth/SecureTokenStore.kt` implementing the Android actual via DataStore Preferences + Tink AEAD; the Tink keyset is wrapped via `AndroidKeysetManager` keyed off an Android-Keystore-derived master key alias `nearyou_auth_tokens_master_key`
- [ ] 3.3 Create `mobile/app/src/iosMain/kotlin/id/nearyou/app/auth/SecureTokenStore.kt` implementing the iOS actual via Keychain Services (`SecItemAdd` / `SecItemUpdate` / `SecItemCopyMatching` / `SecItemDelete`) with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`; service identifier `id.nearyou.app.auth`, account `tokens`
- [ ] 3.4 Write commonTest `SecureTokenStoreContractTest` exercising the round-trip + clear contract against a test-actual that delegates to a backing in-memory map (the platform actuals are exercised by §10 smoke); spec scenarios "write-then-read round-trip" + "clear-then-read returns null" map to test cases
- [ ] 3.5 Write Android-instrumented integration test exercising the real DataStore + Tink path (`androidTest` source set or commonTest with Robolectric — pick per project convention) verifying tokens encrypt at rest (raw file content should not contain the access-token string literal)
- [ ] 3.6 Run `./gradlew :mobile:app:assembleDebug` to confirm Android compile + `./gradlew :mobile:app:linkPodDebugFrameworkIosSimulatorArm64` to confirm iOS link; commit "feat(mobile): SecureTokenStore expect/actual + tests"

## 4. GoogleSignInClient expect/actual + tests

- [ ] 4.1 Create `mobile/app/src/commonMain/kotlin/id/nearyou/app/auth/GoogleSignInClient.kt` declaring `expect class GoogleSignInClient { suspend fun signIn(): GoogleSignInResult }` + the sealed `GoogleSignInResult` type with `Success` / `UserCancelled` / `Failed` variants per spec
- [ ] 4.2 Create `mobile/app/src/androidMain/kotlin/id/nearyou/app/auth/GoogleSignInClient.kt` implementing the Android actual via Credential Manager: construct `GetGoogleIdOption.Builder()` (server client ID from BuildConfig / Resources), call `CredentialManager.create(context).getCredential(...)`, extract Google ID token from the `Credential.data` bundle, return `Success(idToken, displayName, email)`; map `GetCredentialCancellationException` → `UserCancelled`; map other `GetCredentialException` → `Failed(message)`
- [ ] 4.3 Create `mobile/app/src/iosMain/kotlin/id/nearyou/app/auth/GoogleSignInClient.kt` implementing the iOS actual via GoogleSignIn SDK: call `GIDSignIn.sharedInstance.signIn(withPresenting:)` on the root view controller; wrap in `suspendCancellableCoroutine` so the completion handler converts to the sealed result type
- [ ] 4.4 Write commonTest using a test-friendly `GoogleSignInClient` test-actual (NOT `expect actual` — a separate `FakeGoogleSignInClient` injected via Koin in tests) verifying the sealed-result contract maps as expected; spec scenarios "UserCancellation produces UserCancelled" map to test cases
- [ ] 4.5 Document in design.md (or a new `IMPLEMENTATION_NOTES.md` if preferred) why commonTest can't exercise the real platform paths — see Risks table row "Mobile-side commonTest can't easily exercise Credential Manager / GoogleSignIn iOS SDK"
- [ ] 4.6 Run `./gradlew :mobile:app:assembleDebug :mobile:app:linkPodDebugFrameworkIosSimulatorArm64`; commit "feat(mobile): GoogleSignInClient expect/actual (Credential Manager Android + GoogleSignIn iOS) + tests"

## 5. AuthApiClient + Ktor HttpClient + DTOs

- [ ] 5.1 Create `mobile/app/src/commonMain/kotlin/id/nearyou/app/config/ApiBaseUrl.kt` declaring `expect val apiBaseUrl: String`
- [ ] 5.2 Create `mobile/app/src/androidMain/kotlin/id/nearyou/app/config/ApiBaseUrl.kt` returning `BuildConfig.API_BASE_URL`
- [ ] 5.3 Create `mobile/app/src/iosMain/kotlin/id/nearyou/app/config/ApiBaseUrl.kt` returning `NSBundle.mainBundle.objectForInfoDictionaryKey("ApiBaseUrl") as String`
- [ ] 5.4 Create `mobile/app/src/commonMain/kotlin/id/nearyou/app/auth/AuthApiClient.kt` defining `@Serializable` DTOs: `SignInRequest(provider: String, idToken: String)`, `SignInResponse(accessToken: String, refreshToken: String, expiresIn: Int)`, `RefreshRequest(refreshToken: String)`, `BackendErrorBody(error: ErrorEnvelope)`, `ErrorEnvelope(code: String)` — use `@SerialName` to match snake_case wire format
- [ ] 5.5 Create the shared `HttpClient` factory in commonMain (`mobile/app/src/commonMain/kotlin/id/nearyou/app/network/HttpClientFactory.kt`) wiring `ContentNegotiation(Json { ignoreUnknownKeys = true; explicitNulls = false })`, `DefaultRequest { url(apiBaseUrl) }`, `Auth { bearer { loadTokens { ... SecureTokenStore.read() ... } ; refreshTokens { ... POST /auth/refresh ... } } }`, `Logging` plugin gated by debug-build check
- [ ] 5.6 Create the engine factory expect/actual: `expect fun httpClientEngine(): HttpClientEngineFactory<*>` returning `OkHttp` on Android and `Darwin` on iOS
- [ ] 5.7 Register the `HttpClient` as a Koin singleton in `mobile/app/src/commonMain/kotlin/id/nearyou/app/di/MobileModule.kt`; also register the `AuthApiClient` and `SecureTokenStore` as singletons
- [ ] 5.8 Write commonTest `AuthApiClientTest` using `MockEngine`: verify (a) signin request body shape `{provider, id_token}` with no `device_fingerprint_hash` key (spec scenario "signin request body does not carry device_fingerprint_hash"), (b) bearer-token attachment on subsequent requests, (c) 401 → refresh → retry happy path, (d) 401 → refresh-fails (refresh returns 401) → terminal 401 surfaced to caller, (e) network failure surfaces as exception
- [ ] 5.9 Run `./gradlew :mobile:app:assembleDebug :mobile:app:linkPodDebugFrameworkIosSimulatorArm64 :mobile:app:commonTest`; commit "feat(mobile): AuthApiClient + Ktor HttpClient with bearer-refresh + tests"

## 6. SignInScreen + RootRouterScreen + AuthRepository

- [ ] 6.1 Create `mobile/app/src/commonMain/kotlin/id/nearyou/app/auth/AuthRepository.kt` with `signInWithGoogle(): SignInOutcome`, `isAuthenticated(): Boolean`, `handleTerminal401()`; the sealed `SignInOutcome` type covers `Success` / `NoAccount` / `Banned` / `InvalidIdToken` / `NetworkError` / `Cancelled`
- [ ] 6.2 Implement the error-mapping logic per Decision 7: `404 user_not_found` → `NoAccount`, `403 account_banned` → `Banned`, `401 invalid_id_token` → re-invoke `GoogleSignInClient.signIn()` once + retry, else `InvalidIdToken` on second-401, `5xx`/IOException → `NetworkError`, `GoogleSignInResult.UserCancelled` → `Cancelled`
- [ ] 6.3 Register `AuthRepository` as a Koin singleton in `mobileModule`
- [ ] 6.4 Create `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/auth/SignInScreen.kt` — a Voyager `Screen` implementation rendering the brand logo + `signin_screen_title` + Google CTA + disclosure footnote; CTA invokes `AuthRepository.signInWithGoogle()` via `koinInject<AuthRepository>()`; error banner renders the `SignInOutcome`-driven message; CTA state (label / enabled) follows the outcome state per Decision 7
- [ ] 6.5 Create `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/RootRouterScreen.kt` reading `AuthRepository.isAuthenticated()` in a `LaunchedEffect`; route via `navigator.replaceAll(HomeScreen)` or `navigator.replaceAll(SignInScreen)`; render splash composition during the check
- [ ] 6.6 Update `mobile/app/src/commonMain/kotlin/id/nearyou/app/App.kt` to use `RootRouterScreen` as the Voyager start destination instead of `HomeScreen`
- [ ] 6.7 Write commonTest `SignInScreenTest` using `runComposeUiTest` verifying: (a) initial render shows the CTA + title + disclosure (per spec scenarios); (b) tapping CTA invokes `AuthRepository.signInWithGoogle()` via a Koin-injected mock; (c) `NoAccount` outcome renders the no-account banner; (d) `Banned` outcome renders the banned banner + disables CTA; (e) `NetworkError` outcome renders the retry CTA label
- [ ] 6.8 Write commonTest `RootRouterScreenTest` verifying: (a) authenticated state routes to `HomeScreen`; (b) unauthenticated state routes to `SignInScreen`; (c) splash renders during the in-flight check
- [ ] 6.9 Run `./gradlew :mobile:app:assembleDebug :mobile:app:linkPodDebugFrameworkIosSimulatorArm64 :mobile:app:commonTest`; commit "feat(mobile): SignInScreen + RootRouterScreen + AuthRepository + tests"

## 7. iOS Swift host integration

- [ ] 7.1 Add `GoogleService-Info.plist` (or equivalent OAuth client ID config) to the `iosApp/iosApp/` Xcode project — generated per-environment from Google Cloud Console under the existing `nearyou-staging` project; document the procedure in `tasks.md` Section 7 inline or in `dev/docs/google-cloud-oauth-clients.md`
- [ ] 7.2 Register the Google Sign-In OAuth callback URL scheme in `iosApp/iosApp/Info.plist` under `CFBundleURLTypes`: scheme matches the reversed client ID from `GoogleService-Info.plist`
- [ ] 7.3 Implement the URL-handling delegate in `iosApp/iosApp/iOSApp.swift` (or `AppDelegate` if introduced) — wire `.onOpenURL { url in GIDSignIn.sharedInstance.handle(url) }` (SwiftUI) OR `application(_:open:options:) -> Bool` returning `GIDSignIn.sharedInstance.handle(url)` (UIKit `AppDelegate`)
- [ ] 7.4 Add the `Staging.xcconfig` + `Production.xcconfig` files under `iosApp/iosApp/Configuration/` (or equivalent path) — each declares `APP_API_BASE_URL = <env-url>`; `Info.plist` declares `<key>ApiBaseUrl</key><string>$(APP_API_BASE_URL)</string>`
- [ ] 7.5 Add the staging + production scheme references in the Xcode project file (`.xcodeproj/project.pbxproj`) referencing the new xcconfig files; ensure default scheme remains `dev` (or equivalent local-development)
- [ ] 7.6 Verify build via Xcode (CLI: `xcodebuild -workspace iosApp/iosApp.xcworkspace -scheme iosApp -configuration Debug build`) — staging-configured build should bake `https://api-staging.nearyou.id` into the Info.plist
- [ ] 7.7 Document the OAuth client ID provisioning runbook (Google Cloud Console steps: create OAuth 2.0 Client ID for Android with SHA-1 of debug + release signing certs + bundle identifier; create separate OAuth 2.0 Client ID for iOS with bundle identifier; download `GoogleService-Info.plist` for iOS) — land as `dev/docs/google-cloud-oauth-clients.md` or as inline comments in `iosApp/iosApp/GoogleService-Info.plist.template`
- [ ] 7.8 Commit "feat(mobile): iOS GoogleSignIn host integration (URL scheme, xcconfig, OAuth client ID config)"

## 8. shared-resources string additions

- [ ] 8.1 Add the 8 Mobile #3 strings (`cta_signin_google`, `signin_screen_title`, `signin_error_no_account`, `signin_error_banned`, `signin_error_network`, `signin_error_token_invalid`, `signin_loading`, `account_separation_disclosure`) to `shared/resources/src/commonMain/composeResources/values/strings.xml` per spec text-content scenarios
- [ ] 8.2 Verify the 10 Mobile #2 / #2.5 strings remain byte-identical to current shipped content (no rewrite); rebuild `:shared:resources` and verify `Res.string.<name>` accessors compile for both new + existing entries
- [ ] 8.3 Update `openspec/specs/shared-resources/spec.md` IF the archive workflow expects it (per delta merge mechanics; check by running `openspec validate mobile-auth-google-signin-flow --strict` after specs update — passing the delta spec is sufficient at this phase)
- [ ] 8.4 Run `./gradlew :shared:resources:assemble` (or equivalent) to verify codegen + commit "feat(shared:resources): add Mobile #3 sign-in flow strings"

## 9. Static checks + grep-verifications updated

- [ ] 9.1 Update the negative-requirement grep verifications in `tasks.md` Section 9 (this very file — yes, recursively) to reflect the lifted scenarios: SignIn / GoogleId / signin / etc. identifiers are permitted inside the carved-out paths (`auth/**`, `screens/auth/**`, `screens/routing/**`, `config/**`); AppleAuth + FCM identifiers + direct HTTP-client usage remain forbidden everywhere
- [ ] 9.2 Run `./gradlew ktlintCheck detekt` and resolve any ktlint / Detekt findings
- [ ] 9.3 Run `./gradlew :backend:ktor:test :lint:detekt-rules:test` and confirm no regressions
- [ ] 9.4 Run `./gradlew :mobile:app:assembleDebug` for each flavor: `assembleDevDebug`, `assembleStagingDebug`, `assembleProductionDebug` — all three SHOULD assemble (production with the placeholder URL doesn't need to run, just build)
- [ ] 9.5 Run `./gradlew :mobile:app:linkPodDebugFrameworkIosSimulatorArm64` to verify iOS framework link
- [ ] 9.6 Run the auth-flow grep verification: `grep -rEi "SignIn|GoogleId|signin|googleSignIn|idToken|accessToken|refreshToken|authToken|JwtToken|Authenticator|oauthClient|loginClient|loginFlow" mobile/app/src/{commonMain,androidMain,iosMain}` and confirm every match resides under `auth/**`, `screens/auth/**`, `screens/routing/**`, or `config/**`; if a match leaks outside, either move it or treat as scope creep
- [ ] 9.7 Run the Apple-Sign-In grep verification: `grep -rEi "AppleAuth|appleSignIn|apple_sign_in|ASAuthorization" mobile/app/src/` — expect zero matches
- [ ] 9.8 Run the FCM grep verification: `grep -rEi "FirebaseMessaging|fcmToken|fcm_token|registerFcmToken|messaging\.token|pushToken|notificationToken" mobile/app/src/` — expect zero matches
- [ ] 9.9 Run the API-base-URL grep verification: `grep -rEi "nearyou\.id|api-staging|api\.nearyou" mobile/app/src/` — every match resides under `mobile/app/src/{commonMain,androidMain,iosMain}/kotlin/id/nearyou/app/config/**`
- [ ] 9.10 Commit any straggler ktlint / Detekt / grep-verification fixes; do NOT commit `--no-verify` — diagnose root cause

## 10. Pre-archive staging deploy + manual smoke

- [ ] 10.1 Confirm backend `/api/v1/auth/signin`, `/api/v1/auth/refresh`, and `/.well-known/jwks.json` are reachable on staging (`curl -i https://api-staging.nearyou.id/health/live` should be 200)
- [ ] 10.2 Provision the staging Android OAuth client ID in Google Cloud Console under the `nearyou-staging` project — SHA-1 of debug + release Android signing certs + bundle identifier `id.nearyou.app.staging` (or equivalent flavor-suffixed ID)
- [ ] 10.3 Provision the staging iOS OAuth client ID in Google Cloud Console — bundle identifier `id.nearyou.app.staging` (or whatever the staging xcconfig sets)
- [ ] 10.4 Build + install the staging-flavored Android APK on a test device (`./gradlew :mobile:app:installStagingDebug`); tap "Masuk dengan Google"; complete the Google ceremony with a real Google account that DOES have an existing `users` row in staging Supabase (pre-seed via `dev/scripts/promote-staging-user.sh` or equivalent); verify the navigation lands on `HomeScreen`; kill + relaunch the app; verify it skips `SignInScreen` (token persisted)
- [ ] 10.5 Build + install the staging iOS app on a test device or simulator; repeat the sign-in flow; verify token persists across app relaunch
- [ ] 10.6 Banned-user smoke: in staging Supabase, set `users.is_banned = TRUE` for a test user; sign in with that user's Google account on the Android build; verify the response is 403 + the banner copy matches `signin_error_banned`; verify CTA is disabled
- [ ] 10.7 No-account smoke: sign in with a Google account that has NO row in staging `users`; verify the response is 404 + the banner copy matches `signin_error_no_account`; verify the user remains on `SignInScreen`
- [ ] 10.8 Network-failure smoke: enable airplane mode mid-flow; verify the banner copy matches `signin_error_network` + the CTA label changes to "Coba lagi"
- [ ] 10.9 Refresh-token rotation smoke: capture the access token + refresh token from a successful sign-in; manually expire the access token (or wait 15 min); make an authenticated request via the app (e.g., navigate to a screen that would call backend); verify backend `/auth/refresh` is called once + the original request succeeds with a fresh token
- [ ] 10.10 Refresh-token-reuse smoke: capture refresh token from one device; use it on a second device; on the first device, the next refresh attempt should produce `token_reuse_detected` 401; verify the app handles this by clearing the store + routing to SignInScreen (per spec scenario "Refresh failure produces terminal 401 + store cleared")
- [ ] 10.11 Document smoke results: record token-version (claim parsed from JWT), Cloud Logging entries for the `/signin` + `/refresh` requests, and screenshots of each error state. Land notes inline in this `tasks.md` Section 10 OR in a `dev/notes/mobile-#3-smoke-2026-MM-DD.md` file
- [ ] 10.12 Tick checkboxes 10.1-10.11 individually in the apply commit OR in a follow-up commit "chore(mobile): mobile-#3 staging smoke complete"

## 11. FOLLOW_UPS.md additions + docs/03-UX-Design.md divergence flag

- [ ] 11.1 Add `mobile-auth-signin-apple-ios` entry to `FOLLOW_UPS.md` — covers swapping iOS primary to Apple Sign-In per `docs/03-UX-Design.md` § Auth Flow line 38; references the user's "Google on both" decision at `/next-change` Phase A.4 as the trigger for the deferral
- [ ] 11.2 Add `mobile-auth-signin-404-route-to-age-gate` entry — covers replacing the temporary `signin_error_no_account` copy with navigation to `AgeGateScreen` (the Mobile #4 outcome)
- [ ] 11.3 Add `mobile-auth-signin-attestation-fingerprint-hash` entry — covers adding the `device_fingerprint_hash` body field when attestation lands per `docs/06-Security-Privacy.md` § Attestation
- [ ] 11.4 Add `mobile-auth-signin-logout-wire-up` entry — covers wiring `POST /api/v1/auth/logout` + `/logout-all` when the Settings screen ships
- [ ] 11.5 Add `mobile-auth-signin-credential-manager-legacy-fallback` entry — covers adding the deprecated `GoogleSignInClient` fallback for older Android devices where Credential Manager fails (per propose-time WebSearch finding); trigger is user reports / Sentry signal showing repeated `GoogleSignInResult.Failed` rate above threshold
- [ ] 11.6 Add `docs-ios-primary-auth-mobile-3-vs-eventual-state` entry — covers amending TWO canonical docs to acknowledge Mobile #3 ships Google iOS as a substrate-proving stopgap before Apple Sign-In iOS lands: (a) [`docs/03-UX-Design.md`](../../../docs/03-UX-Design.md) § Auth Flow line 38 ("iOS: Masuk dengan Apple (primary, user-facing)") and (b) [`docs/04-Architecture.md`](../../../docs/04-Architecture.md) § Tech Stack table line 15 ("Auth | Google Sign-In (Android Credential Manager) + Apple Sign-In (backend verify + ...)"). Both lines are correct as the eventual-state but the staged delivery makes them temporarily misleading. The amendment should add a Mobile #3 status-tag note pointing to the eventual swap, NOT remove the Apple-iOS prescription
- [ ] 11.7 Commit "docs(follow-ups): Mobile #3 deferred work + UX-doc divergence"

## 12. PR refresh + archive prep

- [ ] 12.1 Update the PR description per `openspec/project.md` § "PR title and body MUST stay current at every phase boundary" — retitle the PR via `gh pr edit <pr> --title 'feat(mobile): mobile-auth-google-signin-flow'`; update body to "Status: implementation complete; ready for archive" listing the section-by-section progress, capability deltas, test counts, and pre-archive smoke results
- [ ] 12.2 Re-run `openspec validate mobile-auth-google-signin-flow --strict` after final implementation commits + confirm green
- [ ] 12.3 Pre-archive smoke per `openspec/project.md` § "Staging deploy timing": dispatch `gh workflow run deploy-staging.yml --ref mobile-auth-google-signin-flow` for ANY backend impact verification — Mobile #3 has no backend impact so this step is N/A (mark explicitly N/A in the archive commit body)
- [ ] 12.4 Post `/review` PR comment to trigger qodo review (Manual mode per `openspec/project.md` § Qodo dashboard prerequisite); address feedback iteratively per `/opsx:apply` step 8
- [ ] 12.5 Once all sections green, qodo blocking findings addressed, and pre-archive smoke recorded: invoke `/opsx:archive mobile-auth-google-signin-flow` to land the archive commit (moves `openspec/changes/mobile-auth-google-signin-flow/` under `archive/` + applies spec deltas to canonical `openspec/specs/**`)
- [ ] 12.6 Squash-merge the PR to `main` per the one-PR-per-change convention; verify the resulting single commit on `main` carries the full proposal + feat + archive history in the squash body
