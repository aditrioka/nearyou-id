## ADDED Requirements

### Requirement: GET /api/v1/user/referral returns the caller's invite code and referral progress

The backend SHALL expose `GET /api/v1/user/referral` authenticated via `AUTH_PROVIDER_USER` (Bearer JWT). The handler SHALL resolve the caller from the verified principal (`principal.userId`) ONLY — it accepts NO `user_id` path or query parameter, so it has NO IDOR surface — and SHALL respond `200` with a JSON body `{ "inviteCode": <string>, "grantedReferrals": <int>, "milestone": <int>, "inviterRewardClaimed": <boolean> }` (bare camelCase keys, mirroring the `hide-distance` / timeline wire convention). `inviteCode` SHALL be the caller's `users.invite_code_prefix` returned verbatim (the same value redemption resolves via `findInviterByInviteCodePrefix`) — no second derivation and no transform. An unauthenticated request SHALL be rejected with `401`. The endpoint is a pure read — it writes nothing and grants nothing. The `invite_code_prefix` read is neither a username write nor a privacy-flag column, so it requires no `@allow-*` annotation. PII discipline: the handler SHALL log only a content-free event name (and, on failure, the exception class) — never the bearer token, the JWT `sub`, the invite code, or the response body.

#### Scenario: Authenticated caller receives their code and progress
- **WHEN** an authenticated user calls `GET /api/v1/user/referral`
- **THEN** the response is `200` AND the body's `inviteCode` equals the caller's `users.invite_code_prefix` AND `grantedReferrals`, `milestone`, and `inviterRewardClaimed` are present

#### Scenario: Unauthenticated request is rejected
- **WHEN** `GET /api/v1/user/referral` is sent with no valid JWT
- **THEN** the response is `401`

#### Scenario: The read targets the JWT principal only (no IDOR)
- **WHEN** an authenticated user calls `GET /api/v1/user/referral`
- **THEN** the returned `inviteCode` and progress are the JWT principal's own AND the endpoint exposes no parameter by which a caller could request another user's code or progress

#### Scenario: A repository failure fails soft without leaking
- **WHEN** the read path throws (e.g., a transient DB error) for an authenticated caller
- **THEN** the response is `500` AND the logged line contains only the event name and the exception class — never the token, the JWT `sub`, or the invite code

### Requirement: Referral progress reflects confirmed server state

The `grantedReferrals` value SHALL equal the count of the caller's referral tickets in the `granted` status (`SELECT COUNT(*) FROM referral_tickets WHERE inviter_user_id = <caller> AND status = 'granted'`). The `milestone` value SHALL be the inviter lifetime-reward threshold (`5`, the value the referral activity-check worker uses as `INVITER_MILESTONE`). The `inviterRewardClaimed` value SHALL be `true` when `users.inviter_reward_claimed_at IS NOT NULL` for the caller and `false` otherwise. These values are read from server state only — the endpoint never increments, decrements, grants, or claims; the referral activity-check worker remains the sole authority for promoting tickets and claiming the inviter reward.

#### Scenario: Granted count and milestone are reported from server state
- **GIVEN** a caller with three `granted` referral tickets and an unclaimed inviter reward
- **WHEN** they call `GET /api/v1/user/referral`
- **THEN** `grantedReferrals` is `3` AND `milestone` is `5` AND `inviterRewardClaimed` is `false`

#### Scenario: A claimed inviter reward is reported
- **GIVEN** a caller whose `users.inviter_reward_claimed_at` is set (reward already granted at the 5th referral)
- **WHEN** they call `GET /api/v1/user/referral`
- **THEN** `inviterRewardClaimed` is `true` (and `grantedReferrals` may be `5` or higher — later successes still reward invitees but never re-reward the inviter)

#### Scenario: A user with no referrals reports a zero, unclaimed state
- **GIVEN** a caller who has never been credited a referral
- **WHEN** they call `GET /api/v1/user/referral`
- **THEN** `grantedReferrals` is `0` AND `inviterRewardClaimed` is `false` AND `inviteCode` is still their assigned `users.invite_code_prefix`
