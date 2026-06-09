## 1. Data contract (DTO + repository interface)

- [ ] 1.1 Define `UserProfileResponse` DTO (`@Serializable`, camelCase keys): `userId`, `username`, `displayName`, `bio` (nullable), `followerCount`, `followingCount`, `isSelf`, `followedByViewer`, `isPremium`, `suspendedUntil` (nullable ISO-8601 string), `isPrivate` (nullable Boolean).
- [ ] 1.2 Define the repository read model + interface method in `:core:data` (`id.nearyou.data.repository`), e.g. `UserProfileReader.readProfile(viewerId, targetId): UserProfileRow?` returning the raw row fields (or `null` when unresolvable). Add a `ProfileUserNotFoundException` reuse note (the follow capability already defines `ProfileUserNotFoundException`).

## 2. Repository implementation (read query)

- [ ] 2.1 Implement the JDBC profile-read in `:backend:ktor` (`user` package). Self read (`targetId == viewerId`): resolve from raw `users` on the own-content path — annotate the declaration `@AllowRawPostsRead("own-profile-read")` (or place under an own-content path/filename prefix) for `RawFromPostsRule`, AND add the `// @allow-no-block-exclusion: own-profile-no-self-block` source comment for `BlockExclusionJoinRule` (self carries no block predicate). Other read: resolve from `visible_users` (not raw → no shadow-ban annotation needed).
- [ ] 2.2 Fold the bidirectional block exclusion into the OTHER-user query using the canonical directional-fragment form (matches `ChatRepository.isBlockedBidirectional`; carries the lint-required `user_blocks` + `blocker_id =` + `blocked_id =` tokens): `NOT EXISTS (SELECT 1 FROM user_blocks WHERE (blocker_id = :viewer AND blocked_id = :target) OR (blocker_id = :target AND blocked_id = :viewer))`. Do NOT use the tuple-IN form `(blocker_id, blocked_id) IN (...)` — it lacks the literal fragments the rule's text scan requires and fails lint. Skip the block predicate on the self path (see the `@allow-no-block-exclusion` comment in 2.1).
- [ ] 2.3 Project `followerCount` = `COUNT(*)` of `follows` where followee = target (raw total), `followingCount` = `COUNT(*)` where follower = target (raw total) — NOT viewer-block-filtered (design D1).
- [ ] 2.4 Project `followedByViewer` = `EXISTS (SELECT 1 FROM follows WHERE follower_id = :viewer AND followee_id = :target)`; force `false` on the self path.
- [ ] 2.5 Project `isPremium` = `subscription_status = 'premium_active'`. On the self path additionally project `suspended_until` and the `isPrivate` grace formula `private_profile_opt_in OR (privacy_flip_scheduled_at IS NOT NULL AND privacy_flip_scheduled_at > now())`; null these two on the other-user path (design D2).
- [ ] 2.6 Confirm both lint rules pass: `RawFromPostsRule` (the only raw-`users` read is the annotated own-content self path; the other-user read uses `visible_users`) AND `BlockExclusionJoinRule` (the other-user `users`/`visible_users` read carries the bidirectional block fragments; the self path is suppressed via the `@allow-no-block-exclusion` comment). `users` is a protected table for both rules.

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
- [ ] 6.7 200 — counts are NOT viewer-block-filtered (a follower who blocked the viewer is still counted).
- [ ] 6.8 200 — `isPremium` true for `premium_active`, false for `free` and `premium_billing_retry`.
- [ ] 6.9 200 — self read of a suspended viewer exposes `suspendedUntil`; `isPrivate` honors the 72h grace.
- [ ] 6.10 200 — other-user read does NOT leak `suspendedUntil`/`isPrivate` (both null) even when the target is suspended/private.
- [ ] 6.11 404 — unknown UUID (constant body).
- [ ] 6.12 404 — shadow-banned target (T != viewer), body byte-identical to unknown.
- [ ] 6.13 404 — soft-deleted target.
- [ ] 6.14 404 — viewer-blocked-target (constant body).
- [ ] 6.15 404 — target-blocked-viewer (opposite direction, body byte-identical to viewer-blocked-target).
- [ ] 6.16 400 — malformed `user_id`.
- [ ] 6.17 401 — unauthenticated request.

## 7. Lint + local gate

- [ ] 7.1 Run `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` locally — both lint frameworks must pass (not detekt alone).

## 8. Pre-archive staging smoke (runtime-impacting → smoke before archive)

- [ ] 8.1 Manual branch deploy: `gh workflow run deploy-staging.yml --ref user-profile-read-endpoint`; poll the deploy run.
- [ ] 8.2 Smoke against the branch deploy (authenticated): `GET /api/v1/users/{self}` → 200 self card with self-only fields; `GET /api/v1/users/{other}` → 200; `GET /api/v1/users/<random-uuid>` → 404 `user_not_found`; `GET /api/v1/users/not-a-uuid` → 400.
- [ ] 8.3 `openspec validate user-profile-read-endpoint --strict` green before archive.
