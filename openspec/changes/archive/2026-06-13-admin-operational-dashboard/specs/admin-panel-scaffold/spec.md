## MODIFIED Requirements

### Requirement: Scaffold landing renders greeting and live stat cards

The `/admin/` index page SHALL render the Operational Dashboard (admin mockup board frame 3; the full operational-widget behavior is owned by the `admin-operational-dashboard` capability), whose first row retains, per frame 2: a greeting heading `Welcome back, {display name}` using the authenticated admin's display name; three quick-link stat cards populated with live values queried at request time — **Report queue** (count of pending reports + relative age of the oldest pending report, linking to `/admin/reports`), **Rejected identifiers** (count of rows created in the last 24 hours + the most frequent rejection reason over that window, ties broken deterministically, linking to `/admin/rejected-identifiers`), **Audit log** (count of `admin_actions_log` rows for the current UTC day + the `action_type` of the newest row, linking to `/admin/actions-log`); and an informational banner describing the shell's CSRF posture. Beyond these three quick-link cards the index page SHALL additionally render the operational widgets defined by the `admin-operational-dashboard` capability (posts/signups/reports volume, top active cities, database size). The previous static description cards — including the "User moderation" card, which frame 2 drops from the landing — SHALL NOT be rendered. All time arithmetic SHALL use UTC. The last-action slot shows the newest row **all-time** (the operator wants to know the last thing that happened, even if it was yesterday); its placeholder renders only when the log is empty. Empty-state values (no pending reports, no rejections in 24 h, empty audit log) SHALL render as zero counts with a placeholder (e.g. `—`) for the age/reason/last-action slot, not error or omit the card.

#### Scenario: Stat cards show live values

- **GIVEN** an authenticated session
- **AND** the database contains 4 pending reports (oldest created 2 hours ago), 12 `rejected_identifiers` rows in the last 24 hours of which `age_under_18` is the most frequent reason, and 9 `admin_actions_log` rows today (UTC) with the newest having `action_type = 'user_suspended'`
- **WHEN** `GET /admin/` is served
- **THEN** the Report queue card SHALL show a pending count of 4 and an oldest-pending age derived from the 2-hour-old row
- **AND** the Rejected identifiers card SHALL show 12 and `age_under_18`
- **AND** the Audit log card SHALL show 9 and `user_suspended`

#### Scenario: Top-reason tie breaks deterministically

- **GIVEN** an authenticated session
- **AND** the last 24 hours contain an equal count of `rejected_identifiers` rows for two reasons (e.g. `age_under_18` and `duplicate_identifier`)
- **WHEN** `GET /admin/` is served
- **THEN** the Rejected identifiers card SHALL show the alphabetically-first of the tied reasons (per the deterministic `ORDER BY count DESC, reason ASC` tie-break)

#### Scenario: Empty database renders zero-state cards

- **GIVEN** an authenticated session against a database with no pending reports, no `rejected_identifiers` rows in the last 24 hours, and no `admin_actions_log` rows today
- **WHEN** `GET /admin/` is served
- **THEN** the response status SHALL be 200
- **AND** each of the three stat cards SHALL render with a zero count and a placeholder value in its secondary slot

#### Scenario: Audit card with only-yesterday rows shows zero count but the real last action

- **GIVEN** an authenticated session against a database whose `admin_actions_log` rows are all older than the current UTC day
- **WHEN** `GET /admin/` is served
- **THEN** the Audit log card SHALL show an actions-today count of 0
- **AND** the last-action slot SHALL show the newest row's `action_type` (not the placeholder)

#### Scenario: Landing drops the static User moderation card

- **GIVEN** an authenticated session
- **WHEN** `GET /admin/` is served
- **THEN** the rendered HTML SHALL NOT contain a "User moderation" quick-link card (the Users page remains reachable from the sidebar)
