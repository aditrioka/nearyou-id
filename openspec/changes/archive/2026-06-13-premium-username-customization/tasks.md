## 1. Pre-implementation verification — ✅ DONE (codebase study, /opsx:apply 2026-06-14)

- [x] 1.1 Migration-free CONFIRMED: `users.username`/`username_last_changed_at`/`subscription_status` (V2:20/23, CHECK in free/premium_active/premium_billing_retry) + `reserved_usernames`/`username_history` (V3:8/93) all exist. No new `V<N>__*.sql` — V21 stays free for the in-flight claims.
- [x] 1.2 Premium accessor CONFIRMED: `UserPrincipal.subscriptionStatus` (AuthPlugin.kt:52) + `SearchService.PREMIUM_STATES = setOf("premium_active","premium_billing_retry")` (SearchService.kt:166). Gate reads the principal claim — no `FROM users` row read.
- [x] 1.3 Feature-flag accessor CONFIRMED: `RemoteConfig.getBoolean(key) ?: true` (SearchService kill-switch precedent, SearchService.kt:103). Flag key `premium_username_customization_enabled`, default TRUE.
- [x] 1.4 `NotificationEmitter.emit(conn, recipientId, actorUserId, type, targetType, targetId, bodyData)` CONFIRMED (NotificationEmitter.kt:34) + `NotificationType.USERNAME_RELEASE_SCHEDULED` already in the enum (NotificationRepository.kt:126). NOTE: the emitter SUPPRESSES self-action (`actor==recipient`) → pass `actorUserId = null` (system-originated, the auto-hide precedent) so the user's own-action confirmation is NOT suppressed. In-app only (no FCM).
- [x] 1.5 Collision predicate CONFIRMED: `ReservedUsernameRepository.exists(conn, candidate)` + `UsernameHistoryRepository.existsOnHold(conn, lowercaseUsername)` (the interface in `auth/signup/UsernameHistoryRepository.kt`, whose KDoc reserves the real impl for THIS change). Reuse both; add the write side (see §8 notes).
- [x] 1.6 No `gradle/libs.versions.toml` change CONFIRMED (no new library/module).

## 2. Repository layer (JDBC)

- [x] 2.1 `JdbcUsernameRepository` (or extend the existing user repo): read-only candidate validation reads — reserved-list `LOWER` match (incl. `source = 'admin_added'`), release-hold `LOWER(old_username)` with `released_at > NOW()`, current-username `LOWER` match — on the shared pool-sized `limitedParallelism` JDBC dispatcher (docs/11 § 3.2).
- [x] 2.2 Transactional change operation: `SELECT … FOR UPDATE` on the user row, under-lock re-validation, `UPDATE users SET username = ?, username_last_changed_at = NOW()` (carrying the `// @allow-username-write: customization` annotation), `INSERT INTO username_history (…, released_at = changed_at + INTERVAL '30 days')`, and the `username_release_scheduled` notification via `NotificationEmitter.emit(conn, …)` (D6) — all on one `Connection`/transaction.
- [x] 2.3 `moderation_queue` insert for a flagged candidate: `target_type = 'user'`, `target_id = <user id>`, `trigger = 'username_flagged'`, with `ON CONFLICT (target_type, target_id, trigger) DO NOTHING` (V9 UNIQUE → idempotent, one standing flag per user). Reuse the existing moderation-queue write path if one exists.

## 3. Service layer

- [x] 3.1 `UsernameChangeService` gate ordering: feature-flag → Premium (principal claim) → 30-day cooldown (`username_last_changed_at`) → format validation → collision → moderation → commit (design D2). Returns a typed `Result` per outcome (mirror `SearchService.Result`).
- [x] 3.2 Candidate format validator: length 3–30, charset regex `^[a-z0-9][a-z0-9_.]*[a-z0-9_]$`, application-layer `!candidate.contains("..")` no-consecutive-dots guard.
- [x] 3.3 Moderation step: run the candidate through the existing text-moderation pipeline; on hit → reject (422 `username_rejected`) + idempotent `username_flagged` queue row (task 2.3). The >3/24h case is governed by the failed-attempt throttle (task 4.1); the numeric anomaly-score effect is DEFERRED to anomaly-detection (docs/08 Phase 4 #17) — do NOT add an `anomaly_score` column (design D8; migration-free).
- [x] 3.4 Cooldown check against `username_last_changed_at` (DB-authoritative; design D3).
- [x] 3.5 Availability-probe service path: read-only validation, explicitly non-authoritative (design D4).

## 4. Rate limiting

- [x] 4.1 `UsernameRateLimiter` (mirror `SearchRateLimiter`/`FollowRateLimiter`): 10 FAILED change attempts / hour + 3 availability probes / day, via `computeTTLToNextReset` + `{scope:<value>}` hash-tag keys (rate-limit invariants; no hardcoded midnight math). The 1-change/30-day cooldown is NOT a Redis key (it's DB-authoritative, task 3.4).

## 5. Routes + wiring

- [x] 5.1 `UserUsernameRoutes` — thin `PATCH /api/v1/user/username` (`{ new_username }`) + `GET /api/v1/username/check?candidate=` under the authenticated `/api/v1` tree; map each service `Result` to its status/body (403 `premium_required` envelope, 503 flag-off, 429 cooldown/rate, 422 `invalid_username`/`username_rejected`, 409 `username_unavailable`, 200 success). Routes never touch SQL (docs/11 § 3.1).
- [x] 5.2 Koin DI wiring (repository, service, rate limiter) + mount the routes in `Application.kt`.

## 6. Tests (the docs/08 Pre-Launch Premium-username matrix — all scenarios, none dropped)

- [x] 6.1 Free user → `403 premium_required` paywall (PATCH and GET check).
- [x] 6.2 Premium user (`premium_active` AND `premium_billing_retry`) succeeds within cooldown rules → `200`, username + `username_last_changed_at` updated.
- [x] 6.3 30-day cooldown: second change within window → `429`; change after 30 days (or NULL `last_changed_at`) → proceeds. Pin the inclusive boundary (`username_last_changed_at` exactly 30 days ago → allowed) so a `>` vs `>=` off-by-one is caught.
- [x] 6.4 Collision → `409 username_unavailable`: reserved candidate (incl. `admin_added`) AND a candidate currently owned by another live user (`LOWER` match, non-concurrent path — distinct from the 6.12 race).
- [x] 6.5 Release-hold candidate (`released_at > NOW()`) → `409`; after 30 days elapse → claimable.
- [x] 6.6 Profanity / UU ITE candidate → `422 username_rejected` + an idempotent `moderation_queue` row (`target_type='user'`, `target_id=<user id>`, `trigger='username_flagged'`, `ON CONFLICT DO NOTHING` → a 2nd flagged candidate adds NO 2nd row); the >3-flagged/24h case is governed by the failed-attempt throttle, NOT an `anomaly_score` write (deferred — assert no such column/path is added).
- [x] 6.7 Feature flag OFF → `503` (PATCH and GET check).
- [x] 6.8 Downgrade-to-Free keeps the custom username (no revert) but blocks further changes (`403`); re-subscribe re-enables but the cooldown is still measured from the last change.
- [x] 6.9 Username regex matrix: valid (`abc`, `a_b.c`, `user1.test_2`) accept; invalid (`.abc`, `abc.`, `a..b`, `_abc`, `ab`, 31-char, uppercase) reject (`422`); consecutive-dot `a..b` caught by the application-layer guard, not the regex.
- [x] 6.10 Username-history release-hold lifecycle: Alice `oldname`→`newname` writes history with `released_at = changed_at + 30d`; Bob's claim of `oldname` during the window → `409`; after 30 days → claimable.
- [x] 6.11 Successful change writes the `username_release_scheduled` notification (`{old_username, released_at}`) in the same transaction; a mid-transaction failure leaves no partial write (atomicity / rollback).
- [x] 6.12 Concurrency: two concurrent PATCHes for the same handle → exactly one `200`, the other `409` (unique-index backstop).
- [x] 6.13 Rate limits: 11th failed change attempt/hour → `429`; 4th probe/day → `429`. Assert BOTH reset windows (hourly failed-attempt + daily probe) derive their TTL from `computeTTLToNextReset` (no hardcoded midnight math).
- [x] 6.14 Availability probe is non-authoritative: a candidate available at probe time but taken before PATCH → PATCH `409` under the lock.
- [x] 6.15 New DB-tagged `*RoutesTest` pools `autoClose(hikari())` at size ≤2 (CI connection-budget invariant) — verify the spec doesn't leak a HikariPool.
- [x] 6.16 Auth boundary: unauthenticated `PATCH /api/v1/user/username` and `GET /api/v1/username/check` → `401` before the flag/premium/cooldown gates run (backs the spec "Unauthenticated request is rejected" scenario).
- [x] 6.17 Availability-probe happy paths: probe a well-formed free candidate → `200` available; probe a reserved / release-held / currently-taken candidate → `200` unavailable (backs the two probe-outcome spec scenarios).

## 7. Conformance + reconciliation

- [x] 7.1 Annotate the `UPDATE users SET username` write with `// @allow-username-write: customization` (invariant #7 — a comment-convention allowlist per `project.md:161`, NOT a Detekt-enforced rule; this is the first-ever username `UPDATE` writer, the slot the invariant reserves). Run `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` locally (CI runs both lint frameworks — pre-push gate).
- [x] 7.2 Flip the DESIGN status notes to shipped: `docs/02` § Premium Username Customization, `docs/05` § Premium Customization Endpoint (line 293) — and reconcile `docs/02`'s "soft-flags" wording against the implemented REJECT-upfront behavior (align docs/02 to docs/06, OR file a `follow-up` issue if a wider doc rewrite is warranted; do not silently diverge).
- [x] 7.3 PR title/body kept current at each phase boundary (`feat(backend): premium username customization` at first feat commit); record any non-blocking review findings in the PR body.

## 8. Apply-phase implementation map (codebase study notes — not checkbox work)

Exact seams + the small set of repo extensions this change needs. Closest analog end-to-end: the **search** feature (`backend/ktor/.../search/` — premium gate + flag + rate limit + `sealed Result` + `Application.searchRoutes`). Transaction shape to mirror: **`CreatePostService.create`** (`dataSource.connection.use { conn.autoCommit=false; try{…repo writes…; conn.commit() } catch { conn.rollback(); throw } finally { conn.autoCommit=true } }` inside `withContext(dbDispatcher)`; the service owns the tx boundary per docs/11 §3.1).

**New `:backend:ktor` `user`-package files:**
- `UserUsernameRoutes.kt` — `fun Application.userUsernameRoutes(service)`; `routing { authenticate(AUTH_PROVIDER_USER) { patch("/api/v1/user/username"){…}; get("/api/v1/username/check"){…} } }`. Map `Result` → HTTP: 403 `{"error":"premium_required","upsell":true}`, 503 `{"error":"feature_disabled"}`, 429 + `Retry-After`, 422 `{"error":"invalid_username"}` / `{"error":"username_rejected"}`, 409 `{"error":"username_unavailable"}`, 200. (401 is auto-emitted by `AUTH_PROVIDER_USER`.) Read tier from `principal.subscriptionStatus`.
- `UsernameChangeService.kt` — `sealed interface Result` (Success / FlagDisabled / PremiumRequired / CooldownActive / InvalidFormat / Unavailable / Moderated / RateLimited(retryAfterSeconds)). Gate order: flag (`RemoteConfig.getBoolean("premium_username_customization_enabled") ?: true`) → premium (`subscriptionStatus in PREMIUM_STATES`) → cooldown (`username_last_changed_at`, DB-authoritative, < 30d → CooldownActive) → format (3–30, regex `^[a-z0-9][a-z0-9_.]*[a-z0-9_]$`, `!candidate.contains("..")`) → collision (reserved + history-hold + current-username, read) → moderation (`textModerator.moderate(candidate)`: `Verdict.Reject` **or** `Verdict.Flag` → reject+queue; only `Verdict.Allow` proceeds) → commit tx. `check`/`probe` share the read-only validation. Failed PATCH consumes the 10/h limiter; `/check` consumes the 3/day limiter.
- `UsernameRateLimiter.kt` — mirror `PostRateLimiter` (daily) + `SearchRateLimiter` (hourly). Two limiters: **failed-attempt** hourly `keyFor(u)="{scope:rate_username_change}:{user:$u}"`, `window=Duration.ofHours(1)`, cap 10 (sliding); **probe** daily `keyFor(u)="{scope:rate_username_probe_day}:{user:$u}"` (`_day}` marker → fixed window), `ttl=computeTTLToNextReset(u, now)`, cap 3. `RateLimiter.tryAcquire(userId, key, capacity, ttl)`.
- Request/response DTOs (`@Serializable`): `{ new_username }` body; check-response `{ available: Boolean }`.

**Small repo extensions (interface + Jdbc impl):**
- `UserRepository` (`:infra:supabase`) ADD: `findByIdForUpdate(conn, id): UserRow?` (`SELECT … FOR UPDATE`), `usernameExists(conn, lowercaseCandidate): Boolean` (`LOWER(username)=?`), `updateUsername(conn, id, newUsername)` — the latter carries `// @allow-username-write: customization`.
- `UsernameHistoryRepository` (interface in `auth/signup`) ADD: `insertReleaseHold(conn, userId, oldUsername, newUsername)` (released_at = NOW()+30d); write the **real** `JdbcUsernameHistoryRepository` impl (existsOnHold + insertReleaseHold) in `:backend:ktor` and bind it in DI replacing `NoopUsernameHistoryRepository` (gives signup the real release-hold check too). Add a no-op `insertReleaseHold` to the Noop.
- `ModerationQueueRepository` (`:core:data`) ADD: `upsertUsernameFlaggedRow(conn, targetType=ReportTargetType.USER, targetId=userId)` (mirror `upsertUuIteKeywordMatchRow`; INSERT trigger='username_flagged' `ON CONFLICT (target_type,target_id,trigger) DO NOTHING`).
- Reuse as-is: `ReservedUsernameRepository.exists(conn, candidate)`, `NotificationEmitter.emit(conn, recipientId=userId, actorUserId=null, type=USERNAME_RELEASE_SCHEDULED, targetType=null, targetId=null, bodyData={old_username,released_at})`, `TextModerator.moderate`.

**DI + mounting (`Application.kt`):** manual `single { }` bindings (no Koin-module split) + a `xxxRoutes(service)` call near the other route installs; pass `DbDispatchers.db` as `dbDispatcher` and `dataSource` to the service.

**Build/verify:** backend test DB needs `scripts/setup_backend_db.sh` (SessionStart hook flagged it not migrated). Pre-push gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test`. DB-tagged `*RoutesTest` pools: `autoClose(hikari())` size ≤2 (CI connection budget).
