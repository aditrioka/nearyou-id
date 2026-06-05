# mobile-auth-signin Specification

## Purpose
Defines the first end-to-end mobile authentication flow for `:mobile:app` (Android + iOS, Kotlin Multiplatform): "Masuk dengan Google" on `SignInScreen` → the platform Google Sign-In ceremony → exchange of the Google ID token for the backend's RS256 access + refresh pair via `POST /api/v1/auth/signin` → encrypted token persistence (Android DataStore + Tink AEAD; iOS Keychain) → token-gated `RootRouterScreen` routing to `HomeScreen`, with a shared Ktor `HttpClient` Bearer interceptor handling once-per-request 401-refresh rotation. It specifies the `GoogleSignInClient` and `SecureTokenStore` expect/actual interfaces, the `AuthRepository` backend-status → user-facing-copy mapping, and environment-aware API base URL resolution. This capability is the substrate every downstream authenticated mobile surface depends on (Mobile #4 age gate, Mobile #5 nearby timeline, all Phase 3+ feature screens); the backend `/signin` endpoint shipped 2026-04-20 (`auth-foundation`) but had no mobile caller until this change wired it on both platforms.
## Requirements
### Requirement: SignInScreen renders Google Sign-In entry point

The mobile app SHALL ship a composable `SignInScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/auth/SignInScreen.kt`), mapped from the `SignInRoute` `NavKey` by the `entryProvider`, that renders the unauthenticated entry surface. The screen SHALL display: (a) the brand logo via `painterResource(Res.drawable.logo_brand_{light,dark})` (theme-aware per `isSystemInDarkTheme()` consistent with [`HomeScreen`](../../../../mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt)); (b) a screen title consumed via `stringResource(Res.string.signin_screen_title)`; (c) a primary call-to-action button consumed via `stringResource(Res.string.cta_signin_google)`; (d) a footnote consumed via `stringResource(Res.string.account_separation_disclosure)`. No hardcoded UI string literals SHALL appear in the screen source.

#### Scenario: Initial render shows the Google Sign-In CTA

- **WHEN** a `commonTest` runs `runComposeUiTest { setContent { NearYouTheme { SignInScreen(...) } } }` against a fresh composition with no in-flight auth state
- **THEN** the rendered tree contains a node whose text matches the runtime value of `stringResource(Res.string.cta_signin_google)` (i.e., "Masuk dengan Google") AND the node is clickable

#### Scenario: Initial render shows the screen title and disclosure

- **WHEN** `SignInScreen` is composed
- **THEN** the rendered tree contains a node whose text matches the runtime value of `stringResource(Res.string.signin_screen_title)` AND a node whose text matches the runtime value of `stringResource(Res.string.account_separation_disclosure)`

#### Scenario: No hardcoded UI strings in SignInScreen source

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/auth/SignInScreen.kt`
- **THEN** every `Text(...)` / `contentDescription = ...` / similar UI-string-bearing call site sources its text via `stringResource(Res.string.<name>)` (Compose Multiplatform Resources accessor); zero literal string arguments appear in such call sites

#### Scenario: SignInScreen brand logo swaps on system-theme change at recomposition

- **GIVEN** `SignInScreen` is composed in light mode (`isSystemInDarkTheme() == false`) — the rendered brand logo node uses `Res.drawable.logo_brand_light`
- **WHEN** the system theme is toggled to dark mode AND the screen is recomposed
- **THEN** the rendered brand logo node uses `Res.drawable.logo_brand_dark` (no crash, no stale-logo retention); recomposition is triggered automatically because `isSystemInDarkTheme()` is observable Compose state

### Requirement: Google Sign-In ceremony via expect/actual GoogleSignInClient

The mobile app SHALL declare `expect class GoogleSignInClient { suspend fun signIn(): GoogleSignInResult }` in commonMain (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/auth/GoogleSignInClient.kt`) with a sealed result type:

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

The `androidMain` actual SHALL implement the ceremony via the **Credential Manager API** (`androidx.credentials.CredentialManager` + the `com.google.android.libraries.identity.googleid:googleid` `GetGoogleIdOption` helper); it MUST NOT use the deprecated `com.google.android.gms.auth.api.signin.GoogleSignInClient`. The `iosMain` actual SHALL implement the ceremony via the **Google Sign-In iOS SDK** (`GoogleSignIn` Pod integrated via CocoaPods, calling `GIDSignIn.sharedInstance.signIn(withPresenting:)`); it MUST NOT use a webview-based OAuth flow.

Both actuals SHALL be coroutine-suspending wrappers (via `suspendCancellableCoroutine` or `withContext(Dispatchers.Main) { ... }` as appropriate) returning a `GoogleSignInResult` from the platform-specific callback/completion-handler shape.

#### Scenario: GoogleSignInClient interface is declared in commonMain

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/auth/GoogleSignInClient.kt`
- **THEN** the file declares `expect class GoogleSignInClient` with a `suspend fun signIn(): GoogleSignInResult` member; the sealed result type `GoogleSignInResult` is also declared with the three documented variants

#### Scenario: Android actual uses Credential Manager

- **WHEN** inspecting `mobile/app/src/androidMain/kotlin/id/nearyou/app/auth/GoogleSignInClient.kt` (or equivalent `androidMain` path)
- **THEN** the actual class implementation contains references to `androidx.credentials.CredentialManager` AND `com.google.android.libraries.identity.googleid.GetGoogleIdOption` (the modern Credential Manager + GoogleID helper path); the file contains NO references to `com.google.android.gms.auth.api.signin.GoogleSignInClient` or `GoogleSignInOptions` (the deprecated legacy path)

#### Scenario: iOS actual uses GoogleSignIn SDK

- **WHEN** inspecting `mobile/app/src/iosMain/kotlin/id/nearyou/app/auth/GoogleSignInClient.kt` (or equivalent `iosMain` path)
- **THEN** the actual class implementation contains references to `cocoapods.GoogleSignIn` (or the equivalent KMP cinterop binding for the GoogleSignIn iOS SDK); the file contains NO references to `WKWebView` / `SFSafariViewController` (webview-based OAuth fallback paths)

#### Scenario: User-cancellation produces UserCancelled, not Failed

- **GIVEN** a commonTest-friendly stub `GoogleSignInClient` impl that simulates the user dismissing the Google Sign-In sheet
- **WHEN** the suspending `signIn()` call returns
- **THEN** the returned value is `GoogleSignInResult.UserCancelled` (the singleton `data object`), NOT `GoogleSignInResult.Failed(...)` (cancellation is not an error)

#### Scenario: iOS URL handler verifies GIDSignIn.handle(url) Bool return value

- **WHEN** inspecting `iosApp/iosApp/iOSApp.swift` (or the equivalent SwiftUI / `AppDelegate` URL handler hook)
- **THEN** the OAuth callback handler invokes `GIDSignIn.sharedInstance.handle(url)` AND captures + returns its `Bool` return value (in `application(_:open:options:)` form) OR uses the SwiftUI `.onOpenURL { url in let handled = GIDSignIn.sharedInstance.handle(url); ... }` pattern that branches on `handled`; the handler MUST NOT discard the return value (a `false` return signals the URL was NOT a GoogleSignIn callback and SHOULD be propagated to subsequent URL handlers — silently swallowing `false` would mask a future URL-scheme misconfiguration)

#### Scenario: iOS Info.plist CFBundleURLTypes uses reversedClientId scheme

- **WHEN** inspecting `iosApp/iosApp/Info.plist`
- **THEN** the file contains a `CFBundleURLTypes` array entry whose `CFBundleURLSchemes` value matches the `REVERSED_CLIENT_ID` from the staging (or production) `GoogleService-Info.plist` — a string of shape `com.googleusercontent.apps.<NNN>-<XYZ>` (bundle-ID-derived, not an arbitrary custom scheme); arbitrary custom schemes (e.g., `nearyou-auth://`) MUST NOT be registered for the Google Sign-In callback path

### Requirement: SecureTokenStore persists tokens at rest with platform-specific encryption

The mobile app SHALL declare `expect class SecureTokenStore { suspend fun read(): TokenPair?; suspend fun write(tokens: TokenPair); suspend fun clear() }` in commonMain (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/auth/SecureTokenStore.kt`) where `TokenPair` is `data class TokenPair(val accessToken: String, val refreshToken: String, val accessExpiresAtEpochMillis: Long)`.

The `androidMain` actual SHALL persist `TokenPair` via **Preferences DataStore + Tink AEAD encryption** with the keyset wrapped by Android Keystore. It MUST NOT use the deprecated `androidx.security.crypto.EncryptedSharedPreferences` (deprecated as of `androidx.security:security-crypto:1.1.0-alpha07` per the official [Android Developers reference](https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences)).

The `iosMain` actual SHALL persist `TokenPair` via **Keychain Services** (Security framework — `SecItemAdd` / `SecItemUpdate` / `SecItemCopyMatching` / `SecItemDelete`) with accessibility attribute `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` (token usable after device unlock, NOT synced via iCloud Keychain, NOT survivable across device transfer).

Round-trip integrity MUST hold: `write(tokens)` followed by `read()` (potentially after process restart) MUST return a `TokenPair` equal to the written one; `clear()` followed by `read()` MUST return `null`.

#### Scenario: SecureTokenStore expect interface is declared in commonMain

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/auth/SecureTokenStore.kt`
- **THEN** the file declares `expect class SecureTokenStore` with `read()`, `write(tokens)`, and `clear()` suspend members AND the `TokenPair` data class with the three documented fields

#### Scenario: Android actual uses DataStore + Tink, NOT EncryptedSharedPreferences

- **WHEN** inspecting `mobile/app/src/androidMain/kotlin/id/nearyou/app/auth/SecureTokenStore.kt` (or equivalent `androidMain` path)
- **THEN** the actual class implementation contains references to `androidx.datastore.preferences.core.preferencesDataStore` (or equivalent DataStore API) AND `com.google.crypto.tink.aead.Aead` (or `AesGcmKeyManager`) AND `com.google.crypto.tink.integration.android.AndroidKeysetManager`; the file contains NO references to `androidx.security.crypto.EncryptedSharedPreferences` or `androidx.security.crypto.MasterKey` (the deprecated path)

#### Scenario: iOS actual uses Keychain Services with appropriate accessibility

- **WHEN** inspecting `mobile/app/src/iosMain/kotlin/id/nearyou/app/auth/SecureTokenStore.kt` (or equivalent `iosMain` path)
- **THEN** the actual class implementation contains references to `SecItemAdd` / `SecItemCopyMatching` AND uses `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` (NOT `kSecAttrAccessibleAlways` which would weaken security, NOT `kSecAttrAccessibleAfterFirstUnlock` without the `ThisDeviceOnly` suffix which would sync via iCloud Keychain)

#### Scenario: Android Tink keyset is constructed with Android Keystore master key URI, no user-authentication-required flag

- **WHEN** inspecting `mobile/app/src/androidMain/kotlin/id/nearyou/app/auth/SecureTokenStore.kt`
- **THEN** the `AndroidKeysetManager.Builder()` invocation specifies `.withMasterKeyUri("android-keystore://nearyou_auth_tokens_master_key")` (or the equivalent constant referencing the master-key alias) AND does NOT invoke `.withMasterKey(... setUserAuthenticationRequired(true) ...)` (which would lock the keyset behind a biometric / lockscreen unlock and break the post-reboot routing path described in the `RootRouterScreen` requirement)

#### Scenario: iOS Keychain item is single-bundle-scoped, no kSecAttrAccessGroup set

- **WHEN** inspecting `mobile/app/src/iosMain/kotlin/id/nearyou/app/auth/SecureTokenStore.kt`
- **THEN** the `SecItemAdd` query dictionary contains NO `kSecAttrAccessGroup` key (or sets it explicitly to the app's own bundle identifier prefix); a future watch-app or shared-app-group target SHALL NOT be able to read refresh tokens written by this app via the default keychain access group

#### Scenario: write-then-read round-trip preserves the TokenPair

- **GIVEN** a `SecureTokenStore` instance in a test environment
- **WHEN** `write(TokenPair("at-X", "rt-Y", 1234567890L))` is invoked AND a subsequent `read()` is invoked
- **THEN** the returned `TokenPair` equals `TokenPair("at-X", "rt-Y", 1234567890L)` field-by-field

#### Scenario: clear-then-read returns null

- **GIVEN** a `SecureTokenStore` instance containing a previously-written `TokenPair`
- **WHEN** `clear()` is invoked AND a subsequent `read()` is invoked
- **THEN** the returned value is `null`

### Requirement: Ktor HTTP client attaches Bearer token and handles 401 refresh

The mobile app SHALL construct a single `HttpClient` in `:mobile:app` commonMain (registered in the Koin `mobileModule`) configured with:

- The platform engine (`OkHttp` engine in `androidMain`, `Darwin` engine in `iosMain`).
- `ContentNegotiation` plugin with `Json { ignoreUnknownKeys = true }`.
- `DefaultRequest` plugin setting `url(apiBaseUrl)` from the `expect val apiBaseUrl: String` per Requirement: Environment-aware API base URL.
- `Auth` plugin with a `bearer { ... }` provider whose `loadTokens { ... }` reads from `SecureTokenStore` and whose `refreshTokens { ... }` invokes `POST /api/v1/auth/refresh` to obtain a new `BearerTokens(accessToken, refreshToken)` pair from the backend; on refresh failure (network error OR `401` response from the refresh endpoint), `refreshTokens` MUST return `null` so the Ktor `Auth` plugin surfaces a terminal `401` to the caller.
- `Logging` plugin — debug-build-gated (never installed in production builds) with `LogLevel.HEADERS` (NEVER `LogLevel.ALL`, which would log request bodies including the raw refresh-token body of `POST /api/v1/auth/refresh`) AND mandatory `sanitizeHeader { it.equals(HttpHeaders.Authorization, ignoreCase = true) }` per Decision 4. If the build is a release build, the Logging plugin is NOT installed at all (the spec scenario asserts behavior conditional on the plugin being installed).

The Ktor `Auth { bearer { ... } }` plugin's built-in request-queuing-during-refresh behavior is the canonical mechanism — handlers MUST NOT reimplement custom 401-retry logic.

#### Scenario: HttpClient is constructed in commonMain Koin module

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/di/MobileModule.kt` (or equivalent commonMain DI registration file)
- **THEN** the Koin module's bindings include a `single<HttpClient> { ... }` registration; the construction uses an `HttpClient(engine) { ... }` invocation where `engine` is resolved per platform via expect/actual or factory injection

#### Scenario: Auth bearer plugin is configured with loadTokens + refreshTokens

- **WHEN** inspecting the `HttpClient` construction code
- **THEN** the configuration block contains an `install(Auth) { bearer { ... } }` invocation; the `bearer` block declares both `loadTokens { ... }` (returning a `BearerTokens?` derived from `SecureTokenStore.read()`) AND `refreshTokens { ... }` (invoking `POST /api/v1/auth/refresh` and returning a fresh `BearerTokens?`)

#### Scenario: Successful request attaches Bearer token from store

- **GIVEN** a `SecureTokenStore` containing `TokenPair("at-X", "rt-Y", <future-epoch>)` AND a Ktor MockEngine-backed `HttpClient` matching the production configuration
- **WHEN** a request is issued via `client.get("/api/v1/posts")`
- **THEN** the captured outbound request's `Authorization` header equals `"Bearer at-X"`

#### Scenario: 401 response triggers refresh and retry with new token

- **GIVEN** `SecureTokenStore` contains `TokenPair("at-stale", "rt-Y", <past-epoch>)` AND the MockEngine responds 401 to the first request and 200 to the retried request
- **WHEN** a request is issued via `client.get("/api/v1/posts")`
- **THEN** the engine first observes a request with `Authorization: Bearer at-stale` (the stale token) AND then observes a `POST /api/v1/auth/refresh` call carrying `rt-Y` AND finally observes the retried `GET /api/v1/posts` with the freshly-issued access token; the final response status returned to the caller is 200

#### Scenario: Refresh failure produces terminal 401 + store cleared by AuthRepository

- **GIVEN** `SecureTokenStore` contains `TokenPair("at-stale", "rt-revoked", <past-epoch>)` AND the MockEngine responds 401 to the first request AND responds 401 to the subsequent refresh call (simulating `token_reuse_detected` per `auth-session` spec)
- **WHEN** the request is issued via the `AuthRepository`-wrapped client
- **THEN** `AuthRepository` observes the terminal 401, invokes `SecureTokenStore.clear()` (clearing BOTH access token AND refresh token AND expiration timestamp — not just the access token), AND emits a state event triggering `RootRouterScreen` to re-route to `SignInScreen`

#### Scenario: Concurrent 401s during in-flight refresh queue and retry once

- **GIVEN** `SecureTokenStore` contains `TokenPair("at-stale", "rt-Y", <past-epoch>)`; three concurrent requests in-flight (`GET /api/v1/posts`, `GET /api/v1/timeline`, `GET /api/v1/profile`); the MockEngine responds 401 to all three on first attempt AND responds 200 to `POST /api/v1/auth/refresh` (returning fresh tokens) AND responds 200 to each retry
- **WHEN** the three requests are issued in parallel via the `HttpClient`
- **THEN** exactly ONE `POST /api/v1/auth/refresh` call is observed (NOT three) AND all three original requests retry with the new access token AND all three final responses are 200

#### Scenario: Authorization header is sanitized in Ktor Logging plugin output

- **WHEN** inspecting the `HttpClient` construction code in `HttpClientFactory.kt`
- **THEN** the `install(Logging) { ... }` block (if present) sets `level = LogLevel.HEADERS` (NOT `LogLevel.ALL`, which would log request bodies including the raw refresh-token body of `POST /api/v1/auth/refresh`) AND configures `sanitizeHeader { header -> header.equals(HttpHeaders.Authorization, ignoreCase = true) }` (per Ktor 3's `sanitizeHeader` API) so that emitted log lines display `Authorization: ***` rather than `Authorization: Bearer <token>`; the Logging plugin install MUST be gated by a debug-build check so production builds do not install it at all

#### Scenario: App-backgrounded during in-flight /signin produces NetworkError on resumption

- **GIVEN** `AuthRepository.signInWithGoogle()` is in-flight after a successful `GoogleSignInClient.signIn()` AND the `POST /api/v1/auth/signin` call has been dispatched but no response has arrived
- **WHEN** the host app is backgrounded (Android process death or iOS scene phase change suspending the coroutine scope) AND subsequently resumed
- **THEN** the `AuthRepository.signInWithGoogle()` flow either (a) was cancelled by structured-concurrency on background-out and emits `SignInOutcome.Cancelled` on resumption (re-render to initial CTA-visible state, NO error banner), OR (b) on iOS where URLSession may complete in the background, the call resumes and emits the appropriate outcome per the actual backend response; in BOTH cases the user is never left on a permanent "Sedang masuk…" splash without a path forward

### Requirement: Environment-aware API base URL via expect/actual config

The mobile app SHALL declare `expect val apiBaseUrl: String` in commonMain (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/config/ApiBaseUrl.kt`). The Android actual SHALL read from a generated `BuildConfig.API_BASE_URL` field injected per gradle product flavor (`dev` / `staging` / `production`). The iOS actual SHALL read from `NSBundle.mainBundle.objectForInfoDictionaryKey("ApiBaseUrl")` driven by an xcconfig variable per scheme.

The `dev` flavor's URL field SHALL be `"http://10.0.2.2:8080"` (Android emulator host loopback) OR an equivalent local-development URL. The `staging` flavor's URL field SHALL be `"https://api-staging.nearyou.id"` (per [`openspec/project.md`](../../project.md) § Environments). The `production` flavor's URL field SHALL be a deliberately-broken placeholder value (e.g., `"https://api.nearyou.id.PLACEHOLDER"`) so a misconfigured production build fails fast; a future change replaces the placeholder when production infra is provisioned.

#### Scenario: apiBaseUrl is declared in commonMain config package

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/config/ApiBaseUrl.kt`
- **THEN** the file declares `expect val apiBaseUrl: String`

#### Scenario: Android flavors inject API_BASE_URL BuildConfig field

- **WHEN** inspecting `mobile/app/build.gradle.kts`
- **THEN** the `android { ... productFlavors { ... } }` block declares `dev`, `staging`, AND `production` flavors; each flavor sets `buildConfigField("String", "API_BASE_URL", "\"<env-specific-url>\"")` with the respective URL per the convention above; the staging flavor's URL equals `"https://api-staging.nearyou.id"` AND the production flavor's URL contains the substring `PLACEHOLDER`

#### Scenario: iOS xcconfig drives Info.plist injection

- **WHEN** inspecting `iosApp/iosApp/Configuration/Staging.xcconfig` (or equivalent path)
- **THEN** the file declares an `APP_API_BASE_URL = https://api-staging.nearyou.id` assignment; the `Info.plist` source contains an `ApiBaseUrl` key with value `${APP_API_BASE_URL}` (or equivalent xcconfig substitution); the production xcconfig declares the placeholder URL

### Requirement: Sign-in API call uses canonical unified-provider endpoint

The mobile app SHALL invoke `POST /api/v1/auth/signin` (the canonical unified-provider endpoint per [`openspec/specs/auth-signin/spec.md`](../../../auth-signin/spec.md) Requirement: Sign-in endpoint contract) with request body `{ "provider": "google", "id_token": <google-id-token> }`. It MUST NOT invoke a per-provider sub-path such as `/api/v1/auth/signin/google` (the menu's casual shorthand in `openspec/project.md` § Mobile + Admin Scaffolding Priority does NOT match the canonical endpoint). The `device_fingerprint_hash` body field SHALL be omitted in Mobile #3 per Decision 9 (attestation deferred; field is optional per the auth-signin spec).

#### Scenario: Sign-in API call targets the canonical endpoint path

- **GIVEN** a Ktor MockEngine that captures outbound requests
- **WHEN** the `AuthRepository.signInWithGoogle("test-google-id-token")` flow runs
- **THEN** the captured outbound request's URL path equals `/api/v1/auth/signin` (NOT `/api/v1/auth/signin/google`) AND the request method is `POST`

#### Scenario: Sign-in API request body shape matches the canonical contract

- **WHEN** `AuthRepository.signInWithGoogle("test-google-id-token")` makes the API call
- **THEN** the captured outbound request body parses as JSON containing exactly `provider = "google"` AND `id_token = "test-google-id-token"` AND no `device_fingerprint_hash` key (the field is omitted in Mobile #3)

### Requirement: Backend error codes are mapped to user-facing copy with no fallthrough

The `AuthRepository` SHALL map each result from the sign-in flow to a specific UI state per Decision 7's table:

- **HTTP 200 (backend `/signin` success)**: persist `TokenPair` via `SecureTokenStore.write(...)` AND emit a navigation event routing to `HomeRoute` (the authenticated `HomeScreen`) via `backStack.replaceAll(HomeRoute)`.
- **HTTP 404 with `error = "user_not_found"`**: this is NOT an error — it is the "no account exists yet for this verified Google identity" signal. Set the in-memory `PendingSignupIdentity` holder to the verified Google identity (the `id_token` from the `GoogleSignInResult.Success` that produced this `404`) AND emit a navigation event appending `AgeGateRoute` to the back stack — the identity is read from the holder by the signup flow (per the `mobile-age-gate` capability), NOT carried as a route parameter (which would write it into the serialized back stack on iOS — see `mobile-age-gate` § "The verified id_token is never written to the serialized back stack"), so the signup flow reuses it without a second Google ceremony. Do NOT emit an on-`SignInScreen` error banner for `404`. The `signin_error_no_account` string is no longer rendered on this path (retired in place; its removal/repurpose for a narrow network-edge fallback is an implementation-time decision per design Open Questions). This replaces the temporary Mobile #3 behavior ("show `signin_error_no_account` banner, remain on `SignInScreen`") and resolves the `FOLLOW_UPS.md` entry `mobile-auth-signin-404-route-to-age-gate`.
- **HTTP 403 with `error = "account_banned"` (permanent-ban path)**: emit an error state whose user-facing copy is `stringResource(Res.string.signin_error_banned)` ("Akun kamu telah dinonaktifkan. Hubungi support jika ini keliru."); remain on `SignInScreen`; CTA is disabled (visually disabled AND tap-rejected per the dedicated scenario below) to prevent retry. **Note:** the current backend `/signin` emits `account_banned` for any `is_banned = TRUE` row WITHOUT inspecting `suspended_until`, so temporarily-suspended users (`is_banned = TRUE AND suspended_until > NOW()` per V2 schema) hit this same permanent-ban copy in Mobile #3. A FOLLOW_UPS entry `mobile-auth-signin-suspended-user-copy-split` tracks the eventual backend differentiation (emit `account_suspended` with a `suspended_until` field at `/signin`) + mobile copy split per `docs/03-UX-Design.md` § Suspension UX (the paragraphs beginning `When users.is_banned = TRUE AND users.suspended_until > NOW()` and `When users.is_banned = TRUE AND users.suspended_until IS NULL`); until that lands, Mobile #3 deliberately ships the permanent-ban copy as a uniform path.
- **HTTP 401 with `error = "invalid_id_token"`**: emit an error state whose user-facing copy is `stringResource(Res.string.signin_error_token_invalid)` ("Sesi Google bermasalah. Coba lagi."); automatically re-invoke `GoogleSignInClient.signIn()` ONCE; if the re-invocation also produces a 401, remain on the error state and require a manual retry tap. The retry counter SHALL be **screen-state-local** (held in the `SignInScreen`'s composition state) — re-entering `SignInScreen` (e.g., after a process death + relaunch) SHALL reset the counter to zero.
- **HTTP 5xx OR network/IO failure**: emit an error state whose user-facing copy is `stringResource(Res.string.signin_error_network)` ("Tidak bisa terhubung. Periksa koneksi internet kamu."); CTA changes label to "Coba lagi" and re-invokes the full flow on tap.
- **`GoogleSignInResult.UserCancelled`**: emit no error state (cancellation is not a failure); `SignInScreen` returns to the initial CTA-visible state.
- **`GoogleSignInResult.Failed(message)`**: emit the same `NetworkError` UI state as the HTTP 5xx / network-IO path (user-facing copy `signin_error_network`); the Google ceremony failing pre-backend is operationally indistinguishable from a network failure from the user's perspective; the `message` payload SHOULD be emitted to Sentry / OTel logs (NOT to the user-facing UI) for diagnosis.

There SHALL NOT be a generic "Sign-in failed" fallthrough — every observed result from the flow maps to exactly one explicit outcome (a navigation event OR an error state) among those listed above. Error states SHALL NOT render the Google account `email` or `displayName` from `GoogleSignInResult.Success` in any user-facing UI (no "Hi, foo@example.com — try again?" pattern); the `displayName` MAY be surfaced ONLY post-authentication, on screens behind the auth gate.

#### Scenario: 200 success persists tokens and navigates to Home

- **GIVEN** a successful Google ID-token exchange producing backend response `200 { access_token: "at-X", refresh_token: "rt-Y", expires_in: 900 }`
- **WHEN** the `AuthRepository.signInWithGoogle(...)` flow processes the response
- **THEN** `SecureTokenStore.write(TokenPair("at-X", "rt-Y", <epoch-millis-now + 900_000>))` is called exactly once AND a navigation event routing to `HomeRoute` (via `backStack.replaceAll(HomeRoute)`) is emitted

#### Scenario: 404 sets the pending identity and navigates to AgeGateRoute

- **GIVEN** backend response `404 { error: { code: "user_not_found" } }` for a `GoogleSignInResult.Success(idToken="g-id", ...)`
- **WHEN** the `AuthRepository.signInWithGoogle(...)` flow processes the response
- **THEN** the `PendingSignupIdentity` holder is set to `idToken = "g-id"` AND a navigation event appending `AgeGateRoute` to the back stack is emitted (the identity is read from the holder, NOT carried as a route parameter) AND NO on-`SignInScreen` error banner is emitted (the `signin_error_no_account` copy is NOT shown) AND no token write is performed

#### Scenario: 403 banned emits banned error state with CTA disabled

- **GIVEN** backend response `403 { error: { code: "account_banned" } }`
- **WHEN** the `AuthRepository.signInWithGoogle(...)` flow processes the response
- **THEN** the emitted UI state's error message text equals the runtime value of `Res.string.signin_error_banned` AND the UI state's `ctaEnabled` field is `false`

#### Scenario: 401 invalid_id_token auto-retries once

- **GIVEN** backend responds `401 { error: { code: "invalid_id_token" } }` on the first call AND responds `200 { ... }` on a subsequent call
- **WHEN** the `AuthRepository.signInWithGoogle(...)` flow processes the first 401
- **THEN** `GoogleSignInClient.signIn()` is invoked a second time within the same flow AND the second call's resulting ID token is submitted via a second `POST /api/v1/auth/signin` AND on the second success the standard token-persist + navigation path runs (per the 200 scenario)

#### Scenario: Network failure emits network error with retry CTA

- **GIVEN** the Ktor MockEngine throws `IOException("connection refused")` on the request
- **WHEN** the `AuthRepository.signInWithGoogle(...)` flow processes the exception
- **THEN** the emitted UI state's error message text equals the runtime value of `Res.string.signin_error_network` AND the UI state's CTA label equals the runtime value of `Res.string.cta_retry` (NOT `Res.string.cta_signin_google` in this state)

#### Scenario: User cancellation produces no error state

- **GIVEN** `GoogleSignInClient.signIn()` returns `GoogleSignInResult.UserCancelled`
- **WHEN** the `AuthRepository.signInWithGoogle(...)` flow processes the cancellation
- **THEN** no error message is emitted; the UI state remains in the initial CTA-visible state (no banner, no disabled CTA, no navigation)

#### Scenario: 401 invalid_id_token second attempt also failing emits terminal InvalidIdToken state

- **GIVEN** backend responds `401 { error: { code: "invalid_id_token" } }` on the first call AND `401 { error: { code: "invalid_id_token" } }` on the auto-retry second call
- **WHEN** the `AuthRepository.signInWithGoogle(...)` flow processes the second 401
- **THEN** the emitted UI state's error message equals `Res.string.signin_error_token_invalid` AND `GoogleSignInClient.signIn()` is NOT invoked a third time (the retry budget is exhausted at one) AND the CTA returns to its initial label ("Masuk dengan Google") with `ctaEnabled = true` so the user can manually tap to start a fresh flow

#### Scenario: 401 invalid_id_token retry counter is screen-state-local and resets across SignInScreen re-entries

- **GIVEN** the user just exhausted the auto-retry budget (two consecutive 401s within one flow) AND has navigated AWAY from `SignInScreen` (e.g., closed the app, was routed to `HomeRoute` by an unrelated path then back to `SignInRoute` via 401-clear, OR the process died and relaunched)
- **WHEN** `SignInScreen` is re-composed AND the user taps the CTA again
- **THEN** the auto-retry counter starts fresh at zero — the next 401 (if any) triggers ONE auto-retry per the canonical mapping, NOT zero (the counter is NOT persisted to disk, NOT persisted across back-stack pops)

#### Scenario: GoogleSignInResult.Failed maps to NetworkError state

- **GIVEN** `GoogleSignInClient.signIn()` returns `GoogleSignInResult.Failed("Credential Manager failed to reach Google's servers")`
- **WHEN** the `AuthRepository.signInWithGoogle(...)` flow processes the result
- **THEN** the emitted `SignInOutcome` is `NetworkError` (NOT a silent no-op, NOT a separate `UnexpectedFailure` state — the user-perceived experience is "the Google sign-in didn't work because of a connectivity issue") AND the UI banner copy equals `Res.string.signin_error_network` AND the `message` payload IS emitted to Sentry / OTel logs (for diagnosis) but is NOT rendered to user-facing UI

#### Scenario: Banned banner CTA does not invoke signInWithGoogle when tapped

- **GIVEN** UI state is in `SignInOutcome.Banned` with `ctaEnabled = false`
- **WHEN** a synthetic Compose tap event is dispatched on the CTA node (some Compose configurations still synthesize click events on `Button(enabled=false)`)
- **THEN** `GoogleSignInClient.signIn()` is NOT invoked AND `AuthRepository.signInWithGoogle()` is NOT entered (the tap is rejected by the visual-AND-tap-handler-disable defense pair, not visual-disable-only)

#### Scenario: No error-state UI renders Google email or displayName

- **GIVEN** the in-flight `GoogleSignInResult.Success(idToken="g-id", displayName="Test User", email="test@example.com")` followed by ANY non-200 backend response that produces a `SignInScreen` error state (403, 401, 5xx — note `404` no longer produces a `SignInScreen` error state; it sets `PendingSignupIdentity` and navigates to `AgeGateRoute`, whose PII discipline is covered by the `mobile-age-gate` capability)
- **WHEN** the resulting error-state UI is rendered
- **THEN** the rendered tree contains NO node whose text contains the substring `"test@example.com"` AND NO node whose text contains the substring `"Test User"` (the PII payload from `GoogleSignInResult.Success` is consumed only for the backend API call body; it is NOT plumbed into any error-state banner / disclaimer / debug text)

#### Scenario: Double-tap on CTA rejects the second concurrent invocation

- **GIVEN** `AuthRepository.signInWithGoogle()` is currently in-flight (first invocation suspended on the Google ceremony or the backend call)
- **WHEN** the user taps the CTA a second time within the same in-flight window
- **THEN** the second invocation either (a) is silently rejected via an `isInFlight` guard in `AuthRepository` (the first call completes normally; the second call returns immediately without re-invoking `GoogleSignInClient.signIn()`), OR (b) the CTA is visibly transitioned to a disabled / `signin_loading` state for the duration of the in-flight window (preventing a second tap). The implementation MAY choose either pattern; both prevent the race-condition outcome where TWO concurrent token writes hit `SecureTokenStore`

### Requirement: RootRouterScreen routes based on token presence

The navigation back stack SHALL be seeded with `RootRoute` as its start destination; `RootRoute` maps (via the `entryProvider`) to the `RootRouterScreen` composable (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/RootRouterScreen.kt`). On first composition, `RootRouterScreen` SHALL read `SecureTokenStore.read()` once (in a `LaunchedEffect`-suspended scope, via `AuthRepository.isAuthenticated()`) and route via `backStack.replaceAll(...)` (the clear-and-set back-stack operation):

- If `read()` returns a non-null `TokenPair`, replace-all with `HomeRoute`. **The router gates on token PRESENCE only** — it does NOT compare `accessExpiresAtEpochMillis` against the current time. Whether the persisted access token is still fresh or already expired is decided lazily, downstream, by the Ktor `Auth` plugin: `loadTokens` attaches the persisted access token, and on the first authenticated `401` `refreshTokens` exchanges the refresh token for a new pair (or, on refresh failure, `SessionInvalidator` clears the store + re-routes to `SignInRoute`). The stored `accessExpiresAtEpochMillis` is retained on the `TokenPair` for a future pre-emptive-refresh optimization in `loadTokens`, but is intentionally NOT a routing gate (the project cannot cheaply know the refresh-token's own expiry client-side, so "a `TokenPair` exists" is the routing signal and the backend is the authority on refresh-token validity).
- If `read()` returns `null`, replace-all with `SignInRoute`.

While the `read()` is in-flight, the screen SHALL render a splash composition (brand logo centered + `CircularProgressIndicator`); no token-bearing decisions are made before the read completes.

#### Scenario: Token present routes to HomeScreen

- **GIVEN** `SecureTokenStore` contains `TokenPair("at-X", "rt-Y", <future-epoch-millis>)`
- **WHEN** the app launches with `RootRoute` as the seeded start destination
- **THEN** the in-flight read completes, AND `backStack.replaceAll(HomeRoute)` is invoked; the visible entry post-route is `HomeRoute` (the `HomeScreen` composable)

#### Scenario: Token absent routes to SignInScreen

- **GIVEN** `SecureTokenStore.read()` returns `null`
- **WHEN** the app launches with `RootRoute` as the seeded start destination
- **THEN** `backStack.replaceAll(SignInRoute)` is invoked; the visible entry post-route is `SignInRoute` (the `SignInScreen` composable)

#### Scenario: Splash composition renders while token check is in-flight

- **GIVEN** a `SecureTokenStore` test stub that suspends indefinitely on `read()`
- **WHEN** `RootRouterScreen` is composed
- **THEN** the rendered tree contains the brand logo node AND a `CircularProgressIndicator`; the screen does NOT make a routing decision (the back stack is unchanged — still `[RootRoute]`)

#### Scenario: Persisted TokenPair with a still-fresh access token routes to HomeScreen

- **GIVEN** `SecureTokenStore.read()` returns `TokenPair("at-X", "rt-Y", accessExpiresAtEpochMillis = now + 1)` (one millisecond in the future)
- **WHEN** `RootRouterScreen` is composed
- **THEN** the routing decision is `HomeRoute` — the router gates on token PRESENCE (non-null `TokenPair`), NOT on the expiry comparison; a fresh access token simply means the Ktor `Auth` plugin won't need to refresh on the first authenticated call

#### Scenario: Persisted TokenPair with an already-expired access token still routes to HomeScreen

- **GIVEN** `SecureTokenStore.read()` returns `TokenPair("at-X", "rt-Y", accessExpiresAtEpochMillis = now)` (epoch equals current time, i.e. the access token is at/past expiry)
- **WHEN** `RootRouterScreen` is composed
- **THEN** the routing decision is still `HomeRoute` — the presence-only gate does NOT distinguish fresh from expired access tokens; `RootRouterScreen` does NOT compare the expiry or pre-emptively refresh. The Ktor `Auth` plugin refreshes lazily on the first authenticated request (or, on refresh failure, `SessionInvalidator` clears the store + re-routes to `SignInRoute`). This is why both the `now + 1` and `now` cases route identically: routing is presence-driven, expiry handling is the Auth plugin's concern.

#### Scenario: Post-Banned process restart routes to SignInScreen with token cleared

- **GIVEN** a previous flow ended in `SignInOutcome.Banned` AND no token was persisted (per the `Banned` scenario, `SecureTokenStore.write` is NOT called) AND the user kills the app
- **WHEN** the app relaunches with `RootRoute` as the seeded start destination
- **THEN** `SecureTokenStore.read()` returns `null` AND the routing decision is `SignInRoute`; the previous `Banned` UI state is NOT restored across process death (which is correct — the Banned state is composition-local; the user MUST re-attempt sign-in on next launch to get the up-to-date banned status from the backend)

### Requirement: device_fingerprint_hash omitted in Mobile #3

The `POST /api/v1/auth/signin` and `POST /api/v1/auth/refresh` requests issued by the mobile app in this change SHALL NOT include the `device_fingerprint_hash` JSON field in the request body. The omission MUST be intentional per Decision 9 (attestation deferred per `docs/06-Security-Privacy.md` § Attestation) and is compatible with the auth-signin spec's "MUST NOT be required for sign-in to succeed" guarantee.

A `FOLLOW_UPS.md` entry `mobile-auth-signin-attestation-fingerprint-hash` SHALL track adding the field when attestation lands.

#### Scenario: signin request body does not carry device_fingerprint_hash

- **WHEN** the `AuthRepository.signInWithGoogle(...)` flow makes the `/signin` API call
- **THEN** the captured outbound JSON body contains no `device_fingerprint_hash` key (verifiable via `body.toString().contains("device_fingerprint_hash") == false` OR via a parsed-JSON assertion)

#### Scenario: FOLLOW_UPS.md tracks attestation fingerprint follow-up

- **WHEN** inspecting `FOLLOW_UPS.md` (in the repository root) after this change is applied
- **THEN** the file contains an entry whose kebab-case identifier is `mobile-auth-signin-attestation-fingerprint-hash` (or equivalent) referencing this Mobile #3 omission as the trigger and `docs/06-Security-Privacy.md` § Attestation as the landing context

### Requirement: AuthRepository orchestrates the sign-in flow

The mobile app SHALL ship an `AuthRepository` class (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/auth/AuthRepository.kt`) registered as a Koin singleton in `mobileModule`, exposing at minimum:

- `suspend fun signInWithGoogle(): SignInOutcome` — orchestrates the full ceremony (GoogleSignInClient → backend `/signin` → token persistence → outcome emission).
- `suspend fun isAuthenticated(): Boolean` — returns whether a persisted `TokenPair` exists (`SecureTokenStore.read() != null`). Presence-only — it does NOT evaluate `accessExpiresAtEpochMillis` (staleness is handled lazily by the Ktor `Auth` plugin per the RootRouterScreen requirement).
- `suspend fun handleTerminal401()` — called by the Ktor `Auth` plugin's `refreshTokens` callback returning `null`; clears the store and triggers re-route to SignInScreen.

`SignInOutcome` is a sealed type modeling six distinct UI states from Decision 7 — `Success`, `NoAccount`, `Banned`, `InvalidIdToken`, `NetworkError`, `Cancelled`. The state count is six (not seven) because `GoogleSignInResult.Failed(message)` from the ceremony layer + HTTP 5xx / network IO failures from the API layer BOTH map to the same `NetworkError` outcome (per Decision 7's table where those two result rows share the user-facing copy `signin_error_network`); the Decision 7 table has seven RESULT rows that converge into six distinct OUTCOMES.

#### Scenario: AuthRepository is registered as a Koin singleton

- **WHEN** inspecting the commonMain Koin module file (`MobileModule.kt`)
- **THEN** the module declares `single<AuthRepository> { ... }` (or equivalent factory binding) constructing `AuthRepository` with `GoogleSignInClient`, `SecureTokenStore`, and `HttpClient` (or `AuthApiClient`) dependencies

#### Scenario: signInWithGoogle composes GoogleSignInClient + backend call + token write

- **GIVEN** stubbed `GoogleSignInClient` returning `Success("google-id", "Test User", "test@example.com")` AND stubbed Ktor MockEngine returning 200 with `{access_token, refresh_token, expires_in}` AND a clean `SecureTokenStore`
- **WHEN** `AuthRepository.signInWithGoogle()` is invoked
- **THEN** the returned outcome is `SignInOutcome.Success` AND `SecureTokenStore.read()` returns a non-null `TokenPair` matching the response AND the captured backend request body parses as `{provider: "google", id_token: "google-id"}`

