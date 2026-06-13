## 1. Repository — viewer-visible single-post projection

- [ ] 1.1 Add a `PostReadRepository` interface in `core/data/src/main/kotlin/id/nearyou/data/repository/` with `findVisiblePostById(postId: UUID, viewerId: UUID): SinglePostRow?` and a `SinglePostRow` data class carrying `id`, `authorUsername`, `authorDisplayName`, `content`, `cityName`, `createdAt`, `likedByViewer`, `replyCount` (NO author UUID, NO coordinates — Decision 2).
- [ ] 1.2 Implement `JdbcPostReadRepository` in `infra/supabase/src/main/kotlin/id/nearyou/app/infra/repo/` mirroring `JdbcPostLikeRepository.resolveVisiblePost`'s two-arm gate (Decision 1): a `visible_posts` arm with the bidirectional `user_blocks` NOT-IN subqueries (`blocker_id`/`blocked_id` token pair) `UNION ALL` an own-content raw-`posts` arm (`id = ? AND author_id = ? AND deleted_at IS NULL`), `LIMIT 1`. Select the full projection on each arm: join `visible_users` for author identity on the `visible_posts` arm, raw `users` for the viewer's own row on the own-content arm; compute `liked_by_viewer` (`EXISTS` against `post_likes` for the viewer) and `reply_count` reusing the timeline projection's computation verbatim.
- [ ] 1.3 Annotate the own-content raw arm with `@AllowRawPostsRead("...")` (own-content self-arm rationale, same shape as `resolveVisiblePost`); confirm the `visible_posts` arm literal carries the `visible_posts`, `user_blocks`, `blocker_id =`, and `blocked_id =` tokens for `BlockExclusionJoinRule` compliance. Use `PreparedStatement` + `.use {}` discipline (docs/11 § JDBC).
- [ ] 1.4 Bind the JDBC impl in DI / `Application.kt` wiring (the same place the other `Jdbc*Repository` instances are constructed).

## 2. Service — read + visibility-to-error mapping

- [ ] 2.1 Add `PostReadService` in `backend/ktor/src/main/kotlin/id/nearyou/app/post/` with `getById(viewerId: UUID, postId: UUID): SinglePostRow` that calls `findVisiblePostById` and throws `PostNotFoundException` when the repository returns `null` (reuse the existing `PostNotFoundException` the like/reply paths throw, or add it if not visible from this module).

## 3. Route + DTO + wiring

- [ ] 3.1 Add the `SinglePostResponse` `@Serializable` DTO (in the `post` package) with the EXACT mixed-case wire from the spec: `@SerialName("city_name") cityName`, `@SerialName("liked_by_viewer") likedByViewer`, `@SerialName("reply_count") replyCount`; bare camelCase `id`, `authorUsername`, `authorDisplayName`, `content`, `createdAt`, and `distanceM: Double? = null`. NO `authorUserId`, NO `latitude`/`longitude`.
- [ ] 3.2 Add `singlePostRoutes(service: PostReadService)` Application extension registering `GET /api/v1/posts/{id}` under `authenticate(AUTH_PROVIDER_USER)`, mirroring `UserProfileRoutes`: principal-null → `401`; non-UUID `{id}` → `400 invalid_request`; `PostNotFoundException` → `respondText(POST_NOT_FOUND_BODY, ContentType.Application.Json, NotFound)` with `POST_NOT_FOUND_BODY = """{"error":{"code":"post_not_found"}}"""`; success → `200` + `SinglePostResponse`. The body literal MUST stay byte-identical to the shipped `LikeRoutes.kt` / `ReplyRoutes.kt` `POST_NOT_FOUND_BODY` constant (the established like/reply 404 contract — add a cross-route byte-equality assertion in the route test, mirroring the `user-profile-read` ↔ `FollowRoutes` precedent).
- [ ] 3.3 Wire `singlePostRoutes(...)` into `Application.kt` route registration alongside `postRoutes` / `userProfileRoutes`.

## 4. Tests — repository (DB-tagged)

- [ ] 4.1 Create a DB-tagged repository test for `findVisiblePostById` with `autoClose(hikari())` + `maximumPoolSize = 2` (CI connection-budget rule). Cover: visible post returns full projection; unknown id → null; soft-deleted post → null; other-author shadow-banned → null; auto-hidden post → null; viewer-blocked-author → null; author-blocked-viewer → null; shadow-banned author reads own (non-deleted) post → row; author's own soft-deleted post → null.
- [ ] 4.2 Assert `liked_by_viewer` true/false per the viewer's like state and `reply_count` equals the timeline-consistent count (not viewer-block-filtered).

## 5. Tests — route (HTTP, DB-tagged) mapping every spec scenario

- [ ] 5.1 Create `SinglePostRoutesTest` (DB-tagged, `autoClose(hikari())` + pool size 2). Happy path: `200` with the full projection (`GET /api/v1/posts/{id}` → "Visible post returns the full projection", "likedByViewer reflects ...", "replyCount reflects ...", "Empty city_name is preserved").
- [ ] 5.2 No-PII assertions: parse the `200` body and assert it has no `authorUserId`/author-UUID key, no `latitude`/`longitude` key, and no non-null `distanceM` ("Response body contains no author UUID and no coordinates", "distanceM is never a non-null value in v1").
- [ ] 5.3 `404` cases each return byte-identical `{"error":{"code":"post_not_found"}}`: unknown UUID, soft-deleted post, other-author shadow-banned, auto-hidden, viewer-blocked-author, author-blocked-viewer; plus an explicit byte-equality assertion across all six bodies ("All 404 causes are byte-identical").
- [ ] 5.4 Own-content: shadow-banned author reads their own live post → `200` with identity; author's own soft-deleted post → `404` ("A shadow-banned author reads their own post via the own-content arm").
- [ ] 5.5 Malformed + auth: non-UUID `{id}` → `400 invalid_request` (not `404`); no Bearer JWT → `401` ("rejects malformed and unauthenticated requests").

## 6. Tests — serialization (commonTest / unit)

- [ ] 6.1 `SinglePostResponse` serialization round-trip asserts the snake_case keys `city_name`/`liked_by_viewer`/`reply_count` and bare camelCase `id`/`authorUsername`/`authorDisplayName`/`content`/`createdAt` ("Response serializes with the mixed-case keys").
- [ ] 6.2 Negative-guard fixture: a body using camelCase `cityName` does NOT populate `SinglePostResponse.cityName` ("camelCase cityName does not bind", PR #128 precedent).

## 7. Verification + lint gate

- [ ] 7.1 Run the local pre-push gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` (both lint frameworks per CLAUDE.md; the new DB-tagged tests run under the backend test gate with fresh DB containers).
- [ ] 7.2 Manual smoke: boot Ktor locally (`KTOR_ENV=test` per the local-boot env set) and `curl` `GET /api/v1/posts/{id}` for (a) a visible post → `200`, (b) an unknown UUID → `404 post_not_found`, (c) `not-a-uuid` → `400`, (d) no token → `401`; capture the outputs as DoD evidence in the PR body (docs/11 § 5).

## 8. Housekeeping

- [ ] 8.1 At archive, close GitHub issue [#202](https://github.com/aditrioka/nearyou-id/issues/202) `backend-single-post-get-endpoint` (the by-id endpoint it tracks now ships). Leave issue [#188](https://github.com/aditrioka/nearyou-id/issues/188) (replies infinite-scroll) open — out of scope here.
- [ ] 8.2 Reconcile the now-stale "(none exists on the backend)" parenthetical in `openspec/specs/mobile-post-detail/spec.md` § "The post header renders from nav args ..." / § "By-id post fetch ... deferred": the mobile behavior is unchanged (still renders from nav args, issues no by-id GET), but the rationale that "none exists" is no longer true. Update the parenthetical (or note via the archive spec-sync) so the mobile-post-detail spec doesn't assert a falsehood post-merge.
