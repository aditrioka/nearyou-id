## Context

The `suspension-unban-worker` (shipped, `POST /internal/unban-worker`) flips elapsed time-bound suspensions and records each run only in a structured INFO log (Cloud Logging, ~30-day retention). The canonical record per [`docs/05-Implementation.md:235`](../../../docs/05-Implementation.md) is "Audit log inserted per unban" against `admin_actions_log`, which shipped in V16 ([`V16__admin_users.sql`](../../../backend/ktor/src/main/resources/db/migration/V16__admin_users.sql)) + V17.

Two shipped-schema facts shape this change:

1. `admin_actions_log.admin_id` is `UUID NOT NULL REFERENCES admin_users(id)` (no `ON DELETE` → `NO ACTION`). A machine action therefore needs a *real* `admin_users` row to attribute to — there is no nullable-actor escape hatch. `V16__admin_users.sql:24-26` explicitly defers seeding that `system` sentinel to **this** change.
2. `admin_users.password_hash` is `TEXT NOT NULL` and `role` is `NOT NULL CHECK (role IN ('owner','admin','moderator','read_only'))`. A sentinel cannot have a NULL hash or NULL role.

The originating follow-up (`suspension-unban-worker-audit-log-after-phase-3.5`) was written before V16 shipped; its plan (NULL `password_hash`, a `password_hash IS NULL` auth guard, "all `/internal/*` workers") is corrected here — see Decisions D2/D5 and the proposal's Scope reconciliation block.

## Goals / Non-Goals

**Goals:**
- Seed a single deterministic `system` sentinel `admin_users` row that machine-initiated `admin_actions_log` rows attribute to.
- Make `suspension-unban-worker` write one immutable audit row per unban, atomic with the unban itself.
- Guarantee the sentinel can never authenticate.
- Keep the worker's existing INFO log, response shape, idempotency, and the verbatim eligibility predicate intact.

**Non-Goals:**
- Audit rows for any other worker (none exist yet — `suspension-unban-worker` is the only shipped moderation worker; `/internal/apple/s2s-notifications` is a user-initiated webhook, excluded).
- Any change to admin-login code or schema (the sentinel's un-loginability is structural — see D2).
- The `admin_actions_log` role-level immutability REVOKE (owned by the in-progress `admin-app-revoke-staging-and-prod` follow-up; the worker writes via the main app DB role, which holds INSERT).
- A down/rollback migration (Flyway is forward-only; the seeded row is inert if unused).

## Decisions

### D1 — Sentinel is a seeded `admin_users` row (not a nullable actor or a separate table)
`admin_actions_log.admin_id` is `NOT NULL REFERENCES admin_users(id)`. A real row is the only attribution mechanism that satisfies the shipped FK without a schema change.
- *Alternative — make `admin_id` nullable:* rejected. Weakens the FK + audit attribution; requires altering shipped V16 schema; "who did this" becomes ambiguous.
- *Alternative — separate `system_actors` table:* rejected. The FK targets `admin_users` only; would need a schema change + a second FK.

### D2 — Login is blocked structurally via `is_active = FALSE`, not via a NULL-hash guard
`AdminUserRepository.findActiveByEmail` uses `WHERE email = ? AND is_active = TRUE`, so a deactivated row's `password_hash` "is never even loaded for verification" (its own KDoc). Seeding the sentinel `is_active = FALSE` makes it un-loginable with **zero new auth code**, reusing already-tested behavior.
- Defense-in-depth: the sentinel's `password_hash` is a **well-formed** Argon2id PHC string whose plaintext is a randomly-generated secret produced once and discarded — so `PasswordHasher.verify` returns `false` for every candidate. A bare non-PHC literal was rejected in round-1 review: Password4j's `decodeHash` splits on `$` and **throws** `BadParametersException` on a malformed string, so a non-PHC value would turn a hypothetical future `is_active`-flip into a noisy 500 per sentinel-email login rather than a silent reject. The hash is of a discarded secret, so committing it to the source-available repo is not a credential leak (and the account is un-loginable regardless).
- Side effect (benign, documented so it isn't mistaken for a gap): a login *attempt* against the sentinel email takes the existing inactive-admin branch (`AdminLoginRoutes` → `findByEmailAnyStatus` → `auditLogger.logFailure(..., INACTIVE_ADMIN)`), writing one `inactive_admin` audit row attributed to the sentinel. It still returns the no-enumeration response with no session — identical to any other inactive admin.
- *Alternative — the follow-up's `password_hash IS NULL` guard:* rejected as **moot** — `password_hash` is `NOT NULL` (no NULL row can exist), and `is_active` already gates the lookup. Adding a guard would be dead code.

### D3 — Deterministic UUID, asserted equal across migration + Kotlin at CI time
`id = '54b53072-540e-3eb8-b8e9-343e71f28176'` = `UUID.nameUUIDFromBytes("system".toByteArray())`. Hardcoded as a literal in V18 and as a Kotlin `SYSTEM_ACTOR_ID` constant; a unit test asserts `UUID.nameUUIDFromBytes("system") == SYSTEM_ACTOR_ID` so the migration literal and the constant provably agree (drift is caught in CI, not production).

### D4 — Unban + audit write as a single data-modifying CTE (atomic, captures `before_state`)
The worker issues one statement: a CTE that snapshots eligible rows `FOR UPDATE`, performs the UPDATE, and INSERTs the audit rows, returning the affected user ids for the INFO log + count:

```sql
WITH eligible AS (
    SELECT id, suspended_until
      FROM users
     WHERE is_banned = TRUE
       AND suspended_until IS NOT NULL
       AND suspended_until <= NOW()
       AND deleted_at IS NULL
     FOR UPDATE
),
unbanned AS (
    UPDATE users u
       SET is_banned = FALSE, suspended_until = NULL
      FROM eligible
     WHERE u.id = eligible.id
    RETURNING u.id, eligible.suspended_until AS prev_suspended_until
)
INSERT INTO admin_actions_log
    (admin_id, action_type, target_type, target_id, reason, before_state, after_state)
SELECT
    '54b53072-540e-3eb8-b8e9-343e71f28176'::uuid,
    'system_unban_applied', 'user', unbanned.id::text, 'suspension_elapsed',
    jsonb_build_object('is_banned', true,  'suspended_until', unbanned.prev_suspended_until),
    jsonb_build_object('is_banned', false, 'suspended_until', null)
  FROM unbanned
RETURNING target_id;
```

The four-conjunct eligibility predicate is preserved **verbatim** inside `eligible` (the suspension-unban-worker spec's non-negotiable). It remains exactly one SQL statement in one transaction; `RETURNING target_id` yields the unbanned user ids (drives `unbanned_count` + the INFO log's `unbanned_user_ids`). Capturing `prev_suspended_until` in the snapshot lets `before_state` record the real prior expiry (post-UPDATE `RETURNING` would only see the NULLed value). Note: `jsonb_build_object('suspended_until', <timestamptz>)` renders Postgres's native JSON timestamptz form (offset + microseconds), not Java `Instant.toString()` (`…Z`); tests assert `before_state.suspended_until` by `::timestamptz` value-equality, not string match. The audit INSERT is uncapped — one row per flipped user — independent of the INFO log's 50-entry `unbanned_user_ids` cap.
- *Alternative — app-managed two statements (UPDATE...RETURNING id; then batch INSERT):* viable but adds a round-trip and loses `prev_suspended_until` unless a separate SELECT snapshots first. The single CTE is tighter and keeps "exactly one statement" true.

### D5 — Worker writes via the main app DB role; immutability REVOKE is out of scope
`admin_actions_log` immutability (no UPDATE/DELETE) is enforced at the role level on `admin_app` (per [`docs/07-Operations.md:27`](../../../docs/07-Operations.md)); the REVOKE itself is the in-progress `admin-app-revoke-staging-and-prod` follow-up. The worker runs on the main application DB connection, which retains INSERT on `admin_actions_log`, so it can write audit rows today regardless of that REVOKE's status.

### D6 — Audit row vocabulary
`action_type = 'system_unban_applied'` (fits `VARCHAR(64)`), `target_type = 'user'` (fits `VARCHAR(32)`), `target_id = <user_id>::text`, `reason = 'suspension_elapsed'`, `ip`/`user_agent` = NULL (no request context — Cloud Scheduler invocation). Matches the audit-row column shape at [`docs/07-Operations.md:106`](../../../docs/07-Operations.md).

### D7 — Testing the atomicity guarantee against real Postgres
The worker SQL is a compile-time constant with no app-level injection seam, so the "failed audit INSERT rolls back the unban" scenario is exercised by installing a transient fault at the database layer: the test creates a `BEFORE INSERT` trigger on `admin_actions_log` that unconditionally `RAISE`s (or a transient `CHECK` rejecting `action_type = 'system_unban_applied'`), invokes the worker, asserts the transaction rolled back (the target user is still `is_banned = TRUE` with its original `suspended_until`, and no `admin_actions_log` row persisted), then drops the trigger in a `finally`. This makes the atomicity scenario concretely testable rather than hand-wavy (round-1 review B2). Separately, the existing "Worker performance is bounded" `EXPLAIN`/index-scan test (unmodified, still in force) must be re-pointed at the new CTE — confirm the `eligible … FOR UPDATE` snapshot still plans as an index scan on `users_suspended_idx`.

## Risks / Trade-offs

- **A failed audit INSERT now rolls back the unban** (atomicity) → acceptable: Cloud Scheduler retries the idempotent endpoint; a transiently-failed batch retries cleanly. Strictly better than the alternative (an unban with no audit record, or vice-versa).
- **Hardcoded UUID drift** between the V18 literal and the Kotlin constant → mitigated by the D3 CI equality assertion.
- **Sentinel accidentally activated** in a future change → mitigated by the non-login `password_hash` literal (D2) + the cannot-authenticate regression test.
- **CTE complexity** vs. the prior single UPDATE → mitigated by the spec MODIFIED requirement showing the exact SQL + scenarios pinning the predicate and the audit-row shape.

## Migration Plan

1. **V18 data-seed migration** — `INSERT INTO admin_users (id, email, display_name, password_hash, role, is_active, webauthn_enrolled)` for the sentinel, using `ON CONFLICT (id) DO NOTHING` for re-run safety. No DDL; Flyway-portable; runs clean in the CI integration Postgres (the table exists from V16).
2. **Worker code** — replace the handler SQL with the D4 CTE; add `SYSTEM_ACTOR_ID`; the INFO log + response shape are unchanged (driven by `RETURNING target_id`).
3. **Deploy** — standard Flyway-on-deploy; the worker change ships in the same artifact. No data backfill (pre-change unbans remain only in Cloud Logging — acceptable per the follow-up's Impact note).
4. **Rollback** — forward-only; the seeded row is inert if the worker code is reverted.

## Open Questions

None blocking. `before_state`/`after_state` are deliberately scoped to the two fields the unban changes (`is_banned`, `suspended_until`) rather than the full user row — minimal, privacy-conscious, and sufficient for the "audit log inserted per unban" prescription. Surface at review if a fuller snapshot is wanted.
