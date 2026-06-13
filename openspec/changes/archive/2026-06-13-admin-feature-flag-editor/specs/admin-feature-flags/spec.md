## ADDED Requirements

### Requirement: Authenticated GET /admin/feature-flags renders the Feature Flag Admin panel

The admin panel SHALL expose `GET /admin/feature-flags` behind the standard admin session middleware. A request carrying a valid `__Host-admin_session` SHALL render the Feature Flag Admin page (frame 20). Serving the page SHALL be strictly read-only — it MUST NOT publish to Remote Config and MUST NOT write an `admin_actions_log` row. Any authenticated admin role MAY view the page.

#### Scenario: Authenticated request renders the current flag state
- **WHEN** an authenticated admin requests `GET /admin/feature-flags`
- **THEN** the response is `200` and renders the canonical flag catalog with each flag's current Server-template value

#### Scenario: Serving the page mutates nothing
- **WHEN** an authenticated admin loads `GET /admin/feature-flags`
- **THEN** no Remote Config publish occurs AND no `admin_actions_log` row is written

#### Scenario: Unauthenticated request redirects to the login page
- **WHEN** a request without a valid admin session calls `GET /admin/feature-flags`
- **THEN** the response is a redirect to `/admin/login` and no flag state is disclosed

### Requirement: The panel renders the canonical flag catalog with typed controls and the active environment

The page SHALL render exactly the canonical flag catalog: `image_upload_enabled`, `search_enabled`, `perspective_api_enabled`, `premium_username_customization_enabled`, `premium_like_cap_override` as boolean toggles; `attestation_mode` as a three-valued enum control (`enforce` / `warn` / `off`); and `moderation_match_threshold` as an editable integer. It SHALL render `moderation_profanity_list` and `moderation_uu_ite_list` as read-only summaries (entry count + template version) with no content-editing control. It SHALL surface the active environment (e.g., `STAGING` / `PRODUCTION`) of the Remote Config project the running service is bound to. All rendered Remote Config values SHALL be HTML-escaped.

#### Scenario: Catalog renders with the correct control type per parameter
- **WHEN** the page renders
- **THEN** `attestation_mode` shows a three-valued enum control (not a boolean toggle) AND `moderation_match_threshold` shows an integer input AND the five boolean flags show on/off toggles

#### Scenario: Active environment is shown
- **WHEN** the running admin service is bound to the staging Firebase project
- **THEN** the page surfaces `STAGING` as the active environment

#### Scenario: A Remote Config value containing markup is HTML-escaped
- **WHEN** a rendered parameter value contains HTML metacharacters
- **THEN** the rendered output is HTML-escaped and not interpreted as markup

### Requirement: A single-flag write publishes to the Server template with a mandatory reason and exactly one audit row

A state-changing write SHALL target a single parameter, carry the new value and a non-blank free-text `reason`, and on success publish the new value to the Remote Config **Server** template and write exactly one immutable `admin_actions_log` row with `action_type = 'feature_flag_toggled'`, the prior value in `before_state`, the new value in `after_state`, and the `reason`. The audit row and the publish SHALL be consistent — a write that does not publish MUST NOT leave an audit row.

#### Scenario: A valid write publishes and audits exactly once
- **WHEN** an authorized admin submits a new value for a flag with a non-blank reason and the gates pass
- **THEN** the value is published to the Server template AND exactly one `admin_actions_log` row is written with `action_type = 'feature_flag_toggled'`, `before_state` = prior value, `after_state` = new value, and the supplied reason

#### Scenario: A write with a blank or missing reason is rejected
- **WHEN** an admin submits a flag change with an empty or whitespace-only reason
- **THEN** the write is rejected with a validation error AND no Remote Config publish occurs AND no `admin_actions_log` row is written

#### Scenario: A no-op write (value unchanged) is rejected without publish or audit
- **WHEN** an admin submits a value identical to the flag's current Server-template value
- **THEN** the write is rejected as a no-op AND no publish occurs AND no `admin_actions_log` row is written

### Requirement: Flag writes are CSRF-protected

Every state-changing flag write SHALL require a valid `X-CSRF-Token` matching the session's `csrf_token_hash`. A missing or mismatched token SHALL return `403`, perform no publish, and (on mismatch) write an `admin_csrf_violation` audit row per the established admin CSRF contract. This `admin_csrf_violation` security-audit row is distinct from the `feature_flag_toggled` action audit and is the **one intended exception** to the "no audit row on rejection" rule the other write gates (role, rate-limit, validation, stale-version) follow — those write no row of any kind on rejection.

#### Scenario: A write without a CSRF token is rejected
- **WHEN** a flag write arrives without an `X-CSRF-Token` header
- **THEN** the response is `403` AND no Remote Config publish occurs

#### Scenario: A write with a mismatched CSRF token is rejected and audited
- **WHEN** a flag write arrives with an `X-CSRF-Token` that does not match the session
- **THEN** the response is `403` AND an `admin_csrf_violation` audit row is written AND no flag publish occurs

### Requirement: Flag writes are role-gated to owner/admin

Flag writes (all parameters in the catalog, including the security-sensitive `attestation_mode`) SHALL require the acting admin to hold the `owner` or `admin` role. A `moderator` (or any lesser role) attempting a write SHALL be rejected with `403`, with no publish and no `feature_flag_toggled` audit row. Viewing the page (GET) remains available to any authenticated admin role.

#### Scenario: A moderator write is rejected
- **WHEN** an admin holding only the `moderator` role submits any flag write
- **THEN** the response is `403` AND no publish occurs AND no `feature_flag_toggled` audit row is written

#### Scenario: An owner or admin write is permitted
- **WHEN** an admin holding `owner` or `admin` submits an otherwise-valid flag write
- **THEN** the write proceeds through the remaining gates

#### Scenario: A moderator may still view the panel
- **WHEN** an admin holding only `moderator` requests `GET /admin/feature-flags`
- **THEN** the page renders read-only flag state with no write controls enabled for that role

### Requirement: Flag writes are capped at 5 per admin per trailing hour, distinct from the destructive-action cap

A per-admin rate limit SHALL cap `feature_flag_toggled` writes at 5 within the trailing hour, counted from the `admin_actions_log` ledger. This is a bucket separate from the 20/hour destructive-action cap: feature-flag writes SHALL NOT be counted by the destructive-action limiter, and the destructive-action count SHALL NOT consume the feature-flag budget. A write at or over the cap SHALL be rejected with no Remote Config publish and no audit row (so the rejected attempt does not itself advance the count). The trailing-hour COUNT and the success-path audit INSERT SHALL execute on a single database connection (mirroring `DestructiveActionRateLimiter`'s caller-supplied-`Connection` contract) so the count cannot drift from the ledger it gates.

#### Scenario: The sixth flag write within an hour is rejected
- **WHEN** an admin has 5 `feature_flag_toggled` rows within the trailing hour and submits a sixth flag write
- **THEN** the write is rejected AND no publish occurs AND no `admin_actions_log` row is written

#### Scenario: The fifth flag write within an hour succeeds
- **WHEN** an admin has 4 `feature_flag_toggled` rows within the trailing hour and submits a fifth valid flag write
- **THEN** the write passes the rate-limit gate AND (the remaining gates passing) the value is published and one audit row is written

#### Scenario: The feature-flag bucket is independent of the destructive-action cap
- **WHEN** an admin has performed destructive actions counted by the 20/hour cap
- **THEN** those actions do not reduce the admin's 5/hour feature-flag budget, and vice versa

### Requirement: A moderation_match_threshold write is validated to the [1, 10000] range

A write to `moderation_match_threshold` SHALL accept only an integer within `[1, 10000]`. An out-of-range or non-integer value SHALL be rejected inline with a validation error, with no publish and no audit row. The reader-side clamp in `content-moderation-keyword-lists` remains the runtime safety net against any value that nonetheless reaches Remote Config.

#### Scenario: An out-of-range threshold is rejected
- **WHEN** an admin submits `moderation_match_threshold = 0` (or any value outside `[1, 10000]`)
- **THEN** the write is rejected with a validation error AND no publish occurs AND no audit row is written

#### Scenario: A non-integer threshold is rejected
- **WHEN** an admin submits a non-integer `moderation_match_threshold`
- **THEN** the write is rejected with a validation error AND no publish occurs

#### Scenario: An in-range threshold is accepted
- **WHEN** an authorized admin submits `moderation_match_threshold = 50` with a reason and the gates pass
- **THEN** the value is published to the Server template AND one `feature_flag_toggled` audit row is written

#### Scenario: The inclusive range boundaries are honored
- **WHEN** an admin submits `moderation_match_threshold = 1` or `= 10000` (the inclusive bounds) with a reason and the gates pass
- **THEN** each is accepted and published AND a submission of `= 10001` is rejected as out-of-range with no publish and no audit row

### Requirement: A flag write validates the submitted value against the parameter type

A write SHALL validate the submitted value against the target parameter's type before any publish: `attestation_mode` accepts only `enforce` / `warn` / `off`; a boolean flag accepts only a boolean value. An invalid value SHALL be rejected inline with no publish and no `feature_flag_toggled` audit row.

#### Scenario: Each attestation_mode value is accepted
- **WHEN** an authorized admin submits `attestation_mode` = `enforce`, `warn`, or `off` (each in turn) with a reason and the gates pass
- **THEN** the submitted value is published to the Server template AND audited once

#### Scenario: An unknown attestation_mode value is rejected
- **WHEN** an admin submits `attestation_mode` = a value outside {`enforce`, `warn`, `off`}
- **THEN** the write is rejected inline with no publish AND no `feature_flag_toggled` audit row

#### Scenario: A non-boolean value for a boolean flag is rejected
- **WHEN** an admin submits a non-boolean value for a boolean flag (e.g. `search_enabled = maybe`)
- **THEN** the write is rejected inline with no publish AND no `feature_flag_toggled` audit row

### Requirement: Publishes use optimistic concurrency to prevent silent clobbering

A flag publish SHALL carry the Remote Config Server-template version/etag observed when the page was rendered. If the Server template has changed since (a concurrent publish), the publish SHALL be rejected as stale with a retry prompt, performing no overwrite and writing no `feature_flag_toggled` audit row.

#### Scenario: A stale publish is rejected, not clobbered
- **WHEN** the Server template version has advanced since the page was rendered and an admin submits a write against the stale version
- **THEN** the publish is rejected as stale AND the concurrent change is not overwritten AND no `feature_flag_toggled` audit row is written

#### Scenario: A current-version publish succeeds
- **WHEN** the submitted version matches the current Server-template version and the gates pass
- **THEN** the publish succeeds and is audited once

### Requirement: The panel degrades to read-only when Remote Config write credentials are absent

When the service is not configured with Remote Config write credentials, the publish seam SHALL report write-unavailable (typed failure, never a thrown crash). In that state `GET /admin/feature-flags` SHALL render the current flag values read-only with disabled controls and an inline notice, and any attempted write SHALL fail safely with no publish, no audit row, and no `500`.

#### Scenario: Write-unconfigured renders read-only
- **WHEN** Remote Config write credentials are not configured and an admin loads the page
- **THEN** the page renders current values read-only with disabled controls and a notice that writes are unavailable

#### Scenario: A write attempted while write-unconfigured fails safely
- **WHEN** a write is submitted while Remote Config write credentials are absent
- **THEN** the request fails safely with no publish AND no `admin_actions_log` row AND no `500` response

### Requirement: Wordlist array-content editing is out of scope and guarded read-only

This capability SHALL NOT expose any endpoint or control that mutates the array *content* of `moderation_profanity_list` or `moderation_uu_ite_list`. Those two lists SHALL be presented read-only (entry count + template version). Editing their content remains out of scope, deferred to the separate `admin-moderation-wordlist-editor` change; the Firebase Console path stays the interim for list content.

#### Scenario: No list-content mutation surface exists
- **WHEN** the Feature Flag Admin surface is exercised
- **THEN** there is no endpoint or control that adds, removes, or edits entries within `moderation_profanity_list` or `moderation_uu_ite_list`

#### Scenario: The two wordlists render read-only
- **WHEN** the page renders the moderation parameters
- **THEN** `moderation_profanity_list` and `moderation_uu_ite_list` appear as read-only summaries (count + version) with no content editor, while `moderation_match_threshold` remains editable
