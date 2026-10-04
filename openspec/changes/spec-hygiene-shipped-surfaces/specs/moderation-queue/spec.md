## RENAMED Requirements

- FROM: `### Requirement: No reader endpoint in V9`
- TO: `### Requirement: moderation_queue is read only by authenticated admin-panel surfaces`

## MODIFIED Requirements

### Requirement: moderation_queue is read only by authenticated admin-panel surfaces

V9 shipped `moderation_queue` write-only (no reader endpoint); the Phase 3.5 admin panel now owns every reader. No user-facing endpoint (any `/api/v1/*` route serving the mobile clients) SHALL read or return `moderation_queue` rows. The only HTTP surfaces that read `moderation_queue` SHALL be routes under `/admin/*` behind the admin session middleware (and its CSRF gate for writes), each owned by its own admin capability:

- `admin-report-queue` — `GET /admin/reports` attaches the representative `moderation_queue` context to the report-queue listing, and its in-row resolution actions (`POST /admin/reports/{id}/resolve`, `POST /admin/moderation-queue/{id}/resolve`) read the queue row they transition.
- `admin-premium-username-oversight` — `GET /admin/username-oversight` lists the `username_flagged` rows, and `POST /admin/username-oversight/flags/{queue_id}/resolve` reads the row it resolves.

A new reader SHALL land as an admin capability under `/admin/*`, never as a user-facing route.

#### Scenario: No user-facing route returns moderation_queue rows

- **WHEN** inspecting the backend's `/api/v1/*` route handlers and the repositories they call
- **THEN** none reads `moderation_queue` (every `SELECT` against it sits behind an `/admin/*` route), so no user-facing response can contain `moderation_queue` rows

#### Scenario: The admin readers sit behind the admin session

- **WHEN** a request without a valid admin session calls `GET /admin/reports` or `GET /admin/username-oversight`
- **THEN** the response redirects to `/admin/login` AND no `moderation_queue` content is disclosed
