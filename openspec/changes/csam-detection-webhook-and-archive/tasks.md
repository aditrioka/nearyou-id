## 1. Pre-flight (apply-time re-checks)

- [ ] 1.1 Re-confirm the next free Flyway version at apply time (4 parallel sessions are racing #353/#354/#355/#356): `ls backend/ktor/src/main/resources/db/migration | sed -E 's/V([0-9]+)__.*/\1/' | sort -n | tail -1` → use `V<max+1>`; this change assumes **V29** but `git mv` to the next free `V<N>` (+ bump in-file/docs refs) if a sibling merged first.
- [ ] 1.2 Reconciliation note (no edit unless confirmed): verify `openspec/specs/premium-image-upload/spec.md:143-151` ("CSAM subsystem deferred / no `/internal/csam-webhook` exists") is scoped "introduced by **this** change" (= #325) and stays true — default is **leave untouched** (avoids archive-conflict with `image-attached-posts` #354). Only touch it if the B.3 pass concludes a surgical `MODIFIED` is required.
- [ ] 1.3 Confirm `docs/11` § Pattern Registry needs **no amendment** (this change introduces no new pattern for a listed concern — design.md § Standards conformance). External-data sanity check is **N/A** (no OSM/BPS/CC-BY source).

## 2. Migration — `csam_detection_archive` table

- [ ] 2.1 Create `V29__csam_detection_archive.sql` (or the re-confirmed `V<N>`): `image_hash TEXT NOT NULL UNIQUE`, `cf_match_id TEXT`, `ncmec_reference TEXT`, `source TEXT NOT NULL CHECK (source IN ('admin_manual','cf_worker'))`, `encrypted_metadata BYTEA`, `kominfo_report_id TEXT`, `kominfo_reported_at TIMESTAMPTZ`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `expires_at TIMESTAMPTZ NOT NULL`. **No FK to `users`** (legal-preservation: survives the uploader's hard-delete).
- [ ] 2.2 Add the partial `UNIQUE` index on `cf_match_id` `WHERE cf_match_id IS NOT NULL` (immutable predicate — partial-index-clean, no `NOW()`).
- [ ] 2.3 Add a header comment (mirroring the V26 style) linking back to this change + the `image_uploads` ledger; confirm the migration applies cleanly against the parity-init Postgres (CI `migrate-supabase-parity`).

## 3. AES-256-GCM encryption helper

- [ ] 3.1 Add a JDK `javax.crypto` AES-256-GCM helper (alongside the existing HMAC/jitter crypto utilities — no `:infra:*` module, no `libs.versions.toml` change) reading the key via `secretKey(env, "csam-archive-aes-key")`.
- [ ] 3.2 Make it **fail-soft**: a missing/unprovisioned key slot returns a NoOp encryptor (encrypt → `null` ciphertext) without throwing, mirroring `:infra:cloud-vision`/`:infra:cloudflare-images`.
- [ ] 3.3 Provide the matching decrypt (used only by the deferred admin viewer + by round-trip tests here); encrypt and decrypt round-trip byte-for-byte with the same key.

## 4. Archive repository

- [ ] 4.1 `CsamArchiveRepository.archive(...)` — `INSERT ... ON CONFLICT (image_hash) DO UPDATE` that **enriches** `cf_match_id` (and other newly-supplied NULLs) **without** resetting `source`/`created_at`; set `expires_at = created_at + INTERVAL '90 days'`, `kominfo_reported_at = NULL`.
- [ ] 4.2 `CsamArchiveRepository.purgeExpiredReported()` — `DELETE WHERE expires_at < NOW() AND kominfo_reported_at IS NOT NULL` (NOW() in a DELETE WHERE is allowed; only partial-INDEX WHERE forbids it).
- [ ] 4.3 Uploader/post resolution helper over the `image_uploads` ledger (`cf_image_id → uploader_user_id`); raw reads/writes carry the `RawFromPostsRule` / block-join allowlist annotations (internal-worker context).

## 5. Takedown service (atomic)

- [ ] 5.1 `CsamService.handleDetection(...)` runs the fixed-policy sequence in **one transaction**: resolve uploader+post via the ledger → tombstone the affected post (`posts.deleted_at`) → permanent-ban uploader (`is_banned = TRUE`, `suspended_until` NULL, `token_version + 1`) → cascade-tombstone the uploader's other posts → archive (plaintext essentials + AES-GCM `encrypted_metadata`, with the uploader id INSIDE the encrypted blob) → enqueue `moderation_queue` `trigger = 'csam_detected'`.
- [ ] 5.2 Idempotency: re-trigger for an already-actioned image creates no second archive row (UNIQUE) and does not destructively re-ban; converges + returns success.
- [ ] 5.3 Ledger-miss resilience: when `image_id` has no ledger row, still archive the match metadata (Kominfo record) and return success without throwing; skip only the uploader-dependent steps.
- [ ] 5.4 Audit attribution: write immutable `admin_actions_log` rows per mutation — `cf_worker` source → `system` sentinel admin id (`system-actor`); `admin_manual` source → the acting admin's id.

## 6. Routes + auth

- [ ] 6.1 `POST /internal/csam-webhook` — mount **outside** the `InternalEndpointAuth` OIDC plugin (vendor-webhook opt-out seam, RevenueCat/Apple-S2S precedent); parse/validate the payload (reject missing `image_hash` → `400`).
- [ ] 6.2 Admin-internal auth path — require a valid admin session + matching session-bound CSRF token; reject otherwise.
- [ ] 6.3 CF-Worker auth path — verify **both** a `Bearer` token and an `HMAC-SHA256` body signature keyed by `secretKey(env, "cf-worker-csam-secret")`; reject if either is missing/invalid.
- [ ] 6.4 CF-Worker rate limit — 100/hour per `clientIp` via `tryAcquireByKey` on key `{scope:csam_webhook}:{ip:<clientIp>}` (two-segment hash-tag; `clientIp` request-context value, never raw `X-Forwarded-For`).
- [ ] 6.5 `POST /internal/csam-archive-purge` — mount **inside** the OIDC plugin (ordinary scheduled worker); call `purgeExpiredReported()`.
- [ ] 6.6 Koin DI wiring (service/repository/encryptor/rate-limiter) + register both routes; one shared `Json`, bounded JDBC dispatcher, StatusPages envelope (per docs/11 §3).

## 7. Tests (one per spec scenario — no coverage compression)

- [ ] 7.1 Archive schema: plaintext `image_hash`/`ncmec_reference`/`cf_match_id`, no image bytes; `UNIQUE(image_hash)` collapses duplicates; partial `UNIQUE(cf_match_id)` allows multiple NULL, rejects duplicate non-NULL.
- [ ] 7.2 Preservation: archive row survives the uploader's `users`-row hard-delete (no cascade).
- [ ] 7.3 ON CONFLICT enrich: admin_manual row (cf_match_id NULL) later enriched by a cf_worker detection of the same `image_hash` → `cf_match_id` filled, `source` stays `admin_manual`, `created_at` unchanged, no second row.
- [ ] 7.4 Admin-trigger E2E takedown: post tombstoned, uploader banned + `token_version` bumped, other posts cascade-tombstoned, one archive row (`source='admin_manual'`, `expires_at = +90d`, `kominfo_reported_at` NULL), `moderation_queue` `csam_detected` row, audit rows present.
- [ ] 7.5 CF-Worker E2E takedown (the fully-exercisable path) with a simulated match payload → same end state, `source='cf_worker'`, audit under the `system` sentinel.
- [ ] 7.6 Idempotent re-trigger: second invocation → no second archive row, no destructive re-ban, success.
- [ ] 7.7 Ledger miss: unknown `image_id` + valid `image_hash` → archive written, success, no throw.
- [ ] 7.8 Malformed payload (missing `image_hash`) → `400`, no mutation.
- [ ] 7.9 Audit attribution: admin_manual → acting admin id; cf_worker → system sentinel id.
- [ ] 7.10 Encryption: round-trip when key set; takedown proceeds + plaintext essentials written + `encrypted_metadata = NULL` when key unset; decrypted metadata contains no image bytes.
- [ ] 7.11 Purge worker: expired+reported purged; expired+unreported preserved; within-window preserved.
- [ ] 7.12 Auth (internal-endpoint-auth delta): OIDC token alone does NOT authorize `/internal/csam-webhook`; CF-Worker path needs both Bearer+HMAC; CF-Worker path rate-limited >100/hr/IP; admin-internal path needs session+CSRF; `/internal/csam-archive-purge` rejects a request without a valid OIDC token (`401`).
- [ ] 7.13 `moderation_queue` accepts `trigger = 'csam_detected'` (enum already at V9) and the enqueue is idempotent on `(target_type, target_id, trigger)`.
- [ ] 7.14 Negative: the diff introduces no admin route reading/decrypting `csam_detection_archive` and no endpoint writing `kominfo_report_id`/`kominfo_reported_at` (deferred-scope guard).
- [ ] 7.15 Test pools `autoClose(hikari())` + size 2 (CI `max_connections` budget); timestamp assertions truncated to micros (macOS/Linux clock-resolution parity).

## 8. Verification + pre-archive

- [ ] 8.1 Local gates green: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test`.
- [ ] 8.2 `openspec validate csam-detection-webhook-and-archive --strict` green.
- [ ] 8.3 Pre-archive staging branch deploy (`gh workflow run deploy-staging.yml --ref csam-detection-webhook-and-archive`) + smoke: unauthenticated `POST /internal/csam-webhook` is rejected (not 200); unauthenticated `POST /internal/csam-archive-purge` → `401` (routes mount + auth-gate; endpoints are internal + dark).
- [ ] 8.4 Launch-readiness note (NOT a deploy gate now): document that `csam-archive-aes-key` must be provisioned AND the zone-level CF CSAM Scanning Tool enabled **before** `image_upload_enabled` flips TRUE (restates `premium-image-upload/spec.md:147`).
- [ ] 8.5 PR body refreshed at the apply boundary (retitle `feat(backend): csam-detection-webhook-and-archive — …`); capability deltas + test counts current before archive.
