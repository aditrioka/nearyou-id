## MODIFIED Requirements

### Requirement: Radius bounds

`radius_m` SHALL be validated against the **discrete allowed set** `{10000, 20000, 50000, 100000}` (the 10 / 20 / 50 / 100 km filter positions per `docs/02-Product.md` § Nearby Timeline). A value not in this set — whether out of range or an in-range non-member (e.g. `15000`, `30000`) — MUST yield HTTP 400 with error code `radius_out_of_bounds`. This narrows the prior continuous `[100, 50000]` contract; the 100 km position is now valid and intermediate values are now rejected (no shipped client sends a non-member value).

#### Scenario: Radius below the set rejected
- **WHEN** `radius_m=50`
- **THEN** the response is HTTP 400 with `error.code = "radius_out_of_bounds"`

#### Scenario: In-range non-member rejected
- **WHEN** `radius_m=15000`
- **THEN** the response is HTTP 400 with `error.code = "radius_out_of_bounds"`

#### Scenario: Above the set rejected
- **WHEN** `radius_m=200000`
- **THEN** the response is HTTP 400 with `error.code = "radius_out_of_bounds"`

#### Scenario: Each set member is accepted for radius bounds
- **WHEN** `radius_m` is `10000`, `20000`, `50000`, or `100000` (from a Premium caller)
- **THEN** the request is not rejected for radius bounds (HTTP is not 400 `radius_out_of_bounds`)

## ADDED Requirements

### Requirement: Premium radius gating

`GET /api/v1/timeline/nearby` SHALL gate non-default radii behind Premium. A **Free** principal — `subscription_status` NOT in `{premium_active, premium_billing_retry}` — MAY use only `radius_m=20000`; any other set member (`10000`, `50000`, `100000`) from a Free principal MUST yield HTTP 403 with error code `radius_premium_only`. A **Premium** principal (`subscription_status` in `{premium_active, premium_billing_retry}`, the same predicate as hide-distance at `TimelineRoutes.kt`) MAY use any set member. The tier SHALL be read from `UserPrincipal.subscriptionStatus` with NO per-request `users` SELECT (preserving the timeline-read-rate-limit "zero `users` SELECTs in the handler" invariant). Both the set-membership check (→ 400) and the tier gate (→ 403) SHALL run BEFORE the rate-limiter pre-check, so a rejected request never consumes a Free user's rolling or per-session read quota.

#### Scenario: Free caller at the default radius is admitted
- **WHEN** a Free principal calls Nearby with `radius_m=20000`
- **THEN** the request is not rejected for tier (no `radius_premium_only` 403) and proceeds to the normal Nearby flow

#### Scenario: Free caller at a Premium radius is 403'd
- **WHEN** a Free principal calls Nearby with `radius_m=50000` (a valid set member)
- **THEN** the response is HTTP 403 with `error.code = "radius_premium_only"`

#### Scenario: Premium caller may use any set member
- **WHEN** a principal with `subscription_status = premium_active` calls Nearby with `radius_m=100000`
- **AND** separately a principal with `subscription_status = premium_billing_retry` calls Nearby with `radius_m=10000`
- **THEN** neither is rejected for tier (no `radius_premium_only` 403)

#### Scenario: A rejected radius does not burn the Free read quota
- **GIVEN** a Free principal with read-quota accounting observable
- **WHEN** the caller requests `radius_m=50000` and receives the `radius_premium_only` 403 (or requests an out-of-set value and receives the `radius_out_of_bounds` 400)
- **THEN** the rolling/per-session read counters are NOT incremented (the gate runs ahead of the rate-limiter pre-check)

#### Scenario: Tier gate performs no users SELECT
- **WHEN** the Nearby handler evaluates the radius tier gate
- **THEN** the tier is read from the auth principal (`UserPrincipal.subscriptionStatus`) and the handler issues zero `users`-table SELECTs for the gate
