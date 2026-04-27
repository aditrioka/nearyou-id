## ADDED Requirements

### Requirement: `POST /internal/unban-worker` flips elapsed time-bound suspensions

The Ktor backend SHALL expose `POST /internal/unban-worker` as a Cloud-Scheduler-invoked endpoint that flips `is_banned = FALSE` and nulls `suspended_until` for users whose time-bound suspension window has elapsed. The endpoint MUST be mounted under `/internal/*` and is gated by the `internal-endpoint-auth` capability — every request requires a valid Google OIDC bearer token whose audience matches the configured `internal-oidc-audience`.

The handler SHALL execute exactly one SQL statement, in a single transaction, matching this canonical predicate verbatim:

```sql
UPDATE users
SET is_banned = FALSE,
    suspended_until = NULL
WHERE is_banned = TRUE
  AND suspended_until IS NOT NULL
  AND suspended_until <= NOW()
  AND deleted_at IS NULL
RETURNING id;
```

(Verbatim from [`docs/05-Implementation.md:353-361`](docs/05-Implementation.md), with `RETURNING id` added so the worker can write per-user audit log rows.)

Each of the four `WHERE` conjuncts is required and MUST NOT be relaxed:
- `is_banned = TRUE` — defensive filter; without it, a future schema change reusing `suspended_until` for non-ban semantics could cause unintended writes.
- `suspended_until IS NOT NULL` — excludes permanent bans, which MUST never be auto-flipped.
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

The response body MUST NOT include the affected user IDs, raw exception messages, stack traces, or any other field. Sanitized error responses (HTTP `500`) follow the same fixed-vocabulary pattern as [`health-check`](openspec/specs/health-check/spec.md): body is `{"error": "<classification>"}` with classification ∈ `"timeout"`, `"connection_refused"`, `"unknown"`.

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

This logging is the operational trail until [`docs/05-Implementation.md:363`](docs/05-Implementation.md)'s prescribed `admin_actions_log` audit table ships in Phase 3.5. A follow-up change after Phase 3.5 will add audit-row writes to this worker; until then, Cloud Logging is the canonical record.

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

The `POST /internal/unban-worker` endpoint SHALL be idempotent: invoking it twice in succession with no intervening state change MUST produce identical observable outcomes on the second call (zero rows flipped, zero audit rows written, `unbanned_count: 0` response). Idempotency arises naturally from the `WHERE` predicate — after the first run, no row satisfies the predicate, so the second run flips nothing.

This guarantee enables Cloud Scheduler retry policies to safely re-invoke the endpoint after transient failures without risking duplicate audit rows.

#### Scenario: Two consecutive invocations produce one effective unban
- **WHEN** a user is flipped by a first `POST /internal/unban-worker` AND a second invocation runs immediately after
- **THEN** the first invocation returns `{"unbanned_count": 1}` AND the second returns `{"unbanned_count": 0}` AND exactly one audit row exists for that user

#### Scenario: Retry after network blip is safe
- **WHEN** Cloud Scheduler invokes the endpoint, receives the response, but the response is lost in transit AND Cloud Scheduler retries the invocation
- **THEN** the retry's UPDATE flips zero additional rows AND no duplicate audit rows are written

### Requirement: Schedule is daily at 04:00 WIB

The endpoint SHALL be invoked exactly once per day at `04:00` Asia/Jakarta time (WIB, UTC+7) via Cloud Scheduler. The cron schedule expressed in UTC is `0 21 * * *` (the prior calendar day in UTC). Cloud Scheduler job configuration MUST be tracked in version-controlled deploy artifacts (one job per environment: `nearyou-unban-worker-staging`, `nearyou-unban-worker-prod`) so staging and production remain reproducible.

The Cloud Scheduler retry policy MUST permit at least 3 attempts with exponential backoff (min 30s, max 5min) — the endpoint's idempotency guarantee makes retries safe.

#### Scenario: Cron expression is correct in UTC
- **WHEN** the Cloud Scheduler job is configured
- **THEN** its cron schedule is `0 21 * * *` in UTC, equivalent to `0 4 * * *` in Asia/Jakarta time

#### Scenario: Job exists per environment
- **WHEN** the staging deploy artifacts and production deploy artifacts are inspected
- **THEN** each environment has exactly one Cloud Scheduler job named `nearyou-unban-worker-<env>` that targets the deployed service URL `${SERVICE_URL}/internal/unban-worker` with method `POST` and an OIDC token bound to a dedicated service account

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
