## ADDED Requirements

### Requirement: users.hide_distance_opt_in column

The `users` table SHALL carry a `hide_distance_opt_in BOOLEAN NOT NULL DEFAULT FALSE` column, added by an additive Flyway migration (the next available version), mirroring the `private_profile_opt_in` column shape. The migration MUST NOT backfill or transform existing rows — the column default applies to every existing user.

#### Scenario: Column exists with a safe default
- **WHEN** the migration has run AND a pre-existing user row is read
- **THEN** that row's `hide_distance_opt_in` is `FALSE`

#### Scenario: Additive — no other user column changes
- **WHEN** the migration is applied
- **THEN** it adds only `hide_distance_opt_in` AND removes/renames/retypes no existing `users` column

### Requirement: PATCH /api/v1/user/hide-distance writes the flag for the authenticated caller

The backend SHALL expose `PATCH /api/v1/user/hide-distance` (Bearer JWT via `AUTH_PROVIDER_USER`) accepting a body `{"hideDistance": <boolean>}` and persisting it to the caller's `users.hide_distance_opt_in` with a single-statement `UPDATE`. A successful write SHALL return HTTP `200` echoing the stored value. The write SHALL be permitted for any caller regardless of `subscription_status` (the flag's *effect* is read-gated per the effectiveness requirement; storing intent is harmless and mirrors how a Free user may hold a stale `private_profile_opt_in = TRUE` after a downgrade). The endpoint SHALL require a valid JWT and reject an unauthenticated request with `401`. The column is neither `username` nor `private_profile_opt_in`, so the write is outside both the username-write and privacy-flag-write lint allowlists and requires no `@allow-*` annotation. The target user is the JWT principal (`principal.userId`) ONLY — the endpoint accepts NO user-id path or body parameter, so it has no IDOR surface — and it enforces the standard request-body transport cap (mirroring the `ConsentRoutes` precedent).

#### Scenario: Enabling persists the flag
- **WHEN** an authenticated user sends `PATCH /api/v1/user/hide-distance` with `{"hideDistance": true}`
- **THEN** the response is `200` AND the user's `hide_distance_opt_in` is `TRUE`

#### Scenario: Disabling persists the flag
- **WHEN** an authenticated user whose `hide_distance_opt_in` is `TRUE` sends `{"hideDistance": false}`
- **THEN** the response is `200` AND the user's `hide_distance_opt_in` is `FALSE`

#### Scenario: Unauthenticated request is rejected
- **WHEN** `PATCH /api/v1/user/hide-distance` is sent with no valid JWT
- **THEN** the response is `401` AND no `users` row is modified

#### Scenario: A Free user may store the flag (no read effect)
- **WHEN** a `free` user sends `{"hideDistance": true}`
- **THEN** the response is `200` AND `hide_distance_opt_in` is `TRUE` AND (per the effectiveness requirement) the flag has no effect on any distance rendering while the user is `free`

#### Scenario: The write targets the JWT principal only (no IDOR)
- **WHEN** an authenticated user calls `PATCH /api/v1/user/hide-distance`
- **THEN** the `UPDATE` targets the principal's own `users` row only AND the endpoint exposes no user-id path/body parameter that could target another user's row

### Requirement: GET /api/v1/user/hide-distance returns the toggle state for the Settings screen

The backend SHALL expose `GET /api/v1/user/hide-distance` (Bearer JWT via `AUTH_PROVIDER_USER`) returning `{"hideDistance": <boolean>, "premium": <boolean>}` for the authenticated caller. `hideDistance` is the stored `users.hide_distance_opt_in`; `premium` is whether the caller is **effectively Premium** (`subscription_status IN ('premium_active','premium_billing_retry')`, derived from the JWT principal with no extra read). The mobile Settings screen reads this on open to seed the toggle's checked state AND to decide interactive-toggle (Premium) vs Premium-upsell (Free). An unauthenticated request MUST be rejected with `401`.

#### Scenario: Returns the stored flag and premium status
- **WHEN** an authenticated `premium_active` user whose `hide_distance_opt_in` is `TRUE` calls `GET /api/v1/user/hide-distance`
- **THEN** the response is `200` with body `{"hideDistance": true, "premium": true}`

#### Scenario: Free caller reads premium=false regardless of the stored flag
- **WHEN** an authenticated `free` user (with any stored `hide_distance_opt_in`) calls `GET /api/v1/user/hide-distance`
- **THEN** the response is `200` AND `premium` is `false`

#### Scenario: Unauthenticated read is rejected
- **WHEN** `GET /api/v1/user/hide-distance` is called with no valid JWT
- **THEN** the response is `401`

### Requirement: hide_distance_opt_in is effective only while the user is Premium

A user's hide-distance preference SHALL be **effective** if and only if `hide_distance_opt_in = TRUE AND subscription_status IN ('premium_active','premium_billing_retry')`. This mirrors the EFFECTIVE-private formula in `user-profile-read` (active OR billing-retry — `premium_billing_retry` is an effective-Premium state), deliberately broader than the badge-only `premium_active` test. A Free user with a stale `hide_distance_opt_in = TRUE` is treated as OFF.

#### Scenario: Premium-active user with the flag is effective
- **WHEN** a user has `hide_distance_opt_in = TRUE` AND `subscription_status = 'premium_active'`
- **THEN** the user's hide-distance preference is effective

#### Scenario: Billing-retry user with the flag is effective
- **WHEN** a user has `hide_distance_opt_in = TRUE` AND `subscription_status = 'premium_billing_retry'`
- **THEN** the user's hide-distance preference is effective (billing-retry retains effective-Premium access)

#### Scenario: Free user with a stale flag is not effective
- **WHEN** a user has `hide_distance_opt_in = TRUE` AND `subscription_status = 'free'`
- **THEN** the user's hide-distance preference is NOT effective

### Requirement: Hide-distance symmetrically suppresses the Nearby distance number

On the Nearby feed (`GET /api/v1/timeline/nearby`), the per-post distance number SHALL be suppressed for a (post-author, viewer) pair if and only if the **author's** preference is effective OR the **viewer's** preference is effective (the rule is symmetric: an author who hides loses distance display to all viewers; a viewer who hides loses distance display on every post they see). Suppression affects the distance **number only** — the `city_name` field, the global 5km distance floor, the radius filter, and the `(created_at DESC, id DESC)` time ordering are all unaffected (so suppression leaks no ordering signal). This rule applies identically on both Nearby query arms (the visible arm and the self arm per `shadow-ban-feed-self-visibility`); on the self arm author == viewer, so the two terms coincide.

#### Scenario: Author-on hides distance for every viewer
- **WHEN** post author A has an effective hide-distance preference AND viewer V (no preference) sees A's post in their Nearby feed
- **THEN** the distance number for A's post is suppressed for V AND the post's `city_name` is still present

#### Scenario: Viewer-on hides distance on every post the viewer sees
- **WHEN** viewer V has an effective hide-distance preference AND sees posts by authors who have NO preference
- **THEN** the distance number is suppressed on every post in V's Nearby feed

#### Scenario: Both off — distance is shown
- **WHEN** neither the author nor the viewer has an effective hide-distance preference
- **THEN** the post's distance number is present (raw meters, per `nearby-timeline`)

#### Scenario: Ordering and radius filter are unaffected by suppression
- **WHEN** a viewer with an effective hide-distance preference loads Nearby AND the same viewer loads Nearby with the preference off
- **THEN** the set of posts and their `(created_at DESC, id DESC)` order are identical across both loads (only the presence of the distance number differs)

#### Scenario: The 5km floor still applies to a shown distance
- **WHEN** a distance number is NOT suppressed AND the fuzzed distance is below 5km
- **THEN** the rendered distance honors the `distance-rendering` 5km floor (unchanged)

### Requirement: Suppression omits the distance from the wire server-side; the shared renderer stays pure

When the symmetric rule suppresses a Nearby post's distance, the backend SHALL omit the `distanceM` field from that post's response object (the number MUST NOT be sent and hidden only on the client). The shared `:shared:distance` `DistanceRenderer.render(Double): String` SHALL remain a pure formatter — it is NOT modified and is NOT given a "hidden" mode; the suppression is a field-omission decision upstream of rendering.

#### Scenario: Suppressed distance is absent on the wire
- **WHEN** a Nearby post's distance is suppressed for the requesting viewer
- **THEN** that post's response object contains no `distanceM` key (omitted via the app-wide `explicitNulls = false`) — neither a number nor `null`

#### Scenario: DistanceRenderer.render signature is unchanged
- **WHEN** `DistanceRenderer.render` is referenced from `:shared:distance`
- **THEN** its signature remains `(Double) -> String` with no hidden/visibility parameter

### Requirement: Scope is Nearby only — every other surface stays distance-free

Hide-distance SHALL affect only the Nearby distance number. Every other surface already carries no viewer-relative distance and SHALL continue to: the Following and Global feeds emit no distance field, post-detail / single-post / profile / search results / the chat embedded-post card carry no distance, and notification push bodies never carry distance. The toggle SHALL NOT add, remove, or alter any field on those surfaces (it is a verified no-op there, satisfying the docs/01 cross-surface checklist).

#### Scenario: Following and Global are unaffected by the flag
- **WHEN** a viewer with an effective hide-distance preference loads the Following feed and the Global feed
- **THEN** neither response contains a `distanceM` / `distance_m` field (unchanged — those feeds never carried distance) AND no other field is altered by the flag

#### Scenario: Post-detail stays distance-free regardless of the flag
- **WHEN** a viewer with an effective hide-distance preference reads a single post via `GET /api/v1/posts/{post_id}`
- **THEN** the response carries no viewer-relative distance number (unchanged — post-detail has no viewer-location context)

#### Scenario: Push notification bodies never carry distance
- **WHEN** any notification push is composed for a user (with or without the flag)
- **THEN** the push body contains no distance value

### Requirement: Premium Tenure Counter and non-Nearby distance display are out of scope (deferred)

This capability SHALL NOT implement the Premium Tenure Counter (a separate docs/01 Premium feature) and SHALL NOT add viewer-relative distance to post-detail, profile, search, or the chat embedded-post card — those surfaces remaining distance-free is intentional, not a gap this change fills. A future change MAY MODIFY this requirement to introduce either, but until then neither is part of hide-distance.

#### Scenario: No tenure-counter surface is introduced
- **WHEN** this change ships
- **THEN** no premium-tenure-counter field, endpoint, or UI is added by it

#### Scenario: Non-Nearby surfaces are not given a new distance number
- **WHEN** this change ships
- **THEN** post-detail / profile / search / chat-embedded responses gain no new viewer-relative distance field
