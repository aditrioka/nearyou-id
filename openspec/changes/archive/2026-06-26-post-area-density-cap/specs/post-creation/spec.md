## ADDED Requirements

### Requirement: POST /api/v1/posts composes the per-area density gate

`POST /api/v1/posts` SHALL invoke the per-area density gate (defined by the `post-area-density-cap` capability) on every post that will be created, and on a threshold hit SHALL write the `area_spam` `moderation_queue` row **in the same transaction** as the `posts` INSERT. The gate runs AFTER the per-user daily cap and AFTER the moderator's `Verdict.Reject` short-circuit (so a rejected, never-created post neither counts toward area density nor produces a queue row), and applies to ALL tiers (Premium is NOT exempt — unlike the daily cap). The success-path `201` response body and the route's error envelope are UNCHANGED by this gate: an area-flag adds no error code, no `Retry-After`, and no new response field. When an area-flag and a `Verdict.Flag` both fire on one post, two `moderation_queue` rows are written (one per trigger), each idempotent via `ON CONFLICT (target_type, target_id, trigger) DO NOTHING`.

#### Scenario: Area flag writes the queue row in the post transaction
- **WHEN** a post that trips the area-density threshold is created
- **THEN** the `posts` INSERT and the `moderation_queue` row (`trigger='area_spam'`) commit atomically in one transaction AND the response is HTTP 201 with the canonical post payload (no error code, no `Retry-After`)

#### Scenario: Rejected content neither counts nor flags for area density
- **WHEN** caller A POSTs content that produces a `Verdict.Reject`
- **THEN** the response is HTTP 400 `content_moderated_profanity` AND no `posts` row is inserted AND the area-density counter for that area is NOT incremented AND no `area_spam` `moderation_queue` row exists

#### Scenario: Area flag and UU-ITE flag coexist on one post
- **GIVEN** a post both trips the area-density threshold AND produces a `Verdict.Flag`
- **WHEN** the post is created
- **THEN** the response is HTTP 201 AND exactly two `moderation_queue` rows exist for the post — one `trigger='uu_ite_keyword_match'` and one `trigger='area_spam'` — both with `target_type='post'` and the same `target_id`

## MODIFIED Requirements

### Requirement: Moderator runs AFTER length guard and BEFORE INSERT (call order)

The integration of `TextModerator.moderate(...)` into `POST /api/v1/posts` SHALL be at a call site where:
- The 280-Unicode-code-point length guard from existing `### Requirement: Content length guard — 1 to 280 Unicode code points` has already executed (and short-circuited oversized payloads).
- No `INSERT INTO posts ...` has yet been issued in the request transaction.
- The coordinate envelope check from existing `### Requirement: Coordinate envelope check` may run before OR after the moderator (no ordering constraint between coords and moderator); the moderator MUST execute regardless.

The per-area density gate (`post-area-density-cap`) SHALL run AFTER the moderator's `Verdict.Reject` short-circuit (a rejected post is never created, so it neither increments the area counter nor produces a queue row) and AFTER the coordinate envelope check + `display_location` derivation it depends on, and BEFORE the `posts` INSERT — its `moderation_queue` write composes into the same transaction as any `Verdict.Flag` write. The area gate MUST NOT appear below the INSERT statement.

The moderator MUST NOT be called speculatively before length validation (would waste Redis/Remote Config calls on payloads that will be rejected anyway for length).

#### Scenario: Static analysis confirms call order
- **WHEN** the `POST /api/v1/posts` handler source is read top-down
- **THEN** the order of statements (within the per-request flow, ignoring auth/middleware) is: content length guard → `TextModerator.moderate(...)` → coordinate envelope check (or vice versa) → per-area density gate → INSERT (`INSERT INTO posts ...`); neither the moderator nor the area-density gate ever appears below the INSERT statement

#### Scenario: Oversized payload short-circuits before moderator runs
- **WHEN** caller A POSTs `{"content": "<281-char string>", ...}`
- **THEN** the response is HTTP 400 with the existing `### Requirement: Content length guard` error code (NOT `content_moderated_profanity`) AND no `TextModerator.moderate(...)` call is recorded for this request (verifiable via mock-spy on the moderator in integration test)
