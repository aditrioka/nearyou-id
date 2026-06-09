# suspension-unban-worker Specification

## Purpose

The suspension-unban-worker capability defines `POST /internal/unban-worker`, the daily Cloud-Scheduler-invoked endpoint that flips `is_banned = FALSE` and `suspended_until = NULL` for every user whose 7-day suspension window has elapsed. Permanent bans (`suspended_until IS NULL`) and soft-deleted users (`deleted_at IS NOT NULL`) are deliberately untouched. The worker is gated by the `internal-endpoint-auth` OIDC plugin, runs at 04:00 WIB (`0 21 * * *` UTC), emits a structured `suspension_unban_applied` log per run, and returns `{"unbanned_count": N}` so Scheduler retries are observable. It pioneered the `/internal/*` route prefix that subsequent scheduled workers (privacy-flip, hard-delete, FCM cleanup, notifications purge) reuse.
## Requirements
### Requirement: `POST /internal/unban-worker` flips elapsed time-bound suspensions

The Ktor backend SHALL expose `POST /internal/unban-worker` as a Cloud-Scheduler-invoked endpoint that flips `is_banned = FALSE` and nulls `suspended_until` for users whose time-bound suspension window has elapsed. The endpoint MUST be mounted under `/internal/*` and is gated by the `internal-endpoint-auth` capability — every request requires a valid Google OIDC bearer token whose audience matches the configured `oidc.internalAudience` Ktor config value (resolved from the `INTERNAL_OIDC_AUDIENCE` environment variable; the audience is the deployed Cloud Run service URL, a public non-secret value).

The handler SHALL execute exactly one SQL statement, in a single transaction — a data-modifying CTE that snapshots the eligible rows, performs the UPDATE, AND writes the per-unban audit rows atomically — matching this canonical shape (the eligibility predicate is preserved verbatim inside the `eligible` CTE):

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

(The eligibility predicate is verbatim from [`docs/05-Implementation.md`](docs/05-Implementation.md) § suspension unban. Previously the handler ran a bare `UPDATE ... RETURNING id`; per [`docs/05-Implementation.md:235`](docs/05-Implementation.md) "Audit log inserted per unban" and now that `admin_actions_log` has shipped (V16/V17), the per-unban audit write ships in this same statement — see the "Worker writes one immutable `admin_actions_log` row per unban" requirement below and `design.md` D4. `RETURNING target_id` surfaces the unbanned user ids that drive `unbanned_count` and the INFO log's `unbanned_user_ids`; snapshotting `suspended_until` in the `eligible` CTE preserves each user's prior expiry for `before_state`.)

Each of the four `WHERE` conjuncts in the `eligible` CTE is required and MUST NOT be relaxed even though the `users_suspended_idx ON users(suspended_until) WHERE suspended_until IS NOT NULL` partial index already implies the second conjunct. An implementer MUST NOT consolidate or omit any of:
- `is_banned = TRUE` — defensive filter that pins the eligibility predicate to the ban semantic; without it, a future schema change repurposing `suspended_until` for a non-ban use (e.g., a temporary throttle) would silently extend the worker's reach. The predicate makes the intent explicit at the SQL level.
- `suspended_until IS NOT NULL` — excludes permanent bans, which MUST never be auto-flipped. Redundant with the partial index but kept in the WHERE for defense-in-depth and so the SQL reads correctly even if the index is ever dropped or reshaped.
- `suspended_until <= NOW()` — the actual elapse trigger.
- `deleted_at IS NULL` — soft-deleted users MUST NOT be unbanned (their account is tombstoned).

#### Scenario: Elapsed 7-day suspension is flipped
- **WHEN** a user has `is_banned = TRUE`, `suspended_until = NOW() - INTERVAL '1 hour'`, AND `deleted_at IS NULL` AND `POST /internal/unban-worker` is invoked with a valid OIDC token
- **THEN** that user's row has `is_banned = FALSE` AND `suspended_until = NULL` after the request completes

#### Scenario: Future-dated suspension is untouched
- **WHEN** a user has `is_banned = TRUE` AND `suspended_until = NOW() + INTERVAL '1 hour'` AND `POST /internal/unban-worker` is invoked
- **THEN** that user's row is unchanged: `is_banned = TRUE` AND `suspended_until` retains the future timestamp

#### Scenario: Permanent ban is untouched
- **WHEN** a user has `is_banned = TRUE` AND `suspended_until IS NULL` AND `POST /internal/unban-worker` is invoked
- **THEN** that user's row is unchanged: `is_banned = TRUE` AND `suspended_until IS NULL`

#### Scenario: Soft-deleted user is untouched
- **WHEN** a user has `is_banned = TRUE`, `suspended_until = NOW() - INTERVAL '1 day'`, AND `deleted_at = NOW() - INTERVAL '7 days'` AND `POST /internal/unban-worker` is invoked
- **THEN** that user's row is unchanged: `is_banned = TRUE` AND `suspended_until` retains the past timestamp AND `deleted_at` is unchanged

#### Scenario: Already-active user is untouched
- **WHEN** a user has `is_banned = FALSE` AND `suspended_until IS NULL` AND `POST /internal/unban-worker` is invoked
- **THEN** that user's row is unchanged

### Requirement: Response shape is `{"unbanned_count": N}` with HTTP 200

On successful execution (the SQL statement completes without error), the endpoint SHALL return HTTP `200 OK` with a JSON body containing exactly one field `unbanned_count` whose value is the integer count of rows actually flipped (the size of the `RETURNING id` result set). The count MUST be reported even when zero — operators rely on the response to confirm the cron fired even on no-op runs.

The response body MUST NOT include the affected user IDs, raw exception messages, stack traces, or any other field. Sanitized error responses (HTTP `500`) use the body shape `{"error": "<classification>"}` with classification drawn from a fixed vocabulary tailored to handler-level failures (distinct from the probe-level vocabulary in `health-check` — handler errors map to JDBC failures, not external-service liveness):
- `"timeout"` — the JDBC query / transaction exceeded its configured budget.
- `"connection_refused"` — the DataSource could not acquire a connection (pool exhaustion, downstream Postgres unreachable).
- `"unknown"` — escape-hatch catch-all; the original exception is logged at WARN with full context for operator debugging but never appears in the response.

The original exception's message and stack trace MUST NOT appear in the response.

#### Scenario: Success with zero eligible rows
- **WHEN** there are no users meeting the eligibility predicate AND `POST /internal/unban-worker` is invoked with a valid OIDC token
- **THEN** the response status is `200 OK` AND the response body parses as JSON with exactly `{"unbanned_count": 0}`

#### Scenario: Success with three eligible rows
- **WHEN** three users each have `is_banned = TRUE`, `suspended_until` in the past, AND `deleted_at IS NULL` AND `POST /internal/unban-worker` is invoked
- **THEN** the response status is `200 OK` AND the response body is `{"unbanned_count": 3}` AND all three rows have `is_banned = FALSE` AND `suspended_until = NULL`

#### Scenario: DB unreachable returns sanitized 500
- **WHEN** the Postgres connection acquire times out AND `POST /internal/unban-worker` is invoked
- **THEN** the response status is `500 Internal Server Error` AND the response body is `{"error": "timeout"}` AND does NOT contain the original exception message or stack trace

### Requirement: Worker emits one structured INFO log event per run

For every successful invocation (HTTP 200 path), the worker SHALL emit exactly one structured INFO log event with the following fields:

- `event` = the literal string `"suspension_unban_applied"`
- `unbanned_count` = the integer count of rows flipped (matches the response field of the same name)
- `unbanned_user_ids` = a JSON array of the affected user UUIDs, capped at the first 50 entries to bound log-line size in pathological data scenarios; when the cap is reached the field MUST also include a sibling field `unbanned_user_ids_truncated = true`
- `duration_ms` = the elapsed wall-clock time of the request handler in milliseconds

The log event MUST NOT include the inbound OIDC bearer token (per the `internal-endpoint-auth` capability's redaction rules), MUST NOT include the request body, and MUST NOT include the JWT claims.

This structured INFO log is retained alongside the per-unban `admin_actions_log` audit row introduced by the `system-actor-and-worker-audit-rows` change (see the "Worker writes one immutable `admin_actions_log` row per unban" requirement). The INFO log is the real-time operational-observability trail (Cloud Logging, ~30-day retention); the audit row is the durable, queryable record (`admin_actions_log`, retained 1 year per `docs/07-Operations.md`). Both are written for every successful run.

#### Scenario: Successful unban emits one structured INFO event
- **WHEN** `POST /internal/unban-worker` is invoked with a valid OIDC token AND exactly one user is flipped
- **THEN** exactly one INFO-level log event is emitted with `event="suspension_unban_applied"`, `unbanned_count=1`, AND `unbanned_user_ids` containing exactly that one user's UUID

#### Scenario: Zero-flip run still emits an INFO event
- **WHEN** there are no eligible users AND the worker is invoked with a valid OIDC token
- **THEN** exactly one INFO-level log event is emitted with `event="suspension_unban_applied"`, `unbanned_count=0`, AND `unbanned_user_ids` is an empty array

#### Scenario: Pathological run truncates user-ID list
- **WHEN** more than 50 users are flipped in one invocation
- **THEN** the INFO event's `unbanned_user_ids` array contains exactly 50 entries AND the event also carries `unbanned_user_ids_truncated = true` AND `unbanned_count` reflects the true total (greater than 50)

#### Scenario: INFO event excludes OIDC token
- **WHEN** the INFO event is emitted
- **THEN** the event payload does NOT contain the inbound `Authorization` header value, the JWT bytes, or any of the JWT claims

### Requirement: Endpoint is idempotent

The `POST /internal/unban-worker` endpoint SHALL be idempotent: invoking it twice in succession with no intervening state change MUST produce identical observable outcomes on the second call (zero rows flipped, `unbanned_count: 0` response). Idempotency arises naturally from the `WHERE` predicate — after the first run, no row satisfies the predicate, so the second run flips nothing.

This guarantee enables Cloud Scheduler retry policies to safely re-invoke the endpoint after transient failures without risking double-flips. Once `admin_actions_log` ships in Phase 3.5 and the audit-row write is wired through this worker (deferred to Phase 3.5 admin work, tracked as a `follow-up` GitHub issue), the same idempotency guarantee will mean no duplicate audit rows are written on retry; until then, the structured INFO log is the trail and a duplicate INFO event with `unbanned_count=0` on retry is the correct, expected shape.

#### Scenario: Two consecutive invocations produce one effective unban
- **WHEN** a user is flipped by a first `POST /internal/unban-worker` AND a second invocation runs immediately after
- **THEN** the first invocation returns `{"unbanned_count": 1}` AND the second returns `{"unbanned_count": 0}` AND exactly one INFO event with `event="suspension_unban_applied"` AND `unbanned_count=1` containing that user's UUID was emitted by the first call AND a second INFO event with `unbanned_count=0` was emitted by the second call

#### Scenario: Retry after network blip is safe
- **WHEN** Cloud Scheduler invokes the endpoint, receives the response, but the response is lost in transit AND Cloud Scheduler retries the invocation
- **THEN** the retry's UPDATE flips zero additional rows AND the user row is NOT modified again AND the response body is `{"unbanned_count": 0}`

### Requirement: Schedule is daily at 04:00 WIB

The endpoint SHALL be invoked exactly once per day at `04:00` Asia/Jakarta time (WIB, UTC+7) via Cloud Scheduler. The cron schedule expressed in UTC is `0 21 * * *` (the prior calendar day in UTC). Cloud Scheduler job configuration MUST be reproducible per environment (one job per environment: `nearyou-unban-worker-staging`, `nearyou-unban-worker-prod`) — initial setup is via the `gcloud` commands documented in `tasks.md` § 11; long-term, this codebase intends to move infrastructure-state to declarative configuration as the project grows, but that's out of scope for this change.

The Cloud Scheduler retry policy MUST permit at least 3 attempts with exponential backoff (min 30s, max 5min) — the endpoint's idempotency guarantee makes retries safe.

#### Scenario: Cron expression is correct in UTC
- **WHEN** the Cloud Scheduler job is configured
- **THEN** its cron schedule is `0 21 * * *` in UTC, equivalent to `0 4 * * *` in Asia/Jakarta time

#### Scenario: Job exists per environment
- **WHEN** the staging Cloud Scheduler jobs are inspected via `gcloud scheduler jobs list --project=nearyou-staging`
- **THEN** exactly one job named `nearyou-unban-worker-staging` exists that targets the deployed service URL `${SERVICE_URL}/internal/unban-worker` with method `POST` and an OIDC token bound to a dedicated service account (and the analogous job exists in production once the production Cloud Run + Scheduler deployment is wired in a subsequent change)

### Requirement: No notification on unban

The worker SHALL NOT insert any `notifications` row when a user is unbanned. The user's next sign-in attempt or post action succeeds, which is the natural in-band signal of restored access. This decision is documented in `design.md` D5 — adopting `account_action_applied` would cause user-facing copy mismatch ("Akun kamu menerima tindakan moderasi" does not fit a positive restoration), and adding a new `account_action_lifted` type is out of scope.

If post-launch user research surfaces confusion about restored access, a follow-up change can introduce `account_action_lifted` and amend this requirement.

#### Scenario: No notification row on unban
- **WHEN** a user is flipped by `POST /internal/unban-worker`
- **THEN** zero new rows are inserted into the `notifications` table referencing that user

### Requirement: Worker performance is bounded

The worker SHALL leverage the existing `users_suspended_idx ON users(suspended_until) WHERE suspended_until IS NOT NULL` partial index ([`docs/05-Implementation.md:242`](docs/05-Implementation.md)) so that the eligibility scan is index-bound, not a full table scan. The endpoint's worst-case latency at expected suspension volumes (≤100 unbans/day) MUST complete within Cloud Scheduler's per-invocation timeout (30 minutes). At the operational steady state, end-to-end response time is expected to be well under 1 second.

#### Scenario: Index scan, not seq scan
- **WHEN** the worker's UPDATE query is profiled with `EXPLAIN`
- **THEN** the query plan shows an index scan against `users_suspended_idx`, not a sequential scan of the `users` table

### Requirement: Worker writes one immutable `admin_actions_log` row per unban in the same transaction

For every user flipped by `POST /internal/unban-worker`, the worker SHALL INSERT exactly one `admin_actions_log` row, in the SAME database transaction as the `UPDATE` (via the data-modifying CTE above), so the unban and its audit record commit or roll back together. If the audit INSERT fails, the user UPDATE MUST be rolled back (the user remains banned) and the endpoint returns the sanitized `500` path; Cloud Scheduler's retry re-runs the idempotent endpoint.

Each audit row SHALL set:
- `admin_id` = the `system` sentinel UUID `54b53072-540e-3eb8-b8e9-343e71f28176` (the canonical machine-action attribution actor — see the `system-actor` capability)
- `action_type` = `'system_unban_applied'`
- `target_type` = `'user'`
- `target_id` = the unbanned user's id rendered as text
- `reason` = `'suspension_elapsed'`
- `before_state` = `{"is_banned": true, "suspended_until": <the user's prior suspended_until>}` — produced by `jsonb_build_object` from the `timestamptz`, so it renders in Postgres's native JSON timestamptz form (offset + microseconds), NOT Java `Instant.toString()`; tests MUST assert it by `timestamptz` value-equality (`(before_state->>'suspended_until')::timestamptz = <prior expiry>`), not string match
- `after_state` = `{"is_banned": false, "suspended_until": null}`
- `ip` = NULL AND `user_agent` = NULL (Cloud Scheduler invocation carries no client request context)

The worker MUST NOT write an audit row for any user it did not flip. Audit rows are NOT subject to the INFO log's 50-entry `unbanned_user_ids` cap — exactly one `admin_actions_log` row is written per flipped user regardless of batch size. The worker writes via the main application DB role (which retains INSERT on `admin_actions_log`); the role-level immutability REVOKE (no UPDATE/DELETE) targets the `admin_app` role and is out of scope for this change.

#### Scenario: One unban writes exactly one matching audit row
- **WHEN** exactly one user with `suspended_until = NOW() - INTERVAL '1 hour'` is flipped by `POST /internal/unban-worker`
- **THEN** exactly one new `admin_actions_log` row exists with `admin_id = '54b53072-540e-3eb8-b8e9-343e71f28176'`, `action_type = 'system_unban_applied'`, `target_type = 'user'`, `target_id` = that user's id, `reason = 'suspension_elapsed'`, `before_state->>'is_banned' = 'true'`, `before_state->>'suspended_until'` = the user's prior expiry, AND `after_state = {"is_banned": false, "suspended_until": null}`

#### Scenario: Audit INSERT failure rolls back the unban (atomicity)
- **WHEN** the `UPDATE` would flip a user but the `admin_actions_log` INSERT fails within the same transaction (e.g., a constraint violation injected in test)
- **THEN** the transaction is rolled back: the user's row still has `is_banned = TRUE` AND its original `suspended_until` AND no `admin_actions_log` row was written for that user

#### Scenario: Zero-flip run writes zero audit rows
- **WHEN** no user is eligible AND `POST /internal/unban-worker` is invoked
- **THEN** zero new `admin_actions_log` rows are written AND the response is `{"unbanned_count": 0}`

#### Scenario: Idempotent retry writes no duplicate audit row
- **WHEN** a user is flipped by a first invocation AND a second invocation runs immediately after
- **THEN** the first invocation writes exactly one `admin_actions_log` row for that user AND the second invocation flips zero rows AND writes zero additional `admin_actions_log` rows

#### Scenario: Three unbans write three audit rows
- **WHEN** three eligible users are flipped in one invocation
- **THEN** exactly three new `admin_actions_log` rows exist, one per user, each with `action_type = 'system_unban_applied'` AND a distinct `target_id` matching one of the three flipped users

#### Scenario: Audit rows are not capped at the INFO log's 50-entry limit
- **WHEN** more than 50 users are flipped in one invocation
- **THEN** exactly one `admin_actions_log` row is written per flipped user — the audit-row count equals `unbanned_count` (greater than 50), NOT capped at 50 — even though the INFO log's `unbanned_user_ids` array is capped at 50 entries

