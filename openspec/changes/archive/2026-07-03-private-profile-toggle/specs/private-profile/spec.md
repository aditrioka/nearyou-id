## ADDED Requirements

### Requirement: PATCH /api/v1/user/private-profile writes the opt-in for the authenticated caller

The backend SHALL expose `PATCH /api/v1/user/private-profile` (Bearer JWT via `AUTH_PROVIDER_USER`) accepting a body `{"privateProfile": <boolean>}` and persisting it to the caller's `users.private_profile_opt_in`. A successful write SHALL return HTTP `200` echoing the stored value. Because `private_profile_opt_in` IS on the privacy-flag-write lint allowlist, the repository `UPDATE` SHALL carry the `// @allow-privacy-write: user_settings` annotation (the reserved Settings-flow writer named in `openspec/project.md` § Coding Conventions). The write SHALL be permitted for any caller regardless of `subscription_status` (the flag's *effect* is read-gated by the canonical effective-private formula; storing intent is harmless and mirrors how a downgraded user holds a stale `private_profile_opt_in = TRUE` — the `hide-distance` "write-anytime + read-gated" posture). The endpoint SHALL require a valid JWT and reject an unauthenticated request with `401`. The target user is the JWT principal (`principal.userId`) ONLY — the endpoint accepts NO user-id path or body parameter, so it has no IDOR surface — and it enforces the standard request-body transport cap (mirroring the `ConsentRoutes` / `HideDistanceRoutes` precedent). PII discipline: the handler SHALL log only the content-free event name (and, on failure, the exception class) — never the bearer token, the JWT `sub`, or the body.

#### Scenario: Enabling persists the opt-in
- **WHEN** an authenticated user sends `PATCH /api/v1/user/private-profile` with `{"privateProfile": true}`
- **THEN** the response is `200` AND the user's `private_profile_opt_in` is `TRUE`

#### Scenario: Disabling persists the opt-in
- **WHEN** an authenticated user whose `private_profile_opt_in` is `TRUE` sends `{"privateProfile": false}`
- **THEN** the response is `200` AND the user's `private_profile_opt_in` is `FALSE`

#### Scenario: Unauthenticated request is rejected
- **WHEN** `PATCH /api/v1/user/private-profile` is sent with no valid JWT
- **THEN** the response is `401` AND no `users` row is modified

#### Scenario: A Free user may store the opt-in (no read effect)
- **WHEN** a `free` user sends `{"privateProfile": true}`
- **THEN** the response is `200` AND `private_profile_opt_in` is `TRUE` AND (per the effectiveness requirement) the opt-in has no effect on any read surface while the user is `free`

#### Scenario: A malformed body is rejected
- **WHEN** an authenticated user sends a body with the `privateProfile` key missing or non-boolean
- **THEN** the response is `400` AND no `users` row is modified

#### Scenario: The write targets the JWT principal only (no IDOR)
- **WHEN** an authenticated user calls `PATCH /api/v1/user/private-profile`
- **THEN** the `UPDATE` targets the principal's own `users` row only AND the endpoint exposes no user-id path/body parameter that could target another user's row

### Requirement: Opting out switches the profile public immediately, clearing any pending privacy-flip

When the `PATCH` sets `private_profile_opt_in = FALSE`, the same single `UPDATE` SHALL ALSO set `privacy_flip_scheduled_at = NULL`. This realizes the "confirm switch public" action (`docs/03-UX-Design.md` § Downgrade flow privacy flip): a user inside the 72h privacy-flip grace window (downgraded, `private_profile_opt_in` still `TRUE`, `privacy_flip_scheduled_at` in the future) is effectively private via the grace short-circuit (`privacy_flip_scheduled_at > now()`), so clearing it is required for the opt-out to take effect — otherwise the toggle would be visibly broken during grace. An opt-IN write (`{"privateProfile": true}`) SHALL touch ONLY `private_profile_opt_in` (it MUST NOT set or clear `privacy_flip_scheduled_at`). The whole statement remains own-row (`WHERE id = :principal`) and is covered by the single `@allow-privacy-write: user_settings` annotation. This change does NOT modify `PrivacyFlipWorker` or the RevenueCat webhook.

#### Scenario: Opt-out during the grace window clears the scheduled flip
- **GIVEN** a user with `private_profile_opt_in = TRUE` AND `privacy_flip_scheduled_at` set to a future instant (mid-grace after a downgrade)
- **WHEN** they send `PATCH /api/v1/user/private-profile` with `{"privateProfile": false}`
- **THEN** the response is `200` AND `private_profile_opt_in` is `FALSE` AND `privacy_flip_scheduled_at` is `NULL` (so the effective-private formula resolves `FALSE` — the profile is public immediately)

#### Scenario: Opt-in does not touch the scheduled flip
- **GIVEN** a user with `privacy_flip_scheduled_at = NULL`
- **WHEN** they send `{"privateProfile": true}`
- **THEN** the response is `200` AND `private_profile_opt_in` is `TRUE` AND `privacy_flip_scheduled_at` remains `NULL` (the opt-in write writes only the opt-in column)

### Requirement: GET /api/v1/user/private-profile returns the toggle state for the Settings screen

The backend SHALL expose `GET /api/v1/user/private-profile` (Bearer JWT via `AUTH_PROVIDER_USER`) returning `{"privateProfile": <boolean>, "premium": <boolean>}` for the authenticated caller. `privateProfile` is the stored `users.private_profile_opt_in`; `premium` is whether the caller is **effectively Premium** (`subscription_status IN ('premium_active','premium_billing_retry')`, derived from the JWT principal with no extra read). The mobile Settings screen reads this on open to seed the toggle's checked state AND to decide interactive-toggle (Premium) vs Premium-upsell (Free). An unauthenticated request MUST be rejected with `401`.

#### Scenario: Returns the stored opt-in and premium status
- **WHEN** an authenticated `premium_active` user whose `private_profile_opt_in` is `TRUE` calls `GET /api/v1/user/private-profile`
- **THEN** the response is `200` with body `{"privateProfile": true, "premium": true}`

#### Scenario: Free caller reads premium=false regardless of the stored opt-in
- **WHEN** an authenticated `free` user (with any stored `private_profile_opt_in`) calls `GET /api/v1/user/private-profile`
- **THEN** the response is `200` AND `premium` is `false`

#### Scenario: Billing-retry caller reads premium=true
- **WHEN** an authenticated `premium_billing_retry` user calls `GET /api/v1/user/private-profile`
- **THEN** the response is `200` AND `premium` is `true` (billing-retry retains effective-Premium access)

#### Scenario: Unauthenticated read is rejected
- **WHEN** `GET /api/v1/user/private-profile` is called with no valid JWT
- **THEN** the response is `401`

### Requirement: private_profile_opt_in is effective only while the user is Premium (canonical formula, unchanged)

A user's private-profile preference SHALL be **effective** if and only if `private_profile_opt_in = TRUE AND subscription_status IN ('premium_active','premium_billing_retry')`, OR the 72h privacy-flip grace short-circuit applies (`privacy_flip_scheduled_at IS NOT NULL AND privacy_flip_scheduled_at > now()`). This is the canonical effective-private formula (`docs/05-Implementation.md` § Effective private), already implemented in `JdbcUserProfileReader` (the self `is_private` projection) and enforced in `premium-search` (the Premium private-profile gate). This change does NOT alter the formula or any read enforcement — it only adds the user-facing writer that sets the opt-in. A Free user with a stale `private_profile_opt_in = TRUE` is treated as NOT effectively private.

#### Scenario: Premium-active user with the opt-in is effective
- **WHEN** a user has `private_profile_opt_in = TRUE` AND `subscription_status = 'premium_active'`
- **THEN** the user's private-profile preference is effective

#### Scenario: Free user with a stale opt-in is not effective
- **WHEN** a user has `private_profile_opt_in = TRUE` AND `subscription_status = 'free'` AND no future `privacy_flip_scheduled_at`
- **THEN** the user's private-profile preference is NOT effective

#### Scenario: The existing search gate is the read enforcement (unchanged)
- **WHEN** an effectively-private Premium author A's posts are searched by a viewer V who does NOT follow A
- **THEN** A's posts are excluded from V's search results (the shipped `premium-search` Premium private-profile gate — this change adds no new read enforcement and removes none)

### Requirement: Broader read-hiding is out of scope (deferred)

This capability SHALL add ONLY the user-facing opt-in writer and the Settings toggle. It SHALL NOT add private-profile read-hiding to any surface that does not already implement it: the Nearby / Following / Global timelines, the profile page (`user-profile-read` identity + raw counts), post-detail, single-post read, the chat embedded-post card, and notification bodies SHALL be unchanged by this change. The private-profile read effect remains exactly the shipped `premium-search` follower-only gate. Their continued non-hiding is intentional (declared per docs/12 §3), not a gap this change fills. A future change MAY MODIFY this requirement to broaden private-profile semantics; until then neither is part of this capability.

#### Scenario: Timelines and profile reads are unaffected by the opt-in
- **WHEN** an effectively-private Premium user A's profile and posts are read via `GET /api/v1/users/{A}` and the Nearby/Following/Global timelines by a non-follower V
- **THEN** A's profile identity, raw follower/following counts, and timeline posts are returned exactly as before (no new private-hiding) — only `premium-search` gates A's posts for non-followers

#### Scenario: No new private-hiding field or endpoint is introduced
- **WHEN** this change ships
- **THEN** no read endpoint gains a new private-hiding behavior or field beyond the existing `premium-search` gate AND the self-only `is_private` projection in `user-profile-read` is unchanged
