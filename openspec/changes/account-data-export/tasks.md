## 1. Substrate re-check + module scaffolds

- [ ] 1.1 **Pre-implementation library re-check (MUST — new pin)**: dated `WebSearch` confirming `aws.sdk.kotlin:s3` is still the canonical R2/S3 JVM client + its current stable version (`"aws-sdk-kotlin s3 R2 <current-month-year>"`); record the one-line evidence note in the first apply commit / design.md Decision D1. If a materially-better option surfaces, STOP and surface via `AskUserQuestion`.
- [ ] 1.2 Pin `aws.sdk.kotlin:s3` in `gradle/libs.versions.toml` (typesafe `libs.*` alias; version from 1.1) + add a `docs/09-Versions.md` Decisions-Log row (first AWS SDK on the classpath; rationale; next review 2026-Q3).
- [ ] 1.3 Scaffold `:infra:r2` module (`include(":infra:r2")` in `settings.gradle.kts`; `build.gradle.kts` JVM target, depends on `:core` for the interface). Confirm Resend needs **no** pin (raw Ktor client reuses existing client coordinates).
- [ ] 1.4 Scaffold `:infra:resend` module (`include(":infra:resend")`; raw Ktor client + kotlinx.serialization, no new pin).
- [ ] 1.5 Add both modules to `dev/module-descriptions.txt` (one line each) + run `dev/scripts/sync-readme.sh --write`.
- [ ] 1.6 Add `Dockerfile` builder COPY blocks for `:infra:r2` + `:infra:resend` (both backend-included, non-gated) + run `dev/scripts/check-dockerfile-module-copies.sh` (a missing COPY breaks every staging/prod image build while CI stays green).

## 2. Schema (V29)

- [ ] 2.1 Add `V29__data_export_requests.sql` exactly per `specs/account-data-export/spec.md` § data_export_requests schema (table + the 3 indexes; both partial `WHERE`s `NOW()`-free).
- [ ] 2.2 Add the canonical **Data Export Requests Schema** section to `docs/05-Implementation.md` (schema home), matching the migration verbatim (sibling to § Deletion Requests Schema).
- [ ] 2.3 Verify the migration applies cleanly (fresh DB + `migrate-supabase-parity` shape) and `flyway validate` passes; confirm **no** `notifications` CHECK change.

## 3. `:infra:r2` — ObjectStore over R2

- [ ] 3.1 Define the vendor-neutral `ObjectStore` interface (`put` / `presignedGetUrl(key, ttl)` / `delete`) in `:core` (so `:backend:ktor` depends only on the interface).
- [ ] 3.2 Implement `R2ObjectStore` in `:infra:r2` via `aws.sdk.kotlin:s3` pointed at the R2 S3 endpoint (`https://<account_id>.r2.cloudflarestorage.com`); `presignedGetUrl` via the coroutine `presignGetObject` extension (24h TTL, within R2's 7-day max).
- [ ] 3.3 Resolve all R2 credentials via `secretKey(env, name)` (slots `r2-account-id`, `r2-access-key-id`, `r2-secret-access-key`, `r2-export-bucket`); never direct env reads.
- [ ] 3.4 Implement `NoOpObjectStore` + Koin wiring that binds it when creds are unset (boot never fails; consumer maps to soft failure).
- [ ] 3.5 Confirm no `aws.sdk.kotlin.*` / `aws.smithy.*` import escapes `:infra:r2`.

## 4. `:infra:resend` — EmailSender over Resend

- [ ] 4.1 Define the vendor-neutral `EmailSender` interface (`send(to, template, idempotencyKey)`) + an `EmailTemplate` type in `:core`.
- [ ] 4.2 Implement `ResendEmailSender` in `:infra:resend` via a raw Ktor client `POST /emails`; set the `Idempotency-Key` header; 3-attempt exponential backoff on 5xx; API key via `secretKey(env, name)` (`resend-api-key`).
- [ ] 4.3 Add the "data export ready" email template (HTML + text, Bahasa Indonesia) under `/backend/email-templates/`; **ready-email** copy per docs/06 §380 ("Data export kamu siap diunduh") + the signed link. (The docs/03 §260 "Export akan dikirim… dalam 7 hari… berlaku 24 jam…" copy is the in-app request-confirmation shown by the deferred mobile entry, NOT this email.)
- [ ] 4.4 Implement `NoOpEmailSender` + Koin wiring binding it when the key is unset; ensure no log line emits the recipient address or body (no-PII-in-logs).

## 5. Export gather + packaging (Service + Repository)

- [ ] 5.1 `DataExportRepository` — own-data-only reads for the **canonical docs/06 §350** scope matrix (profile, username + edit history, DOB, hashed Google/Apple ID, analytics-consent, posts incl. own `actual_location`, soft-deleted-in-grace posts, likes, replies, follows, block list, chat **sent + received with peer id hashed**, reports-submitted, notifications, moderation actions applied, session history 90-day, subscription history). Hash every peer id (follow/block/chat/report target). Annotate `@AllowRawPostsRead` / `@AllowMissingBlockJoin` + the sanctioned own-`actual_location` read. **Exclude** reports-received, attestation, admin-audit, CSAM, `rejected_identifiers`, and any shadow-ban status (stealth invariant — even within "moderation actions applied").
- [ ] 5.2 `DataExportService` — serialize each category to **JSON (singleton/state) or CSV (tabular)** per the Format column, build `manifest.json` + Bahasa-Indonesia `README.txt`, stream-zip to a temp file (bounded memory), return the archive bytes/handle + content type.
- [ ] 5.3 `data_export_requests` repository ops: insert-pending (mapping the one-active unique-violation → return existing), optimistic claim (`UPDATE … WHERE status='pending'`), set-ready / set-failed, read-latest-for-user, expired-derivation on read.

## 6. Trigger + status routes

- [ ] 6.1 `POST /api/v1/account/export` — authenticated; insert-pending or return the existing active request (catch the unique-violation); `202` + `{request_id, status}`; `401` unauth.
- [ ] 6.2 `GET /api/v1/account/export` — authenticated; return the caller's latest status (+ `download_expires_at` + current signed URL when `ready`; derive `expired` past the deadline); never leak another user's state/key/URL.

## 7. Worker + delivery

- [ ] 7.1 `POST /internal/data-export-run` — OIDC-verified per `internal-endpoint-auth` (`GoogleOidcTokenVerifier`); scan `data_export_requests_pending_idx`, claim optimistically, process each.
- [ ] 7.2 Per request: gather (§5) → upload to `ObjectStore` → `presignedGetUrl(24h)` → set `ready` + `r2_object_key` + `download_expires_at` → emit `data_export_ready` notification (`body_data {signed_url, expires_at}`, NULL target, via the existing `NotificationEmitter`) → `EmailSender.send` (idempotency key `SHA256(user_id+'data_export_ready'+timestamp_minute)`).
- [ ] 7.3 Fail-soft: on object-storage/email error after retries set `status='failed'` + non-PII `error` + `attempt_count++`; never crash the worker; never leave a partial `ready`.
- [ ] 7.4 Register the Cloud Scheduler job spec for `/internal/data-export-run` (deploy config; mirrors the existing worker scheduler entries).

## 8. Tests (one per spec scenario — no compression)

- [ ] 8.1 Schema: table+indexes exist; `status` CHECK rejects unknown; one-active partial-unique forbids 2nd active row; non-active row doesn't block a new request; partial-index predicates `NOW()`-free.
- [ ] 8.2 Request endpoint: first request → `202` pending; re-request while active → existing returned (no 2nd row); re-request after ready/expired/failed → new row; unauth → `401`; own-data-only (cannot enqueue another user's export).
- [ ] 8.3 Status endpoint: pending status; ready status + `download_expires_at`; no-export state; no cross-user leak.
- [ ] 8.4 Worker auth: invalid/absent OIDC → `401`/`403`, no processing.
- [ ] 8.5 Worker happy path: pending → ready + `r2_object_key` + `download_expires_at ≈ NOW()+24h` + `data_export_ready` notification row + one email sent.
- [ ] 8.6 Worker concurrency: two invocations on one pending request → exactly one claims/produces; the other claims nothing.
- [ ] 8.7 Worker fail-soft: object-storage/email unconfigured/erroring → `status='failed'` + non-PII error, no crash, no partial ready.
- [ ] 8.8 Scope matrix (vs docs/06 §350): archive contains all canonical Included categories; chat = sent+received with peer id **hashed**; posts carry own `actual_location`; out-of-scope categories absent (reports-received, attestation, admin-audit, CSAM, `rejected_identifiers`); **shadow-ban stealth** — a shadow-banned user's export reveals no shadow-ban; no raw peer identifier leaks.
- [ ] 8.9 Notification: row has `type='data_export_ready'` + `body_data {signed_url, expires_at}` + NULL target; no `notifications` CHECK migration present.
- [ ] 8.10 Expiry: ready carries 24h deadline; past-deadline status reads `expired` + no fresh durable URL.
- [ ] 8.11 `:infra:r2`: stored object retrievable via signed URL within TTL; URL rejected after TTL; no vendor import outside `:infra:r2`; creds via `secretKey`; boot with R2 unconfigured → no-op bound + graceful degrade. (Integration leg against a local S3-compatible mock; unit leg for URL/expiry structure + no-op.)
- [ ] 8.12 `:infra:resend`: send issues `POST /emails` with rendered template + `Idempotency-Key`; 5xx retried 3× w/ backoff; same idempotency key → one delivery; boot with key unset → no-op + no throw; recipient/body never logged. (Ktor `MockEngine` for the REST leg.)
- [ ] 8.13 Run the full local gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` (+ the new infra modules' tests) green on fresh DB containers.

## 9. Docs + deferred follow-ups

- [ ] 9.1 Update `docs/07-Operations.md` § Core Features → "Data Export Queue": note the user-facing producer SHIPPED (this change) + the admin monitoring/trigger surface deferred (link the follow-up).
- [ ] 9.2 File a `follow-up` issue (labels `follow-up` + `admin`): **admin Data Export Queue** surface to be built on `data_export_requests` (async trigger view + status list + download deep-link).
- [ ] 9.3 File a `follow-up` issue (labels `follow-up` + `mobile`): mobile Settings **"Unduh Data Saya"** entry (row + confirm dialog + status banner) calling the shipped endpoints.
- [ ] 9.4 Resolve the remaining design.md Open Questions: mobile-entry defer-vs-fold (Phase D review); export-row retention cadence (note or follow-up). (Scope matrix + chat inclusion already resolved against docs/06 §350.)

## 10. Staging deploy + smoke (deploy tasks stay unchecked until infra provisioned)

- [ ] 10.1 Provision staging secret slots (`r2-*`, `resend-api-key`) + the R2 export bucket with a 24h object-lifecycle rule; grant the Cloud Run runtime SA `secretAccessor`.
- [ ] 10.2 Manual branch deploy (`gh workflow run deploy-staging.yml --ref account-data-export`) + `dev/scripts/smoke-account-data-export.sh`: request export → worker run → status `ready` → notification present → email delivered → signed URL downloads the archive → past-TTL reads `expired`.
- [ ] 10.3 Production secret slots + R2 bucket + Cloud Scheduler job (Pre-Launch; left unchecked until prod infra exists).
