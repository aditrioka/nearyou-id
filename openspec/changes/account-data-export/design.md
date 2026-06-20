## Context

UU PDP grants every user the right to a portable copy of their personal data. The **erasure** half of this surface already shipped (`account-deletion` + `account-hard-delete-worker`, V27); the **portability** half is unbuilt. Supporting substrate is partially in place: the `data_export_ready` notification type ships in the V10 catalog (body_data `{signed_url, expires_at}`), the `deletion_requests` request-lifecycle pattern is a proven template, and three OIDC-authed Cloud-Scheduler workers already exist (`suspension-unban`, `privacy-flip`, `account-hard-delete`). Two vendor substrates the canonical docs call for — Cloudflare R2 (signed download URL, docs/03 §260 + docs/04 §200) and Resend (transactional email, docs/04 §194) — are **not yet on the backend classpath** (`:infra:cloudflare-images` is the only `:infra` storage/HTTP-vendor module today; Resend was smoke-tested 2026-04-27 but never modularized).

This change is the user-facing **producer**. The admin **Data Export Queue** monitoring surface (docs/07) and the mobile Settings entry (docs/03) are deliberately separate (see Non-Goals).

## Goals / Non-Goals

**Goals:**
- A user can request a portable copy of all their own data and receive it as a time-limited download link by email + in-app notification, within a 7-day SLA.
- Establish two reusable, vendor-neutral `:infra:*` substrates — object storage (R2) and transactional email (Resend) — behind interfaces, so future changes (image upload, CSAM archive, deletion-confirmation email, subscription receipts) consume them instead of re-introducing vendor SDKs.
- Own-data-only, fail-soft, no-PII-in-logs, single-active-request-per-user.

**Non-Goals:**
- **Admin Data Export Queue** monitoring/trigger surface (docs/07) — a separate admin-lane change; captured here as an explicit deferred requirement.
- **Mobile Settings "Unduh Data Saya"** entry (docs/03) — this change ships the backend the entry calls; the Compose UI is deferred (captured as an explicit deferred requirement). *Surface to the user at review: fold in vs. fast-follow.*
- Marketing-email infrastructure, Resend bounce-webhook handling, the full S3 object API — only the slice each substrate needs now.
- Re-export throttling beyond "one active request per user" + the natural per-user signup/auth rate limits.

## Decisions

### D1 — Object storage = Cloudflare R2 via the AWS SDK for Kotlin (`:infra:r2`)
Canonical per docs/03 §260 ("R2 signed URL") + docs/04 §200. New `:infra:r2` module exposing a **vendor-neutral interface**:
```
interface ObjectStore {
    suspend fun put(key: String, bytes: ByteArray, contentType: String)
    fun presignedGetUrl(key: String, ttl: Duration): String   // capability URL
    suspend fun delete(key: String)
}
```
Implementation = **AWS SDK for Kotlin S3 client** (`aws.sdk.kotlin:s3`) pointed at the R2 S3 endpoint (`https://<account_id>.r2.cloudflarestorage.com`). Rationale (verified 2026-06-20 via dated WebSearch): Cloudflare officially documents the `aws-sdk-kotlin` path for R2 ([R2 docs › aws-sdk-kotlin](https://developers.cloudflare.com/r2/examples/aws/aws-sdk-kotlin/)); the SDK ships a coroutine-native `presignGetObject` extension ([AWS SDK for Kotlin › Presign requests](https://docs.aws.amazon.com/sdk-for-kotlin/latest/developer-guide/presign-requests.html)); R2 supports presigned-URL expiry 1s–7days, so the 24h TTL is in-range ([R2 › Presigned URLs](https://developers.cloudflare.com/r2/api/s3/presigned-urls/)). Coroutine-native fits the Ktor backend better than the blocking Java SDK v2.
- **Alternatives considered:** AWS SDK for **Java** v2 (`S3Presigner`) — battle-tested but blocking, needs `withContext(Dispatchers.IO)` wrapping; rejected for a coroutine backend. MinIO Java client — extra dependency, no advantage over the official SDK. Raw signed-request crypto — error-prone, reinvents SigV4.
- **New pin** (`aws.sdk.kotlin:s3`, first AWS SDK on the classpath) → the project's **pre-implementation library re-check MUST fire at `/opsx:apply`** (tasks Phase 1) to confirm the current version + that this remains canonical.

### D2 — Transactional email = Resend via a raw Ktor client (`:infra:resend`)
Canonical per docs/04 §194–218. New `:infra:resend` module exposing:
```
interface EmailSender {
    suspend fun send(to: String, template: EmailTemplate, idempotencyKey: String): SendResult
}
```
Implementation = **raw Ktor client** against the Resend REST `POST /emails` endpoint. Rationale (verified 2026-06-20): Resend publishes SDKs for Node/Python/Ruby/Go/Java but **none for Kotlin/Ktor** ([Resend API]); the project's established pattern for "no official JVM SDK" is a raw Ktor client behind an `:infra:*` interface — exactly the `:infra:cloudflare-images` precedent (docs/09 V44 row: "Cloudflare Images has no official JVM SDK → raw Ktor client, no new pin"). **No new pin** — reuses the Ktor client + kotlinx.serialization already on the backend classpath. Idempotency is two-layer: the app-level `resend_idempotency_key = SHA256(user_id + 'data_export_ready' + timestamp_minute)` (docs/04 §210) plus Resend's `Idempotency-Key` header. 3-retry exponential backoff on 5xx; HTML+text template versioned under `/backend/email-templates/`.
- **Alternatives considered:** Resend **Java** SDK — pulls a transitive HTTP stack (OkHttp) duplicating Ktor's; rejected for footprint + the no-vendor-SDK-leak posture. SMTP via JavaMail — Resend is API-first; SMTP loses the idempotency header + structured error handling.

### D3 — Async execution = OIDC internal worker + Cloud Scheduler (not a Cloud Run Job)
`POST /internal/data-export-run`, OIDC-verified (`internal-endpoint-auth` + `GoogleOidcTokenVerifier`), invoked by Cloud Scheduler — the shipped `suspension-unban` / `privacy-flip` / `account-hard-delete` pattern. The trigger endpoint only **enqueues** (`status='pending'`); the worker does the heavy gather/serialize/upload off the request path (avoids Cloud Run request-timeout risk on large accounts). The worker **claims** a row optimistically — `UPDATE … SET status='processing', started_at=NOW() WHERE id=? AND status='pending'` (affected-rows guard) — so concurrent invocations never double-process. The 7-day SLA gives ample slack; a few-minute scheduler cadence is plenty.
- **Alternative considered:** a dedicated Cloud Run **Job** (like backups) — heavier ops surface for lightweight per-request work; the internal-endpoint pattern is the established lighter path.

### D4 — Export artifact = ZIP of JSON + CSV + manifest + README
Per the canonical Format column (docs/06 §350): singleton/state categories (profile, DOB, hashed auth id, analytics-consent) as **JSON**; tabular/list categories (posts, replies, likes, follows, blocks, chat, reports, notifications, moderation actions, session history, subscription history, username + edit history) as **CSV**. Plus a `manifest.json` (user id, `generated_at`, schema version, file list) and a Bahasa-Indonesia `README.txt` (what's included). Streamed to a temp file then zipped + uploaded (bounded memory).

### D5 — Scope matrix = the canonical docs/06 §350 table (own-data-only)
The included/excluded set is **the canonical Data Export Scope Matrix (docs/06 §350)**, mirrored faithfully in `specs/account-data-export/spec.md` — NOT a re-invented list. Points the implementation must honor (these corrected an earlier draft that diverged from canonical):
- **Chat = sent AND received**, with the **peer id hashed** — the canonical decision protects the counterparty by hashing their id, not by omitting received messages (an earlier "own-sent-only" draft contradicted canonical and was dropped).
- **Posts carry the user's own `actual_location`** (+ `city_name`) — own-data is the sanctioned non-`display_location` read (the admin-path exception applies to own-content export).
- **Included** that the implementation must not drop: hashed Google/Apple ID (self-reference), session history (fingerprint, IP — 90-day window), username-change history, post edit history, moderation actions applied.
- **Shadow-ban stealth invariant OVERRIDES** the "moderation actions applied" inclusion — the export MUST NOT reveal a shadow-ban (critical CLAUDE.md invariant; wins over the canonical matrix row).
- Reads are own-content raw reads → `@AllowRawPostsRead` / `@AllowMissingBlockJoin` + the sanctioned own-`actual_location` read.

### D6 — `data_export_requests` schema (V29)
```sql
CREATE TABLE data_export_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending','processing','ready','expired','failed')),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    r2_object_key TEXT,
    download_expires_at TIMESTAMPTZ,
    attempt_count INT NOT NULL DEFAULT 0,
    error TEXT
);
-- Worker scan (NOW()-free):
CREATE INDEX data_export_requests_pending_idx ON data_export_requests(requested_at)
    WHERE status = 'pending';
-- One active request per user (idempotency at the DB level; NOW()-free):
CREATE UNIQUE INDEX data_export_requests_one_active_idx ON data_export_requests(user_id)
    WHERE status IN ('pending','processing');
-- Latest-status lookup for GET:
CREATE INDEX data_export_requests_user_recent_idx ON data_export_requests(user_id, requested_at DESC);
```
The partial-unique index makes the idempotency structural: a second `POST` while one is active raises a unique violation → the route catches it and returns the existing request (200, not a duplicate). A `ready`/`expired`/`failed` request does **not** block a fresh request (the active set is `pending|processing` only). Both partial `WHERE`s are `NOW()`-free (partial-index lint invariant).

### D7 — Notification = reuse the shipped `data_export_ready` type (no migration)
The worker emits `data_export_ready` via the existing `NotificationEmitter` with `body_data = {signed_url, expires_at}`, `target_type`/`target_id` NULL — exactly the V10 catalog shape (docs/05 §596). **No `notifications` CHECK migration.** Do NOT duplicate any id into `body_data`.

### D8 — Admin Data Export Queue is out of scope (explicit deferred requirement)
The spec carries a deferred requirement: this capability does NOT expose an admin monitoring/trigger surface; the admin Data Export Queue (docs/07) is a separate admin-lane change. A negative-guard scenario asserts no `/admin/*` route is added here, and a follow-up issue tracks it — so the future admin change has a requirement to MODIFY.

### D9 — Mobile Settings entry deferred (explicit deferred requirement)
This change ships the backend endpoints; the Compose "Unduh Data Saya" Settings row + confirm dialog + status banner are deferred to a mobile-lane follow-up. Captured as an explicit deferred requirement + follow-up issue. **To surface at review:** the operator's complete-scope preference may favor folding the mobile entry in — present it as an option in the Phase D handoff.

### Standards conformance (docs/11 Pattern Registry)
Builds on, without deviating from: **backend layering** (Route → Service → Repository; the export-gather logic lives in a Service, raw SQL in a Repository); the **internal-worker pattern** (OIDC + `GoogleOidcTokenVerifier` + Cloud Scheduler, the suspension-unban precedent); **JDBC discipline** (Hikari pool, bounded per-user queries, no N+1 — the scope-matrix gather is a fixed set of per-user queries); **new `:infra:*` module boundaries** (`ObjectStore` / `EmailSender` interfaces in `:core`/`:infra`, no `aws.sdk.*` or Resend types leaking into `:backend:ktor`, the no-vendor-SDK-outside-`:infra` invariant). **No new pattern is introduced → no docs/11 §Pattern Registry amendment needed.**

## Risks / Trade-offs

- **Large-account export exceeds worker memory/time** → stream each category (JSON/CSV) to a temp file, zip, upload; one request per claim; on failure set `status='failed'` + `attempt_count++`; a bounded-retry reaper (or next scheduler tick) can re-pend low-attempt failures.
- **Signed URL is a bearer capability (in email + notification body_data)** → short 24h TTL **and** an R2 lifecycle rule that deletes the export object after the window (defense-in-depth: a leaked URL is dead once expired *or* the object is gone); the URL authorizes read of that one object only. This is the standard signed-URL model and is the canonical docs/03+04 design.
- **PII leak via logs/traces** → never log the signed URL, email address, or export contents; log only `{request_id, user_id, status}`. Email address is passed to Resend, not logged.
- **Cross-user data leak** → own-data-only by construction (every query keyed on the authenticated `user_id`); chat exports sent+received but the **peer id is hashed** (counterparty protected by hashing, not omission); follow/block peer ids are likewise hashed; block list is the requester's own.
- **R2/Resend unprovisioned in dev/staging** → fail-soft `NoOpObjectStore` / `NoOpEmailSender` (the `NoOpImageModerator` precedent): the worker marks the request `failed` with a clear `error` rather than crashing; app boots without the creds.
- **Concurrent worker double-processing** → optimistic claim via conditional `UPDATE … WHERE status='pending'` affected-rows guard; only the claimer proceeds.
- **Scope creep across ~9 tables** → the spec enumerates the must-include set (anchored to the Pre-Launch checklist) as discrete scenarios; field-level `users`-row scope is an Open Question resolved before apply.

## Migration Plan

1. **V29** `data_export_requests` (+ 3 indexes) — additive, forward-only, no backfill. No `notifications` CHECK change.
2. New modules `:infra:r2` + `:infra:resend` → update `settings.gradle.kts`, `gradle/libs.versions.toml` (R2 pin only), `dev/module-descriptions.txt` + `sync-readme.sh --write`, **and the `Dockerfile` builder COPY blocks** (both are backend-included, non-mobile-gated — a missing COPY breaks every staging/prod image build while CI stays green; run `dev/scripts/check-dockerfile-module-copies.sh`).
3. Secrets via `secretKey(env, name)`: `r2-account-id` / `r2-access-key-id` / `r2-secret-access-key` / `r2-export-bucket` + `resend-api-key` (staging slots first; prod at Pre-Launch). Fail-soft means unprovisioned slots don't block boot.
4. Operator: create the R2 export bucket + a 24h object-lifecycle rule; create the Cloud Scheduler job for `/internal/data-export-run`; provision the Resend domain/template. Staging-first per the deploy convention.
5. **Rollback:** drop the two routes (no rows created if the endpoint is absent); the table + modules are inert without the scheduler + creds.

## Open Questions

- **Scope matrix + chat inclusion** — RESOLVED by reconciliation against the canonical docs/06 §350 Data Export Scope Matrix (chat = sent+received peer-hashed; session history + hashed auth id included; shadow-ban excluded for stealth). No open question remains; the spec mirrors the canonical table.
- **Mobile Settings entry** — defer (D9) vs. fold in (operator complete-scope preference). Surface at Phase D review.
- **Export-row retention** — how long to keep `ready/expired` rows (audit trail) before purge? Proposal: align to a dedicated purge or the 90-day notifications-purge cadence; confirm or file a follow-up.
