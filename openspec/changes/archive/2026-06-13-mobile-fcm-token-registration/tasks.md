# Tasks: mobile-fcm-token-registration

## 1. Pre-implementation gates (external dependency + library re-check)

- [x] 1.1 **Pre-implementation library re-check** (dated WebSearch, per `openspec/project.md` § Change Delivery Workflow): native `firebase-messaging` (Android) + the `FirebaseMessaging` Pod (iOS) behind a commonMain `interface` confirmed as the canonical KMP-FCM pattern vs. a unified wrapper (KMPNotifier / GitLive firebase-kotlin-sdk / KFire) — propose-time search 2026-06-13; native+expect/actual matches the project's "pure DIY wrapper" precedent (design D1). docs/09 rows added (task 2.3).
- [x] 1.2 **External-dependency sanity check** (per the `/next-change` external-dependency rule): verify the Firebase **client** config is operator-provisioned BEFORE wiring live init — `google-services.json` per flavor (`dev`/`staging`/`prod`) for Android + `GoogleService-Info.plist` for iOS, tied to the **separate staging Firebase project** (free tier, its own FCM credentials + a separate APNs `.p8` per `docs/04-Architecture.md` § 259 "Push: separate Firebase project"; the sandbox APNs endpoint is `api.sandbox.push.apple.com`). Confirm whether that staging Firebase Android+iOS app pair + the iOS APNs `.p8` auth key already exist, AND that the iOS Xcode project carries the APNs entitlement + any required Info.plist notification purpose string (operator/xcconfig copy, NOT a CMP Resources string). If absent, file/track the operator-setup task and proceed with the code (it must build + unit-test WITHOUT the config — task 1.3). Do NOT block the code on config placement.
- [x] 1.3 Confirm the build stays green with NO config files present (the `google-services` plugin tolerates a missing JSON at unit-test time, or fall back to manual `FirebaseOptions` per design § Open Questions D2) — the commonTest surface uses fakes + `MockEngine` and never initializes live Firebase.

## 2. Dependencies (gradle/libs.versions.toml + docs/09)

- [x] 2.1 Add the Android pins: `firebase-bom` (`com.google.firebase:firebase-bom`) + `firebase-messaging` (BoM-resolved, no per-artifact version) to `gradle/libs.versions.toml`; add the `com.google.gms.google-services` Gradle plugin alias to the catalog. Apply `firebase-messaging` (via the BoM platform) in `androidMain.dependencies`. **The `google-services` plugin is NOT applied in `build.gradle.kts`** (it hard-fails CI without `google-services.json`); it is declared for the OPERATOR to apply with the config (design D8). `AndroidFcmTokenProvider` returns `null` without a configured `FirebaseApp`, so the app builds + runs without it.
- [x] 2.2 Add the iOS `FirebaseMessaging` Pod to the existing `cocoapods { … }` block in `mobile/app/build.gradle.kts` (alongside the `GoogleSignIn` Pod precedent).
- [x] 2.3 Add `docs/09-Versions.md` Decisions-Log rows for `firebase-bom` (+ the BoM-managed `firebase-messaging`), the `google-services` plugin (declared, operator-applied), and the iOS `FirebaseMessaging` Pod — each with the dated re-check rationale from task 1.1 and the native-SDK-over-wrapper justification (design D1).

## 3. The FcmTokenProvider seam (commonMain interface + platform actuals)

- [x] 3.1 Create the commonMain `interface FcmTokenProvider` (`push/FcmTokenProvider.kt`): `suspend fun currentToken(): String?` + `val tokenRefreshes: Flow<String>`. Platform-free (no Firebase/platform-notification type) — assert via a source guard.
- [x] 3.2 Android actual (`androidMain/.../push/AndroidFcmTokenProvider.kt`): `currentToken()` via `FirebaseMessaging.getInstance().token` (awaited); a `NearYouFirebaseMessagingService : FirebaseMessagingService` whose `onNewToken` feeds `tokenRefreshes` (e.g. via a shared `MutableSharedFlow`); register the service in the Android manifest. The Firebase import lives ONLY here. No `POST_NOTIFICATIONS` request (spec: Android requests no permission for token acquisition).
- [x] 3.3 iOS actual (`iosMain/.../push/IosFcmTokenProvider.kt`): request `UNUserNotificationCenter.requestAuthorization` + `registerForRemoteNotifications()` (the token-acquisition minimum, design D5), read `Messaging.messaging().token`, bridge the messaging-delegate registration-token callback to `tokenRefreshes`; return `null` on denied authorization. The `FirebaseMessaging` Pod import lives ONLY here.
- [x] 3.4 Bind each actual in its Koin platform module (the `LocationProvider` precedent).
- [x] 3.5 `FakeFcmTokenProvider` (commonTest): configurable `currentToken()` return + a manually-emittable `tokenRefreshes`.

## 4. FcmTokenApiClient (data layer)

- [x] 4.1 Create `push/FcmTokenApiClient.kt`: `POST /api/v1/user/fcm-token` over the shipped `HttpClient` (Bearer attached by the `Auth` plugin — NOT reimplemented); JSON body `{ token, platform, app_version }`; client-side guards (trim + non-empty + ≤4096 token, ≤64 app_version) before the request.
- [x] 4.2 Create the sealed `FcmRegistrationOutcome` (`push/FcmRegistrationOutcome.kt`): `Registered`, `Rejected(code)` (closed code set incl. `unknown`), `Unauthorized`, `TransportError`, `NoTokenAvailable`.
- [x] 4.3 Source `platform` as a compile-time constant per platform actor (`"android"`/`"ios"`) and `app_version` from the existing build-config seam (the `mobile-auth-signin` API-base-URL mechanism).
- [x] 4.4 `FcmTokenApiClientTest` (Ktor `MockEngine`): canonical body shape (token, platform constant, present app_version) + the null-app_version body (JSON `null` field); 204→`Registered`; each documented 400 code→`Rejected(code)`; unknown/unparseable 400→`Rejected(unknown)`; 401→`Unauthorized`; transport→`TransportError`; client-side guards each issuing NO request — over-length token→`Rejected(token_too_long)`, empty-after-trim token→`Rejected(empty_token)`, over-length app_version→`Rejected(app_version_too_long)`.

## 5. FcmTokenRegistrar (orchestration)

- [x] 5.1 Create `push/FcmTokenRegistrar.kt`: `suspend fun registerCurrentToken(): FcmRegistrationOutcome` (acquire via provider → `NoTokenAvailable` on null → register via api client → outcome) + `fun observeTokenRefreshes(scope)` collecting `tokenRefreshes` and re-registering. Stateless singleton. `TransportError` swallowed (no throw); token-free diagnostic logging only (design D6).
- [x] 5.2 Bind `FcmTokenApiClient` + `FcmTokenRegistrar` as Koin common-module singletons.
- [x] 5.3 `FcmTokenRegistrarTest` (commonTest, Compose-free): acquire→register happy path; `NoTokenAvailable` on null token; the iOS-denied→`NoTokenAvailable` chain (fake provider modelling denied auth); token-refresh re-registration; `TransportError` swallowed + re-attempt-on-next-trigger; concurrent-registration tolerance (overlapping calls both proceed, no mutex). (The no-register-while-unauthenticated guarantee is structural via the shell-scoped collector — design D4 — so it is asserted by the `AppShellScreenTest` no-fetch guard, not a registrar-internal session check.)
- [x] 5.4 No-token-in-logs guard test: assert across `FcmTokenRegistrar.kt` + `FcmTokenApiClient.kt` + both platform actuals that no enumerated log sink (`Log.`/`println`/`print(`/`NSLog`/`os_log`/`Napier`/`Timber` — the `LocationSourceGuardTest` sink set) receives the token, AND that the shared `HttpClient` `Logging` level on the registration path is not `BODY`/`ALL`.
- [x] 5.5 Koin-resolution test: `FcmTokenRegistrar` resolves with provider (fake) + api-client bound.
- [x] 5.6 `FcmPushSourceGuardTest` (`androidUnitTest`, the `LocationSourceGuardTest` idiom): assert no commonMain push source references `com.google.firebase`/`FirebaseMessaging`/`Messaging`/`UNUserNotificationCenter`, and that the Firebase SDK imports appear ONLY under `androidMain`/`iosMain`. This is the mobile-module enforcement the global `VendorSdkLeakageScanTest` does NOT provide (its roots are `core/*` + `backend/ktor` only).

## 6. Session-active trigger wiring (single convergent hook, no behavior change)

- [x] 6.1 Invoke `registerCurrentToken()` fire-and-forget from a single `LaunchedEffect(Unit)` in `AppShellScreen` (the `HomeRoute` entry where cold-start / sign-in / signup all converge — design D4). Additive call only — no change to routing/refresh behavior; registration never delays navigation (the shell is already composed). NOT wired into `AuthRepository`/`RootRouterScreen` (no auth↔push coupling).
- [x] 6.2 Collect `observeTokenRefreshes()` from the SAME shell-scoped `LaunchedEffect` (lives for the authenticated shell's composition; cancelled on sign-out → structural no-register-while-unauthenticated; restarts on next sign-in).
- [x] 6.3 Update `AppShellScreenTest` to bind a no-op `FcmTokenRegistrar` (a null-token `FakeFcmTokenProvider` → `NoTokenAvailable`, NO POST) so the existing recording-MockEngine no-fetch guard stays intact (the shell now resolves `FcmTokenRegistrar` via Koin).

## 7. Deferrals (tracked as follow-up issues)

- [x] 7.1 Filed `follow-up` issue [#256](https://github.com/aditrioka/nearyou-id/issues/256) for `mobile-push-message-handling` (Android data-only local render + preference check; iOS NSE body rewrite — `docs/04-Architecture.md` § 459–501); referenced in the spec's deferral requirement.
- [x] 7.2 Filed `follow-up` issue [#257](https://github.com/aditrioka/nearyou-id/issues/257) for the contextual notification-permission prompt UX owned by the chat surface (the Android `POST_NOTIFICATIONS` prompt + the "before we notify you" rationale), referencing `mobile-chat-screen`.
- [x] 7.3 Filed `follow-up` issue [#258](https://github.com/aditrioka/nearyou-id/issues/258) (operator-setup: Firebase client config + APNs key, staging) so live verification can complete post-merge.

## 8. Verification

- [x] 8.1 Run `./gradlew :mobile:app:testStagingDebugUnitTest` (JVM/Robolectric) — all new `FcmTokenRegistrarTest` / `FcmTokenApiClientTest` / Koin-resolution / `FcmPushSourceGuardTest` / no-token-in-logs tests green, with NO Firebase config present.
- [x] 8.2 Pre-push lint gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test`. NOTE: the Firebase-import-only-in-platform-source-sets invariant is enforced for the mobile module by `FcmPushSourceGuardTest` (task 5.6), NOT by the global `VendorSdkLeakageScanTest` (whose roots are `core/*` + `backend/ktor` only) — so the mobile guard must be in the unit-test run (8.1), not assumed covered by detekt.
- [x] 8.3 Build the staging APK: `./gradlew :mobile:app:assembleStagingDebug` (confirms the Android Firebase deps + plugin resolve; if config is absent, confirm the chosen init fallback keeps the build green).
- [ ] 8.4 Manual / live verification (gated on operator Firebase config — record as DoD evidence, not a blocking CI gate): on a real device with the config present, sign in → confirm a `204` registration round-trip and a `user_fcm_tokens` row for the user (the `scripts/run_on_device.sh` Robo path can confirm the app launches + reaches the authenticated surface without the Firebase init crashing).
