## ADDED Requirements

### Requirement: SignInScreen renders Google Sign-In entry point

The mobile app SHALL ship a Voyager `Screen` implementation `SignInScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/auth/SignInScreen.kt`) that renders the unauthenticated entry surface. The screen SHALL display: (a) the brand logo via `painterResource(Res.drawable.logo_brand_{light,dark})` (theme-aware per `isSystemInDarkTheme()` consistent with [`HomeScreen`](../../../../mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt)); (b) a screen title consumed via `stringResource(Res.string.signin_screen_title)`; (c) a primary call-to-action button consumed via `stringResource(Res.string.cta_signin_google)`; (d) a footnote consumed via `stringResource(Res.string.account_separation_disclosure)`. No hardcoded UI string literals SHALL appear in the screen source.

#### Scenario: Initial render shows the Google Sign-In CTA

- **WHEN** a `commonTest` runs `runComposeUiTest { setContent { NearYouTheme { SignInScreen().Content() } } }` against a fresh composition with no in-flight auth state
- **THEN** the rendered tree contains a node whose text matches the runtime value of `stringResource(Res.string.cta_signin_google)` (i.e., "Masuk dengan Google") AND the node is clickable

#### Scenario: Initial render shows the screen title and disclosure

- **WHEN** `SignInScreen` is composed
- **THEN** the rendered tree contains a node whose text matches the runtime value of `stringResource(Res.string.signin_screen_title)` AND a node whose text matches the runtime value of `stringResource(Res.string.account_separation_disclosure)`

#### Scenario: No hardcoded UI strings in SignInScreen source

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/auth/SignInScreen.kt`
- **THEN** every `Text(...)` / `contentDescription = ...` / similar UI-string-bearing call site sources its text via `stringResource(Res.string.<name>)` (Compose Multiplatform Resources accessor); zero literal string arguments appear in such call sites

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
- **THEN** `AuthRepository` observes the terminal 401, invokes `SecureTokenStore.clear()`, AND emits a state event triggering `RootRouterScreen` to re-route to `SignInScreen`

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

The `AuthRepository` SHALL map each backend response from `POST /api/v1/auth/signin` to a specific UI state per Decision 7's table:

- **HTTP 200**: persist `TokenPair` via `SecureTokenStore.write(...)` AND emit a navigation event routing through `RootRouterScreen` to `HomeScreen`.
- **HTTP 404 with `error = "user_not_found"`**: emit an error state whose user-facing copy is `stringResource(Res.string.signin_error_no_account)` (currently "Akun belum terdaftar. Daftar dulu lewat pembaruan aplikasi berikutnya."); remain on `SignInScreen`; user may retry via the CTA.
- **HTTP 403 with `error = "account_banned"`**: emit an error state whose user-facing copy is `stringResource(Res.string.signin_error_banned)` ("Akun kamu telah dinonaktifkan. Hubungi support jika ini keliru."); remain on `SignInScreen`; CTA is disabled to prevent retry.
- **HTTP 401 with `error = "invalid_id_token"`**: emit an error state whose user-facing copy is `stringResource(Res.string.signin_error_token_invalid)` ("Sesi Google bermasalah. Coba lagi."); automatically re-invoke `GoogleSignInClient.signIn()` ONCE; if the re-invocation also produces a 401, remain on the error state and require a manual retry tap.
- **HTTP 5xx OR network/IO failure**: emit an error state whose user-facing copy is `stringResource(Res.string.signin_error_network)` ("Tidak bisa terhubung. Periksa koneksi internet kamu."); CTA changes label to "Coba lagi" and re-invokes the full flow on tap.
- **`GoogleSignInResult.UserCancelled`**: emit no error state (cancellation is not a failure); `SignInScreen` returns to the initial CTA-visible state.

There SHALL NOT be a generic "Sign-in failed" fallthrough — every observed result from the flow maps to one of the six explicit states above.

#### Scenario: 200 success persists tokens and navigates to Home

- **GIVEN** a successful Google ID-token exchange producing backend response `200 { access_token: "at-X", refresh_token: "rt-Y", expires_in: 900 }`
- **WHEN** the `AuthRepository.signInWithGoogle(...)` flow processes the response
- **THEN** `SecureTokenStore.write(TokenPair("at-X", "rt-Y", <epoch-millis-now + 900_000>))` is called exactly once AND a navigation event routing to `HomeScreen` (via `RootRouterScreen`) is emitted

#### Scenario: 404 emits no-account error state

- **GIVEN** backend response `404 { error: { code: "user_not_found" } }`
- **WHEN** the `AuthRepository.signInWithGoogle(...)` flow processes the response
- **THEN** the emitted UI state's error message text equals the runtime value of `Res.string.signin_error_no_account` AND no token write is performed AND no navigation event is emitted

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

### Requirement: RootRouterScreen routes based on token presence

The Voyager `Navigator(startDestination = ...)` SHALL be constructed with `RootRouterScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/RootRouterScreen.kt`) as the start destination. On first composition, `RootRouterScreen` SHALL read `SecureTokenStore.read()` once (in a `LaunchedEffect`-suspended scope) and route via `navigator.replaceAll(...)`:

- If `read()` returns a `TokenPair` whose `accessExpiresAtEpochMillis` is in the future, replace-all with `HomeScreen`.
- If `read()` returns a `TokenPair` whose access token has expired but whose refresh token is still within its `expires_in` TTL (30 days per [`openspec/specs/auth-session/spec.md`](../../../auth-session/spec.md)), replace-all with `HomeScreen` (the Ktor `Auth` plugin will refresh on the first authenticated call).
- If `read()` returns `null` or the refresh token has clearly expired, replace-all with `SignInScreen`.

While the `read()` is in-flight, the screen SHALL render a splash composition (brand logo centered + `CircularProgressIndicator`); no token-bearing decisions are made before the read completes.

#### Scenario: Token present routes to HomeScreen

- **GIVEN** `SecureTokenStore` contains `TokenPair("at-X", "rt-Y", <future-epoch-millis>)`
- **WHEN** the app launches and `RootRouterScreen` is the start destination
- **THEN** the in-flight read completes, AND `navigator.replaceAll(HomeScreen)` is invoked; the visible screen post-route is `HomeScreen`

#### Scenario: Token absent routes to SignInScreen

- **GIVEN** `SecureTokenStore.read()` returns `null`
- **WHEN** the app launches and `RootRouterScreen` is the start destination
- **THEN** `navigator.replaceAll(SignInScreen)` is invoked; the visible screen post-route is `SignInScreen`

#### Scenario: Splash composition renders while token check is in-flight

- **GIVEN** a `SecureTokenStore` test stub that suspends indefinitely on `read()`
- **WHEN** `RootRouterScreen` is composed
- **THEN** the rendered tree contains the brand logo node AND a `CircularProgressIndicator`; the screen does NOT make a routing decision (navigator state is unchanged)

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
- `suspend fun isAuthenticated(): Boolean` — checks `SecureTokenStore` for a non-stale `TokenPair`.
- `suspend fun handleTerminal401()` — called by the Ktor `Auth` plugin's `refreshTokens` callback returning `null`; clears the store and triggers re-route to SignInScreen.

`SignInOutcome` is a sealed type modeling the six UI states from Decision 7 (`Success`, `NoAccount`, `Banned`, `InvalidIdToken`, `NetworkError`, `Cancelled`).

#### Scenario: AuthRepository is registered as a Koin singleton

- **WHEN** inspecting the commonMain Koin module file (`MobileModule.kt`)
- **THEN** the module declares `single<AuthRepository> { ... }` (or equivalent factory binding) constructing `AuthRepository` with `GoogleSignInClient`, `SecureTokenStore`, and `HttpClient` (or `AuthApiClient`) dependencies

#### Scenario: signInWithGoogle composes GoogleSignInClient + backend call + token write

- **GIVEN** stubbed `GoogleSignInClient` returning `Success("google-id", "Test User", "test@example.com")` AND stubbed Ktor MockEngine returning 200 with `{access_token, refresh_token, expires_in}` AND a clean `SecureTokenStore`
- **WHEN** `AuthRepository.signInWithGoogle()` is invoked
- **THEN** the returned outcome is `SignInOutcome.Success` AND `SecureTokenStore.read()` returns a non-null `TokenPair` matching the response AND the captured backend request body parses as `{provider: "google", id_token: "google-id"}`
