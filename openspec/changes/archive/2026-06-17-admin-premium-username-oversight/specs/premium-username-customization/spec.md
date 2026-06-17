## MODIFIED Requirements

### Requirement: Profanity and UU ITE moderation on the candidate

The system SHALL run the candidate handle through the existing text-moderation pipeline (profanity blocklist + UU ITE keyword match), EXCEPT that it SHALL FIRST consult `username_flag_overrides` (V23) for a non-consumed approval matching `(user_id = the caller, candidate = the normalized lowercased candidate)`. On such a match the moderation rejection SHALL be SKIPPED — the admin has pre-approved this exact handle (`admin-premium-username-oversight`) — and the candidate SHALL continue through the remaining gates (reserved, release-hold, uniqueness, cooldown, charset). The matched override SHALL be consumed via a conditional `UPDATE username_flag_overrides SET consumed_at = NOW() WHERE user_id = <caller> AND candidate = <normalized candidate> AND consumed_at IS NULL` executed INSIDE the same `SELECT … FOR UPDATE` change transaction that performs the rename (so consume + rename + history + notification commit or roll back together). The skip decision SHALL be re-validated under that lock: if a concurrent change already consumed the override (the conditional consume affects zero rows), the candidate SHALL be re-moderated, so the override grants at most one successful pass even under concurrent same-user submits. Absent a matching approval, on a moderation hit the change SHALL be REJECTED upfront, AND the system SHALL upsert the standing `moderation_queue` row (`target_type = 'user'`, `target_id =` the caller's user id, `trigger = 'username_flagged'`) recording the flagged candidate in `notes`. Because `moderation_queue` carries `UNIQUE (target_type, target_id, trigger)` (V9), the upsert SHALL use `ON CONFLICT (target_type, target_id, trigger) DO UPDATE SET status = 'pending', resolution = NULL, resolved_by = NULL, resolved_at = NULL, notes = EXCLUDED.notes, created_at = NOW()` — re-opening the standing flag with the latest candidate so a previously-resolved flag does not silently swallow a new attempt (this supersedes the prior `ON CONFLICT DO NOTHING`). The result is one standing username-flag per user, always reflecting the latest flagged candidate. (Authoritative per `docs/06` § Premium Username Moderation; supersedes `docs/02`'s looser "soft-flags" wording.)

#### Scenario: Flagged candidate without an approval is rejected and (re-)queued with the candidate
- **WHEN** the candidate matches the profanity or UU ITE keyword lists AND no non-consumed `username_flag_overrides` row matches `(caller, candidate)`
- **THEN** the system SHALL respond `422` with a `username_rejected` error advising the user to pick another handle
- **AND** SHALL upsert the standing `moderation_queue` row (`target_type = 'user'`, `target_id = <caller's user id>`, `trigger = 'username_flagged'`) with `notes` set to the flagged candidate, re-opening it to `status = 'pending'` (clearing any prior `resolution`/`resolved_by`/`resolved_at`)
- **AND** SHALL NOT change the username

#### Scenario: An admin-approved candidate skips moderation and is consumed
- **GIVEN** a non-consumed `username_flag_overrides` row for `(user_id = <U>, candidate = 'borderlinehandle')` written by the admin accept action
- **WHEN** `<U>` `PATCH`es the candidate `borderlinehandle` (which the moderation pipeline would otherwise flag) and all other gates pass
- **THEN** the moderation rejection SHALL be skipped AND the change SHALL succeed (`200`)
- **AND** that override row's `consumed_at` SHALL be set by the conditional consume inside the successful-change transaction

#### Scenario: A consumed or absent override does not grant a pass
- **GIVEN** the `username_flag_overrides` row for `(user_id = <U>, candidate = 'borderlinehandle')` is already `consumed`, OR no override exists for the submitted candidate
- **WHEN** `<U>` `PATCH`es a candidate the moderation pipeline flags
- **THEN** the change SHALL be REJECTED (`422` `username_rejected`) exactly as the no-override path — the override grants no blanket per-user exemption

#### Scenario: An override grants at most one pass under concurrent same-user submits
- **GIVEN** a single non-consumed `username_flag_overrides` row for `(user_id = <U>, candidate = 'borderlinehandle')`
- **WHEN** `<U>` concurrently submits `borderlinehandle` twice (two `PATCH`es racing on the `FOR UPDATE` lock)
- **THEN** at most ONE SHALL succeed via the override (the conditional consume affects exactly one row); the other, acquiring the lock after the consume, SHALL find the override already spent and be re-moderated (and rejected, or `409` for the now-taken handle) — never a second free pass

#### Scenario: Repeated flagged attempts are throttled and signalled
- **WHEN** the same user produces more than 3 moderation-flagged candidates (none pre-approved) within 24 hours
- **THEN** the excess attempts SHALL be governed by the 10-failed-attempts-per-hour limit (a flagged attempt is a failed attempt) AND the standing `username_flagged` queue row SHALL exist for the user with `notes` reflecting the most recent flagged candidate
- **AND** the numeric anomaly-score increment described in `docs/06` SHALL be deferred to the anomaly-detection capability (see the deferred-scope requirement), since no `anomaly_score` substrate exists yet

### Requirement: Backend-only scope (mobile UI and anomaly-score effect deferred)

This capability SHALL expose only the two backend endpoints (`PATCH /api/v1/user/username` + `GET /api/v1/username/check`). The mobile Settings UI (Premium entry, paywall, live probe, cooldown countdown, downgrade banner) and the **numeric anomaly-score increment** on repeated flagged attempts remain explicitly OUT OF SCOPE and tracked as separate follow-on changes. The admin username-change oversight **surface** (`username_history` viewer, flagged-candidate accept/reject, manual handle release) is delivered by the separate `admin-premium-username-oversight` capability — no longer deferred; this capability participates in that flow only by consulting and consuming `username_flag_overrides` approvals inside its moderation gate (see the moderation requirement). This change SHALL NOT add an `anomaly_score` column or any anomaly-scoring path.

#### Scenario: No mobile or admin surface is added by this capability
- **WHEN** this capability is implemented
- **THEN** no `:mobile:app` screen and no `/admin/*` route SHALL be added by `premium-username-customization` itself
- **AND** the mobile Premium-username UI SHALL be delivered by the separate follow-on mobile change
- **AND** the admin username-change oversight surface SHALL be delivered by the `admin-premium-username-oversight` capability (the gate's consult/consume of approvals is the only override-related behavior in this capability)

#### Scenario: Anomaly-score effect is deferred to the anomaly-detection capability
- **WHEN** a user accumulates more than 3 moderation-flagged username attempts in 24 hours
- **THEN** this change SHALL NOT raise a numeric anomaly score and SHALL NOT introduce an `anomaly_score` substrate
- **AND** the numeric-score effect described in `docs/06` SHALL be implemented by the future anomaly-detection capability (docs/08 Phase 4 #17), for which this requirement is the tracking marker

## RENAMED Requirements

- FROM: `### Requirement: Backend-only scope (mobile UI, admin oversight, and anomaly-score effect deferred)`
- TO: `### Requirement: Backend-only scope (mobile UI and anomaly-score effect deferred)`
