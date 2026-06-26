## ADDED Requirements

### Requirement: Authenticated GET /admin/posts/{post_id}/edits renders the post's version history

The system SHALL serve an authenticated admin route `GET /admin/posts/{post_id}/edits` that renders a single post's complete edit-version history, composed from the live `posts` row plus its `post_edits` snapshots, newest-version first.

#### Scenario: Authenticated request renders the version table

- **WHEN** an authenticated admin requests `/admin/posts/{post_id}/edits` for a post that has been edited at least once
- **THEN** the response is the version-history page listing the live version followed by each prior snapshot, newest-first

#### Scenario: Unauthenticated request redirects to the login page

- **WHEN** an unauthenticated client requests `/admin/posts/{post_id}/edits`
- **THEN** the system redirects to the admin login page and renders no history

#### Scenario: The live post is rendered as the newest version

- **WHEN** the history is rendered for a post whose current `posts.content` differs from its most recent `post_edits.content_snapshot`
- **THEN** the current `posts.content` is shown as the newest ("Versi terbaru") version, above all snapshots, reflecting that `post_edits` holds only superseded content

#### Scenario: Versions are numbered over the composed, ordered list

- **WHEN** a post has N edit snapshots
- **THEN** the rendered list contains N+1 versions, numbered most-recent-first, with the live version first and the oldest snapshot last

### Requirement: A post with no edits renders its single live version, not an error

The system SHALL render a valid post that has zero `post_edits` rows as a single-version history (the live post), never as an error or an empty error page.

#### Scenario: Post with no edit history

- **WHEN** an authenticated admin requests the history for an existing post that has never been edited
- **THEN** the page renders exactly one version (the live post) and indicates there is no prior edit history

### Requirement: Unknown, malformed, or hard-deleted post renders an empty state safely

The system SHALL treat an unknown `post_id`, a non-UUID `post_id`, or a hard-deleted post as not-found and render an admin-styled empty state, never a 500 and never an injection vector.

#### Scenario: Unknown post id renders the empty state

- **WHEN** an authenticated admin requests the history for a `post_id` that matches no row
- **THEN** the system renders the "post not found / no history" empty state with a 200-class page (not a server error)

#### Scenario: Non-UUID post id is treated as not-found, not an error

- **WHEN** an authenticated admin requests `/admin/posts/{post_id}/edits` with a `post_id` segment that is not a valid UUID
- **THEN** the value is treated literally as not-found and the empty state renders, with no exception and no SQL injection

#### Scenario: Hard-deleted post yields the empty state

- **WHEN** the target post has been hard-deleted (so its `post_edits` rows are cascade-removed)
- **THEN** the history renders the not-found empty state rather than a partial or errored page

### Requirement: Keyset pagination over (post_id, edited_at DESC) with a fixed page size

The system SHALL paginate the snapshot list using keyset pagination over `post_edits (post_id, edited_at DESC)` at a fixed page size, served by the existing `post_edits_post_id_idx`, with the live version shown above the snapshots on the first page.

#### Scenario: Page is capped at the fixed page size

- **WHEN** a post has more snapshots than the fixed page size
- **THEN** the first page renders the live version plus at most one page of snapshots and exposes an "older" control

#### Scenario: Following the cursor returns the next-older, non-overlapping page

- **WHEN** the admin follows the "older" control
- **THEN** the next page renders the next-older snapshots with no overlap and no gap relative to the prior page

#### Scenario: Last page omits the older control

- **WHEN** the final page of snapshots is rendered
- **THEN** no "older" control is shown

#### Scenario: Malformed cursor falls back to the first page

- **WHEN** the request carries a malformed pagination cursor
- **THEN** the system renders the first page rather than erroring

#### Scenario: edited_at uniqueness gives a stable, tiebreaker-free keyset

- **WHEN** the snapshots are paginated
- **THEN** ordering is total and stable because `post_edits_temporal_idx` guarantees `edited_at` is unique per post, requiring no secondary tiebreaker

### Requirement: HTMX partial swap with plain-GET progressive enhancement

The system SHALL serve the history as an HTMX partial fragment for HTMX requests and as the full page for plain `GET` requests.

#### Scenario: HTMX request returns only the table fragment

- **WHEN** the request is an HTMX request (e.g. following the "older" control)
- **THEN** the response contains only the version-table fragment, suitable for an in-place swap

#### Scenario: Plain GET returns the full page

- **WHEN** the request is a plain `GET` (no HTMX headers)
- **THEN** the response is the full admin page with chrome, navigation, and the version table

### Requirement: Each version row surfaces content, timestamp, author deep-link, and a location-change indicator only

The system SHALL render, per version, the content, the `edited_at` timestamp (live version uses `posts.updated_at`/created), and the editing author as a deep-link to `/admin/users?q=`, and SHALL NOT render raw coordinates from `location_snapshot`.

#### Scenario: Author deep-links to the shipped user lookup

- **WHEN** a version row is rendered
- **THEN** the editing author is a link to `/admin/users?q=` for that user

#### Scenario: Location change is indicated without exposing coordinates

- **WHEN** a snapshot's `location_snapshot` differs from the adjacent newer version's location
- **THEN** the row shows a neutral "lokasi berubah" indicator and never renders the raw coordinates

#### Scenario: Unchanged location shows no location indicator

- **WHEN** a snapshot's location matches the adjacent newer version's location
- **THEN** no location-change indicator is shown for that row

### Requirement: All rendered values are HTML-escaped, never raw

The system SHALL HTML-escape every rendered value (content snapshots, usernames, identifiers) so that markup in user content cannot execute.

#### Scenario: Content containing markup is escaped, not executed

- **WHEN** a snapshot's content contains HTML or script markup
- **THEN** it is rendered as escaped text, not executed

### Requirement: The capability adds only a read route; mutation methods are unmapped and nothing is written

The system SHALL add only the read route; it SHALL NOT map any mutation method on the path, SHALL write no `admin_actions_log` row, SHALL mutate no data, and SHALL notify no user.

#### Scenario: Mutating methods on the path are not wired

- **WHEN** a `POST`, `PUT`, `PATCH`, or `DELETE` is sent to `/admin/posts/{post_id}/edits`
- **THEN** the route is not handled as a mutation (no such handler is mapped)

#### Scenario: Serving the viewer writes no audit row and mutates nothing

- **WHEN** the history page is served
- **THEN** no `admin_actions_log` row is written, no table is mutated, and no notification is sent

### Requirement: The viewer is accessible to every authenticated admin role

The system SHALL allow any authenticated admin role — including `read_only` — to view the post edit history, consistent with the other read-only admin viewers.

#### Scenario: read_only admin can view the history

- **WHEN** an authenticated `read_only` admin requests the history
- **THEN** the version-history page renders for them

### Requirement: Content is read from raw tables for moderation completeness

The system SHALL read `posts` and `post_edits` directly (not through `visible_*` views and without the block-exclusion join), so moderators see content regardless of shadow-ban, block, or auto-hide state.

#### Scenario: Shadow-banned or auto-hidden post is still visible to admins

- **WHEN** the target post is shadow-banned, auto-hidden, or authored by a blocked user
- **THEN** its full edit history still renders in the admin viewer
