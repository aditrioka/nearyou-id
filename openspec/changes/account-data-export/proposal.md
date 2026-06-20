## Why

NearYouID must honor the UU PDP **right to data portability** — every user can request a copy of their own personal data — and the operator declined the launch cut-line (full-vision delivery). Today this is entirely unbuilt: no export endpoint, no async job, no delivery path. The erasure half of the account-data-rights surface already shipped (`account-deletion` + `account-hard-delete-worker`), the `data_export_ready` notification type already ships in the V10 catalog, and the Resend transactional-email integration was smoke-tested — so the export **producer** is the missing half. This is a Pre-Launch compliance requirement (docs/08 Phase 3.5 item 15 + Pre-Launch "Data export scope verified").

## What Changes

- **NEW** `POST /api/v1/account/export` (authenticated) — enqueue a personal-data export; **idempotent + rate-limited to one active request per user** (returns the in-flight request rather than duplicating). `GET /api/v1/account/export` returns the caller's latest export status (for a mobile status banner).
- **NEW** `data_export_requests` table (migration **V29**) — status state machine `pending → processing → ready → (expired | failed)`, R2 object key, 24h download expiry, a **one-active-per-user partial unique index**, and a worker-scan partial index (both `WHERE` clauses `NOW()`-free per the partial-index invariant).
- **NEW** OIDC-authed internal worker `POST /internal/data-export-run` (Cloud Scheduler) — gathers the scope matrix, serializes to a ZIP of **JSON + CSV** files (per the canonical Format column) + a manifest, uploads to R2, issues a **24h signed URL**, sets `ready`, emits the `data_export_ready` notification, sends the Resend email. **Fail-soft** when R2/Resend creds are unset (established `:infra:*` posture). Mirrors the shipped suspension-unban / privacy-flip / account-hard-delete worker pattern.
- **NEW** `:infra:r2` module — vendor-neutral `ObjectStore` + signed-URL contract over Cloudflare R2 (S3-compatible). First object-storage substrate on the backend classpath.
- **NEW** `:infra:resend` module — vendor-neutral `EmailSender` contract over the Resend REST API (3-retry backoff + idempotency). Modularizes the Resend integration smoke-tested 2026-04-27.
- **Scope matrix** = the canonical **docs/06 §350 Data Export Scope Matrix** (own-data only, auth-scoped): profile, username + edit history, DOB, hashed Google/Apple ID, analytics-consent, posts (incl. the user's own `actual_location`), likes, replies, follows, block list, chat (sent + received, **peer id hashed**), reports submitted, notifications, moderation actions applied, session history (90-day), subscription history. **Excludes** reports-received, attestation verdicts, admin audit, CSAM archive, `rejected_identifiers`, and shadow-ban status (stealth invariant overrides the moderation-actions row).
- **Reuses** the already-shipped `data_export_ready` notification type (V10, body_data `{signed_url, expires_at}`) — **no notifications migration**.
- **Out of scope (deferred, captured as explicit requirements):** the admin **Data Export Queue** monitoring surface (docs/07 — separate admin-lane change); the mobile Settings **"Unduh Data Saya"** entry (this change ships the backend it calls).

## Capabilities

### New Capabilities
- `account-data-export`: user-initiated export of all own personal data — the trigger + status endpoints, the `data_export_requests` lifecycle, the async gather/serialize/upload worker, the scope matrix, and signed-URL + email + notification delivery, under a 7-day SLA with a 24h download window.
- `object-storage`: vendor-neutral object-storage contract (`:infra:r2`) — store an object and issue a time-limited signed GET URL over Cloudflare R2; fail-soft when unconfigured. Reusable substrate (future: image upload, CSAM archive, backups).
- `transactional-email`: vendor-neutral transactional-email contract (`:infra:resend`) — send a templated transactional email via the Resend REST API with retry + idempotency; fail-soft when unconfigured. Reusable substrate (future: deletion confirmation, subscription receipts, Apple relay-change notice).

### Modified Capabilities
- None. The `data_export_ready` notification type already ships in the V10 catalog (`in-app-notifications`); **emitting** it is a behavior of `account-data-export`, not a requirement change to the notifications capability.

## Impact

- **New module `:infra:r2`** — Cloudflare R2 / S3-compatible object storage + signed URLs. New `gradle/libs.versions.toml` pin(s); **substrate-introducing → pre-implementation library re-check fires at `/opsx:apply`**. Backend-included → root README module list (`dev/module-descriptions.txt` + `sync-readme.sh`) **and** `Dockerfile` COPY blocks updated (a non-gated `include()` not COPY'd breaks every staging/prod image build silently).
- **New module `:infra:resend`** — Resend REST email (likely raw Ktor client → possibly no new pin; thin one if a helper is chosen). Same README + Dockerfile updates; pre-impl re-check if a pin is added.
- **Migration V29** `data_export_requests` (+ partial indexes). **No change** to the `notifications` CHECK.
- **New endpoints:** `POST` / `GET /api/v1/account/export`; `POST /internal/data-export-run` (OIDC, `internal-endpoint-auth`).
- **Secrets** via `secretKey(env, name)`: R2 (account/access-key/secret/bucket) + Resend (API key) slots, staging + prod.
- **Infra/ops:** a Cloud Scheduler job for the worker; an R2 bucket + object-lifecycle rule (auto-expire export objects past the 24h window); Resend templates under `/backend/email-templates/`.
- **Reads:** own-content raw reads across the scope-matrix tables, annotated `@AllowRawPostsRead` / `@AllowMissingBlockJoin` per the Repository own-content exception (the user exports their real data, incl. their own block list — not shadow-ban/block-filtered).
- **Deferred (follow-up issues):** admin Data Export Queue surface; mobile Settings entry.
