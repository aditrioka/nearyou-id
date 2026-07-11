# Proposal: post-detail-tap-to-profile

## Why

The post-detail header renders the author identity row (avatar + display name + @handle) but it is not a tap target — there is no in-context navigation from a post to its author's profile, a visible dead-end on the app's most-used drill-down surface (GitHub issue [#455](https://github.com/aditrioka/nearyou-id/issues/455)). The original blocker — `PostDetailRoute` carries no author UUID (the serialized-back-stack PII discipline) — was dissolved by `mobile-block-from-content`: the single-post freshness read now resolves `SinglePostResponse.authorUserId` on every resume, and reply rows carry `author_id` + display identity on the wire. The `mobile-profile` spec explicitly tracked this as deferral (c) of its "Edit-profile, suspension countdown, and post-detail identity tap are deferred" requirement; this change lifts it.

## What Changes

- The post-detail **header identity row** becomes a tap target that pushes `ProfileRoute(authorUserId)` onto the root back stack — the same profile-entry mechanism the feed-card identity tap uses (the `mobile-profile` spec's established entry convention; mockup board frame 7).
- `authorUserId` is sourced from the **single-post freshness read** (`SinglePostResponse.authorUserId`) — NOT from the route payload; `PostDetailRoute` stays UUID-free (the existing serialization discipline is untouched).
- **Graceful absence**: when the freshness read degraded (`Unavailable` → no `authorUserId`), the identity row is not tappable — mirroring the Edit/Block affordances' dependence on the same read. Same for an empty payload identity (no row at all, unchanged).
- The same affordance on **reply-row identity rows**: tapping a reply's identity pushes `ProfileRoute(reply.authorId)` (the reply wire already carries `author_id` + identity per `mobile-block-from-content` D7); absent when the wire identity is absent (older-backend body → no identity row, unchanged).
- The screen stays navigation-free: a hoisted `onOpenProfile: (userId: String) -> Unit` lambda, wired by `AppEntryProvider` to `backStack.add(ProfileRoute(userId))`.
- PII discipline preserved: the UUID is used solely as the navigation argument (already the timeline-wire norm — `ProfileRoute` docs); it is never rendered or logged.

## Capabilities

### New Capabilities

None — this is a navigation affordance on existing surfaces, consuming already-shipped reads.

### Modified Capabilities

- `mobile-post-detail`: the header requirement's "The identity is NOT a tap target" clause is replaced by tap-to-profile behavior; two requirements are ADDED (header identity tap, reply-row identity tap) with graceful-absence + PII scenarios; the test-coverage expectation extends to the new affordance.
- `mobile-profile`: the "Edit-profile, suspension countdown, and post-detail identity tap are deferred" requirement is MODIFIED to lift deferral (c) — the post-detail author-identity tap now ships; deferrals (a) edit-profile and (b) suspension countdown stay.

## Impact

- **Code**: `mobile/app/src/commonMain/.../screens/post/PostDetailScreen.kt` (header + reply-card clickable identity, new hoisted lambda), `screens/routing/AppEntryProvider.kt` (wire `onOpenProfile`). No new routes, no DI changes, no backend/admin work (the vertical slice is mobile-only by construction — `ProfileRoute`/`ProfileScreen` and the freshness read are already shipped; docs/12 cohesion is satisfied).
- **Tests**: Robolectric `PostDetailScreenTest` additions (tap fires with resolved id; not tappable when degraded; reply identity tap) + no PII regression.
- **Specs**: delta files for `mobile-post-detail` and `mobile-profile`.
- **Issue**: closes [#455](https://github.com/aditrioka/nearyou-id/issues/455).
