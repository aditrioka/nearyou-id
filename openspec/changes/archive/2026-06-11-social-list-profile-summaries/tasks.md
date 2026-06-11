# Tasks: social-list-profile-summaries

## 1. Core-data row types + repository (follow lists)

- [x] 1.1 Extend `FollowListRow` (`:core:data`) with `username: String`, `displayName: String`, `isPremium: Boolean`
- [x] 1.2 `JdbcUserFollowsRepository.listFollowers`/`listFollowing`: add INNER `JOIN visible_users vu ON vu.id = f.<follower|followee>_id`, project `vu.username`, `vu.display_name`, `(vu.subscription_status = 'premium_active') AS is_premium` (profile-read formula); keep both bidirectional `user_blocks` NOT-IN subqueries on the `follows` clause (NOTE: `follows` is outside `BlockExclusionJoinRule`'s protected pattern and `visible_users` doesn't trip it — the lint rule does NOT guard these, the integration scenarios do); keyset predicate + ORDER BY untouched (join columns in neither)
- [x] 1.3 Replace `ensureProfileExists` (raw `users`) with the two-path resolution gate: self (`profileId == viewerId`) → raw-`users` existence with the own-content justification KDoc'd at the SQL-holding declaration (NOTE the `@AllowMissingBlockJoin` annotation is inapplicable here: it lives in `:backend:ktor` and the detekt ruleset does not scan `:infra:supabase` — scenarios are the guardrail; justification mirrors `JdbcUserProfileReader.SQL_SELF`); other → `visible_users` + `NOT EXISTS` bidirectional `user_blocks` (param order mirroring `JdbcUserProfileReader.SQL_OTHER`); unresolvable → `ProfileUserNotFoundException`. Expose the gate to `FollowService` (interface method on `UserFollowsRepository`) and update every fake implementing the interface (`SocialGraphRateLimitTest`'s fake breaks at compile otherwise)
- [x] 1.4 Delete the unused non-tx `UserFollowsRepository.follow()` + its `JdbcUserFollowsRepository` implementation (zero production call sites; its KDoc would otherwise document the removed 409 contract)

## 2. Follow routes + service (wire + constant-404 + POST alignment)

- [x] 2.1 `FollowListItem` gains `username`/`displayName`/`isPremium` (bare camelCase, no `@SerialName`); `FollowPage.toResponse()` maps the new fields
- [x] 2.2 List endpoints' `ProfileUserNotFoundException` handler switches from `respondError` (map body with `message`) to a constant `respondText` 404 — body literal `{"error":{"code":"user_not_found"}}`. The profile constant is a file-private top-level val in `UserProfileRoutes.kt`, so duplicate the literal with a cross-reference comment on BOTH sides (tests assert byte-equality against the real profile route, not the constant)
- [x] 2.3 `POST /follows/{user_id}`: resolution gate runs INSIDE `FollowService.follow`, AFTER `checkRateLimit` and before the transaction (routes stay SQL-free per docs/11 §3.1; 404-probes burn the 50/h bucket — design D5; 429 takes precedence over 404); map gate misses AND `UserNotFoundException` (FK backstop) AND `FollowBlockedException` (in-tx guard, unchanged) to the SAME constant 404; delete `FOLLOW_BLOCKED_BODY` + the 409 mapping; `DELETE /follows` untouched (204-always)
- [x] 2.4 KDoc updates: `followRoutes`/`userSocialRoutes` error-mapping tables (constant-404 contract, removed 409, D4-posture rationale) AND `UserProfileRoutes.kt` KDoc line ~30 whose "mirroring `FollowRoutes.FOLLOW_BLOCKED_BODY`" cite goes stale when that constant is deleted

## 3. Blocks list (user-blocking)

- [x] 3.1 Extend `UserBlockRow` (lives in the `infra/supabase` repo module, NOT `:core:data`) with `username`/`displayName`/`isPremium`
- [x] 3.2 `JdbcUserBlockRepository.listOutbound`: `LEFT JOIN visible_users u ON u.id = b.blocked_id` + `COALESCE(u.username, 'akun_dihapus')`, `COALESCE(u.display_name, 'Akun Dihapus')`, `COALESCE(u.subscription_status = 'premium_active', FALSE)` — placeholder literals byte-matching `ChatRepository.listMyConversations`; keyset/ORDER BY untouched; no block-direction masking
- [x] 3.3 `BlockListItem` gains the same camelCase summary fields; `BlockRoutes` maps them; POST/DELETE `/blocks` handlers untouched (negative requirement)

## 4. Tests (kotest `@Tags("database")`, existing pools/harnesses — no new HikariPool)

- [x] 4.0 Extend the `seedUser()` helpers in `FollowEndpointsTest`/`BlockEndpointsTest` with `isShadowBanned`/`deletedAt`/`subscriptionStatus` params (copy the `UserProfileRoutesTest.seedUser` pattern — all states seedable under the V2 CHECK); mount `userProfileRoutes(UserProfileService(JdbcUserProfileReader(dataSource)))` into the `withFollows` harness (same dataSource, no new pool) so byte-compare tests can fetch the real profile 404
- [x] 4.1 Rewrite the three 409 tests → constant-404: caller-blocked, target-blocked, shadow-banned, soft-deleted, unknown → all 404, no `follows` row; assert bodies byte-identical across causes AND equal to the live `GET /users/{id}` 404 `bodyAsText`
- [x] 4.2 Visible-target follow still 204 + edge created (sanity); re-follow idempotency + self-follow 400 unchanged; 429-precedes-404 stays pinned by `SocialGraphRateLimitTest` (update its fake per 1.3)
- [x] 4.3 `/followers` + `/following`: enriched-row assertions on RAW JSON keys (exact camelCase present, NO snake_case variants), values from the seeded users row; `isPremium` profile-read formula (`premium_active` → true, `premium_billing_retry` → false)
- [x] 4.4 `/followers` + `/following`: shadow-banned + soft-deleted members excluded from rows; viewer-block exclusion both directions; order + page-cap-30 preserved; ADD the previously-missing list tests — malformed cursor → 400 `invalid_cursor`, `nextCursor` null on last page, profile OWNER sees own followers minus their own blocks (caller = P, P blocked X)
- [x] 4.5 `/followers` + `/following`: constant-404 differential — five separate fixtures (unknown / shadow-banned / soft-deleted / caller-blocked-target / target-blocked-caller) → byte-identical status+body, equal to the profile-read 404; shadow-banned caller reads OWN lists → 200
- [x] 4.6 Blocks list: enriched visible row asserted on RAW JSON keys (no snake_case variants); hidden (shadow-banned, soft-deleted) blocked user → placeholder mask (`akun_dihapus`/`Akun Dihapus`/false) with row surviving + real `userId`; counter-block does NOT mask; `POST /blocks` on shadow-banned + soft-deleted target → 204 + row (negative-requirement scenarios); unknown target → 404
- [x] 4.7 401-without-JWT coverage across the four follow endpoints + three block endpoints retained
- [x] 4.8 `UserProfileRoutesTest`: counts are NOT visibility-filtered — seed a shadow-banned follower, assert `followerCount` still counts them (new `user-profile-read` scenario)
- [x] 4.9 In-tx guard coverage (the resolution gate makes pre-existing blocks unreachable at the endpoint): repo-level test — seed a block, call `followInTx` directly → `FollowBlockedException`; plus a route-mapping test (fake repo throwing `FollowBlockedException`) → constant 404 (TOCTOU scenario)

## 5. Gates + delivery

- [x] 5.1 `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` green locally (no local `:backend:ktor:run` alive during the run)
- [x] 5.2 `openspec validate social-list-profile-summaries --strict` green; PR title/body updated at the phase boundary (`gh pr edit`)
- [x] 5.3 Staging branch-deploy smoke (docs/11 §5 item 4) — deploy run 27356512714 success; fixtures seeded via Supabase MCP (premium + shadow-banned followers of `smoketest_adi`); verified: enriched camelCase rows + `isPremium=true` (premium_active), hidden follower excluded, byte-identical `{"error":{"code":"user_not_found"}}` 404 across hidden-followers/ghost-followers/hidden-following/hidden-profile, `POST /follows` constant 404 for hidden + ghost targets; fixtures cleaned (0 remaining)
- [x] 5.4 At archive: refresh the `follow-system` Purpose paragraph (constant-404 posture, visible_users filtering, profile summaries) and touch the `user-blocking` Purpose line for the enriched list (stale-Purpose precedent: PR #171) — done in the archive commit
- [ ] 5.5 Close-the-loop after merge (executed by the audit-burndown session post-squash-merge): close #211 (auto via PR "Closes"); comment the social-list action item done on #196 (issue stays open for the mobile profile screen); PROGRESS.md § Remaining updated in the archive commit (rides this PR)
