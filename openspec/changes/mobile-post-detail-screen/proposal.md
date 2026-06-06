## Why

The Nearby and Global feeds (`mobile-nearby-timeline`, `mobile-global-timeline`) render post cards with `liked_by_viewer` + `reply_count` **read-only** — a visible dead-end: a user can browse and post but cannot like or reply, the heart of a social product. The backend like + reply endpoints have been shipped since V7/V8 (`post-likes`, `post-replies`) and sit unused by the mobile client. This change adds the post-detail surface that closes the core engagement loop (like + reply), and it is the dependency-upstream of the future in-app notifications list (notifications deep-link *into* post/reply detail per `docs/03-UX-Design.md:178`). It is the next mobile scaffolding pick under the balanced-mode priority (mobile remains the MVP-readiness gap; `docs/08-Roadmap-Risk.md:139` Phase 3 Shared (KMP) screens).

## What Changes

- **New `PostDetailScreen`** in `:mobile:app` — the "everything you do on a single post" surface, opened by tapping a feed card. Renders the tapped post's header (`content` + "Diposting dari {city_name}, {relative_time}" per `docs/03-UX-Design.md:14`), a **like control** (toggle + count), a **replies list** (read-only reply cards), and a **reply composer** (multiline + live `N/280` Unicode-code-point counter + "Balas" CTA). Loading / empty / error / rate-limit states, all copy via `:shared:resources`.
- **New payload-carrying `PostDetailRoute` NavKey** — the first non-parameterless route (current routes are all `data object`s). Pushed onto the **root** back stack (overlaying the tab bar), mirroring the post-composer FAB pattern and deliberately avoiding the per-tab `NavDisplay` back stacks that `mobile-home-tab-host` deferred. `@Serializable` + registered in the `navSavedStateConfiguration` polymorphic module (iOS-saveable). Carries only **non-PII display fields** (`postId`, `content`, `cityName`, `distanceM?`, `createdAtIso`, `likedByViewer`, `replyCount`) — **never** `latitude`/`longitude` (PII must not enter the serialized back stack, same discipline `AgeGateRoute` applies to the `id_token`).
- **Feed cards become tappable** — the Nearby + Global post cards gain a hoisted `onOpenPost(...)` lambda (a host-level callback, NOT a back-stack reference), wired by the tab host to the root-stack push. The timeline screens stay **navigation-free**, exactly as the existing hoisted `onSeeGlobal` callback already permits.
- **Like toggle** — initial state from the card's `likedByViewer` (nav arg); optimistic flip, revert on failure; `POST/DELETE /api/v1/posts/{post_id}/like` (204); numeric count via `GET /api/v1/posts/{post_id}/likes/count`. `429` surfaces the Free like-cap upsell (`docs/03-UX-Design.md:205`).
- **Reply list + composer** — `GET /api/v1/posts/{post_id}/replies` (read); `POST /api/v1/posts/{post_id}/replies` (write, 280-cp guard). On 201 the new reply is appended locally + the count bumped (no full re-fetch). `429` surfaces the reply-cap upsell. Reply cards render content + timestamp only — **no author identity** (the wire carries only `author_id`; consistent with the PII-first post cards that already render no author).
- **DTOs mirror the SHIPPED wire, not stale spec JSON** — `ReplyDto`/`ReplyListResponse` are **snake_case** (`post_id`, `author_id`, `is_auto_hidden`, `created_at`, and critically `next_cursor`), which **differs** from the timeline endpoints' camelCase `nextCursor`. A negative-guard test asserts a camelCase `nextCursor` body does NOT populate the field. (Precedent: the PR #128 timeline casing-drift trap.)
- **No new Flyway migration** — pure consumption of the shipped V7 `post_likes` + V8 `post_replies` endpoints.
- **Explicit deferrals** (captured as spec requirements with negative guards + `FOLLOW_UPS.md` entries): block/report kebab actions; inline-card like/reply shortcuts; replies infinite-scroll (cursor parsed, not consumed); a `GET /api/v1/posts/{id}` by-id fetch (deep-link dependency owned by the future notifications change).

## Capabilities

### New Capabilities
- `mobile-post-detail`: the `:mobile:app` post-detail surface — `PostDetailScreen` + `PostDetailRoute`, the like toggle (with count + cap upsell), the replies list + reply composer (shipped snake_case wire, 280-cp guard, cap upsell), the status-driven repository behind a `PostDetailFlow` seam, the pure UI-state projection, Koin wiring, the new Bahasa Indonesia strings, the test trio (Robolectric + commonTest + iosTest), and the explicit deferral requirements.

### Modified Capabilities
- `mobile-nearby-timeline`: the Nearby post card becomes tappable via a hoisted `onOpenPost(...)` lambda (screen remains navigation-free; mirrors the existing `onSeeGlobal` hoisted-lambda pattern).
- `mobile-global-timeline`: the Global post card becomes tappable via the same hoisted `onOpenPost(...)` lambda.
- `mobile-home-tab-host`: the tab host passes `onOpenPost` into the Nearby + Global tab content and appends `PostDetailRoute` to the **root** back stack when invoked (a new host navigation behavior parallel to the existing composer-FAB root push).

## Impact

- **Module**: `:mobile:app` only (`screens/post/PostDetailScreen.kt`, `post/` ApiClient + repository + DTOs, `di/MobileModule.kt`, `screens/routing/NavKeys.kt` + entryProvider + polymorphic serializers, `screens/home/HomeScreen.kt` tab-host wiring, `screens/timeline/NearbyTimelineScreen.kt` + `GlobalTimelineScreen.kt` card-tap lambda). `:shared:resources` gains ~10 strings (`SharedStringsCatalogTest` count bumped).
- **Backend**: none — consumes shipped `post-likes` (V7) + `post-replies` (V8) endpoints. No migration, no new endpoint.
- **Wire contract source of truth**: `backend/ktor/.../engagement/LikeRoutes.kt` + `ReplyRoutes.kt` (canonical shipped casing), NOT any stale spec JSON example.
- **Auth**: Bearer + 401 refresh owned by the shipped `HttpClient` `Auth` plugin (not reimplemented). Like/reply endpoints are not per-session soft-capped → no `X-Session-Id` header (unlike the timelines).
- **PII**: no `author_id`, no coordinates rendered/logged; `HttpClientFactory` stays at `LogLevel.HEADERS`.
- **`FOLLOW_UPS.md`**: the file is already over its 30-entry hard cap (32 open as of 2026-06-06; a `/triage-follow-ups` sweep is flagged OVERDUE). To avoid deepening the breach this change adds **3** new entries (block-report-kebab, inline-card-actions, backend-single-post-get-endpoint) and **amends the existing `mobile-nearby-timeline-infinite-scroll` entry** to cover replies load-more (rather than opening a 4th). A `/triage-follow-ups` sweep SHOULD run before/at `/opsx:apply` (tasks 8.0) to draw the count back under cap.
