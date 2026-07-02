# ads-config Specification

## Purpose
The server-authoritative ad-serving configuration read — the operator-controllable gate that makes Free-tier ad serving BUILT-but-OFF until launch. `GET /api/v1/config/ads` returns the viewer's effective `{adsEnabled, timelineFrequency}`: the global `ads_enabled` kill-switch is sourced from the Remote-Config→Redis flag seam (`{scope:remote_config}:{flag:ads_enabled}`) with a 30s per-flag short-TTL emergency override (fail-closed FALSE), and premium viewers (`subscription_status ∈ {premium_active, premium_billing_retry}` — the access-control formula, grace keeps the ad-free benefit) are suppressed server-side so the client never needs a premium boolean. No schema, no migration, no new admin surface — the flag is operated through the existing feature-flag tooling; mobile (`mobile-ads`) initializes the ad SDK and interleaves placements ONLY when this endpoint says so.
## Requirements

### Requirement: Ad-serving configuration read endpoint

The backend SHALL expose `GET /api/v1/config/ads` returning the viewer's ad-serving configuration as `{ "ads_enabled": Boolean, "timeline_frequency": Int }`, where `ads_enabled` is the effective gate for THIS viewer and `timeline_frequency` is the number of feed posts between native-ad slots. The route SHALL be a thin route delegating to an ads-config service (no SQL in the route, docs/11 §3.1) and SHALL require an authenticated session.

#### Scenario: Authenticated Free viewer reads the config with the flag ON

- **WHEN** an authenticated non-premium user calls `GET /api/v1/config/ads` while the `ads_enabled` flag is `true`
- **THEN** the response is `200` with `ads_enabled = true` and a `timeline_frequency` in the 5–7 range

#### Scenario: Unauthenticated request is rejected

- **WHEN** `GET /api/v1/config/ads` is called without a valid session
- **THEN** the response is `401` and no configuration is returned

### Requirement: Ad serving is OFF by default via the `ads_enabled` kill-switch

`ads_enabled` SHALL be sourced from the Remote-Config→Redis flag seam at `remote_config:{flag:ads_enabled}` (docs/11 §3.3) and SHALL default to `false` when the flag is absent or `false`. The endpoint SHALL NOT serve `ads_enabled = true` to any viewer unless the flag is explicitly `true`. This is the launch kill-switch (BUILT-but-OFF, the `image_upload_enabled` precedent).

#### Scenario: Flag absent or false yields ads OFF

- **WHEN** the `ads_enabled` flag is unset or `false` and any authenticated user reads the config
- **THEN** `ads_enabled` is `false`

#### Scenario: Operator flip to ON propagates within the staleness budget

- **WHEN** the operator sets the `ads_enabled` flag to `true`
- **THEN** subsequent config reads return `ads_enabled = true` once the cached flag's short TTL elapses

### Requirement: The `ads_enabled` flag carries a short-TTL emergency override

Because `ads_enabled` is an emergency kill-switch, its Redis cache entry SHALL use a per-flag short TTL of 30–60 seconds (the docs/11 §3.3 mandate for kill-switch flags), overriding the default 5-minute Remote-Config staleness budget, so an OFF flip takes effect in under a minute without a deploy.

#### Scenario: Flip to OFF takes effect within the short TTL

- **WHEN** the operator sets `ads_enabled` to `false` while ads are live
- **THEN** config reads return `ads_enabled = false` within the 30–60 second TTL window, with no deploy required

### Requirement: Premium viewers are server-suppressed from ads

For a viewer with active premium access — `subscription_status ∈ {premium_active, premium_billing_retry}` (the access-control formula; the grace state retains Premium access per docs/08 Phase 4 item 4, so it keeps the ad-free benefit — NOT the stricter `premium_active`-only badge formula) — the endpoint SHALL return `ads_enabled = false` regardless of the global flag. Premium suppression is decided server-side (docs/12 single source of truth), so the client never needs a premium boolean to suppress ads.

#### Scenario: Premium viewer gets ads OFF even with the flag ON

- **WHEN** an authenticated `premium_active` user reads the config while the global `ads_enabled` flag is `true`
- **THEN** `ads_enabled` is `false`

#### Scenario: Grace-period subscriber keeps the ad-free benefit

- **WHEN** a `premium_billing_retry` (7-day grace) user reads the config while the global flag is `true`
- **THEN** `ads_enabled` is `false`

### Requirement: Ad configuration adds no schema and no admin surface

This change SHALL NOT add any database table, column, or migration, and SHALL NOT add a new admin UI surface. The `ads_enabled` flag is managed through the existing `admin-feature-flags` editor + Remote Config. Adding an ads-specific admin screen or an ads table is out of scope and tracked as future work if ever needed.

#### Scenario: No migration ships with this capability

- **WHEN** this change is implemented
- **THEN** no new Flyway migration file is added for ad configuration and the `ads_enabled` flag is editable via the existing feature-flag admin tooling
