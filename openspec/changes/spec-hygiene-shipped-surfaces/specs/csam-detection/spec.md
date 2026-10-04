## RENAMED Requirements

- FROM: `### Requirement: Admin CSAM review surface and the report-filing read path are deferred`
- TO: `### Requirement: The admin CSAM review surface and Kominfo report filing are owned by admin-csam-detection-log`

## MODIFIED Requirements

### Requirement: The admin CSAM review surface and Kominfo report filing are owned by admin-csam-detection-log

The `csam-detection` capability SHALL NOT itself expose an admin-facing CSAM surface: its own HTTP surface is `POST /internal/csam-webhook` and `POST /internal/csam-archive-purge`, and neither SHALL return `csam_detection_archive` rows or decrypted `encrypted_metadata` (the webhook answers with a status, the purge worker with a purged count). The human review / decrypt / file workflow SHALL be owned by the `admin-csam-detection-log` capability (shipped by the `admin-csam-detection-log-viewer` change), mounted under the authenticated admin panel: the detection-log viewer (`GET /admin/csam`), the admin-triggered takedown (`POST /admin/csam/takedown`, which invokes this capability's `CsamDetectionService.handleDetection` in-process with `source = ADMIN_MANUAL` rather than re-implementing the fixed policy), the Kominfo filing write (`POST /admin/csam/{id}/kominfo-report`), and the audit-logged metadata decrypt (`POST /admin/csam/{id}/decrypt`). An archive row written by this capability SHALL start with `kominfo_report_id` and `kominfo_reported_at` NULL. The only writer that sets them SHALL be the `admin-csam-detection-log` Kominfo filing action, and setting them is what releases the row to this capability's purge worker once its preservation window has elapsed.

#### Scenario: csam-detection's own routes expose no archive read or decrypt

- **WHEN** `POST /internal/csam-webhook` or `POST /internal/csam-archive-purge` is invoked
- **THEN** neither response body contains `csam_detection_archive` rows or decrypted `encrypted_metadata` AND every admin read / decrypt / file route for the archive lives under `/admin/csam` (`admin-csam-detection-log`)

#### Scenario: Kominfo filing fields are set only by the admin filing action

- **WHEN** a match is archived (via the webhook, or via the admin-triggered takedown calling `handleDetection`)
- **THEN** the new archive row has `kominfo_report_id IS NULL` AND `kominfo_reported_at IS NULL`, AND those columns are later set only by `POST /admin/csam/{id}/kominfo-report` (per `admin-csam-detection-log` § "Kominfo report tracking")
