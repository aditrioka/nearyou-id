## 1. Substrate re-check + module scaffolds

- [x] 1.1 **Pre-implementation library re-check (MUST — new pin)**: dated `WebSearch` confirming `aws.sdk.kotlin:s3` is still the canonical R2/S3 JVM client + its current stable version (`"aws-sdk-kotlin s3 R2 <current-month-year>"`); record the one-line evidence note in the first apply commit / design.md Decision D1. If a materially-better option surfaces, STOP and surface via `AskUserQuestion`.
- [x] 1.2 Pin `aws.sdk.kotlin:s3` in `gradle/libs.versions.toml` (typesafe `libs.*` alias; version from 1.1) + add a `docs/09-Versions.md` Decisions-Log row (first AWS SDK on the classpath; rationale; next review 2026-Q3).
- [x] 1.3 Scaffold `:infra:r2` module (`include(":infra:r2")` in `settings.gradle.kts`; `build.gradle.kts` JVM target). The `ObjectStore` interface lives **inside this module** (the `:infra:cloudflare-images` `ImageStore` precedent), NOT `:core`. Confirm Resend needs **no** pin (raw Ktor client reuses existing client coordinates).
- [x] 1.4 Scaffold `:infra:resend` module (`include(":infra:resend")`; raw Ktor client + kotlinx.serialization, no new pin).
- [x] 1.5 Add both modules to `dev/module-descriptions.txt` (one line each) + run `dev/scripts/sync-readme.sh --write`.
- [x] 1.6 Add `Dockerfile` builder COPY blocks for `:infra:r2` + `:infra:resend` (both backend-included, non-gated) + run `dev/scripts/check-dockerfile-module-copies.sh` (a missing COPY breaks every staging/prod image build while CI stays green).

## 2. Schema (V29)

- [x] 2.1 Add `V29__data_export_requests.sql` exactly per `specs/account-data-export/spec.md` § data_export_requests schema (table + the 3 indexes; both partial `WHERE`s `NOW()`-free).
- [x] 2.2 Add the canonical **Data Export Requests Schema** section to `docs/05-Implementation.md` (schema home), matching the migration verbatim (sibling to § Deletion Requests Schema).
- [x] 2.3 Verify the migration applies cleanly (fresh DB + `migrate-supabase-parity` shape) and `flyway validate` passes; confirm **no** `notifications` CHECK change.

## 3. `:infra:r2` — ObjectStore over R2

- [x] 3.1 Define the vendor-neutral `ObjectStore` interface (`put` / `presignedGetUrl(key, ttl)` / `delete`) **in `:infra:r2`** (package `id.nearyou.app.infra.r2`, the `ImageStore` precedent); `:backend:ktor` imports it from there.
- [x] 3.2 Implement `R2ObjectStore` in `:infra:r2` via `aws.sdk.kotlin:s3` pointed at the R2 S3 endpoint (`https://<account_id>.r2.cloudflarestorage.com`); `presignedGetUrl` via the coroutine `presignGetObject` extension (24h TTL, within R2's 7-day max).
- [x] 3.3 Resolve all R2 credentials via `secretKey(env, name)` (slots `r2-account-id`, `r2-access-key-id`, `r2-secret-access-key`, `r2-export-bucket`); never direct env reads.
- [x] 3.4 Implement `NoOpObjectStore` + Koin wiring that binds it when creds are unset (boot never fails; consumer maps to soft failure).
- [x] 3.5 Confirm no `aws.sdk.kotlin.*` / `aws.smithy.*` import escapes `:infra:r2`.

## 4. `:infra:resend` — EmailSender over Resend

- [x] 4.1 Define the vendor-neutral `EmailSender` interface (`send(to, template, idempotencyKey)`) + an `EmailTemplate` type **in `:infra:resend`** (package `id.nearyou.app.infra.resend`), NOT `:core`.
- [x] 4.2 Implement `ResendEmailSender` in `:infra:resend` via a raw Ktor client `POST /emails`; set the `Idempotency-Key` header; 3-attempt exponential backoff on 5xx; API key via `secretKey(env, name)` (`resend-api-key`).
- [x] 4.3 Add the "data export ready" email template (HTML + text, Bahasa Indonesia) under `/backend/email-templates/`; **ready-email** copy per docs/06 §380 ("Data export kamu siap diunduh") + the signed link. (The docs/03 §260 "Export akan dikirim… dalam 7 hari… berlaku 24 jam…" copy is the in-app request-confirmation shown by the deferred mobile entry, NOT this email.)
- [x] 4.4 Implement `NoOpEmailSender` + Koin wiring binding it when the key is unset; ensure no log line emits the recipient address or body (no-PII-in-logs).

## 5. Export gather + packaging (Service + Repository)

- [x] 5.1 `DataExportRepository` — own-data-only reads for the **canonical docs/06 §350** scope matrix (profile, username + edit history, DOB, hashed Google/Apple ID, analytics-consent, posts incl. own `actual_location`, soft-deleted-in-grace posts, likes, replies, follows, block list, chat **sent + received with peer id hashed**, reports-submitted, notifications, moderation actions applied, session history 90-day, subscription history). Hash every peer id via `HMAC-SHA256(export-peer-hash-secret, peer_id)` (keyed HMAC, the invite-code precedent — never a bare `SHA256`). Annotate `@AllowRawPostsRead`, `@AllowMissingBlockJoin`, and `@AllowActualLocationRead("data export own-content")` (the exact token `CoordinateJitterRule` requires — the first two alone fail the coordinate-jitter detekt lane). **Exclude** reports-received, attestation, admin-audit, CSAM, `rejected_identifiers`, the `is_shadow_banned` column itself, and any shadow-ban status (stealth invariant — even within "moderation actions applied").
- [x] 5.2 `DataExportService` — serialize each category to **JSON (singleton/state) or CSV (tabular)** per the Format column, build `manifest.json` + Bahasa-Indonesia `README.txt`, stream-zip to a temp file (bounded memory), return the archive bytes/handle + content type.
- [x] 5.3 `data_export_requests` repository ops: insert-pending (mapping the one-active unique-violation → return existing), optimistic claim (`UPDATE … WHERE status='pending'`), set-ready / set-failed, read-latest-for-user, expired-derivation on read.

## 6. Trigger + status routes

- [x] 6.1 `POST /api/v1/account/export` — authenticated; insert-pending or return the existing active request (catch the unique-violation); `202` + `{request_id, status}`; `401` unauth.
- [x] 6.2 `GET /api/v1/account/export` — authenticated; return the caller's latest status (+ `download_expires_at` + current signed URL when `ready`; derive `expired` past the deadline); never leak another user's state/key/URL.

## 7. Worker + delivery

- [x] 7.1 `POST /internal/data-export-worker` — OIDC-verified per `internal-endpoint-auth` (`GoogleOidcTokenVerifier`); scan `data_export_requests_pending_idx`, claim optimistically, process each.
- [x] 7.2 Per request: gather (§5) → upload to `ObjectStore` → `presignedGetUrl(24h)` → set `ready` + `r2_object_key` + `download_expires_at` → emit `data_export_ready` notification (`body_data {signed_url, expires_at}`, NULL target, via the existing `NotificationEmitter`) → `EmailSender.send` (idempotency key `SHA256(user_id+'data_export_ready'+timestamp_minute)`).
- [x] 7.3 Fail-soft: on object-storage/email error after retries set `status='failed'` + non-PII `error` + `attempt_count++`; never crash the worker; never leave a partial `ready`.
- [ ] 7.4 Register the Cloud Scheduler job spec for `/internal/data-export-worker` (deploy config; mirrors the existing worker scheduler entries).
- [x] 7.5 Observability: give the worker the same OTel span-attribute treatment as the shipped workers + extend `InternalEndpointSpanAttributeTest` (or equivalent) to cover `/internal/data-export-worker` (no forbidden span attributes; no PII in attrs).

## 8. Tests (one per spec scenario — no compression)

- [x] 8.1 Schema: table+indexes exist; `status` CHECK rejects unknown; one-active partial-unique forbids 2nd active row; non-active row doesn't block a new request; partial-index predicates `NOW()`-free.
- [x] 8.2 Request endpoint: first request → `202` pending; re-request while active → existing returned (no 2nd row); re-request after ready/expired/failed → new row; unauth → `401`; own-data-only (cannot enqueue another user's export).
- [x] 8.3 Status endpoint: pending status; ready status + `download_expires_at`; no-export state; no cross-user leak.
- [x] 8.4 Worker auth: invalid/absent OIDC → `401`/`403`, no processing.
- [x] 8.5 Worker happy path: pending → ready + `r2_object_key` + `download_expires_at ≈ NOW()+24h` + `data_export_ready` notification row + one email sent.
- [x] 8.6 Worker concurrency: two invocations on one pending request → exactly one claims/produces; the other claims nothing.
- [x] 8.7 Worker fail-soft (two independent cases): (a) **object storage unavailable** → `status='failed'` + `attempt_count ≥ 1` + non-PII error, no crash, no `ready`/notification/email; (b) **upload OK but Resend fails** after retries → export stays `ready` (the in-app notification delivered the link), email failure logged (no PII), NOT reverted to `failed`.
- [x] 8.8 Scope matrix (vs docs/06 §350): archive contains all canonical Included categories; chat = sent+received with peer id **hashed**; posts carry own `actual_location`; out-of-scope categories absent (reports-received, attestation, admin-audit, CSAM, `rejected_identifiers`); **shadow-ban stealth** — a shadow-banned user's export reveals no shadow-ban; no raw peer identifier leaks.
- [x] 8.9 Notification: row has `type='data_export_ready'` + `body_data {signed_url, expires_at}` + NULL target; no `notifications` CHECK migration present.
- [x] 8.10 Expiry: ready carries 24h deadline; past-deadline status reads `expired` + no fresh durable URL.
- [x] 8.11 `:infra:r2`: stored object retrievable via signed URL within TTL; URL rejected after TTL; no vendor import outside `:infra:r2`; creds via `secretKey`; boot with R2 unconfigured → no-op bound + graceful degrade. (Integration leg against a local S3-compatible mock; unit leg for URL/expiry structure + no-op.)
- [x] 8.12 `:infra:resend`: send issues `POST /emails` with rendered template + `Idempotency-Key`; 5xx retried 3× w/ backoff; same idempotency key → one delivery; the worker derives the canonical key `SHA256(user_id + 'data_export_ready' + timestamp_minute)`; boot with key unset → no-op + no throw; recipient/body never logged. (Ktor `MockEngine` for the REST leg.)
- [x] 8.13 Peer-hash determinism + secrecy: `HMAC-SHA256(export-peer-hash-secret, peer_id)` is stable for a given (secret, peer) and differs under a different secret (non-correlatable across exports); never emits a raw id or a bare `SHA256`.
- [x] 8.14 Deferred guards (negative): enumerate mounted routes — no new `/admin/*` route (only `/api/v1/account/export` + `/internal/data-export-worker`); confirm `:mobile:app` is untouched by this change.
- [x] 8.15 Run the full local gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` (+ the new infra modules' tests) green on fresh DB containers.

## 9. Docs + deferred follow-ups

- [x] 9.1 Update `docs/07-Operations.md` § Core Features → "Data Export Queue": note the user-facing producer SHIPPED (this change) + the admin monitoring/trigger surface deferred (link the follow-up).
- [x] 9.2 File a `follow-up` issue (labels `follow-up` + `admin`): **admin Data Export Queue** surface to be built on `data_export_requests` (async trigger view + status list + download deep-link).
- [x] 9.3 File a `follow-up` issue (labels `follow-up` + `mobile`): mobile Settings **"Unduh Data Saya"** entry (row + confirm dialog + status banner) calling the shipped endpoints.
- [x] 9.4 Resolve the remaining design.md Open Questions: mobile-entry defer-vs-fold (Phase D review); export-row retention cadence (note or follow-up). (Scope matrix + chat inclusion already resolved against docs/06 §350.)

## 10. Staging deploy + smoke (deploy tasks stay unchecked until infra provisioned)

- [ ] 10.0 **MERGE-GATE — V29 Flyway collision**: `V29__data_export_requests.sql` collides with in-flight `V29__csam_detection_archive` (PR #358) and possibly other in-flight migration-bearing PRs (#353 referral, #354 image, #355, #360). CI passes in isolation (CI builds a fresh DB from this branch only); the collision only fails `migrate-supabase-parity` at merge-to-`main` if a sibling V29 lands first. **Before merge**: `git fetch origin main`, take the next free `V<N>`, `git mv` the migration + update refs (docs/05 § Data Export Requests Schema, the spec schema block, the V29 mentions in design/proposal). Additive + not yet applied to real staging/prod → renumber is safe (the documented parallel-session Flyway-collision fix).

- [ ] 10.1 Provision staging secret slots (`r2-*`, `resend-api-key`, `export-peer-hash-secret`) + the R2 export bucket with a 24h object-lifecycle rule; grant the Cloud Run runtime SA `secretAccessor`.
- [ ] 10.2 Manual branch deploy (`gh workflow run deploy-staging.yml --ref account-data-export`) + `dev/scripts/smoke-account-data-export.sh`: request export → worker run → status `ready` → notification present → email delivered → signed URL downloads the archive → past-TTL reads `expired`.
- [ ] 10.3 Production secret slots + R2 bucket + Cloud Scheduler job (Pre-Launch; left unchecked until prod infra exists).
