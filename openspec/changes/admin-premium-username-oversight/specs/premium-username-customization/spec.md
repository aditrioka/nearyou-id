## MODIFIED Requirements

### Requirement: Profanity and UU ITE moderation on the candidate

The system SHALL run the candidate handle through the existing text-moderation pipeline (profanity blocklist + UU ITE keyword match), EXCEPT that it SHALL FIRST consult `username_flag_overrides` (V23) for a non-consumed approval matching `(user_id = the caller, candidate = the normalized lowercased candidate)`. On such a match the moderation rejection SHALL be SKIPPED — the admin has pre-approved this exact handle (`admin-premium-username-oversight`) — and the candidate SHALL continue through the remaining gates (reserved, release-hold, uniqueness, cooldown, charset), with the matched override consumed (`consumed_at = NOW()`) inside the successful-change transaction so it grants at most one free pass. Absent a matching approval, on a moderation hit the change SHALL be REJECTED upfront, AND the system SHALL upsert the standing `moderation_queue` row (`target_type = 'user'`, `target_id =` the caller's user id, `trigger = 'username_flagged'`) recording the flagged candidate in `notes`. Because `moderation_queue` carries `UNIQUE (target_type, target_id, trigger)` (V9), the upsert SHALL use `ON CONFLICT (target_type, target_id, trigger) DO UPDATE SET status = 'pending', resolution = NULL, resolved_by = NULL, resolved_at = NULL, notes = EXCLUDED.notes, created_at = NOW()` — re-opening the standing flag with the latest candidate so a previously-resolved flag does not silently swallow a new attempt (this supersedes the prior `ON CONFLICT DO NOTHING`). The result is one standing username-flag per user, always reflecting the latest flagged candidate. (Authoritative per `docs/06` § Premium Username Moderation; supersedes `docs/02`'s looser "soft-flags" wording.)

#### Scenario: Flagged candidate without an approval is rejected and (re-)queued with the candidate
- **WHEN** the candidate matches the profanity or UU ITE keyword lists AND no non-consumed `username_flag_overrides` row matches `(caller, candidate)`
- **THEN** the system SHALL respond `422` with a `username_rejected` error advising the user to pick another handle
- **AND** SHALL upsert the standing `moderation_queue` row (`target_type = 'user'`, `target_id = <caller's user id>`, `trigger = 'username_flagged'`) with `notes` set to the flagged candidate, re-opening it to `status = 'pending'` (clearing any prior `resolution`/`resolved_by`/`resolved_at`)
- **AND** SHALL NOT change the username

#### Scenario: An admin-approved candidate skips moderation and is consumed
- **GIVEN** a non-consumed `username_flag_overrides` row for `(user_id = <U>, candidate = 'borderlinehandle')` written by the admin accept action
- **WHEN** `<U>` `PATCH`es the candidate `borderlinehandle` (which the moderation pipeline would otherwise flag) and all other gates pass
- **THEN** the moderation rejection SHALL be skipped AND the change SHALL succeed (`200`)
- **AND** that override row's `consumed_at` SHALL be set within the successful-change transaction

#### Scenario: A consumed or absent override does not grant a pass
- **GIVEN** the `username_flag_overrides` row for `(user_id = <U>, candidate = 'borderlinehandle')` is already `consumed`, OR no override exists for the submitted candidate
- **WHEN** `<U>` `PATCH`es a candidate the moderation pipeline flags
- **THEN** the change SHALL be REJECTED (`422` `username_rejected`) exactly as the no-override path — the override grants no blanket per-user exemption

#### Scenario: Repeated flagged attempts are throttled and signalled
- **WHEN** the same user produces more than 3 moderation-flagged candidates (none pre-approved) within 24 hours
- **THEN** the excess attempts SHALL be governed by the 10-failed-attempts-per-hour limit (a flagged attempt is a failed attempt) AND the standing `username_flagged` queue row SHALL exist for the user with `notes` reflecting the most recent flagged candidate
- **AND** the numeric anomaly-score increment described in `docs/06` SHALL be deferred to the anomaly-detection capability (see the deferred-scope requirement), since no `anomaly_score` substrate exists yet
