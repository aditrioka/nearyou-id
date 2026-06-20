## ADDED Requirements

### Requirement: data_export_requests schema

Migration `V29__data_export_requests.sql` SHALL create the `data_export_requests` table and its three indexes (the migration number MAY be renumbered above V28 at rebase if a concurrent change lands first):

```sql
CREATE TABLE data_export_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending','processing','ready','expired','failed')),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    r2_object_key TEXT,
    download_expires_at TIMESTAMPTZ,
    attempt_count INT NOT NULL DEFAULT 0,
    error TEXT
);

CREATE INDEX data_export_requests_pending_idx ON data_export_requests(requested_at)
    WHERE status = 'pending';

CREATE UNIQUE INDEX data_export_requests_one_active_idx ON data_export_requests(user_id)
    WHERE status IN ('pending','processing');

CREATE INDEX data_export_requests_user_recent_idx ON data_export_requests(user_id, requested_at DESC);
```

Both partial-index `WHERE` clauses MUST be `NOW()`-free (the partial-index lint invariant). This change adds **no** migration to the `notifications` table — the `data_export_ready` type already ships in the V10 catalog.

#### Scenario: Table and indexes exist after migration
- **WHEN** the migration set is applied and `data_export_requests` is inspected via `information_schema`
- **THEN** the table exists with columns `id, user_id, status, requested_at, started_at, completed_at, r2_object_key, download_expires_at, attempt_count, error` AND the three indexes `data_export_requests_pending_idx`, `data_export_requests_one_active_idx`, `data_export_requests_user_recent_idx` exist

#### Scenario: status CHECK rejects an unknown value
- **WHEN** an `INSERT INTO data_export_requests (user_id, status) VALUES (..., 'queued')` is attempted
- **THEN** the database rejects it with a CHECK-constraint violation

#### Scenario: one-active partial unique index forbids a second active row per user
- **WHEN** a user already has a row with `status = 'pending'` and a second `INSERT` with `status = 'pending'` is attempted for the same user
- **THEN** the database rejects the second insert with a unique-violation on `data_export_requests_one_active_idx`

#### Scenario: a non-active row does not block a new request
- **WHEN** a user's only prior row has `status = 'ready'` (or `'expired'`/`'failed'`) and a new `status = 'pending'` row is inserted for that user
- **THEN** the insert succeeds (the partial unique index covers only `pending`/`processing`)

#### Scenario: Partial-index predicates are NOW()-free
- **WHEN** the `CREATE INDEX` statements for `data_export_requests_pending_idx` and `data_export_requests_one_active_idx` are read
- **THEN** neither `WHERE` clause contains `NOW()` (only `status`-predicate expressions), so Postgres accepts them as immutable partial indexes

### Requirement: User can request a personal-data export (idempotent, one active per user)

An authenticated endpoint `POST /api/v1/account/export` (Bearer JWT) SHALL enqueue a data export by inserting a `data_export_requests` row for the calling user with `status = 'pending'`, responding `202` with the request id + status. The operation MUST be **idempotent / single-active**: when the caller already has a row in `status IN ('pending','processing')`, the endpoint MUST NOT create a second row and MUST return the existing request (its id + status), relying on the `data_export_requests_one_active_idx` unique index as the structural guard (a unique-violation is caught and mapped to "return existing"). A caller whose prior requests are all `ready`/`expired`/`failed` MAY create a new request. An unauthenticated request MUST return `401`. Export is keyed strictly on the authenticated `user_id` — a caller can only ever enqueue an export of **their own** data.

#### Scenario: First request enqueues a pending export
- **WHEN** an authenticated user with no active request calls `POST /api/v1/account/export`
- **THEN** the response is `202` with the new request id and `status = 'pending'` AND exactly one `data_export_requests` row exists for that user in an active status

#### Scenario: Re-request while one is active returns the existing request
- **WHEN** a user who already has a `pending` (or `processing`) request calls the endpoint again
- **THEN** no second row is inserted AND the response returns the existing request's id and status

#### Scenario: Re-request after delivery creates a fresh export
- **WHEN** a user whose only prior request is `ready` (or `expired`/`failed`) calls `POST /api/v1/account/export`
- **THEN** a NEW `pending` row is inserted and returned

#### Scenario: Unauthenticated request rejected
- **WHEN** a caller without a valid Bearer JWT calls `POST /api/v1/account/export`
- **THEN** the response is `401` and no row is written

### Requirement: Export status is readable so the client can show progress and the download deadline

An authenticated read `GET /api/v1/account/export` SHALL report the caller's latest export request: its `status` and, when `status = 'ready'`, the `download_expires_at` deadline (and a way to obtain the current signed download URL). When the caller has never requested an export it MUST report a no-export state. It MUST NOT leak any other user's export state or expose another user's `r2_object_key` / signed URL.

#### Scenario: Status reflects a pending export
- **WHEN** a user with a `pending`/`processing` request reads the export status
- **THEN** the response indicates an in-progress export with the matching status

#### Scenario: Status reflects a ready export with its deadline
- **WHEN** a user whose latest request is `ready` reads the export status
- **THEN** the response indicates `ready` with the matching `download_expires_at`

#### Scenario: Status when nothing was requested
- **WHEN** a user who never requested an export reads the status
- **THEN** the response indicates no export

#### Scenario: No cross-user leak
- **WHEN** user A reads the export status
- **THEN** the response reflects only A's request and never another user's status, object key, or URL

### Requirement: The export worker packages and delivers the export

An OIDC-authed internal endpoint `POST /internal/data-export-run` (verified per `internal-endpoint-auth`, invoked by Cloud Scheduler) SHALL process pending exports. For each claimed request the worker SHALL: optimistically claim the row (`UPDATE … SET status='processing', started_at=NOW() WHERE id=? AND status='pending'`, proceeding only if a row was affected — so concurrent invocations never double-process); gather the caller's data per the scope matrix; serialize it to a single archive (ZIP of JSON + CSV files per the scope-matrix Format column + a manifest + a Bahasa-Indonesia README); upload the archive to object storage; obtain a signed GET URL with a 24-hour TTL; set `status='ready'`, `r2_object_key`, `completed_at=NOW()`, `download_expires_at=NOW()+INTERVAL '24 hours'`; emit a `data_export_ready` notification; and send the Resend delivery email. A request that cannot be processed (object storage or email unconfigured/erroring after retries) MUST be set to `status='failed'` with a non-PII `error`, never crashing the worker. An invalid/absent OIDC token MUST be rejected per `internal-endpoint-auth` (no processing).

#### Scenario: Worker rejects an unauthenticated invocation
- **WHEN** `POST /internal/data-export-run` is called without a valid Google-OIDC bearer token
- **THEN** the request is rejected (`401`/`403`) and no export is processed

#### Scenario: Pending export is processed end-to-end
- **WHEN** the worker runs with one `pending` request whose object storage + email are configured
- **THEN** the request ends `status='ready'` with a non-null `r2_object_key` and `download_expires_at ≈ NOW()+24h`, a `data_export_ready` notification row exists for the user, and one delivery email was sent

#### Scenario: Concurrent invocations do not double-process
- **WHEN** two worker invocations run against the same single `pending` request
- **THEN** exactly one claims it (transitions it to `processing`) and produces exactly one archive/notification/email; the other claims nothing for that row

#### Scenario: Fail-soft when delivery substrate is unconfigured
- **WHEN** the worker processes a request while object storage (or email) is unconfigured/erroring after retries
- **THEN** the request is set to `status='failed'` with a non-PII `error` AND the worker does not crash AND no partial/broken `ready` state is left

### Requirement: Export scope matrix matches the canonical Data Export Scope Matrix

The export archive — a ZIP of **JSON + CSV** files per the canonical Format column — SHALL include exactly the categories marked **Included** in `docs/06-Security-Privacy.md` § Data Export Scope Matrix (the canonical authority for this contract) and SHALL exclude those marked out of scope.

**Included** (the requester's own data): user profile (name/bio/username, current state — JSON); username change history (own — CSV: old + new + changed_at); date of birth (JSON); hashed Google/Apple ID (JSON, self-reference); analytics-consent history (JSON); posts active (CSV — incl. the user's own `actual_location`, `city_name`, timestamp); posts soft-deleted in grace (CSV, marked `deleted_at`); post edit history (own — CSV, all versions); likes given (CSV); replies given (CSV); follow list (CSV, peer id hashed); block list (CSV, blocked id hashed); chat messages **sent and received** (CSV: conversation_id + content + timestamp + **peer_id hashed**); reports submitted by the user (CSV: target hashed + reason + timestamp); notifications received (CSV: type + target + timestamp + read state); moderation actions applied to the user (CSV: action_type + timestamp, admin_id omitted); session history (CSV: fingerprint, IP — 90-day window only); premium subscription history (CSV: tier + start/end + source paid|referral).

**Excluded** (MUST NOT appear): reports received about the user (affects third parties), attestation verdicts, admin audit log about the user, CSAM detection archive, `rejected_identifiers` hash, and — overriding the "moderation actions applied" inclusion — any **shadow-ban** status or shadow-ban moderation entry (the shadow-ban stealth invariant wins: the export MUST NOT reveal that the user is shadow-banned). Every included peer reference is a one-way hash, so no other user's identity is exposed.

Scope-matrix reads are own-content raw reads — they intentionally read the user's real data, including the user's own `actual_location` (NOT the HMAC-fuzzed `display_location`) and the user's own block list — so the repository sites carry the own-content allowlist annotations (`@AllowRawPostsRead` / `@AllowMissingBlockJoin`, plus the sanctioned own-`actual_location` read).

#### Scenario: Archive contains the canonical Included categories
- **WHEN** a user's export archive is produced
- **THEN** it contains files for profile, username-change history, DOB, hashed Google/Apple ID, analytics-consent history, posts (active + soft-deleted-in-grace), post edit history, likes given, replies given, follow list, block list, chat messages, reports submitted, notifications received, moderation actions applied, session history, and subscription history

#### Scenario: Chat export includes sent and received with the peer hashed
- **WHEN** a user who participated in a conversation requests an export
- **THEN** the chat file contains the user's sent AND received messages for that conversation with `conversation_id`, content, timestamp, and the peer's id as a one-way **hash** (never the peer's raw id or username)

#### Scenario: Posts export the user's own actual location
- **WHEN** the posts file in a user's archive is inspected
- **THEN** it carries the user's own `actual_location` + `city_name` (own-data; NOT the fuzzed `display_location`)

#### Scenario: Out-of-scope categories are excluded
- **WHEN** a user's archive is inspected
- **THEN** it contains no reports-received-about-the-user, no attestation verdicts, no admin audit log, no CSAM archive, and no `rejected_identifiers` hash

#### Scenario: Shadow-ban stealth is preserved
- **WHEN** a shadow-banned user requests and receives an export
- **THEN** neither the moderation-actions file nor any other file reveals the shadow-ban (the stealth invariant overrides the "moderation actions applied" inclusion)

#### Scenario: No other user's personal data leaks into the archive
- **WHEN** user A's export is produced while user B also has data
- **THEN** no raw identifier or content of B appears (peer references are hashed; only A's own messages within A's own conversations are present)

### Requirement: Delivery reuses the shipped data_export_ready notification (no notifications migration)

The worker SHALL emit the export-ready notification via the existing `data_export_ready` type (V10 catalog) with `body_data = {signed_url, expires_at}` and NULL `target_type`/`target_id` — it MUST NOT introduce a new notification type and MUST NOT duplicate any id inside `body_data` (per the docs/05 §Notifications canonical rule). This change adds no migration to the `notifications` CHECK.

#### Scenario: Notification row has the canonical shape
- **WHEN** an export becomes `ready`
- **THEN** a `notifications` row is written with `type = 'data_export_ready'`, `body_data` carrying `signed_url` and `expires_at`, and NULL `target_type`/`target_id`

#### Scenario: No notifications schema change
- **WHEN** the change's migrations are reviewed
- **THEN** none alters the `notifications` `type` CHECK (the `data_export_ready` value already exists)

### Requirement: The download link is time-limited and the request expires

The signed download URL SHALL be valid for 24 hours; `download_expires_at` records the deadline. Once `download_expires_at` has passed, the export's effective status SHALL be reported as `expired` and the status read MUST NOT hand out a fresh long-lived URL for that request — the user must request a new export. The export object SHALL be removed after the download window (an object-storage lifecycle rule on the export bucket), so an expired/leaked URL resolves to nothing.

#### Scenario: Ready export carries a 24h deadline
- **WHEN** an export transitions to `ready`
- **THEN** `download_expires_at ≈ NOW() + 24 hours`

#### Scenario: Past-deadline export reads as expired
- **WHEN** a user reads the status of a `ready` request whose `download_expires_at` is in the past
- **THEN** the response reports `expired` and does not return a fresh durable download URL

### Requirement: Admin Data Export Queue surface is deferred (out of scope)

This change is the user-facing export **producer** only. It SHALL NOT add any `/admin/*` route, admin view, or admin-triggered export path; the admin **Data Export Queue** monitoring/trigger surface (docs/07 § Core Features) is a separate admin-lane change that will build on the `data_export_requests` table this change creates. A `follow-up` issue tracks the deferred admin surface.

#### Scenario: No admin route added here
- **WHEN** the routes introduced by this change are enumerated
- **THEN** none is mounted under `/admin/*` (the only new routes are `POST`/`GET /api/v1/account/export` and `POST /internal/data-export-run`)

#### Scenario: Deferred admin surface is tracked
- **WHEN** the change is delivered
- **THEN** a `follow-up` issue exists describing the admin Data Export Queue surface to be built on `data_export_requests`

### Requirement: Mobile Settings entry is deferred (out of scope)

This change ships the backend endpoints the mobile "Unduh Data Saya" Settings entry will call; it SHALL NOT add Compose/mobile UI. The mobile Settings row + confirm dialog + status banner are a deferred mobile-lane follow-up. A `follow-up` issue tracks it.

#### Scenario: No mobile UI added here
- **WHEN** the change's touched modules are enumerated
- **THEN** `:mobile:app` is not modified (the change is backend + `:infra:*` only)

#### Scenario: Backend is ready for the future mobile entry
- **WHEN** the deferred mobile entry is later built
- **THEN** it can drive the export entirely through the shipped `POST`/`GET /api/v1/account/export` endpoints (no further backend change required for the happy path) AND a `follow-up` issue tracks the mobile entry
