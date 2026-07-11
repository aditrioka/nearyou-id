# premium-image-upload (delta)

## MODIFIED Requirements

### Requirement: Delivery optimization, anomaly detection, and orphan cleanup are deferred

This change SHALL NOT implement delivery-cost optimization (`srcset` single-variant, lazy-load), per-user delivery anomaly detection (>5× baseline), or the `:infra:r2` module. Orphan cleanup is no longer deferred: the `orphan-image-cleanup` capability ships the scheduled cleanup job — uploaded-but-never-attached images (`image_uploads.status = 'uploaded'`) older than 24 hours are deleted from Cloudflare Images together with their ledger rows by the daily `POST /internal/cleanup-orphan-images` worker.

#### Scenario: Orphaned upload is cleaned up after the grace window
- **WHEN** an image is uploaded but never attached to a post
- **THEN** its `image_uploads` row remains with `status = 'uploaded'` for at least 24 hours, after which the `orphan-image-cleanup` job deletes the Cloudflare image and the ledger row
