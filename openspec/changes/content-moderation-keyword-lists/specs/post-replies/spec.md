## ADDED Requirements

### Requirement: POST /api/v1/posts/{post_id}/replies runs `TextModerator.moderate(...)` before INSERT

`POST /api/v1/posts/{post_id}/replies` SHALL invoke `TextModerator.moderate(request.content)` AFTER the existing daily rate limit, the parent-post visibility resolution, and the content-length guard (1–280 Unicode code points), AND BEFORE the canonical `post_replies` INSERT (per existing [`post-replies/spec.md`](../../specs/post-replies/spec.md) `### Requirement: POST replies — INSERT and success response`).

The verdict mapping is:
- **`Verdict.Reject(matchedKeywords)`** → respond HTTP 400 with `error.code = "content_moderated_profanity"` + canonical Bahasa Indonesia message (see [`content-moderation-keyword-lists/spec.md`](../content-moderation-keyword-lists/spec.md) `### Requirement: User-facing rejection message is in Bahasa Indonesia, omits matched keywords`). NO `post_replies` row inserted, NO `moderation_queue` row written, NO `notifications` row written. The matched keywords are NOT in the response body.
- **`Verdict.Flag(matchedKeywords)`** → INSERT proceeds; in the same SQL transaction, INSERT one row into `moderation_queue` with `target_type = 'reply'`, `target_id = <new_reply_id>`, `trigger = 'uu_ite_keyword_match'`, `priority = 5`, `status = 'pending'`, using `ON CONFLICT (target_type, target_id, trigger) DO NOTHING`. `post_replies.is_auto_hidden` remains `FALSE` (UU ITE Layer 2 is soft-flag, not auto-hide). Response is 201 with the canonical reply payload. The existing `post_replied` notification (per existing `### Requirement: Successful reply emits a post_replied notification for the parent post author`) is still emitted on Flag — Flag is "soft-flag for admin review, otherwise normal" semantics, the user-visible flow is unchanged.
- **`Verdict.Allow`** → INSERT proceeds; no `moderation_queue` row written; `post_replied` notification emitted as today.

#### Scenario: Profanity hit rejects the reply, no INSERT or notification
- **GIVEN** the active profanity list contains `"badword"` AND parent post `P` is visible to caller `A`
- **WHEN** caller `A` POSTs `{"content": "this badword reply"}` to `/api/v1/posts/{P}/replies` (length valid)
- **THEN** the response is HTTP 400 with `error.code = "content_moderated_profanity"` AND no `post_replies` row exists for caller A on parent P from this request AND no `moderation_queue` row inserted AND no `notifications` row inserted

#### Scenario: UU ITE flag at threshold inserts reply + queue row, emits notification
- **GIVEN** the active UU ITE list is `["sara1", "sara2", "sara3"]` AND threshold is 3 AND parent post `P` is visible to caller `A`
- **WHEN** caller `A` POSTs `{"content": "soal sara1 dan sara2 dan sara3"}` to `/api/v1/posts/{P}/replies`
- **THEN** the response is HTTP 201 AND exactly one `post_replies` row exists with `is_auto_hidden = FALSE` AND exactly one `moderation_queue` row exists with `target_type = 'reply'`, `target_id = <new_reply_id>`, `trigger = 'uu_ite_keyword_match'` AND a `post_replied` `notifications` row is inserted for the parent-post author (per existing `### Requirement: Successful reply emits a post_replied notification for the parent post author`)

#### Scenario: Allowed reply writes no moderation_queue row
- **GIVEN** the active lists do not match the content
- **WHEN** caller A POSTs an allowed reply
- **THEN** the response is HTTP 201 AND no `moderation_queue` row exists for the new reply AND a `post_replied` notification is emitted

#### Scenario: Flag transaction is atomic — reply INSERT failure rolls back queue row
- **GIVEN** a Flag verdict produces a target reply id AND the `post_replies` INSERT subsequently fails (simulated rollback)
- **WHEN** the transaction is committed
- **THEN** no `post_replies` row exists AND no `moderation_queue` row exists for the target id AND no notification is emitted

#### Scenario: Reject response body does not leak matched keywords
- **GIVEN** content matched 2 distinct profanity keywords AND `Verdict.Reject(matchedKeywords = listOf("k1", "k2"))` was produced
- **WHEN** the response body is captured AND scanned for `"k1"` and `"k2"`
- **THEN** neither literal appears in the response body

### Requirement: Moderator runs AFTER rate limit AND length guard AND visibility resolution, BEFORE INSERT (call order)

The integration of `TextModerator.moderate(...)` into `POST /api/v1/posts/{post_id}/replies` SHALL be at a call site where:
- The 20/day rate limit (existing `### Requirement: Daily rate limit — 20/day Free, unlimited Premium, with WIB stagger`) has already passed.
- The parent-post visibility resolution (existing `### Requirement: POST replies — post visibility resolution`) has already passed.
- The 280-character content length guard (existing `### Requirement: POST replies — content length guard (1–280)`) has already passed.
- No `INSERT INTO post_replies ...` has yet been issued in the request transaction.

#### Scenario: Reply rate-limited at 21st reply does not invoke moderator
- **GIVEN** caller A has 20 successful replies in the WIB day AND attempts a 21st
- **WHEN** the request reaches the rate-limit gate
- **THEN** the response is HTTP 429 with the canonical rate-limit code (NOT `content_moderated_profanity`) AND no `TextModerator.moderate(...)` call is recorded for this request

#### Scenario: Oversized payload short-circuits before moderator runs
- **WHEN** caller A POSTs `{"content": "<281-char string>"}` to a visible parent post
- **THEN** the response is HTTP 400 with the existing length-guard error code AND no `TextModerator.moderate(...)` call is recorded
