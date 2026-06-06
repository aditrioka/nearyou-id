## Context

`:mobile:app` has shipped the read-only feed surfaces (`mobile-nearby-timeline`, `mobile-global-timeline`) hosted by the `mobile-home-tab-host` tab host, plus the write half of the loop (`mobile-post-creation`). The feed cards display `liked_by_viewer` + `reply_count` but expose no interaction — a user can read and post but cannot like or reply. The backend like + reply endpoints shipped at V7 (`post-likes`) / V8 (`post-replies`) and are unused by mobile. This change adds the post-detail surface that consumes them, closing the engagement loop.

Established mobile patterns this change mirrors (do not re-invent):
- **Screen + ApiClient + Repository-behind-a-`*Flow`-interface + pure Compose-free UI-state projection + Koin singletons** (`mobile-post-creation`, `mobile-nearby-timeline`).
- **Status + `error.code`-driven sealed outcome with no generic fallthrough** (`mobile-post-creation`'s `PostCreationOutcome`).
- **Root-back-stack overlay navigation** (the composer FAB pushes `PostCreationRoute` onto the root stack above `HomeRoute`).
- **Hoisted navigation lambdas keep feed screens navigation-free** (`onSeeGlobal` in `mobile-nearby-timeline`).
- **DTOs generated from the SHIPPED wire, not stale spec JSON** (the PR #128 timeline casing-drift lesson).
- **All UI strings via `:shared:resources` `Res.string.*`**; `SharedStringsCatalogTest` count assertion.
- **Test trio**: Robolectric `*ScreenTest` (Release-excluded), commonTest projection, iosTest flow.

Constraints discovered while scoping:
- **No `GET /api/v1/posts/{id}` single-post endpoint** exists (only `POST /api/v1/posts` in the post package; like/reply endpoints are post-scoped sub-resources). A detail screen cannot re-fetch a post by id.
- **`ReplyDto` / `ReplyListResponse` are snake_case** (`post_id`, `author_id`, `is_auto_hidden`, `created_at`, `next_cursor`) — `next_cursor` differs from the timelines' camelCase `nextCursor`.
- **`ReplyDto` carries only `author_id` (UUID)** — no username; the PII-first post cards already render no author identity, so reply cards follow suit.

## Goals / Non-Goals

**Goals:**
- A tappable feed card → `PostDetailScreen` showing the post header, like toggle (+ count), replies list, and reply composer.
- Consume the shipped like + reply endpoints with status/error.code-driven outcomes.
- Preserve PII discipline (no `author_id` / coordinates rendered, logged, or serialized into the back stack) and the navigation-free feed-screen property.
- Full test parity (Robolectric + commonTest + iosTest) with the existing mobile surfaces.

**Non-Goals:**
- Block / report kebab actions (separate feature: `user_blocks` + `reports` backends, confirmation modal, reason picker) — deferred.
- Inline like/reply shortcuts on the feed cards themselves — v1 routes all engagement through the detail screen — deferred.
- Replies infinite scroll (cursor parsed, not consumed) — deferred.
- A backend `GET /api/v1/posts/{id}` by-id fetch (the future notifications-list change owns deep-linking) — deferred.
- Per-tab `NavDisplay` back stacks (`mobile-home-tab-host` deferral) — this change uses the root back stack instead.
- Relative-timestamp formatting — stays with the existing `mobile-timeline-relative-timestamp` follow-up; the detail reuses the card's current `created_at` treatment.
- Premium post-edit history ("Diedit" / "Riwayat edit") — Phase 4.

## Decisions

### D1 — Navigate via the ROOT back stack with a payload-carrying `PostDetailRoute`
Tapping a card pushes `PostDetailRoute` onto the **root** back stack (above `HomeRoute`), overlaying the tab bar — the same mechanism the composer FAB uses. **Alternative considered:** per-tab `NavDisplay` back stacks so detail stacks within its origin tab. Rejected for v1 because `mobile-home-tab-host` explicitly deferred per-tab back stacks (`FOLLOW_UPS mobile-home-tab-host-per-tab-backstacks`) and a root-overlay detail is the standard pattern (and what the composer already does); building the per-tab infrastructure now would be vestigial. `PostDetailRoute` is the **first payload-carrying `NavKey`** (existing routes are parameterless `data object`s), so it MUST be `@Serializable` **and** registered in the `navSavedStateConfiguration` polymorphic `SerializersModule` (the iOS-saveable back stack requirement from `mobile-app-scaffold`).

### D2 — Render the header from nav args, not a re-fetch (no `GET /posts/{id}`)
Because no single-post GET endpoint exists, `PostDetailRoute` carries the display fields needed to render the post header, captured from the tapped card. **Alternatives considered:** (a) add a backend `GET /api/v1/posts/{id}` — rejected: a backend change balloons scope beyond a mobile scaffold and the by-id fetch is only truly needed for notification deep-linking (a later change); (b) carry only `postId` and show a header-less reply view — rejected: you must show the post you're replying to. The by-id endpoint is deferred to `FOLLOW_UPS backend-single-post-get-endpoint`, owned by the notifications change.

### D3 — PII boundary on the route payload
`PostDetailRoute` carries `postId`, `content`, `cityName`, `distanceM?` (Nearby-only; null from Global), `createdAtIso`, `likedByViewer`, `replyCount` — and **MUST NOT** carry `latitude`/`longitude`. The serialized back stack persists to disk on iOS, so raw coordinates must never enter it (the same discipline `AgeGateRoute` applies to the `id_token`). `content` is public post text, safe to serialize. Rendering keeps the existing card discipline: no `author_id`, no raw coordinates.

### D4 — DTOs mirror the SHIPPED snake_case reply wire; explicit negative guard
`ReplyDto` (`id`, `@SerialName("post_id")`, `@SerialName("author_id")`, `content`, `@SerialName("is_auto_hidden")`, `@SerialName("created_at")`, `@SerialName("updated_at")`, `@SerialName("deleted_at")`) and `ReplyListResponse` (`replies`, `@SerialName("next_cursor")`) are generated from `backend/ktor/.../engagement/ReplyRoutes.kt`, NOT from any spec JSON example. The `next_cursor` snake_case is the trap: it differs from the timelines' camelCase `nextCursor`. A commonTest fixture asserts a camelCase `nextCursor` body does NOT populate the field and the snake_case `next_cursor` does. `LikesCountResponse` is `{ "count": <Long> }`.

### D5 — Like state from nav arg + optimistic toggle; count via `GET /likes/count`
There is no "is-liked" endpoint. Initial like state comes from the card's `likedByViewer` nav arg; the toggle flips optimistically and reverts on a non-204 / network failure. `POST /like` and `DELETE /like` both return 204 (DELETE is a pure no-op, never 404). The numeric count is fetched via `GET /api/v1/posts/{post_id}/likes/count` ("{n} suka"); a count-fetch failure degrades gracefully (hide the count, keep the toggle). A `429` on like surfaces the Free like-cap upsell copy.

### D6 — One `PostDetailRepository` behind a single `PostDetailFlow` seam
A single repository exposes `loadReplies()`, `toggleLike(currentlyLiked)`, `postReply(content)`, and `likeCount()`, mapping each to a sealed per-operation outcome. **Alternative considered:** three separate `*Flow` interfaces (like/reply/replies). Rejected: one cohesive surface → one fake (`FakePostDetailFlow`) drives all screen-test paths, matching the one-repo-per-screen precedent. The ApiClient(s) MAY be split internally (`LikeApiClient`, `ReplyApiClient`) but are wired as Koin singletons reusing the shared `HttpClient` (no new client, no `X-Session-Id` — these endpoints are not session-soft-capped).

### D7 — Reply cards render content + timestamp only (no author identity)
The shipped `ReplyDto` exposes only `author_id` (UUID), which is PII and never rendered (consistent with the existing post cards). Reply cards therefore show `content` + the `created_at` treatment used by the feed cards. This is a deliberate, wire-grounded constraint, not an omission; a future reply-author-identity enrichment would be a backend DTO change out of scope here.

### D8 — Detail-only engagement for v1
All like/reply interaction lives on `PostDetailScreen`. Inline-card like/reply shortcuts are deferred (`FOLLOW_UPS mobile-post-detail-inline-card-actions`) so v1 ships one cohesive surface.

### D9 — Reply 201 appends locally; no list re-fetch; infinite scroll deferred
On a successful reply POST the returned `ReplyDto` is appended to the in-memory list and the displayed reply count is incremented — the list is NOT re-fetched. `next_cursor` is parsed + retained on the `Loaded` outcome but load-more is not wired; this deferral **amends the existing `mobile-nearby-timeline-infinite-scroll` FOLLOW_UP** (the entry the Global feed already extended) rather than opening a new one, to avoid deepening the FOLLOW_UPS 30-entry cap breach. The reply 400 path maps to a single `InvalidContent` outcome — the shipped backend emits one `invalid_request` code for both empty and over-limit content, so empty-vs-too-long is gated client-side (the pre-submit code-point projection disables the CTA), not derived from the server response.

## Risks / Trade-offs

- **[Stale header values]** The header `replyCount` / `likedByViewer` come from when the card was tapped; another device could change them meanwhile. → The screen manages live deltas locally (toggle flips the like; a successful reply bumps the count). Absolute accuracy is not promised for a read snapshot; this matches the no-by-id-fetch reality and is acceptable for MVP.
- **[Optimistic like divergence]** An optimistic flip that the server later rejects (429/404) must revert. → Outcome mapping reverts the local state on any non-204; the like control is idempotent (re-tap re-issues).
- **[Casing-drift regression]** A future contributor "normalizes" the reply DTO to camelCase and silently breaks parsing. → The negative-guard commonTest fixture (camelCase `nextCursor` must NOT populate) fails CI if that happens.
- **[Back-stack PII leak]** Carrying the post content + counts on a serialized route risks someone adding coordinates later. → The spec requirement forbids `latitude`/`longitude` on `PostDetailRoute`; an iOS saved-state round-trip test exercises the route serializer.
- **[Scope size]** Detail + replies-read + reply-write + like-toggle is larger than a single menu item. → It is one cohesive surface (everything you do on one post); each sub-feature reuses a proven pattern. If review finds it too large, the reply composer (write half) is the natural split line — but a half-interactive detail screen reproduces the same dead-end, so the engagement-complete scope is preferred.

## Migration Plan

No schema migration (pure consumption of shipped endpoints). No new library pin (`gradle/libs.versions.toml` untouched) → the pre-implementation library re-check gate does not apply. Mobile-only change with no backend/deploy impact → the pre-archive staging smoke step is N/A (mark Section 6 N/A in the archive commit). Rollback is a straight revert of the squash-merged commit.

## Open Questions

- **Like count display** — show the numeric count ("{n} suka") via `GET /likes/count`, or show only the binary toggle state? Leaning toward showing the count (the endpoint exists for exactly this and a detail screen benefits from it), with graceful degradation if the count fetch fails. Resolved in the spec as "count shown when available; toggle always present."
