## ADDED Requirements

### Requirement: Destructive moderation-queue resolutions enforce the per-admin destructive-action cap

The destructive moderation-queue resolutions served by `POST /admin/moderation-queue/{id}/resolve` — `suspend_author_7d`, `ban_author`, and `shadow_ban_author` — SHALL enforce `admin-destructive-action-rate-limit` before performing enforcement: when the acting admin is at or over the cap (20 destructive actions in the trailing hour), the resolution SHALL be rejected with an inline "quota exceeded" state, leaving the `moderation_queue` row in `status = 'pending'`, performing NO author enforcement (no `users` mutation), and writing NO `admin_actions_log` row for the rejected attempt. The non-destructive resolutions (`keep`, `hide`) and the report-status bookkeeping (`POST /admin/reports/{id}/resolve` with `decision` in `{actioned, dismissed}`) are NOT in the destructive set and SHALL NOT be gated by the cap.

#### Scenario: A ban resolution beyond the cap is rejected without enforcement

- **GIVEN** an authenticated `admin` at the destructive-action cap (20 in the trailing hour) AND a `pending` `moderation_queue` row for a `post` target whose author is active
- **WHEN** the client sends `POST /admin/moderation-queue/{id}/resolve` with `resolution = ban_author`
- **THEN** the response SHALL surface a "quota exceeded" state (not a 5xx) AND the queue row SHALL stay `status = 'pending'` AND the author's `is_banned` SHALL be unchanged AND no new `admin_actions_log` row SHALL be written

#### Scenario: A non-destructive resolution is allowed even at the cap

- **GIVEN** an authenticated write-role admin at the destructive-action cap AND a `pending` `moderation_queue` row for a `post` target
- **WHEN** the client sends `POST /admin/moderation-queue/{id}/resolve` with `resolution = hide`
- **THEN** the resolution SHALL apply normally (content `hide` is not destructive) AND the queue row SHALL become `status = 'resolved'`

#### Scenario: Report-status bookkeeping is allowed even at the cap

- **GIVEN** an authenticated write-role admin at the destructive-action cap AND a `pending` `reports` row
- **WHEN** the client sends `POST /admin/reports/{id}/resolve` with `decision = dismissed`
- **THEN** the report-status transition SHALL apply normally (bookkeeping is not destructive)

#### Scenario: A destructive resolution under the cap proceeds

- **GIVEN** an authenticated `admin` with 19 destructive-action rows in the trailing hour AND a `pending` `moderation_queue` row whose author is active
- **WHEN** the client sends `POST /admin/moderation-queue/{id}/resolve` with `resolution = suspend_author_7d`
- **THEN** the suspend enforcement SHALL apply AND the queue row SHALL become `status = 'resolved'` AND exactly one new `admin_actions_log` row SHALL be written
