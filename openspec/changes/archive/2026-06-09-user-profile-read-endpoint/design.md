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

### Decision 2 — Privacy state is SELF-ONLY; suspension state is NOT carried by this endpoint
`isPrivate` is populated only when `isSelf == true`. For other users it is `null` (omitted from the rendered card). The response carries **no `suspendedUntil`** field at all.

**Regression fix (reconciliation with `docs/02-Product.md` § Suspension).** An earlier revision of this proposal made `suspendedUntil` a self-only field to drive the mobile "you are suspended until …" countdown. Implementation revealed that contradicts the platform's suspension model: a 7-day suspension (`docs/02-Product.md` § Suspension) sets `is_banned = TRUE`, sets `suspended_until`, and **increments `token_version`** (kicks the session). `AuthPlugin.configureUserJwt` therefore rejects a suspended principal's JWT with `403` (`account_banned` / `account_suspended`) on every `AUTH_PROVIDER_USER` route — so a suspended user can **never** reach `GET /api/v1/users/{self}`. The unban worker also nulls `suspended_until` once the window elapses, so the field could never be non-null for any user who *can* authenticate. The field was structurally undeliverable, not merely pending a worker.

- **Established-pattern resolution.** Per the standard API-design pattern (account-suspension state is surfaced at the **auth boundary** via a structured `401`/`403` error body carrying the reason + expiry, NOT on a protected resource read — see [Auth0](https://auth0.com/blog/forbidden-unauthorized-http-status-codes/), [Logto](https://blog.logto.io/401-vs-403), [API Handyman](http://apihandyman.io/hands-off-that-resource-http-status-code-401-vs-403-vs-404/)), the suspension countdown belongs on the auth-rejection / token-refresh response. `suspendedUntil` is dropped from this endpoint; enriching the `account_suspended` 403 (and the login/refresh flow) with `suspended_until` so the mobile "suspended until …" screen has a real source is tracked as a separate `follow-up`-labelled issue ([#208](https://github.com/aditrioka/nearyou-id/issues/208) — a new auth-error capability, out of this read endpoint's scope).
- **`isPrivate` stays self-only.** Another user's private-profile flag is unnecessary for the profile card and risks coupling clients to a flag they shouldn't act on; the viewer's own `isPrivate` is needed for the settings-toggle context.
- **`isPrivate` = canonical "effective private"** (`docs/05-Implementation.md` § Effective private), NOT a bare `private_profile_opt_in`: `(private_profile_opt_in AND subscription_status IN ('premium_active','premium_billing_retry')) OR (privacy_flip_scheduled_at IS NOT NULL AND now() < privacy_flip_scheduled_at)`. The premium-status conjunct is load-bearing — private profile is a Premium-only feature, so a Free user with a stale `private_profile_opt_in = TRUE` reads `isPrivate = false`. The second term is the 72h privacy-flip grace short-circuit; it is forward-looking plumbing (the `/internal/privacy-flip-worker` that populates `privacy_flip_scheduled_at` is DESIGN-status, so the column is null in practice today), included for forward-compatibility. The same `subscription_status NOT IN ('premium_active','premium_billing_retry')` shape is used by the live timeline query (`docs/05-Implementation.md:843`).
- **Alternatives considered:** Keep `suspendedUntil` as forward-looking plumbing like the privacy-flip grace term (rejected: the privacy term is inert *pending a DESIGN-status worker* but reachable once it ships; the suspension field is *structurally unreachable* under the session-kick model and would mislead clients into reading the countdown from the wrong surface). Change auth to allow a suspended user to `GET` while blocking writes (rejected: contradicts the product model that terminates the session, and an auth-layer change is out of this change's scope). Expose suspension state for all (rejected: moderation leak, and moot once the field is dropped).

### Decision 3 — Private-profile content gating is OUT OF SCOPE
This endpoint returns the basic profile card (handle/display-name/bio/counts/follow-state) for **any** non-blocked, visible user, including private ones. It does not gate the card behind a follow relationship.

- **Why:** "Private profile" in this product means the *content* (posts, and per follow-system, the follow lists which are already viewer-filtered) is restricted — not that the existence/handle of the user is hidden. The profile card is the entry point from which a viewer requests to follow. Gating the *feed* lives where the feed is served. Keeping content-gating out of this change avoids scope creep and an ambiguous half-implementation. `isPrivate` is surfaced (self-only per Decision 2) as an informational flag; clients render the "private" affordance, but the read endpoint itself does not 403/empty a private target's card.
- **Alternatives considered:** Gate the whole card for private non-followers (rejected: would hide the follow entry-point and overlaps feed-gating scope not yet specified).

### Decision 4 — Block → 404 (leak-safe), not 403
A bidirectional block (viewer blocked target OR target blocked viewer) returns `404 user_not_found` with the **same constant body** as the unknown-UUID case — never 403, never a direction hint.

- **Why:** Identical to the existing follow-endpoint posture (`FollowRoutes.kt` `FOLLOW_BLOCKED_BODY`). A 403 or a distinct body would let a viewer distinguish "blocked" from "doesn't exist," leaking that a block relationship exists and (worse) which direction. 404 collapses both into one indistinguishable response. NOTE: the read deliberately uses 404 (not the follow action's 409 `follow_blocked`) because a 409 on a *read* would itself reveal that a block exists — the GET surface must be probe-proof in a way the write surface need not.
- **Implementation:** fold a bidirectional `user_blocks` exclusion into the other-user read query using the canonical directional-fragment form — `NOT EXISTS (SELECT 1 FROM user_blocks WHERE (blocker_id = :viewer AND blocked_id = :target) OR (blocker_id = :target AND blocked_id = :viewer))` — matching the established `ChatRepository.isBlockedBidirectional` precedent. When the row is absent for any reason (unknown / shadow-banned / soft-deleted / blocked-either-direction), the route maps to the one constant 404.
- **This predicate is a CORRECTNESS guardrail, not a lint guardrail.** The other-user read is `FROM visible_users`, and `BlockExclusionJoinRule`'s `PROTECTED_TABLE_PATTERN` (`\b(?:FROM|JOIN)\s+(?:posts|visible_posts|users|chat_messages|post_replies)\b`) does NOT match `visible_users` (the `visible_` prefix breaks the `\busers\b` boundary). So the linter will NOT force the block predicate here — the explicit both-direction 404 test scenarios are the real guardrail (a future refactor that drops the predicate would pass lint but fail those tests). The canonical directional-fragment form is used for readability/consistency with `ChatRepository`, not because lint requires the tokens.

### Decision 5 — Own-profile reads use raw `users`; all other reads use `visible_users`
When `target == viewer`, the repository reads from raw `users` on an annotated own-content path (the shadow-ban invariant's documented Repository own-content exception). All other reads go through `visible_users`.

- **Why:** A shadow-banned user is, by design, unaware of their state and must still see their own profile normally; `visible_users` filters shadow-banned rows, so a self-read through it would 404 the viewer's own profile. The own-content exception exists precisely for this. (Reading raw `users` for self is also the project.md shadow-ban convention's documented "Repository own-content path" carve-out.)
- **ONE lint annotation on the self path — and it is the block rule, not the shadow-ban rule.** Verified against the rule sources:
  - `RawFromPostsRule`'s pattern is `\b(?:FROM|JOIN)\s+posts\b` — it matches `posts` ONLY, never `users`. So a raw `FROM users` read does NOT trip `RawFromPostsRule`, and `@AllowRawPostsRead` is inert here (do not add it). "Use `visible_users` not raw `users`" is a project.md *convention*, enforced for the non-own-content path indirectly via the block rule below, not by a dedicated shadow-ban Detekt rule on `users`.
  - `BlockExclusionJoinRule`'s `PROTECTED_TABLE_PATTERN` DOES include `users`, so the raw `FROM users` self read trips it. Suppress it with the **`@AllowMissingBlockJoin("own-profile-read: …")` annotation** on the reader declaration (the real, registered mechanism — `ALLOW_ANNOTATION_SHORT = "AllowMissingBlockJoin"`; precedent: `JdbcActorUsernameLookup`). Do NOT use a `// @allow-no-block-exclusion: own-profile-…` comment — `RECOGNIZED_COMMENT_MARKERS` contains exactly one registered token (`@allow-no-block-exclusion: chat-history-readable-after-block`); any other comment token is silently ignored and the build fails. (A `UserOwn*`-prefixed class/file name is an equivalent alternative via `OWN_CONTENT_PREFIXES`, but the explicit annotation is clearer.)
  - The other-user `visible_users` read needs NO annotation: `visible_users` matches neither rule's pattern (see Decision 4).
- **Alternatives considered:** Always read `visible_users` (rejected: breaks self-read for shadow-banned viewers); a single raw-`users` query for both self and other with `(is_shadow_banned = FALSE OR id = :viewer)` (rejected: hand-reimplements `visible_users` and reads raw `users` on the non-own-content path, against the convention's intent); a DB function (rejected: over-engineered for one read).

### Decision 6 — DTO shape + wire casing
`UserProfileResponse` uses camelCase keys (`userId`, `username`, `displayName`, `bio`, `followerCount`, `followingCount`, `isSelf`, `followedByViewer`, `isPremium`, `isPrivate`) to match the repo's mixed-case timeline-DTO wire convention. There is no `suspendedUntil` key (Decision 2). `bio` and `isPrivate` are nullable. `followedByViewer` is `false` whenever `isSelf` is true (you don't follow yourself).

**Null fields are OMITTED from the wire, not emitted as `null`.** The app-wide `ContentNegotiation` is configured `Json { explicitNulls = false }` (`Application.kt`), so a null-valued nullable property is dropped from the serialized JSON rather than rendered as `"key": null`. Concretely: `bio` is absent when the user has no bio, and `isPrivate` is absent on every other-user read. A consuming client DTO MUST declare these fields nullable-with-default (`val isPrivate: Boolean? = null`) so an absent key parses cleanly — matching the established repo convention (timeline / follow / search DTOs all rely on `explicitNulls = false` key omission; `PostRoutes` / `ChatDtos` use manual `buildJsonObject` only where a present-null is specifically required). The spec's JSON example shows the logical shape; the wire omits null keys.

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
