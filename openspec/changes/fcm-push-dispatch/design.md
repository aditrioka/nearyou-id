## Context

Two predecessor changes ship the bookends:

- [`in-app-notifications`](../../specs/in-app-notifications/spec.md) (V10, PR [#33](https://github.com/aditrioka/nearyou-id/pull/33)) created the `notifications` table, the `NotificationService`/`NotificationEmitter` write path with block-suppression and self-action suppression, the four V10-wired emit sites (`post_liked`, `post_replied`, `followed`, `post_auto_hidden`), and a `NotificationDispatcher` interface in `:core:data` whose only implementation today is a no-op `InAppOnlyDispatcher` (logs at INFO, no network call). The seam was deliberately introduced as a forward extension point — its spec text says verbatim: *"The FCM push dispatch change SHALL add a new implementation (or composite) without modifying `NotificationService` or any emitter."*

- [`fcm-token-registration`](../../specs/fcm-token-registration/spec.md) (V14, PR [#55](https://github.com/aditrioka/nearyou-id/pull/55)) created the `user_fcm_tokens` table with the `(user_id, platform, token)` UNIQUE index and the `last_seen_at` freshness column, the `POST /api/v1/user/fcm-token` upsert endpoint, and a "Schema MUST support the deferred Phase 2 on-send-failure delete" requirement that scopes the on-send 404/410 → row-delete contract for *this* change to own.

Canonical platform-delivery contract lives at [`docs/04-Architecture.md § Push Notification Infrastructure`](../../../docs/04-Architecture.md) (lines 502–558). Notifications type catalog + body_data shape conventions at [`docs/05-Implementation.md § Notifications Schema`](../../../docs/05-Implementation.md) (lines 820–873). Token cleanup paths at [`docs/05-Implementation.md § FCM Token Registration`](../../../docs/05-Implementation.md) (lines 1394–1408). Roadmap anchor at [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md) Phase 2 items 7 + 8.

Constraints from `CLAUDE.md` invariants:
- **No vendor SDK import outside `:infra:*`** — Firebase Admin SDK confined to `:infra:fcm`. The `NotificationDispatcher` interface stays in `:core:data` (its existing module).
- **Secrets MUST be read via `secretKey(env, name)`** — the Firebase service-account JSON is read via `secretKey(env, "firebase-admin-sa")`. Direct env-name reads are a Detekt violation.
- **`user_fcm_tokens` is per-user PII** — already protected by `ON DELETE CASCADE` from `users`. No additional view layer needed; the FcmDispatcher reads tokens by recipient `user_id`, which is the natural ownership filter.

## Goals / Non-Goals

**Goals:**
- Server-emitted FCM pushes for the four V10-wired notification types reach every active token of the recipient user, in parallel per device.
- Per-platform payload contract (Android data-only `priority: "high"`; iOS alert + `mutable-content: 1` + `body_full` data field for the future NSE).
- 404/410 → row-delete contract owned end-to-end (test exercises an UNREGISTERED response and asserts the row is gone).
- Zero modification to `NotificationService` or the four emit sites — the integration point is DI-only, behind the existing `NotificationDispatcher` interface.
- Boot-time fail-fast on missing/invalid service-account JSON in non-test profiles (so a misconfigured deploy is caught at startup, not on the first emit).
- All existing CLAUDE.md invariants honored (no SDK import outside `:infra:fcm`, `secretKey(env, name)` for the service-account secret, no hardcoded mobile strings ever — backend-emitted strings are server-side i18n).

**Non-Goals:**
- Per-conversation push batching (max 1 push per 10s per conversation) — chat hasn't shipped; deferred to the chat change cycle.
- iOS NSE on-device body rewriting — that's iOS-client work in Phase 3. The backend ships `body_full`; the consumer is out of scope.
- Stale token cleanup worker (`last_seen_at < NOW() - INTERVAL '30 days'`) — Phase 3.5 admin-panel territory.
- Notification-type copy localization for any of the 9 notification types whose emit-sites haven't shipped yet (`chat_message`, `subscription_*`, `account_action_applied`, `data_export_ready`, `chat_message_redacted`, `privacy_flip_warning`, `username_release_scheduled`, `apple_relay_email_changed`). They get a fallback copy ("Pemberitahuan baru" / "Notifikasi baru dari NearYou") and an explicit follow-up.
- Redis-backed dedupe of recently-dispatched notification IDs. The `notifications` UUID PK + emit-site short-circuit logic already prevents source duplication; a dedupe cache would be premature.
- Rate-limiting FCM dispatch. FCM free-tier supports millions/day per [`docs/04-Architecture.md:558`](../../../docs/04-Architecture.md); a per-user dispatch cap is unwarranted at MVP scale.

## Decisions

### D1. Composition shape: composite `FcmAndInAppDispatcher`, not a Remote-Config toggle

Two options for combining `FcmDispatcher` with the existing `InAppOnlyDispatcher`:

- **(a) Remote Config flag** (`fcm_dispatch_enabled`): single `NotificationDispatcher` binding selected at boot based on the flag. Operator can flip it to disable FCM dispatch in an incident.
- **(b) Composite dispatcher** that calls both `FcmDispatcher.dispatch(...)` and `InAppOnlyDispatcher.dispatch(...)` (the latter is currently a no-op log; the composite preserves the log line + adds FCM).

**Decision: (b) composite.** Rationale:
- The "in-app log line" is already a no-op effect-wise; keeping it as a *consumer* of dispatch alongside FCM is cleaner than gating it behind a flag.
- A Remote Config flag introduces a runtime branch on a hot path (every notification emit) that's only useful for one operator action ("disable all FCM in an incident"). That action is also achievable by rotating the Firebase service-account secret + restarting (forces fail-open behavior per D7), which is a less subtle bigger-hammer that doesn't require a new RC flag.
- Tests bind `InAppOnlyDispatcher` directly (no FCM), preserving the existing in-app-notifications test surface verbatim. Production binds the composite. Test ergonomics stay simple.

A `kill-switch` Remote Config flag (`fcm_dispatch_enabled`) MAY be added in a follow-up change if operational experience shows it's needed. Not introduced here.

### D2. `FcmDispatcher` runs on a background coroutine scope, not the request thread

Each emit triggers up to N (= number of recipient's active tokens) FCM Admin SDK calls. Even with the SDK's async API, those calls block the dispatcher fiber until the round-trip completes. Running them on the request-thread coroutine scope would couple endpoint p95 latency to FCM round-trip latency — a regression vs the current behavior where `InAppOnlyDispatcher.dispatch()` is a synchronous log line.

**Decision: dispatch in a dedicated `CoroutineScope` (named `fcmDispatcherScope`) backed by a bounded thread pool (`Dispatchers.IO.limitedParallelism(8)`).** `FcmDispatcher.dispatch(notification)` enqueues the work as a coroutine `launch` on that scope and returns immediately. The endpoint thread sees zero added latency.

Implications:
- Errors in the FCM call do NOT propagate back to the emit site (the primary write transaction has already committed; a push failure is a notification-delivery problem, not a request-failure problem). Errors are logged structured WARN.
- On JVM shutdown, the `fcmDispatcherScope` is canceled with `cancelAndJoin()` (5-second timeout) to drain in-flight pushes. New emits during shutdown observe a closed-scope and fall through to the `InAppOnlyDispatcher` log path only.
- Test ergonomics: the test profile may inject a `Dispatchers.Unconfined`-backed scope so tests can assert dispatch effects synchronously. The production scope is bound in the Koin DI module.

### D3. Per-platform payload builders share a `body_data` source-of-truth, differ only on the wire

Both Android and iOS payloads derive from the same `NotificationDto` (`type`, `actor_user_id`, `target_type`, `target_id`, `body_data`). The wire format differs:

**Android (data-only):**
```kotlin
Message.builder()
  .putData("type", dto.type)                         // e.g., "post_liked"
  .putData("actor_user_id", dto.actorUserId.orEmpty())
  .putData("target_type", dto.targetType.orEmpty())
  .putData("target_id", dto.targetId?.toString().orEmpty())
  .putData("body_data", dto.bodyData?.toJsonString().orEmpty()) // Stringified JSON
  .setAndroidConfig(AndroidConfig.builder().setPriority(HIGH).build())
  .setToken(token)
  .build()
```

**iOS (alert + mutable-content):**
```kotlin
Message.builder()
  .setNotification(Notification.builder()
    .setTitle(localizedTitle(dto.type))     // e.g., "NearYou"
    .setBody(localizedBodyShort(dto))       // e.g., "Bob menyukai post-mu"
    .build())
  .setApnsConfig(ApnsConfig.builder()
    .setAps(Aps.builder().setMutableContent(true).build())
    .putCustomData("body_full", dto.bodyData?.toJsonString().orEmpty())  // NSE rewrites body using this
    .build())
  .setToken(token)
  .build()
```

The `body_data` for Android is JSON-stringified (FCM data fields must be `String`). iOS gets the same JSON in `body_full` for NSE consumption. The localization helpers (`localizedTitle`, `localizedBodyShort`) are pure functions of `dto.type` (and `dto.body_data` for excerpt-bearing types like `post_liked`'s `post_excerpt`).

### D4. Server-side i18n: in-source Indonesian copy, NO Moko Resources for backend

[`CLAUDE.md` invariant](../../../CLAUDE.md): "Mobile strings: no hardcoded UI strings; must go through Moko Resources." That rule scopes mobile UI strings — not server-side push-notification body strings.

Backend strings have a different posture:
- They're emitted by Ktor (JVM-only); Moko Resources is a KMP client concern.
- The strings are end-user-facing, but in a fixed delivery surface (FCM push body) where the client (iOS NSE OR Android local renderer) MAY override based on on-device preference (preview-toggle).

**Decision: ship a small `id.nearyou.app.notification.PushCopy` Kotlin object inside `:infra:fcm` with a single function `fun bodyForType(dto: NotificationDto): String`** that returns Indonesian copy for the four V10-wired types:

| Type | Title | Body |
|------|-------|------|
| `post_liked` | NearYou | `<actor_username> menyukai post-mu` |
| `post_replied` | NearYou | `<actor_username> membalas post-mu` |
| `followed` | NearYou | `<actor_username> mulai mengikuti kamu` |
| `post_auto_hidden` | NearYou | `Postinganmu disembunyikan otomatis karena beberapa laporan` |
| (any other type) | NearYou | `Notifikasi baru dari NearYou` (fallback) |

**Rationale for choosing in-source copy over a `:shared:resources`-style server-side i18n module:**
- The four V10-wired types are the only emit-sites today. Adding 9 fallback strings + a server-i18n abstraction for ~4 distinct strings is over-engineering.
- Once chat ships and the other 9 emit-sites land, a server-i18n migration is a natural follow-up — but `PushCopy.bodyForType` is a single-file refactor target with zero contract surface (no caller depends on the lookup mechanism).
- A future English-locale version (`Accept-Language` from token registration time) is straightforward to retrofit into `PushCopy.bodyForType(dto, locale)`.

The `<actor_username>` substitution requires a join: `FcmDispatcher` does a single `SELECT username FROM users WHERE id = :actor_user_id` per dispatch (when `actor_user_id IS NOT NULL`). For system-emitted notifications (`actor_user_id IS NULL`, e.g., `post_auto_hidden`, `privacy_flip_warning`), no join is needed.

**`actor_username` join shadow-ban posture:** the actor-username lookup is a notification-rendering step, NOT a "business query that filters posts/replies/chat." It runs on a hot path (one row by primary key) and the value is shown to the *recipient*, who has already been deemed a legitimate audience for the actor's action by the existing `NotificationEmitter` block-check (see [`in-app-notifications` requirement "NotificationEmitter write-path with block-suppression"](../../specs/in-app-notifications/spec.md)). The join uses raw `FROM users WHERE id = :actor_user_id LIMIT 1` — exempt from `RawFromPostsRule` because: (a) it's a single-row PK lookup, not a list/feed query; (b) shadow-banning is a list-suppression mechanism (per the `visible_users` view contract), not a "hide every reference of this user globally"; (c) the upstream emit-site already gates the notification through the block-check, so the actor↔recipient relationship is approved by the time the username is rendered. Documented as a permitted call-site in the FcmDispatcher source with `// @allow-raw-users-read: notification-rendering` annotation, and the `RawFromPostsRule` allowlist is extended to recognize that annotation. (Allowlist precedent matches the `// @allow-username-write: signup` pattern from the username-customization design.)

### D5. Read-tokens query SHALL filter `user_fcm_tokens` by recipient only

`SELECT platform, token FROM user_fcm_tokens WHERE user_id = :recipient_user_id` — a single index lookup via `user_fcm_tokens_user_idx`. No `last_seen_at` filter at read time (the stale-cleanup worker is the freshness GC; on-send 404/410 catches dead tokens within the next emit). No `LIMIT` (unbounded — but per-user token count is bounded by D3's tripwire in `fcm-token-registration` to a small constant; in practice ≤ 5 tokens per user).

The query MUST run **outside** the recipient's primary write transaction (the dispatcher runs after-commit per D2). It runs on its own connection from the pool.

### D6. 404/410 → DELETE: per-token, not per-user

When the FCM Admin SDK reports `MessagingErrorCode.UNREGISTERED` (HTTP 404) or `MessagingErrorCode.INVALID_ARGUMENT` (HTTP 410) for a specific token, the dispatcher executes:

```sql
DELETE FROM user_fcm_tokens
WHERE user_id = :u AND platform = :p AND token = :t
```

This deletes exactly the one row for that `(user_id, platform, token)` triple. It does NOT touch other tokens for the same user (a user with stale-token-A and live-token-B keeps token-B). The DELETE uses the UNIQUE index from V14 — a single-row index lookup.

`MessagingErrorCode.SENDER_ID_MISMATCH` is also a permanent failure (the token belongs to a different FCM project) and is treated identically — DELETE the row.

All other `MessagingErrorCode` values (`QUOTA_EXCEEDED`, `UNAVAILABLE`, `INTERNAL`) and any non-`FirebaseMessagingException` (network, timeout, JSON deserialize) are treated as transient — log structured WARN with the error code, do NOT delete the row.

### D7. Boot-time service-account validation: fail-fast in non-test, fail-open in test

In `:backend:ktor` startup, the Firebase Admin SDK initialization (`FirebaseApp.initializeApp(options)`) is wrapped in a one-time validation:

- **Non-test profile**: read `secretKey(env, "firebase-admin-sa")` → parse JSON → call `FirebaseApp.initializeApp(...)`. If parse fails OR the secret is missing, the application **fails to start** with a structured FATAL log naming the secret slot. Cloud Run sees the failed health check and refuses to roll forward.
- **Test profile**: the Koin DI module binds `NotificationDispatcher` to `InAppOnlyDispatcher` only (no `FcmDispatcher`). The `:infra:fcm` Firebase initialization is skipped entirely. Tests requiring FCM dispatch behavior bind a stub `FcmDispatcher` against a mock Firebase Admin SDK (we use the SDK's `FirebaseMessaging` interface directly with a Mockito-style mock).

**Why fail-fast in production:** a misconfigured `firebase-admin-sa` secret silently degrades push delivery. Server starts, requests succeed, in-app notifications work, but pushes never fire. That's exactly the failure mode that's hardest to detect from telemetry — there's no single error log saying "FCM is broken." Fail-fast at startup forces the operator to fix the secret slot before the deploy completes.

Alternative considered: fail-open with a structured FATAL log on every emit attempt. Rejected because the FATAL noise floods Sentry without surfacing root cause; ops tickets become "what does this FATAL spam mean?" instead of "the deploy is broken, the secret slot is empty."

### D8. Module boundary: `:infra:fcm` is a leaf, depends on `:core:data` only

`:infra:fcm` sources:
- `dependencies { implementation(libs.firebase.admin) }` (the new pin in `gradle/libs.versions.toml`).
- `dependencies { api(project(":core:data")) }` (for the `NotificationDispatcher` interface and `NotificationDto`).
- `dependencies { implementation(project(":core:domain")) }` (for `UserId` value-class etc., if any cross-cuts).
- A small DAO interface (`UserFcmTokenReader { fun activeTokens(userId: UserId): List<FcmTokenRow>; fun deleteToken(userId, platform, token) }`) defined in `:infra:fcm` and implemented against the existing JOOQ/SQL surface in `:backend:ktor` via Koin (the implementation is in `:backend:ktor` so the SDK leaf has zero JDBC import). Wait — that creates a back-reference. Re-decide:

**Decision (revised):** the `UserFcmTokenReader` interface lives in `:core:data` (alongside the existing `NotificationDispatcher` interface — same module, same posture). Its production JOOQ-backed implementation lives in `:backend:ktor`, and `FcmDispatcher` (in `:infra:fcm`) takes it as a constructor dependency. `:infra:fcm` therefore depends only on `:core:data`. The DI graph wires the JOOQ implementation in.

This matches the existing `:core:data` interface posture (`NotificationDispatcher` lives there and is implemented in `:backend:ktor` via `InAppOnlyDispatcher`).

### D9. New library pin: `firebase-admin` SDK

Per [`docs/09-Versions.md`](../../../docs/09-Versions.md) Pinning Policy, all third-party libraries must be declared in `gradle/libs.versions.toml` and reasoned in the Version Decisions table. This change adds:

```toml
[versions]
firebase-admin = "9.4.3"   # current stable as of 2026-04-28; verify before scaffold

[libraries]
firebase-admin = { module = "com.google.firebase:firebase-admin", version.ref = "firebase-admin" }
```

Rationale (to land in `docs/09-Versions.md`):
> First Firebase Admin SDK on the JVM classpath; introduced by `fcm-push-dispatch` change for the `:infra:fcm` module's FCM Admin SDK integration. 9.4.x line is current stable; transitively pulls google-auth-library which is already bundled by other Google deps. Sync API used initially (`FirebaseMessaging.send(message)`); async `sendAsync` can be revisited if a benchmark shows it matters.

Verify the actual current stable version at scaffold time (the `firebase-admin` SDK ships frequently; treat the `9.4.3` figure above as a placeholder requiring verification per the "External-data dependencies need a sanity-check task" rule in [`/.claude/skills/next-change/SKILL.md`](../../../.claude/skills/next-change/SKILL.md) — same posture applies to "external library version pins").

### D10. Post-DB-commit invocation contract is preserved verbatim

[`in-app-notifications` Requirement: NotificationDispatcher seam](../../specs/in-app-notifications/spec.md): "`NotificationService.emit()` SHALL call `dispatch()` after the DB commit succeeds. The FCM push dispatch change SHALL add a new implementation (or composite) without modifying `NotificationService` or any emitter."

`FcmDispatcher.dispatch(notification)` is invoked by the existing `NotificationService` post-commit. This change does NOT modify `NotificationService` source. The DI binding swap (composite vs in-app-only) is the only `:backend:ktor` change. All four emit-site services (`LikeService`, `ReplyService`, `FollowService`, `ReportService`) remain untouched.

### D11. Settings new module entry per CLAUDE.md README rule

Per [`CLAUDE.md` invariant](../../../CLAUDE.md): "Root README module list is auto-generated from `settings.gradle.kts` + `dev/module-descriptions.txt`. When a change adds a new module, also (a) add a one-line description to `dev/module-descriptions.txt`, then (b) run `dev/scripts/sync-readme.sh --write`."

This change adds `:infra:fcm`. Tasks include adding the line to `dev/module-descriptions.txt` and running `sync-readme.sh --write` to regenerate the README block. CI's `--check` mode will catch drift.

## Risks / Trade-offs

- **[Risk] In-source Indonesian copy is hard to localize later** → Mitigation: localized in a single file (`PushCopy.kt`); migration to `:shared:resources`-style backend i18n is a one-file refactor when chat lands and the 9 fallback types need real strings. Documented as a follow-up.

- **[Risk] FcmDispatcher coroutine scope leak on JVM shutdown** → Mitigation: shutdown hook calls `cancelAndJoin()` with a 5-second timeout; in-flight FCM calls drain or are abandoned (the recipient sees the in-app notification on next app open). Tested via an integration test that triggers shutdown mid-dispatch.

- **[Risk] Composite dispatcher silently drops on FCM transport failure** → Mitigation: structured WARN log per failure with `error_code`, `user_id`, `platform`, token-presence-only-no-value (matches `fcm-token-registration`'s D11 token-confidentiality posture). Sentry pickup is on `MessagingErrorCode.QUOTA_EXCEEDED` / `UNAVAILABLE` / `INTERNAL` only — transient errors, but if their rate spikes it's an FCM provider problem we want to know about. The on-send 404/410 → DELETE path produces a structured INFO (not WARN) since it's an expected, healthy GC path.

- **[Risk] N+1 username lookups for batch emits** → Mitigation: per-emit cardinality is bounded by recipient token count (typically 1–3), and each emit does at most one `actor_user_id` lookup. Batch emits aren't a thing in V10 (each notification is single-recipient single-emit); the username lookup is per-notification, not per-token. If chat batching lands later, a per-batch username cache becomes a natural optimization.

- **[Risk] FCM Admin SDK boot-time global state** → Mitigation: `FirebaseApp.initializeApp(...)` is a process-wide singleton; called exactly once at backend startup with a named `FirebaseApp` instance (`"nearyou-default"`) so future test isolation can use a different name without colliding. Test profile skips initialization entirely (no FcmDispatcher binding).

- **[Risk] Token-confidentiality drift in WARN logs** → Mitigation: same posture as `fcm-token-registration` requirement "Validation errors MUST use a closed vocabulary" — structured WARN logs MUST NOT include the raw token value. `FcmDispatcher` emits `token_present=true` or `token_length=<int>` only. Test asserts the captured log appender output does not contain a known test-token string.

- **[Trade-off] Composite over Remote-Config flag** — operator loses the on-the-fly kill switch. Acceptable because (a) rotating the secret-slot value to invalid achieves the same effect, and (b) D7's fail-open-in-emit (the WARN on transport-level failure) means a Firebase outage degrades to in-app-only behavior automatically. A future RC flag can be added if operational pain materializes.

- **[Trade-off] No per-conversation batching now** — chat hasn't shipped, so the batching surface has zero callers today. Risk of "bolt-on later vs design-in upfront" is small because the batching layer is naturally a wrapper around `FcmDispatcher` (same `dispatch(notification)` contract; batching moves from instant to debounced). Documented as the entry point for the chat change cycle.

## Migration Plan

1. **Pre-deploy (operator)**: populate `firebase-admin-sa` (prod) and `staging-firebase-admin-sa` (staging) GCP Secret Manager slots with the Firebase service-account JSON downloaded from the Firebase Console (`Settings > Service accounts > Generate new private key`). Slot names already reserved per [`docs/08-Roadmap-Risk.md:51`](../../../docs/08-Roadmap-Risk.md).
2. **Code merge**: lands the `:infra:fcm` module + `FcmDispatcher` impl + DI wiring + tests + lib pin + README sync. No Flyway migration. No schema change. No new endpoint.
3. **Deploy to staging** (auto via `main` branch per `openspec/project.md` § Environments). Smoke test:
   - Register a test FCM token via `POST /api/v1/user/fcm-token` from a staging mobile build.
   - Trigger a `post_liked` notification (Bob likes Alice's post via the staging API).
   - Verify the FCM push arrives on the test device.
   - Inspect Cloud Logging: structured INFO `event="fcm_dispatched"` with `user_id`, `platform`, `tokens_dispatched=1`.
4. **Deploy to prod** (manual approval via git tag `v*`). Same smoke test against a real device. Watch the `fcm_dispatch_failed` WARN rate for 24 hours; expected to be near-zero on a healthy deploy.

**Rollback strategy:** revert the deploy. The `InAppOnlyDispatcher` was the prior production binding and works without any Firebase setup. No state migration needed. No data to clean up.

## Open Questions

- **Q1**: Should the boot-time service-account validation include a *liveness* check against FCM (e.g., `FirebaseMessaging.send(dryRun=true)` with a synthetic token)? Pro: catches a key-rotation problem before the first real emit. Con: adds external dependency at boot and a startup-latency tax. **Default decision unless objected: no.** The first real emit's WARN log is sufficient; we already fail-fast on missing/malformed JSON.

- **Q2**: Should the `actor_username` lookup hit a Redis cache (TTL 5 minutes per the existing pattern at [`docs/05-Implementation.md:1406`](../../../docs/05-Implementation.md))? Pro: trim Postgres load. Con: cache invalidation on username-customization writes adds another contract. **Default decision unless objected: no.** Per-notification cardinality is low (1 lookup per emit); Postgres handles a single index-PK lookup in <1ms. Revisit if profiling shows it.

- **Q3**: Should `fcm_dispatch_enabled` Remote Config kill-switch be added now, even with composite as the default? Pro: cheap insurance. Con: D1 argues against. **Default decision unless objected: no.** Add later if needed.

- **Q4**: Sub-agent / qodo review may surface concerns about the `actor_username` raw-`FROM users` posture in D4. If reviewers object, alternative is reading from `visible_users` view — but that filters shadow-banned actors out, which would *change* the recipient's experience (notification arrives but actor name says "akun_dihapus") for the shadow-ban target's actions on the recipient's content. Surface for explicit decision in review.
