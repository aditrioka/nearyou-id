## Why

The keystone mobile screen `mobile-profile-screen` (`openspec/project.md` § Mobile-First to Full-Demo, live-menu pick #1) must render a user's profile — own and other users: handle, display name, bio, follower/following counts, follow state, and an own-profile suspension countdown. But no backend endpoint returns profile data today. The follow (`POST/DELETE /api/v1/follows/{user_id}`, `/followers`, `/following`), block (`/api/v1/blocks`), and report (`/api/v1/reports`) endpoints all exist; a profile-**read** endpoint does not, and there is no `user-profile` capability in `openspec/specs/`. `follow-system/spec.md` even references "the `is_following` / `followed_by_viewer` fields on user profiles" as a concept with no endpoint surfacing them. This change is the backend dependency that unblocks the mobile keystone — splitting the backend read from the mobile screen mirrors the established `nearby-timeline` / `mobile-nearby-timeline` precedent, and is a legitimate backend pick under project.md § A.0 ("backend picks valid when a dependency for the prioritized mobile work").

## What Changes

- Add a new Ktor route **`GET /api/v1/users/{user_id}`** (Bearer JWT, `AUTH_PROVIDER_USER`) returning a `UserProfileResponse` DTO for a single user (own or other).
- Response carries: `userId`, `username`, `displayName`, `bio` (nullable), `followerCount`, `followingCount`, `isSelf`, `followedByViewer`, `isPremium`, and the **self-only** fields `suspendedUntil` (nullable, drives the mobile suspension countdown) + `isPrivate` (private-profile flag honoring the 72h privacy-flip grace window). camelCase wire keys, matching the repo's mixed-case timeline-DTO convention.
- Leak-safe, shadow-ban-safe, bidirectional-block-aware semantics:
  - Unknown / soft-deleted / shadow-banned target → **404** `user_not_found` (other-user reads go through the `visible_users` view).
  - Viewer-blocked-target **or** target-blocked-viewer → **404** `user_not_found` with a constant, direction-less body (mirrors `FollowRoutes` block-leak prevention).
  - Malformed `user_id` → **400** `invalid_request`; missing/invalid principal → **401**.
  - Own profile (`target == viewer`) is served via the Repository own-content raw-`users` path (the shadow-ban invariant's documented own-content exception) so a shadow-banned viewer still sees their own profile.
- **No schema migration.** All columns already exist (`users` V2; `follows` V6; `user_blocks` V5; `visible_users` view V7). Pure read-path addition.

## Capabilities

### New Capabilities
- `user-profile-read`: the `GET /api/v1/users/{user_id}` profile-read endpoint — single-user profile projection (handle/display-name/bio/counts/follow-state/premium-flag + self-only suspension+privacy state), shadow-ban-safe via `visible_users`, bidirectional-block-aware (leak-safe 404), with the own-content raw-`users` exception for self reads.

### Modified Capabilities
<!-- None. The follow / block / report capabilities are consumed (counts, block-exclusion, is_following), not modified — their requirements and response shapes are unchanged. -->

## Impact

- **New code** in `:backend:ktor` `user` package: route + `UserProfileResponse` DTO + service + repository read query; Koin wiring + `Application.kt` route registration.
- **APIs**: adds `GET /api/v1/users/{user_id}` under the existing `/api/v1` surface. No change to existing endpoints.
- **DB**: read-only against existing tables/views (`visible_users`, `users` own-content path, `follows`, `user_blocks`). No migration, no new index.
- **Invariants exercised**: shadow-ban (`visible_users`), block-exclusion (bidirectional `user_blocks` NOT EXISTS), block-state non-leak (constant 404 body).
- **Downstream**: unblocks `mobile-profile-screen`; consumed later by the live Following feed (follow action surfaced on the profile).
- **Tests**: new DB-tagged `*RoutesTest` (HikariPool `autoClose` + size 2 per the CI connection-budget rule) + unit tests.
