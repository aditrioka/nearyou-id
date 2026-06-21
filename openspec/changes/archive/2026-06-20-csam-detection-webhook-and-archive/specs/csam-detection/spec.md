## ADDED Requirements

### Requirement: `csam_detection_archive` table preserves match metadata for legal filing

A new `csam_detection_archive` table SHALL record one row per detected CSAM match, holding the minimum metadata required to file the Kominfo report and survive the offending account's deletion. The table SHALL store, in **plaintext**, only the columns needed for Kominfo filing — the matched `image_hash`, the matched NCMEC/partner reference, and the Cloudflare `cf_match_id` — plus the trigger `source`, lifecycle timestamps, and an AES-256-GCM `encrypted_metadata BYTEA` column for all other (sensitive) detail. **No image bytes are ever retained.**

The schema SHALL include:
- `image_hash TEXT NOT NULL` with a `UNIQUE` constraint (the dedup key — one archive row per matched image).
- `cf_match_id TEXT` (nullable — the admin-manual path may lack it) with a **partial** `UNIQUE` index `WHERE cf_match_id IS NOT NULL`.
- `ncmec_reference TEXT` (plaintext, the matched-list reference for filing).
- `source TEXT NOT NULL CHECK (source IN ('admin_manual', 'cf_worker'))`.
- `encrypted_metadata BYTEA` (nullable — AES-256-GCM ciphertext; see the encryption requirement).
- `kominfo_report_id TEXT` and `kominfo_reported_at TIMESTAMPTZ` (both nullable; `kominfo_reported_at IS NULL` means the report is still pending).
- `created_at TIMESTAMPTZ NOT NULL DEFAULT now()` and `expires_at TIMESTAMPTZ NOT NULL` (the 90-day preservation deadline).

The table SHALL carry **no `ON DELETE CASCADE` foreign key to `users`** — the offending uploader's identity is stored inside `encrypted_metadata`, never as a plaintext cascading FK — so the archive row survives the uploader's later account hard-delete (legal preservation). Any index supporting the purge worker MUST be partial-index-clean (no `NOW()` in a `WHERE` clause).

#### Scenario: Archive row carries plaintext Kominfo essentials and no image bytes
- **WHEN** a CSAM match is archived
- **THEN** the row exposes `image_hash`, `ncmec_reference`, and `cf_match_id` as plaintext columns AND stores no raw image bytes in any column

#### Scenario: UNIQUE(image_hash) prevents a duplicate archive row
- **WHEN** two archive attempts supply the same `image_hash`
- **THEN** the second attempt does not create a second row (the `UNIQUE(image_hash)` constraint collapses them to one row)

#### Scenario: Partial UNIQUE(cf_match_id) allows multiple NULL but rejects duplicate non-NULL
- **WHEN** two archive rows are written with `cf_match_id = NULL` AND a third write supplies a `cf_match_id` already present on another row
- **THEN** the two NULL rows coexist (partial index excludes NULLs) AND the duplicate non-NULL `cf_match_id` write is rejected by the partial `UNIQUE` index

#### Scenario: Archive row survives the offending uploader's account hard-delete
- **WHEN** an archive row exists for a match AND the uploader's `users` row is subsequently hard-deleted
- **THEN** the `csam_detection_archive` row remains intact (no cascade removed it) so the legal-preservation record outlives the account

### Requirement: `POST /internal/csam-webhook` executes the fixed-policy takedown

The backend SHALL expose `POST /internal/csam-webhook` which, on a verified invocation carrying the matched image identifier (Cloudflare `image_id`/delivery URL) plus the match metadata (`image_hash`, optional `cf_match_id`, `ncmec_reference`), SHALL execute a **fixed, non-configurable** takedown sequence in a single database transaction:

1. **Resolve** the offending uploader and the affected post from the matched image via the `image_uploads` ledger (`cf_image_id → uploader_user_id`).
2. **Tombstone the affected post** — set `posts.deleted_at` so it leaves every `visible_*` view immediately, and write an audit row.
3. **Permanently ban the uploader** — set `users.is_banned = TRUE`, leave `suspended_until` NULL (permanent), and increment `users.token_version` (invalidating live sessions).
4. **Cascade-tombstone the uploader's other posts** — set `deleted_at` on all of the uploader's remaining posts (abundance of caution).
5. **Archive** the match metadata into `csam_detection_archive` (plaintext essentials + AES-256-GCM `encrypted_metadata`), with `expires_at = created_at + 90 days` and `kominfo_reported_at = NULL` (pending).
6. **Enqueue admin awareness** — insert a `moderation_queue` row with `trigger = 'csam_detected'` (the enum value reserved at V9; no schema change) for the affected post (`target_type = 'post'`, `target_id` = the affected post id), idempotent via the `(target_type, target_id, trigger)` UNIQUE (`ON CONFLICT DO NOTHING`).

The endpoint SHALL be **idempotent**: a re-trigger for an already-actioned image MUST NOT create duplicate archive rows, re-ban an already-banned user destructively, or error — it converges on the same end state and returns success.

#### Scenario: Admin-trigger end-to-end takedown
- **WHEN** a verified `POST /internal/csam-webhook` (admin-manual path) supplies a matched `image_id` that resolves via `image_uploads` to uploader U and post P
- **THEN** P's `deleted_at` is set AND U is `is_banned = TRUE` with `suspended_until` NULL and `token_version` incremented AND all of U's other posts have `deleted_at` set AND one `csam_detection_archive` row is written with `source = 'admin_manual'`, `expires_at = created_at + 90 days`, `kominfo_reported_at = NULL` AND a `moderation_queue` row with `trigger = 'csam_detected'` exists AND audit rows are written

#### Scenario: Re-trigger is idempotent
- **WHEN** `POST /internal/csam-webhook` is invoked twice for the same matched image
- **THEN** the second invocation creates no second `csam_detection_archive` row (UNIQUE(image_hash)), enqueues no duplicate `moderation_queue` row (the `(target_type, target_id, 'csam_detected')` UNIQUE / `ON CONFLICT DO NOTHING` collapses it), leaves the already-banned uploader banned (no destructive re-ban), and returns success

#### Scenario: Matched image not resolvable in the ledger
- **WHEN** the matched `image_id` has no `image_uploads` row (already-cleaned or stale) AND the request still carries a valid `image_hash`
- **THEN** the handler still archives the match metadata (so the Kominfo record exists) AND returns success without throwing (the takedown steps that require an uploader are skipped, the archive is not)

#### Scenario: Malformed payload is rejected before any mutation
- **WHEN** a verified invocation supplies a body missing the required `image_hash`
- **THEN** the response status is `400` AND no post is tombstoned, no user is banned, and no archive row is written

### Requirement: CSAM auto-action attributes its audit rows to a resolvable actor

Every mutation performed by the takedown (post tombstone, ban, cascade) SHALL write an immutable `admin_actions_log` row. Because the Cloudflare-Worker invocation has no human admin, system-originated takedowns SHALL attribute their audit rows to the deterministic `system` sentinel admin defined by the `system-actor` capability; admin-manual invocations SHALL attribute their audit rows to the acting admin's id.

#### Scenario: CF-Worker path audits under the system sentinel
- **WHEN** the takedown runs via the `cf_worker` source (no human admin)
- **THEN** the `admin_actions_log` rows it writes carry `admin_id` equal to the `system` sentinel admin id

#### Scenario: Admin-manual path audits under the acting admin
- **WHEN** the takedown runs via the `admin_manual` source initiated by admin A
- **THEN** the `admin_actions_log` rows it writes carry `admin_id = A`

### Requirement: Archive metadata is AES-256-GCM encrypted via a fail-soft secret-managed key

The `encrypted_metadata` column SHALL be AES-256-GCM ciphertext produced by a JDK-`javax.crypto` helper keyed by `secretKey(env, "csam-archive-aes-key")` — no vendor SDK and no new module. Encryption SHALL round-trip: decrypting `encrypted_metadata` with the same key yields the original plaintext metadata. The helper SHALL be **fail-soft** when the key slot is unprovisioned (the slot is operator-provisioned at the Month-6 image launch; staging mirror is `staging-csam-archive-aes-key`): a missing key MUST NOT block the safety-critical takedown — the post is still tombstoned, the user still banned, and the archive row still written with the plaintext Kominfo essentials and `encrypted_metadata = NULL`.

#### Scenario: Metadata round-trips when the key is provisioned
- **WHEN** the `csam-archive-aes-key` slot is set AND metadata is archived
- **THEN** `encrypted_metadata` is non-NULL ciphertext AND decrypting it with the same key reproduces the original plaintext metadata byte-for-byte

#### Scenario: Takedown proceeds and archives plaintext essentials when the key is unset
- **WHEN** the `csam-archive-aes-key` slot is unprovisioned AND a takedown runs
- **THEN** the post is tombstoned, the uploader banned, and a `csam_detection_archive` row is written with plaintext `image_hash`/`ncmec_reference`/`cf_match_id` AND `encrypted_metadata = NULL` (encryption degraded, takedown not blocked)

#### Scenario: Encrypted metadata never contains image bytes
- **WHEN** any archive row is written
- **THEN** neither the plaintext columns nor the decrypted `encrypted_metadata` contains raw image bytes (only hashes, references, and identifiers)

### Requirement: Re-detection of an already-archived image enriches without resetting

When a CSAM match is archived for an `image_hash` that already has a row, the write SHALL be an `ON CONFLICT (image_hash) DO UPDATE` that **enriches** the existing row (e.g., fills a previously-NULL `cf_match_id`) **without** resetting the original `source` or `created_at`.

#### Scenario: CF-Worker re-detection enriches an admin-manual row
- **WHEN** an image is first archived via `admin_manual` with `cf_match_id = NULL` AND later the `cf_worker` path archives the same `image_hash` carrying a `cf_match_id`
- **THEN** the existing row's `cf_match_id` is populated AND its `source` remains `admin_manual` AND its `created_at` is unchanged AND no second row is created

### Requirement: `/internal/csam-archive-purge` worker enforces 90-day retention with pending-report hold

The backend SHALL expose an OIDC-gated `POST /internal/csam-archive-purge` worker (a daily Cloud Scheduler job) that deletes archive rows whose preservation window has elapsed **only once the Kominfo report is filed**: `DELETE WHERE expires_at < NOW() AND kominfo_reported_at IS NOT NULL`. A row past `expires_at` with `kominfo_reported_at IS NULL` SHALL be preserved (an unfulfilled legal obligation extends preservation).

#### Scenario: Expired-and-reported row is purged
- **WHEN** the purge worker runs AND a row has `expires_at` in the past AND `kominfo_reported_at` set
- **THEN** that row is deleted

#### Scenario: Expired-but-unreported row is preserved
- **WHEN** the purge worker runs AND a row has `expires_at` in the past AND `kominfo_reported_at IS NULL`
- **THEN** that row is NOT deleted (preservation extended until the report is filed)

#### Scenario: Within-window row is preserved
- **WHEN** the purge worker runs AND a row has `expires_at` in the future
- **THEN** that row is NOT deleted regardless of `kominfo_reported_at`

### Requirement: Admin CSAM review surface and the report-filing read path are deferred

This change SHALL NOT introduce the admin-facing CSAM surface: there SHALL be no admin route that lists/filters `csam_detection_archive`, no `encrypted_metadata` decrypt-read endpoint, no paste-URL admin trigger form, and no in-panel Kominfo-filing write that sets `kominfo_report_id`/`kominfo_reported_at`. Those are tracked for the follow-on admin change (`admin-csam-detection-log-viewer`). This change ships the takedown handler, the archive (write + encrypt), and the purge worker; the human review/decrypt/file workflow is the follow-up.

#### Scenario: No admin CSAM read/decrypt/file artifacts in this change
- **WHEN** the change diff is inspected
- **THEN** it contains no admin route reading or decrypting `csam_detection_archive` and no endpoint writing `kominfo_report_id`/`kominfo_reported_at` (the archive starts with `kominfo_reported_at = NULL`, set later by the deferred admin surface)
