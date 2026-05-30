## 1. Pre-flight verification

- [ ] 1.1 Confirm the next Flyway migration number is **V18** (latest is `V17__admin_actions_log_created_idx.sql`).
- [ ] 1.2 Add a unit test asserting `UUID.nameUUIDFromBytes("system".toByteArray())` equals `54b53072-540e-3eb8-b8e9-343e71f28176` — pins the V18 migration literal and the Kotlin `SYSTEM_ACTOR_ID` constant to the same value so drift is caught at CI time (design D3).
- [ ] 1.3 Re-confirm the shipped `admin_actions_log` column shape against `V16__admin_users.sql` (`admin_id UUID NOT NULL` FK, `action_type VARCHAR(64)`, `target_type VARCHAR(32)`, `target_id TEXT`, `reason TEXT`, `before_state`/`after_state JSONB`, `ip INET`, `user_agent TEXT`) so the CTE INSERT column list + value types match exactly.

## 2. V18 migration — seed the `system` sentinel

- [ ] 2.1 Create `backend/ktor/src/main/resources/db/migration/V18__seed_system_actor.sql` that `INSERT`s the sentinel `admin_users` row: `id = '54b53072-540e-3eb8-b8e9-343e71f28176'`, `email = 'system@system.nearyou.invalid'`, `display_name = 'System Actor'`, `password_hash = '!system-actor-no-login!'` (non-PHC literal), `role = 'read_only'`, `is_active = FALSE`, `webauthn_enrolled = FALSE` (TOTP/webauthn columns left default/NULL), with `ON CONFLICT (id) DO NOTHING` for re-run safety.
- [ ] 2.2 Add a header comment citing `V16__admin_users.sql:24-26` (which defers this seed to this change) + `docs/05-Implementation.md:235`.
- [ ] 2.3 Verify the migration applies cleanly against the CI integration Postgres (table exists from V16; no `admin_app` role required — env-portable like V16).

## 3. Worker code — atomic unban + audit write

- [ ] 3.1 Add a `SYSTEM_ACTOR_ID` constant (`UUID.fromString("54b53072-540e-3eb8-b8e9-343e71f28176")`) in the admin worker package; the worker references it (and task 1.2's test asserts it equals the derived value).
- [ ] 3.2 Replace the handler's bare `UPDATE ... RETURNING id` with the data-modifying CTE (design D4): `eligible` snapshot (`FOR UPDATE`, 4-conjunct predicate verbatim) → `unbanned` UPDATE `RETURNING id, prev_suspended_until` → `INSERT INTO admin_actions_log ... RETURNING target_id`. Run inside the existing single transaction.
- [ ] 3.3 Derive `unbanned_count` and `unbanned_user_ids` (capped at 50 + `unbanned_user_ids_truncated`) from the `RETURNING target_id` result set; keep the `{"unbanned_count": N}` HTTP 200 response shape and the structured INFO log unchanged.
- [ ] 3.4 Keep the existing sanitized-500 handler-error classifier (`timeout`/`connection_refused`/`unknown`); a failed audit INSERT propagates as a rolled-back transaction → sanitized 500.

## 4. Tests

- [ ] 4.1 Audit-row content (real Postgres): one elapsed-suspension user flipped → exactly one `admin_actions_log` row with `admin_id` = sentinel UUID, `action_type='system_unban_applied'`, `target_type='user'`, `target_id` = user id, `reason='suspension_elapsed'`, `before_state` = `{is_banned:true, suspended_until:<prior expiry>}`, `after_state` = `{is_banned:false, suspended_until:null}`.
- [ ] 4.2 Atomicity: inject an `admin_actions_log` INSERT failure (e.g., a deferred constraint / test hook) → the transaction rolls back; the user still has `is_banned=TRUE` and its original `suspended_until`; no audit row persisted.
- [ ] 4.3 Zero-flip run → zero `admin_actions_log` rows written AND `{"unbanned_count":0}`.
- [ ] 4.4 Idempotent retry: first invocation writes one audit row; immediate second invocation flips zero rows and writes zero additional audit rows (no duplicate).
- [ ] 4.5 Three eligible users → exactly three audit rows, one per user, distinct `target_id`s.
- [ ] 4.6 Regression: re-run the five existing flip-eligibility scenarios (elapsed / future-dated / permanent / soft-deleted / already-active) against the CTE — flip behavior is unchanged from the pre-audit worker.
- [ ] 4.7 Sentinel cannot authenticate: `POST /admin/login` with `email='system@system.nearyou.invalid'` → standard no-enumeration failure, no `admin_sessions` row created.
- [ ] 4.8 Sentinel hash never verifies: `PasswordHasher.verify(<any plaintext>, "!system-actor-no-login!")` returns `false` (defense-in-depth, independent of `is_active`).
- [ ] 4.9 Sentinel hard-delete rejected: with the sentinel owning ≥1 `admin_actions_log` row, `DELETE FROM admin_users WHERE id = <sentinel>` is rejected by the FK.
- [ ] 4.10 Seed idempotency: applying the V18 seed statement twice leaves exactly one sentinel row, no error.

## 5. Lint + local verification

- [ ] 5.1 Run `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` locally — all green (both lint frameworks, per CLAUDE.md pre-push rule).
- [ ] 5.2 `openspec validate system-actor-and-worker-audit-rows --strict` passes.

## 6. Docs / lifecycle

- [ ] 6.1 Confirm no canonical-doc amendment is needed — `docs/05-Implementation.md:235` ("Audit log inserted per unban") + `docs/07-Operations.md` already describe the end state; this change makes it observed, not changed.
- [ ] 6.2 At archive: delete the `suspension-unban-worker-audit-log-after-phase-3.5` entry from `FOLLOW_UPS.md` (its action items are now merged).
- [ ] 6.3 At archive: confirm `openspec/specs/system-actor/spec.md` (new) and `openspec/specs/suspension-unban-worker/spec.md` (deltas applied) reflect the shipped behavior.
