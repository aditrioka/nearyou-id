## Context

The shipped `mobile-notifications-list` renders the in-app Notifikasi feed with mark-read/mark-all-read, pull-to-refresh, and cursor load-more, but tapping a row does **only** mark-read — deep-link navigation was deferred behind a negative-guard requirement because the destination screens and a by-id post endpoint did not exist. Both blockers shipped: `mobile-post-detail-screen` (the `PostDetailRoute` surface) and `single-post-read` (`GET /api/v1/posts/{post_id}`, whose spec explicitly cites notification deep-links as its raison d'être). This change flips the guard.

Verified current state (in-repo):
- `NotificationsScreen` is **navigation-free** and embedded by `AppShellScreen` — invoked **bare** as `NotificationsScreen()` (no callbacks). The shell already hoists `onOpenPost: (PostDetailTarget) -> Unit`, `onOpenProfile: (authorUserId: String) -> Unit`, `onOpenChat: () -> Unit`, wired by `appEntryProvider` to root-stack pushes (the `mobile-post-detail` / `mobile-profile` / `mobile-chat` seams).
- NavKeys exist and are unchanged: `PostDetailRoute` (full payload), `ProfileRoute(userId)`, `ChatThreadRoute(conversationId, partnerUsername = "", partnerDisplayName = "")`.
- `PostDetailTarget` (the host→shell DTO mapped to `PostDetailRoute`) has fields identical to the `single-post-read` projection **except** `distanceM` (the by-id projection omits coordinates).
- `SinglePostApiClient` exists but decodes only a **minimal** projection (`SinglePostDto = content, editedAt, isAuthor`) for post-detail's freshness fetch — it does not decode the author/city/counts a `PostDetailTarget` needs.
- Canonical notification addressing (`docs/05-Implementation.md` § Notifications): the outer `(target_type, target_id)` pair is what deep-links route to; `body_data` carries only what that pair can't (e.g. `conversation_id` for `chat_message`). `followed` has `target_type = NULL` and routes via the actor.

## Goals / Non-Goals

**Goals:**
- Tapping a notification row navigates to its target, alongside the unchanged optimistic mark-read.
- Route off the canonical `(target_type, target_id)` + actor model: `post` → post detail (via a by-id fetch), `followed` → actor profile, `chat_message` → chat thread (with the partner top-bar identity resolved via the actor).
- A post that no longer resolves degrades to a non-blocking "tidak tersedia" affordance — never an error screen; the notifications list stays usable.
- Reuse every existing seam (NavKeys, shell hoisted callbacks, `SinglePostApiClient`, and the shipped `ProfileApiClient`/`user-profile-read` for the chat partner identity); add only the full-projection single-post read.
- Preserve the no-PII discipline: actor/target/conversation UUIDs are route payload / path params only — never rendered or logged.

**Non-Goals:**
- Reply deep-linking (`target_type='reply'`, the dynamic reply case of `post_auto_hidden`) — no reply-by-id → parent-post endpoint exists to build a `PostDetailRoute`; non-navigating this change, deferred to a follow-up.
- `chat_message_redacted` deep-linking — that type carries `actor_user_id = NULL`, so the partner top-bar identity cannot be resolved (see D3); non-navigating this change, deferred alongside the reply-target case.
- Navigation for the no-target informational types (`subscription_*`, `account_action_applied`, `data_export_ready`, `privacy_flip_warning`, `username_release_scheduled`, `apple_relay_email_changed`) — they have no in-app actionable destination; mark-read only (unchanged).
- Actor-username rendering / live unread-badge updates (separate deferred follow-ups; this change does not touch them).
- Any backend, DB/Flyway, or `gradle/libs.versions.toml` change.

## Standards conformance (docs/11 Pattern Registry)

This change builds on three already-registered patterns and introduces **no** new pattern (so no `docs/11 § Pattern Registry` amendment):
- **§ 2.2 State management** — the per-section `NotificationsViewModel` + Compose-free `NotificationsUiState` projection. Navigation target-resolution + the post fetch live in the VM; the screen stays a thin renderer. The resolved nav target is a **nullable, consumed-once field on the `StateFlow` UiState** (e.g. `pendingNavTarget: NotificationNavTarget?`), consumed + cleared by a VM `onNavConsumed()` callback after the screen forwards it to the hoisted callback — mirroring the codebase's established one-shot-signal pattern (`EditPostUiState.editedContent` / `showPremiumUpsell`). **NO `Channel`/`SharedFlow` event bus** — that is explicitly forbidden by docs/11 § 2.2 and absent from all 17 mobile VMs; the consume-and-clear is what prevents re-fire on recomposition / config change.
- **§ 2.3 Navigation 3** — the hoisted-callback → root-stack-push pattern. `NotificationsScreen` gains `onOpenPost`/`onOpenProfile`/`onOpenChatThread` callbacks the shell already owns and wires; **no new NavKeys**.
- **§ 2.6 Data layer (mobile)** — the `ApiClient → Repository → sealed-Outcome` pattern. The full-projection post fetch extends the existing `SinglePostApiClient` and mirrors its `SinglePostApiResult` (`Success` / `Unavailable`) discipline (`CancellationException` rethrown; `401` owned by the `Auth` plugin). The chat partner-identity fetch **reuses the shipped `ProfileApiClient.getProfile` as-is** (`GET /api/v1/users/{id}` → `UserProfileResponse`) — no new client, no extension.

## Decisions

### D1 — Capability partition: fold the full-projection fetch into `mobile-notifications-list` (no new capability)
The client-side full-projection by-id fetch exists **only** to serve this deep-link — post-detail already uses the minimal projection, and no other surface opens a post from an id alone. Folding the ADDED fetch requirements into the `mobile-notifications-list` delta keeps the capability set minimal and the deep-link behavior owned end-to-end by one capability.
- **Alternative**: a standalone `mobile-single-post-read` capability. Rejected — YAGNI; a one-consumer seam doesn't warrant its own capability, and splitting it would scatter the deep-link contract across two specs.

### D2 — Post targets resolve via a by-id fetch (reuse + extend `SinglePostApiClient`), not a lighter route
`PostDetailRoute` carries a full post payload (the feed card supplies it); a notification carries only `target_id`. So a post-target tap must fetch the post to build a `PostDetailTarget`. Extend `SinglePostApiClient` with a full-projection method/result decoding the shipped `SinglePostResponse` wire → map to `PostDetailTarget(distanceM = null, …)`. The existing minimal `SinglePostDto` (post-detail refresh) is left untouched (a second method / sibling result type — no behavior change for its current consumer).
- **Match the deployed MIXED-case wire EXACTLY** (verified against `backend/.../post/SinglePostRoutes.kt`): bare camelCase `id`, `authorUsername`, `authorDisplayName`, `content`, `createdAt`, but `@SerialName` **snake_case** `city_name`, `liked_by_viewer`, `reply_count`. An all-camelCase DTO silently parses `cityName=""`/`likedByViewer=false`/`replyCount=0` on the real wire — the exact timeline-DTO mixed-case footgun (the memory's lesson is "match the actual mixed shape," not "make everything camelCase"). A regression-guard test asserts the all-camelCase shape does NOT bind those three.
- **Alternative**: a coordinate-free `PostDetailByIdRoute` that the detail screen fetches its own header for. Rejected — forks the route + the screen's payload contract for one caller; the by-id endpoint was purpose-built so the consumer fetches and pushes the existing route.

### D3 — Per-type routing table
Resolution is a pure function of `(type, target_type, target_id, actor_user_id, body_data)`:

| Type(s) | Resolution |
|---|---|
| `post_liked`, `post_replied`, `post_auto_hidden` (→ `target_type='post'`) | fetch `GET /api/v1/posts/{target_id}` → `Success` → `onOpenPost(PostDetailTarget)`; `Unavailable` → non-blocking "tidak tersedia", no nav |
| `followed` (`target_type=NULL`, actor present) | `onOpenProfile(actor_user_id)` — no fetch (the profile screen fetches) |
| `chat_message` (`target_type='message'`, actor present) | fetch `GET /api/v1/users/{actor_user_id}` (the sender = the 1:1 partner) → `onOpenChatThread(conversation_id, partnerUsername, partnerDisplayName)`; profile-fetch failure → `onOpenChatThread(conversation_id, "", "")` (the conversation is valid; the top bar degrades to its blank-name placeholder) |
| `chat_message_redacted` (`target_type='message'`, **actor=NULL**) | **non-navigating** (no actor → no resolvable partner identity for the thread top bar) |
| `post_auto_hidden` resolving to `target_type='reply'` | **non-navigating** (no endpoint to resolve a reply → its parent post) |
| informational no-target types | **non-navigating** |

Why `chat_message` needs a fetch (unlike `followed`): `ChatThreadRoute` carries the partner DISPLAY identity in its payload, and the notifications wire returns no actor username (actor-username rendering is a separate deferred follow-up). The sender of a 1:1 `chat_message` IS the partner, so `actor_user_id` resolves the partner via the shipped `user-profile-read`. `chat_message_redacted` is admin-emitted with `actor_user_id = NULL`, so no partner can be resolved → non-navigating (deferred). There is deliberately NO new `GET /api/v1/conversations/{id}` endpoint and NO chat-thread self-resolution change — the messages response carries no partner identity and no by-id conversation route exists, so reusing `user-profile-read` is the shipped-endpoint path.

### D4 — Mark-read and navigation are independent
Tapping always issues the optimistic mark-read (unchanged: `204` success / `404` silent no-op / `5xx`·IO revert) AND independently resolves navigation. A post-target tap that `404`s on the **fetch** still marks the row read — the notification is a real, acknowledged event even if its target post is gone. Navigation never blocks on the mark-read call, and mark-read never blocks on the fetch.

### D5 — Post-unavailable is a non-blocking affordance, not an error screen
`single-post-read` collapses every unresolvable cause (unknown / soft-deleted / shadow-banned author / auto-hidden / blocked-either-direction) to one direction-less `404 post_not_found`. The screen surfaces a transient, non-blocking "Postingan tidak tersedia" message (the canonical disappearing affordance — not a modal, not a full-screen error) and performs no navigation; the row is still marked read. This keeps the notifications list usable and leaks no existence signal (matches the endpoint's privacy intent). Note: a hard-deleted author's post resolves `200` with an anonymized author header (`account-deletion-tombstone`), so it navigates normally (anonymized) — only a true `404` shows the affordance.

### D6 — PII discipline carried forward verbatim
`actor_user_id`, `target_id`, and `conversation_id` are used ONLY as the `ProfileRoute.userId` / `ChatThreadRoute.conversationId` payload fields or a fetch path param — they are NEVER rendered in any UI node nor logged. This is the established pattern (`ProfileRoute.userId` is "never rendered as a UI string"; `ChatThreadRoute.conversationId` is "NOT user PII"). The `partnerUsername` / `partnerDisplayName` resolved from `user-profile-read` ARE display strings intended for the thread top bar (the same display identity the app shows everywhere) — they carry no UUID. The Compose-free `NotificationsUiState` and any diagnostic continue to carry only HTTP status / outcome type.

## Risks / Trade-offs

- **Merge adjacency on `AppShellScreen` / `appEntryProvider` with in-flight `mobile-amplitude-analytics` (#367)** → both may touch the shell wiring. Mitigation: the notifications-nav diff is localized (a bare `NotificationsScreen()` → a parameterized call + one `appEntryProvider` push); a standard rebase resolves it. No shared migration.
- **Post-target / chat-partner tap adds a fetch round-trip before navigation** → a brief per-tap latency (a single `GET`). Mitigation: lightweight read; per-tap resolving indicator on the tapped row; supersede/cancel an in-flight resolution if another row is tapped (`CancellationException` rethrown, never surfaced). For `chat_message`, a partner-fetch failure does NOT block — the thread opens with the blank-name placeholder (the conversation is independently valid).
- **`reply`-target + `chat_message_redacted` non-navigation may read as a dead tap** → `reply` affects only `post_auto_hidden` on your **own** reply (rare); `chat_message_redacted` is an admin-emitted edge with no actor. Both still mark read, and the deferral is captured as a negative-guard requirement + a follow-up issue so the gap is tracked, not silent.

## Open Questions

- **`chat_message_redacted` deep-link** — RESOLVED (proposal review): **non-navigating**. The type carries `actor_user_id = NULL`, so the partner top-bar identity cannot be resolved via `user-profile-read`; navigating would land a misleading blank-name top bar on a live conversation. Deferred with the reply-target case (captured as a negative-guard + a follow-up). Re-open only if a by-id conversation-identity endpoint ships.
