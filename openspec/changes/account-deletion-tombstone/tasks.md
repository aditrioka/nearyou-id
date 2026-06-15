## 1. Schema (migrations)

- [ ] 1.1 Add `V23__deletion_requests.sql`: `CREATE TABLE deletion_requests` + the two partial indexes (`deletion_requests_scheduled_idx`, `deletion_requests_immediate_idx`) verbatim per `docs/05` § Deletion Requests Schema; `source` CHECK enumerates all four canonical values; both partial-index `WHERE` clauses `NOW()`-free.
- [ ] 1.2 Same migration: `CREATE TABLE deletion_log` (`id, user_id, executed_at, source`; no FK on `user_id`); make it append-only at the app role (no UPDATE/DELETE grant), mirroring the audit-log posture.
- [ ] 1.3 Add `V24__visible_posts_surface_tombstoned_authors.sql`: `CREATE OR REPLACE VIEW visible_posts` dropping ONLY the author-side `u.deleted_at IS NULL` (keep `p.deleted_at IS NULL`, `is_auto_hidden = FALSE`, `is_shadow_banned = FALSE`); `visible_users` untouched. Add the migration-header comment noting the surfaces that consume the view + the bidirectional-block caller requirement (per the view-consumer convention).
- [ ] 1.4 Renumber note: if a concurrent change squash-merges V23 first, rebase + bump these two files (mechanical).

## 2. Account-deletion API (`account` package)

- [ ] 2.1 Create the `account` backend package: `AccountRoutes` (thin) → `AccountService` (tx boundary) → `AccountDeletionRepository` (JDBC), wired into Koin; JDBC on the shared pool-bounded dispatcher (docs/11 §3.2).
- [ ] 2.2 `POST /api/v1/account/deletion-request`: insert `source='user'`, `scheduled_hard_delete_at = NOW() + INTERVAL '30 days'`; idempotent when a row with `cancelled_at IS NULL AND executed_at IS NULL` already exists; `401` when unauthenticated.
- [ ] 2.3 `DELETE /api/v1/account/deletion-request`: set `cancelled_at = NOW()` where `executed_at IS NULL AND cancelled_at IS NULL`; reject when `executed_at IS NOT NULL`; enforce the `apple_s2s_account_delete` non-cancellable guard.
- [ ] 2.4 `GET /api/v1/account/deletion-request`: report pending state + `scheduled_hard_delete_at`; no cross-user leak.
- [ ] 2.5 Confirm the auth boundary: requesting deletion does NOT bump `token_version` and does NOT 403 authenticated routes during grace (Q3 fully-functional decision).

## 3. Hard-delete worker (`/internal/account-hard-delete-worker`)

- [ ] 3.1 Mount the worker endpoint under the internal-endpoint-auth (OIDC) subtree (precedent: suspension-unban-worker); reject non-internal callers; attribute actions to the system actor.
- [ ] 3.2 Scan due rows: `scheduled_hard_delete_at <= NOW() AND executed_at IS NULL AND cancelled_at IS NULL`; process each in its OWN transaction.
- [ ] 3.3 Tombstone the user row: set `deleted_at`; NULL `display_name, bio, google_id_hash, apple_id_hash, device_fingerprint_hash, date_of_birth, email`; `username = 'deleted_user_' || left(id::text, 8)` with the `// @allow-username-write: deletion` annotation.
- [ ] 3.4 Cascade-DELETE (explicit statements, since the user row is not row-deleted): `refresh_tokens` (all families), `follows` (both directions), `user_blocks` (both directions), `user_fcm_tokens`, addressed `notifications`. (`docs/06`'s "non-post location history" item is a no-op — no such table; location-on-open is request-only.)
- [ ] 3.5 Leave authored content RETAINED (no delete): `posts`, `post_replies`, `post_likes`, `post_edits`, `chat_messages`, submitted `reports`.
- [ ] 3.6 In the same transaction: insert a `deletion_log` row (`user_id`, `source`) and set `deletion_requests.executed_at = NOW()`; verify all-or-nothing rollback on a mid-row failure.
- [ ] 3.7 Idempotency: re-running does not re-process executed rows; one failing row does not block the batch.

## 4. Tombstoned-author render (D1) across read surfaces

- [ ] 4.1 Relax the author-side `deleted_at IS NULL` predicate in the Nearby + Global raw-`posts` timeline queries (keep shadow-ban, bidirectional-block, and the self-visibility UNION arm byte-identical).
- [ ] 4.2 Relax the author-side `deleted_at` predicate in post-detail (`single-post-read`) and the reply-list query (same predicate-preservation discipline).
- [ ] 4.3 Ensure the author-identity projection tolerates a tombstoned author (nulled `display_name`, `deleted_user_` handle) so the wire DTO carries the anonymized identity (LEFT/raw-users author join as needed; allowlist annotations where a raw read is introduced).
- [ ] 4.4 Confirm the client renders the nulled identity as "Akun Dihapus" (string via `:shared:resources`); verify push bodies still never include distance/identity beyond the existing contract.
- [ ] 4.5 Verify the deliberately-NOT-relaxed surfaces: `user-profile-read` still `404`s a tombstoned target, `premium-search` still excludes them, active-user metrics (operational dashboard) still exclude them.

## 5. Mobile Settings (Hapus Akun + restore banner)

- [ ] 5.1 Account-deletion data seam: `AccountDeletionApiClient` → `AccountDeletionRepository` → sealed `AccountDeletionOutcome` (success / terminal-401 / retryable), reusing the `Auth { bearer }` client; Koin-wired at `SettingsRoute` scope; no token/sub/body logging.
- [ ] 5.2 Destructive "Hapus Akun" affordance + confirmation dialog (30-day-grace + restore copy via `:shared:resources`); confirm → `POST`; `401` → sign-in; retryable → in-screen error.
- [ ] 5.3 Non-blocking scheduled-deletion banner driven by `GET` status (restore-by date) + "Batalkan" → `DELETE`; failed cancel keeps the banner (no optimistic clear).
- [ ] 5.4 Add the new Bahasa Indonesia strings to `:shared:resources` (`strings.xml`) — confirm the no-hardcoded-string grep passes.

## 6. Tests

- [ ] 6.1 Backend schema tests (`@Tags("database")`): table/index existence, `source` CHECK accept-4/reject-unknown, `NOW()`-free partial indexes, `deletion_log` append-only + no-FK.
- [ ] 6.2 API tests: request (first + idempotent + 401), cancel (within grace, post-execution reject, apple non-cancellable guard), status (pending / none), grace auth-boundary (write succeeds, no `token_version` bump).
- [ ] 6.3 Worker tests: due/cancelled/not-due selection; tombstone exact-PII-set + username regex; cascade tables emptied (both-direction blocks/follows); retain tables intact + like-count parity; deletion_log + executed_at atomic; partial-failure leaves no tombstone; idempotent re-run; internal-auth rejection + system-actor attribution.
- [ ] 6.4 View + render tests: V24 `pg_views` definition (shadow-ban + `p.deleted_at` present, no author `u.deleted_at`); tombstoned author's post surfaces in Global timeline anonymized; shadow-banned-then-deleted stays hidden; soft-deleted post still excluded; tombstoned author's profile still `404`.
- [ ] 6.5 Mobile tests (the `mobile-settings` test trio): `*ScreenTest` (Release-excluded) for the Hapus Akun + banner flow; `commonTest` for the deletion-seam DTO/outcome projections + Koin resolution; `iosTest` flow covering open settings → confirm-deletion path; the out-of-scope scenario updated (no data-export/suspension/chat-preview; Hapus Akun present).

## 7. Docs reconciliation & follow-ups

- [ ] 7.1 File a `follow-up` issue (D4): `docs/05:508` ("on user hard-delete, submitted reports cascade") is stale under the tombstone pattern (no row-delete → no FK cascade → reports retained per `docs/06:343`); flag for a clarifying doc-amend. Do NOT edit docs in this change.
- [ ] 7.2 File a `follow-up` issue: R2 deletion-log → R2 export (7-yr retention) once `:infra:r2` exists.
- [ ] 7.3 File a `follow-up` issue: optional "Akun Dihapus" placeholder profile (instead of `404`) for a tombstoned user (Q1 polish; docs/06:327 allows either).
- [ ] 7.4 File a `follow-up` issue (Q2): if the both-direction block cascade's ghost-post edge case proves jarring, narrow to blocker-only.
- [ ] 7.5 Note in the PR body the downstream changes this unblocks (admin Hard Delete Queue; Apple S2S delete sources + immediate-execute path).

## 8. Verification & deploy

- [ ] 8.1 Local gate green: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` + (mobile) `:mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest`.
- [ ] 8.2 UI-affecting (mobile) bring-up via `verify-loop` with screenshot evidence in the PR body BEFORE archive (Hapus Akun dialog + restore banner), per docs/11 §5 DoD.
- [ ] 8.3 Pre-archive staging branch deploy + smoke (request → status → cancel → re-request → worker dry-run on a synthetic user); tick this before archive.
- [ ] 8.4 (Deploy, stays unchecked until prod infra) Wire the Cloud Scheduler trigger for `/internal/account-hard-delete-worker` (daily), mirroring the existing internal-worker schedule pattern.
