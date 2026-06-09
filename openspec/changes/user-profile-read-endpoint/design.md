## Context

`mobile-profile-screen` (the keystone of the mobile-first-to-full-demo critical path) needs to render a single user's profile, for both the viewer's own profile and other users. No backend endpoint surfaces this data. The platform already has the underlying tables and views:

- `users` (V2): `username`, `display_name` (NOT NULL), `bio` (nullable VARCHAR(160)), `private_profile_opt_in`, `privacy_flip_scheduled_at`, `is_shadow_banned`, `is_banned`, `suspended_until`, `subscription_status` (`free`|`premium_active`|`premium_billing_retry`), `deleted_at`.
- `visible_users` view (V7) = `SELECT u.* FROM users u WHERE u.deleted_at IS NULL AND u.is_shadow_banned = FALSE` — the shadow-ban-safe read surface for non-admin, non-own-content paths.
- `follows` (V6): backs `followerCount`, `followingCount`, and `followedByViewer`.
- `user_blocks` (V5): bidirectional block rows, used for the leak-safe 404.

The existing `follow` capability (`FollowRoutes.kt` + `follow-system/spec.md`) establishes the conventions this endpoint mirrors: Bearer JWT auth via `AUTH_PROVIDER_USER`, `parseUserId` → 400 on non-UUID, 404 `user_not_found` on unknown/blocked target, and a **constant, direction-less body** for block-state responses so a viewer can't tell which side initiated a block.

This is a read-only addition. There is no migration, no new index, no new dependency.

## Goals / Non-Goals

**Goals:**
- Expose `GET /api/v1/users/{user_id}` returning a single `UserProfileResponse` for own + other users.
- Honor the three relevant invariants exactly: shadow-ban (`visible_users`), bidirectional block-exclusion (`user_blocks` NOT EXISTS → 404), and block-state non-leak (constant 404 body).
- Surface enough for the mobile profile card: identity (handle/display-name/bio), social counts, viewer follow-state, premium badge flag, and the viewer's own suspension countdown + privacy state.
- Reuse the established `follow`-route seam (auth provider, UUID parsing, error mapping) so the surface is consistent.

**Non-Goals:**
- **No profile mutation.** Username change (`PATCH /api/v1/user/username`), bio/display-name edits, and privacy-toggle writes are out of scope (separate DESIGN-status work per `02-Product.md`).
- **No private-profile content gating.** Hiding a private user's posts/follow-lists from non-followers is the concern of the feed/list endpoints, not this read endpoint (see Decision 3).
- **No follower/following list payload.** Those already exist as `GET /api/v1/users/{user_id}/followers|following`. This endpoint returns only the aggregate counts.
- **No mobile work.** The Compose screen ships as the separate `mobile-profile-screen` change that consumes this contract.
- **No new migration / schema change.**

## Decisions

### Decision 1 — Follower/following counts are RAW totals, not viewer-block-filtered
The `followerCount` / `followingCount` are the unfiltered totals from `follows` (count of edges into / out of the profile). This is **intentionally asymmetric** with the `/followers` and `/following` *list* endpoints, which ARE bidirectionally viewer-block-filtered.

- **Why:** A follower *count* is a public aggregate (every social platform shows it the same to everyone). Per-viewer-filtering the count would (a) leak block state through count deltas — a viewer could infer "X blocked me" by comparing the count to the visible list length — and (b) require a block-join on every count, which is wasteful for a scalar. The list endpoints filter because they enumerate identities; the count reveals no identity.
- **Alternatives considered:** Viewer-filtered counts (rejected: leaks block state + cost); counts that exclude shadow-banned followers (deferred — the shadow-ban view applies to the *profile* being read, not to aggregate edge counts; matching the existing like/reply counter convention which counts raw edges). Counting raw edges keeps this consistent with how `post-likes` / `post-replies` count.

### Decision 2 — Suspension + privacy state are SELF-ONLY
`suspendedUntil` and `isPrivate` are populated only when `isSelf == true`. For other users they are `null` (omitted from the rendered card).

- **Why:** Another user's suspension/ban window and private-profile flag are **private moderation/account state**. Surfacing `suspendedUntil` for an arbitrary target would leak admin moderation decisions to every viewer; surfacing another user's `isPrivate` is unnecessary for the profile card and risks coupling clients to a flag they shouldn't act on. The viewer's own suspension countdown drives the mobile "you are suspended until …" UI (`02-Product.md` § Suspension), and the viewer's own `isPrivate` is needed for the settings-toggle context.
- **`isPrivate` grace handling:** computed as `private_profile_opt_in OR (privacy_flip_scheduled_at IS NOT NULL AND now() < privacy_flip_scheduled_at)` — the app-layer short-circuit from `02-Product.md` § Privacy Downgrade Flow, so a user mid-72h-grace still reads as private.
- **Alternatives considered:** Expose suspension state for all (rejected: moderation leak); omit self-state entirely and add a separate `/api/v1/user/me` (rejected: doubles the round-trips for the own-profile screen, and the single endpoint already knows `isSelf`).

### Decision 3 — Private-profile content gating is OUT OF SCOPE
This endpoint returns the basic profile card (handle/display-name/bio/counts/follow-state) for **any** non-blocked, visible user, including private ones. It does not gate the card behind a follow relationship.

- **Why:** "Private profile" in this product means the *content* (posts, and per follow-system, the follow lists which are already viewer-filtered) is restricted — not that the existence/handle of the user is hidden. The profile card is the entry point from which a viewer requests to follow. Gating the *feed* lives where the feed is served. Keeping content-gating out of this change avoids scope creep and an ambiguous half-implementation. `isPrivate` is surfaced (self-only per Decision 2) as an informational flag; clients render the "private" affordance, but the read endpoint itself does not 403/empty a private target's card.
- **Alternatives considered:** Gate the whole card for private non-followers (rejected: would hide the follow entry-point and overlaps feed-gating scope not yet specified).

### Decision 4 — Block → 404 (leak-safe), not 403
A bidirectional block (viewer blocked target OR target blocked viewer) returns `404 user_not_found` with the **same constant body** as the unknown-UUID case — never 403, never a direction hint.

- **Why:** Identical to the existing follow-endpoint posture (`FollowRoutes.kt` `FOLLOW_BLOCKED_BODY`). A 403 or a distinct body would let a viewer distinguish "blocked" from "doesn't exist," leaking that a block relationship exists and (worse) which direction. 404 collapses both into one indistinguishable response. NOTE: the read deliberately uses 404 (not the follow action's 409 `follow_blocked`) because a 409 on a *read* would itself reveal that a block exists — the GET surface must be probe-proof in a way the write surface need not.
- **Implementation:** fold a bidirectional `user_blocks` exclusion into the other-user read query using the canonical directional-fragment form — `NOT EXISTS (SELECT 1 FROM user_blocks WHERE (blocker_id = :viewer AND blocked_id = :target) OR (blocker_id = :target AND blocked_id = :viewer))`. This carries the four tokens `BlockExclusionJoinRule` scans for (`user_blocks`, `blocker_id =`, `blocked_id =`) and matches the established `ChatRepository.isBlockedBidirectional` precedent. Do NOT use the tuple-IN form `(blocker_id, blocked_id) IN (...)` — it is semantically equivalent but lacks the literal `blocker_id =`/`blocked_id =` fragments the rule's text scan requires, so it would fail lint. When the row is absent for any reason (unknown / shadow-banned / soft-deleted / blocked-either-direction), the route maps to the one constant 404.

### Decision 5 — Own-profile reads use raw `users`; all other reads use `visible_users`
When `target == viewer`, the repository reads from raw `users` on an annotated own-content path (the shadow-ban invariant's documented Repository own-content exception). All other reads go through `visible_users`.

- **Why:** A shadow-banned user is, by design, unaware of their state and must still see their own profile normally; `visible_users` filters shadow-banned rows, so a self-read through it would 404 the viewer's own profile. The own-content exception exists precisely for this.
- **Two lint annotations on the self path** (`users` is a protected table for BOTH the shadow-ban rule and the block rule): the raw-`users` self read needs (a) `@AllowRawPostsRead("own-profile-read")` on the declaration (or placement under an own-content repository path/filename prefix) to satisfy `RawFromPostsRule`, AND (b) the `// @allow-no-block-exclusion: own-profile-no-self-block` source comment to suppress `BlockExclusionJoinRule` (a self read carries no block predicate — you cannot block yourself). The other-user `visible_users` read needs neither annotation: it is not raw, and it carries the bidirectional block fragments (Decision 4).
- **Alternatives considered:** Always read `visible_users` (rejected: breaks self-read for shadow-banned viewers); a single raw-`users` query for both self and other with `(is_shadow_banned = FALSE OR id = :viewer)` (rejected: hand-reimplements `visible_users` and forces raw-`users` reads on the non-own-content path, against the invariant's intent); a DB function (rejected: over-engineered for one read).

### Decision 6 — DTO shape + wire casing
`UserProfileResponse` uses camelCase keys (`userId`, `displayName`, `followerCount`, `followedByViewer`, `suspendedUntil`, `isPremium`, `isSelf`, `isPrivate`) to match the repo's mixed-case timeline-DTO wire convention. `suspendedUntil` is an ISO-8601 string (nullable). `bio` is nullable. `followedByViewer` is `false` whenever `isSelf` is true (you don't follow yourself).

## Risks / Trade-offs

- **Raw-count vs filtered-list inconsistency could confuse a client** (count says 10, list shows 8 because 2 are blocked) → Mitigation: document the asymmetry in the spec; it is the correct, leak-safe behavior and matches mainstream apps. The client treats the count as an aggregate, the list as the enumerable subset.
- **Own-content raw-`users` read is a lint-sensitive path** → Mitigation: annotate the exception exactly as the existing own-content reads do; the integration test "shadow-banned viewer sees own profile (200)" guards the behavior, and the lint allowlist annotation guards the rule.
- **Forgetting the bidirectional half of the block check** (only checking viewer→target) would leak the target's profile to someone the target blocked → Mitigation: explicit spec scenarios for BOTH directions (viewer-blocked-target AND target-blocked-viewer), both asserting the identical constant 404 body.
- **New DB-tagged `*RoutesTest` adds a HikariPool** → Mitigation: `autoClose(hikari())` + pool size 2 per the CI connection-budget rule (CI Postgres sits near `max_connections`; a leaked per-spec pool fails unrelated tests).
- **`subscription_status` has three values, `isPremium` collapses to a boolean** → Mitigation: `isPremium = (subscription_status == 'premium_active')`; `premium_billing_retry` reads as not-premium for badge purposes (matches the schema formula intent). Documented in the spec.

## Migration Plan

No DB migration. Deploy is additive (new route only):
1. Land repository + service + route + DTO + Koin wiring + `Application.kt` registration.
2. CI green (`ktlintCheck` + `detekt` + `:backend:ktor:test` + `:lint:detekt-rules:test`).
3. Pre-archive staging smoke: authenticated `GET /api/v1/users/{self}` → 200 self card; `GET /api/v1/users/{other}` → 200; `GET /api/v1/users/<random-uuid>` → 404; `GET /api/v1/users/not-a-uuid` → 400.
4. Rollback: revert the route registration commit — no data migration to unwind.

## Open Questions

None blocking. (Resolved during design: counts raw vs filtered → D1; self-only moderation state → D2; private gating scope → D3; block status code → D4; self-read view → D5.)
