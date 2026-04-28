## ADDED Requirements

### Requirement: `:infra:fcm` module SHALL encapsulate the Firebase Admin SDK with no vendor imports outside it

A new Gradle module `:infra:fcm` SHALL be added to `settings.gradle.kts`. It SHALL be the only module in the codebase that imports `com.google.firebase.*` types. Domain (`:core:domain`), data (`:core:data`), and backend (`:backend:ktor`) modules MUST NOT import any `com.google.firebase.*` symbol — they depend only on the `NotificationDispatcher` interface (existing in `:core:data`) and on a new `UserFcmTokenReader` interface SHALL be defined in `:core:data` so that `:infra:fcm` reads tokens via interface, not via JDBC.

The module SHALL declare a single new library dependency `firebase-admin` pinned in `gradle/libs.versions.toml`, accompanied by a row in [`docs/09-Versions.md`](../../../docs/09-Versions.md) Version Decisions table justifying the pin.

The new module SHALL be registered in [`dev/module-descriptions.txt`](../../../dev/module-descriptions.txt) with a one-line description, and `dev/scripts/sync-readme.sh --write` SHALL be run to refresh the auto-generated module list in the root README per the [`CLAUDE.md`](../../../CLAUDE.md) "Root README module list is auto-generated" rule.

#### Scenario: `:infra:fcm` is the only module that imports `com.google.firebase.*`

- **WHEN** the codebase is grepped for `import com.google.firebase` across all module sources
- **THEN** every match is under `infra/fcm/` (no matches under `core/domain/`, `core/data/`, `backend/ktor/`, or any other module)

#### Scenario: `firebase-admin` library is pinned in libs.versions.toml

- **WHEN** inspecting `gradle/libs.versions.toml`
- **THEN** a `firebase-admin` version is declared under `[versions]` AND a corresponding `firebase-admin` library is declared under `[libraries]`

#### Scenario: Version Decisions Log records the new pin

- **WHEN** inspecting [`docs/09-Versions.md`](../../../docs/09-Versions.md) Version Decisions table
- **THEN** a row exists for `com.google.firebase:firebase-admin` with the pinned version, pin date, rationale referencing this change, and a next-review quarter

#### Scenario: New module recorded in dev/module-descriptions.txt and root README

- **WHEN** inspecting [`dev/module-descriptions.txt`](../../../dev/module-descriptions.txt)
- **THEN** a line for `infra/fcm` exists with a one-line description

- **WHEN** inspecting the auto-generated module list block in the root README
- **THEN** the `infra/fcm` entry is present and matches the description (verifying `dev/scripts/sync-readme.sh --write` was run)

### Requirement: `UserFcmTokenReader` interface SHALL define the read + on-send-delete contract over `user_fcm_tokens`

An interface `UserFcmTokenReader` SHALL be defined in `:core:data` with two methods:

- `fun activeTokens(userId: UserId): List<FcmTokenRow>` — returns every row from `user_fcm_tokens WHERE user_id = :userId`. The result is ordered platform-stable (e.g., `ORDER BY platform, token` so test snapshots are deterministic) but the order MUST NOT carry semantic meaning to callers. The query SHALL execute via a single index lookup using `user_fcm_tokens_user_idx`.
- `fun deleteToken(userId: UserId, platform: String, token: String): Int` — deletes exactly the row matching the `(user_id, platform, token)` triple via the UNIQUE index. Returns the number of rows deleted (0 or 1). The delete SHALL execute as a single index-lookup DELETE.

The data class `FcmTokenRow` SHALL carry `platform: String` and `token: String` (no other fields are needed for dispatch; `app_version` is metadata-only and MAY be omitted from the row).

The production implementation MUST live in `:backend:ktor` (which owns the JDBC/JOOQ surface). `:infra:fcm` consumes the interface, never JDBC directly.

#### Scenario: activeTokens returns rows for the user only

- **WHEN** `user_fcm_tokens` contains 2 rows for user A and 1 row for user B AND `activeTokens(userA)` is invoked
- **THEN** the returned list has exactly 2 entries (both belonging to user A) AND none of user B's rows leak

#### Scenario: activeTokens returns empty list for user with no tokens

- **WHEN** no rows exist in `user_fcm_tokens` for user A AND `activeTokens(userA)` is invoked
- **THEN** the returned list is empty (no exception)

#### Scenario: deleteToken returns 1 when the row exists

- **WHEN** a row `(userA, 'android', 'tok-1')` exists AND `deleteToken(userA, "android", "tok-1")` is invoked
- **THEN** the row is removed from `user_fcm_tokens` AND the return value is 1

#### Scenario: deleteToken returns 0 when no matching row exists

- **WHEN** no row matches `(userA, 'android', 'tok-missing')` AND `deleteToken(userA, "android", "tok-missing")` is invoked
- **THEN** the return value is 0 AND no other rows are affected

#### Scenario: deleteToken does not affect peer tokens

- **WHEN** rows exist for `(userA, 'android', 'tok-1')` AND `(userA, 'android', 'tok-2')` AND `(userA, 'ios', 'tok-3')` AND `deleteToken(userA, "android", "tok-1")` is invoked
- **THEN** only the first row is removed; the other two persist intact

### Requirement: `FcmDispatcher` SHALL implement `NotificationDispatcher` and dispatch per-token in parallel after DB commit

A class `FcmDispatcher` SHALL implement the existing `NotificationDispatcher` interface (defined in `:core:data` per the [`in-app-notifications` capability](../../specs/in-app-notifications/spec.md)). It SHALL be constructor-injected with:

- `userFcmTokenReader: UserFcmTokenReader`
- `firebaseMessaging: FirebaseMessaging` (the Firebase Admin SDK type, available only inside `:infra:fcm`)
- `pushCopy: PushCopy` (the in-source localization helper)
- `actorUsernameLookup: ActorUsernameLookup` (a small one-method interface defined in `:core:data` returning `String?` for an `actor_user_id`; production impl lives in `:backend:ktor` and reads from the `users` table)
- `dispatcherScope: CoroutineScope` (production: backed by `Dispatchers.IO.limitedParallelism(8)`; test: `Dispatchers.Unconfined` or test-specific scope)

`dispatch(notification: NotificationDto)` SHALL:

1. Be invoked by `NotificationService.emit()` AFTER the DB commit succeeds (contract preserved verbatim from `in-app-notifications`).
2. Look up active tokens via `userFcmTokenReader.activeTokens(notification.userId)`.
3. If the result is empty, log a single structured INFO line with `event="fcm_skipped_no_tokens"`, `user_id`, `notification_type` AND return without further work.
4. Look up the `actor_username` via `actorUsernameLookup.lookup(notification.actorUserId)` if `notification.actorUserId != null`; otherwise pass `null`.
5. For each `FcmTokenRow` in the active tokens list, build a platform-specific FCM `Message` via the per-platform builders (Android or iOS) and submit `firebaseMessaging.send(message)` on the `dispatcherScope`. The dispatch fanout SHALL be parallel (one coroutine `launch` per token); the function returns immediately without awaiting completion (the request thread that originally emitted the notification is not blocked on FCM round-trips).
6. On a successful FCM send, log a structured INFO line `event="fcm_dispatched"` with `user_id`, `platform`, `notification_type`, `message_id` (FCM-returned).
7. On a `FirebaseMessagingException` whose `MessagingErrorCode` is `UNREGISTERED` (HTTP 404), `INVALID_ARGUMENT` (HTTP 410), OR `SENDER_ID_MISMATCH`, invoke `userFcmTokenReader.deleteToken(userId, platform, token)` and log a structured INFO line `event="fcm_token_pruned"` with `user_id`, `platform`, `error_code`, `rows_deleted`.
8. On any other `FirebaseMessagingException` (`QUOTA_EXCEEDED`, `UNAVAILABLE`, `INTERNAL`) OR any other throwable (network, timeout, deserialization), log a structured WARN line `event="fcm_dispatch_failed"` with `user_id`, `platform`, `error_code` (or `"unknown"`), `notification_type`, AND do NOT delete the token. The throwable MUST NOT propagate out of the dispatcher (the primary write transaction has already committed; a push failure is not a request failure).

`FcmDispatcher` MUST NOT log the raw `token` value at any severity. WARN logs MAY include `token_present=true` or `token_length=<int>` only — same posture as the `fcm-token-registration` requirement "Validation errors MUST use a closed vocabulary" sub-clause on token confidentiality.

#### Scenario: Recipient with zero tokens skips dispatch and logs INFO

- **WHEN** `dispatch(notification)` is invoked for a recipient with zero rows in `user_fcm_tokens`
- **THEN** no FCM Admin SDK call is made AND a single structured INFO log is emitted with `event="fcm_skipped_no_tokens"`, `user_id`, AND `notification_type`

#### Scenario: Recipient with multiple tokens triggers parallel dispatch

- **WHEN** `dispatch(notification)` is invoked for a recipient with 2 Android rows and 1 iOS row
- **THEN** exactly 3 `firebaseMessaging.send(...)` calls are made (one per token) AND each carries the platform-correct payload shape (Android = data-only no-notification-block; iOS = alert + mutable-content)

#### Scenario: 404 UNREGISTERED prunes the specific token only

- **WHEN** `dispatch(notification)` is invoked for a recipient with rows `(userA, 'android', 'tok-stale')` AND `(userA, 'android', 'tok-live')` AND FCM responds `UNREGISTERED` for `tok-stale` AND succeeds for `tok-live`
- **THEN** the `tok-stale` row is deleted from `user_fcm_tokens` AND the `tok-live` row persists AND the dispatcher logs INFO `event="fcm_token_pruned"` for the stale token AND INFO `event="fcm_dispatched"` for the live token AND no exception escapes

#### Scenario: 410 INVALID_ARGUMENT prunes identically to 404

- **WHEN** FCM responds `INVALID_ARGUMENT` (HTTP 410) for a token
- **THEN** that token's row is deleted via `deleteToken(...)` AND the structured INFO log carries `error_code="INVALID_ARGUMENT"`

#### Scenario: SENDER_ID_MISMATCH prunes identically

- **WHEN** FCM responds `SENDER_ID_MISMATCH` for a token
- **THEN** that token's row is deleted AND the structured INFO log carries `error_code="SENDER_ID_MISMATCH"`

#### Scenario: 5xx-class errors do NOT delete the token

- **WHEN** FCM responds `INTERNAL` OR `UNAVAILABLE` OR `QUOTA_EXCEEDED` for a token
- **THEN** the corresponding `user_fcm_tokens` row is NOT deleted AND a structured WARN log is emitted with `event="fcm_dispatch_failed"` AND `error_code="<code>"` AND no exception escapes

#### Scenario: Network timeout does NOT delete the token

- **WHEN** the FCM Admin SDK call throws a non-`FirebaseMessagingException` (e.g., a network timeout)
- **THEN** the token row is NOT deleted AND a structured WARN log is emitted with `event="fcm_dispatch_failed"` AND `error_code="unknown"`

#### Scenario: Dispatcher does NOT log the raw token value

- **WHEN** any of the above WARN-emitting failure scenarios fires AND the failing token is the well-known test value `"sentinel-token-string-DO-NOT-LEAK"`
- **THEN** the captured log appender's output does NOT contain the literal string `"sentinel-token-string-DO-NOT-LEAK"` (token-confidentiality posture preserved)

#### Scenario: Dispatcher returns immediately without awaiting FCM round-trips

- **WHEN** `dispatch(notification)` is invoked AND each FCM Admin SDK call has a 500ms latency injected
- **THEN** `dispatch(notification)` returns to its caller in <50ms wall-clock (the launches are fanned out on the dispatcher scope; the caller is not blocked)

#### Scenario: Exception in one token's send does not block the others

- **WHEN** `dispatch(notification)` is invoked for a recipient with 3 tokens AND the first FCM send throws an unchecked exception (`RuntimeException`)
- **THEN** the second and third tokens are still dispatched (each on its own coroutine launch) AND the dispatcher returns no exception to the caller AND a WARN log line is emitted for the failing token

### Requirement: Android payload SHALL be data-only with `priority: "high"` and required data keys

For each row whose `platform = "android"`, `FcmDispatcher` SHALL build an FCM `Message` with the following shape:

- The `notification` block (alert payload) MUST be empty / unset. The Android payload is **data-only** per [`docs/04-Architecture.md:506`](../../../docs/04-Architecture.md) (the app handles rendering locally with the user's preview-toggle preference check).
- An `AndroidConfig` block with `priority = HIGH`.
- Data fields populated from the `NotificationDto`:
  - `type` — the notification type string (e.g., `"post_liked"`).
  - `actor_user_id` — the actor's UUID as string, or empty string if `dto.actorUserId == null`.
  - `target_type` — `dto.targetType` (e.g., `"post"`), or empty string if null.
  - `target_id` — `dto.targetId` as string, or empty string if null.
  - `body_data` — `dto.bodyData` JSON-stringified (FCM data fields must be strings), or empty string if null. The client MUST parse this back into JSON for in-app rendering.
- The `token` set to the row's `token` value.

#### Scenario: Android payload has no notification block

- **WHEN** an Android push is constructed
- **THEN** the resulting `Message`'s `notification` field is null/unset (data-only mode confirmed)

#### Scenario: Android payload sets priority HIGH

- **WHEN** an Android push is constructed
- **THEN** the resulting `Message`'s `AndroidConfig.priority == HIGH`

#### Scenario: Android payload includes `type`, `target_type`, `target_id` data fields

- **WHEN** an Android push is constructed for a `post_liked` notification with `target_type="post"`, `target_id=<uuid>`
- **THEN** the data map contains `"type" -> "post_liked"` AND `"target_type" -> "post"` AND `"target_id" -> "<uuid-as-string>"`

#### Scenario: Android payload includes JSON-stringified `body_data`

- **WHEN** an Android push is constructed for a notification with `body_data = {"post_excerpt": "Hi from Jakarta"}`
- **THEN** the data map contains `"body_data"` whose value is the JSON string `{"post_excerpt":"Hi from Jakarta"}` (or equivalent canonical form parseable as JSON)

#### Scenario: Android payload tolerates null actor and target

- **WHEN** an Android push is constructed for a system-emitted notification (`actor_user_id = NULL`, `target_type = NULL`, `target_id = NULL` — e.g., `post_auto_hidden` for a reply target uses `target_type='reply'` so this is the `privacy_flip_warning` shape)
- **THEN** the data map contains `"actor_user_id" -> ""`, `"target_type" -> ""`, `"target_id" -> ""` (no key omission; consumers can rely on key presence with empty-string semantics)

### Requirement: iOS payload SHALL be alert + mutable-content with `body_full` data field

For each row whose `platform = "ios"`, `FcmDispatcher` SHALL build an FCM `Message` with the following shape:

- A `Notification` block with `title` (per-type via `PushCopy.titleFor(type)`) and `body` (per-type via `PushCopy.bodyFor(notification, actor_username)`).
- An `ApnsConfig` block with `aps.mutableContent = true`. This is the flag the future iOS Notification Service Extension consumes to optionally rewrite the body based on the on-device preview-toggle preference per [`docs/04-Architecture.md:535-540`](../../../docs/04-Architecture.md).
- A custom data field `body_full` carrying the JSON-stringified `dto.bodyData`. The NSE rewrites the body based on this field if the preview-toggle is ON.
- The `token` set to the row's `token` value.

The iOS payload MUST NOT include the same data block as Android (the iOS NSE consumes `body_full` only; other data routing is via `aps.category` etc., which is out of scope for this change).

#### Scenario: iOS payload has alert title and body

- **WHEN** an iOS push is constructed for a `post_liked` notification by actor "bobby"
- **THEN** the resulting `Message`'s `notification.title` is `"NearYou"` AND `notification.body` matches the format `"bobby menyukai post-mu"` (per `PushCopy` rules)

#### Scenario: iOS payload sets aps.mutableContent = true

- **WHEN** an iOS push is constructed
- **THEN** the resulting `Message`'s `ApnsConfig.aps.mutableContent` equals true

#### Scenario: iOS payload carries body_full as JSON-stringified body_data

- **WHEN** an iOS push is constructed for a notification with `body_data = {"post_excerpt": "Hi from Jakarta"}`
- **THEN** the `ApnsConfig.payload.body_full` is the JSON string `{"post_excerpt":"Hi from Jakarta"}`

#### Scenario: iOS payload uses fallback copy for unwired notification types

- **WHEN** an iOS push is constructed for a notification of type `chat_message` (not yet emitted as of this change but admitted by the V10 enum)
- **THEN** the `notification.body` is the fallback copy `"Notifikasi baru dari NearYou"` (per `PushCopy` fallback rule) AND no exception is thrown

### Requirement: `PushCopy` SHALL provide Indonesian copy for the four V10-wired types and a fallback for others

A Kotlin object `PushCopy` SHALL be defined in `:infra:fcm` with at minimum:

- `fun titleFor(type: String): String` — returns the constant `"NearYou"` for all known types and the fallback (no per-type title differentiation in this change).
- `fun bodyFor(notification: NotificationDto, actorUsername: String?): String` — returns Indonesian copy parameterized by the actor username (when available) and notification type:
  - `post_liked`: `"<actor_username> menyukai post-mu"` (or `"Seseorang menyukai post-mu"` when `actorUsername == null`)
  - `post_replied`: `"<actor_username> membalas post-mu"` (or `"Seseorang membalas post-mu"` when `actorUsername == null`)
  - `followed`: `"<actor_username> mulai mengikuti kamu"` (or `"Seseorang mulai mengikuti kamu"` when `actorUsername == null`)
  - `post_auto_hidden`: `"Postinganmu disembunyikan otomatis karena beberapa laporan"` (no actor; system-emitted)
  - Any other type (the 9 not yet emitted): `"Notifikasi baru dari NearYou"` (fallback)

`PushCopy` MUST NOT call any external service, MUST NOT read any database row, AND MUST NOT depend on Moko Resources (Moko Resources is a KMP client concern; backend strings are server-side i18n per `design.md` D4).

#### Scenario: post_liked body uses actor username when present

- **WHEN** `bodyFor(post_liked_notification, actorUsername = "bobby")` is invoked
- **THEN** the result is `"bobby menyukai post-mu"`

#### Scenario: post_liked body falls back when actor username is null

- **WHEN** `bodyFor(post_liked_notification, actorUsername = null)` is invoked
- **THEN** the result is `"Seseorang menyukai post-mu"`

#### Scenario: post_auto_hidden body is a constant system string

- **WHEN** `bodyFor(post_auto_hidden_notification, actorUsername = null)` is invoked
- **THEN** the result is `"Postinganmu disembunyikan otomatis karena beberapa laporan"`

#### Scenario: Unknown / unwired type returns the fallback

- **WHEN** `bodyFor(chat_message_notification, actorUsername = "bobby")` is invoked (chat emit-site has not shipped as of this change but the type is admitted by the V10 enum)
- **THEN** the result is `"Notifikasi baru dari NearYou"` AND no exception is thrown

#### Scenario: titleFor returns "NearYou" for every known type

- **WHEN** `titleFor("post_liked")`, `titleFor("post_replied")`, `titleFor("followed")`, `titleFor("post_auto_hidden")`, `titleFor("chat_message")` are each invoked
- **THEN** every call returns `"NearYou"`

### Requirement: Service-account JSON SHALL be read via `secretKey(env, name)` and validated at boot

Backend startup SHALL call `secretKey(env, "firebase-admin-sa")` to fetch the Firebase service-account JSON, parse it, and call `FirebaseApp.initializeApp(...)` exactly once with a named `FirebaseApp` instance (`"nearyou-default"`). In the staging environment, the helper resolves to the staging-namespaced slot `staging-firebase-admin-sa` per the [`secretKey(env, name)` invariant in CLAUDE.md](../../../CLAUDE.md).

If the secret read fails OR the JSON is malformed OR the Firebase Admin SDK rejects the credential, the application SHALL fail to start with a structured FATAL log naming the exact secret slot. Cloud Run health checks fail; the deploy does not roll forward.

In test profiles, the Firebase Admin SDK initialization SHALL be skipped entirely AND the Koin DI module SHALL bind `NotificationDispatcher` to `InAppOnlyDispatcher` (the existing no-op binding from `in-app-notifications` V10). Tests that exercise FCM dispatch behavior bind a mock `FirebaseMessaging` directly into a test-only `FcmDispatcher` instance.

The Firebase service-account JSON MUST NOT be read via direct `System.getenv("FIREBASE_ADMIN_SA")` or any other environment-variable read that bypasses the `secretKey(env, name)` helper. Direct reads are a Detekt violation per the [`secretKey(env, name)` invariant in CLAUDE.md](../../../CLAUDE.md).

#### Scenario: Production startup with valid service-account JSON succeeds

- **WHEN** `secretKey(env, "firebase-admin-sa")` returns valid Firebase service-account JSON AND the backend starts in the production profile
- **THEN** `FirebaseApp.initializeApp(...)` is called exactly once with name `"nearyou-default"` AND the application reaches the ready-to-serve state

#### Scenario: Missing service-account secret fails the production deploy

- **WHEN** the `firebase-admin-sa` secret slot is empty / missing AND the backend starts in the production profile
- **THEN** the application fails to start AND a structured FATAL log is emitted naming the secret slot `firebase-admin-sa`

#### Scenario: Malformed service-account JSON fails the production deploy

- **WHEN** the `firebase-admin-sa` secret returns a non-JSON string OR a JSON object missing required Firebase service-account fields AND the backend starts in the production profile
- **THEN** the application fails to start AND a structured FATAL log is emitted indicating credential parse failure

#### Scenario: Staging profile reads the staging-namespaced secret

- **WHEN** the backend starts in the staging profile AND `secretKey(env, "firebase-admin-sa")` is invoked
- **THEN** the helper resolves to the GCP Secret Manager slot named `staging-firebase-admin-sa` (per the namespace convention recorded in [`docs/08-Roadmap-Risk.md:51`](../../../docs/08-Roadmap-Risk.md))

#### Scenario: Test profile skips Firebase Admin SDK initialization

- **WHEN** the backend starts in a test profile (e.g., the integration-test Koin module is loaded)
- **THEN** `FirebaseApp.initializeApp(...)` is NOT invoked AND the bound `NotificationDispatcher` is `InAppOnlyDispatcher` (the V10 no-op binding) unless a test-only override is explicitly installed

#### Scenario: No direct env-var read of the service-account name

- **WHEN** the codebase is grepped for the literal string `"firebase-admin-sa"` outside of `secretKey(env, "firebase-admin-sa")` invocations
- **THEN** zero matches are found in production source (test fixtures may reference the name; production code reads exclusively via the helper)

### Requirement: DI graph SHALL bind a composite dispatcher in production and `InAppOnlyDispatcher` in tests

The Koin DI module for `:backend:ktor` production startup SHALL bind `NotificationDispatcher` to a composite implementation (`FcmAndInAppDispatcher`) that calls both `FcmDispatcher.dispatch(notification)` AND `InAppOnlyDispatcher.dispatch(notification)` in sequence. The `InAppOnlyDispatcher`'s INFO log line is preserved (audit trail of every emit) AND the new FCM dispatch fires alongside it.

The Koin DI module for `:backend:ktor` test startup (the integration-test profile) SHALL bind `NotificationDispatcher` to `InAppOnlyDispatcher` only — no FCM dispatch in tests by default. Tests that explicitly want to exercise `FcmDispatcher` MUST install a test-only override module that binds it with a mocked `FirebaseMessaging`.

This change SHALL NOT modify `NotificationService` or any of the four V10 emit-site services (`LikeService`, `ReplyService`, `FollowService`, `ReportService`). The integration is DI-only, behind the existing `NotificationDispatcher` interface.

#### Scenario: Production DI binds the composite

- **WHEN** the backend starts in the production profile AND `Koin.get<NotificationDispatcher>()` is resolved
- **THEN** the resolved instance is the composite `FcmAndInAppDispatcher` (or equivalent class name) AND `dispatch(notification)` invokes both `FcmDispatcher.dispatch(...)` AND `InAppOnlyDispatcher.dispatch(...)` exactly once each

#### Scenario: Test DI binds InAppOnlyDispatcher only

- **WHEN** the integration-test Koin module is loaded AND `Koin.get<NotificationDispatcher>()` is resolved
- **THEN** the resolved instance is `InAppOnlyDispatcher` AND no FCM dispatch occurs during the default test run

#### Scenario: NotificationService source is unchanged

- **WHEN** comparing the source of `NotificationService` before and after this change lands
- **THEN** the diff for `NotificationService` is empty AND the four emit-site services (`LikeService`, `ReplyService`, `FollowService`, `ReportService`) likewise have empty diffs (the integration is purely additive in `:infra:fcm` and DI wiring)

### Requirement: Dispatcher coroutine scope SHALL drain on JVM shutdown

The `fcmDispatcherScope` SHALL be registered with a JVM shutdown hook that invokes `cancelAndJoin()` with a 5-second timeout. In-flight FCM dispatches that complete within the timeout reach FCM normally; dispatches that exceed the timeout are abandoned (the recipient sees the notification on next app open via the in-app `notifications` list per the `docs/04-Architecture.md:558` fallback). New `dispatch(...)` calls received after shutdown initiation observe a closed scope and SHALL fall through to a structured WARN log `event="fcm_dispatch_after_shutdown"` without throwing.

#### Scenario: Shutdown hook drains in-flight dispatches up to 5 seconds

- **WHEN** the JVM receives `SIGTERM` AND 3 FCM dispatches are in-flight, each with a 1-second simulated FCM round-trip
- **THEN** all 3 dispatches complete (under the 5-second timeout) AND the JVM exits cleanly

#### Scenario: Dispatches exceeding the 5-second drain are abandoned

- **WHEN** the JVM receives `SIGTERM` AND 1 FCM dispatch is in-flight with a 10-second simulated FCM round-trip
- **THEN** the dispatch is canceled at the 5-second mark AND the JVM exits AND no exception escapes the dispatcher

#### Scenario: Dispatch after shutdown logs WARN and does not throw

- **WHEN** `dispatch(notification)` is invoked AFTER the shutdown hook has fired
- **THEN** a structured WARN log is emitted with `event="fcm_dispatch_after_shutdown"` AND the call returns normally (no exception propagates back to the emit site)
