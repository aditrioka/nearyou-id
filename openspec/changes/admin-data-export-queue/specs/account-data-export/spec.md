## ADDED Requirements

### Requirement: Worker exposes a reusable single-request processing seam

The data-export worker SHALL expose a **single-request processing seam** that drives one identified `data_export_requests` row through the **same** pipeline as the batch run — claim (`pending → processing`, affected-rows guard) → gather the scope matrix → serialize the ZIP → upload to object storage → set `ready` (+ `r2_object_key` + `download_expires_at`) → emit the durable `data_export_ready` notification → best-effort email — preserving the identical fail-soft, claim-race, and delivery semantics. The batch `/internal/data-export-worker` run SHALL process its pending set **using this same seam** (one export path, two callers), so an out-of-band caller (e.g. an operator-triggered re-run) and the scheduler exercise identical behavior with no divergent second path. This seam adds no new external endpoint and does not change the batch worker's scan/claim/fail-soft behavior, the `/api/v1/account/export` contract, the `data_export_requests` schema, or delivery semantics.

#### Scenario: Single-request drive on a claimable request reaches ready
- **GIVEN** a request in `status = 'pending'` and object storage configured
- **WHEN** the single-request seam is invoked for that request id
- **THEN** the request is claimed, gathered, uploaded, and set `ready` (with `r2_object_key` + `download_expires_at`) AND the durable `data_export_ready` notification is emitted — the same outcome the batch run produces

#### Scenario: Single-request drive on a non-claimable request is a no-op
- **WHEN** the single-request seam is invoked for a request that is not in `status = 'pending'` (already `processing` or `ready`)
- **THEN** the claim guard skips it and no second processing occurs (consistent with the batch claim guard)

#### Scenario: Fail-soft is preserved on the single-request path
- **GIVEN** a gather/upload failure (including object storage unconfigured)
- **WHEN** the single-request seam processes the request
- **THEN** the row is set `failed` with a non-PII `error` and `attempt_count` incremented, and no `ready`/notification/email is produced — identical to the batch fail-soft path

#### Scenario: The batch worker uses the same seam
- **WHEN** the batch `/internal/data-export-worker` run processes its pending snapshot
- **THEN** each request is processed through the same single-request seam (no separate batch-only export code path)
