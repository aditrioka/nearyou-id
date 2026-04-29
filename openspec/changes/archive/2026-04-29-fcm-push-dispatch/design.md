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
- A Remote Config flag introduces a runtime branch on a hot path (every notification emit) that's only useful for one operator action ("disable all FCM in an incident"). That action is also achievable by rotating the Firebase service-account secret to an invalid value AND triggering a Cloud Run revision rollout (per D15: secret rotation requires process restart) — the next instance fails to start, the prior instance drains, FCM dispatch goes silent. A subsequent rollback restores. Less subtle but doesn't require a new RC flag.
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

**`actor_username` join Detekt-rule posture:** the actor-username lookup is a notification-rendering step, NOT a "business query that filters posts/replies/chat." It runs on a hot path (one row by primary key). The relevant Detekt rule is **`BlockExclusionJoinRule`** ([`lint/detekt-rules/.../BlockExclusionJoinRule.kt`](../../../lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/BlockExclusionJoinRule.kt) — both `users` AND `visible_users` are in its protected table set: `posts | visible_posts | users | chat_messages | post_replies`), NOT `RawFromPostsRule` (the latter only protects `posts | visible_posts`).

The lookup uses `FROM visible_users WHERE id = :actor_user_id LIMIT 1` (see D13 below for the shadow-ban rationale). It still requires the `@AllowMissingBlockJoin("<reason>")` KtAnnotation because `visible_users` is one of `BlockExclusionJoinRule`'s protected tables — the rule fires on any non-block-joined read of those tables, regardless of whether shadow-banning is filtered. Per [`block-exclusion-lint` "Allowlists for block-exclusion lint"](../../../openspec/specs/block-exclusion-lint/spec.md). Direct precedent: [`ReportService.kt:178`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/moderation/ReportService.kt) — the V9 report change applies the same annotation on its own raw lookup for an analogous posture (system-originated lookup; recipient is the known audience).

The annotation reason for the `ActorUsernameLookup` SHALL be:

```kotlin
@AllowMissingBlockJoin(
    "Notification-rendering actor-username lookup (PK seek on visible_users). " +
    "Two reasons block-exclusion does not apply: (1) the recipient is the known " +
    "audience and was already cleared via the NotificationEmitter bidirectional " +
    "block-check at emit-time (see in-app-notifications spec, NotificationEmitter " +
    "write-path requirement) — re-applying the block-join here would re-litigate " +
    "an already-gated relationship; (2) the read goes through visible_users which " +
    "applies shadow-ban filtering — that is the active privacy filter on this " +
    "surface (see fcm-push-dispatch design D13). Reads to visible_users in this " +
    "module are restricted to the actor-username path."
)
```

### D5. Read-tokens query SHALL filter `user_fcm_tokens` by recipient only

`SELECT platform, token FROM user_fcm_tokens WHERE user_id = :recipient_user_id` — a single index lookup via `user_fcm_tokens_user_idx`. No `last_seen_at` filter at read time (the stale-cleanup worker is the freshness GC; on-send 404/410 catches dead tokens within the next emit). No `LIMIT` (unbounded — but per-user token count is bounded by D3's tripwire in `fcm-token-registration` to a small constant; in practice ≤ 5 tokens per user).

The query MUST run **outside** the recipient's primary write transaction (the dispatcher runs after-commit per D2). It runs on its own connection from the pool.

### D6. Token-prune semantics: DELETE only on `UNREGISTERED` and `SENDER_ID_MISMATCH`; `INVALID_ARGUMENT` is ambiguous and treated as transient

When the FCM Admin SDK reports `MessagingErrorCode.UNREGISTERED` (the registration token has been unregistered or expired) OR `MessagingErrorCode.SENDER_ID_MISMATCH` (the token belongs to a different FCM project), the dispatcher executes a per-token DELETE:

```sql
DELETE FROM user_fcm_tokens
WHERE user_id = :u
  AND platform = :p
  AND token = :t
  AND last_seen_at <= :dispatch_started_at
```

The `last_seen_at <= :dispatch_started_at` predicate guards against the **re-registration race** (see D12 below). The DELETE uses the UNIQUE index from V14 — a single-row index lookup.

`MessagingErrorCode.INVALID_ARGUMENT` is **NOT** treated as a permanent failure. The original review of this design (PR [#60](https://github.com/aditrioka/nearyou-id/pull/60)) flagged that `INVALID_ARGUMENT` is overloaded by the FCM Admin SDK: it covers both stale-token-format failures AND oversized-payload failures (APNs caps payloads at 4 KB, FCM's underlying limit is similar). Deleting a token because the *payload* was too large would be wrong — the token is healthy. Therefore `INVALID_ARGUMENT` is treated as transient: structured WARN log, no DELETE, recipient sees the notification on next emit (or via the in-app list per the docs/04-Architecture.md:558 fallback). The trade-off: a genuinely-invalid-format token persists until the next emit attempt, where it produces another `INVALID_ARGUMENT`. This is acceptable because (a) genuinely-malformed tokens are rare (Firebase SDKs produce well-formed tokens), (b) the stale-cleanup worker (Phase 3.5) eventually purges via `last_seen_at` even if the token never gets touched, and (c) the alternative — best-effort message-substring matching to disambiguate — is brittle.

To minimize the false-positive `INVALID_ARGUMENT` rate, the iOS payload builder **MUST clamp `body_full` size** so the assembled APNs payload stays under the 4 KB limit. The clamp truncates `body_full` to a safe ceiling (typically ~3 KB after JSON-stringification), preserving the `body_data` shape but truncating the longest-field — typically `post_excerpt` or `reply_excerpt` — to fit. Tests assert that a `body_data` with a deliberately-oversized excerpt produces a payload ≤ 4 KB and does not trigger `INVALID_ARGUMENT`.

All other `MessagingErrorCode` values (`QUOTA_EXCEEDED`, `UNAVAILABLE`, `INTERNAL`) and any non-`FirebaseMessagingException` (network, timeout, JSON deserialize) are treated as transient — log structured WARN with the error code, do NOT delete the row.

Summary of token-prune triggers:

| Error | Treatment | Delete? |
|-------|-----------|---------|
| `UNREGISTERED` | Permanent — token gone | ✅ YES |
| `SENDER_ID_MISMATCH` | Permanent — different FCM project | ✅ YES |
| `INVALID_ARGUMENT` | Ambiguous (stale-format OR oversized payload) | ❌ NO |
| `QUOTA_EXCEEDED` | Transient — back off later | ❌ NO |
| `UNAVAILABLE` | Transient — FCM provider hiccup | ❌ NO |
| `INTERNAL` | Transient — FCM internal | ❌ NO |
| Network / timeout / JSON-deserialize | Transient | ❌ NO |

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
- A small DAO interface `UserFcmTokenReader` (with `activeTokens(userId)` returning a `TokenSnapshot` and `deleteTokenIfStale(userId, platform, token, dispatchStartedAt)` returning row-count) lives in `:core:data` (alongside the existing `NotificationDispatcher` interface — same module, same posture). Its production JOOQ-backed implementation lives in `:backend:ktor`, and `FcmDispatcher` (in `:infra:fcm`) takes it as a constructor dependency. `:infra:fcm` therefore depends only on `:core:data`. The DI graph wires the JOOQ implementation in.

This matches the existing `:core:data` interface posture (`NotificationDispatcher` lives there and is implemented in `:backend:ktor` via `InAppOnlyDispatcher`).

### D9. New library pin: `firebase-admin` SDK

Per [`docs/09-Versions.md`](../../../docs/09-Versions.md) Pinning Policy, all third-party libraries must be declared in `gradle/libs.versions.toml` and reasoned in the Version Decisions table. This change adds:

```toml
[versions]
firebase-admin = "<verified-at-scaffold>"   # implementer pins via task 1.1 — see Maven Central before adding

[libraries]
firebase-admin = { module = "com.google.firebase:firebase-admin", version.ref = "firebase-admin" }
```

The exact version pin is deliberately deferred to implementation (task 1.1). The `firebase-admin` SDK ships frequently; the implementer's first task is to look up the current stable on Maven Central and pin it, per the "External-data dependencies need a sanity-check task" rule in [`/.claude/skills/next-change/SKILL.md`](../../../.claude/skills/next-change/SKILL.md) — same posture applies to "external library version pins."

Rationale (to land in `docs/09-Versions.md` Version Decisions table):
> First Firebase Admin SDK on the JVM classpath; introduced by `fcm-push-dispatch` change for the `:infra:fcm` module's FCM Admin SDK integration. Transitively pulls google-auth-library which is already bundled by other Google deps. Sync API used initially (`FirebaseMessaging.send(message)`); async `sendAsync` can be revisited if a benchmark shows it matters.

### D10. Post-DB-commit invocation contract is preserved verbatim

[`in-app-notifications` Requirement: NotificationDispatcher seam](../../specs/in-app-notifications/spec.md): "`NotificationService.emit()` SHALL call `dispatch()` after the DB commit succeeds. The FCM push dispatch change SHALL add a new implementation (or composite) without modifying `NotificationService` or any emitter."

`FcmDispatcher.dispatch(notification)` is invoked by the existing `NotificationService` post-commit. This change does NOT modify `NotificationService` source. The DI binding swap (composite vs in-app-only) is the only `:backend:ktor` change. All four emit-site services (`LikeService`, `ReplyService`, `FollowService`, `ReportService`) remain untouched.

**Composite "in sequence" semantics (call-order, not completion-order).** `FcmAndInAppDispatcher.dispatch(...)` invokes `FcmDispatcher.dispatch(...)` first, then `InAppOnlyDispatcher.dispatch(...)`. Both calls return synchronously (per D2, `FcmDispatcher.dispatch` enqueues coroutines on `fcmDispatcherScope` and returns immediately; `InAppOnlyDispatcher.dispatch` is a synchronous log line). "In sequence" therefore means the call-order is deterministic — FCM enqueue happens first, in-app log fires second — but does NOT mean the FCM round-trip completes before the in-app log. This is intentional: the in-app log is the audit trail (always lands), the FCM push is fire-and-forget (lands when the FCM round-trip completes, possibly hundreds of ms later, possibly never on transport failure).

### D11. New module entry SHALL be propagated through dev/module-descriptions.txt + sync-readme.sh

Per [`CLAUDE.md` invariant](../../../CLAUDE.md): "Root README module list is auto-generated from `settings.gradle.kts` + `dev/module-descriptions.txt`. When a change adds a new module, also (a) add a one-line description to `dev/module-descriptions.txt`, then (b) run `dev/scripts/sync-readme.sh --write`."

This change adds `:infra:fcm`. Tasks include adding the line to `dev/module-descriptions.txt` and running `sync-readme.sh --write` to regenerate the README block. CI's `--check` mode will catch drift.

### D12. Re-registration race: `last_seen_at` predicate on the on-send DELETE

Race scenario:

1. T0 — Dispatcher reads `(userA, android, tok-1, last_seen_at = T0)` and starts an FCM send.
2. T1 — User reinstalls / token-rotates; client POSTs `/api/v1/user/fcm-token` with the same `(userA, android, tok-1)` triple. The upsert refreshes `last_seen_at = T1` (per `fcm-token-registration` requirement).
3. T2 — FCM responds `UNREGISTERED` for the dispatch from T0 (the token *was* stale at the time of send; the re-registration may have come from a different process / device).
4. T3 — Naive DELETE removes the row whose `last_seen_at = T1` — i.e., the row that's *currently fresh*. The user's just-re-registered token is gone; pushes will silently drop until the next client re-register.

**Decision: capture `dispatchStartedAt` via `SELECT NOW() AT TIME ZONE 'UTC'` on the SAME database connection used for `activeTokens(...)`** — NOT via JVM `Instant.now()`. Reason: `last_seen_at` in `user_fcm_tokens` is written via Postgres `NOW()` (per the `fcm-token-registration` upsert), so the predicate `last_seen_at <= :dispatch_started_at` MUST compare two values from the same clock domain. Using JVM `Instant.now()` introduces clock-skew between the JVM container and the DB primary (NTP drift, container clock drift, leap-second handling) — a JVM clock running ahead of DB by even seconds can cause a freshly-re-registered row (`last_seen_at = T_db_now`) to falsely satisfy `last_seen_at <= T_jvm_now` and be wrongly deleted. The harm in the false-delete direction is bounded (the next client re-register recreates the row), but it's an unforced error; using DB clock-domain throughout eliminates it.

The DELETE returns 0 rows affected when a re-registration has bumped `last_seen_at` past the dispatch's window — the dispatcher logs INFO `event="fcm_token_prune_skipped_re_registered"` instead of `event="fcm_token_pruned"`.

Implementation hint: `UserFcmTokenReader.activeTokens(userId)` returns `Pair<List<FcmTokenRow>, Instant dispatchStartedAt>` where the `Instant` is the result of `SELECT NOW()` on the same connection. The dispatcher then passes that Instant to `deleteTokenIfStale(...)` for any prune that fires later in the dispatch's lifetime. Alternative API shapes (e.g., a transaction-scoped reader) are acceptable; the contract is "both ends of the comparison come from the DB clock."

The `UserFcmTokenReader` interface signature for the on-send-prune path is `deleteTokenIfStale(userId, platform, token, dispatchStartedAt) → Int` (the bare `deleteToken(...)` shape from the initial design draft was promoted to this race-guarded form during the round-1 review of PR [#60](https://github.com/aditrioka/nearyou-id/pull/60); the `dispatchStartedAt` source was re-decided from `Instant.now()` to `SELECT NOW()` during the round-3 review). Tests cover both: race-free (predicate matches, row deleted) and racey (predicate doesn't match, row preserved).

### D13. Visible_users posture: shadow-banned actors are MASKED in the FCM push body

The actor-username lookup uses `FROM visible_users` (not `FROM users`). This is a privacy decision documented normatively in the spec.

**Decision: shadow-banned actors' usernames are masked in the FCM push body. The dispatcher reads from `visible_users`, which returns NULL/sentinel `username` for shadow-banned (and hard-deleted) users. The downstream `PushCopy.bodyFor(notification, actorUsername=null)` produces the actor-less fallback (e.g., `"Seseorang menyukai post-mu"`).**

Rationale:

1. **Shadow-ban contract per [`docs/06-Security-Privacy.md` § Shadow Ban](../../../docs/06-Security-Privacy.md):** "all actions succeed from the banned user's perspective, invisible to others. High-friction layer." The intent is high-friction so a shadow-banned user can't easily detect their state. Surfacing their handle to recipients in a high-velocity surface (push notifications) inverts the friction — the recipient may respond, the response surfaces to the shadow-banned user (because they're not blocked-from-receiving), and the shadow-banned user infers their content is being seen. We preserve the high-friction posture by masking.

2. **Industry convention.** Reddit, Hacker News, X, and most mature platforms with shadow-ban surfaces produce *no* recipient signal for shadow-banned actors' actions — the action drops silently. The closest within-scope approximation here, given that `notifications` rows are *already* being created (NotificationEmitter doesn't shadow-ban-suppress today), is to surface a generic actor signal rather than the actor's real handle.

3. **Defense in depth.** The notifications path has TWO suppression layers today (self-action and bidirectional `user_blocks`). Reading actor-username via `visible_users` adds a THIRD layer that aligns with the shadow-ban contract elsewhere in the system (the timeline / search / feed surfaces all read via `visible_*` views). Adding the new push surface with the safer posture matches the rest of the system.

4. **Outcome parity, not mechanism parity.** Today's `GET /api/v1/notifications` endpoint returns `actor_user_id` UUID with no username text ([`backend/ktor/.../notifications/NotificationRoutes.kt`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/notifications/NotificationRoutes.kt) — verified by the round-3 review of PR [#60](https://github.com/aditrioka/nearyou-id/pull/60)). The mobile client renders the username via a separate profile-lookup endpoint that already filters via shadow-ban-aware paths. So the *outcome* is "shadow-banned actor's name is NOT directly surfaced to the recipient by the notifications endpoint." This change reads via `visible_users` to mirror that outcome on the push surface — different mechanisms (in-app: separate profile fetch; push: server-side `visible_users` read), same outcome.

5. **Reversibility.** If product later decides shadow-banned actors SHOULD surface to recipients via push, switching `visible_users → users` is a one-line revert. Going the other way after shipping creates a privacy regression that's hard to walk back.

6. **Upstream design flaw acknowledged.** The cleaner architectural fix is for `NotificationEmitter` to also suppress on shadow-ban — not just `user_blocks`. That's an `in-app-notifications` capability change, out of scope for `fcm-push-dispatch`. Tracked as a follow-up in `FOLLOW_UPS.md` at apply-time.

This decision is promoted to a normative spec requirement in `specs/fcm-push-dispatch/spec.md` (see § "Actor-username lookup SHALL use `FROM visible_users` to mask shadow-banned and deleted actors").

**Earlier-draft retraction:** an earlier version of this design (rounds 1 + 2) proposed reading from raw `users` and rationalized the choice as "preserving in-app/push parity." Round-3 review of PR [#60](https://github.com/aditrioka/nearyou-id/pull/60) verified that the asserted parity does not exist — `GET /api/v1/notifications` does not return username text — so the rationale was factually wrong. The current decision corrects the analysis and flips the read source. Documented here so the rationale-trail is preserved for future readers.

### D14. Composite exception propagation: catch-and-log at ERROR + counter metric, never silent-swallow

`FcmAndInAppDispatcher.dispatch(...)` invokes `FcmDispatcher.dispatch(...)` then `InAppOnlyDispatcher.dispatch(...)`. Both implementations are designed to never throw — `FcmDispatcher` catches all transport-level exceptions internally and surfaces them as WARN logs (per spec); `InAppOnlyDispatcher` is a single `logger.info(...)` call.

**Decision: the composite SHALL wrap each delegate call in its own try/catch. Any exception caught is logged at ERROR severity with `event="fcm_composite_dispatcher_unexpected_error"`, `delegate=<className>`, `notification_id`, `notification_type`. A counter metric `fcm_composite_unexpected_error_total{delegate=...}` SHALL be incremented (Cloud Monitoring custom metric or OTEL counter — implementer's choice). The exception is NOT propagated to the caller.**

Rationale: silent swallowing would mask emit-site bugs (e.g., a future NPE in the in-app log path because `body_data` shape changed unexpectedly). The earlier-draft posture (rounds 1+2) used FATAL severity — round-3 review of PR [#60](https://github.com/aditrioka/nearyou-id/pull/60) flagged this as over-aggressive: a latent NPE in the in-app log path would page on-call for *every* notification emit, which is wrong-shaped for a defense-in-depth catch. ERROR + counter metric is the proportional shape: structured ERROR appears in Sentry / Cloud Logging, the metric supports a Cloud Monitoring alert at "rate > 5/min" so noisy regressions still page, and a one-shot ERROR doesn't trip the alarm. Audit-trail integrity (the in-app log) is preserved either way.

### D15. Secret rotation: process restart required (no hot-reload)

The Firebase Admin SDK's `FirebaseApp` is a process-wide singleton initialized once at startup with the service-account JSON. The SDK does NOT support hot-reload of credentials.

**Decision: rotating `firebase-admin-sa` (or `staging-firebase-admin-sa`) in GCP Secret Manager requires a process restart — typically a Cloud Run revision rollout. The rotation is NOT picked up mid-process.**

This is documented in the spec § "Service-account JSON SHALL be read via `secretKey(env, name)`" with an explicit scenario asserting the no-hot-reload posture. Operational implication: an emergency credential rotation requires a Cloud Run revision deploy, not just a Secret Manager update — same posture as every other process-singleton secret in the system.

### D16. `:infra:fcm` boundary edge: Koin DI binding may reference `FirebaseMessaging` type in `:backend:ktor`

Spec § "`:infra:fcm` module SHALL encapsulate the Firebase Admin SDK" claims `:infra:fcm` is the only module with `import com.google.firebase.*`. But the production Koin DI module in `:backend:ktor` must construct the `FirebaseMessaging` instance and pass it to `FcmDispatcher`'s constructor — meaning `:backend:ktor`'s DI module references the type at compile-time.

**Decision: `:backend:ktor` MAY import `com.google.firebase.messaging.FirebaseMessaging` strictly for DI binding signatures (Koin module + factory). The boundary scenario in the spec is amended from a strict SHALL to a SHOULD — the lint check is a `grep`-based assertion in the test suite (see tasks 8.3), NOT a Detekt rule, so this rule is best-effort code-review-grade rather than CI-enforced. Round-3 review of PR [#60](https://github.com/aditrioka/nearyou-id/pull/60) flagged this distinction: claims-without-CI-enforcement rot. The spec scenario "`:backend:ktor` Firebase imports are scoped to DI-binding files only" is downgraded from a hard guarantee to a guideline that future contributors must respect at PR-review time. A future Detekt rule (e.g., `FirebaseImportBoundaryRule` allowlisting `*Module.kt` filenames or files annotated `@FcmDiBinding`) would tighten this back to SHALL — tracked as a `FOLLOW_UPS.md` candidate at apply-time.**

A stricter alternative (factory function in `:infra:fcm` exposing only `NotificationDispatcher`-typed return values) would hide the Firebase types entirely from `:backend:ktor` at the cost of a non-idiomatic DI shape. Picked the simpler pattern with the documented exception + the explicit rot-acknowledgment.

### D17. (Promoted from Q1) No boot-time FCM liveness check via `dryRun`

The boot-time service-account validation (D7) confirms the secret parses + the SDK accepts the credentials. It does NOT exercise an actual FCM call (e.g., `FirebaseMessaging.send(dryRun=true)` against a synthetic token).

**Decision: do NOT add a boot-time FCM liveness check.** Cost: an external dependency call at every cold start (Cloud Run scales-to-zero — cold start latency matters for the first request after idle). Benefit: catches credential-rotation-broken case before the first real emit. Trade-off lands on cost: Cloud Logging on the first failed real emit produces a structured WARN that's identical in operator value to a startup-time WARN, and the latter taxes every cold start. If a rotation breaks the credential, the next emit's WARN raises the alarm and the operator rolls back / fixes the secret slot.

### D18. (Promoted from Q2) No Redis cache for `actor_username` lookup

The `actor_username` lookup hits Postgres on every dispatch. Per-emit cardinality is bounded by recipient token count (typically 1–3) AND the lookup is a single PK index seek (sub-millisecond).

**Decision: do NOT cache the `actor_username` lookup.** A 5-minute Redis TTL would trim Postgres load by ~5x on a hot user but introduces invalidation complexity (username-customization writes must invalidate the cache) for a saving that's invisible at MVP scale. Revisit if profiling under load shows the lookup is a bottleneck.

### D19. (Promoted from Q3) No `fcm_dispatch_enabled` Remote Config kill-switch

A Remote Config flag could disable all FCM dispatch in an incident.

**Decision: do NOT add the kill-switch in this change.** Rationale per D1: rotating the secret-slot value to invalid achieves the same effect (emits fall back to in-app-only via the WARN-on-transport-failure path), and the composite dispatcher already isolates FCM failures from the in-app log path. A kill-switch RC flag adds a hot-path runtime branch on every emit for an action achievable with a Secret Manager update + restart. Add later if operational pain materializes.

### D20. Defensive logback floor for FCM Admin SDK loggers

The Firebase Admin SDK uses `java.util.logging` (j.u.l) and Apache HttpClient internally. At low severities (`FINE` / `FINER`) those loggers MAY emit request URLs, response bodies, or other internals — and while the SDK does not (as of the current stable line) log raw FCM tokens at default severities, defense-in-depth dictates we never assume a future SDK release will hold that contract.

**Decision: the `:infra:fcm` module's logback (or `logback-classic`) configuration SHALL pin the following loggers to `INFO` floor at startup, preventing accidental token-bearing payload emission via the SDK's internal logs:**

```xml
<logger name="com.google.firebase" level="INFO"/>
<logger name="com.google.api.client" level="INFO"/>
<logger name="com.google.cloud" level="INFO"/>
```

The pin is implementation-defensive, not contract-driven — it does not change observable behavior of the dispatcher, but reduces the surface where a future SDK upgrade could regress token confidentiality. Tasks 5.5 includes the logback-config addition.

### D21. Future FCM health-check probes SHALL reuse the initialized `FirebaseMessaging` singleton

If a future change adds an FCM connectivity health-check (e.g., `/health/fcm` for ops dashboards or for the existing `/health/ready` probe surface), the implementation MUST reuse the already-initialized `FirebaseMessaging` singleton via Koin (`Koin.get<FirebaseMessaging>()` from `:backend:ktor`'s DI module). It MUST NOT re-read `firebase-admin-sa` via `secretKey(env, ...)` directly.

Rationale: the secret is read exactly once at startup per D7 and D15. A second read path would (a) break the "secret rotation requires process restart" invariant (D15), (b) create a second initialization surface that could drift from the boot path's validation contract, and (c) duplicate the secret-handling code that the `secretKey(env, name)` helper exists to centralize.

This decision is forward-defensive — no health-check probe is implemented in this change; D21 captures the contract for the next change to honor.

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

(none remaining — Q1–Q3 from the initial design pass were promoted to numbered Decisions D17–D19 after the round-1 review of PR [#60](https://github.com/aditrioka/nearyou-id/pull/60); Q4's substance landed in D13 as a normative spec requirement.)
