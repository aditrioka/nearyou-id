# Design: orphan-image-cleanup

## Context

The `premium-image-upload` pipeline (PR #325) inserts one `image_uploads` row per stored Cloudflare image (`status = 'uploaded'`, `created_at DEFAULT now()`); the post-attach path flips the winner to `'attached'` via a conditional `UPDATE … WHERE status = 'uploaded'`. Nothing ever removes an unattached image. The internal-worker substrate this job slots into already exists: OIDC-gated `/internal/*` routes (`InternalEndpointAuth`, per-subtree install — `RetentionCleanupRoutes.kt` documents why the gate must NOT sit on the shared `/internal` node), single-structured-log worker discipline (`RetentionCleanupWorker`, `PrivacyFlipWorker`), sanitized error classification (`classifyHandlerError`), and Cloud Scheduler invocation.

A sibling in-flight change (`retention-cleanup-deferred-sweeps`, issues #365/#366) is extending `RetentionCleanupWorker` — a reason to keep this job in its own files.

## Goals / Non-Goals

**Goals:**
- Reclaim Cloudflare Images storage + ledger rows for uploads never attached to a post, after a 24-hour grace window.
- Correctness under the concurrent-attach race: never delete the Cloudflare image of a post that just attached it.
- Retry-safe: a Cloudflare API failure leaves the row in place for the next daily run.

**Non-Goals:**
- Delivery-cost optimization and per-user delivery anomaly detection (still deferred in `premium-image-upload`).
- Any schema change (no new status value, no new index — see D4).
- Backfill tooling or admin surface; the daily cadence drains any backlog.

## Decisions

**D1 — Separate worker, not a fifth sweep inside `RetentionCleanupWorker`.** The retention sweeps are single-statement threshold `DELETE`s; this job iterates rows and makes an external HTTP call per row, with per-row transactional commit — a different failure/latency profile that would distort the retention worker's all-zero-idempotent response contract. Separate files also avoid a live collision with `retention-cleanup-deferred-sweeps` (#365/#366), and the codebase precedent is one route per worker (`privacyFlipWorkerRoute`, `accountHardDeleteWorkerRoute`, `ReferralActivityCheckRoute`). Route: `POST /internal/cleanup-orphan-images`, own subtree + own `InternalEndpointAuth` install.

**D2 — Per-row transaction: conditional row-DELETE first (uncommitted), Cloudflare delete, then commit.** For each candidate: open a transaction, `DELETE FROM image_uploads WHERE cf_image_id = ? AND status = 'uploaded'`; if 0 rows affected, an attach won the race since the scan — commit the no-op and skip the Cloudflare call entirely. If 1 row, call `ImageStore.delete`; success (or 404) → commit; failure → roll back, count a `cf_failures`, continue with the next row. The uncommitted DELETE holds the row lock across the HTTP call, so a concurrent attach (`UPDATE … WHERE status = 'uploaded'`) blocks until commit and then correctly affects 0 rows. Holding a row lock over HTTP is normally an anti-pattern; here the locked row is a ≥24h-old orphan whose only possible contender is a last-second attach, and the batch is bounded — contention is effectively zero. Alternative considered: delete-row-then-CF (a CF failure permanently orphans the image — the exact bug this change fixes), CF-then-delete-row unfenced (attach lands between scan and CF delete → live post with a dead image), or a `'deleting'` claim status (needs a CHECK-constraint migration; rejected per Non-Goals).

**D3 — Cloudflare `DELETE /accounts/{account}/images/v1/{image_id}`; 404 = success.** A 404 means the image is already gone (a prior run that crashed after CF-delete but before commit) — treat as deleted so the retry converges. Any other non-2xx throws `CloudflareImageStoreException` (same shape as `upload`). `NoOpImageStore.delete` is NOT called: the worker gates on `isConfigured()` and returns an all-zero result (fail-soft, matching the project's unconfigured-vendor convention) — an unconfigured staging environment must not 500 the scheduler.

**D4 — Scan is a bounded threshold query, no new index, no migration.** `SELECT cf_image_id FROM image_uploads WHERE status = 'uploaded' AND created_at < NOW() - INTERVAL '24 hours' ORDER BY created_at LIMIT 500` (interval inline in the query text, NOT in any index predicate — the partial-index invariant is about indexes, and this change adds none). The table is bounded by the 50/day/user upload quota and mostly `attached` rows; a seq scan at daily cadence is nothing. `LIMIT 500` bounds a single run's duration (≤500 sequential CF calls); a larger backlog drains across subsequent runs, and the run's log line carries the counts so truncation is visible, not silent. Threshold and batch size are constants in the worker — the 24h grace window is the issue's own suggestion and generous for "user uploads, previews, then posts".

**D5 — Observability = the retention discipline.** Exactly one INFO line per run: `event=orphan_image_cleanup scanned={} deleted={} cf_failures={} duration_ms={}`. No per-row logging, no `admin_actions_log` writes, no `system` sentinel actor (routine hygiene). Route failure path mirrors `retentionCleanupRoutes`: WARN with full context, sanitized `{"error": "<classification>"}` `500` body.

## Risks / Trade-offs

- [Row lock held across an HTTP call (D2)] → bounded batch, ≥24h-old rows only, single daily invocation; a hung CF call is bounded by the HTTP client timeout, and one stuck row cannot block the others (per-row transactions).
- [Worker deletes a CF image that a *different* live system references] → `posts.image_id` is only ever populated through the attach path, which flips `status` in the same transaction; a `'uploaded'` row is by construction referenced by no post.
- [CF API partially down → run reports many `cf_failures`] → rows remain, next run retries; the counts in the log line are the operator signal.
- [Backlog > 500/day sustained] → would indicate upload abuse rather than a cleanup-sizing problem (quota is 50/day/user); revisit batch size then.

## Migration Plan

1. Ship the code (no Flyway migration; deploy is a normal Cloud Run rollout).
2. Operator: create the daily Cloud Scheduler job `POST https://api[-staging].nearyou.id/internal/cleanup-orphan-images` with the same OIDC service-account identity as the existing `/internal/cleanup` job (staging first, then production).
3. Rollback: delete the scheduler job / roll back the deploy — the worker is pure-additive; no data migration to unwind.

## Open Questions

None — parameters (24h window, 500 batch) are constants with documented revisit conditions.
