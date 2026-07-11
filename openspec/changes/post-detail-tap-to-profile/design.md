# Design: post-detail-tap-to-profile

## Context

`PostDetailScreen` already holds everything the affordance needs: the header identity row (avatar + display name + @handle) renders from the route payload; `authorUserId` is resolved on every resume by the single-post freshness read (`mobile-block-from-content` D1) into composition-local state; each `ReplyUi` carries `authorId` (wire `author_id`, used today only for the self-block gate and the block path param). `ProfileRoute(userId)` + `ProfileScreen` are shipped, and `AppEntryProvider` already wires two `onOpenProfile` hosts (Home feed cards, follow lists). The only gap is the tap wiring.

## Goals / Non-Goals

**Goals:**
- Header identity row → `ProfileRoute(authorUserId)` when (and only when) the freshness read resolved an id.
- Reply identity rows → `ProfileRoute(reply.authorId)` whenever the identity row renders.
- Zero route/DI/backend changes; PII disciplines untouched.

**Non-Goals:**
- No `PostDetailRoute` payload change (the UUID stays out of the serialized back stack).
- No ripple/visual redesign of the identity rows (tap affordance only; the mockup frame-7 identity treatment is unchanged).
- No profile-entry from the like row, city line, or content.

## Decisions

- **D1 — One hoisted lambda, id passed at the call site.** `PostDetailScreen` gains `onOpenProfile: (userId: String) -> Unit = {}` (the exact shape `HomeScreen`/`FollowListScreen` use); `AppEntryProvider`'s `PostDetailRoute` entry wires it to `backStack.add(ProfileRoute(userId))`. The screen stays navigation-free (existing requirement). Alternative — separate `onOpenAuthorProfile`/`onOpenReplyAuthorProfile` lambdas — rejected: same destination, no behavioral difference, double the wiring.
- **D2 — Header gating via nullable callback, the established idiom.** `PostHeader` receives `onOpenProfile: (() -> Unit)?` built by the caller as `authorUserId?.let { id -> { onOpenProfile(id) } }` — null while the read is unresolved/degraded → no `clickable` modifier at all (not a disabled one), mirroring `onBlockPost`'s construction. Keeps the pure-payload `PostHeader` signature free of the UUID.
- **D3 — Reply rows always tappable when the identity row renders.** The identity row only renders with a wire identity, and `authorId` is non-null on the same wire body — so no extra gate is needed. Own replies navigate too (self profile renders fine); filtering self out would add a gate with no user benefit and diverge from the feed-card behavior.
- **D4 — Tap target = the identity Row, with test tags.** New constants `POST_DETAIL_HEADER_PROFILE_TAG` / `POST_DETAIL_REPLY_PROFILE_TAG`; the `clickable` sits on the identity `Row` (avatar + names), NOT the whole card/header (content taps must not navigate, and the reply kebab keeps its own target).

## Risks / Trade-offs

- [Tap dead until the freshness read lands] → Accepted; identical to the Edit/Block affordances' dependence (typically <1s), and the degraded state renders exactly today's UI — no broken-looking control.
- [Reply-row tap vs kebab mis-taps] → The clickable spans only the identity Row (leading edge); the kebab is a separate trailing `IconButton` with its own touch target.

## Migration Plan

Pure client addition; ships in one PR. Rollback = revert.

## Open Questions

None.
