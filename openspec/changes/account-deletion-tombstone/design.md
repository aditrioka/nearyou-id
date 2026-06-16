## Context

UU PDP right-to-erasure has no implementation today. `users.deleted_at` exists (shipped V2) and the shipped `visible_posts` / `visible_users` views already carry a forward-looking `u.deleted_at IS NULL` exclusion (currently a no-op — nothing sets `deleted_at` yet). The canonical artifacts are: `docs/05` § Deletion Requests Schema (the `deletion_requests` DDL), `docs/06` § Account Deletion (the tombstone/cascade/anonymize matrix + 30-day grace) + § Retention Policy, `docs/03` § Account Deletion / § Account Recovery. Internal-worker precedent: `suspension-unban-worker` (Cloud Scheduler → `/internal/*` OIDC-gated → system-actor audit row). This change wires the `source = 'user'` path end-to-end and the source-agnostic worker; the `apple_s2s_*` / `admin` sources and the admin Hard Delete Queue UI are downstream.

## Goals / Non-Goals

**Goals:**
- A user can request deletion, see the restore deadline, and cancel within a 30-day grace.
- After grace, a Cloud-Scheduler worker irreversibly tombstones + cascades + anonymizes the account in one transaction per due row, idempotently, audited.
- Lay the `deletion_requests` foundation (canonical schema, all four `source` values) the admin queue + Apple S2S path build on without a further migration.
- Honor `docs/06`: a hard-deleted author's *retained* content (posts/replies/likes/chat/reports/edits) renders "Akun Dihapus" rather than vanishing.

**Non-Goals (downstream / deferred):**
- Apple S2S delete sources + the synchronous immediate-execute path (Phase 4 item 7).
- Admin-initiated deletion (`source = 'admin'`) + the admin Hard Delete Queue UI (`docs/07`).
- R2 export of the deletion log (`:infra:r2` is DESIGN/unbuilt) — DB table now (D2).
- Data Export ("Unduh Data Saya"; blocked on `:infra:resend` modularisation).
- Anomaly/abuse monitoring of deletion velocity.

## Decisions

### D0 — Tombstone is an `UPDATE`, so "cascade" is explicit worker `DELETE`s (foundational)
The tombstone pattern **never row-deletes the `users` row** — it `UPDATE`s `deleted_at` + nulls PII. Therefore the FK `ON DELETE CASCADE` relationships (including `deletion_requests.user_id`, `reports.reporter_id`) **never fire** for a tombstone. The worker performs the "cascade delete" set as **explicit `DELETE FROM <table> WHERE user_id = :id`** statements, and the "anonymize-retain" set simply *stays* (the row persists, FKs to the tombstoned `users` row remain valid). This is the load-bearing mechanism behind D4 and the per-table matrix below. Alternative (real row-delete + rely on FK cascade) is rejected: it would wipe the retain-set (chat/posts) that `docs/06` mandates keeping, and there is no row to anchor the "Akun Dihapus" identity to.

Per-table matrix (worker, one transaction per due row):
| Table | Action | Mechanism |
|---|---|---|
| `users` (self) | tombstone | `UPDATE`: set `deleted_at`; NULL `display_name, bio, google_id_hash, apple_id_hash, device_fingerprint_hash, date_of_birth, email`; reset `apple_relay_email = FALSE`; `username = 'deleted_user_' || left(id::text, 8)` |
| sessions, refresh-token families | cascade-DELETE | explicit `DELETE` (also terminates any live session) |
| `follows` (both directions), `user_blocks` (both directions), `user_fcm_tokens`, `notifications` (addressed to user), non-post location history | cascade-DELETE | explicit `DELETE` |
| `posts` (+location), `post_replies`, `post_likes`, `post_edits`, `chat_messages` (sender), `reports` (reporter) | anonymize-RETAIN | no statement — rows persist; identity nulled via the tombstoned `users` join (renders "Akun Dihapus") |

### D1 — Tombstoned-author content stays visible in feeds, anonymized (the crux; operator-confirmed Q1 = option a)
`docs/06` says posts/replies/likes **remain in feeds**, author → "Akun Dihapus". The shipped read paths exclude tombstoned authors, which would make them *vanish*. **Decision (operator-confirmed): honor `docs/06`.** The tombstone sets `users.deleted_at` (per docs/06:324) + nulls PII + renames `username → deleted_user_<prefix>`; the nulled `display_name` + `deleted_user_` handle **is** the anonymized identity (client maps to "Akun Dihapus"). To surface that content, the **author-side `deleted_at IS NULL` exclusion is relaxed on the post-LISTING surfaces only**, keeping the post-soft-delete (`p.deleted_at IS NULL`), shadow-ban (`is_shadow_banned = FALSE`), and bidirectional-block predicates **byte-identical**:

- **`visible_posts` view** (`CREATE OR REPLACE`): drop `u.deleted_at IS NULL` (the author-side one only). This covers the Following timeline + the public counters that read the view.
- **Nearby + Global timelines** query **raw `posts`** (they carry the `shadow-ban-feed-self-visibility` self-arms, not the view) — relax their explicit author-`deleted_at` predicate the same way (shadow-ban / block / self-arm untouched).
- **Post detail** (`single-post-read`) + **reply lists** + **chat context card**: same relaxation so an embedded/opened tombstoned-author post still renders anonymized.

**Deliberately NOT relaxed (tombstoned users stay excluded here):** `visible_users` (V7) is **unchanged**, so `user-profile-read` returns **`404`** for a tombstoned target (docs/06:327 explicitly allows "404 **or** placeholder" — 404 is the lower-risk, leak-safety-preserving choice; an "Akun Dihapus" placeholder profile is a clean follow-up), `premium-search` keeps excluding tombstoned users (gone, not discoverable), and any `deleted_at IS NULL` "active user" metric (DAU/MAU, operational dashboard) keeps counting them as gone.

- **Alternative (b), rejected per Q1:** soft-delete the user's posts so they vanish, keep only chat tombstoned. Lower blast radius but contradicts `docs/06`. Operator chose (a).
- **Safety scenarios paired with (a):** assert (i) a shadow-banned-then-deleted author **stays hidden** (shadow-ban predicate dominates), (ii) a surviving viewer-side block still suppresses (Risk R2), (iii) the self-visibility UNION arm (V20) is unaffected, (iv) reply-count / like-count parity unchanged, (v) profile of a tombstoned user is `404`.

### D2 — Deletion log: append-only DB table now, R2 export deferred
New `deletion_log` table (`id, user_id, executed_at DEFAULT NOW(), source`), append-only, written by the worker inside the same transaction. Rationale: the Pre-Launch backup-reconciliation test ("no tombstoned user resurrected after restore") needs a queryable log to exist; R2 (7-yr) is the eventual home but `:infra:r2` is unbuilt. The DB table is forward-compatible with a later R2 export job. Rejected: deferring the log entirely (breaks the reconciliation test's premise).

### D3 — New `account` backend package
No `account` package exists (`auth, user, …`). Create `account/` with `AccountRoutes` (thin) → `AccountService` (tx boundary) → `AccountDeletionRepository` (JDBC), per docs/11 §3.1. The worker lives as `AccountHardDeleteWorker` mounted under the existing `/internal/*` OIDC subtree. Note on the precedent: the worker *mechanism* (internal-OIDC + scan + row-lock + system-actor attribution) follows `SuspensionUnbanWorker`, but that worker physically lives under `admin/`, not a feature package — this change deliberately places the account-deletion worker in the feature package `account/` (SRP: it's a user-facing capability with its own routes, not an admin surface). Both placements are valid; the rationale is SRP, not "admin-worker convention." Rejected: folding into `user` (deletion is its own feature surface with a worker).

### D6 — Worker endpoint name diverges from `docs/05`'s `/internal/cleanup`
`docs/05:630` + `docs/08` describe "the daily hard-delete worker" and host a generic `/internal/cleanup` (the notifications 90-day purge). This change coins a **dedicated** `/internal/account-hard-delete-worker` rather than overloading `/internal/cleanup` — the deletion worker has a distinct trigger cadence, auth-attribution, and audit surface, and the generic cleanup endpoint is itself still DESIGN-status. This is an intentional docs divergence (flagged here the way D4 flags the reports line); the doc-amend is a follow-up (task 7.x), not done in this change.

### D4 — Reports are RETAINED (resolves the `docs/05:508` vs `docs/06:343` contradiction)
Given D0 (no row-delete), `reports.reporter_id ON DELETE CASCADE` never fires → reports **persist** with the reporter pointing at the tombstoned user. This matches `docs/06:343` (retain for audit) and Data Export ("user has their copy pre-deletion"). `docs/05:508` ("on user hard-delete, submitted reports cascade") conflates tombstone with a real row-delete and is **stale** → flag for a clarifying doc-amend (B.3 bucket (b): canonical-but-stale → `follow-up` issue; do not rewrite docs in this change). The reviewed-by side is unrelated (admin FK, `ON DELETE SET NULL`).

### D5 — Source CHECK ships all four values; only `'user'` is wired
The migration writes the canonical `source` CHECK (`user`, `apple_s2s_consent_revoked`, `apple_s2s_account_delete`, `admin`) verbatim so downstream changes need no migration. This change only **produces** `source = 'user'` rows and the worker treats `scheduled_hard_delete_at` source-agnostically; it enforces the "`apple_s2s_account_delete` is non-cancellable" guard at the cancel endpoint even though that source isn't produced here (cheap forward-guard, prevents a downstream foot-gun).

### D7 — Reconcile the shipped specs' delete-then-row-delete assumption
The shipped schema + specs anticipated a **delete-based** hard-delete worker: `posts.author_id` / `post_replies.author_id` are `ON DELETE RESTRICT` with prose saying the worker "deletes content before the author" (i.e. row-deletes the user), and `post_likes.user_id` / `reports.reporter_id` are `ON DELETE CASCADE`; `single-post-read` even normatively `404`s a soft-deleted author's post. This change implements `docs/06`'s **tombstone** model instead (`UPDATE`, no row-delete), under which those FKs never fire and content is RETAINED anonymized. The FK *behavior* (RESTRICT/CASCADE) is unchanged and correct — only the model assumption is reconciled. Affected specs get deltas in this change: `single-post-read` (MODIFIED — the `404`→`200`-anonymized behavioral flip), `post-creation` (MODIFIED — FK prose), `post-replies` + `reports` (ADDED reconciliation requirements; their heavy schema requirements aren't safely reproducible as MODIFIED). `account-hard-delete-worker` carries the umbrella "supersedes the delete-model" requirement. `docs/05:508` gets a doc-amend follow-up (D4).

### D8 — Mobile: "Hapus Akun" is absent from mockup frame 16 (docs governs)
The settings mockup (frame 16) has no account-deletion row — the prior `mobile-settings` change explicitly recorded account deletion as out-of-scope-and-absent-from-the-mockup. `docs/03-UX-Design.md` § Account Deletion ("Hapus Akun button in Settings") governs behavior over the mockup (docs/11 §3.6 precedence: specs/docs win on behavior, mockup on look). So this change adds the row as a destructive-styled "Usulan" element not in the board; its styling should follow the design-system destructive treatment. Recorded here because the `mobile-settings` spec cites this divergence as "recorded in design.md."

### Standards conformance (docs/11)
- **Backend layering §3.1:** Routes → Service → Repository; DTOs with routes; no SQL in routes. Uses the **existing** internal-worker pattern (suspension-unban-worker) and backend-layering pattern — **no new Pattern-Registry pattern → no docs/11 amendment required.**
- **JDBC §3.2:** worker + endpoints run on the shared pool-bounded dispatcher; the worker's per-row tombstone+cascade+log is **one transaction** (open/commit/rollback helper). Test pools `autoClose(hikari())` + size 2.
- **Testing §3.5:** kotest JUnit5, `@Tags("database")` service-container specs; deterministic seed inputs.
- **Mobile §2.2/§2.6:** the Settings additions extend the existing `mobile-settings` ViewModel/UiState/Repository — no new mobile pattern. Strings via Compose Multiplatform Resources.
- **Invariants:** `// @allow-username-write: …` annotation on the `deleted_user_` rename (username-write lint); shadow-ban `visible_*` safety preserved in the D1 view change; bidirectional block join preserved; internal-endpoint OIDC + system-actor audit; `secretKey()` reads; no `NOW()` in partial-index `WHERE` (the new indexes match the canonical `NOW()`-free shape).

## Risks / Trade-offs

- **R1 — D1 touches a critical-invariant view (`visible_posts`).** A regression could leak shadow-banned or blocked content. → Mitigation: the view change *only* removes the `deleted_at` exclusion; shadow-ban + block predicates are kept byte-identical and asserted by dedicated scenarios; the security-and-invariant review lens (Phase D) reviews the view diff specifically.
- **R2 — Block asymmetry after tombstone.** The worker cascade-deletes `user_blocks` both directions, so a viewer who had blocked the (now-deleted) user loses that block row and could see the anonymized ghost posts surfaced by D1. → Mitigation: **Open Question Q2** — either keep the matrix as-docs (accept: the blocked user is gone, content is anonymized) or narrow the cascade to delete only rows where the deleted user is the *blocker* (preserving viewer→deleted suppression). Flag for user; default to docs-faithful (both-direction delete) with a `follow-up` if the UX proves jarring.
- **R3 — Scope/blast radius.** This is a large change (schema + API + worker + view-replace + mobile). → Mitigation: implementation MAY stage as ordered commits on this one branch (per the one-PR "session of commits" convention) — (1) schema+API+grace, (2) worker+tombstone+log, (3) D1 view-replace + render scenarios, (4) mobile Settings — each independently green; reviewer sees the map in the PR body. Not a separate PR per stage.
- **R4 — Idempotency / partial failure.** A worker crash mid-batch must not double-tombstone or strand a row. → Mitigation: `executed_at` set inside the same transaction as the mutations; the `WHERE executed_at IS NULL AND cancelled_at IS NULL` scan predicate makes re-runs skip done rows; per-row transaction isolates failures (one bad row doesn't block the batch).
- **R5 — Migration-number collision (V23).** `premium-image-upload-pipeline` (#325) + `referral-ticket-creation` (#327) also target V23. → Mitigation: whoever squash-merges first takes V23; this branch rebases + renumbers (a mechanical bump of two new files); flagged in the PR body.

## Migration Plan

1. `V23__deletion_requests.sql` — `CREATE TABLE deletion_requests` + the two partial indexes (verbatim `docs/05:603-624`) + `CREATE TABLE deletion_log`.
2. `V24__visible_posts_surface_tombstoned_authors.sql` — `CREATE OR REPLACE VIEW visible_posts` dropping **only** the author-side `u.deleted_at IS NULL` (keeping `p.deleted_at IS NULL`, `is_auto_hidden = FALSE`, `is_shadow_banned = FALSE`). `visible_users` is **NOT** touched. `CREATE OR REPLACE`, no data backfill.
3. Relax the author-`deleted_at` predicate in the Nearby + Global raw-`posts` timeline queries, post-detail, and reply-list (code, not migration) — shadow-ban / block / self-arm predicates unchanged.
4. Wire `AccountRoutes` + the `/internal/account-hard-delete-worker` (Cloud Scheduler trigger added at deploy, not in-migration).
5. Mobile Settings additions.
- **Rollback:** `visible_posts` is `CREATE OR REPLACE` (re-add the author `deleted_at` exclusion to revert D1); the raw-query predicate relaxations revert with the code. `deletion_requests` / `deletion_log` are additive (drop if reverting pre-launch; no prod data yet). The worker is idempotent and disabled by simply not scheduling it.
- **Renumber note:** V23/V24 MAY shift at rebase (R5).

## Resolved Questions (operator decision, 2026-06-16)

- **Q1 (D1) → option (a), docs-faithful.** Tombstoned authors' posts/replies/likes **remain in feeds, anonymized as "Akun Dihapus"**. Mechanism (refined per the shipped read-path reality — Nearby/Global use raw `posts`, Following + counters use `visible_posts`): relax the **author-side** `deleted_at IS NULL` exclusion on the **post-listing surfaces only** — `visible_posts` (`CREATE OR REPLACE`), the Nearby/Global raw-posts predicates, post-detail, reply-list, chat-context — keeping post-soft-delete + shadow-ban + block predicates byte-identical. `visible_users` is **unchanged** → `user-profile-read` stays `404` (docs/06:327 allows 404), search/metrics keep excluding. The recently-hardened "excludes soft-deleted authors" requirement on `visible-posts-view` is MODIFIED accordingly, paired with the D1 safety scenarios.
- **Q2 (R2) → both directions, docs-faithful.** The worker deletes `user_blocks` where the deleted user is on **either** side. The resulting ghost-post edge case (a surviving viewer who blocked them may see anonymized ghost posts) is accepted; file a `follow-up` only if UX proves jarring.
- **Q3 → fully functional during grace.** A pending-deletion account works normally; only a non-blocking "deletion scheduled, restore by {date}" banner. The deletion request is NOT a suspension and does NOT bump `token_version` / terminate sessions (contrast: suspension is session-terminating).
