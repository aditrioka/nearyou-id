## MODIFIED Requirements

### Requirement: Scaffold does not introduce networking, auth, or feature behavior

The `:mobile:app` module commonMain SHALL NOT contain Ktor HTTP-client setup, ad-hoc HTTP usage, authentication-flow wiring, FCM token registration, hardcoded API base URLs, or any feature-specific business logic — **EXCEPT** for the substrate landed by the `mobile-auth-google-signin-flow` change (Mobile #3) per the carve-outs below. All other such concerns ship in subsequent mobile changes per [`openspec/project.md`](../../project.md) § Mobile + Admin Scaffolding Priority (#4 age gate, #5 first product screen, and beyond).

**Carve-outs introduced by `mobile-auth-google-signin-flow` (Mobile #3):**

- **Ktor HTTP client** is now permitted in commonMain via the canonical KMP coordinates (`io.ktor:ktor-client-core`, `io.ktor:ktor-client-content-negotiation`, `io.ktor:ktor-serialization-kotlinx-json` in commonMain; `io.ktor:ktor-client-okhttp` in androidMain; `io.ktor:ktor-client-darwin` in iosMain). The non-KMP `-jvm` artifact set (e.g., `io.ktor:ktor-client-okhttp-jvm`) remains forbidden in mobile sources (those are backend-only).
- **Auth-flow identifiers** (`SignIn`, `GoogleId`, `signIn`, `signin`, `googleSignIn`, `idToken`, `accessToken`, `refreshToken`, `authToken`, `JwtToken`, `jwt_token`, `Authenticator`, `oauthClient`, `loginClient`, `loginFlow`) are now permitted inside files added by this change — specifically: `mobile/app/src/commonMain/kotlin/id/nearyou/app/auth/**`, `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/auth/**`, `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/**`, `mobile/app/src/commonMain/kotlin/id/nearyou/app/config/**`, `mobile/app/src/{androidMain,iosMain}/kotlin/id/nearyou/app/auth/**`, `mobile/app/src/{androidMain,iosMain}/kotlin/id/nearyou/app/config/**`. They remain forbidden everywhere else in mobile sources.
- **Apple Sign-In identifiers** (`AppleAuth`, `appleSignIn`, `apple_sign_in`) remain forbidden across all mobile sources (Apple Sign-In iOS is a separate later change).
- **Environment-aware API base URL** is now permitted inside the `id.nearyou.app.config` package only — specifically: `mobile/app/src/commonMain/kotlin/id/nearyou/app/config/ApiBaseUrl.kt`, `mobile/app/src/{androidMain,iosMain}/kotlin/id/nearyou/app/config/ApiBaseUrl.kt`. The Android actual reads from `BuildConfig.API_BASE_URL` (via gradle product flavor injection) and the iOS actual reads from `NSBundle` (via xcconfig). Hardcoded API hostnames remain forbidden everywhere else in mobile sources.
- **FCM-token registration identifiers** (`FirebaseMessaging`, `fcmToken`, `fcm_token`, `registerFcmToken`, `register_fcm_token`, `messaging.token`, `pushToken`, `push_token`, `notificationToken`, `notification_token`) remain forbidden across all mobile sources.
- **Direct HTTP-client usage** (`URLConnection`, `HttpURLConnection`, `URLSession`, `NSURLSession`, `okhttp3.OkHttpClient`, `WebSocket`, `WebSocketClient`) remains forbidden. The Ktor `HttpClient` is the ONLY permitted client substrate.
- **Backend / infra module dependencies** (`projects.backend.*`, `projects.infra.*`, `project(":backend:...")`, `project(":infra:...")`) remain forbidden in `mobile/app/build.gradle.kts`.

The negative scenarios below use case-insensitive grep patterns intentionally broadened to cover common identifier shapes. They are NOT exhaustive — the canonical defense against scope drift is the spec requirement itself, with grep as a CI-time backstop. Implementers SHOULD treat additions to mobile sources that match the spirit (auth flow OUTSIDE the carved-out paths, FCM token handling, ad-hoc network calls, hardcoded API hostnames OUTSIDE the config package) as requirement violations even if the specific identifier shape escapes a literal grep.

#### Scenario: Ktor KMP client dependencies are permitted in mobile build

- **WHEN** inspecting `mobile/app/build.gradle.kts`
- **THEN** the file MAY declare `io.ktor:ktor-client-core`, `io.ktor:ktor-client-content-negotiation`, `io.ktor:ktor-serialization-kotlinx-json` as `commonMain` dependencies AND `io.ktor:ktor-client-okhttp` as an `androidMain` dependency AND `io.ktor:ktor-client-darwin` as an `iosMain` dependency; NO `io.ktor:ktor-client-*-jvm` artifact (the `-jvm` suffix variant) is declared as a mobile-module dependency

#### Scenario: No ad-hoc HTTP usage in mobile sources

- **WHEN** grepping `mobile/app/src/commonMain`, `mobile/app/src/androidMain`, and `mobile/app/src/iosMain` for any of the following identifiers (case-insensitive): `URLConnection`, `HttpURLConnection`, `URLSession`, `NSURLSession`, `okhttp3.OkHttpClient`, `WebSocket`, `WebSocketClient`
- **THEN** no matches are found in mobile-module sources (Ktor's internal transitive use is permitted; this scenario targets first-party scaffold code only)

#### Scenario: Auth-flow identifiers are permitted only inside this change's carved-out paths

- **WHEN** grepping `mobile/app/src/commonMain`, `mobile/app/src/androidMain`, and `mobile/app/src/iosMain` for any of the following identifiers (case-insensitive): `SignIn`, `GoogleId`, `signIn`, `signin`, `googleSignIn`, `google_sign_in`, `JwtToken`, `JWT_TOKEN`, `jwt_token`, `RefreshToken`, `refresh_token`, `authToken`, `auth_token`, `accessToken`, `access_token`, `idToken`, `id_token`, `Authenticator`, `oauthClient`, `loginClient`, `loginFlow`
- **THEN** every match resides under one of the carved-out paths declared above (`auth/**`, `screens/auth/**`, `screens/routing/**`, `config/**` in any source set); no match resides outside those paths

#### Scenario: Apple Sign-In identifiers remain forbidden in mobile sources

- **WHEN** grepping `mobile/app/src/commonMain`, `mobile/app/src/androidMain`, and `mobile/app/src/iosMain` for any of the following identifiers (case-insensitive): `AppleAuth`, `appleSignIn`, `apple_sign_in`, `ASAuthorization`
- **THEN** no matches are found in mobile-module sources (Apple Sign-In iOS is a separate later change)

#### Scenario: No FCM-token registration code in mobile sources

- **WHEN** grepping `mobile/app/src/commonMain`, `mobile/app/src/androidMain`, and `mobile/app/src/iosMain` for any of the following identifiers (case-insensitive): `FirebaseMessaging`, `fcmToken`, `fcm_token`, `registerFcmToken`, `register_fcm_token`, `messaging.token`, `pushToken`, `push_token`, `notificationToken`, `notification_token`
- **THEN** no matches are found in mobile-module sources

#### Scenario: Hardcoded API base URL is permitted only inside the config package

- **WHEN** grepping `mobile/app/src/commonMain`, `mobile/app/src/androidMain`, and `mobile/app/src/iosMain` for any of the following patterns (case-insensitive): `nearyou\.id`, `api-staging`, `api\.nearyou`, `admin-staging`, `admin\.nearyou`, `img-staging`, `img\.nearyou`
- **THEN** every match resides under `mobile/app/src/{commonMain,androidMain,iosMain}/kotlin/id/nearyou/app/config/**`; no match resides outside the `config` package. The Android `BuildConfig.API_BASE_URL` value injected via gradle product flavor IS the canonical Android resolution path; the iOS `NSBundle.objectForInfoDictionaryKey("ApiBaseUrl")` value injected via xcconfig IS the canonical iOS resolution path

#### Scenario: No backend or infra module dependencies

- **WHEN** inspecting `mobile/app/build.gradle.kts`
- **THEN** the file contains no `projects.backend.*` / `projects.infra.*` Gradle-module-accessor references AND no `project(":backend:..."` / `project(":infra:..."` legacy-syntax references; neither form may smuggle a backend or infra module into the mobile dependency graph
