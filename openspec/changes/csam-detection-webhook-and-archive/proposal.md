## Why

When the Cloudflare CSAM Scanning Tool matches a served image against NCMEC/partner hash lists, it blocks the URL (HTTP 451) and emails the operator — but there is **no backend response**: the offending post stays in the database, the uploader keeps their session, and nothing is preserved for the legally-mandated Kominfo (Ditjen Aptika) report due **within <24h of detection** (`docs/06` § Kominfo Reporting Obligation). This is a hard Pre-Launch security-review gate (`docs/08` Pre-Launch security review: "CSAM webhook end-to-end tested", "CSAM archive retention + purge worker tested") and a launch blocker for image uploads — the highest-severity content category. The image-upload infrastructure this hangs off of is already shipped (`premium-image-upload-pipeline`, V26 `image_uploads` ledger), and Open Decision #33 (CSAM Trigger Path) is **Resolved** (admin-triggered MVP). The codebase pre-names this change in [`V26__image_uploads.sql:7`](../../../backend/ktor/src/main/resources/db/migration/V26__image_uploads.sql) and defers CSAM to it by name in [`premium-image-upload/spec.md:143`](../../specs/premium-image-upload/spec.md).

## What Changes

- **New `csam_detection_archive` table** (next free migration **V29**) — legal-preservation record per detected match: plaintext `image_hash` + matched NCMEC reference + `cf_match_id` (needed for Kominfo filing), AES-256-GCM `encrypted_metadata BYTEA`, `kominfo_report_id` / `kominfo_reported_at`, `expires_at` (90-day preservation that **bypasses** the banned uploader's cascade-delete), `UNIQUE(image_hash)` + partial `UNIQUE(cf_match_id)` for idempotent `ON CONFLICT` dedup that **enriches without resetting** the original `source`. No image bytes are ever retained.
- **New `POST /internal/csam-webhook` handler** with a **fixed-policy** auto-action (identical in `docs/02` § Image Upload Flow and `docs/06` line 201): resolve uploader + post from the matched image via the `image_uploads` ledger → hard-delete the post → permanent-ban the uploader + bump `token_version` → cascade-delete the uploader's other posts → AES-256-GCM-archive metadata → queue the Kominfo report (`kominfo_reported_at IS NULL` = pending) + enqueue a `moderation_queue` row `trigger = 'csam_detected'` (enum value already reserved at V9 — no schema change). Idempotent on re-trigger.
- **Two non-OIDC invocation paths** (the `/internal/*` OIDC opt-out the `internal-endpoint-auth` spec already names): (a) **admin-internal MVP** — invoked from the Admin Panel with the admin's scoped session + a session-bound CSRF token (admin pastes the matched URL from CF's daily email); (b) **Cloudflare Worker (Phase 2+)** — `Bearer` token + `HMAC-SHA256` body signature (`cf-worker-csam-secret`), both verified, rate-limited 100 req/hour per IP.
- **AES-256-GCM encryption helper** — JDK `javax.crypto`, key via `secretKey(env, "csam-archive-aes-key")`; **fail-soft** when the slot is unprovisioned (NoOp-degrade, mirroring `:infra:cloud-vision`/`:infra:cloudflare-images` — the slot is operator-provisioned at the Month-6 image launch).
- **New `/internal/csam-archive-purge` worker** (OIDC-gated daily Cloud Scheduler job) — `DELETE WHERE expires_at < NOW() AND kominfo_reported_at IS NOT NULL` (preservation extended while a report/investigation is still pending). Partial-index-clean.
- **System-actor audit** — the CF-Worker/system path writes `admin_actions_log` rows under the `system` sentinel admin (the admin-manual path uses the acting admin's id).
- **Deferred (explicit, as positive deferral requirements):** the **Admin CSAM Detection Log Viewer + paste-URL trigger UI + decrypt-metadata view + file-to-Kominfo tracking** (`docs/07` § CSAM Detection Log Viewer) → follow-on admin change; the **Cloudflare Worker deployment** is operator ops (this change ships + tests the handler's CF-Worker auth path). No mobile work.

## Capabilities

### New Capabilities
- `csam-detection`: the CSAM takedown subsystem — the `csam_detection_archive` table + AES-256-GCM metadata encryption + idempotent dedup, the `POST /internal/csam-webhook` handler and its fixed-policy auto-action (hard-delete / permanent-ban / cascade / archive / Kominfo-queue), and the `/internal/csam-archive-purge` retention worker.

### Modified Capabilities
- `internal-endpoint-auth`: add the concrete `/internal/csam-webhook` non-OIDC auth contract (admin-internal session+CSRF **and** CF-Worker Bearer+HMAC, both must opt out of the OIDC plugin and provide their own auth; a bare OIDC token on the route is rejected) plus the OIDC-gated `/internal/csam-archive-purge` worker route — concretizing the opt-out the spec already names generically.

## Impact

- **Migration:** new `V29__csam_detection_archive.sql` (contended next-version with 4 in-flight parallel sessions — #353/#354/#355/#356; `git mv` to the next free `V<N>` pre-merge if a sibling lands first).
- **Backend (`:backend:ktor`):** new `moderation`/`internal` handler + routes (`/internal/csam-webhook`, `/internal/csam-archive-purge`), an AES-256-GCM helper, a `csam_detection_archive` repository, uploader-resolution via the `image_uploads` ledger; raw `posts`/`users` writes in the internal-worker context (annotated per the `RawFromPostsRule` allowlist).
- **Secrets (already DESIGN-reserved, `docs/05` line 22):** `csam-archive-aes-key`, `cf-worker-csam-secret` (+ `staging-*` mirror) — read via `secretKey(env, name)` only.
- **Reuses, does not change:** the `moderation_queue` `csam_detected` trigger enum (V9), the `image_uploads` ledger (V26, read-only linkage), the `system-actor` sentinel, the `admin_actions_log` schema.
- **Launch precondition (restated, owned by `premium-image-upload`):** the zone-level CF CSAM Scanning Tool must be enabled **before** `image_upload_enabled` flips TRUE — this handler is the *response* to a match, not the scanner.
- **No new library pin** (AES-GCM is JDK crypto) → no `libs.versions.toml` change.
