## 1. Data contract (DTO + repository interface)

- [ ] 1.1 Define `UserProfileResponse` DTO (`@Serializable`, camelCase keys): `userId`, `username`, `displayName`, `bio` (nullable), `followerCount`, `followingCount`, `isSelf`, `followedByViewer`, `isPremium`, `suspendedUntil` (nullable ISO-8601 string), `isPrivate` (nullable Boolean).
- [ ] 1.2 Define the repository read model + interface method in `:core:data` (`id.nearyou.data.repository`), e.g. `UserProfileReader.readProfile(viewerId, targetId): UserProfileRow?` returning the raw row fields (or `null` when unresolvable). Add a `ProfileUserNotFoundException` reuse note (the follow capability already defines `ProfileUserNotFoundException`).

## 2. Repository implementation (read query)

- [ ] 2.1 Implement the JDBC profile-read in `:backend:ktor` (`user` package). Self read (`targetId == viewerId`): resolve from raw `users` on the own-content path, annotating the reader declaration `@AllowMissingBlockJoin("own-profile-read: self read of raw users — a shadow-banned viewer must see their own profile; no block predicate because you cannot block yourself")` (the registered `BlockExclusionJoinRule` bypass — `users` is in that rule's `PROTECTED_TABLE_PATTERN`; precedent `JdbcActorUsernameLookup`). Do NOT add `@AllowRawPostsRead` — `RawFromPostsRule` matches `posts` only, never `users`, so it is inert. Do NOT use a `// @allow-no-block-exclusion: own-profile-…` comment — only the chat token is registered; any other comment marker is silently ignored and the build fails. Other read: resolve from `visible_users` (matches neither rule's pattern → no annotation needed).
- [ ] 2.2 Fold the bidirectional block exclusion into the OTHER-user query for the leak-safe 404 behavior, using the canonical directional-fragment form (matches `ChatRepository.isBlockedBidirectional`): `NOT EXISTS (SELECT 1 FROM user_blocks WHERE (blocker_id = :viewer AND blocked_id = :target) OR (blocker_id = :target AND blocked_id = :viewer))`. NOTE this predicate is a CORRECTNESS guardrail, not a lint one — `BlockExclusionJoinRule`'s `PROTECTED_TABLE_PATTERN` does NOT match `visible_users`, so the both-direction 404 tests (6.14/6.15) are the real guardrail. Skip the block predicate on the self path (annotated in 2.1).
- [ ] 2.3 Project `followerCount` = `COUNT(*)` of `follows` where followee = target (raw total), `followingCount` = `COUNT(*)` where follower = target (raw total) — NOT viewer-block-filtered (design D1).
- [ ] 2.4 Project `followedByViewer` = `EXISTS (SELECT 1 FROM follows WHERE follower_id = :viewer AND followee_id = :target)`; force `false` on the self path.
- [ ] 2.5 Project `isPremium` = `subscription_status = 'premium_active'` (badge: actively-premium only). On the self path additionally project `suspended_until` and the canonical effective-private `isPrivate` = `(private_profile_opt_in AND subscription_status IN ('premium_active','premium_billing_retry')) OR (privacy_flip_scheduled_at IS NOT NULL AND privacy_flip_scheduled_at > now())` — NOTE the premium-status conjunct is required (a Free user with stale `private_profile_opt_in` is NOT effectively private; the grace term is forward-looking plumbing, worker is DESIGN-status); null both `suspendedUntil` and `isPrivate` on the other-user path (design D2). `isPremium` (premium_active only) and the privacy premium-set (`premium_active`+`premium_billing_retry`) are deliberately different — do not conflate.
- [ ] 2.6 Confirm lint: `BlockExclusionJoinRule` — the self raw-`users` read is suppressed by the `@AllowMissingBlockJoin` annotation (2.1); the other-user read is `FROM visible_users`, which the rule's `PROTECTED_TABLE_PATTERN` does not match (no annotation needed). `RawFromPostsRule` does not apply to `users` at all (matches `posts` only). Run `:lint:detekt-rules:test` + root `detekt` to confirm.

## 3. Service layer

- [ ] 3.1 Add `UserProfileService.getProfile(viewerId, targetId): UserProfileResponse` mapping the repository row to the DTO; map "unresolvable row" to the not-found signal the route turns into 404.

## 4. Route + error mapping

- [ ] 4.1 Add `fun Application.userProfileRoutes(service: UserProfileService)` registering `get("/api/v1/users/{user_id}")` under `authenticate(AUTH_PROVIDER_USER)`.
- [ ] 4.2 Parse `user_id` via the existing UUID-parse helper → `400 invalid_request` on non-UUID; missing principal → `401`.
- [ ] 4.3 Map an unresolvable target (unknown / soft-deleted / shadow-banned / blocked-either-direction) to a CONSTANT `404` body `{"error":{"code":"user_not_found"}}` using `respondText` (byte-identical regardless of cause — mirror `FollowRoutes` `FOLLOW_BLOCKED_BODY` leak-prevention, design D4).
- [ ] 4.4 Success → `200` with the `UserProfileResponse` JSON.

## 5. Wiring

- [ ] 5.1 Register the route in `Application.kt` (call `userProfileRoutes(...)` alongside the existing `followRoutes` / `userSocialRoutes`).
- [ ] 5.2 Add the repository + service to the Koin module(s) used by the `user`/`follow` route wiring.

## 6. Integration tests (DB-tagged)

- [ ] 6.1 New `*RoutesTest` (DB-tagged) MUST `autoClose(hikari())` with pool size 2 (CI connection-budget rule).
- [ ] 6.2 200 — viewer reads another user's public profile (isSelf=false, followedByViewer=false, self-only fields null).
- [ ] 6.3 200 — `followedByViewer = true` when the viewer follows the target.
- [ ] 6.4 200 — viewer reads own profile (isSelf=true, followedByViewer=false).
- [ ] 6.5 200 — shadow-banned VIEWER reads own profile (own-content path; not filtered).
- [ ] 6.6 200 — `followerCount`/`followingCount` reflect the follows graph (e.g. 3 followers / 5 following).
- [ ] 6.7 200 — counts are NOT viewer-block-filtered: a FOLLOWER who blocked the viewer is still in `followerCount`, AND (symmetric) a FOLLOWEE the target follows who blocked the viewer is still in `followingCount` (locks D1 on both axes).
- [ ] 6.8 200 — `isPremium` true for `premium_active`, false for `free` and `premium_billing_retry`.
- [ ] 6.9 200 — self-read `isPrivate` matrix: (a) `private_profile_opt_in=TRUE` + `premium_active`, no grace → `true`; (b) `private_profile_opt_in=TRUE` + `free`, no grace → `false` (premium-conjunct guard); (c) base-false + `privacy_flip_scheduled_at` in FUTURE → `true` (grace); (d) base-false + `privacy_flip_scheduled_at` in PAST → `false` (guards against an inverted `<` comparison). Also: self read of a suspended viewer exposes `suspendedUntil` (ISO-8601).
- [ ] 6.10 200 — other-user read does NOT leak `suspendedUntil`/`isPrivate` (both null) even when the target is suspended AND effectively private.
- [ ] 6.11 404 — unknown UUID. Assert `response.bodyAsText()` equals the shared constant `{"error":{"code":"user_not_found"}}` (define once, reuse in 6.12/6.14/6.15).
- [ ] 6.12 404 — shadow-banned target (T != viewer); assert body equals the same shared constant (byte-identical to 6.11).
- [ ] 6.13 404 — soft-deleted target; assert body equals the same shared constant.
- [ ] 6.14 404 — viewer-blocked-target; assert body equals the same shared constant.
- [ ] 6.15 404 — target-blocked-viewer (opposite direction); assert body is byte-identical to 6.14 AND to 6.11 (the block-direction non-leak — the security property D4 exists to protect). Recommend one assertion that all five 404 bodies are mutually equal.
- [ ] 6.16 400 — malformed `user_id` (`invalid_request`).
- [ ] 6.17 401 — unauthenticated request.

## 7. Lint + local gate

- [ ] 7.1 Run `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` locally — both lint frameworks must pass (not detekt alone).

## 8. Pre-archive staging smoke (runtime-impacting → smoke before archive)

- [ ] 8.1 Manual branch deploy: `gh workflow run deploy-staging.yml --ref user-profile-read-endpoint`; poll the deploy run.
- [ ] 8.2 Smoke against the branch deploy (authenticated): `GET /api/v1/users/{self}` → 200 self card with self-only fields; `GET /api/v1/users/{other}` → 200; `GET /api/v1/users/<random-uuid>` → 404 `user_not_found`; `GET /api/v1/users/not-a-uuid` → 400.
- [ ] 8.3 `openspec validate user-profile-read-endpoint --strict` green before archive.
