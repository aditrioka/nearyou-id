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
- `followed` (`target_type` absent, `actor_user_id` present) → the actor's profile destination (`onOpenProfile(actor_user_id)`), with NO fetch.
- `target_type = "message"` (the `chat_message` and `chat_message_redacted` cases) → the chat-thread destination (`onOpenChatThread(body_data.conversation_id)`), with NO fetch.
- `target_type = "reply"` (the dynamic reply case of `post_auto_hidden`) → NO destination (non-navigating): there is no reply-by-id → parent-post endpoint to build a post-detail route. This is an explicit deferral captured by the § "Reply-target deep-linking is deferred" negative-guard requirement.
- every no-target informational type (`subscription_billing_issue`, `subscription_expired`, `account_action_applied`, `data_export_ready`, `privacy_flip_warning`, `username_release_scheduled`, `apple_relay_email_changed`) → NO destination (non-navigating).

An unknown/future `type`, or a row missing the field its mapping requires (e.g. a `message` row without `body_data.conversation_id`), SHALL resolve to NO destination (no crash). The resolution SHALL use `actor_user_id` / `target_id` / `conversation_id` ONLY as destination payload or fetch path params — never rendering or logging them.

#### Scenario: followed resolves to the actor's profile with no fetch

- **GIVEN** a `followed` row with `actor_user_id = "<A>"` and no `target_id`
- **WHEN** the row is tapped
- **THEN** the profile destination is invoked with `<A>` AND no `GET /api/v1/posts/...` fetch is issued AND `<A>` is not rendered in any UI node

#### Scenario: chat_message resolves to the chat thread from body_data.conversation_id

- **GIVEN** a `chat_message` row with `target_type = "message"` and `body_data = {"conversation_id":"<C>"}`
- **WHEN** the row is tapped
- **THEN** the chat-thread destination is invoked with conversation `<C>` AND no fetch is issued AND `<C>` is not rendered in any UI node

#### Scenario: chat_message_redacted resolves to the chat thread

- **GIVEN** a `chat_message_redacted` row with `target_type = "message"` and `body_data = {"conversation_id":"<C>"}`
- **WHEN** the row is tapped
- **THEN** the chat-thread destination is invoked with conversation `<C>` (the conversation persists; the redacted message renders its existing moderator placeholder)

#### Scenario: an informational no-target row navigates nowhere

- **GIVEN** a `subscription_expired` row with no `target_type` and no actionable target
- **WHEN** the row is tapped
- **THEN** the row is marked read AND no navigation destination is invoked

#### Scenario: an unknown type or a message row missing conversation_id navigates nowhere

- **GIVEN** a row whose `type` is an unrecognized/future value, OR a `target_type = "message"` row whose `body_data` has no `conversation_id`
- **WHEN** the row is tapped
- **THEN** no navigation destination is invoked AND no crash occurs (the tap still marks read)

### Requirement: A post-target notification resolves to a PostDetailTarget via the full-projection single-post fetch

The mobile app SHALL resolve a `post`-target notification to a `PostDetailTarget` by fetching `GET /api/v1/posts/{target_id}` (the shipped `single-post-read` capability) through the existing `SinglePostApiClient`, extended with a full-projection read that decodes the shipped wire's **bare camelCase** fields — `id`, `authorUsername`, `authorDisplayName`, `content`, `cityName`, `createdAt`, `likedByViewer`, `replyCount` (matching the deployed mixed-case response, NOT a snake_case shape) — and maps them to a `PostDetailTarget` with `distanceM = null` (the by-id projection omits coordinates). A `200` SHALL yield a `Success` carrying the mapped `PostDetailTarget`; a `404 post_not_found`, any other non-`200`, or a transport/IO failure SHALL yield the graceful `Unavailable` (mirroring the existing `SinglePostApiResult` discipline). `CancellationException` SHALL be rethrown (never mapped to a failure); `401` is owned by the `Auth` plugin. The full-projection read SHALL decode NO author UUID and NO coordinate field (the projection carries none — no-PII), and SHALL NOT alter the existing minimal `content`/`editedAt`/`isAuthor` projection the post-detail refresh consumes.

#### Scenario: a 200 full-projection response maps to a PostDetailTarget with null distance

- **GIVEN** a MockEngine returning `200` for `GET /api/v1/posts/<P>` with a bare-camelCase body (`id`, `authorUsername`, `authorDisplayName`, `content`, `cityName`, `createdAt`, `likedByViewer`, `replyCount`)
- **WHEN** the full-projection fetch runs for `<P>`
- **THEN** it returns a `Success` whose `PostDetailTarget` carries the parsed `content` / `cityName` / `createdAt` / `likedByViewer` / `replyCount` / `authorUsername` / `authorDisplayName` AND `distanceM` is `null`

#### Scenario: a 404 (or non-200 / IO) yields Unavailable

- **GIVEN** a MockEngine returning `404 post_not_found` (or `500`, or throwing `IOException`) for `GET /api/v1/posts/<P>`
- **WHEN** the full-projection fetch runs for `<P>`
- **THEN** it returns `Unavailable` AND no exception propagates to the caller

#### Scenario: the minimal post-detail projection is undisturbed

- **WHEN** inspecting `SinglePostApiClient`
- **THEN** the existing minimal `content`/`editedAt`/`isAuthor` read used by post-detail refresh still exists and is unchanged AND the new full-projection read is a distinct method/result (the two do not share a decoded type that would force coordinate/UUID fields onto either)

### Requirement: NotificationsScreen exposes hoisted deep-link callbacks wired through the shell

`NotificationsScreen` SHALL expose hoisted navigation callbacks — `onOpenPost: (PostDetailTarget) -> Unit`, `onOpenProfile: (userId: String) -> Unit`, and `onOpenChatThread: (conversationId: String) -> Unit` — and SHALL collect the `NotificationsViewModel`'s one-shot navigation events to invoke them; the screen itself SHALL remain navigation-free (it holds no back-stack reference). `AppShellScreen` SHALL stop invoking `NotificationsScreen()` bare and instead forward its already-hoisted `onOpenPost` / `onOpenProfile` callbacks plus a `onOpenChatThread` callback wired (via `appEntryProvider`) to a `ChatThreadRoute(conversationId)` push onto the root back stack. This change SHALL NOT declare any new `NavKey` — it reuses the shipped `PostDetailRoute`, `ProfileRoute`, and `ChatThreadRoute`.

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

### Requirement: Reply-target deep-linking is deferred

Deep-link navigation for a `target_type = "reply"` notification (the dynamic reply case of `post_auto_hidden`) SHALL be deferred: the tap marks the row read and performs NO navigation, because no reply-by-id → parent-post endpoint exists to build a `PostDetailRoute`. This deferral is captured here as a negative-guard so a follow-up change has a requirement to MODIFY once a reply-resolution path ships.

#### Scenario: a reply-target auto-hidden notification does not navigate

- **GIVEN** a `post_auto_hidden` row whose `target_type = "reply"` and `target_id = "<R>"`
- **WHEN** the row is tapped
- **THEN** the row is marked read AND no navigation destination is invoked AND `<R>` is not rendered in any UI node
