## ADDED Requirements

### Requirement: POST /api/v1/posts runs `TextModerator.moderate(...)` before INSERT

`POST /api/v1/posts` SHALL invoke `TextModerator.moderate(request.content)` AFTER the existing content-length guard (1–280 Unicode code points) AND BEFORE the canonical single-INSERT path (per existing [`post-creation/spec.md`](../../specs/post-creation/spec.md) `### Requirement: Single-INSERT transactional write`).

The verdict mapping is:
- **`Verdict.Reject(matchedKeywords)`** → respond HTTP 400 with the existing error envelope, `error.code = "content_moderated_profanity"`, `error.message = "Konten ini mengandung kata yang tidak diperbolehkan. Silakan ubah dan coba lagi."`. NO `posts` row is inserted, NO `moderation_queue` row is written. The matched keywords from the verdict are NOT included in the response body.
- **`Verdict.Flag(matchedKeywords)`** → INSERT proceeds normally; in the same SQL transaction, INSERT one row into `moderation_queue` with `target_type = 'post'`, `target_id = <new_post_id>`, `trigger = 'uu_ite_keyword_match'`, `priority = 5`, `status = 'pending'`, `notes = NULL`. The INSERT uses `ON CONFLICT (target_type, target_id, trigger) DO NOTHING` (idempotency). The post is NOT auto-hidden (`is_auto_hidden = FALSE`); UU ITE Layer 2 is soft-flag only per [`docs/06-Security-Privacy.md:158`](../../../docs/06-Security-Privacy.md). Response is 201 with the canonical post payload (unchanged from the `Verdict.Allow` shape).
- **`Verdict.Allow`** → INSERT proceeds normally; no `moderation_queue` row written. Response 201 unchanged.

The moderator call site SHALL be the unique writer of the `Verdict.Reject` → HTTP 400 mapping in this handler — no other code path produces the same `error.code = "content_moderated_profanity"` envelope.

#### Scenario: Profanity hit rejects the post, no INSERT
- **GIVEN** the active profanity list contains `"badword"` AND the active UU ITE list is `["sara1"]` AND threshold is 3
- **WHEN** caller A POSTs `{"content": "this is a badword test", ...}` (length 23, valid coords)
- **THEN** the response is HTTP 400 AND the body has `error.code = "content_moderated_profanity"` AND `error.message = "Konten ini mengandung kata yang tidak diperbolehkan. Silakan ubah dan coba lagi."` AND no row exists in `posts` for caller A from this request AND no row exists in `moderation_queue`

#### Scenario: UU ITE flag at threshold inserts post + moderation_queue row in same transaction
- **GIVEN** the active profanity list is `[]` AND the active UU ITE list contains `["sara1", "sara2", "sara3"]` AND threshold is 3
- **WHEN** caller A POSTs `{"content": "perdebatan tentang sara1 dan sara2 dan sara3", ...}` (length within 280, valid coords)
- **THEN** the response is HTTP 201 with the canonical post payload AND exactly one `posts` row exists for the new post AND exactly one `moderation_queue` row exists with `target_type = 'post'`, `target_id = <new_post_id>`, `trigger = 'uu_ite_keyword_match'`, `status = 'pending'` AND `posts.is_auto_hidden = FALSE` (UU ITE is soft-flag, not auto-hide)

#### Scenario: UU ITE matches below threshold are allowed silently
- **GIVEN** the active UU ITE list contains `["sara1", "sara2", "sara3"]` AND threshold is 3
- **WHEN** caller A POSTs `{"content": "saya membahas sara1 saja", ...}` (single match, matchCount = 1, below threshold)
- **THEN** the response is HTTP 201 AND no `moderation_queue` row exists for the new post

#### Scenario: Allowed content writes no moderation_queue row
- **GIVEN** the active profanity list is `["badword"]` AND the active UU ITE list is `["sara1"]` AND threshold is 3
- **WHEN** caller A POSTs `{"content": "halo dunia, ini post yang baik", ...}` (no matches in either list)
- **THEN** the response is HTTP 201 AND no `moderation_queue` row exists for the new post

#### Scenario: Flag transaction is atomic — INSERT failure rolls back queue row
- **GIVEN** a Flag verdict produces `target_id = <new_post_id>` AND the `posts` INSERT fails (e.g., FK constraint regression, simulated rollback)
- **WHEN** the transaction is committed
- **THEN** no `posts` row exists for the new post AND no `moderation_queue` row exists for `target_id = <new_post_id>` (atomic rollback)

#### Scenario: Idempotent retry preserves single moderation_queue row
- **GIVEN** caller A's content produced a `Verdict.Flag` AND a `moderation_queue` row was inserted with `(target_type='post', target_id=P, trigger='uu_ite_keyword_match')` AND the same retry would attempt to insert the same row
- **WHEN** the retry transaction COMMITs
- **THEN** the `moderation_queue` table contains exactly ONE row for `(target_type='post', target_id=P, trigger='uu_ite_keyword_match')` (the `ON CONFLICT ... DO NOTHING` clause suppresses the duplicate)

#### Scenario: Reject response body does not leak matched keywords
- **GIVEN** a `Verdict.Reject(matchedKeywords = listOf("badword1", "badword2"))` is produced
- **WHEN** the HTTP response body is captured AND scanned for the literals `"badword1"` and `"badword2"`
- **THEN** neither literal appears in the response body

### Requirement: Moderator runs AFTER length guard and BEFORE INSERT (call order)

The integration of `TextModerator.moderate(...)` into `POST /api/v1/posts` SHALL be at a call site where:
- The 280-Unicode-code-point length guard from existing `### Requirement: Content length guard — 1 to 280 Unicode code points` has already executed (and short-circuited oversized payloads).
- No `INSERT INTO posts ...` has yet been issued in the request transaction.
- The coordinate envelope check from existing `### Requirement: Coordinate envelope check` may run before OR after the moderator (no ordering constraint between coords and moderator); the moderator MUST execute regardless.

The moderator MUST NOT be called speculatively before length validation (would waste Redis/Remote Config calls on payloads that will be rejected anyway for length).

#### Scenario: Static analysis confirms call order
- **WHEN** the `POST /api/v1/posts` handler source is read top-down
- **THEN** the order of statements (within the per-request flow, ignoring auth/middleware) is: content length guard → `TextModerator.moderate(...)` → coordinate envelope check (or vice versa) → INSERT (`INSERT INTO posts ...`); the moderator NEVER appears below the INSERT statement

#### Scenario: Oversized payload short-circuits before moderator runs
- **WHEN** caller A POSTs `{"content": "<281-char string>", ...}`
- **THEN** the response is HTTP 400 with the existing `### Requirement: Content length guard` error code (NOT `content_moderated_profanity`) AND no `TextModerator.moderate(...)` call is recorded for this request (verifiable via mock-spy on the moderator in integration test)
