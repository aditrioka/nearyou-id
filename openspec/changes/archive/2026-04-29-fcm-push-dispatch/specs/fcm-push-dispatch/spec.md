## ADDED Requirements

### Requirement: `:infra:fcm` module SHALL encapsulate the Firebase Admin SDK with no vendor imports outside it

A new Gradle module `:infra:fcm` SHALL be added to `settings.gradle.kts`. It SHALL be the only module in the codebase that imports `com.google.firebase.*` types. Domain (`:core:domain`), data (`:core:data`), and backend (`:backend:ktor`) modules MUST NOT import any `com.google.firebase.*` symbol — they depend only on the `NotificationDispatcher` interface (existing in `:core:data`) and on a new `UserFcmTokenReader` interface SHALL be defined in `:core:data` so that `:infra:fcm` reads tokens via interface, not via JDBC.

The module SHALL declare a single new library dependency `firebase-admin` pinned in `gradle/libs.versions.toml`, accompanied by a row in [`docs/09-Versions.md`](../../../docs/09-Versions.md) Version Decisions table justifying the pin.

The new module SHALL be registered in [`dev/module-descriptions.txt`](../../../dev/module-descriptions.txt) with a one-line description, and `dev/scripts/sync-readme.sh --write` SHALL be run to refresh the auto-generated module list in the root README per the [`CLAUDE.md`](../../../CLAUDE.md) "Root README module list is auto-generated" rule.

#### Scenario: `:infra:fcm` is the only module that imports `com.google.firebase.*` for behavior

- **WHEN** the codebase is grepped for `import com.google.firebase` across all module sources
- **THEN** every match is under `infra/fcm/` EXCEPT for narrowly-scoped DI-binding signatures in `:backend:ktor` Koin modules per `design.md` D16 (the production Koin module must reference `FirebaseMessaging` to construct `FcmDispatcher` — this is a type-only reference used solely for DI binding, not behavior); the boundary scenario admits this single edge

#### Scenario: `:backend:ktor` Firebase imports are scoped to DI-binding files only

- **WHEN** the codebase is grepped for `import com.google.firebase` across `backend/ktor/src/main/kotlin/`
- **THEN** the only matches are inside Koin module / DI-binding files (e.g., `KoinModule.kt`, `FcmModule.kt`) AND no matches are found in business-logic files (services, repositories, handlers)

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

### Requirement: `UserFcmTokenReader` interface SHALL define the read + on-send-delete contract over `user_fcm_tokens` with a re-registration race guard

An interface `UserFcmTokenReader` SHALL be defined in `:core:data` with two methods:

- `fun activeTokens(userId: UserId): TokenSnapshot` — returns every row from `user_fcm_tokens WHERE user_id = :userId` AND the `dispatchStartedAt: Instant` value captured via `SELECT NOW()` **on the same DB connection as the token-read** (per `design.md` D12: both ends of the race-guard predicate MUST come from the DB clock domain to avoid JVM-vs-DB clock-skew false-deletes). The token list is ordered platform-stable (e.g., `ORDER BY platform, token` so test snapshots are deterministic) but the order MUST NOT carry semantic meaning to callers. The query SHALL execute via a single index lookup using `user_fcm_tokens_user_idx`.
- `fun deleteTokenIfStale(userId: UserId, platform: String, token: String, dispatchStartedAt: Instant): Int` — deletes the row matching the `(user_id, platform, token)` triple **only if `last_seen_at <= :dispatchStartedAt`**. The `dispatchStartedAt` predicate is the re-registration race guard per `design.md` D12: a row whose `last_seen_at` is later than the dispatcher's read-time has been re-registered by a fresh `POST /api/v1/user/fcm-token` upsert during the dispatch window, and MUST NOT be deleted. Returns the number of rows deleted (0 — predicate did not match OR no row exists, OR 1). The delete SHALL execute as a single index-lookup DELETE.

The data class `FcmTokenRow` SHALL carry `platform: String`, `token: String`, AND `lastSeenAt: Instant` (the latter is read from the existing `user_fcm_tokens.last_seen_at` column at query time and SHOULD be exposed so dispatchers MAY make additional decisions; production callers may ignore it but the field is required so the integration-test surface can assert race behaviour). The data class `TokenSnapshot` SHALL carry `tokens: List<FcmTokenRow>` AND `dispatchStartedAt: Instant` — the latter being the DB-clock `NOW()` captured atomically with the token read.

The production implementation MUST live in `:backend:ktor` (which owns the JDBC/JOOQ surface). `:infra:fcm` consumes the interface, never JDBC directly.

#### Scenario: activeTokens returns rows for the user only

- **WHEN** `user_fcm_tokens` contains 2 rows for user A and 1 row for user B AND `activeTokens(userA)` is invoked
- **THEN** the returned list has exactly 2 entries (both belonging to user A) AND none of user B's rows leak

#### Scenario: activeTokens returns empty list for user with no tokens

- **WHEN** no rows exist in `user_fcm_tokens` for user A AND `activeTokens(userA)` is invoked
- **THEN** the returned list is empty (no exception)

#### Scenario: deleteTokenIfStale returns 1 when the row exists and predicate matches

- **WHEN** a row `(userA, 'android', 'tok-1', last_seen_at = T_old)` exists AND `deleteTokenIfStale(userA, "android", "tok-1", dispatchStartedAt = T_dispatch)` is invoked WITH `T_old <= T_dispatch`
- **THEN** the row is removed from `user_fcm_tokens` AND the return value is 1

#### Scenario: deleteTokenIfStale returns 0 when re-registration races

- **WHEN** a row `(userA, 'android', 'tok-1', last_seen_at = T_fresh)` exists AND `deleteTokenIfStale(userA, "android", "tok-1", dispatchStartedAt = T_dispatch)` is invoked WITH `T_fresh > T_dispatch` (the row was upserted *after* the dispatcher started)
- **THEN** the return value is 0 AND the row remains in `user_fcm_tokens` (race guard preserved the just-re-registered token)

#### Scenario: deleteTokenIfStale returns 0 when no matching row exists

- **WHEN** no row matches `(userA, 'android', 'tok-missing')` AND `deleteTokenIfStale(userA, "android", "tok-missing", dispatchStartedAt = T_dispatch)` is invoked
- **THEN** the return value is 0 AND no other rows are affected

#### Scenario: deleteTokenIfStale does not affect peer tokens

- **WHEN** rows exist for `(userA, 'android', 'tok-1')` AND `(userA, 'android', 'tok-2')` AND `(userA, 'ios', 'tok-3')` AND `deleteTokenIfStale(userA, "android", "tok-1", dispatchStartedAt = T_dispatch)` is invoked AND the predicate matches `tok-1`'s `last_seen_at`
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
2. Look up active tokens AND the `dispatchStartedAt` timestamp atomically via `userFcmTokenReader.activeTokens(notification.userId)` — both come from the DB clock domain (`SELECT NOW()` on the same connection as the token query) per `design.md` D12. The `dispatchStartedAt` is captured BEFORE the dispatcher fans out FCM calls; it is propagated to `deleteTokenIfStale(...)` later in the dispatch lifecycle as the race-guard predicate.
3. If the result is empty, log a single structured INFO line with `event="fcm_skipped_no_tokens"`, `user_id`, `notification_type` AND return without further work.
4. Look up the `actor_username` via `actorUsernameLookup.lookup(notification.actorUserId)` if `notification.actorUserId != null`; otherwise pass `null`. The lookup reads from `visible_users` (NOT raw `users`) so shadow-banned and deleted actors return `null`, which the downstream `PushCopy.bodyFor(notification, actorUsername=null)` renders as the actor-less fallback (per design D13).
5. For each `FcmTokenRow` in the active tokens list, build a platform-specific FCM `Message` via the per-platform builders (Android or iOS) and submit `firebaseMessaging.send(message)` on the `dispatcherScope`. The dispatch fanout SHALL be parallel (one coroutine `launch` per token); the function returns immediately without awaiting completion (the request thread that originally emitted the notification is not blocked on FCM round-trips).
6. On a successful FCM send, log a structured INFO line `event="fcm_dispatched"` with `user_id`, `platform`, `notification_type`, `message_id` (FCM-returned). The log line MUST NOT include the raw `token` value.
7. On a `FirebaseMessagingException` whose `MessagingErrorCode` is `UNREGISTERED` OR `SENDER_ID_MISMATCH` (the two unambiguously-permanent token-failure codes per `design.md` D6), invoke `userFcmTokenReader.deleteTokenIfStale(userId, platform, token, dispatchStartedAt)` AND:
   - If the call returns 1: log structured INFO `event="fcm_token_pruned"` with `user_id`, `platform`, `error_code`, `rows_deleted=1`.
   - If the call returns 0: log structured INFO `event="fcm_token_prune_skipped_re_registered"` with `user_id`, `platform`, `error_code`, `rows_deleted=0` (the row was either re-registered during the dispatch window — race-guarded — or had already been removed by a peer dispatcher).
8. On a `FirebaseMessagingException` whose `MessagingErrorCode` is `INVALID_ARGUMENT` OR `QUOTA_EXCEEDED` OR `UNAVAILABLE` OR `INTERNAL`, OR on any non-`FirebaseMessagingException` (network, timeout, deserialization, `CancellationException` propagating from a per-launch cancellation), log a structured WARN line `event="fcm_dispatch_failed"` with `user_id`, `platform`, `error_code` (or `"unknown"`), `notification_type`, AND do NOT delete the token. `INVALID_ARGUMENT` is NOT a delete trigger because it is overloaded by the FCM Admin SDK (covers both stale-token-format AND oversized-payload — see `design.md` D6). The throwable MUST NOT propagate out of the dispatcher (the primary write transaction has already committed; a push failure is not a request failure). For `CancellationException`: the dispatcher's coroutine scope is a `SupervisorJob`, so a per-launch cancellation does NOT cascade to peer launches; the cancelled launch's WARN is suppressed (cancellation is a normal control-flow signal, not a failure) but peer launches complete normally.

`FcmDispatcher` MUST NOT log the raw `token` value at ANY severity (INFO, WARN, FATAL). All structured log lines emitted by the dispatcher (including the success-path `event="fcm_dispatched"` and the prune-path `event="fcm_token_pruned"` / `event="fcm_token_prune_skipped_re_registered"`) MAY include `token_length=<int>` or `token_hash_prefix=<8-char hex>` for forensic correlation, but never the literal token value — same posture as the `fcm-token-registration` requirement "Validation errors MUST use a closed vocabulary" sub-clause on token confidentiality, extended to cover the dispatcher's full log surface.

#### Scenario: Recipient with zero tokens skips dispatch and logs INFO

- **WHEN** `dispatch(notification)` is invoked for a recipient with zero rows in `user_fcm_tokens`
- **THEN** no FCM Admin SDK call is made AND a single structured INFO log is emitted with `event="fcm_skipped_no_tokens"`, `user_id`, AND `notification_type`

#### Scenario: Recipient with multiple tokens triggers parallel dispatch

- **WHEN** `dispatch(notification)` is invoked for a recipient with 2 Android rows and 1 iOS row
- **THEN** exactly 3 `firebaseMessaging.send(...)` calls are made (one per token) AND each carries the platform-correct payload shape (Android = data-only no-notification-block; iOS = alert + mutable-content)

#### Scenario: UNREGISTERED prunes the specific token only

- **WHEN** `dispatch(notification)` is invoked for a recipient with rows `(userA, 'android', 'tok-stale')` AND `(userA, 'android', 'tok-live')` AND FCM responds `UNREGISTERED` for `tok-stale` AND succeeds for `tok-live`
- **THEN** the `tok-stale` row is deleted from `user_fcm_tokens` via `deleteTokenIfStale(userA, "android", "tok-stale", dispatchStartedAt)` (race guard satisfied) AND the `tok-live` row persists AND the dispatcher logs INFO `event="fcm_token_pruned"` for the stale token AND INFO `event="fcm_dispatched"` for the live token AND no exception escapes

#### Scenario: SENDER_ID_MISMATCH prunes identically to UNREGISTERED

- **WHEN** FCM responds `SENDER_ID_MISMATCH` for a token
- **THEN** `deleteTokenIfStale(...)` is invoked AND the structured INFO log carries `error_code="SENDER_ID_MISMATCH"` AND if the predicate matches, `event="fcm_token_pruned"` AND `rows_deleted=1`

#### Scenario: INVALID_ARGUMENT does NOT delete the token

- **WHEN** FCM responds `INVALID_ARGUMENT` for a token (which is ambiguous between "stale-format token" and "oversized payload" per `design.md` D6)
- **THEN** the corresponding `user_fcm_tokens` row is NOT deleted (no `deleteTokenIfStale` call is made) AND a structured WARN log is emitted with `event="fcm_dispatch_failed"` AND `error_code="INVALID_ARGUMENT"` AND no exception escapes

#### Scenario: Re-registration race — token NOT deleted when row freshness post-dates dispatch start

- **WHEN** `dispatch(notification)` is invoked at `T_dispatch_start` for `(userA, 'android', 'tok-1', last_seen_at = T_old)` AND between read-time and FCM-response-time the user re-registers `tok-1` (upserting `last_seen_at = T_fresh` where `T_fresh > T_dispatch_start`) AND FCM responds `UNREGISTERED` for the original send
- **THEN** `deleteTokenIfStale(userA, "android", "tok-1", T_dispatch_start)` returns 0 (predicate `last_seen_at <= T_dispatch_start` does not match the now-fresh row) AND the row persists AND the dispatcher logs INFO `event="fcm_token_prune_skipped_re_registered"` AND `rows_deleted=0`

#### Scenario: 5xx-class errors do NOT delete the token

- **WHEN** FCM responds `INTERNAL` OR `UNAVAILABLE` OR `QUOTA_EXCEEDED` for a token
- **THEN** the corresponding `user_fcm_tokens` row is NOT deleted AND a structured WARN log is emitted with `event="fcm_dispatch_failed"` AND `error_code="<code>"` AND no exception escapes

#### Scenario: Network timeout does NOT delete the token

- **WHEN** the FCM Admin SDK call throws a non-`FirebaseMessagingException` (e.g., a network timeout)
- **THEN** the token row is NOT deleted AND a structured WARN log is emitted with `event="fcm_dispatch_failed"` AND `error_code="unknown"`

#### Scenario: Dispatcher does NOT log the raw token value at any severity

- **WHEN** any successful dispatch (INFO `fcm_dispatched`) OR any prune (INFO `fcm_token_pruned`) OR any failure path (WARN `fcm_dispatch_failed`) fires AND the affected token is the well-known test value `"sentinel-token-string-DO-NOT-LEAK"`
- **THEN** the captured log appender's output does NOT contain the literal string `"sentinel-token-string-DO-NOT-LEAK"` at any severity (INFO, WARN, FATAL — token-confidentiality posture covers the full log surface, not just WARN)

#### Scenario: Recipient hard-deleted between emit and dispatch

- **WHEN** `NotificationService.emit(...)` commits a notifications row for user A AND the user A row is hard-deleted (CASCADE removes `user_fcm_tokens` rows AND CASCADE removes the just-emitted `notifications` row) AND the dispatcher subsequently runs
- **THEN** `activeTokens(userA)` returns an empty list AND the dispatcher logs INFO `event="fcm_skipped_no_tokens"` AND no exception escapes (the recipient-CASCADE is a benign data-race; the dispatcher tolerates orphaned `userId`s gracefully)

#### Scenario: Actor hard-deleted between emit and dispatch

- **WHEN** a `notifications` row exists with `actor_user_id = userB` AND user B is hard-deleted between emit and dispatch (per the `actor_user_id ON DELETE SET NULL` FK, the column flips to NULL on the existing row) AND the dispatcher runs
- **THEN** the dispatcher reads the `notifications` row with `actor_user_id` now NULL AND `actorUsernameLookup.lookup(null)` returns null AND `PushCopy.bodyFor(notification, actorUsername=null)` produces the actor-less fallback string (e.g., `"Seseorang menyukai post-mu"` for `post_liked`) AND the FCM dispatch proceeds normally

#### Scenario: Dispatcher invocation count is exactly once per notifications row

- **WHEN** `NotificationService.emit(...)` commits a single notifications row AND `NotificationDispatcher.dispatch(...)` is invoked from the post-commit hook
- **THEN** the FCM Admin SDK send is invoked exactly once per active token (e.g., a recipient with 2 active tokens sees 2 sends total; not 4, not 0). Re-emitting the same `notifications` row is out-of-scope for this contract — emit-site retry safety is the emitter's responsibility, not the dispatcher's.

#### Scenario: Body data with quote-escape and Unicode round-trips

- **WHEN** `dispatch(notification)` is invoked for a notification whose `body_data = {"post_excerpt": "Hi from Jakarta — \"Selamat datang\" 中田 🎉"}` AND the recipient has one Android token AND one iOS token
- **THEN** the Android payload's `body_data` data field is the JSON-stringified value with all special characters preserved (parses back to the original via `JSON.parse(...)`) AND the iOS payload's `body_full` is the same stringified JSON AND no exception is thrown

#### Scenario: Body data IS NULL is rendered as empty-string in payload data fields

- **WHEN** `dispatch(notification)` is invoked for a notification whose `body_data IS NULL` (the `notifications.body_data` JSONB column is NULL — defensive case; the V10 emit-sites all populate body_data, but the column has no NOT NULL constraint)
- **THEN** the Android payload's `body_data` data field is the empty string `""` AND the iOS payload's `body_full` is the empty string `""` AND no exception is thrown

#### Scenario: Concurrent emits — second dispatch's prune attempt is a no-op when first dispatch already deleted

- **WHEN** two near-simultaneous `dispatch(notificationA)` and `dispatch(notificationB)` calls fire for the same recipient (e.g., a like + a follow within 50ms) AND both observe the same stale token AND FCM responds `UNREGISTERED` for both AND the first `deleteTokenIfStale(...)` deletes the row AND the second `deleteTokenIfStale(...)` runs after
- **THEN** the second `deleteTokenIfStale(...)` returns 0 (no row matches the predicate) AND the dispatcher logs INFO `event="fcm_token_prune_skipped_re_registered"` (semantically: skipped — but here because of peer-deletion, not re-registration; both observable states are "row no longer exists matching predicate") AND no exception is thrown

#### Scenario: Per-launch CancellationException does not cascade to peer launches

- **WHEN** `dispatch(notification)` is invoked for a recipient with 3 Android tokens AND the first launch's coroutine is cancelled mid-`firebaseMessaging.send(...)` (e.g., a fault-injection test) AND the dispatcher scope is a `SupervisorJob`
- **THEN** the cancelled launch terminates without propagating `CancellationException` to peer launches AND the second and third tokens are dispatched successfully AND the cancelled-launch WARN is suppressed (cancellation is a control-flow signal, not a failure) AND no exception escapes back to `NotificationService.emit(...)`

#### Scenario: Dispatcher returns immediately without awaiting FCM round-trips (token count ≤ pool size)

- **WHEN** `dispatch(notification)` is invoked for a recipient with 3 Android tokens AND each FCM Admin SDK call has a 500ms latency injected AND the dispatcher is configured with a real (non-`Unconfined`) production-shaped scope (`Dispatchers.IO.limitedParallelism(8)`)
- **THEN** `dispatch(notification)` returns to its caller in less than half the per-call latency (i.e., <250ms when injected latency is 500ms) — all 3 launches enqueue immediately on the pool; the caller is not blocked. This scenario uses a separate test-scope from the synchronous-assertion tests (which rely on `Dispatchers.Unconfined` for deterministic dispatch ordering); the latency-bound assertion explicitly opts out of `Unconfined` so the fanout actually happens on a worker pool.

#### Scenario: Dispatcher returns immediately even when token count exceeds the dispatcher pool size

- **WHEN** `dispatch(notification)` is invoked for a recipient with 12 active tokens (theoretical edge — same user on 12 devices) AND the dispatcher scope is `Dispatchers.IO.limitedParallelism(8)` AND each FCM call has 500ms latency injected
- **THEN** `dispatch(notification)` STILL returns to its caller in <250ms — the 9th-12th launches sit in the dispatcher's queue waiting for a worker slot, but `launch { ... }` enqueueing is non-blocking; the caller is not waiting for FCM round-trips even when tokens > pool size. The 9th-12th sends complete after the first 8 finish (so total dispatch wall-clock for the recipient is ~1000ms = 2 batches of 500ms), but the dispatch caller has long since returned.

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

#### Scenario: Android payload tolerates null body_data

- **WHEN** an Android push is constructed for a notification whose `body_data IS NULL`
- **THEN** the data map contains `"body_data" -> ""` (no exception, no key omission; consumers can rely on key presence with empty-string semantics)

### Requirement: iOS payload SHALL be alert + mutable-content with `body_full` data field, clamped to APNs 4 KB limit

For each row whose `platform = "ios"`, `FcmDispatcher` SHALL build an FCM `Message` with the following shape:

- A `Notification` block with `title` (per-type via `PushCopy.titleFor(type)`) and `body` (per-type via `PushCopy.bodyFor(notification, actor_username)`).
- An `ApnsConfig` block with `aps.mutableContent = true`. This is the flag the future iOS Notification Service Extension consumes to optionally rewrite the body based on the on-device preview-toggle preference per [`docs/04-Architecture.md:535-540`](../../../docs/04-Architecture.md).
- A custom data field `body_full` carrying the JSON-stringified `dto.bodyData`. The NSE rewrites the body based on this field if the preview-toggle is ON.
- The `token` set to the row's `token` value.

The iOS payload MUST NOT include the same data block as Android (the iOS NSE consumes `body_full` only; other data routing is via `aps.category` etc., which is out of scope for this change).

**APNs 4 KB clamp:** the assembled APNs payload (notification block + custom data) MUST stay under the 4 KB APNs hard limit. Per `design.md` D6, the iOS payload builder SHALL pre-clamp `body_full` to a safe ceiling (typically 3 KB after JSON-stringification, leaving headroom for the notification block + APNs envelope overhead). Truncation MAY drop trailing characters from the longest-field — typically `post_excerpt` or `reply_excerpt` — preserving the surrounding JSON shape (the truncated string is still valid JSON; structurally `{"post_excerpt": "Hi from Jakarta...", "reply_id": "uuid"}` retains both keys, only the excerpt is shortened).

**UTF-8 codepoint-boundary requirement:** truncation MUST cut on a Unicode codepoint boundary, NEVER mid-codepoint. Multi-byte UTF-8 characters (Indonesian diacritics like "Müller", CJK characters like "中田", emoji like "🎉") occupy 2–4 bytes; a naive byte-clamp at byte position N may slice mid-codepoint and produce invalid UTF-8, which (a) corrupts the embedded JSON, (b) causes APNs to reject the payload as malformed, and (c) burns the dispatch with `MessagingErrorCode.INVALID_ARGUMENT` (which per D6 is transient — but the next emit will hit the same bug). Implementation: use Kotlin's `String.take(n)` (which operates on `Char` boundaries, not byte boundaries; for emojis represented as surrogate pairs, additionally guard against splitting a surrogate pair) OR an explicit UTF-8-aware truncator. Tests MUST cover a multi-byte boundary case (e.g., a 4-byte emoji at byte position ~3000 in an oversized excerpt — naive byte-clamp breaks here).

The reason this matters: FCM's underlying APNs response surfaces oversized-payload AND malformed-payload as `MessagingErrorCode.INVALID_ARGUMENT`, which per `design.md` D6 is a transient WARN — without clamping (or with broken clamping), every push for an excerpt-heavy notification would silently fail with no observable signal beyond a steady WARN-rate increase.

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

#### Scenario: iOS payload clamps oversized body_full to stay under APNs 4 KB

- **WHEN** an iOS push is constructed for a `post_replied` notification whose `body_data.reply_excerpt` is a 5000-byte UTF-8 string (deliberately oversized; per the in-app-notifications spec it should be ≤ 80 code points, but the dispatcher MUST be defensive against an emit-site bug or a future spec change)
- **THEN** the assembled APNs payload size (notification block + custom data including `body_full`) is ≤ 4 KB AND the resulting `body_full` is the JSON-stringified `body_data` with `reply_excerpt` truncated AND the structure is valid JSON parseable by the iOS NSE AND the `reply_id` field (if any) is preserved intact

#### Scenario: iOS payload below the clamp threshold is unmodified

- **WHEN** an iOS push is constructed for a typical `post_liked` notification with a 50-codepoint `post_excerpt`
- **THEN** the assembled APNs payload size is well under 4 KB AND `body_full` carries the original JSON-stringified `body_data` verbatim (no clamping applied)

#### Scenario: iOS clamp cuts on UTF-8 codepoint boundary, not mid-codepoint

- **WHEN** an iOS push is constructed for a `post_replied` notification whose `body_data.reply_excerpt` is a 5000-byte UTF-8 string with a 4-byte emoji `🎉` at byte position ~3000 (i.e., the naive byte-clamp ceiling would slice the emoji's surrogate pair / multi-byte sequence)
- **THEN** the truncated `body_full` is valid UTF-8 (no orphan surrogate or partial multi-byte sequence) AND parses back as valid JSON AND the truncation point falls cleanly before or after the emoji — never inside it

#### Scenario: iOS clamp pathology — body_data has no single field large enough to truncate

- **WHEN** an iOS push is constructed for a notification with an unusually large but uniform `body_data` (e.g., 20 small fields totaling >4 KB, with no single field dominating)
- **THEN** the implementation MUST either (a) drop the dispatch entirely with a structured WARN `event="fcm_dispatch_failed"` `error_code="payload_too_large"` (no FCM call made; recipient sees the in-app notification per the docs/04-Architecture.md:558 fallback), OR (b) apply ordered-truncation across multiple fields per a documented strategy. Option (a) is the simpler default; option (b) requires explicit doc + scenario coverage

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

### Requirement: DI graph SHALL bind a composite dispatcher in production and `InAppOnlyDispatcher` in tests, with catch-and-log composite error handling

The Koin DI module for `:backend:ktor` production startup SHALL bind `NotificationDispatcher` to a composite implementation (`FcmAndInAppDispatcher`) that invokes `FcmDispatcher.dispatch(notification)` first AND `InAppOnlyDispatcher.dispatch(notification)` second. "First" and "second" describe **call-order**, NOT completion-order: `FcmDispatcher.dispatch(...)` enqueues coroutines on the background `fcmDispatcherScope` and returns synchronously per `design.md` D2; `InAppOnlyDispatcher.dispatch(...)` is a synchronous log line. The in-app log is the audit trail (always lands); the FCM push completes asynchronously.

The composite SHALL wrap each delegate call in its own try/catch per `design.md` D14. Any exception caught is logged at ERROR severity with `event="fcm_composite_dispatcher_unexpected_error"`, `delegate=<className>`, `notification_id`, `notification_type`. A counter metric `fcm_composite_unexpected_error_total{delegate=...}` SHALL be incremented (Cloud Monitoring custom metric or OTEL counter — implementer's choice) so a Cloud Monitoring alert at "rate > 5/min" can page on noisy regressions while a one-shot ERROR does not. The exception is NOT propagated to the caller — silent swallowing is forbidden, but the audit-trail log line MUST always fire even when one delegate misbehaves. (Round-3 review of PR [#60](https://github.com/aditrioka/nearyou-id/pull/60) downgraded this from FATAL to ERROR + metric — rationale per design D14.)

The Koin DI module for `:backend:ktor` test startup (the integration-test profile) SHALL bind `NotificationDispatcher` to `InAppOnlyDispatcher` only — no FCM dispatch in tests by default. Tests that explicitly want to exercise `FcmDispatcher` MUST install a test-only override module that binds it with a mocked `FirebaseMessaging`.

To guard against accidental production-binding leakage into tests (e.g., a copy-pasted `startKoin { modules(productionModule) }` call), the test-suite SHALL include a static-analysis test that fails if any test source references `FcmAndInAppDispatcher` outside the explicit override-installation path. Per `design.md` security-hardening review (PR [#60](https://github.com/aditrioka/nearyou-id/pull/60) round 1): this guard's reason is that DI test isolation cannot rely on absence-of-reference alone — a positive structural assertion catches the regression.

This change SHALL NOT modify `NotificationService` or any of the four V10 emit-site services (`LikeService`, `ReplyService`, `FollowService`, `ReportService`). The integration is DI-only, behind the existing `NotificationDispatcher` interface. The "source unchanged" assertion is a **structural** check (no new imports referencing `FcmDispatcher` or Firebase types in `NotificationService.kt`) — NOT a brittle hash-snapshot of the file's source. Refactors that touch unrelated parts of the file (whitespace, import reordering) MUST NOT trip the assertion.

#### Scenario: Production DI binds the composite

- **WHEN** the backend starts in the production profile AND `Koin.get<NotificationDispatcher>()` is resolved
- **THEN** the resolved instance is the composite `FcmAndInAppDispatcher` (or equivalent class name) AND `dispatch(notification)` invokes both `FcmDispatcher.dispatch(...)` AND `InAppOnlyDispatcher.dispatch(...)` exactly once each (call-order: FCM enqueue first, in-app log second)

#### Scenario: Composite catch-and-log on FcmDispatcher unexpected throw

- **WHEN** `FcmDispatcher.dispatch(...)` throws an unexpected exception (e.g., a NullPointerException from a defensive bug; nominal `FcmDispatcher` is designed to never throw, so this scenario is a defense-in-depth guard)
- **THEN** the composite catches the exception AND logs ERROR `event="fcm_composite_dispatcher_unexpected_error"` with `delegate="FcmDispatcher"` AND still invokes `InAppOnlyDispatcher.dispatch(...)` AND no exception propagates back to `NotificationService.emit(...)`

#### Scenario: Composite catch-and-log on InAppOnlyDispatcher unexpected throw

- **WHEN** `InAppOnlyDispatcher.dispatch(...)` throws an unexpected exception
- **THEN** the composite catches the exception AND logs ERROR `event="fcm_composite_dispatcher_unexpected_error"` with `delegate="InAppOnlyDispatcher"` AND no exception propagates back to `NotificationService.emit(...)` AND `FcmDispatcher.dispatch(...)` was already invoked first per call-order

#### Scenario: Test DI binds InAppOnlyDispatcher only

- **WHEN** the integration-test Koin module is loaded AND `Koin.get<NotificationDispatcher>()` is resolved
- **THEN** the resolved instance is `InAppOnlyDispatcher` AND no FCM dispatch occurs during the default test run

#### Scenario: Static-analysis guard fails on accidental production binding in tests

- **WHEN** a hypothetical test source file under `backend/ktor/src/test/` references `FcmAndInAppDispatcher` outside of a designated override-installation path
- **THEN** the static-analysis test in the suite fails AND reports the offending file:line (the guard prevents production-binding leakage that would otherwise silently degrade test isolation)

#### Scenario: NotificationService structural assertion (no new FCM dependencies)

- **WHEN** the structural-analysis test inspects `backend/ktor/src/main/kotlin/.../NotificationService.kt` after this change lands
- **THEN** the file does NOT import `FcmDispatcher`, does NOT import any `com.google.firebase.*` symbol, AND the four emit-site services (`LikeService`, `ReplyService`, `FollowService`, `ReportService`) likewise contain no Firebase / FcmDispatcher references (the integration is purely additive in `:infra:fcm` and DI wiring; no source-level coupling)

### Requirement: Actor-username lookup SHALL use `FROM visible_users` to mask shadow-banned and deleted actors

The `ActorUsernameLookup` interface (defined in `:core:data`, production impl in `:backend:ktor`) SHALL execute `SELECT username FROM visible_users WHERE id = :actor_user_id LIMIT 1` — reading from the shadow-ban-filtered `visible_users` view, NOT from the raw `users` table.

The lookup is a single PK index seek (sub-millisecond on the underlying `users` row, with the view applying `is_shadow_banned`/`deleted_at` filters) and runs on the dispatcher's hot path. It is allowlisted from `BlockExclusionJoinRule` via the canonical `@AllowMissingBlockJoin("<reason>")` KtAnnotation per `design.md` D4 — `visible_users` is in the rule's protected set and requires the annotation regardless of the shadow-ban filter (the rule fires on missing-block-join, not on missing-shadow-ban-filter). `RawFromPostsRule` does NOT apply.

**Why `visible_users` not raw `users`:** per `design.md` D13 — the proposal's earlier draft (rounds 1+2) read from raw `users` with a "preserve in-app/push parity" rationale, which was factually wrong: the round-3 review of PR [#60](https://github.com/aditrioka/nearyou-id/pull/60) verified that `GET /api/v1/notifications` returns only `actor_user_id` UUID with no username text — the in-app list does NOT directly surface the actor's username; mobile renders it via a separate profile-lookup that itself filters via shadow-ban-aware paths. Reading raw `users` for the push body would have made FCM push the FIRST surface to inline a shadow-banned actor's handle into a recipient-bound text payload. We mask via `visible_users` instead, aligning with industry convention (Reddit/HN/X shadow-bans produce no recipient signal) AND the rest of the system's `visible_*` view pattern (timelines, search, feed all filter via `visible_*`). Shadow-banned actors' notifications still get emitted (the existing `NotificationEmitter` does not shadow-ban-suppress; cleaning that up is an out-of-scope follow-up tracked in `FOLLOW_UPS.md`), but the push body will say `"Seseorang menyukai post-mu"` — not the actor's handle.

#### Scenario: Lookup reads from `visible_users` view

- **WHEN** the production `ActorUsernameLookup.lookup(userId)` is invoked
- **THEN** the executed SQL is `SELECT username FROM visible_users WHERE id = :actor_user_id LIMIT 1` (NOT `FROM users`)

#### Scenario: Shadow-banned actor's username is masked in push body

- **WHEN** user B is shadow-banned (`users.is_shadow_banned = TRUE`) AND user B likes user A's post AND the FCM push fires for the resulting `notifications` row
- **THEN** `ActorUsernameLookup.lookup(userB)` returns `null` (because `visible_users` filters out shadow-banned rows) AND `PushCopy.bodyFor(notification, actorUsername=null)` produces the actor-less fallback `"Seseorang menyukai post-mu"` AND the FCM push body contains the masked text — the actor's real handle does NOT appear in the push payload

#### Scenario: Visible (non-shadow-banned) actor's username is rendered normally

- **WHEN** user B is visible (`users.is_shadow_banned = FALSE` AND `users.deleted_at IS NULL`) AND user B likes user A's post
- **THEN** `ActorUsernameLookup.lookup(userB)` returns user B's actual username (e.g., `"bobby"`) AND `PushCopy.bodyFor(notification, "bobby")` produces the parameterized text (e.g., `"bobby menyukai post-mu"`) AND the FCM push body contains the actor's handle as expected

#### Scenario: Lookup is allowlisted via `@AllowMissingBlockJoin`

- **WHEN** `./gradlew detekt` runs against the production `ActorUsernameLookup` source
- **THEN** the `BlockExclusionJoinRule` does NOT fire because the function/class is annotated with `@AllowMissingBlockJoin("...")` AND the annotation reason explicitly references BOTH (a) the emit-time block-clear (recipient-already-cleared) AND (b) the `visible_users` shadow-ban filter that's the active privacy mechanism on this surface — per `design.md` D4

#### Scenario: Hard-deleted actor returns null username gracefully

- **WHEN** `ActorUsernameLookup.lookup(actorUserId)` is invoked for a `userId` whose `users` row has been hard-deleted (the row no longer satisfies `visible_users`'s `deleted_at IS NULL` filter, even ignoring shadow-ban)
- **THEN** the lookup returns `null` (zero rows matched) AND the dispatcher passes `null` to `PushCopy.bodyFor(...)` AND the actor-less fallback string is used — same code path as the shadow-banned case above

### Requirement: Service-account secret rotation SHALL require process restart (no hot-reload)

Rotating `firebase-admin-sa` (or `staging-firebase-admin-sa`) in GCP Secret Manager mid-process MUST NOT change the running `FirebaseApp` singleton. The Firebase Admin SDK initializes `FirebaseApp` exactly once per process lifetime; any subsequent secret rotation requires a Cloud Run revision deploy (or equivalent process restart) to be picked up.

This is documented for operators: an emergency credential rotation is a Secret Manager update PLUS a Cloud Run revision rollout, not just a Secret Manager update. The dispatcher does NOT poll Secret Manager for credential changes; the next instance reads the rotated secret at boot via D7's fail-fast validation path.

#### Scenario: Rotated secret does NOT propagate without restart

- **WHEN** the operator rotates the value in `firebase-admin-sa` to a new valid service-account JSON AND the running Cloud Run instance does NOT restart
- **THEN** the running `FirebaseApp` instance continues using the previously-loaded credentials AND no log line indicating credential change is emitted (this is the documented behavior, not a bug; the dispatcher MUST NOT silently switch credentials mid-process)

#### Scenario: Restart picks up the rotated secret

- **WHEN** the operator rotates the value in `firebase-admin-sa` AND triggers a Cloud Run revision rollout
- **THEN** the new instance's startup `secretKey(env, "firebase-admin-sa")` call reads the rotated value AND `FirebaseApp.initializeApp(...)` succeeds with the new credentials AND subsequent emits use the rotated credentials

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

#### Scenario: Shutdown 5-second boundary is deterministic (cancel-on-timeout)

- **WHEN** the JVM receives `SIGTERM` AND 1 FCM dispatch is in-flight using a virtual-time test dispatcher with the simulated FCM round-trip set to exactly 5.0 seconds (the boundary case)
- **THEN** the dispatch is canceled at the 5.0-second mark via `cancelAndJoin(5.seconds)` semantics (cancel-on-or-after-timeout, not "wait an extra epsilon") AND the JVM exits cleanly AND no exception escapes — this scenario MUST be exercised with a virtual-time / injectable-clock test dispatcher, NOT a wall-clock sleep, to guarantee determinism on CI
