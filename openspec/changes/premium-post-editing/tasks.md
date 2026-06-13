## 1. Schema (Flyway V22)

- [ ] 1.1 Add `backend/ktor/src/main/resources/db/migration/V22__post_edits.sql` with the `post_edits` table verbatim from docs/05 §367 (`id` UUID PK `gen_random_uuid()`, `post_id` UUID NOT NULL REFERENCES `posts(id)` ON DELETE CASCADE, `edited_at` TIMESTAMPTZ NOT NULL DEFAULT `clock_timestamp()`, `content_snapshot` VARCHAR(280) NOT NULL, `location_snapshot` GEOGRAPHY NOT NULL, `edited_by` UUID NOT NULL REFERENCES `users(id)`), plus `CREATE UNIQUE INDEX post_edits_temporal_idx ON post_edits(post_id, edited_at)` and `CREATE INDEX post_edits_post_id_idx ON post_edits(post_id, edited_at DESC)`. No `posts` ALTER.
- [ ] 1.2 Confirm V22 is still the lowest free migration number at apply time (re-check `revenuecat-subscription-webhook`/#291 hasn't moved off V21 and no other branch grabbed V22); renumber if needed. Migration is partial-index-clean (no `NOW()` in any `WHERE`).
- [ ] 1.3 Verify the migration applies on a fresh DB (`scripts/setup_backend_db.sh` / disposable PostGIS container) and `flyway validate` passes on boot.

## 2. Repository layer (`PostRepository`)

- [ ] 2.1 Add a "select-for-edit" method: `SELECT id, content, actual_location, author_id FROM posts WHERE id=:id AND author_id=:uid AND created_at > NOW() - INTERVAL '30 minutes' AND deleted_at IS NULL FOR UPDATE` (takes the open `Connection`; returns the locked row or null). Distinguish not-found-vs-ineligible at the service layer.
- [ ] 2.2 Add an "insert before-edit snapshot" method: `INSERT INTO post_edits (post_id, content_snapshot, location_snapshot, edited_at, edited_by) SELECT id, content, actual_location, clock_timestamp(), author_id FROM posts WHERE id=:id` (takes the open `Connection`).
- [ ] 2.3 Add an "update content" method: `UPDATE posts SET content=:new, updated_at=NOW() WHERE id=:id` (takes the open `Connection`).
- [ ] 2.4 Add an edit-history read query (separate read-path component, e.g. `PostEditHistoryQuery`): resolve the post through the shadow-ban-safe `visible_posts` view + a REAL bidirectional `user_blocks` exclusion predicate for the viewer (the `=`-fragment `NOT EXISTS` form `BlockExclusionJoinRule` recognizes — the rule matches `FROM visible_posts`; never an `@AllowMissingBlockJoin`/annotation bypass), then return edits with `ROW_NUMBER() OVER (PARTITION BY post_id ORDER BY edited_at)` as the version index. The projection MUST return content snapshot + version label + `edited_at` ONLY — NEVER `location_snapshot`/`actual_location` (spatial-fuzzing invariant #3; design D10). `visible_posts` read needs no `@AllowRawPostsRead`.

## 3. Service layer (`PostEditService`)

- [ ] 3.1 Create `PostEditService.edit(...)` mirroring `CreatePostService`, gates in this order (all BEFORE the post lookup so a Free/over-limit caller learns nothing about the target): (a) premium gate — `subscriptionStatus` in the same premium-state value set the other gates use (`premium_active` + `premium_billing_retry`; add a local `PREMIUM_STATES` const matching the existing per-service copies — there is NO shared constant today, see design D2) → 403 `premium_required` for Free; (b) per-user edit rate limit (design D8; distinct limiter key from the daily-post-cap `PostRateLimiter`, ops-tunable cap) → 429 + `Retry-After`; (c) `ContentLengthGuard.enforce("post.content", rawContent)` (empty/over-length → 400). Then the single-connection transaction (`dataSource.connection.use { conn.autoCommit=false … }` on the bounded `dbDispatcher`).
- [ ] 3.2 Inside the transaction: select-for-edit `FOR UPDATE` (window+author+not-deleted). On 0 rows, run the author-scoped disambiguation read (`SELECT created_at, deleted_at FROM posts WHERE id=:id AND author_id=:uid`, design D7): existing non-deleted row past the window → 409 `edit_window_expired`; otherwise → uniform 404 (non-author / non-existent / author's own soft-deleted). On a locked row: if normalized new content == current content → 400 `no_changes` (design D9; no snapshot/update); else run `TextModerator.moderate(newContent)` (Reject → throw `ContentModeratedProfanityException` → 400; Flag → `moderationQueue.upsertUuIteKeywordMatchRow` in-tx); insert snapshot (2.2); update content (2.3); commit. The `UPDATE posts SET content=…` keeps the preceding `moderate()` call so `ContentWriteRequiresModerationRule` is satisfied without a carve-out (design § Standards conformance).
- [ ] 3.3 After a successful commit, fire-and-forget `Layer3Moderator` dispatch via `layer3DispatcherScope.dispatch(coroutineContext) { … }` (OTel context propagation, mirroring `CreatePostService`).
- [ ] 3.4 Implement the single app-level retry on `unique_violation` against `post_edits_temporal_idx`; on persistent collision throw the conflict exception → 409 "Coba lagi sebentar."
- [ ] 3.5 Add the typed exceptions for the new failure modes and map them in `StatusPages` to the correct codes + canonical Bahasa Indonesia messages: `premium_required` (403), edit-rate-limited (429 + `Retry-After`), `no_changes` (400), not-found/non-author (404, uniform — non-leaky per D7), `edit_window_expired` (409), edit-conflict/temporal (409 "Coba lagi sebentar."). Reuse `ContentModeratedProfanityException`/length exceptions as-is.

## 4. Routes + DTOs

- [ ] 4.1 Add `patch("/api/v1/posts/{post_id}")` under the existing `authenticate(AUTH_PROVIDER_USER)` group in `PostRoutes` (or a sibling routes file): parse `{post_id}` (400 on malformed), receive the edit DTO (`{ content }`), call `service.edit(authorId=principal.userId, postId, rawContent, subscriptionStatus=principal.subscriptionStatus)`, respond 200 with the updated post. Follow the existing wire-format convention (mixed casing; manual `buildJsonObject` if a null must appear).
- [ ] 4.2 Add `get("/api/v1/posts/{post_id}/edits")` returning the visibility-checked history (404 when the viewer can't see the post — non-leaky) with `version_label` "Versi ke-N" + content snapshot + `edited_at` per entry, and NO location field (design D10); empty history → 200 empty list. Malformed `{post_id}` → 400.
- [ ] 4.3 Wire `PostEditService` + the history query into Koin DI (alongside `CreatePostService`); pass the production `DbDispatchers.db` dispatcher and the Layer-3 scope/moderator.

## 5. Tests (DB-tagged `*RoutesTest` — autoClose pool, size 2 per the CI connection-budget rule)

- [ ] 5.1 Premium author edits within window → 200; one `post_edits` row holds the PRE-edit content; `posts.content`/`updated_at` updated; location unchanged.
- [ ] 5.2 `premium_billing_retry` user edits within window → success.
- [ ] 5.3 Free user → 403 `premium_required`, post unchanged.
- [ ] 5.4 Non-author edit → 404 (does not confirm existence), no change.
- [ ] 5.5 Edit after 30-min window (author's own post created 31 min ago) → 409 `edit_window_expired`, no change.
- [ ] 5.6 Edit author's own soft-deleted post → 404, no change.
- [ ] 5.7 281-char edit → 400; empty edit → 400; post unchanged.
- [ ] 5.8 Edit to profane content (Reject) → 400 `content_moderated_profanity`, no change; edit to flagged content (Flag) → persists + `moderation_queue` row in same tx.
- [ ] 5.9 Atomicity: simulated `UPDATE posts` failure after snapshot insert → full rollback, no orphan `post_edits` row.
- [ ] 5.10 Concurrency (the Pre-Launch "Post edit concurrency tested" item): two concurrent edits to one post → serialized, each writes its own snapshot, no lost update; forced temporal collision → 409 "Coba lagi sebentar."
- [ ] 5.11 History read: post edited twice → "Versi ke-1"/"Versi ke-2" in chronological order; never-edited post → empty (200).
- [ ] 5.12 History visibility: blocked viewer (either direction) → 404; shadow-banned author's post → 404 for others, 200 for the author.
- [ ] 5.13 Cascade: hard-delete a post with history → `post_edits` rows removed.
- [ ] 5.14 Non-existent `post_id` → 404 (same code/shape a non-author gets — no existence reveal); malformed `post_id` → 400.
- [ ] 5.15 No-op edit (content identical to current) → 400 `no_changes`; no `post_edits` row, `posts.updated_at` unchanged.
- [ ] 5.16 Per-user edit rate limit exceeded → 429 + `Retry-After`; post unchanged.
- [ ] 5.17 Layer-3 async dispatch on the edit path — call-site test mirroring `Layer3DispatchCallSiteTest` (static assertion that `PostEditService` dispatches `layer3DispatcherScope.dispatch(...)` AFTER the `UPDATE`/commit, carrying `coroutineContext`); AND a Reject-verdict case asserts NO dispatch occurs (no committed row).
- [ ] 5.18 History read excludes raw location — the `GET …/edits` response contains content snapshot + version label + `edited_at` and NO `location_snapshot`/`actual_location` field (spatial-fuzzing invariant #3).

## 6. Docs reconciliation + standards

- [ ] 6.1 File a `follow-up` GitHub issue (labels `follow-up` + `backend`) to amend docs/05 §367–407 + docs/06 § Content Moderation to make **edit re-moderation** canonical (design D1, reconciliation bucket (b) — canonical doc incomplete). Link it from the PR body.
- [ ] 6.2 Confirm no `docs/11` § Pattern Registry amendment is needed (this change introduces no new pattern — backend layering / JDBC discipline / transactional-service / moderation dispatch are all existing). Note this confirmation in the PR body.
- [ ] 6.3 Run the full pre-push gate locally: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` (both lint frameworks; the DB-tagged tests on fresh containers per the full-gate rule).
- [ ] 6.4 Verify backend boots with the V22 migration applied and the two routes mounted (KTOR_ENV=test fail-soft per the verify-loop), and a manual `PATCH` + `GET …/edits` round-trip behaves per spec.
