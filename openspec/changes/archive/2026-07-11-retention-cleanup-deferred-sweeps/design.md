# Design: retention-cleanup-deferred-sweeps

## Context

`POST /internal/cleanup` (`scheduled-retention-cleanup`, PR #360) runs four idempotent threshold `DELETE` sweeps per invocation — `refresh_tokens`, `notifications`, `user_fcm_tokens`, `login_events` — each an independent single-statement auto-commit `DELETE` on the pool-bounded DB dispatcher, aggregated by `RetentionCleanupWorker` into one result + one structured `retention_cleanup` INFO log line. Two sweeps were bounded out at ship time as negative-guard requirements, tracked by follow-up issues #365 (WebAuthn challenges) and #366 (moderation/reports 1-year retention). This change adds them as sweeps five through seven, reusing every established pattern; nothing else about the worker changes.

## Goals / Non-Goals

**Goals:**

- Enforce the three remaining written retention windows: expired unconsumed WebAuthn challenges (`docs/05` §750), resolved `moderation_queue` rows > 1 year, resolved `reports` rows > 1 year (`docs/06` § Retention Policy).
- Flip the two scope-boundary spec requirements to positive sweep requirements; close #365 + #366.

**Non-Goals:**

- No archive table, no retain/move step (D1).
- No second endpoint / separate weekly schedule (D2).
- No change to the OIDC gate, error classification, or the four shipped sweeps.
- No WebAuthn enrollment work — the sweep is forward-looking hygiene over a table nothing writes yet (that's why #365 was deferred; shipping the sweep early is harmless: it idempotently matches zero rows).

## Decisions

**D1 — "Archival" = retention-enforcing DELETE, no archive table.** `docs/08` Phase 3.5 item 12 says "archival worker"; `docs/06` § Retention Policy is the canonical semantics: "Moderation queue (resolved rows) | 1 year", "Reports (resolved) | 1 year" — a retention window, same shape as the other rows in that table. The moderation *decision* audit trail lives in `admin_actions_log` (its own 1-year "Moderation action log" window), so deleting resolved queue/report rows loses no audit obligation. Copying rows to an archive table before delete would extend reporter-PII retention past the written window (against the UU-PDP data-minimization posture the parent capability exists to enforce) and add a table + migration nobody reads. Alternative considered: `moderation_archive` table with a move step — rejected as above. Issue #366 explicitly delegated retain-vs-delete to this proposal.

**D2 — Sweeps run every invocation (daily), not on a separate weekly schedule.** `docs/05` §750 and `docs/08` items 12–13 say "weekly". The worker is Cloud-Scheduler-invoked daily and every sweep is an idempotent threshold `DELETE`, so running daily is a strict superset of weekly (each run reclaims whatever crossed the threshold; extra runs match zero rows). A second endpoint or scheduler job would duplicate the OIDC gate + routing for zero behavioral gain. Precedent: the shipped sweeps already run per-invocation regardless of their doc-stated cadence.

**D3 — Resolved-row predicates key on the resolution timestamp, not `created_at`.**
- `moderation_queue`: `status = 'resolved' AND resolved_at < NOW() - INTERVAL '1 year'`.
- `reports`: `status IN ('actioned', 'dismissed') AND reviewed_at < NOW() - INTERVAL '1 year'` — `reports.status` has no `resolved` value; `actioned`/`dismissed` are its two resolved states (V9 CHECK).

The retention window is "resolved rows, 1 year" — the clock starts at resolution, not submission. Both timestamp columns are nullable; a NULL comparison is UNKNOWN, so a (hypothetically inconsistent) resolved-status row with a NULL timestamp survives — fail-safe, mirroring the shipped `last_used_at IS NULL` handling. Pending rows are never touched by either predicate.

**D4 — WebAuthn predicate verbatim from docs/05 §750, including leaving consumed rows alone.** `expires_at < NOW() - INTERVAL '1 day' AND consumed_at IS NULL`, exactly the prescribed weekly cleanup and exactly the shape of the `admin_webauthn_challenges_cleanup_idx` partial index (`(expires_at) WHERE consumed_at IS NULL`, V16). Consumed rows are deliberately NOT swept here — docs/05 prescribes only the unconsumed-expired cleanup; widening it would diverge from the canonical query shape. (Consumed challenges are per-ceremony rows the future WebAuthn capability owns; if their growth ever matters, that capability amends docs/05 first.)

**D5 — Mechanical extension of the existing three-layer shape.** Three new `RetentionCleanupRepository` methods (same `executeDelete(SQL)` helper, own auto-commit connection each — one sweep's failure must not roll back a sibling's reclaimed rows), three new `RetentionCleanupResult` fields, three new snake_case response fields, three new log-line keys. Sequential execution order appends the new sweeps after `login_events`. No new patterns; Pattern-Registry-clean by construction.

## Risks / Trade-offs

- **[Index scan on `moderation_queue`/`reports` deletes]** Both predicates are status-prefix-served (`moderation_queue_status_idx` on `(status, priority, created_at)`, `reports_status_idx` on `(status, created_at DESC)`) with a residual filter on the timestamp column. Resolved-row cardinality at MVP scale is tiny; if it ever grows, a dedicated partial index is a one-migration follow-up. → Accept; no new index now.
- **[Daily-vs-weekly doc divergence]** Docs say "weekly"; the sweeps run daily. → D2 documents cadence-superset reasoning; the spec deltas state "on each invocation", consistent with the parent capability's shipped requirements. No doc amendment needed — the docs prescribe the *at-least* hygiene cadence, and the retention windows themselves are unchanged.
- **[Sweep ordering]** Appending three sweeps lengthens the sequential run. Each is a single indexed `DELETE` on near-empty tables; duration is captured in the existing `duration_ms`. → Accept.

## Migration Plan

None — no schema change, no new secret, no deploy step beyond the normal backend release. Rollback = revert the commit; the sweeps are stateless.

## Open Questions

None.
