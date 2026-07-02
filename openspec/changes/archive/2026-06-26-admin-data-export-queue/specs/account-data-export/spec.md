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

## REMOVED Requirements

### Requirement: Admin Data Export Queue surface is deferred (out of scope)

**Reason**: This change ships the admin Data Export Queue surface (the `/admin/data-exports` read + the `POST …/{id}/trigger` action) — the very surface this requirement asserted would NOT exist. Leaving it in place would leave a stale "no `/admin/*` route" requirement contradicting the shipped surface (the `Mobile Settings entry is deferred` requirement is **retained** — that layer is still genuinely deferred to #362).

**Migration**: The admin surface's behavior is now specified by the new `admin-data-export-queue` capability spec; the producer's reusable single-request seam (consumed by the admin trigger) is specified by the ADDED requirement above. The tracking issue #361 is closed on archive of this change.
