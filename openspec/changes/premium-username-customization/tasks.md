## 1. Pre-implementation verification

- [ ] 1.1 Confirm migration-free: verify `users.username` / `users.username_last_changed_at` / `users.subscription_status` (V2) and `reserved_usernames` / `username_history` + its 3 indexes (V3) all exist; assert this change adds NO new `V<N>__*.sql` (the next free V-number, V21, stays available for the in-flight claims).
- [ ] 1.2 Confirm the Premium-status accessor to reuse: the principal `subscription_status` claim + `PREMIUM_STATES = {"premium_active", "premium_billing_retry"}` (mirror `SearchService`); the username gate reads the claim, NOT a `FROM users` row (keeps clear of `BlockExclusionJoinRule`).
- [ ] 1.3 Confirm the feature-flag accessor (`premium_username_customization_enabled`) via the existing `RemoteConfig` seam (mirror `SearchService`'s kill-switch read); default TRUE.
- [ ] 1.4 Reuse `NotificationEmitter.emit(conn, …)` (`notifications/NotificationEmitter.kt:34`) as the transaction-aware notification seam — it exists (resolves design Open Question #1); no new helper needed. Confirm `username_release_scheduled` emits in-app only (no FCM push for this self-action type).
- [ ] 1.5 Locate `UsernameGenerator`'s reserved + release-hold collision predicate and decide the share shape (extract a shared function vs. a repository method) — no duplicated SQL (design D7, Open Questions).
- [ ] 1.6 No `gradle/libs.versions.toml` change is expected (no new library/module); confirm before implementation.

## 2. Repository layer (JDBC)

- [ ] 2.1 `JdbcUsernameRepository` (or extend the existing user repo): read-only candidate validation reads — reserved-list `LOWER` match (incl. `source = 'admin_added'`), release-hold `LOWER(old_username)` with `released_at > NOW()`, current-username `LOWER` match — on the shared pool-sized `limitedParallelism` JDBC dispatcher (docs/11 § 3.2).
- [ ] 2.2 Transactional change operation: `SELECT … FOR UPDATE` on the user row, under-lock re-validation, `UPDATE users SET username = ?, username_last_changed_at = NOW()` (carrying the `// @allow-username-write: customization` annotation), `INSERT INTO username_history (…, released_at = changed_at + INTERVAL '30 days')`, and the `username_release_scheduled` notification via `NotificationEmitter.emit(conn, …)` (D6) — all on one `Connection`/transaction.
- [ ] 2.3 `moderation_queue` insert for a flagged candidate: `target_type = 'user'`, `target_id = <user id>`, `trigger = 'username_flagged'`, with `ON CONFLICT (target_type, target_id, trigger) DO NOTHING` (V9 UNIQUE → idempotent, one standing flag per user). Reuse the existing moderation-queue write path if one exists.

## 3. Service layer

- [ ] 3.1 `UsernameChangeService` gate ordering: feature-flag → Premium (principal claim) → 30-day cooldown (`username_last_changed_at`) → format validation → collision → moderation → commit (design D2). Returns a typed `Result` per outcome (mirror `SearchService.Result`).
- [ ] 3.2 Candidate format validator: length 3–30, charset regex `^[a-z0-9][a-z0-9_.]*[a-z0-9_]$`, application-layer `!candidate.contains("..")` no-consecutive-dots guard.
- [ ] 3.3 Moderation step: run the candidate through the existing text-moderation pipeline; on hit → reject (422 `username_rejected`) + idempotent `username_flagged` queue row (task 2.3). The >3/24h case is governed by the failed-attempt throttle (task 4.1); the numeric anomaly-score effect is DEFERRED to anomaly-detection (docs/08 Phase 4 #17) — do NOT add an `anomaly_score` column (design D8; migration-free).
- [ ] 3.4 Cooldown check against `username_last_changed_at` (DB-authoritative; design D3).
- [ ] 3.5 Availability-probe service path: read-only validation, explicitly non-authoritative (design D4).

## 4. Rate limiting

- [ ] 4.1 `UsernameRateLimiter` (mirror `SearchRateLimiter`/`FollowRateLimiter`): 10 FAILED change attempts / hour + 3 availability probes / day, via `computeTTLToNextReset` + `{scope:<value>}` hash-tag keys (rate-limit invariants; no hardcoded midnight math). The 1-change/30-day cooldown is NOT a Redis key (it's DB-authoritative, task 3.4).

## 5. Routes + wiring

- [ ] 5.1 `UserUsernameRoutes` — thin `PATCH /api/v1/user/username` (`{ new_username }`) + `GET /api/v1/username/check?candidate=` under the authenticated `/api/v1` tree; map each service `Result` to its status/body (403 `premium_required` envelope, 503 flag-off, 429 cooldown/rate, 422 `invalid_username`/`username_rejected`, 409 `username_unavailable`, 200 success). Routes never touch SQL (docs/11 § 3.1).
- [ ] 5.2 Koin DI wiring (repository, service, rate limiter) + mount the routes in `Application.kt`.

## 6. Tests (the docs/08 Pre-Launch Premium-username matrix — all scenarios, none dropped)

- [ ] 6.1 Free user → `403 premium_required` paywall (PATCH and GET check).
- [ ] 6.2 Premium user (`premium_active` AND `premium_billing_retry`) succeeds within cooldown rules → `200`, username + `username_last_changed_at` updated.
- [ ] 6.3 30-day cooldown: second change within window → `429`; change after 30 days (or NULL `last_changed_at`) → proceeds. Pin the inclusive boundary (`username_last_changed_at` exactly 30 days ago → allowed) so a `>` vs `>=` off-by-one is caught.
- [ ] 6.4 Collision → `409 username_unavailable`: reserved candidate (incl. `admin_added`) AND a candidate currently owned by another live user (`LOWER` match, non-concurrent path — distinct from the 6.12 race).
- [ ] 6.5 Release-hold candidate (`released_at > NOW()`) → `409`; after 30 days elapse → claimable.
- [ ] 6.6 Profanity / UU ITE candidate → `422 username_rejected` + an idempotent `moderation_queue` row (`target_type='user'`, `target_id=<user id>`, `trigger='username_flagged'`, `ON CONFLICT DO NOTHING` → a 2nd flagged candidate adds NO 2nd row); the >3-flagged/24h case is governed by the failed-attempt throttle, NOT an `anomaly_score` write (deferred — assert no such column/path is added).
- [ ] 6.7 Feature flag OFF → `503` (PATCH and GET check).
- [ ] 6.8 Downgrade-to-Free keeps the custom username (no revert) but blocks further changes (`403`); re-subscribe re-enables but the cooldown is still measured from the last change.
- [ ] 6.9 Username regex matrix: valid (`abc`, `a_b.c`, `user1.test_2`) accept; invalid (`.abc`, `abc.`, `a..b`, `_abc`, `ab`, 31-char, uppercase) reject (`422`); consecutive-dot `a..b` caught by the application-layer guard, not the regex.
- [ ] 6.10 Username-history release-hold lifecycle: Alice `oldname`→`newname` writes history with `released_at = changed_at + 30d`; Bob's claim of `oldname` during the window → `409`; after 30 days → claimable.
- [ ] 6.11 Successful change writes the `username_release_scheduled` notification (`{old_username, released_at}`) in the same transaction; a mid-transaction failure leaves no partial write (atomicity / rollback).
- [ ] 6.12 Concurrency: two concurrent PATCHes for the same handle → exactly one `200`, the other `409` (unique-index backstop).
- [ ] 6.13 Rate limits: 11th failed change attempt/hour → `429`; 4th probe/day → `429`. Assert BOTH reset windows (hourly failed-attempt + daily probe) derive their TTL from `computeTTLToNextReset` (no hardcoded midnight math).
- [ ] 6.14 Availability probe is non-authoritative: a candidate available at probe time but taken before PATCH → PATCH `409` under the lock.
- [ ] 6.15 New DB-tagged `*RoutesTest` pools `autoClose(hikari())` at size ≤2 (CI connection-budget invariant) — verify the spec doesn't leak a HikariPool.
- [ ] 6.16 Auth boundary: unauthenticated `PATCH /api/v1/user/username` and `GET /api/v1/username/check` → `401` before the flag/premium/cooldown gates run (backs the spec "Unauthenticated request is rejected" scenario).
- [ ] 6.17 Availability-probe happy paths: probe a well-formed free candidate → `200` available; probe a reserved / release-held / currently-taken candidate → `200` unavailable (backs the two probe-outcome spec scenarios).

## 7. Conformance + reconciliation

- [ ] 7.1 Annotate the `UPDATE users SET username` write with `// @allow-username-write: customization` (invariant #7 — a comment-convention allowlist per `project.md:161`, NOT a Detekt-enforced rule; this is the first-ever username `UPDATE` writer, the slot the invariant reserves). Run `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` locally (CI runs both lint frameworks — pre-push gate).
- [ ] 7.2 Flip the DESIGN status notes to shipped: `docs/02` § Premium Username Customization, `docs/05` § Premium Customization Endpoint (line 293) — and reconcile `docs/02`'s "soft-flags" wording against the implemented REJECT-upfront behavior (align docs/02 to docs/06, OR file a `follow-up` issue if a wider doc rewrite is warranted; do not silently diverge).
- [ ] 7.3 PR title/body kept current at each phase boundary (`feat(backend): premium username customization` at first feat commit); record any non-blocking review findings in the PR body.
