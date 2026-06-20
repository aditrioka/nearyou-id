## RENAMED Requirements

- FROM: `### Requirement: Tapping a row marks it read; deep-link navigation is deferred`
- TO: `### Requirement: Tapping a row marks it read and deep-links to its target`

## MODIFIED Requirements

### Requirement: Tapping a row marks it read and deep-links to its target

Tapping a notification row SHALL issue `PATCH /api/v1/notifications/{id}/read` and optimistically flip that row to the read state. A `204 No Content` response SHALL confirm success; a `404` (code `not_found` — already-read, not-owned, or non-existent) SHALL be treated as a no-op (the row remains/flips to read; no error surfaced). On any OTHER failure (5xx / network-IO), the optimistic flip SHALL be reverted to unread and NO blocking error surfaced (the next refresh reconciles).

In ADDITION, tapping SHALL navigate to the row's resolved deep-link destination per the § "Notification tap resolves a deep-link destination from (type, target_type, target_id, actor)" requirement. Mark-read and navigation are INDEPENDENT: a tap ALWAYS issues the mark-read call (its success/failure handling above is unchanged), and navigation never blocks on mark-read nor mark-read on navigation. A `post`-target tap whose by-id fetch fails to resolve a visible post (the endpoint's single `404 post_not_found`) SHALL still mark the row read AND surface a non-blocking "Postingan tidak tersedia" affordance (a transient message — NOT a modal, NOT a full-screen error) with NO navigation. A row whose type has no in-app destination — the no-target informational types and the `reply`-target case (see the resolution requirement) — SHALL navigate nowhere and remain mark-read-only.

The navigation SHALL be delivered as a consumed-once signal (a nullable field on the screen's state, cleared after first delivery — NOT a `Channel`/`SharedFlow` event bus, per docs/11 § 2.2), so it does NOT re-fire on recomposition or configuration change. No `actor_user_id` / `target_id` / `conversation_id` SHALL be rendered in any UI node nor logged as part of resolving or performing the navigation (they are route payload / path params only).

#### Scenario: Tapping an unread row marks it read

- **GIVEN** a `FakeNotificationsFlow`/MockEngine where `PATCH /api/v1/notifications/{id}/read` returns 204 AND a rendered unread row
- **WHEN** the row is tapped
- **THEN** a `PATCH /api/v1/notifications/{id}/read` request is issued for that row's `id` AND the row renders as read

#### Scenario: 404 on mark-read is a silent no-op

- **GIVEN** `PATCH /api/v1/notifications/{id}/read` returns `404 { "error": { "code": "not_found" } }`
- **WHEN** the row is tapped
- **THEN** no blocking error is surfaced AND the row renders as read (idempotent-looking)

#### Scenario: Transport failure reverts the optimistic flip

- **GIVEN** `PATCH /api/v1/notifications/{id}/read` returns HTTP 500 (or throws `IOException`) AND a rendered unread row
- **WHEN** the row is tapped (optimistically flipped to read)
- **THEN** the row reverts to the unread state AND no blocking error is surfaced

#### Scenario: A post-target tap marks read and navigates to post detail

- **GIVEN** a rendered unread `post_liked` row with `target_type = "post"`, `target_id = "<P>"` AND a fetch seam where `GET /api/v1/posts/<P>` resolves to a visible post
- **WHEN** the row is tapped
- **THEN** the mark-read call is issued for the row AND the resolved post-detail destination (`onOpenPost`) is invoked exactly once AND no post `target_id` UUID appears in any rendered UI node

#### Scenario: A post-target whose fetch is unavailable marks read with no navigation

- **GIVEN** a rendered unread `post_liked` row whose `GET /api/v1/posts/{target_id}` returns `404 post_not_found` (or any non-200 / IO failure)
- **WHEN** the row is tapped
- **THEN** the row is marked read AND a non-blocking "Postingan tidak tersedia" affordance is shown AND NO navigation destination is invoked

## ADDED Requirements

### Requirement: Notification tap resolves a deep-link destination from (type, target_type, target_id, actor)

The mobile app SHALL resolve a tapped notification to a deep-link destination as a pure function of its `(type, target_type, target_id, actor_user_id, body_data)` fields, following the canonical addressing model in `docs/05-Implementation.md` § Notifications (the outer `(target_type, target_id)` pair is the deep-link address; `body_data` supplies only what that pair cannot). The resolution SHALL map:

- `target_type = "post"` (the `post_liked`, `post_replied`, and `post_auto_hidden`-on-a-post cases) → fetch the post by `target_id` and, on a visible result, the post-detail destination (`onOpenPost`).
- `followed` (`target_type` absent, `actor_user_id` present) → the actor's profile destination (`onOpenProfile(actor_user_id)`), with NO fetch (the profile screen fetches its own data).
- `chat_message` (`target_type = "message"`, `actor_user_id` present) → the chat-thread destination, addressed by `body_data.conversation_id`. Because the notifications wire carries no actor display name, the resolution SHALL fetch the partner's display identity via the SHIPPED `user-profile-read` read (`GET /api/v1/users/{actor_user_id}` — the sender of a 1:1 chat message IS the partner) and invoke `onOpenChatThread(conversation_id, partnerUsername, partnerDisplayName)`. If that profile fetch fails (`404`/IO), the resolution SHALL still invoke `onOpenChatThread(conversation_id, "", "")` — the conversation (messages) is independently valid; the thread top bar degrades to its existing blank-name placeholder rather than blocking a reachable conversation.
- `chat_message_redacted` (`target_type = "message"`, `actor_user_id` = NULL) → NO destination (non-navigating): with no actor there is no partner to resolve for the thread top bar; deferred with the reply-target case (see § "Actor-less and reply-target deep-linking is deferred").
- `target_type = "reply"` (the dynamic reply case of `post_auto_hidden`) → NO destination (non-navigating): there is no reply-by-id → parent-post endpoint to build a post-detail route. Deferred (same § as above).
- every no-target informational type (`subscription_billing_issue`, `subscription_expired`, `account_action_applied`, `data_export_ready`, `privacy_flip_warning`, `username_release_scheduled`, `apple_relay_email_changed`) → NO destination (non-navigating).

An unknown/future `type`, or a row missing the field its mapping requires (e.g. a `message` row without `body_data.conversation_id`), SHALL resolve to NO destination (no crash). The resolution SHALL use `actor_user_id` / `target_id` / `conversation_id` ONLY as destination payload or fetch path params — never rendering or logging them (the resolved `partnerUsername` / `partnerDisplayName` are display strings, NOT UUIDs).

#### Scenario: followed resolves to the actor's profile with no fetch

- **GIVEN** a `followed` row with `actor_user_id = "<A>"` and no `target_id`
- **WHEN** the row is tapped
- **THEN** the profile destination is invoked with `<A>` AND no `GET /api/v1/posts/...` fetch is issued AND `<A>` is not rendered in any UI node

#### Scenario: chat_message resolves the partner profile then navigates to the thread

- **GIVEN** a `chat_message` row with `target_type = "message"`, `actor_user_id = "<A>"`, `body_data = {"conversation_id":"<C>"}` AND a `GET /api/v1/users/<A>` returning `username`/`displayName`
- **WHEN** the row is tapped
- **THEN** the chat-thread destination is invoked with conversation `<C>` and the fetched `partnerUsername`/`partnerDisplayName` AND neither `<A>` nor `<C>` is rendered in any UI node

#### Scenario: a chat_message whose partner fetch fails still opens the thread

- **GIVEN** a `chat_message` row whose `GET /api/v1/users/{actor_user_id}` returns `404` (or IO failure) and `body_data = {"conversation_id":"<C>"}`
- **WHEN** the row is tapped
- **THEN** the chat-thread destination is invoked with conversation `<C>` and empty partner display fields (the conversation is reachable; the thread top bar renders its existing blank-name placeholder) AND no blocking error is surfaced

#### Scenario: chat_message_redacted (no actor) does not navigate

- **GIVEN** a `chat_message_redacted` row with `target_type = "message"`, `actor_user_id = NULL`, and `body_data = {"conversation_id":"<C>"}`
- **WHEN** the row is tapped
- **THEN** the row is marked read AND no navigation destination is invoked (with no actor, the partner top-bar identity cannot be resolved; deferred)

#### Scenario: an informational no-target row navigates nowhere

- **GIVEN** a `subscription_expired` row with no `target_type` and no actionable target
- **WHEN** the row is tapped
- **THEN** the row is marked read AND no navigation destination is invoked

#### Scenario: an unknown type or a message row missing conversation_id navigates nowhere

- **GIVEN** a row whose `type` is an unrecognized/future value, OR a `target_type = "message"` row whose `body_data` has no `conversation_id`
- **WHEN** the row is tapped
- **THEN** no navigation destination is invoked AND no crash occurs (the tap still marks read)

#### Scenario: a second tap supersedes an in-flight resolution

- **GIVEN** a tapped post-target row A whose by-id fetch is still in flight
- **WHEN** a second post-target row B is tapped before A resolves
- **THEN** A's resolution is superseded/cancelled (its `CancellationException` is swallowed, never surfaced) AND only B's resolved destination is invoked (no double-navigation)

### Requirement: A post-target notification resolves to a PostDetailTarget via the full-projection single-post fetch

The mobile app SHALL resolve a `post`-target notification to a `PostDetailTarget` by fetching `GET /api/v1/posts/{target_id}` (the shipped `single-post-read` capability) through the existing `SinglePostApiClient`, extended with a full-projection read that decodes the deployed `SinglePostResponse` wire's **MIXED case** exactly: bare camelCase `id`, `authorUsername`, `authorDisplayName`, `content`, `createdAt`, and `@SerialName` **snake_case** `city_name` (→ `cityName`), `liked_by_viewer` (→ `likedByViewer`), `reply_count` (→ `replyCount`). It MUST NOT decode those three as bare camelCase — an all-camelCase DTO silently parses `cityName = ""`, `likedByViewer = false`, `replyCount = 0` on the real wire (the timeline-DTO mixed-case footgun). The read maps these to a `PostDetailTarget` with `distanceM = null` (the by-id projection omits coordinates). A `200` SHALL yield a `Success` carrying the mapped `PostDetailTarget`; a `404 post_not_found`, any other non-`200`, or a transport/IO failure SHALL yield the graceful `Unavailable` (mirroring the existing `SinglePostApiResult` discipline). `CancellationException` SHALL be rethrown (never mapped to a failure); `401` is owned by the `Auth` plugin. The full-projection read SHALL decode NO author UUID and NO coordinate field (the projection carries none — no-PII), and SHALL NOT alter the existing minimal `content`/`editedAt`/`isAuthor` projection the post-detail refresh consumes.

#### Scenario: a 200 mixed-case response maps to a PostDetailTarget with null distance

- **GIVEN** a MockEngine returning `200` for `GET /api/v1/posts/<P>` with the deployed mixed-case body — bare camelCase `id`/`authorUsername`/`authorDisplayName`/`content`/`createdAt` AND snake_case `"city_name"`/`"liked_by_viewer"`/`"reply_count"` keys
- **WHEN** the full-projection fetch runs for `<P>`
- **THEN** it returns a `Success` whose `PostDetailTarget` carries the parsed `content` / `cityName` (from `city_name`) / `createdAt` / `likedByViewer` (from `liked_by_viewer`) / `replyCount` (from `reply_count`) / `authorUsername` / `authorDisplayName` AND `distanceM` is `null`

#### Scenario: an all-camelCase body does NOT bind the snake_case fields (regression guard)

- **GIVEN** a MockEngine returning `200` with an all-camelCase body that uses `cityName`/`likedByViewer`/`replyCount` keys (the wrong shape)
- **WHEN** the full-projection fetch runs
- **THEN** the test asserts those three fields do NOT populate from the camelCase keys (proving the DTO binds the snake_case `@SerialName`, so a regression to an all-camelCase DTO would fail this test rather than silently yielding `cityName=""`/`likedByViewer=false`/`replyCount=0`)

#### Scenario: a 404 (or non-200 / IO) yields Unavailable

- **GIVEN** a MockEngine returning `404 post_not_found` (or `500`, or throwing `IOException`) for `GET /api/v1/posts/<P>`
- **WHEN** the full-projection fetch runs for `<P>`
- **THEN** it returns `Unavailable` AND no exception propagates to the caller

#### Scenario: the minimal post-detail projection is undisturbed

- **WHEN** inspecting `SinglePostApiClient`
- **THEN** the existing minimal `content`/`editedAt`/`isAuthor` read used by post-detail refresh still exists and is unchanged AND the new full-projection read is a distinct method/result (the two do not share a decoded type that would force coordinate/UUID fields onto either)

### Requirement: NotificationsScreen exposes hoisted deep-link callbacks wired through the shell

`NotificationsScreen` SHALL expose hoisted navigation callbacks — `onOpenPost: (PostDetailTarget) -> Unit`, `onOpenProfile: (userId: String) -> Unit`, and `onOpenChatThread: (conversationId: String, partnerUsername: String, partnerDisplayName: String) -> Unit` — and SHALL invoke them by consuming the `NotificationsViewModel`'s consumed-once nav signal; the screen itself SHALL remain navigation-free (it holds no back-stack reference). `AppShellScreen` SHALL stop invoking `NotificationsScreen()` bare and instead forward its already-hoisted `onOpenPost` / `onOpenProfile` callbacks plus a `onOpenChatThread` callback wired (via `appEntryProvider`) to a `ChatThreadRoute(conversationId, partnerUsername, partnerDisplayName)` push onto the root back stack. This change SHALL NOT declare any new `NavKey` — it reuses the shipped `PostDetailRoute`, `ProfileRoute`, and `ChatThreadRoute`.

#### Scenario: the shell no longer invokes NotificationsScreen bare

- **WHEN** inspecting `AppShellScreen`'s Notifikasi section
- **THEN** `NotificationsScreen` is invoked WITH the `onOpenPost` / `onOpenProfile` / `onOpenChatThread` callbacks (not bare) AND each callback is wired to a root-stack push of the corresponding existing route

#### Scenario: navigation is a consumed-once signal

- **GIVEN** a notification whose tap resolves to a destination
- **WHEN** the row is tapped once AND the screen subsequently recomposes (or the configuration changes)
- **THEN** the destination callback is invoked exactly once (the consumed-once nav signal is cleared after first delivery, not re-emitted on recomposition)

#### Scenario: no new NavKey is introduced

- **WHEN** inspecting the change's NavKey declarations
- **THEN** no new `NavKey` type is added (the deep-links reuse the shipped `PostDetailRoute`, `ProfileRoute`, and `ChatThreadRoute`)

### Requirement: Actor-less and reply-target deep-linking is deferred

Two deep-link cases SHALL be deferred (the tap marks the row read and performs NO navigation), each captured here as a negative-guard so a follow-up change has a requirement to MODIFY once the enabling path ships:

- a `target_type = "reply"` notification (the dynamic reply case of `post_auto_hidden`) — no reply-by-id → parent-post endpoint exists to build a `PostDetailRoute`.
- a `chat_message_redacted` notification (`target_type = "message"`, `actor_user_id = NULL`) — with no actor, the partner top-bar identity for the thread cannot be resolved via `user-profile-read`; navigating would land a misleading blank-name top bar on a live conversation.

#### Scenario: a reply-target auto-hidden notification does not navigate

- **GIVEN** a `post_auto_hidden` row whose `target_type = "reply"` and `target_id = "<R>"`
- **WHEN** the row is tapped
- **THEN** the row is marked read AND no navigation destination is invoked AND `<R>` is not rendered in any UI node

#### Scenario: an actor-less chat_message_redacted notification does not navigate

- **GIVEN** a `chat_message_redacted` row with `actor_user_id = NULL`
- **WHEN** the row is tapped
- **THEN** the row is marked read AND no navigation destination is invoked
