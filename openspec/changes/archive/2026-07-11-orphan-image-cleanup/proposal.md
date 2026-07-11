# Proposal: orphan-image-cleanup

## Why

Every successful `POST /api/v1/images` inserts an `image_uploads` ledger row with `status = 'uploaded'`; the row only flips to `'attached'` when a post attaches the image. Images uploaded but never attached accumulate forever — as paid storage in Cloudflare Images and as dead `uploaded` rows in the ledger. The `premium-image-upload` capability deliberately deferred this cleanup ("Orphan cleanup is enabled-but-not-implemented: the `image_uploads.status` field makes it a pure-additive follow-up") and issue [#340](https://github.com/aditrioka/nearyou-id/issues/340) tracks it. This change discharges that deferral.

## What Changes

- New daily worker `OrphanImageCleanupWorker` (Cloud-Scheduler-invoked via a new OIDC-gated `POST /internal/cleanup-orphan-images` route) that, for each `image_uploads` row with `status = 'uploaded'` older than 24 hours (bounded batch per run): deletes the ledger row and the Cloudflare image atomically per-row — commit only after the Cloudflare delete succeeds, roll the row back on failure so the next run retries.
- `ImageStore` (`:infra:cloudflare-images`) gains a `delete(imageId)` operation (Cloudflare `DELETE /accounts/{account}/images/v1/{image_id}`; a 404 counts as already-deleted success). `NoOpImageStore` stays fail-soft: an unconfigured environment no-ops the run.
- The worker follows the established internal-worker discipline (`RetentionCleanupWorker` / `PrivacyFlipWorker`): idempotent, one structured INFO log line per run (`event=orphan_image_cleanup` with counts + duration), sanitized-classification `500` on failure, own OIDC subtree so the vendor-auth webhook siblings under `/internal` are untouched.
- No Flyway migration: the scan is a threshold query on the existing ledger; no schema change.
- Operator task: create the daily Cloud Scheduler job targeting the new route (same OIDC service account as `/internal/cleanup`).

## Capabilities

### New Capabilities

- `orphan-image-cleanup`: the scheduled orphan-image cleanup job — scan contract (which rows qualify), per-row delete ordering/atomicity vs the concurrent-attach race, Cloudflare delete semantics (404-tolerant), batch bound, fail-soft unconfigured behavior, auth + observability contract of `POST /internal/cleanup-orphan-images`.

### Modified Capabilities

- `premium-image-upload`: the "Delivery optimization, anomaly detection, and orphan cleanup are deferred" requirement no longer defers orphan cleanup — its "Orphaned upload is retained" scenario is superseded by the 24-hour retention window this change ships (delivery optimization and anomaly detection remain deferred).

## Impact

- **Backend**: new `backend/ktor/.../image/OrphanImageCleanup{Worker,Repository,Routes}.kt`; `Application.kt` wiring (repository + worker + route mount under `route("/internal")`).
- **Infra**: `:infra:cloudflare-images` — `ImageStore.delete`, `CloudflareImageStore` implementation, `NoOpImageStore` counterpart.
- **Specs**: new `orphan-image-cleanup` capability; delta on `premium-image-upload`.
- **Ops**: one new Cloud Scheduler job (operator-provisioned; staging + production).
- **Closes**: issue #340.
