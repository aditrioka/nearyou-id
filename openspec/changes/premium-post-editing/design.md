## Context

Posts are immutable after creation today (`CreatePostService` only INSERTs; there is no UPDATE path). The freemium contract promises Premium users a 30-minute edit window ([`docs/01-Business.md`](../../../docs/01-Business.md) line 19) and [`docs/05-Implementation.md`](../../../docs/05-Implementation.md) §367–407 already specifies the canonical `post_edits` schema + the race-safe edit transaction verbatim — this change implements exactly that. The backend half lands first (the project's established backend→mobile seam: `user-profile-read` → `mobile-profile`, `premium-search` → `mobile-search`); the mobile "Riwayat edit" UI (Phase 4 item 13) and the admin report-queue edit-history filter ([#191](https://github.com/aditrioka/nearyou-id/issues/191)) build against this contract afterward.

Current-state anchors this change mirrors:
- `backend/ktor/src/main/kotlin/id/nearyou/app/post/CreatePostService.kt` — the transactional-service pattern (single pooled connection, `autoCommit=false`, commit/rollback), the `PREMIUM_STATES = setOf("premium_active", "premium_billing_retry")` gate constant, the `ContentLengthGuard.enforce("post.content", …)` guard, the `TextModerator.moderate` (Verdict Reject/Flag/Allow) + `Layer3Moderator` fire-and-forget dispatch with OTel context propagation.
- `PostRoutes.kt` — the `authenticate(AUTH_PROVIDER_USER) { … call.principal<UserPrincipal>() … principal.subscriptionStatus }` route shape.
- `visible-posts-view` + `user-blocking` capabilities — the read-path visibility invariants the history endpoint must honour.

## Goals / Non-Goals

**Goals:**
- A Premium-gated `PATCH /api/v1/posts/{post_id}` that edits post content within 30 minutes of creation, author-only, race-safe, with an append-only before-edit audit trail in `post_edits`.
- A `GET /api/v1/posts/{post_id}/edits` history read that respects shadow-ban + block visibility and labels versions "Versi ke-N".
- Byte-for-byte fidelity to the canonical `post_edits` schema + transaction in `docs/05` §367–407.
- Close the clean→toxic edit-laundering hole by re-running moderation on edit (D1).

**Non-Goals:**
- **No mobile UI** — the "Riwayat edit" modal + "Diedit [time]" label (`docs/02` §133, Phase 4 item 13) are a deferred follow-up. This change ships zero `:mobile:app` code (asserted as a spec scope-boundary requirement).
- **No admin surface** — the report-queue "post has edit history" filter ([#191](https://github.com/aditrioka/nearyou-id/issues/191)) stays deferred.
- **No location editing** — edits are content-only; the `location_snapshot` records the (unchanged) location for a complete temporal row, but the `UPDATE` never changes `posts` location.
- **No edit of replies** — `post_replies` editing is out of scope (not in the freemium contract).
- **No chat context-card edit navigation** (Phase 4 item 14) — separate change.

## Decisions

### D1 — Re-run moderation on edit (extends the canonical spec)
The edited content re-runs the moderation pipeline, mirroring `CreatePostService`: synchronous `TextModerator.moderate` (Verdict.Reject → 400 `content_moderated_profanity`, Verdict.Flag → in-transaction `moderation_queue` upsert, Verdict.Allow → proceed) followed by the post-commit fire-and-forget `Layer3Moderator` dispatch with `coroutineContext` propagation.
**Why:** without it, a user posts clean content, passes create-time moderation, then edits to toxic content — laundering past every moderation gate. `docs/05` §367–407 is **silent** on edit moderation (it predates the moderation layers). This is additive correctness, not a doc contradiction.
**Reconciliation (per `/next-change` B.3, bucket (b) — canonical doc incomplete):** a `follow-up` issue is filed to amend `docs/05` §367–407 + `docs/06` § Content Moderation to make edit re-moderation canonical. The proposal states the extension explicitly.
**Alternative considered:** moderate-on-read or admin-only catch — rejected (lets toxic content reach the timeline between edit and the next moderation pass).

### D2 — Premium gate accepts `premium_active` AND `premium_billing_retry`
Reuse the existing `PREMIUM_STATES` constant (don't redefine). `premium_billing_retry` is the active 7-day billing grace where Premium access REMAINS active (Phase 4 item 4) — matching the like/post daily-cap-skip precedent. Free / suspended-to-free → 403 `premium_required`.
**Alternative:** `premium_active` only — rejected; would revoke a paid feature mid-grace, inconsistent with every other Premium gate in the codebase.

### D3 — Route shape `PATCH /api/v1/posts/{post_id}` + `GET …/edits`
`PATCH` (partial update of one field) over `PUT`; nested `…/edits` collection for history. Consistent with the `PATCH /api/v1/user/username` premium-mutation precedent. Both mount in the existing `postRoutes` group under `authenticate(AUTH_PROVIDER_USER)`.

### D4 — Race-safe transaction, single pooled connection (`docs/11` §3.2)
Implement the `docs/05` §385 transaction verbatim in a new `PostEditService.edit(...)` mirroring `CreatePostService`: one `dataSource.connection.use { conn.autoCommit=false … }` on the pool-bounded `dbDispatcher`. `SELECT id, content, actual_location, author_id FROM posts WHERE id=:id AND author_id=:uid AND created_at > NOW() - INTERVAL '30 minutes' AND deleted_at IS NULL FOR UPDATE` — a 0-row result distinguishes the failure mode for the route (not-author / outside-window / deleted / not-found → 403/409/404 per the spec scenarios). Then `INSERT … post_edits` (before-edit snapshot via `clock_timestamp()`) → `UPDATE posts SET content=:new, updated_at=NOW()` → commit. App-level retry once on `unique_violation` against `post_edits_temporal_idx` (sub-µs collision) → 409 `Coba lagi sebentar.`
**Why `FOR UPDATE`:** serializes concurrent edits of the same post so two in-flight edits can't both snapshot-then-overwrite (lost update). The UNIQUE temporal index is the second line of defence.

### D5 — History read honours visibility (`visible_posts` + block)
`GET …/edits` first resolves the post through the shadow-ban-safe `visible_posts` view + the bidirectional `user_blocks` NOT-IN join (the standard read-path invariants) — a viewer who can't see the post gets 404 (not 403; don't confirm existence). Versions via `ROW_NUMBER() OVER (PARTITION BY post_id ORDER BY edited_at)` rendered "Versi ke-N". History is visible to any permitted viewer (transparency), not author-only.

### D6 — Wire format follows the existing convention
Response DTOs follow the live `TimelineRoutes`/`PostRoutes` convention (mixed casing; `explicitNulls = false` ContentNegotiation, manual `buildJsonObject` where a null must appear on the wire). Exact field casing is an apply-phase detail; the spec constrains semantics, not JSON keys.

## Standards conformance

Per [`docs/11-Engineering-Standards.md`](../../../docs/11-Engineering-Standards.md): this change builds on the **existing** Pattern-Registry patterns — **backend layering** (§3.1: route → service → repository; new `PostEditService` + `PostEditHistoryQuery` slot beside `CreatePostService`, new methods on `PostRepository`), **JDBC/connection discipline** (§3.2: one pooled connection per transaction on the bounded `dbDispatcher`, no connection held across suspension points), **transactional-service** (mirrors `CreatePostService`), and **moderation dispatch** (reuses `TextModerator` + `Layer3Moderator`). **No new pattern is introduced** — so no `docs/11` § Pattern Registry amendment is required. The two read-path invariants (`visible_posts`, bidirectional block join) are consumed unchanged. The only doc amendment is the D1 re-moderation reconciliation (`docs/05`/`docs/06`), tracked as a follow-up.

## Risks / Trade-offs

- **[V22 migration-number race]** → `revenuecat-subscription-webhook` (#291) holds V21; this takes V22. If a third parallel change grabs V22 before merge, rebase-renumber to the next free V (Flyway files are checksum-immutable once applied, but pre-merge renaming is safe). Flagged to the user at pick time.
- **[`clock_timestamp()` vs `now()` collision]** → two edits inside the same statement-microsecond would violate `post_edits_temporal_idx`; mitigated by `FOR UPDATE` serialization + the single app-level retry → 409 (canonical per `docs/05` §406).
- **[Re-moderation latency on edit]** → the synchronous `TextModerator.moderate` adds cold-cache Redis/Remote-Config I/O to the edit path; runs on the bounded `dbDispatcher` like create, after the cheap length/window/author gates so a rejected edit burns no moderation budget.
- **[Edit-laundering window before Layer 3 returns]** → identical to the create path (Layer 3 is fire-and-forget post-commit); accepted, matches existing posture.
- **[Reply counter / like state on edit]** → editing content does not touch likes/replies; no counter recompute needed. No risk.

## Migration Plan

- Forward: add `V22__post_edits.sql` (table + 2 indexes). `RUN_FLYWAY_ON_STARTUP` applies it on boot; no backfill (empty table). Deploy backend; the two new routes are additive (no client depends on them yet).
- Rollback: the table is append-only and unreferenced by existing reads; a bad deploy rolls back to the prior revision and the empty `post_edits` table is inert. Do **not** edit `V22` after it applies anywhere (checksum-immutable) — fix-forward with `V23`.

## Open Questions

- **None blocking.** D1 (re-moderation) is decided as in-scope with a docs follow-up; if the user prefers to ship edit WITHOUT re-moderation and treat the laundering hole as a separate change, that is the one scope lever to pull before `/opsx:apply` — surfaced in the Phase D review digest.
