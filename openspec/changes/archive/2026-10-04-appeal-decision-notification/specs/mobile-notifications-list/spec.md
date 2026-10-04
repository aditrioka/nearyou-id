## ADDED Requirements

### Requirement: appeal_decided row renders decision-keyed copy

An `appeal_decided` notification row SHALL render copy keyed by `body_data.decision` via `:shared:resources` `stringResource`: `"approved"` → the approved copy (`notif_appeal_approved`, "Banding kamu diterima — akunmu aktif kembali"), `"rejected"` → the rejected copy (`notif_appeal_rejected`, "Banding kamu ditolak"), and an absent / non-string / unknown `decision` → a neutral appeal copy (`notif_appeal_decided`, "Ada keputusan atas banding kamu") — never the generic `notif_generic` fallback and never a crash. The row SHALL render no excerpt (the `body_data` carries none) and SHALL NOT render the `target_id` appeal UUID or any decision reason.

#### Scenario: approved appeal_decided row renders the approved copy

- **GIVEN** an `appeal_decided` row with `body_data = {"decision":"approved"}`
- **WHEN** the row is rendered
- **THEN** it shows the `notif_appeal_approved` copy AND no UUID is rendered

#### Scenario: rejected appeal_decided row renders the rejected copy

- **GIVEN** an `appeal_decided` row with `body_data = {"decision":"rejected"}`
- **WHEN** the row is rendered
- **THEN** it shows the `notif_appeal_rejected` copy

#### Scenario: appeal_decided row without a decision renders the neutral appeal copy

- **GIVEN** an `appeal_decided` row with `body_data = {}`
- **WHEN** the row is rendered
- **THEN** it shows the `notif_appeal_decided` copy AND no exception is thrown

## MODIFIED Requirements

### Requirement: Notification tap resolves a deep-link destination from (type, target_type, target_id, actor)

The mobile app SHALL resolve a tapped notification to a deep-link destination as a pure function of its `(type, target_type, target_id, actor_user_id, body_data)` fields, following the canonical addressing model in `docs/05-Implementation.md` § Notifications (the outer `(target_type, target_id)` pair is the deep-link address; `body_data` supplies only what that pair cannot). The resolution SHALL map:

- `target_type = "post"` (the `post_liked`, `post_replied`, and `post_auto_hidden`-on-a-post cases) → fetch the post by `target_id` and, on a visible result, the post-detail destination (`onOpenPost`).
- `followed` (`target_type` absent, `actor_user_id` present) → the actor's profile destination (`onOpenProfile(actor_user_id)`), with NO fetch (the profile screen fetches its own data).
- `chat_message` (`target_type = "message"`, `actor_user_id` present) → the chat-thread destination, addressed by `body_data.conversation_id`. Because the notifications wire carries no actor display name, the resolution SHALL fetch the partner's display identity via the SHIPPED `user-profile-read` read (`GET /api/v1/users/{actor_user_id}` — the sender of a 1:1 chat message IS the partner) and invoke `onOpenChatThread(conversation_id, partnerUsername, partnerDisplayName)`. If that profile fetch fails (`404`/IO), the resolution SHALL still invoke `onOpenChatThread(conversation_id, "", "")` — the conversation (messages) is independently valid; the thread top bar degrades to its existing blank-name placeholder rather than blocking a reachable conversation.
- `chat_message_redacted` (`target_type = "message"`, `actor_user_id` = NULL) → NO destination (non-navigating): with no actor there is no partner to resolve for the thread top bar; deferred with the reply-target case (see § "Actor-less and reply-target deep-linking is deferred").
- `target_type = "reply"` (the dynamic reply case of `post_auto_hidden`) → NO destination (non-navigating): there is no reply-by-id → parent-post endpoint to build a post-detail route. Deferred (same § as above).
- `appeal_decided` (`target_type = "appeal"`, `actor_user_id` = NULL) → the appeal-screen destination (`onOpenAppeal()`), with NO fetch and NO payload — the appeal screen reads the caller's own latest appeal status itself (the `target_id` appeal UUID is NOT used as a route key).
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

#### Scenario: appeal_decided resolves to the appeal screen with no fetch

- **GIVEN** an `appeal_decided` row with `target_type = "appeal"`, `target_id = "<P>"`, `actor_user_id = NULL`, and `body_data = {"decision":"approved"}`
- **WHEN** the row is tapped
- **THEN** the row is marked read AND the appeal destination (`onOpenAppeal`) is invoked exactly once AND no `GET /api/v1/posts/...` or `GET /api/v1/users/...` fetch is issued AND `<P>` is not rendered in any UI node

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

### Requirement: NotificationsScreen exposes hoisted deep-link callbacks wired through the shell

`NotificationsScreen` SHALL expose hoisted navigation callbacks — `onOpenPost: (PostDetailTarget) -> Unit`, `onOpenProfile: (userId: String) -> Unit`, `onOpenChatThread: (conversationId: String, partnerUsername: String, partnerDisplayName: String) -> Unit`, and `onOpenAppeal: () -> Unit` — and SHALL invoke them by consuming the `NotificationsViewModel`'s consumed-once nav signal; the screen itself SHALL remain navigation-free (it holds no back-stack reference). `AppShellScreen` SHALL stop invoking `NotificationsScreen()` bare and instead forward its already-hoisted `onOpenPost` / `onOpenProfile` callbacks plus a `onOpenChatThread` callback wired (via `appEntryProvider`) to a `ChatThreadRoute(conversationId, partnerUsername, partnerDisplayName)` push onto the root back stack, and an `onOpenAppeal` callback wired (via `appEntryProvider`) to an `AppealRoute` push onto the root back stack. No new `NavKey` SHALL be declared for these destinations — they reuse the shipped `PostDetailRoute`, `ProfileRoute`, `ChatThreadRoute`, and `AppealRoute`. The push-tap consumer (`PushTapNavigationEffect`) consumes the SAME shared resolver and SHALL push the same route for each resolved target (including `AppealRoute`).

#### Scenario: the shell no longer invokes NotificationsScreen bare

- **WHEN** inspecting `AppShellScreen`'s Notifikasi section
- **THEN** `NotificationsScreen` is invoked WITH the `onOpenPost` / `onOpenProfile` / `onOpenChatThread` / `onOpenAppeal` callbacks (not bare) AND each callback is wired to a root-stack push of the corresponding existing route

#### Scenario: navigation is a consumed-once signal

- **GIVEN** a notification whose tap resolves to a destination
- **WHEN** the row is tapped once AND the screen subsequently recomposes (or the configuration changes)
- **THEN** the destination callback is invoked exactly once (the consumed-once nav signal is cleared after first delivery, not re-emitted on recomposition)

#### Scenario: no new NavKey is introduced

- **WHEN** inspecting the change's NavKey declarations
- **THEN** no new `NavKey` type is added (the deep-links reuse the shipped `PostDetailRoute`, `ProfileRoute`, `ChatThreadRoute`, and `AppealRoute`)
