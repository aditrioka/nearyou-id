## 1. Schema (V34)

- [x] 1.1 Add `backend/ktor/src/main/resources/db/migration/V34__login_events.sql` creating `login_events` per the `login-history-tracking` spec: `id`, `user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE`, `occurred_at`, `event_type VARCHAR(16) CHECK (event_type IN ('signin','refresh'))`, `ip INET`, `ip_subnet_24 INET GENERATED ALWAYS AS (network(set_masklen(ip, 24))) STORED`, `device_fingerprint_hash TEXT`, `identifier_hash TEXT`, plus the `(user_id, occurred_at DESC)` index. No `NOW()` in any index predicate.
- [x] 1.2 Confirm V34 is the next free version against `origin/main` immediately before pushing (parallel-session Flyway-collision guard); renumber if a sibling change merged a V34 first.
- [x] 1.3 Migration smoke test (`MigrationV34SmokeTest`, the `migration-pipeline` precedent): table + columns exist, `event_type` CHECK rejects an out-of-set value, the FK is `ON DELETE CASCADE`, the generated `ip_subnet_24` masks the host octet (and is NULL when `ip` is NULL), and the `(user_id, occurred_at)` index exists.

## 2. Login-event write path

- [x] 2.1 Add `LoginEventRepository` (JDBC, `Connection`-per-call, bounded DB dispatcher per docs/11 §3.2 — the `ReferralGrantRepository` precedent) with an `insert(userId, eventType, ip, deviceFingerprintHash, identifierHash)` writing one row.
- [x] 2.2 Add `LoginEventRecorder` wrapping the repository with the fail-soft contract (catch + log; never throw into the auth response).
- [x] 2.3 Wire the recorder into `AuthRoutes.kt` `signin` handler: on the success path (after `tokens.issue`), record `event_type='signin'`, `ip = call.clientIp`, `device_fingerprint_hash = req.deviceFingerprintHash`, `identifier_hash = subHash` (the already-computed `sha256Hex(verified.sub)`).
- [x] 2.4 Wire the recorder into `AuthRoutes.kt` `refresh` handler: on the success path (after the access token is issued for a non-banned/non-deleted account), record `event_type='refresh'`, `ip = call.clientIp`, `device_fingerprint_hash = req.deviceFingerprintHash`, `identifier_hash` looked up from the user row (`google_id_hash` / `apple_id_hash`). Do NOT touch `RefreshTokenService`.
- [x] 2.5 Wire `JdbcLoginEventRepository` + `LoginEventRecorder` in `Application.kt` (the backend's manual composition root — no Koin module for this) and thread the recorder into `authRoutes(...)`.
- [x] 2.6 Tests: signin writes a `signin` row with the hashed identifier + clientIp; refresh writes a `refresh` row AND its `identifier_hash` equals the account's STORED provider hash (`google_id_hash`/`apple_id_hash` per provider — not the signin `sub` path); a rejected signin AND a denied refresh each write nothing; a failing insert does NOT fail the auth response on BOTH the signin and refresh call sites (fail-soft); the stored IP is `call.clientIp` (not a raw header).
- [x] 2.7 Consent-exemption behavioral test (the UU-PDP guarantee, design D5): a successful signin (and refresh) by a user with `analytics_consent.analytics = false` STILL writes a `login_events` row — the behavioral assertion, distinct from "the code does not read the flag".

## 3. Referral activity-gate leg activation

- [x] 3.1 Add four gate-read methods to `ReferralGrantRepository` (each `(conn, ...)`-style, reading `login_events`, with the sanctioned raw-read lint annotations where a raw `users` join is needed): invitee distinct login-days (≥3, `Asia/Jakarta` day-bucketed), invitee sessionized app-sessions (≥5, 30-min idle-gap `LAG` window), invitee-vs-inviter device-fingerprint 90-day collision (leg 3, voids), invitee identifier on `login_events` (within the inviter's last 90 days) sharing the inviter's **device-fingerprint** (leg 5, voids — fingerprint-sharing only, NOT subnet). The `ip_subnet_24` is recorded at write time only — there is NO /24 voiding read method (D8a, operator decision).
- [x] 3.2 Extend `ReferralActivityCheckWorker` gate evaluation: the device-fingerprint anti-collision legs (3 + 5) void the ticket (terminal `'voided'`, reusing `voidTicket`) on any positive match; engagement legs (login-days, sessions) join the ≥2-posts leg → below threshold keeps `pending_activity`; a ticket passes only when all voiding legs clear. Preserve the existing inviter-ban void + posts/expiry behavior.
- [x] 3.3 IP /24 is recorded-only / NON-voiding (D8a, operator decision): `ip_subnet_24` is written by the login-event recorder and exported, but the worker performs NO /24 voiding read and leg 5 shares on device-fingerprint only (no subnet arm). Add a short code comment pointing to D8a + docs/01 §213 so a future NAT-aware corroboration heuristic has a clear seam.
- [x] 3.4 Tests (`ReferralActivityCheckWorkerTest` additions): all-legs-pass → granted; **exactly** 2 posts / 3 login-days / 5 sessions → granted (inclusive-bound boundary); below login-days → pending; below sessions → pending; fingerprint collision → voided; **/24 overlap ALONE (no fingerprint/identifier match) → NOT voided** (the non-voiding guarantee, D8a); recently-seen-identifier collision (shared device-fingerprint) → voided; absence of inviter history → no false void; anti-collision void takes precedence over an engagement shortfall (void, not pending); voided vs expired terminal distinction; the existing posts/inviter-ban scenarios still pass.
- [x] 3.5 Tests for the two boundary-sensitive leg queries: **sessionization** — events at 29-min spacing collapse to 1 session, a > 30-min gap yields a new session, the first (NULL-`LAG`) event counts; **login-days WIB bucketing** — two events straddling UTC midnight but on the same `Asia/Jakarta` day count as ONE login-day.

## 4. Retention sweep

- [x] 4.1 Add `deleteOldLoginEvents()` to `RetentionCleanupRepository` + `JdbcRetentionCleanupRepository` (`DELETE FROM login_events WHERE occurred_at < NOW() - INTERVAL '90 days'`, own-connection independent sweep).
- [x] 4.2 Wire the new sweep + its `login_events_deleted` count into `RetentionCleanupWorker`, the `/internal/cleanup` response body, AND the single structured `retention_cleanup` INFO log line (now four per-sweep counts, not three).
- [x] 4.3 Tests: a >90-day row is purged + counted; a <90-day row survives; a row at **exactly 90 days survives** (the strict-`<` boundary); the per-invocation response carries `login_events_deleted`; the structured log line carries the four counts; the all-zero idempotent re-run includes `login_events_deleted = 0`.

## 5. Data-export integration

- [x] 5.1 Switch `DataExportGatherRepository.gatherSessions` (+ `SessionExportRow`) to source from `login_events` (last-90-day window), emitting `occurred_at`, `event_type`, `device_fingerprint_hash`, `ip`, `ip_subnet_24`; carry the own-content lint annotations as the other own-data reads do.
- [x] 5.2 Tests: the session-history CSV is sourced from `login_events`, includes the IP + /24 (no longer omitted), and is bounded to the 90-day window.

## 6. Account hard-delete cascade

- [x] 6.1 Add `DELETE FROM login_events WHERE user_id = ?` to `AccountHardDeleteWorker`'s explicit cascade set (alongside refresh_tokens/follows/blocks/fcm/notifications) — the FK does not fire on a tombstoned (not row-deleted) user.
- [x] 6.2 Tests: a hard-deleted user's `login_events` rows are gone; the existing cascade + tombstone + retain-content scenarios still pass.

## 7. Docs reconciliation

- [x] 7.1 docs/01 §212: reword "≥5 app sessions (tracked via the `session_start` event)" → server-side `login_events` sessionization (security-purpose, not the consent-gated client event).
- [x] 7.2 docs/05 §1231/§1233: note the login-history data is now tracked (`login-history-tracking` / V34) and the deferred anti-collision + engagement legs are now active.
- [x] 7.3 docs/06 § Analytics & Tracking Consent: document the `login_events` security/legitimate-interest exemption from the `analytics` toggle (always-on for authenticated users, security purpose).
- [x] 7.4 docs/06 § Data Export Scope Matrix MVP-limitation note: update "Session history — `refresh_tokens` has no IP column" → sourced from `login_events`, IP now included.
- [x] 7.5 docs/06 § Account Deletion (Tombstone Pattern) cascade-delete list: add `login_events`.
- [x] 7.6 docs/06 § Retention Policy: clarify the "Session trail | 90 days auto-purge" row is enforced over `login_events`.
- [x] 7.7 docs/01 §213 (Anti-fingerprint-collision): amend the standalone "invitee IP subnet (/24) NOT among the inviter's last 10 login subnets" criterion to reflect the operator decision (D8a) — `ip_subnet_24` is recorded but **non-voiding**; anti-collision voiding is device-fingerprint-based (the invitee's fingerprint in the inviter's 90-day history; the invitee's identifier seen on the inviter's device). State the carrier-grade-NAT rationale so the divergence from the original criterion is explicit and documented.

## 8. Verification + lifecycle

- [x] 8.1 Pre-push gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` green locally (both lint frameworks).
- [x] 8.2 Full-suite DB run against fresh PostGIS + Redis containers (CI-equivalent) so no seed pollution / cross-spec interaction false-fails — 184 specs / 2240 tests, 0 failures.
- [ ] 8.3 Staging smoke (no-creds where possible): `/health/ready` 200 (V34 applied); a signin/refresh writes a `login_events` row; `/internal/cleanup` → 200 with `login_events_deleted`; export includes the IP-bearing session-history CSV. (Deferred — no `smoke-login-history-tracking.sh` yet; run via a staging branch deploy in the verify-loop / pre-archive phase.)
- [x] 8.4 `openspec validate login-history-tracking --strict` green before the squash-merge.
- [ ] 8.5 `openspec archive login-history-tracking` + spec sync (new `login-history-tracking`; modified `referral-grant-worker`, `scheduled-retention-cleanup`, `account-data-export`, `account-hard-delete-worker`, `referral-ticket-creation`); `openspec validate --specs … --strict` green for each touched spec.
- [ ] 8.6 At archive, refresh the now-stale descriptive prose in `openspec/specs/referral-grant-worker/spec.md` that the requirement deltas do not reach: the `## Purpose` block (states the login-history legs "are deferred — no durable source exists yet") and the untouched `### Requirement: Activity gate evaluates the durable legs` prose (its pass-condition is now the subset that the new `Activity gate evaluates the login-history legs` requirement completes) — so the canonical spec reads coherently post-archive.
