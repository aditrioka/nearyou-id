## 1. Pre-implementation verification

- [ ] 1.1 Verify the current stable Firebase Admin SDK version on Maven Central (`com.google.firebase:firebase-admin`) — pin in `gradle/libs.versions.toml` MUST match an actually-published version, not a guess. Treat the `9.4.3` figure in `design.md` D9 as a placeholder; the implementer's first task is to look it up and update both the design note and the actual pin.
- [ ] 1.2 Verify the GCP Secret Manager slot `firebase-admin-sa` (prod) and `staging-firebase-admin-sa` (staging) exist OR are reserved. If only reserved, document the slot-population responsibility in the deploy migration plan (operator must populate before deploy).
- [ ] 1.3 Confirm the Firebase project that will be used in staging vs prod is documented somewhere (likely `docs/04-Architecture.md § Environments`); if not, file a note in `FOLLOW_UPS.md` for the eventual doc-amendment change.

## 2. Module + library scaffolding

- [ ] 2.1 Create the new Gradle module `infra/fcm/` with `build.gradle.kts`. Source-set is JVM-only (Ktor server, not KMP). Module path: `:infra:fcm`.
- [ ] 2.2 Add `:infra:fcm` to [`settings.gradle.kts`](settings.gradle.kts) `include(...)` list.
- [ ] 2.3 Pin the `firebase-admin` library in [`gradle/libs.versions.toml`](gradle/libs.versions.toml) under `[versions]` and `[libraries]`. Reference the value verified in 1.1.
- [ ] 2.4 In `infra/fcm/build.gradle.kts`, declare dependencies: `api(project(":core:data"))`, `implementation(project(":core:domain"))`, `implementation(libs.firebase.admin)`, plus the standard test dependencies (kotest, mockk).
- [ ] 2.5 Add a one-line description for `infra/fcm` to [`dev/module-descriptions.txt`](dev/module-descriptions.txt) (e.g., `infra/fcm — Firebase Cloud Messaging Admin SDK wrapper; production NotificationDispatcher impl`).
- [ ] 2.6 Run `dev/scripts/sync-readme.sh --write` to refresh the auto-generated module list in the root README. Verify the diff includes the new `infra/fcm` row.
- [ ] 2.7 Add a row to [`docs/09-Versions.md`](docs/09-Versions.md) Version Decisions table for `com.google.firebase:firebase-admin` with the verified version, today's pin date (2026-04-28), rationale referencing this change + PR number (fill PR number after `gh pr create`), and `2026-Q3` next review.

## 3. `:core:data` interfaces (no logic, just contracts)

- [ ] 3.1 Add `interface UserFcmTokenReader { fun activeTokens(userId: UserId): List<FcmTokenRow>; fun deleteToken(userId: UserId, platform: String, token: String): Int }` in `:core:data` (next to the existing `NotificationDispatcher` interface). Per spec `fcm-push-dispatch.spec.md` § "UserFcmTokenReader interface".
- [ ] 3.2 Add `data class FcmTokenRow(val platform: String, val token: String)` in `:core:data`.
- [ ] 3.3 Add `interface ActorUsernameLookup { fun lookup(actorUserId: UserId?): String? }` in `:core:data` (the helper that resolves actor → username for push body composition).

## 4. `:infra:fcm` implementations

- [ ] 4.1 Implement `object PushCopy` in `:infra:fcm` with `titleFor(type: String): String` and `bodyFor(notification: NotificationDto, actorUsername: String?): String` per the table in `fcm-push-dispatch.spec.md` § "PushCopy SHALL provide Indonesian copy". All five Indonesian strings + the fallback live in this single file.
- [ ] 4.2 Implement `class AndroidPayloadBuilder` (or top-level fun) in `:infra:fcm` that takes `(NotificationDto, String token)` and returns an FCM `Message` with the data-only shape per spec § "Android payload SHALL be data-only".
- [ ] 4.3 Implement `class IosPayloadBuilder` (or top-level fun) in `:infra:fcm` that takes `(NotificationDto, String? actorUsername, String token)` and returns an FCM `Message` with the alert + mutable-content shape per spec § "iOS payload SHALL be alert + mutable-content".
- [ ] 4.4 Implement `class FcmDispatcher` in `:infra:fcm` per spec § "FcmDispatcher SHALL implement NotificationDispatcher". Constructor params: `userFcmTokenReader`, `firebaseMessaging`, `pushCopy`, `actorUsernameLookup`, `dispatcherScope`. Dispatch logic per the spec scenarios (parallel fanout, INFO log on success, INFO log on token-prune for 404/410/SENDER_ID_MISMATCH, WARN log on transient errors, no exception escape).
- [ ] 4.5 Implement `class FcmAndInAppDispatcher(fcm: FcmDispatcher, inApp: InAppOnlyDispatcher) : NotificationDispatcher` in `:infra:fcm` (composite). `dispatch(...)` invokes both in sequence. Per `design.md` D1.
- [ ] 4.6 Implement boot-time `FirebaseApp.initializeApp(...)` helper in `:infra:fcm` named `initializeFcmAdminSdk(secretJson: String)` that parses + validates the service-account JSON and registers the named `FirebaseApp` instance `"nearyou-default"`. Throws a typed exception on parse / validation failure that the production startup path will route to a structured FATAL log.
- [ ] 4.7 Register a JVM shutdown hook in the `:infra:fcm` Koin module (or in the production wiring) that calls `dispatcherScope.cancelAndJoin()` with a 5-second timeout. Per spec § "Dispatcher coroutine scope SHALL drain on JVM shutdown".

## 5. `:backend:ktor` wiring

- [ ] 5.1 Implement the production `UserFcmTokenReader` against the existing JOOQ surface in `:backend:ktor`. Single index lookup for `activeTokens`; single index DELETE for `deleteToken`.
- [ ] 5.2 Implement the production `ActorUsernameLookup` in `:backend:ktor`. Reads `SELECT username FROM users WHERE id = :actor_user_id LIMIT 1`. Annotate the call-site with `// @allow-raw-users-read: notification-rendering` per `design.md` D4.
- [ ] 5.3 Update the existing `RawFromPostsRule` (or analogous Detekt rule that gates raw `FROM users` reads) to recognize the `// @allow-raw-users-read: notification-rendering` annotation as an allowlist marker. If no such rule exists yet for `users` (the rule may currently target `posts` / `post_replies` only), this task collapses to "verify no Detekt rule blocks the raw read"; if a rule does exist, extend its allowlist tests.
- [ ] 5.4 Wire the production Koin DI module to bind `NotificationDispatcher` to `FcmAndInAppDispatcher` (the composite). Inject all 5 dependencies (`UserFcmTokenReader`, `FirebaseMessaging`, `PushCopy`, `ActorUsernameLookup`, `dispatcherScope`).
- [ ] 5.5 Wire production startup to call `initializeFcmAdminSdk(secretKey(env, "firebase-admin-sa"))`. On failure, structured FATAL log + `exitProcess(1)` (or equivalent fail-fast that prevents Cloud Run from rolling forward).
- [ ] 5.6 Verify the test Koin module continues to bind `NotificationDispatcher` to `InAppOnlyDispatcher` — no FCM dispatch in the default test profile.
- [ ] 5.7 Add a small named `CoroutineScope` factory for `fcmDispatcherScope` (production: `Dispatchers.IO.limitedParallelism(8)` with a `SupervisorJob`; test: provided by the test override module).

## 6. Tests — `:infra:fcm` unit tests

- [ ] 6.1 `PushCopyTest`: covers all four V10-wired types with and without actor username, and the fallback for `chat_message`. Asserts exact string outputs per spec scenarios.
- [ ] 6.2 `AndroidPayloadBuilderTest`: asserts no notification block, `priority = HIGH`, all 5 data fields present (with empty-string semantics for null actor / target), `body_data` JSON-stringified correctly.
- [ ] 6.3 `IosPayloadBuilderTest`: asserts `notification.title = "NearYou"`, `notification.body` matches expected per-type copy, `aps.mutableContent = true`, `body_full` carries JSON-stringified body_data, no Android data block.
- [ ] 6.4 `FcmDispatcherTest` (using mocked `FirebaseMessaging` + mocked `UserFcmTokenReader` + `Dispatchers.Unconfined` for synchronous assertion):
  - 6.4.1 Recipient with zero tokens: zero FCM calls, single INFO `event="fcm_skipped_no_tokens"` log line.
  - 6.4.2 Recipient with 2 Android + 1 iOS tokens: 3 FCM calls, payloads platform-correct.
  - 6.4.3 FCM returns `UNREGISTERED` for one token: that row deleted, INFO `event="fcm_token_pruned"` logged, peer tokens still dispatched.
  - 6.4.4 FCM returns `INVALID_ARGUMENT` for one token: row deleted, same INFO log.
  - 6.4.5 FCM returns `SENDER_ID_MISMATCH` for one token: row deleted, same INFO log.
  - 6.4.6 FCM returns `INTERNAL` (5xx) for one token: row NOT deleted, WARN `event="fcm_dispatch_failed"` logged, no exception escapes.
  - 6.4.7 FCM throws a non-Firebase exception (network timeout): row NOT deleted, WARN logged.
  - 6.4.8 Token-confidentiality: with a sentinel test-token string `"sentinel-token-string-DO-NOT-LEAK"`, captured WARN log output does NOT contain that literal.
  - 6.4.9 Exception in one token's send does not abort the other tokens' fanout.
- [ ] 6.5 `FcmAndInAppDispatcherTest`: composite dispatch fires both sub-dispatchers; if one throws, the other still runs (defensive).
- [ ] 6.6 `InitializeFcmAdminSdkTest`: malformed JSON → typed exception thrown; valid service-account JSON → `FirebaseApp.initializeApp` called once with the expected name.

## 7. Tests — `:backend:ktor` integration

- [ ] 7.1 End-to-end test: register a token via `POST /api/v1/user/fcm-token`, trigger a `post_liked` emit (Bob likes Alice's post), assert the (mocked) `FirebaseMessaging.send(...)` was called once with the expected platform-correct payload AND with the registered token. (Test profile installs a test-only override binding `FcmAndInAppDispatcher` with a mock `FirebaseMessaging`.)
- [ ] 7.2 End-to-end test for the on-send 404 → row-delete contract: install a mock `FirebaseMessaging` that returns `UNREGISTERED` for a specific test token, trigger a notification emit, assert the `user_fcm_tokens` row is deleted via the production `UserFcmTokenReader.deleteToken(...)` path.
- [ ] 7.3 End-to-end test that the dispatcher runs AFTER DB commit: emit a notification, assert that the `notifications` row exists in the DB at the time the FCM mock is invoked (race-safe assertion: capture the mock invocation timestamp; query the DB; assert the row exists).
- [ ] 7.4 Default-profile test: confirm `Koin.get<NotificationDispatcher>()` resolves to `InAppOnlyDispatcher` (not `FcmAndInAppDispatcher`) when no FCM override is installed — guards against accidental production binding leaking into tests.
- [ ] 7.5 NotificationService source-unchanged test: a static-analysis test (or a hash-based snapshot test of the file's source) asserts that this change did NOT modify `NotificationService.kt`. Per spec § "NotificationService source unchanged by FcmDispatcher addition".
- [ ] 7.6 Boot-time fail-fast test: in a "production-like" test profile, point `secretKey(env, "firebase-admin-sa")` at a malformed string; assert startup fails with the expected FATAL log AND a non-zero exit. Skip if test infrastructure can't simulate `exitProcess`; in that case assert the typed exception is thrown.
- [ ] 7.7 Shutdown-drain test: launch a dispatch with a 1-second simulated FCM round-trip, fire the shutdown hook, assert the dispatch completes within the 5-second drain window AND no exception escapes. (May be flaky on CI; mark `@Flaky` with a clear comment if needed.)
- [ ] 7.8 Dispatch-after-shutdown test: fire shutdown, then call `dispatch(...)`, assert WARN `event="fcm_dispatch_after_shutdown"` is logged AND no exception propagates.

## 8. Lint / invariants

- [ ] 8.1 `./gradlew :lint:detekt-rules:test` — extant Detekt rules (RawFromPostsRule, BlockExclusionJoinRule, SecretKeyHelperRule, etc.) still pass against the new module + wiring. If `RawFromPostsRule` was extended in 5.3, its own rule-test must pass.
- [ ] 8.2 `./gradlew ktlintCheck detekt` — full lint sweep across all modules including the new `:infra:fcm`.
- [ ] 8.3 Confirm via `grep -r "import com.google.firebase" --include='*.kt'` that `:infra:fcm` is the only module with Firebase Admin SDK imports.
- [ ] 8.4 Confirm via `grep -r "firebase-admin-sa"` that the only production reads of the secret name go through `secretKey(env, "firebase-admin-sa")` — no direct `System.getenv(...)` shortcuts.

## 9. Verification before pushing

- [ ] 9.1 `./gradlew ktlintCheck detekt :backend:ktor:test :infra:fcm:test :lint:detekt-rules:test` — full pre-push battery per [`CLAUDE.md`](CLAUDE.md) § Pre-push verification.
- [ ] 9.2 Build the staging Docker image locally (`./gradlew :backend:ktor:shadowJar` + the existing Dockerfile build); confirm `:infra:fcm` is in the resulting fat-JAR / image.
- [ ] 9.3 Confirm `dev/scripts/sync-readme.sh --check` exits 0 on the change branch (the README block was updated in 2.6).

## 10. Staging smoke test (post-deploy)

- [ ] 10.1 Operator populates `staging-firebase-admin-sa` GCP Secret Manager slot with the staging Firebase service-account JSON (if not already populated).
- [ ] 10.2 After staging deploy: register a test FCM token from a staging mobile build (or via curl with a synthetic token if mobile not available) → trigger `post_liked` notification (Bob likes Alice's post via the staging API) → verify Cloud Logging shows `event="fcm_dispatched"` AND the FCM push arrives on the test device (if real device available) OR the structured log confirms the call shape was correct (if synthetic token is used, the dispatch path still exercises end-to-end except the actual push delivery).
- [ ] 10.3 Inspect Cloud Logging for unexpected WARN `event="fcm_dispatch_failed"` rate over the first hour of staging traffic. Expected: zero.
- [ ] 10.4 Confirm the `user_fcm_tokens` row count is unchanged after the smoke test (no spurious deletes).

## 11. Documentation + follow-ups

- [ ] 11.1 If the `:shared:resources` server-side i18n pattern is later established (e.g., for chat copy), file a follow-up to migrate `PushCopy.bodyFor(...)` strings into it. Per `design.md` D4.
- [ ] 11.2 File a follow-up entry in `FOLLOW_UPS.md` for the deferred per-conversation push batching (max 1 push per 10s per conversation; burst-merge "3 pesan baru dari {username}" copy) — to be picked up when chat capability lands. Per `proposal.md` § Out of Scope.
- [ ] 11.3 If the `RawFromPostsRule` allowlist pattern was extended in 5.3, document the new annotation `// @allow-raw-users-read: notification-rendering` in [`openspec/project.md`](openspec/project.md) § Coding Conventions & CI Lint Rules (if/when that section enumerates allowlist annotations).
