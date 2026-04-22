# reports Specification

## Purpose
TBD - created by archiving change reports-v9. Update Purpose after archive.
## Requirements
### Requirement: reports table created via Flyway V9

A migration `V9__reports_moderation.sql` SHALL create the `reports` table verbatim-aligned with `docs/05-Implementation.md` §745–775 with columns:
- `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`
- `reporter_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE`
- `target_type VARCHAR(16) NOT NULL CHECK (target_type IN ('post', 'reply', 'user', 'chat_message'))`
- `target_id UUID NOT NULL`
- `reason_category VARCHAR(32) NOT NULL CHECK (reason_category IN ('spam', 'hate_speech_sara', 'harassment', 'adult_content', 'misinformation', 'self_harm', 'csam_suspected', 'other'))`
- `reason_note VARCHAR(200)` (nullable)
- `status VARCHAR(16) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'actioned', 'dismissed'))`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- `reviewed_at TIMESTAMPTZ` (nullable)
- `reviewed_by UUID` (nullable; **NO FK** — deferred to the Phase 3.5 admin-users migration)
- `UNIQUE (reporter_id, target_type, target_id)`

Plus three indexes:
- `reports_status_idx ON reports(status, created_at DESC)`
- `reports_target_idx ON reports(target_type, target_id)`
- `reports_reporter_idx ON reports(reporter_id, created_at DESC)`

The `reviewed_by` column MUST carry a `COMMENT ON COLUMN` recording its deferred FK target (`admin_users(id) ON DELETE SET NULL`). The Phase 3.5 admin-users migration will add the constraint via `ALTER TABLE ... ADD CONSTRAINT ... NOT VALID` + `VALIDATE CONSTRAINT`.

#### Scenario: Migration runs cleanly from V8
- **WHEN** Flyway runs `V9__reports_moderation.sql` against a DB at V8
- **THEN** the migration succeeds AND `flyway_schema_history` records V9

#### Scenario: All canonical columns present
- **WHEN** querying `information_schema.columns WHERE table_name = 'reports'`
- **THEN** every column above is present with its documented type, nullability, and default

#### Scenario: UNIQUE constraint on (reporter_id, target_type, target_id)
- **WHEN** two INSERTs attempt the same `(reporter_id, target_type, target_id)` tuple
- **THEN** the second INSERT fails with SQLSTATE `23505` (unique-violation)

#### Scenario: target_type CHECK rejects out-of-enum value
- **WHEN** an INSERT supplies `target_type = 'meme'`
- **THEN** the INSERT fails with a check-constraint violation

#### Scenario: reason_category CHECK rejects out-of-enum value
- **WHEN** an INSERT supplies `reason_category = 'political_disagreement'`
- **THEN** the INSERT fails with a check-constraint violation

#### Scenario: status CHECK rejects out-of-enum value
- **WHEN** an INSERT supplies `status = 'escalated'`
- **THEN** the INSERT fails with a check-constraint violation

#### Scenario: All three indexes exist
- **WHEN** querying `pg_indexes WHERE tablename = 'reports'`
- **THEN** the result contains `reports_status_idx`, `reports_target_idx`, AND `reports_reporter_idx`

#### Scenario: reporter_id CASCADE removes reports on user delete
- **WHEN** a `users` row is hard-deleted (e.g. via the tombstone worker when no other RESTRICT FKs block)
- **THEN** every `reports` row referencing that user as `reporter_id` is cascade-deleted in the same transaction

#### Scenario: reviewed_by has no FK constraint in V9
- **WHEN** querying `information_schema.referential_constraints` for the `reports` table
- **THEN** no row is returned for `reviewed_by` (the column exists as a plain UUID)

#### Scenario: reviewed_by column carries deferred-FK documentation
- **WHEN** querying `pg_description` for the `reviewed_by` column of `reports`
- **THEN** the comment mentions `admin_users(id)` AND the phrase `deferred`

### Requirement: POST /api/v1/reports endpoint exists

A Ktor route SHALL be registered at `POST /api/v1/reports`. The route MUST require Bearer JWT authentication via the existing `auth-jwt` plugin; an unauthenticated request MUST receive HTTP 401 with error code `unauthenticated`. The route handler MUST live under `backend/ktor/src/main/kotlin/id/nearyou/app/moderation/`.

#### Scenario: Unauthenticated rejected
- **WHEN** `POST /api/v1/reports` is called with no `Authorization` header
- **THEN** the response is HTTP 401 with `error.code = "unauthenticated"`

#### Scenario: Authenticated routed to handler
- **WHEN** the same request is made with a valid Bearer JWT
- **THEN** the request reaches the handler (HTTP status is not 401)

### Requirement: Request body shape + enum validation

The request body SHALL be JSON of shape `{ "target_type": string, "target_id": string, "reason_category": string, "reason_note"?: string }`. The handler MUST validate:
- `target_type` is one of `"post"`, `"reply"`, `"user"`, `"chat_message"`.
- `target_id` is a valid UUID string.
- `reason_category` is one of `"spam"`, `"hate_speech_sara"`, `"harassment"`, `"adult_content"`, `"misinformation"`, `"self_harm"`, `"csam_suspected"`, `"other"`.
- `reason_note` is either absent, null, or a string of ≤ 200 Unicode code points after NFKC normalization.

Validation failures MUST yield HTTP 400 with error code `invalid_request`. All validation MUST run before any DB work.

#### Scenario: Missing target_type rejected
- **WHEN** the body is `{ "target_id": "<uuid>", "reason_category": "spam" }`
- **THEN** the response is HTTP 400 with `error.code = "invalid_request"`

#### Scenario: Out-of-enum target_type rejected
- **WHEN** the body has `target_type = "dm"`
- **THEN** the response is HTTP 400 with `error.code = "invalid_request"` AND no DB INSERT executes

#### Scenario: Non-UUID target_id rejected
- **WHEN** the body has `target_id = "not-a-uuid"`
- **THEN** the response is HTTP 400 with `error.code = "invalid_request"` AND no DB INSERT executes

#### Scenario: Out-of-enum reason_category rejected
- **WHEN** the body has `reason_category = "political_disagreement"`
- **THEN** the response is HTTP 400 with `error.code = "invalid_request"`

#### Scenario: 201-character reason_note rejected
- **WHEN** the body has a `reason_note` of 201 code points
- **THEN** the response is HTTP 400 with `error.code = "invalid_request"` AND no DB INSERT executes

#### Scenario: 200-character reason_note accepted
- **WHEN** the body has a `reason_note` of exactly 200 code points AND all other fields valid
- **THEN** the response is HTTP 204 (after target resolution + INSERT)

### Requirement: Self-report rejected

The endpoint SHALL reject a request where `target_type = "user"` AND `target_id` equals the authenticated caller's `users.id` with HTTP 400 and error code `self_report_rejected`. No `reports` row MUST be written.

#### Scenario: User reports self
- **WHEN** a caller with `users.id = X` posts `{ "target_type": "user", "target_id": "X", "reason_category": "other" }`
- **THEN** the response is HTTP 400 with `error.code = "self_report_rejected"` AND no `reports` row is inserted

#### Scenario: User reports own post does NOT trigger self-report rejection
- **WHEN** a caller with `users.id = X` reports a post they authored (`target_type = "post"`)
- **THEN** the self-report check does NOT fire (the target_type is not `"user"`); normal existence + INSERT proceeds

### Requirement: Target existence check WITHOUT block-exclusion filtering

The handler SHALL resolve the target by id against the appropriate table, with ONLY a soft-delete guard where applicable:
- `target_type = "post"` → `SELECT 1 FROM posts WHERE id = ? AND deleted_at IS NULL`
- `target_type = "reply"` → `SELECT 1 FROM post_replies WHERE id = ? AND deleted_at IS NULL`
- `target_type = "user"` → `SELECT 1 FROM users WHERE id = ? AND deleted_at IS NULL`
- `target_type = "chat_message"` → `SELECT 1 FROM chat_messages WHERE id = ?` (no `deleted_at` column on chat_messages)

The existence check MUST NOT go through `visible_posts`, MUST NOT JOIN `visible_users`, and MUST NOT apply any bidirectional `user_blocks` NOT-IN filter. A user reporting content from a user they blocked (or who blocked them), or reporting a shadow-banned user's content, or reporting an already-auto-hidden post, are all legitimate behaviors that the endpoint MUST accept.

On missing or soft-deleted target, the response MUST be HTTP 404 with error code `target_not_found`. No `reports` row MUST be written.

#### Scenario: Target UUID not in any table returns 404
- **WHEN** the body references a random UUID with `target_type = "post"`
- **THEN** the response is HTTP 404 with `error.code = "target_not_found"` AND no `reports` row is inserted

#### Scenario: Soft-deleted post returns 404
- **WHEN** the referenced post has `deleted_at IS NOT NULL`
- **THEN** the response is HTTP 404 with `error.code = "target_not_found"`

#### Scenario: Blocked-by-target still allowed to report
- **WHEN** the reporter is blocked by the post's author (reporter's id in `user_blocks.blocked_id WHERE blocker_id = post.author`)
- **THEN** the target existence check succeeds AND the `reports` row is inserted AND the response is HTTP 204

#### Scenario: Blocker reports blocked user's post
- **WHEN** the reporter has blocked the post's author (reporter's id in `user_blocks.blocker_id WHERE blocked_id = post.author`)
- **THEN** the target existence check succeeds AND the `reports` row is inserted AND the response is HTTP 204

#### Scenario: Reporting an already-auto-hidden post
- **WHEN** the referenced post has `is_auto_hidden = TRUE`
- **THEN** the target existence check succeeds AND the `reports` row is inserted AND the response is HTTP 204

#### Scenario: Reporting a shadow-banned user's reply
- **WHEN** the referenced reply's author has `is_shadow_banned = TRUE`
- **THEN** the target existence check succeeds AND the `reports` row is inserted AND the response is HTTP 204

### Requirement: Duplicate report returns 409

If the UNIQUE `(reporter_id, target_type, target_id)` constraint fires on INSERT, the endpoint MUST return HTTP 409 with error code `duplicate_report`. No second `reports` row MUST be written. The auto-hide check MUST NOT run on duplicate (the original report already counted).

#### Scenario: Same reporter reports same target twice
- **WHEN** reporter R has already reported target (type=T, id=I) AND R posts the same report again
- **THEN** the response is HTTP 409 with `error.code = "duplicate_report"` AND the `reports` table contains exactly ONE row for `(R, T, I)`

### Requirement: Rate limit 10 submissions per hour per user

The endpoint SHALL enforce a rate limit of 10 submissions/hour/user via a Redis-backed counter keyed `{scope:rate_report}:{user:<user_id>}` (hash-tag format per `docs/00-README.md § CI Lint Rules — Redis keys`). The 11th submission within a 1-hour sliding window MUST return HTTP 429 with error code `rate_limited` and a `Retry-After` header set to the seconds until the oldest counted submission ages out. The rate limit check MUST run BEFORE any DB work (target existence, INSERT, auto-hide). Duplicate requests that hit 409 MUST NOT consume a rate-limit slot.

#### Scenario: 10 submissions within an hour succeed
- **WHEN** a user submits 10 distinct valid reports within a 60-minute window
- **THEN** all 10 return HTTP 204

#### Scenario: 11th submission rate-limited
- **WHEN** a user has 10 accepted reports in the last hour AND submits an 11th valid report
- **THEN** the response is HTTP 429 with `error.code = "rate_limited"` AND the response carries a `Retry-After` header with a positive integer value AND no `reports` row is inserted

#### Scenario: Retry-After reflects oldest counted submission
- **WHEN** the 11th request is rejected
- **THEN** the `Retry-After` value is approximately the number of seconds until the oldest counted submission's timestamp is more than 1 hour in the past

#### Scenario: 409 duplicate does not consume a rate-limit slot
- **WHEN** a user has 9 accepted reports and submits a 10th report that collides with a prior report (UNIQUE violation → 409)
- **THEN** the response is HTTP 409 AND a subsequent valid distinct report still succeeds (not 429)

#### Scenario: Redis key uses hash-tag format
- **WHEN** the rate-limit check runs against Redis
- **THEN** the key used has the form `{scope:rate_report}:{user:<uuid>}`

### Requirement: Successful submission returns 204 with no body

On success (enum-valid body + target exists + not a self-report + not a duplicate + not rate-limited + INSERT succeeds + auto-hide check completes), the endpoint MUST return HTTP 204 with no response body.

#### Scenario: Successful report returns 204
- **WHEN** all validation passes AND the INSERT succeeds
- **THEN** the response is HTTP 204 AND the response body is empty

### Requirement: Auto-hide trigger — 3 unique reporters aged > 7 days

After a successful `reports` INSERT (same DB transaction), the handler SHALL compute:
```sql
SELECT COUNT(DISTINCT r.reporter_id)
FROM reports r
JOIN users u ON u.id = r.reporter_id
WHERE r.target_type = :target_type
  AND r.target_id = :target_id
  AND u.created_at < NOW() - INTERVAL '7 days'
  AND u.deleted_at IS NULL
```
If the count is ≥ 3, the handler MUST:
- For `target_type = "post"`: `UPDATE posts SET is_auto_hidden = TRUE WHERE id = :target_id AND deleted_at IS NULL`.
- For `target_type = "reply"`: `UPDATE post_replies SET is_auto_hidden = TRUE WHERE id = :target_id AND deleted_at IS NULL`.
- For `target_type IN ("user", "chat_message")`: no column UPDATE (neither table has `is_auto_hidden`).
- In all 4 cases: `INSERT INTO moderation_queue (target_type, target_id, trigger) VALUES (:target_type, :target_id, 'auto_hide_3_reports') ON CONFLICT (target_type, target_id, trigger) DO NOTHING`.

All of the above MUST execute in the same DB transaction as the `reports` INSERT. The UPDATE MUST be idempotent (the WHERE clause already handles the "already hidden" case as a no-op).

#### Scenario: 2 aged reporters do not trigger auto-hide
- **WHEN** a post has 2 distinct reporters both aged > 7 days AND a 3rd reporter (also aged > 7 days) is the NEW submitter
- **THEN** after the 3rd report INSERTs, the `posts.is_auto_hidden` flips from FALSE to TRUE AND a `moderation_queue` row with `trigger = 'auto_hide_3_reports'` exists for that post

#### Scenario: 3rd reporter aged < 7 days does not trigger auto-hide
- **WHEN** 2 prior reporters are > 7 days old AND the 3rd reporter is < 7 days old
- **THEN** the post's `is_auto_hidden` remains FALSE AND no `moderation_queue` row exists

#### Scenario: 3 reporters, one < 7 days old does not trigger auto-hide
- **WHEN** the target has 3 reporters total but one is < 7 days old
- **THEN** the COUNT DISTINCT with account-age filter returns 2 AND `is_auto_hidden` remains FALSE

#### Scenario: Aged-past-threshold account now counts on next submission
- **WHEN** an account was < 7 days old when it filed report #1, then > 7 days old at the time of a later report #3 on the same target
- **THEN** the COUNT DISTINCT (run at report #3's insert time) returns 3 (the aged account now counts) AND the flip fires

#### Scenario: Soft-deleted reporter does not count
- **WHEN** the target has 3 distinct reporters but one has `users.deleted_at IS NOT NULL`
- **THEN** COUNT DISTINCT returns 2 AND `is_auto_hidden` remains FALSE

#### Scenario: Auto-hide on a reply flips post_replies.is_auto_hidden
- **WHEN** 3 aged reporters report a reply with `target_type = "reply"`
- **THEN** `post_replies.is_auto_hidden` flips to TRUE AND a `moderation_queue` row with `target_type = "reply"` AND `trigger = 'auto_hide_3_reports'` exists

#### Scenario: Auto-hide on a user target writes queue row but no column flip
- **WHEN** 3 aged reporters report a user with `target_type = "user"`
- **THEN** no `is_auto_hidden` column is updated (users has no such column) AND a `moderation_queue` row with `target_type = "user"` AND `trigger = 'auto_hide_3_reports'` exists

#### Scenario: Auto-hide on a chat_message target writes queue row but no column flip
- **WHEN** 3 aged reporters report a chat_message with `target_type = "chat_message"`
- **THEN** no column is updated on `chat_messages` AND a `moderation_queue` row with `target_type = "chat_message"` AND `trigger = 'auto_hide_3_reports'` exists

#### Scenario: 4th and 5th reporters are no-ops on the queue row
- **WHEN** auto-hide has already fired (≥ 3 reporters; queue row exists) AND a 4th aged reporter submits
- **THEN** the `reports` INSERT succeeds AND the `UPDATE posts SET is_auto_hidden = TRUE` is a no-op (already TRUE) AND the `INSERT INTO moderation_queue ... ON CONFLICT DO NOTHING` is a no-op (queue row already exists)

#### Scenario: Transaction atomicity — INSERT + flip + queue row all commit together
- **WHEN** the auto-hide threshold is crossed
- **THEN** the new `reports` row, the `is_auto_hidden = TRUE` UPDATE, and the `moderation_queue` INSERT all commit in the same transaction (none visible externally until COMMIT)

#### Scenario: Duplicate report (409) does not re-run auto-hide
- **WHEN** a report is rejected with 409 due to the UNIQUE constraint
- **THEN** the auto-hide COUNT DISTINCT query is NOT executed AND no UPDATE or queue INSERT runs

### Requirement: Observability — structured log + Sentry breadcrumb on auto-hide fire

On every auto-hide fire (the threshold crossing from < 3 to ≥ 3), the handler SHALL emit a structured log line at INFO level with fields `event = "auto_hide_triggered"`, `target_type`, `target_id`, `reporter_count`. The handler SHALL also add a Sentry breadcrumb with the same data for the request trace.

#### Scenario: Log line emitted on threshold crossing
- **WHEN** the 3rd aged reporter submits and the flip fires
- **THEN** the backend structured log contains a line with `event = "auto_hide_triggered"` AND `target_type` AND `target_id` AND `reporter_count >= 3`

#### Scenario: No log line emitted on sub-threshold submission
- **WHEN** the 1st or 2nd reporter submits (count < 3)
- **THEN** no `auto_hide_triggered` log line is emitted

#### Scenario: No log line emitted on 4th+ reporter (already hidden)
- **WHEN** the 4th aged reporter submits on an already-hidden target
- **THEN** no `auto_hide_triggered` log line is emitted (the threshold was not crossed by this submission)

### Requirement: Error envelope matches existing routes

All 4xx responses SHALL use the envelope `{ "error": { "code": "<kebab-or-snake>", "message": "<human-readable>" } }` established by earlier auth / post / reply routes. Error codes SHALL be exactly one of: `invalid_request`, `invalid_uuid`, `self_report_rejected`, `target_not_found`, `duplicate_report`, `rate_limited`, `unauthenticated`.

#### Scenario: Error envelope for target_not_found
- **WHEN** the target UUID does not exist
- **THEN** the response body is `{ "error": { "code": "target_not_found", "message": "..." } }` AND `message` is non-empty

