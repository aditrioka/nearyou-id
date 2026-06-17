# premium-username-customization Specification

## Purpose

Premium username customization lets a Premium subscriber replace their auto-generated handle with a custom one — a headline paid perk in the freemium model. This capability defines the **backend**: the `PATCH /api/v1/user/username` change endpoint and the `GET /api/v1/username/check` availability probe, gated in order by the `premium_username_customization_enabled` Remote Config kill switch → Premium entitlement (`subscription_status` principal claim) → a 30-day cooldown, then a validation + profanity/UU-ITE moderation pipeline, committed in a race-safe `FOR UPDATE` transaction that enforces a 30-day release hold on the relinquished handle (anti-impersonation) and emits a confirmation notification. The mobile Settings UI and the admin username-change oversight are separate follow-on capabilities.
## Requirements
### Requirement: Premium-gated username change endpoint

The system SHALL expose `PATCH /api/v1/user/username` (authenticated; body `{ "new_username": <string> }`) that changes the caller's username only after, in order, the feature-flag, Premium, and 30-day-cooldown gates pass and the candidate clears the full validation + moderation pipeline. Premium status SHALL be read from the principal's `subscription_status` claim and SHALL treat both `premium_active` and `premium_billing_retry` as Premium (mirroring `premium-search`'s `PREMIUM_STATES`).

#### Scenario: Premium user changes username successfully
- **WHEN** a user whose `subscription_status` claim is `premium_active` (or `premium_billing_retry`) `PATCH`es a valid, available candidate and is outside the 30-day cooldown
- **THEN** the system SHALL update `users.username` to the candidate and set `users.username_last_changed_at = NOW()`
- **AND** SHALL respond `200` with the new username

#### Scenario: Non-Premium user is paywalled
- **WHEN** a user whose `subscription_status` claim is `free` calls `PATCH /api/v1/user/username`
- **THEN** the system SHALL reject with `403` and body `{"error":"premium_required","upsell":true}` (the canonical Premium-gate envelope)
- **AND** SHALL NOT change the username

#### Scenario: Unauthenticated request is rejected
- **WHEN** an unauthenticated request hits `PATCH /api/v1/user/username`
- **THEN** the system SHALL reject at the auth boundary (`401`) before any gate runs

### Requirement: Feature-flag kill switch

The system SHALL gate both username endpoints behind the `premium_username_customization_enabled` Remote Config flag (default TRUE), checked BEFORE the Premium gate. When the flag is FALSE, the capability SHALL behave as if unavailable.

#### Scenario: Flag OFF disables the change endpoint
- **WHEN** `premium_username_customization_enabled` is FALSE and a Premium user calls `PATCH /api/v1/user/username`
- **THEN** the system SHALL respond `503` and SHALL NOT change the username

#### Scenario: Flag OFF disables the availability probe
- **WHEN** `premium_username_customization_enabled` is FALSE and a Premium user calls `GET /api/v1/username/check`
- **THEN** the system SHALL respond `503`

### Requirement: 30-day change cooldown

The system SHALL allow at most one successful username change per rolling 30 days per user, enforced against the durable `users.username_last_changed_at` column (not a Redis key).

#### Scenario: Second change within 30 days is blocked
- **WHEN** a Premium user whose `username_last_changed_at` is less than 30 days ago calls `PATCH /api/v1/user/username`
- **THEN** the system SHALL respond `429` and SHALL NOT change the username

#### Scenario: Change is allowed after the cooldown elapses
- **WHEN** a Premium user whose `username_last_changed_at` is 30 or more days ago (or NULL) submits an otherwise-valid candidate
- **THEN** the cooldown gate SHALL pass and the change SHALL proceed to validation

### Requirement: Candidate format validation

The system SHALL validate the candidate handle: length 3–30 characters (application-layer cap, stricter than the `VARCHAR(60)` schema ceiling), charset matching `^[a-z0-9][a-z0-9_.]*[a-z0-9_]$` (lowercase alphanumerics, dots and underscores middle-only), and no consecutive dots (an application-layer `!candidate.contains("..")` guard, since a single regex cannot cleanly forbid it). A format failure SHALL be rejected before any database collision check.

#### Scenario: Out-of-range length is rejected
- **WHEN** the candidate is shorter than 3 or longer than 30 characters
- **THEN** the system SHALL respond `422` with an `invalid_username` error and SHALL NOT change the username

#### Scenario: Invalid charset is rejected
- **WHEN** the candidate contains an uppercase letter, a leading or trailing dot, a leading underscore, or any character outside the charset
- **THEN** the system SHALL respond `422` with an `invalid_username` error

#### Scenario: Consecutive dots are rejected
- **WHEN** the candidate contains `..` (e.g. `a..b`)
- **THEN** the system SHALL respond `422` with an `invalid_username` error (caught by the application-layer guard, not the regex)

#### Scenario: Well-formed candidates pass format validation
- **WHEN** the candidate is one of `abc`, `a_b.c`, or `user1.test_2`
- **THEN** the candidate SHALL pass format validation and proceed to collision + moderation checks

### Requirement: Reserved and release-hold collision checks

The system SHALL reject a candidate that collides (case-insensitively) with a `reserved_usernames` row (including `source = 'admin_added'`), with a `username_history` row still under its 30-day release hold (`released_at > NOW()`), or with another user's current username.

#### Scenario: Reserved username is rejected
- **WHEN** the candidate matches a `reserved_usernames` row (`LOWER` match), including an `admin_added` entry
- **THEN** the system SHALL respond `409` with a `username_unavailable` error

#### Scenario: Username on active release hold is rejected
- **WHEN** the candidate matches a `username_history.old_username` (`LOWER` match) whose `released_at > NOW()`
- **THEN** the system SHALL respond `409` with a `username_unavailable` error

#### Scenario: Released username becomes claimable
- **WHEN** the candidate matches a `username_history.old_username` whose `released_at <= NOW()` and the handle is otherwise free
- **THEN** the collision check SHALL pass and the change SHALL proceed

#### Scenario: Username currently taken by another user is rejected
- **WHEN** the candidate equals another user's current `users.username` (`LOWER` match)
- **THEN** the system SHALL respond `409` with a `username_unavailable` error

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

### Requirement: Race-safe release-hold transaction

On a successful change the system SHALL, within a single transaction holding `SELECT … FOR UPDATE` on the caller's `users` row: re-verify uniqueness/reserved/release-hold under the lock, update the username and `username_last_changed_at`, write the old handle to `username_history` with `released_at = changed_at + INTERVAL '30 days'`, and insert the confirmation notification. Any step failing SHALL roll back the entire change. The `users.username` write SHALL carry the username-write allowlist annotation (critical invariant #7).

#### Scenario: Successful change writes history and notification atomically
- **WHEN** a change commits, renaming `oldname` to `newname`
- **THEN** a `username_history` row SHALL exist with `old_username = oldname`, `new_username = newname`, and `released_at = changed_at + 30 days`
- **AND** a `notifications` row of type `username_release_scheduled` with `body_data {old_username, released_at}` SHALL be inserted in the same transaction via the transaction-aware `NotificationEmitter.emit(conn, …)` seam (in-app only, no FCM for this self-action type)

#### Scenario: A failure mid-transaction leaves no partial write
- **WHEN** any step of the change transaction fails (e.g. a unique-violation caught at the final write)
- **THEN** the transaction SHALL roll back leaving `users.username`, `users.username_last_changed_at`, and `username_history` unchanged
- **AND** the system SHALL respond `409` `username_unavailable` for a lost uniqueness race

#### Scenario: Concurrent changes for the same handle yield one winner
- **WHEN** two users concurrently `PATCH` the same available candidate
- **THEN** exactly one SHALL succeed (`200`) and the other SHALL receive `409` `username_unavailable` (the `username` unique index is the final backstop)

### Requirement: Non-authoritative availability probe

The system SHALL expose `GET /api/v1/username/check?candidate=<handle>` (authenticated, Premium- and flag-gated, rate-limited 3/day) that runs the read-only format + collision validation and returns availability, explicitly NON-authoritatively — the authoritative check happens under the row lock at change time.

#### Scenario: Probe reports an available candidate
- **WHEN** a Premium user probes a well-formed, free candidate
- **THEN** the system SHALL respond `200` indicating the candidate is currently available

#### Scenario: Probe reports an unavailable candidate
- **WHEN** the probed candidate is reserved, on active release hold, or currently taken
- **THEN** the system SHALL respond `200` indicating the candidate is unavailable

#### Scenario: Probe availability is not a guarantee
- **WHEN** a candidate reported available by the probe is claimed by another user before the prober's `PATCH`
- **THEN** the prober's `PATCH` SHALL still fail the under-lock recheck with `409` `username_unavailable` (the probe carries no reservation)

### Requirement: Anti-probing and probe rate limits

The system SHALL enforce, via Redis rate-limit-infrastructure using `computeTTLToNextReset` and `{scope:<value>}` hash-tag keys (no hardcoded reset math): at most 10 FAILED change attempts per user per hour, and at most 3 availability probes per user per day.

#### Scenario: Excess failed attempts are throttled
- **WHEN** a user accumulates more than 10 failed `PATCH` attempts within an hour
- **THEN** the next attempt SHALL be rejected with `429` independent of the candidate's validity

#### Scenario: Excess probes are throttled
- **WHEN** a user issues more than 3 `GET /api/v1/username/check` requests within a day
- **THEN** the next probe SHALL be rejected with `429`

### Requirement: Downgrade-to-Free preserves the custom username

When a Premium user downgrades to Free, the system SHALL keep their custom username as last set (no revert to an auto-generated handle) and SHALL block further changes until they re-subscribe. The 30-day cooldown SHALL be measured from the last actual change and SHALL NOT be reset by the subscription cycle.

#### Scenario: Downgrade keeps the username
- **WHEN** a user with a custom username downgrades to `free`
- **THEN** `users.username` SHALL remain the custom handle (no revert)

#### Scenario: Downgraded user cannot change username
- **WHEN** a now-Free user calls `PATCH /api/v1/user/username`
- **THEN** the system SHALL respond `403` `premium_required`

#### Scenario: Re-subscribe re-enables changes without resetting the cooldown
- **WHEN** a user re-subscribes and their last change was less than 30 days ago
- **THEN** the change endpoint SHALL be re-enabled but the cooldown SHALL still block a change until 30 days from that last change have elapsed

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

