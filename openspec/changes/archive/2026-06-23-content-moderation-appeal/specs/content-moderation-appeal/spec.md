## ADDED Requirements

### Requirement: Appeals ledger schema

The system SHALL persist moderation appeals in an `appeals` table created by Flyway migration `V34__appeals.sql` (the version number MUST be re-verified as the next-free version at pre-merge). The table MUST have columns: `id` UUID PK, `user_id` UUID NOT NULL `REFERENCES users(id) ON DELETE CASCADE`, `action_type` TEXT NOT NULL `CHECK (action_type IN ('suspension','permanent_ban'))`, `appeal_text` TEXT NOT NULL `CHECK (char_length(appeal_text) BETWEEN 1 AND 1000)`, `status` TEXT NOT NULL DEFAULT `'pending'` `CHECK (status IN ('pending','approved','rejected'))`, `decision_reason` TEXT (nullable, `CHECK` length ≤ 1000), `reviewed_by` UUID `REFERENCES admin_users(id) ON DELETE SET NULL`, `reviewed_at` TIMESTAMPTZ (nullable), `created_at` TIMESTAMPTZ NOT NULL DEFAULT NOW(). The migration MUST also create a partial-unique index `appeals_one_pending_per_user ON appeals(user_id) WHERE status = 'pending'` and a partial index `appeals_pending_created_idx ON appeals(created_at) WHERE status = 'pending'`. No partial-index predicate may reference `NOW()`.

#### Scenario: Valid appeal row persists
- **WHEN** an appeal row is inserted with `action_type = 'suspension'`, a 1–1000-char `appeal_text`, and `status = 'pending'`
- **THEN** the insert succeeds and `created_at` is populated by the DB default

#### Scenario: Over-length appeal text rejected at the schema
- **WHEN** an appeal row is inserted with `appeal_text` longer than 1000 characters
- **THEN** the DB `CHECK` constraint rejects the insert

#### Scenario: Admin deletion does not destroy appeal history
- **WHEN** the `admin_users` row referenced by an appeal's `reviewed_by` is deleted
- **THEN** the appeal row survives with `reviewed_by` set to NULL (the audit/appeal record is retained)

### Requirement: Ban-exempt appeal submission endpoint

The backend SHALL expose `POST /api/v1/appeals` mounted under the ban-exempt authenticated realm (per the `auth-jwt` modification) so that an `is_banned` subject — who is 403'd on every standard authenticated route — can submit. The endpoint MUST still validate the JWT's `token_version` (a `token_version` mismatch returns HTTP 401 `token_revoked`, unchanged). The request body carries `appeal_text` only; on success the endpoint returns HTTP 201 with the created appeal's id and `status = 'pending'`.

#### Scenario: Suspended user submits an appeal
- **GIVEN** caller A has `is_banned = TRUE` and `suspended_until > NOW()` and presents a JWT whose `token_version` matches `users.token_version`
- **WHEN** A `POST`s `/api/v1/appeals` with a valid `appeal_text` and no pending appeal exists
- **THEN** the response is HTTP 201, a `pending` appeal row is created, and `action_type = 'suspension'`

#### Scenario: Permanently-banned user submits an appeal
- **GIVEN** caller B has `is_banned = TRUE` and `suspended_until IS NULL` and a matching `token_version`
- **WHEN** B `POST`s `/api/v1/appeals` with a valid `appeal_text` and no pending appeal exists
- **THEN** the response is HTTP 201 and the persisted row has `action_type = 'permanent_ban'`

#### Scenario: Revoked token rejected at the ban-exempt realm
- **WHEN** a request to `POST /api/v1/appeals` arrives with a JWT whose `token_version` is older than `users.token_version`
- **THEN** the response is HTTP 401 `token_revoked` (the ban-exempt realm relaxes the ban check, NOT the revocation check)

### Requirement: Appeal eligibility is is_banned and never reveals shadow-ban

The submission endpoint SHALL accept an appeal only when the subject has `is_banned = TRUE`. Any caller with `is_banned = FALSE` MUST receive an identical HTTP 409 `no_actionable_moderation` response — whether the caller is a normal user or a shadow-banned-only user (`is_shadow_banned = TRUE`, `is_banned = FALSE`). The response MUST NOT distinguish the two states in body, headers, or timing-sensitive branching, preserving shadow-ban invisibility.

#### Scenario: Normal (un-actioned) user cannot appeal
- **GIVEN** caller C has `is_banned = FALSE` and `is_shadow_banned = FALSE`
- **WHEN** C `POST`s `/api/v1/appeals`
- **THEN** the response is HTTP 409 `no_actionable_moderation` and no appeal row is created

#### Scenario: Shadow-banned-only user gets the identical no-op response (no state leak)
- **GIVEN** caller D has `is_shadow_banned = TRUE` and `is_banned = FALSE`
- **WHEN** D `POST`s `/api/v1/appeals`
- **THEN** the response is the SAME HTTP 409 `no_actionable_moderation` envelope returned to caller C, with nothing that could confirm the shadow-ban state

### Requirement: One pending appeal per user

The system SHALL permit at most one `pending` appeal per user. A submission while the user already has a `pending` appeal MUST return HTTP 409 `appeal_already_pending`. Once the pending appeal is decided (`approved` or `rejected`), a new submission is permitted.

#### Scenario: Second concurrent submission rejected
- **GIVEN** caller A already has a `pending` appeal
- **WHEN** A `POST`s `/api/v1/appeals` again
- **THEN** the response is HTTP 409 `appeal_already_pending` and no second row is created

#### Scenario: New appeal allowed after a decision
- **GIVEN** caller A's previous appeal has `status = 'rejected'` and A is still `is_banned = TRUE`
- **WHEN** A `POST`s `/api/v1/appeals`
- **THEN** the response is HTTP 201 and a new `pending` appeal row is created

#### Scenario: Concurrent submissions race the one-pending guard
- **GIVEN** caller A has no pending appeal and issues two submissions that race past the application-level pre-check
- **WHEN** both reach the `appeals_one_pending_per_user` partial-unique index
- **THEN** exactly one succeeds with HTTP 201 and the other is mapped to HTTP 409 `appeal_already_pending` (the unique-violation is caught and translated, never surfaced as a 5xx)

### Requirement: Submission rate limit

The submission endpoint SHALL rate-limit per user via the canonical Redis hash-tag key shape `{scope:rate_appeal_day}:{user:<user_id>}` (the shipped `{scope:rate_*_day}:{user:…}` two-segment family required by `RedisHashTagRule`; `_day` = fixed-window marker) using the shared `computeTTLToNextReset` helper (no hardcoded reset math). Exceeding the daily cap MUST return HTTP 429 with the time-to-reset surfaced.

#### Scenario: Daily submission cap enforced
- **GIVEN** caller A has reached the daily appeal-submission cap
- **WHEN** A `POST`s `/api/v1/appeals` again within the window
- **THEN** the response is HTTP 429 and conveys the time until the limit resets

### Requirement: Server-derived action_type

The `action_type` recorded on an appeal SHALL be derived server-side from the subject's `users` row (`suspended_until IS NULL → 'permanent_ban'`, otherwise `'suspension'`). Any client-supplied `action_type` MUST be ignored.

#### Scenario: Client cannot spoof action_type
- **GIVEN** caller A is suspended (`suspended_until > NOW()`) and includes `action_type = 'permanent_ban'` in the request body
- **WHEN** A submits the appeal
- **THEN** the persisted row has `action_type = 'suspension'` (server-derived), not the client value

### Requirement: Own-appeal-status read

The backend SHALL expose an authenticated read (under the ban-exempt realm) returning the caller's most-recent appeal `status` (`pending` / `approved` / `rejected`), `action_type`, `created_at`, `reviewed_at`, and `decision_reason` when present. When the caller has never submitted an appeal, the endpoint MUST return an empty/no-appeal result rather than an error.

#### Scenario: Pending appeal status returned
- **GIVEN** caller A has a `pending` appeal
- **WHEN** A reads their appeal status
- **THEN** the response reports `status = 'pending'` with the submission `created_at`

#### Scenario: Decided appeal status with reason returned
- **GIVEN** caller A's latest appeal is `rejected` with a `decision_reason`
- **WHEN** A reads their appeal status
- **THEN** the response reports `status = 'rejected'`, the `decision_reason`, and `reviewed_at`

#### Scenario: No appeal yet returns a no-appeal result
- **GIVEN** caller E has never submitted an appeal
- **WHEN** E reads their appeal status
- **THEN** the response is a successful empty/no-appeal result (not a 4xx error)

### Requirement: Decision outcome surfaced via status read, proactive notification deferred

The appeal decision SHALL be surfaced to the user through the own-appeal-status read. Proactive push / in-app `notifications`-row delivery on an appeal decision is explicitly DEFERRED to a follow-up; deciding an appeal MUST NOT enqueue an FCM message or insert a `notifications` row in this change.

#### Scenario: Decision visible on the next status read
- **GIVEN** an admin transitions caller A's appeal to `approved`
- **WHEN** A next reads their appeal status
- **THEN** the response reports `status = 'approved'`

#### Scenario: Deciding an appeal fires no proactive notification (deferred)
- **WHEN** an admin approves or rejects an appeal
- **THEN** no FCM push is dispatched and no `notifications` row is inserted for the appeal decision (the outcome is read-pull only in this change)
