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
- **No history pagination** — for MVP the `GET …/edits` response returns the full history; it is naturally bounded by the 30-minute edit window plus the per-user edit rate limit (D8), so cursor pagination is deferred to a follow-up only if real cardinality warrants it.

## Decisions

### D1 — Re-run moderation on edit (extends the canonical spec)
The edited content re-runs the moderation pipeline, mirroring `CreatePostService`: synchronous `TextModerator.moderate` runs **before the connection/transaction is opened** — so a pooled connection / `FOR UPDATE` row lock is never held across the moderator's Redis/Remote-Config I/O (`docs/11` §3.2; this is exactly how `CreatePostService` moderates *outside* its INSERT transaction). Verdict.Reject → 400 `content_moderated_profanity` (short-circuits before any connection is opened); Verdict.Flag → the verdict carries into the transaction and writes the `moderation_queue` row in-tx; Verdict.Allow → proceed. After commit, the fire-and-forget `Layer3Moderator` dispatch runs with `coroutineContext` propagation.
**Why:** without it, a user posts clean content, passes create-time moderation, then edits to toxic content — laundering past every moderation gate. `docs/05` §367–407 is **silent** on edit moderation (it predates the moderation layers). This is additive correctness, not a doc contradiction.
**Reconciliation (per `/next-change` B.3, bucket (b) — canonical doc incomplete):** a `follow-up` issue is filed to amend `docs/05` §367–407 + `docs/06` § Content Moderation to make edit re-moderation canonical. The proposal states the extension explicitly.
**Alternative considered:** moderate-on-read or admin-only catch — rejected (lets toxic content reach the timeline between edit and the next moderation pass).

### D2 — Premium gate accepts `premium_active` AND `premium_billing_retry`
The gate uses the same premium-state value set as every other premium gate (`premium_active` + `premium_billing_retry`); `premium_billing_retry` is the active 7-day billing grace where Premium access REMAINS active (Phase 4 item 4) — matching the like/post daily-cap-skip precedent. Free / suspended-to-free → 403 `premium_required`, checked **before** any post lookup (a Free caller learns nothing about the target post).
**Note (review finding):** there is currently **no shared `PREMIUM_STATES` constant** — the set is duplicated as a private companion constant across 6 services (`CreatePostService`, `ChatService`, `SearchService`, `LikeService`, `ReplyService`, `TimelineReadRateLimiter`). This change therefore adds a 7th local copy rather than "reusing" a shared one. A DRY refactor (extract a shared `PremiumStates`) is deliberately **out of scope** here — it would touch all 6 call-sites and collide with the in-flight billing/chat/search branches (rebase pain). Tracked as a separate cleanup candidate; not blocking.
**Alternative:** `premium_active` only — rejected; would revoke a paid feature mid-grace, inconsistent with every other Premium gate in the codebase.

### D3 — Route shape `PATCH /api/v1/posts/{post_id}` + `GET …/edits`
`PATCH` (partial update of one field) over `PUT`; nested `…/edits` collection for history. Consistent with the `PATCH /api/v1/user/username` premium-mutation precedent. Both mount in the existing `postRoutes` group under `authenticate(AUTH_PROVIDER_USER)`.

### D4 — Race-safe transaction, single pooled connection (`docs/11` §3.2)
Implement the `docs/05` §385 transaction verbatim in a new `PostEditService.edit(...)` mirroring `CreatePostService`: one `dataSource.connection.use { conn.autoCommit=false … }` on the pool-bounded `dbDispatcher`. `SELECT id, content, actual_location, author_id FROM posts WHERE id=:id AND author_id=:uid AND created_at > NOW() - INTERVAL '30 minutes' AND deleted_at IS NULL FOR UPDATE` — a 0-row result distinguishes the failure mode for the route (→ 404/409 per D7: author's own out-of-window post → 409, everything else → uniform 404; note 403 is the pre-lookup premium gate only, never a 0-row outcome). The new content was already moderated before this connection opened (D1); inside the transaction, after the no-op check (D9): `INSERT … post_edits` (before-edit snapshot via `clock_timestamp()`) → `UPDATE posts SET content=:new, updated_at=NOW()` → (on a Flag verdict) the in-tx `moderation_queue` write → commit. App-level retry once on `unique_violation` against `post_edits_temporal_idx` (sub-µs collision) → 409 `Coba lagi sebentar.`
**Why `FOR UPDATE`:** serializes concurrent edits of the same post so two in-flight edits can't both snapshot-then-overwrite (lost update). The UNIQUE temporal index is the second line of defence.

### D5 — History read honours visibility (`visible_posts` + block)
`GET …/edits` first resolves the post through the shadow-ban-safe `visible_posts` view + the bidirectional `user_blocks` NOT-IN join (the standard read-path invariants) — a viewer who can't see the post gets 404 (not 403; don't confirm existence). Versions via `ROW_NUMBER() OVER (PARTITION BY post_id ORDER BY edited_at)` rendered "Versi ke-N". History is visible to any permitted viewer (transparency), not author-only.

### D6 — Wire format follows the existing convention
Response DTOs follow the live `TimelineRoutes`/`PostRoutes` convention (mixed casing; `explicitNulls = false` ContentNegotiation, manual `buildJsonObject` where a null must appear on the wire). Exact field casing is an apply-phase detail; the spec constrains semantics, not JSON keys.

### D7 — Non-leaky failure codes: disambiguate the 0-row lock result (review finding)
The `SELECT … FOR UPDATE` keyed on `id=:id AND author_id=:uid AND created_at > NOW()-30min AND deleted_at IS NULL` returns 0 rows for four distinct conditions (not-found / not-author / out-of-window / soft-deleted). To give the **author** a useful "window expired" message without **leaking post existence to a non-author**, on a 0-row result the service runs one author-scoped disambiguation read (`SELECT created_at, deleted_at FROM posts WHERE id=:id AND author_id=:uid` — author-scoped, so it can never reveal another user's post): a returned non-deleted row that is merely past the window → `409 edit_window_expired`; otherwise (no row → not-author/non-existent, or the author's own soft-deleted post) → uniform `404`. This makes non-author and non-existent indistinguishable.
**Alternative:** uniform `404` on any 0-row result — rejected; gives the author no actionable feedback for the (common) window-expired case.

### D8 — Per-user edit rate limit (review finding)
The edit endpoint is rate-limited per user via the existing rate-limit infrastructure (a distinct limiter key from the daily-post-cap `PostRateLimiter`). Rationale: each edit triggers synchronous Redis/Remote-Config moderation I/O **plus** a fire-and-forget external Perspective (Layer-3) call; unbounded re-edits within the 30-minute window are an amplification/cost vector on a paid endpoint. The specific cap is ops-tunable (Remote-Config / config value), not a hard-coded literal. Exceed → `429` + `Retry-After`, no change.
**Alternative:** no limiter (accept the risk) — considered and rejected by the operator at proposal review.

### D9 — No-op edits are rejected (review finding)
An edit whose normalized content equals the post's current content is rejected `400 no_changes` — checked inside the transaction against the content from the locked row, before the snapshot/update — so the append-only `post_edits` table and the "Versi ke-N" sequence record only real changes. (Moderation of the new content runs *before* the transaction per D1; a no-op of already-live content is never a Reject verdict, so the `no_changes` path is still reached for genuine re-saves.)
**Alternative:** record identical content as a version — rejected; produces junk versions.

### D10 — History read returns no raw location (review finding — invariant #3)
The `GET …/edits` response is content-only: content snapshot + "Versi ke-N" + `edited_at`. It MUST NOT project `post_edits.location_snapshot` or `posts.actual_location` (both raw, unfuzzed) — these stay admin/audit-only per the spatial-fuzzing invariant. The product surface ("Riwayat edit" modal, docs/02 §133) renders content versions only, so no location field exists on the wire. The history query reads through `visible_posts` (shadow-ban-safe) + a **real** bidirectional `user_blocks` exclusion predicate (the `=`-fragment `NOT EXISTS` form recognized by `BlockExclusionJoinRule`, which matches `FROM visible_posts`) — never an annotation bypass.

## Standards conformance

Per [`docs/11-Engineering-Standards.md`](../../../docs/11-Engineering-Standards.md): this change builds on the **existing** Pattern-Registry patterns — **backend layering** (§3.1: route → service → repository; new `PostEditService` + `PostEditHistoryQuery` slot beside `CreatePostService`, new methods on `PostRepository`), **JDBC/connection discipline** (§3.2: one pooled connection per transaction on the bounded `dbDispatcher`, no connection held across suspension points), **transactional-service** (mirrors `CreatePostService`), and **moderation dispatch** (reuses `TextModerator` + `Layer3Moderator`). **No new pattern is introduced** — so no `docs/11` § Pattern Registry amendment is required. The two read-path invariants (`visible_posts`, bidirectional block join) are consumed unchanged. The only doc amendment is the D1 re-moderation reconciliation (`docs/05`/`docs/06`), tracked as a follow-up.

**Detekt `ContentWriteRequiresModerationRule` (review finding):** the new `UPDATE posts SET content=…` is a new content-write sink the rule scans for a preceding `TextModerator.moderate(...)`. The D1 re-moderation requirement satisfies it naturally — the implementation MUST keep the `moderate()` call on the edit path (do **not** reach for an `@AllowContentWriteWithoutModeration`-style carve-out; there is no legitimate reason to bypass it here).

## Risks / Trade-offs

- **[V22 migration-number race]** → `revenuecat-subscription-webhook` (#291) holds V21; this takes V22. If a third parallel change grabs V22 before merge, rebase-renumber to the next free V (Flyway files are checksum-immutable once applied, but pre-merge renaming is safe). Flagged to the user at pick time.
- **[`clock_timestamp()` vs `now()` collision]** → two edits inside the same statement-microsecond would violate `post_edits_temporal_idx`; mitigated by `FOR UPDATE` serialization + the single app-level retry → 409 (canonical per `docs/05` §406).
- **[Re-moderation latency + cost amplification on edit]** → the synchronous `TextModerator.moderate` adds cold-cache Redis/Remote-Config I/O to the edit path, and each successful edit fires an external Perspective (Layer-3) call; runs on the bounded `dbDispatcher` like create, after the cheap length/window/author gates so a rejected edit burns no moderation budget. The per-user edit rate-limit (D8) bounds the call volume so rapid re-edits can't amplify the I/O / external-API cost.
- **[Edit-laundering window before Layer 3 returns]** → identical to the create path (Layer 3 is fire-and-forget post-commit); accepted, matches existing posture.
- **[Reply counter / like state on edit]** → editing content does not touch likes/replies; no counter recompute needed. No risk.

## Migration Plan

- Forward: add `V22__post_edits.sql` (table + 2 indexes). `RUN_FLYWAY_ON_STARTUP` applies it on boot; no backfill (empty table). Deploy backend; the two new routes are additive (no client depends on them yet).
- Rollback: the table is append-only and unreferenced by existing reads; a bad deploy rolls back to the prior revision and the empty `post_edits` table is inert. Do **not** edit `V22` after it applies anywhere (checksum-immutable) — fix-forward with `V23`.

## Open Questions

- **None.** Both scope levers were resolved at Phase D review (operator decision): D1 re-moderation is **kept in-scope** (with the docs/05+docs/06 amendment follow-up filed at apply), and the per-user edit rate-limit (D8) is **added**. No outstanding decisions before `/opsx:apply`.
