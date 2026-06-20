## ADDED Requirements

### Requirement: CSAM detection-log viewer

The Admin Panel SHALL serve a read-only CSAM detection-log at `GET /admin/csam`, listing `csam_detection_archive` rows keyset-paginated newest-first over `(created_at, id)`. It SHALL support composable filters — `source` (`cf_worker` | `admin_manual`), Kominfo status (`pending` = `kominfo_reported_at IS NULL`, `filed` = `kominfo_reported_at IS NOT NULL`), and a UTC `from`–`to` `created_at` range — and SHALL render an HTML-escaped HTMX fragment when requested via HTMX and a full page on a plain `GET`. Any authenticated admin role MAY read. The surface SHALL display only scalar columns (`image_hash`, `source`, archive/enforcement state, Kominfo status, unblock indicator, timestamps); it SHALL NOT render, fetch, proxy, or link to image bytes or the image's content URL. It SHALL be served by the existing V29 indexes and add no migration.

#### Scenario: Newest-first listing
- **WHEN** an authenticated admin opens `GET /admin/csam` with no filters
- **THEN** archive rows are returned ordered by `created_at` descending (ties broken by `id`), each showing `image_hash`, `source`, archive/enforcement state, Kominfo status, and timestamp

#### Scenario: Filter by source
- **WHEN** the admin applies `source=admin_manual`
- **THEN** only rows with `source = 'admin_manual'` are listed and the `cf_worker` rows are excluded

#### Scenario: Filter by Kominfo status pending
- **WHEN** the admin applies Kominfo status `pending`
- **THEN** only rows with `kominfo_reported_at IS NULL` are listed

#### Scenario: Filter by created-at date range
- **WHEN** the admin applies a `from`–`to` UTC range
- **THEN** only rows whose `created_at` falls within the inclusive range are listed

#### Scenario: Keyset pagination
- **WHEN** the result set exceeds one page and the admin requests the next page with the returned cursor
- **THEN** the following rows after the cursor are returned with no duplicates and no skipped rows

#### Scenario: Plain-GET fallback without HTMX
- **WHEN** `GET /admin/csam` is requested without the HTMX request header
- **THEN** a full HTML page (not a bare fragment) is returned with the same rows

#### Scenario: Pending-count summary
- **WHEN** the log contains rows with `kominfo_reported_at IS NULL`
- **THEN** the page shows a pending/unprocessed count summary (the same-business-day SOP signal)

#### Scenario: Image bytes never rendered in the viewer
- **WHEN** any `GET /admin/csam` page or fragment is rendered
- **THEN** the response contains no image bytes, no `<img>` of the matched content, and no link to `img.nearyou.id` content for the matched image

### Requirement: Admin-triggered CSAM takedown (MVP path)

The Admin Panel SHALL let an `owner`/`admin` admin invoke the shipped fixed-policy CSAM takedown by submitting the matched `image_id` and `image_hash` (from Cloudflare's email) to `POST /admin/csam/takedown`. The route SHALL require a valid same-session CSRF token and the `owner`/`admin` role; it SHALL reject any other admin role with 403. It SHALL invoke `CsamDetectionService.handleDetection` in-process with `source = ADMIN_MANUAL` and `actorAdminId` = the acting admin, SHALL be idempotent (re-invocation converges with no duplicate ban or cascade), and SHALL return the `actioned`/`archived` outcome as an HTML fragment. Both `image_id` and `image_hash` SHALL be required. The takedown SHALL count against the shared 20/hour-per-admin destructive rate-limit budget. The takedown's own audit row is written by the service.

#### Scenario: Owner/admin invokes takedown on a live upload
- **WHEN** an `owner`/`admin` admin submits a valid `image_id`+`image_hash` for an image with an `image_uploads` ledger row
- **THEN** the fixed-policy takedown runs atomically (post tombstoned, uploader permanently banned with `token_version` bumped, other posts cascade-tombstoned, metadata archived, `moderation_queue csam_detected` enqueued, audit row written) and the panel shows an `actioned` result

#### Scenario: Ledger-miss archives only
- **WHEN** an `owner`/`admin` admin submits an `image_id` that has no `image_uploads` row
- **THEN** the match is archived (Kominfo record created) with no ban or cascade, and the panel shows an `archived` result

#### Scenario: Idempotent re-invocation
- **WHEN** an `owner`/`admin` admin invokes takedown a second time for the same already-actioned image
- **THEN** the operation converges with no duplicate ban, no duplicate cascade, and no duplicate archive row

#### Scenario: Read-only admin rejected from takedown
- **WHEN** an admin whose role is not `owner`/`admin` submits `POST /admin/csam/takedown`
- **THEN** the request is rejected with 403 and no takedown occurs

#### Scenario: Missing or cross-session CSRF token rejected
- **WHEN** `POST /admin/csam/takedown` is submitted without a CSRF token, or with a CSRF token captured from a different admin session
- **THEN** the request is rejected with 403 and no takedown occurs

#### Scenario: Missing required field rejected
- **WHEN** `POST /admin/csam/takedown` is submitted with a blank `image_id` or blank `image_hash`
- **THEN** the request is rejected with a validation error and no takedown occurs

### Requirement: Kominfo report tracking

The Admin Panel SHALL let an `owner`/`admin` admin record the Kominfo filing for an archived match at `POST /admin/csam/{id}/kominfo-report`, setting `kominfo_report_id` and `kominfo_reported_at = now()` on the row within one transaction that also writes exactly one immutable `admin_actions_log` row with `action_type = csam_kominfo_reported` (before/after state). It SHALL require the `owner`/`admin` role, a valid same-session CSRF token, and a non-blank report id. It SHALL be idempotent: filing an already-filed row SHALL be rejected with no mutation and no audit row, preserving the original `kominfo_reported_at` (the legal timestamp). It SHALL be rate-limited on a dedicated per-admin counter sourced from the audit-trail limiter, independent of the destructive budget. Recording the filing makes the row eligible for the existing daily purge worker after its 90-day deadline.

#### Scenario: Owner/admin files the Kominfo report
- **WHEN** an `owner`/`admin` admin submits a non-blank report id for a pending archive row with a valid CSRF token
- **THEN** `kominfo_report_id` is set, `kominfo_reported_at` is set to now, and one immutable `csam_kominfo_reported` audit row is written in the same transaction

#### Scenario: Re-filing an already-filed row is rejected
- **WHEN** an admin submits a Kominfo report for a row whose `kominfo_reported_at` is already set
- **THEN** the request is rejected, no column is mutated, no audit row is written, and the original `kominfo_reported_at` is preserved

#### Scenario: Blank report id rejected
- **WHEN** an admin submits `POST /admin/csam/{id}/kominfo-report` with a blank report id
- **THEN** the request is rejected with a validation error and the row is unchanged

#### Scenario: Read-only admin rejected from Kominfo write
- **WHEN** an admin whose role is not `owner`/`admin` submits a Kominfo report
- **THEN** the request is rejected with 403 and the row is unchanged

#### Scenario: Kominfo write rate-limited on its own counter
- **WHEN** an admin exceeds the dedicated per-admin Kominfo-report cap within the window
- **THEN** further Kominfo-report writes are rate-limited without consuming the 20/hour destructive budget

### Requirement: Audit-logged metadata decrypt

The Admin Panel SHALL let an `owner`/`admin` admin decrypt a single archive row's AES-256-GCM `encrypted_metadata` on demand at `POST /admin/csam/{id}/decrypt`, returning the plaintext metadata (uploader id, affected post id, source, cascade count — never image bytes) as an HTML fragment. It SHALL require the `owner`/`admin` role and a valid same-session CSRF token, SHALL write one `admin_actions_log` row with `action_type = csam_metadata_decrypted` on every decrypt attempt, and SHALL be rate-limited on a dedicated per-admin counter off the audit-trail limiter. It SHALL be fail-soft: when the `csam-archive-aes-key` is unprovisioned or the row's `encrypted_metadata` is NULL, it SHALL return a graceful "metadata unavailable" fragment (never a 500) and still write the audit row.

#### Scenario: Owner/admin decrypts metadata
- **WHEN** an `owner`/`admin` admin decrypts a row whose `encrypted_metadata` and key are present
- **THEN** the plaintext metadata fragment is returned (no image bytes) and one `csam_metadata_decrypted` audit row is written

#### Scenario: Key unprovisioned fails soft
- **WHEN** an admin decrypts a row while `csam-archive-aes-key` is unset (or `encrypted_metadata` is NULL)
- **THEN** a graceful "metadata unavailable" fragment is returned with no 500, and the decrypt-attempt audit row is still written

#### Scenario: Read-only admin rejected from decrypt
- **WHEN** an admin whose role is not `owner`/`admin` submits a decrypt
- **THEN** the request is rejected with 403 and no decryption occurs

#### Scenario: Decrypted output contains no image bytes
- **WHEN** a decrypt fragment is rendered
- **THEN** it contains only scalar metadata fields and no image bytes or content URL

### Requirement: Cloudflare unblock-request surfacing

For rows with `source = 'cf_worker'`, the viewer SHALL surface the Cloudflare-provided review/unblock path (link-out) and a status indicator, without implementing any internal unblock-decision workflow. Rows with `source = 'admin_manual'` SHALL NOT show an unblock affordance.

#### Scenario: CF-worker row shows review link
- **WHEN** a `cf_worker` archive row is listed
- **THEN** the row surfaces the Cloudflare review/unblock link-out and a status indicator

#### Scenario: Admin-manual row shows no unblock affordance
- **WHEN** an `admin_manual` archive row is listed
- **THEN** no unblock review affordance is shown for that row

### Requirement: CSAM admin surface security invariants

Across the entire `/admin/csam` surface, every state-changing action (takedown, Kominfo-report, decrypt) SHALL require the `owner`/`admin` role AND a valid same-session CSRF token, and SHALL be auditable via `admin_actions_log`; read access SHALL require an authenticated admin session (any role). No part of the surface SHALL render, fetch, or proxy image content. These invariants are the Pre-Launch security-review gate for this surface (docs/08).

#### Scenario: Cross-session CSRF replay rejected on every write
- **WHEN** a CSRF token issued for admin session A is replayed against takedown, Kominfo-report, or decrypt under admin session B
- **THEN** each request is rejected with 403 and no state change occurs

#### Scenario: Unauthenticated access redirected
- **WHEN** an unauthenticated client requests `GET /admin/csam` or any `/admin/csam/*` write route
- **THEN** the request is redirected to the admin login (302 `/admin/login`) and no data is returned or mutated

#### Scenario: No image content anywhere on the surface
- **WHEN** any `/admin/csam` read or write response is produced
- **THEN** the response body contains no image bytes and no matched-image content URL
