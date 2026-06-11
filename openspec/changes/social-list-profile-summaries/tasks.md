# Tasks: social-list-profile-summaries

## 1. Core-data row types + repository (follow lists)

- [ ] 1.1 Extend `FollowListRow` (`:core:data`) with `username: String`, `displayName: String`, `isPremium: Boolean`
- [ ] 1.2 `JdbcUserFollowsRepository.listFollowers`/`listFollowing`: add INNER `JOIN visible_users vu ON vu.id = f.<follower|followee>_id`, project `vu.username`, `vu.display_name`, `(vu.subscription_status = 'premium_active') AS is_premium` (design-D2 formula); keep both bidirectional `user_blocks` NOT-IN subqueries on the `follows` clause (`BlockExclusionJoinRule`), keyset predicate + ORDER BY untouched (join columns in neither)
- [ ] 1.3 Replace `ensureProfileExists` (raw `users`) with the two-path resolution gate: self (`profileId == viewerId`) → raw-`users` existence on an `@AllowMissingBlockJoin`-annotated SQL-holding property (annotation on the declaration holding the literal — PR #207 lesson; justification: own-content path, shadow-banned viewer keeps own lists); other → `visible_users` + `NOT EXISTS` bidirectional `user_blocks` (param order mirroring `JdbcUserProfileReader.SQL_OTHER`); unresolvable → `ProfileUserNotFoundException`

## 2. Follow routes + service (wire + constant-404 + POST alignment)

- [ ] 2.1 `FollowListItem` gains `username`/`displayName`/`isPremium` (bare camelCase, no `@SerialName`); `FollowPage.toResponse()` maps the new fields
- [ ] 2.2 List endpoints' `ProfileUserNotFoundException` handler switches from `respondError` (map body with `message`) to the constant `respondText` 404 — body literal identical to `UserProfileRoutes.USER_NOT_FOUND_BODY` (`{"error":{"code":"user_not_found"}}`); share/duplicate the constant per layering, with a comment tying the two literals
- [ ] 2.3 `POST /follows/{user_id}`: resolve the target through the same gate BEFORE `FollowService.follow` (or inside it, per §3.1 service-owns-rules); map `UserNotFoundException` (FK backstop) AND `FollowBlockedException` (in-tx guard, unchanged) AND gate misses to the SAME constant 404; delete `FOLLOW_BLOCKED_BODY` + the 409 mapping; `DELETE /follows` untouched (204-always)
- [ ] 2.4 KDoc updates on `followRoutes`/`userSocialRoutes` (error-mapping tables now show the constant-404 contract; note the D4-posture rationale and the removed 409)

## 3. Blocks list (user-blocking)

- [ ] 3.1 Extend `UserBlockRow` (`:core:data`) with `username`/`displayName`/`isPremium`
- [ ] 3.2 `JdbcUserBlockRepository.listOutbound`: `LEFT JOIN visible_users u ON u.id = b.blocked_id` + `COALESCE(u.username, 'akun_dihapus')`, `COALESCE(u.display_name, 'Akun Dihapus')`, `COALESCE(u.subscription_status = 'premium_active', FALSE)` — placeholder literals byte-matching `ChatRepository.listMyConversations`; keyset/ORDER BY untouched; no block-direction masking
- [ ] 3.3 `BlockListItem` gains the same camelCase summary fields; `BlockRoutes` maps them; POST/DELETE `/blocks` handlers untouched (negative requirement)

## 4. Tests (FollowEndpointsTest + block-list coverage, kotest `@Tags("database")`, existing pools — no new HikariPool)

- [ ] 4.1 Rewrite block-409 tests → constant-404: caller-blocked, target-blocked, shadow-banned, soft-deleted, unknown → all 404, no `follows` row; assert bodies byte-identical across causes AND equal to the `GET /users/{id}` 404 body (fetch both, compare exact `bodyAsText`)
- [ ] 4.2 Visible-target follow still 204 + edge created (sanity); re-follow idempotency + self-follow 400 unchanged
- [ ] 4.3 `/followers` + `/following`: enriched-row assertions (exact camelCase keys present, NO snake_case variants — assert on raw JSON keys), values from the seeded users row; `isPremium` D2 (premium_active → true, premium_billing_retry → false)
- [ ] 4.4 `/followers` + `/following`: shadow-banned + soft-deleted members excluded from rows; viewer-block exclusion both directions preserved; order + cursor pagination tests preserved (page cap 30, `nextCursor` null on last page, malformed cursor 400)
- [ ] 4.5 `/followers` + `/following`: constant-404 differential test (unknown / shadow-banned / soft-deleted / blocked-both-directions → byte-identical status+body, equal to profile-read 404); shadow-banned caller reads OWN lists → 200
- [ ] 4.6 Blocks list: enriched visible row; hidden (shadow-banned, soft-deleted) blocked user → placeholder mask (`akun_dihapus`/`Akun Dihapus`/false) with row surviving + real `userId`; counter-block does NOT mask; `POST /blocks` on shadow-banned + soft-deleted target → 204 + row (negative-requirement scenarios); unknown target → 404
- [ ] 4.7 401-without-JWT coverage across the five follow endpoints retained

## 5. Gates + delivery

- [ ] 5.1 `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` green locally (no local `:backend:ktor:run` alive during the run)
- [ ] 5.2 `openspec validate social-list-profile-summaries --strict` green; PR title/body updated at the phase boundary (`gh pr edit`)
- [ ] 5.3 Staging branch-deploy smoke (docs/11 §5 item 4, runtime-impacting backend change): seed a follower fixture incl. one shadow-banned member; verify enriched rows, the byte-identical 404 triple, `POST /follows` 404 on a hidden target; guard against main auto-deploy clobber mid-smoke
- [ ] 5.4 Close-the-loop after merge: close #211; comment the social-list action item done on #196 (issue stays open for the mobile profile screen); update `dev/audits/2026-06-10-holistic-audit/PROGRESS.md` § Remaining (03-#5 + 03-#6 → shipped via this PR)
