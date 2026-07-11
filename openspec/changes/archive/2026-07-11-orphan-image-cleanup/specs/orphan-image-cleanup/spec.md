# orphan-image-cleanup

## ADDED Requirements

### Requirement: Scheduled orphan-image cleanup endpoint

The backend SHALL expose `POST /internal/cleanup-orphan-images`, mounted under the shared `route("/internal")` block with the `InternalEndpointAuth` OIDC gate installed on ITS OWN `/cleanup-orphan-images` subtree (never on the shared `/internal` node — the vendor-auth webhook siblings `/internal/revenuecat-webhook` and `/internal/apple/s2s-notifications` carry non-OIDC credentials and must not be captured; the `InternalRoutingIsolationTest` posture). The endpoint SHALL never be reachable with a user JWT. On success it SHALL respond `200` with body `{"scanned": N, "deleted": N, "cf_failures": N}`. On a thrown exception it SHALL respond `500` with the sanitized `{"error": "<classification>"}` body via `classifyHandlerError` and log the original exception at WARN — mirroring the sibling internal workers.

#### Scenario: Unauthenticated invocation is rejected
- **WHEN** `POST /internal/cleanup-orphan-images` is called without a valid Google-OIDC bearer token
- **THEN** the response is `401` and no cleanup work runs

#### Scenario: Successful run returns the counts
- **WHEN** the endpoint is invoked with a valid OIDC token and the worker completes
- **THEN** the response is `200` with `scanned`, `deleted`, and `cf_failures` integer fields

#### Scenario: Worker failure returns a sanitized error
- **WHEN** the worker throws (e.g. the database is unreachable)
- **THEN** the response is `500` with `{"error": "<classification>"}` (`timeout` / `connection_refused` / `unknown`) and no exception detail leaks into the body

### Requirement: Orphan scan selects only aged unattached uploads, bounded per run

A run SHALL select candidate rows with `status = 'uploaded'` AND `created_at` older than 24 hours, oldest first, capped at 500 rows per run (`SELECT … WHERE status = 'uploaded' AND created_at < NOW() - INTERVAL '24 hours' ORDER BY created_at LIMIT 500`). Rows with `status = 'attached'` and rows younger than 24 hours SHALL never be selected. The bound makes a single run's duration finite; a larger backlog drains across subsequent daily runs and is visible in the run's reported `scanned` count (no silent truncation). This change SHALL add no Flyway migration and no new index.

#### Scenario: Fresh upload is not swept
- **WHEN** an image was uploaded 1 hour ago and not attached
- **THEN** the run does not select it and its ledger row + Cloudflare image survive

#### Scenario: Aged orphan is swept
- **WHEN** an image was uploaded more than 24 hours ago and never attached
- **THEN** the run deletes its Cloudflare image and its `image_uploads` row

#### Scenario: Attached image is never swept regardless of age
- **WHEN** an image older than 24 hours has `status = 'attached'`
- **THEN** the run does not select it

### Requirement: Per-row delete is transactional and loses the race to a concurrent attach

Each candidate SHALL be processed in its own transaction: first the conditional `DELETE FROM image_uploads WHERE cf_image_id = ? AND status = 'uploaded'` (uncommitted), then — only if exactly one row was affected — the Cloudflare delete, then commit. If the conditional DELETE affects zero rows (a concurrent attach flipped the row between scan and delete), the worker SHALL skip the Cloudflare call and leave the image intact. If the Cloudflare delete fails, the transaction SHALL roll back so the ledger row survives for the next run (counted in `cf_failures`); one row's failure SHALL NOT abort the remaining rows. Because the uncommitted DELETE holds the row lock, the attach path's own conditional `UPDATE … WHERE status = 'uploaded'` serializes against it — in no interleaving does a post end up attached to a Cloudflare-deleted image.

#### Scenario: Concurrent attach wins
- **WHEN** a candidate row is flipped to `'attached'` after the scan but before the worker's conditional DELETE commits
- **THEN** the worker's DELETE affects zero rows, the Cloudflare image is NOT deleted, and the row remains `'attached'`

#### Scenario: Cloudflare failure is retryable
- **WHEN** the Cloudflare delete for a row fails with a non-404 error
- **THEN** that row's transaction rolls back (the ledger row survives), `cf_failures` increments, the run continues with the next row, and a later run retries the row

### Requirement: Cloudflare image deletion is 404-tolerant

`ImageStore` SHALL gain a `delete(imageId)` operation implemented by `CloudflareImageStore` as `DELETE https://api.cloudflare.com/client/v4/accounts/{accountId}/images/v1/{imageId}` with the same bearer-token auth as `upload`. An HTTP 404 SHALL be treated as success (the image is already gone — e.g. a prior run crashed after the Cloudflare delete but before commit — so the retry converges instead of wedging). Any other non-2xx SHALL throw `CloudflareImageStoreException`. No Cloudflare SDK or HTTP-client import appears outside `:infra:cloudflare-images` (vendor-isolation invariant).

#### Scenario: 404 converges the retry
- **WHEN** the worker retries a row whose Cloudflare image was already deleted by a crashed prior run
- **THEN** the 404 from Cloudflare is treated as success and the ledger row is deleted (committed)

### Requirement: Unconfigured environment fail-softs to a no-op run

When `ImageStore.isConfigured()` is false (Cloudflare Images credentials absent — the `NoOpImageStore` binding), the worker SHALL perform no scan and no deletes and return an all-zero result with a `200` response, so an unconfigured environment never errors the Cloud Scheduler invocation and never deletes ledger rows whose Cloudflare objects it cannot delete.

#### Scenario: Unconfigured run is a successful no-op
- **WHEN** the endpoint is invoked in an environment without Cloudflare Images credentials
- **THEN** the response is `200` with all counts zero and no `image_uploads` row is deleted

### Requirement: Single structured log line per run

A run SHALL emit exactly one INFO log line — `event=orphan_image_cleanup scanned={} deleted={} cf_failures={} duration_ms={}` — and SHALL NOT write `admin_actions_log` rows or per-row logs (routine hygiene, not a user-visible state change; the `RetentionCleanupWorker` / `PrivacyFlipWorker` discipline). The run SHALL be idempotent: re-invocation with no newly-aged orphans reports all-zero counts.

#### Scenario: Idempotent re-run
- **WHEN** the endpoint is invoked twice in succession with no new orphans aging past 24 hours in between
- **THEN** the second run responds `200` with `scanned = 0`, `deleted = 0`, `cf_failures = 0`

### Requirement: Daily scheduling is an operator-provisioned Cloud Scheduler job

The job SHALL be invoked by a daily Cloud Scheduler job per environment (staging, production) targeting `POST /internal/cleanup-orphan-images` with the same OIDC service-account identity as the existing `/internal/cleanup` retention job. Provisioning is an operator task; the backend SHALL NOT self-schedule.

#### Scenario: Scheduler identity is accepted
- **WHEN** the Cloud Scheduler job invokes the endpoint with its OIDC identity token
- **THEN** the request passes `InternalEndpointAuth` and the run executes
