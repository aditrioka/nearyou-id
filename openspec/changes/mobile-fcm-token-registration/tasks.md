# Tasks: mobile-fcm-token-registration

## 1. Pre-implementation gates (external dependency + library re-check)

- [ ] 1.1 **Pre-implementation library re-check** (dated WebSearch, per `openspec/project.md` § Change Delivery Workflow): re-confirm at `/opsx:apply` kickoff that native `firebase-messaging` (Android) + the `FirebaseMessaging` Pod (iOS) behind an expect/actual `interface` is still the canonical KMP-FCM pattern vs. a unified wrapper (KMPNotifier / GitLive firebase-kotlin-sdk / KFire). Record the dated result in `docs/09-Versions.md`. (Propose-time search 2026-06-13 confirmed native+expect/actual; this is the staleness re-check.)
- [ ] 1.2 **External-dependency sanity check** (per the `/next-change` external-dependency rule): verify the Firebase **client** config is operator-provisioned BEFORE wiring live init — `google-services.json` per flavor (`dev`/`staging`/`prod`) for Android + `GoogleService-Info.plist` for iOS, tied to the **separate staging Firebase project** (free tier, its own FCM credentials + a separate APNs `.p8` per `docs/04-Architecture.md` § 259 "Push: separate Firebase project"; the sandbox APNs endpoint is `api.sandbox.push.apple.com`). Confirm whether that staging Firebase Android+iOS app pair + the iOS APNs `.p8` auth key already exist. If absent, file/track the operator-setup task and proceed with the code (it must build + unit-test WITHOUT the config — task 1.3). Do NOT block the code on config placement.
- [ ] 1.3 Confirm the build stays green with NO config files present (the `google-services` plugin tolerates a missing JSON at unit-test time, or fall back to manual `FirebaseOptions` per design § Open Questions D2) — the commonTest surface uses fakes + `MockEngine` and never initializes live Firebase.

## 2. Dependencies (gradle/libs.versions.toml + docs/09)

- [ ] 2.1 Add the Android pins: `firebase-bom` (`com.google.firebase:firebase-bom`) + `firebase-messaging` (BoM-resolved, no per-artifact version) to `gradle/libs.versions.toml`; add the `com.google.gms.google-services` Gradle plugin alias. Apply `firebase-messaging` in `androidMain.dependencies` and the plugin in `mobile/app/build.gradle.kts` (Android target only).
- [ ] 2.2 Add the iOS `FirebaseMessaging` Pod to the existing `cocoapods { … }` block in `mobile/app/build.gradle.kts` (alongside the `GoogleSignIn` Pod precedent).
- [ ] 2.3 Add `docs/09-Versions.md` Decisions-Log rows for `firebase-bom` (+ the BoM-managed `firebase-messaging`), the `google-services` plugin, and the iOS `FirebaseMessaging` Pod — each with the dated re-check rationale from task 1.1 and the native-SDK-over-wrapper justification (design D1).

## 3. The FcmTokenProvider seam (commonMain interface + platform actuals)

- [ ] 3.1 Create the commonMain `interface FcmTokenProvider` (`push/FcmTokenProvider.kt`): `suspend fun currentToken(): String?` + `val tokenRefreshes: Flow<String>`. Platform-free (no Firebase/platform-notification type) — assert via a source guard.
- [ ] 3.2 Android actual (`androidMain/.../push/AndroidFcmTokenProvider.kt`): `currentToken()` via `FirebaseMessaging.getInstance().token` (awaited); a `NearYouFirebaseMessagingService : FirebaseMessagingService` whose `onNewToken` feeds `tokenRefreshes` (e.g. via a shared `MutableSharedFlow`); register the service in the Android manifest. The Firebase import lives ONLY here. No `POST_NOTIFICATIONS` request (spec: Android requests no permission for token acquisition).
- [ ] 3.3 iOS actual (`iosMain/.../push/IosFcmTokenProvider.kt`): request `UNUserNotificationCenter.requestAuthorization` + `registerForRemoteNotifications()` (the token-acquisition minimum, design D5), read `Messaging.messaging().token`, bridge the messaging-delegate registration-token callback to `tokenRefreshes`; return `null` on denied authorization. The `FirebaseMessaging` Pod import lives ONLY here.
- [ ] 3.4 Bind each actual in its Koin platform module (the `LocationProvider` precedent).
- [ ] 3.5 `FakeFcmTokenProvider` (commonTest): configurable `currentToken()` return + a manually-emittable `tokenRefreshes`.

## 4. FcmTokenApiClient (data layer)

- [ ] 4.1 Create `data/push/FcmTokenApiClient.kt`: `POST /api/v1/user/fcm-token` over the shipped `HttpClient` (Bearer attached by the `Auth` plugin — NOT reimplemented); JSON body `{ token, platform, app_version }`; client-side guards (trim + non-empty + ≤4096 token, ≤64 app_version) before the request.
- [ ] 4.2 Create the sealed `FcmRegistrationOutcome` (`data/push/FcmRegistrationOutcome.kt`): `Registered`, `Rejected(code)` (closed code set incl. `unknown`), `Unauthorized`, `TransportError`, `NoTokenAvailable`.
- [ ] 4.3 Source `platform` as a compile-time constant per platform actor (`"android"`/`"ios"`) and `app_version` from the existing build-config seam (the `mobile-auth-signin` API-base-URL mechanism).
- [ ] 4.4 `FcmTokenApiClientTest` (Ktor `MockEngine`): canonical body shape (token, platform constant, app_version); 204→`Registered`; each documented 400 code→`Rejected(code)`; unknown 400→`Rejected(unknown)`; 401→`Unauthorized`; transport→`TransportError`; over-length token rejected client-side with no request issued.

## 5. FcmTokenRegistrar (orchestration)

- [ ] 5.1 Create `push/FcmTokenRegistrar.kt`: `suspend fun registerCurrentToken(): FcmRegistrationOutcome` (acquire via provider → `NoTokenAvailable` on null → register via api client → outcome) + `fun observeTokenRefreshes(scope)` collecting `tokenRefreshes` and re-registering. Stateless singleton. `TransportError` swallowed (no throw); token-free diagnostic logging only (design D6).
- [ ] 5.2 Bind `FcmTokenApiClient` + `FcmTokenRegistrar` as Koin common-module singletons.
- [ ] 5.3 `FcmTokenRegistrarTest` (commonTest, Compose-free): acquire→register happy path; `NoTokenAvailable` on null token; token-refresh re-registration; `TransportError` swallowed + re-attempt-on-next-trigger; no-register-while-unauthenticated (the registrar is not invoked from the unauthenticated state — assert the trigger wiring, task 6).
- [ ] 5.4 No-token-in-logs guard test (source/behavior): no logging call site receives the token value.
- [ ] 5.5 Koin-resolution test: `FcmTokenRegistrar` resolves with provider (fake) + api-client bound.

## 6. Post-auth trigger wiring (additive, no behavior change)

- [ ] 6.1 Invoke `registerCurrentToken()` fire-and-forget on a non-blocking scope at the session-active transitions: the `RootRouterScreen`/`AuthRepository.isAuthenticated()` cold-start path that routes to `HomeRoute`, and the `AuthRepository` sign-in HTTP-200 success path. Additive call only — do NOT change the documented routing/refresh behavior (design D4); registration must never delay navigation.
- [ ] 6.2 Start `observeTokenRefreshes` once at app scope (so a rotation while signed in re-registers; while signed out, the no-register-while-unauthenticated guard holds).
- [ ] 6.3 Test the trigger wiring: a session-active transition invokes the registrar exactly once; an unauthenticated state does not.

## 7. Deferrals (tracked as follow-up issues)

- [ ] 7.1 File a `follow-up` GitHub issue for `mobile-push-message-handling` (Android data-only local render + preference check; iOS NSE body rewrite — `docs/04-Architecture.md` § 459–501) and reference it in the spec's deferral requirement.
- [ ] 7.2 File a `follow-up` GitHub issue for the contextual notification-permission prompt UX owned by the chat surface (the Android `POST_NOTIFICATIONS` prompt + the "before we notify you" rationale), referencing `mobile-chat-screen`.
- [ ] 7.3 If task 1.2 found the operator Firebase client config / APNs key absent, ensure the operator-setup task is tracked (issue or `ENVIRONMENT_SETUP_CHECKLIST.md` entry) so live verification can complete post-merge.

## 8. Verification

- [ ] 8.1 Run `./gradlew :mobile:app:testStagingDebugUnitTest` (JVM/Robolectric) — all new `FcmTokenRegistrarTest` / `FcmTokenApiClientTest` / Koin-resolution / no-token-in-logs tests green, with NO Firebase config present.
- [ ] 8.2 Pre-push lint gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` (the no-vendor-SDK-outside-platform invariant must hold — Firebase imports only in `androidMain`/`iosMain`).
- [ ] 8.3 Build the staging APK: `./gradlew :mobile:app:assembleStagingDebug` (confirms the Android Firebase deps + plugin resolve; if config is absent, confirm the chosen init fallback keeps the build green).
- [ ] 8.4 Manual / live verification (gated on operator Firebase config — record as DoD evidence, not a blocking CI gate): on a real device with the config present, sign in → confirm a `204` registration round-trip and a `user_fcm_tokens` row for the user (the `scripts/run_on_device.sh` Robo path can confirm the app launches + reaches the authenticated surface without the Firebase init crashing).
