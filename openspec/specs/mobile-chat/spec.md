# mobile-chat Specification

## Purpose
The `:mobile:app` 1:1 chat surface — the messaging half of the social loop — consuming the shipped backend chat data plane (V15: `chat-conversations`, `chat-realtime-broadcast`, `auth-realtime`) with no backend change. `ConversationListScreen` lists conversations (`GET /api/v1/conversations`, partner display identity + the `Akun Dihapus` deleted/shadow-banned placeholder) and `ChatThreadScreen` renders the 1:1 thread (cursor history, own-vs-other alignment, redacted-message neutral placeholder) with an optimistic send (`POST /api/v1/chat/{id}/messages`, 1–2000-char client guard, block-403 send-blocked state) and a create-or-return entry. A live Supabase Realtime subscribe (HS256-token-authed channel `realtime:conversation:<uuid>`, inbound merged by message `id` against REST history + optimistic sends, REST resync on (re)subscribe, REST-only degrade on failure) makes two devices see messages without a manual refresh; the vendor supabase-kt SDK is fenced to `:infra:supabase-realtime` behind the vendor-free `ChatRealtimeSubscriber` seam (invariant #16). PII discipline is enforced: the `ConversationListRoute`/`ChatThreadRoute` carry display identity only (no user UUIDs, no coordinates), shadow-ban filtering stays server-authoritative (the client carries no viewer-ban signal), redaction is honored at render (`content: null` → placeholder), and no message content or token is ever logged.
## Requirements
### Requirement: ConversationListScreen renders the Pesan surface

The mobile app SHALL ship a Compose Multiplatform screen `ConversationListScreen` (file under `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/chat/`) that renders the authenticated conversation list, reached via the `ConversationListRoute` NavKey. It SHALL display a top bar titled `stringResource(Res.string.chat_list_title)` ("*Pesan*") over a scrollable list of conversation rows wrapped in a pull-to-refresh container, with the loading / empty / error states per the § "Conversation-list state mapping" requirement. The screen SHALL render under `NearYouTheme` (light/dark). No hardcoded UI string literal SHALL appear in the screen source.

#### Scenario: Initial render shows the Pesan title
- **WHEN** a test composes `ConversationListScreen` under `NearYouTheme` with a fake flow emitting a loaded list of conversations
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.chat_list_title)`

#### Scenario: No hardcoded UI strings in the chat screens
- **WHEN** inspecting the chat screen sources (`ConversationListScreen`, `ChatThreadScreen`)
- **THEN** every `Text(...)` / `contentDescription = ...` / placeholder call site sources its text via `stringResource(Res.string.<name>)`; zero literal UI-string arguments appear

### Requirement: Conversation list fetches the canonical endpoint and projects six states

`ConversationsApiClient` SHALL issue `GET /api/v1/conversations` (cursor-paginated). `ConversationsRepository` SHALL map the HTTP **status** to a sealed `ConversationListOutcome` (`Loaded(conversations, nextCursor)`, `NetworkError`, `Error`, `SessionExpired`) with no generic fallthrough; a terminal 401 SHALL map to `SessionExpired` (delegated to the shipped `Auth` plugin / `SessionInvalidator`, not reimplemented). A pure Compose-free `conversationListUiState(outcome, isInitialLoad)` projection SHALL map to `Loading` / `Content` / `Empty` / `Error` / `SessionRedirect`. The loading/refresh behavior SHALL follow `mobile-design-system` § "Canonical list loading and refresh pattern" (initial-load skeleton vs refresh-over-retained-content; never two indicators; non-`Content` states rendered inside a scrollable).

The `ConversationListRoute`-scoped `ConversationListViewModel` SHALL expose exactly ONE `uiState: StateFlow<ConversationListUiState>` produced via `combine(_outcome, initialLoad) { … }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationListUiState.Loading)`, whose projection delegates to the unchanged pure `conversationListUiState(outcome, isInitialLoad)` function (reused, not reimplemented) — so the outcome→state mapping is owned by the ViewModel and is NOT recomputed in the composable (docs/11 §2.2). The initial-load flag SHALL be an INTERNAL (private) `MutableStateFlow` reflected only through `uiState` (which projects to `Loading` until the first outcome arrives); it SHALL NOT be a separate public flow. The raw fetched `ConversationListOutcome` SHALL remain exposed as the ViewModel's domain-state seam (a `StateFlow<ConversationListOutcome?>`, not a `ConversationListUiState`; the raw `Loaded` carries the partner UUID the PII-free `Content` rows strip). The pull-to-refresh indicator SHALL be a SEPARATE `isRefreshing` flag, NOT folded into `uiState`. `ConversationListScreen` SHALL collect that single `uiState` via `collectAsStateWithLifecycle()` and SHALL NOT recompute `conversationListUiState(...)` in the composable over separately-collected `outcome` / `isInitialLoad` flows.

#### Scenario: First-page request shape
- **GIVEN** a Ktor MockEngine capturing outbound requests
- **WHEN** `ConversationsApiClient` loads the first page
- **THEN** the captured request is `GET` with path `/api/v1/conversations` AND carries NO `cursor` parameter, AND the Bearer `Authorization` header is attached by the shipped `Auth` plugin (the client does not set it manually)

#### Scenario: Empty list projects to Empty, not Error
- **WHEN** the endpoint returns `200` with an empty conversation array
- **THEN** the outcome is `Loaded(conversations = [], nextCursor = null)` AND the projection (post-initial-load) is `ConversationListUiState.Empty` (rendering `chat_list_empty`), distinct from `Error`

#### Scenario: Terminal 401 projects to SessionRedirect
- **WHEN** the load results in a terminal 401 after the `Auth` refresh fails
- **THEN** the outcome is `SessionExpired` AND the projection is `SessionRedirect` (a neutral placeholder, NOT the network-error/retry copy)

#### Scenario: Pull-to-refresh is available from a non-Content state
- **GIVEN** the screen is in the `Empty` or `Error` state
- **WHEN** the pull-to-refresh gesture is performed
- **THEN** the reload fetch is invoked AND the state remains the same non-`Content` state during the refresh (it does NOT flip to the initial-load skeleton)

#### Scenario: VM exposes one uiState StateFlow via stateIn delegating to the pure projection
- **WHEN** inspecting `ConversationListViewModel`
- **THEN** it exposes a single `uiState: StateFlow<ConversationListUiState>` produced via `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationListUiState.Loading)` AND for the held `(outcome, isInitialLoad)` its value equals `conversationListUiState(outcome, isInitialLoad)` (the pure function is reused, not reimplemented) AND the initial-load flag is NOT exposed as a separate public flow

#### Scenario: ConversationListScreen observes the entry-scoped ViewModel's single uiState, not a composition-local projection
- **WHEN** inspecting the conversation-list composable in `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/chat/ConversationListScreen.kt`
- **THEN** the screen collects the ViewModel's single `uiState` (plus the separate `isRefreshing` flag) via `collectAsStateWithLifecycle()` AND does NOT recompute `conversationListUiState(...)` in the composable over separately-collected `outcome` / `isInitialLoad` flows

#### Scenario: uiState retains the resolved state across a fresh collector (configuration-change proxy)
- **GIVEN** a `ConversationListViewModel` whose load resolved to a `Loaded` outcome so `uiState.value` is `Content`
- **WHEN** the screen composition is recreated (the configuration-change case) and a fresh collector re-collects the same entry-scoped ViewModel's `uiState`
- **THEN** the re-collected `uiState.value` is still `Content` (the outcome was retained by the entry-scoped ViewModel, not reset to `Loading`)

### Requirement: Conversation rows render partner display identity with the deleted-partner placeholder

Each conversation row SHALL render the OTHER participant's display identity (`username`, `displayName`, `isPremium` badge) sourced from the shipped list-conversations response, plus the relative `last_message_at`. When the backend returns the `COALESCE` placeholder for a shadow-banned/deleted partner (`username = "akun_dihapus"`, `displayName = "Akun Dihapus"`), the row SHALL render that placeholder (via `chat_account_deleted`) rather than a blank or a crash. The projected row SHALL NOT carry the partner's user UUID (display fields only).

#### Scenario: Deleted/shadow-banned partner renders the placeholder
- **GIVEN** a conversation row whose partner fields are the backend placeholders (`displayName = "Akun Dihapus"`)
- **WHEN** the row renders
- **THEN** the rendered tree shows the `chat_account_deleted` ("*Akun Dihapus*") label AND contains no partner user-UUID node

#### Scenario: Conversation row projection carries no UUID PII
- **WHEN** inspecting the projected `ConversationListUiState.Content` rows
- **THEN** each row exposes only display fields (username / displayName / isPremium / relative-time source) AND no partner user-id UUID property exists on the projected row type

### Requirement: ChatThreadScreen renders the 1:1 thread

The mobile app SHALL ship a Compose Multiplatform screen `ChatThreadScreen` reached via the `ChatThreadRoute` NavKey. It SHALL render a top bar with the partner display identity (from the route), a scrollable message list with own-vs-other alignment (own = sent by the viewer), and a bottom input bar (`chat_thread_input_placeholder` + a send action). The initial-load vs refresh behavior SHALL follow the `mobile-design-system` canonical pattern. The screen SHALL render under `NearYouTheme`.

#### Scenario: Own vs other alignment
- **GIVEN** a thread with one message from the viewer and one from the partner
- **WHEN** the thread renders
- **THEN** the viewer's message and the partner's message are distinguishable (alignment/treatment) based on `senderId == viewerId`, AND no sender user-UUID is rendered as text

#### Scenario: Thread fetch shape and pagination
- **GIVEN** a Ktor MockEngine
- **WHEN** the thread loads its first page then loads older messages on scroll-up
- **THEN** the first request is `GET /api/v1/chat/{conversation_id}/messages` with NO `cursor`, and the older-page request carries the `cursor` returned by the first page

#### Scenario: Input bar renders and loading uses a single indicator
- **WHEN** the thread renders loaded history, then a refresh is in flight
- **THEN** the bottom input bar (`chat_thread_input_placeholder` + send action) is present in both cases AND exactly one progress indicator shows at a time (initial-load skeleton vs the `PullToRefreshBox` `isRefreshing` indicator over retained content), per `mobile-design-system` § "Canonical list loading and refresh pattern"

#### Scenario: Shadow-banned/deleted partner top-bar is masked, not a stale real name
- **GIVEN** the partner is shadow-banned or deleted (the conversation list masked them as `Akun Dihapus`)
- **WHEN** the thread top bar renders the partner identity
- **THEN** it shows the `Akun Dihapus` placeholder semantics rather than a stale real name carried on the route payload (the shadow-ban mask is not bypassed via `ChatThreadRoute`)

### Requirement: Redacted messages render a neutral placeholder

A message whose `content` is `null` with `redacted_at` set (the shipped redaction wire — `redaction_reason` is never present) SHALL render the neutral `stringResource(Res.string.chat_message_redacted)` ("*Pesan ini telah dihapus*") placeholder, never an empty bubble, a literal `"null"`, or the original content. This SHALL hold whether the redaction arrives via REST history or via realtime.

#### Scenario: Redacted REST message renders the placeholder
- **GIVEN** a fetched message with `content = null`, `redacted_at` set
- **WHEN** it renders
- **THEN** the bubble shows the `chat_message_redacted` text AND no empty bubble / `"null"` literal appears

#### Scenario: redaction_reason is never parsed onto the model
- **WHEN** a message body that (hypothetically) carries a `redaction_reason` field is parsed
- **THEN** the message DTO/model exposes no `redaction_reason` property and the value never reaches the UI

### Requirement: Send message with client-side guard, optimistic append, and reconcile

The send path SHALL `POST /api/v1/chat/{conversation_id}/messages` with a body of `{ content }` ONLY (no `embedded_*` fields). The client SHALL reject empty/whitespace-only content and content longer than 2000 characters BEFORE issuing the request. On a successful `201`, the optimistically-appended message SHALL be reconciled to the server-assigned `id` from the response. The send-state projection SHALL map `SendOutcome` to the input-bar states (idle / sending / blocked / too-long / network-retry) with no generic fallthrough.

#### Scenario: Send body carries no embedded fields
- **WHEN** the client sends a message
- **THEN** the request body contains a `content` field and NONE of `embedded_post_id` / `embedded_post_snapshot` / `embedded_post_edit_id`

#### Scenario: Over-length content blocked client-side
- **WHEN** the user attempts to send content of 2001 characters
- **THEN** no request is issued AND the input bar shows the too-long state

#### Scenario: Empty or whitespace-only content is not sent
- **WHEN** the user attempts to send `""` or a whitespace-only string (e.g. `"   "`)
- **THEN** no request is issued AND the send action stays idle/disabled (no optimistic append)

#### Scenario: Optimistic append reconciles to the server id
- **GIVEN** an optimistic message appended on send
- **WHEN** the `201` returns the inserted row with its server `id`
- **THEN** the optimistic row is reconciled to that `id` (one row, not two) so a subsequent realtime echo or resync of the same `id` does not duplicate it

### Requirement: Block in either direction renders a send-blocked state

When the send returns `403` with body `{ "error": "Tidak dapat mengirim pesan ke user ini" }` (a `user_blocks` row in either direction), the `SendOutcome` SHALL be `Blocked` and the UI SHALL render the `stringResource(Res.string.chat_send_blocked)` banner (the docs-verbatim string) — not a crash and not a generic error. Existing message history SHALL remain visible (the shipped read path keeps history after a block).

#### Scenario: 403 send maps to the blocked banner
- **WHEN** the send returns `403` with the canonical block error body
- **THEN** the outcome is `SendOutcome.Blocked` AND the rendered tree shows the `chat_send_blocked` banner AND the previously-loaded messages remain visible

### Requirement: Create-or-return opens or resumes a conversation

`ConversationsApiClient.createOrReturnConversation(recipientUserId)` SHALL `POST /api/v1/conversations { recipient_user_id }` and distinguish the shipped responses: `201` (new) and `200` (existing) both yield the conversation id to navigate to; `403` yields a blocked result; `400` (self) and `404` (unknown recipient) yield distinct error results. This path is exposed for the future profile "Kirim pesan" caller; the conversation id it returns drives navigation to `ChatThreadRoute`.

#### Scenario: New and existing both return a conversation id
- **WHEN** create-or-return returns `201` (or `200`)
- **THEN** the result carries the conversation id AND the caller can navigate to `ChatThreadRoute(conversationId = …)`

#### Scenario: Block, self, and unknown are distinct results
- **WHEN** create-or-return returns `403` / `400` / `404`
- **THEN** the result maps to `Blocked` / `SelfConversation` / `RecipientNotFound` respectively (distinct, no generic fallthrough)

### Requirement: ChatRealtimeSubscriber is a vendor-SDK-free seam

The KMP module `:infra:supabase-realtime` commonMain SHALL declare a vendor-SDK-free `ChatRealtimeSubscriber` interface exposing `fun subscribe(conversationId: Uuid): Flow<ChatMessageInbound>`, a `ChatMessageInbound` data class (`id`, `conversationId`, `senderId`, `content: String?`, `createdAt: Instant`, `redactedAt: Instant?`), and a `RealtimeTokenProvider` fun-interface (`suspend fun fetchToken(): String`). The interface source file SHALL NOT import any Supabase / vendor SDK symbol — the supabase-kt import is confined to the `SupabaseChatRealtimeSubscriber` implementation in the same module (CLAUDE.md critical invariant — vendor SDK imports only in `:infra:*`). (Apply-phase correction: the proposal named `:core:domain`, but that module is JVM-only and a KMP `:mobile:app` cannot depend on it; the interface lives in the KMP `:infra:supabase-realtime` instead, and `:mobile:app` consumes it from there — see design D2.) The three `embedded_*` payload keys SHALL NOT appear on `ChatMessageInbound` (parsed-and-dropped at the infra boundary).

#### Scenario: Interface source file has no vendor import
- **WHEN** a static scan runs over the `ChatRealtimeSubscriber` interface source file for `io.github.jan.supabase.*` (or any vendor Supabase import)
- **THEN** zero matches are found (the import lives only in the `SupabaseChatRealtimeSubscriber` impl)

#### Scenario: Inbound model omits embedded and redaction-reason fields
- **WHEN** inspecting `ChatMessageInbound`
- **THEN** it has no `embeddedPost*` properties and no `redactionReason` property

### Requirement: Realtime subscribe is token-authed against the canonical channel in an :infra module

A new Gradle module `:infra:supabase-realtime` (Android + iOS targets) SHALL provide the only `ChatRealtimeSubscriber` implementation, using the supabase-kt Realtime client. Before joining, it SHALL fetch a fresh HS256 token via `GET /api/v1/realtime/token`. The token-endpoint client DTO SHALL mirror the SHIPPED `RealtimeTokenResponse` envelope `{ "token": "<jws>", "expires_in": <seconds> }` (snake_case `expires_in`) — NOT a bare string — and the subscriber's token-refresh cadence SHALL key off the returned `expires_in` rather than a hardcoded TTL literal. It SHALL join the channel named exactly `realtime:conversation:<conversation_id>` where `<conversation_id>` is the lowercase canonical UUID (per `chat-realtime-broadcast` § Channel name format). The vendor Supabase import SHALL appear ONLY in this module. The module SHALL be registered in `settings.gradle.kts` and `dev/module-descriptions.txt`, and `dev/scripts/sync-readme.sh --check` SHALL be clean.

#### Scenario: Channel name uses the canonical lowercase form
- **GIVEN** `conversationId` `11111111-2222-3333-4444-555555555555`
- **WHEN** the subscriber joins
- **THEN** the channel name is exactly `"realtime:conversation:11111111-2222-3333-4444-555555555555"` (lowercase canonical UUID)

#### Scenario: Token fetched before join
- **WHEN** a subscription begins
- **THEN** `GET /api/v1/realtime/token` is invoked and its HS256 token is used to authenticate the channel join (the token is never persisted to disk, never logged, and never captured into a `@Serializable` NavKey / saved-state holder)

#### Scenario: Token endpoint envelope is parsed (not a bare string)
- **GIVEN** the shipped response body `{ "token": "<jws>", "expires_in": 3600 }`
- **WHEN** the realtime-token client parses it
- **THEN** the DTO yields the token string AND `expiresIn = 3600` (the snake_case `expires_in` is mapped), AND a body shaped as a bare JSON string would NOT satisfy the DTO

#### Scenario: Reconnect re-fetches a fresh token
- **GIVEN** an active subscription whose transport drops
- **WHEN** the subscriber rejoins the channel
- **THEN** it invokes `GET /api/v1/realtime/token` again (a fresh token) rather than reusing the prior, possibly-near-expiry token

#### Scenario: Vendor SDK confined to the infra module
- **WHEN** a static scan runs over `:mobile:app` and `:core:domain` for the supabase-kt import
- **THEN** zero matches are found (the import lives only in `:infra:supabase-realtime`)

### Requirement: Realtime messages merge into the thread deduplicated by id

The `ChatThreadViewModel` SHALL collect the inbound realtime flow within its scope and merge each `ChatMessageInbound` into the message list **keyed by message `id`**: a new id appends; an already-present id is updated in place (so a realtime redaction flips an existing row's `content` to null rather than duplicating it). The merged list SHALL be ordered by `(createdAt, id)`. The merge function SHALL be pure and unit-tested over REST + optimistic + realtime inputs.

#### Scenario: New inbound id appends
- **GIVEN** a thread with messages loaded
- **WHEN** a realtime `ChatMessageInbound` with an id not yet present arrives
- **THEN** it is appended once, ordered by `(createdAt, id)`

#### Scenario: Existing id collapses (no duplicate)
- **GIVEN** an optimistic/REST message already present with id X
- **WHEN** a realtime message with id X arrives
- **THEN** the list still contains exactly one row for id X

#### Scenario: Realtime redaction flips the existing row
- **GIVEN** a rendered message row with id X and non-null content
- **WHEN** a realtime inbound with id X and `content = null`, `redactedAt` set arrives
- **THEN** row X updates in place to the redacted placeholder (no second row)

### Requirement: Reconnect resyncs via REST and realtime failure degrades to REST-only

On every (re)subscribe the ViewModel SHALL trigger a REST first-page resync and merge by id (the no-outbox recovery path per `chat-realtime-broadcast`). A reconnect after a transport drop SHALL re-fetch a fresh HS256 token before rejoining. A realtime/token failure SHALL NOT break the screen: the thread SHALL remain usable in REST-only mode (send via REST + pull-to-refresh), the failure SHALL be logged without content or token, and no realtime-specific error chrome SHALL be shown. The subscription SHALL be torn down (channel unsubscribed) when the route is popped / the ViewModel is cleared.

#### Scenario: Subscription cancelled on clear
- **GIVEN** an active thread subscription
- **WHEN** the `ChatThreadRoute` is popped (ViewModel `onCleared`)
- **THEN** the inbound-flow collection is cancelled and the channel is unsubscribed

#### Scenario: Realtime failure leaves the thread REST-usable
- **GIVEN** the realtime token fetch (or channel join) fails
- **WHEN** the user sends a message and pulls to refresh
- **THEN** the send still goes via REST and the refresh resyncs history; no realtime error banner is shown

### Requirement: Shadow-ban filtering is server-authoritative; the client does not gate on ban state

The client SHALL NOT re-implement shadow-ban filtering and SHALL NOT gate any merge/render decision on the viewer's own shadow-ban state (shadow-ban status is deliberately not surfaced to the banned user, so the client has no such signal). Shadow-ban correctness is the server's: the publish-side skip means a shadow-banned sender's messages are never broadcast, and the read-path filter excludes shadow-banned other senders. Any own-message echo that does arrive via realtime (the normal non-banned case, or an admin-flip mid-flight race) SHALL be reconciled by the id-keyed dedupe (per § "Realtime messages merge into the thread deduplicated by id") onto the already-present optimistic/REST row — not special-cased.

#### Scenario: Own-message realtime echo collapses, not dropped
- **GIVEN** the viewer has sent a message (present optimistically with server id X)
- **WHEN** a realtime inbound arrives with `senderId == viewerId` and `id == X`
- **THEN** it collapses onto the existing row by id-dedupe (exactly one row), with no shadow-ban-state gate applied

#### Scenario: Client carries no viewer-shadow-ban signal
- **WHEN** inspecting the chat merge/render code paths
- **THEN** no branch reads a "viewer is shadow-banned" flag (the client has no such signal; filtering is server-side)

### Requirement: First-send notification-permission prompt

The first time a user successfully sends a message (a per-install one-shot), the app SHALL show the `notif_permission_rationale` rationale and then the platform notification-permission request. This SHALL reuse the app's existing permission seam where one exists (no duplicate permission machinery) and SHALL be independent of FCM token registration (which stays out of scope). A denied permission SHALL NOT block sending.

#### Scenario: Prompt shown once on first send
- **GIVEN** a fresh install that has never sent a message
- **WHEN** the user sends their first message successfully
- **THEN** the notification-permission rationale + request is shown once; a subsequent send does not re-prompt

#### Scenario: Permission denial does not block chat
- **WHEN** the user denies the notification permission
- **THEN** the message still sends and the thread remains fully usable

### Requirement: Chat navigation uses serializable root-stack routes with no PII

`ConversationListRoute` (parameterless `@Serializable data object`) and `ChatThreadRoute` (`@Serializable data class` carrying `conversationId: String` + the partner display fields `partnerUsername`/`partnerDisplayName`, both defaulted) SHALL be declared in `NavKeys.kt` and registered in the `navSavedStateConfiguration` polymorphic `SerializersModule` (the iOS-saveable back stack). Both SHALL be pushed onto the ROOT back stack (overlaying the section shell), mirroring `PostDetailRoute`. `ChatThreadRoute` SHALL NOT declare a partner user-UUID property or any coordinate property (PII must not enter the serialized back stack).

#### Scenario: ChatThreadRoute carries no PII
- **WHEN** inspecting `ChatThreadRoute`
- **THEN** it has a `conversationId` (a conversation UUID, not user PII) + partner display fields only; it has NO partner user-id, latitude, or longitude property

#### Scenario: Routes are registered for the saveable back stack
- **WHEN** inspecting the `navSavedStateConfiguration` `SerializersModule`
- **THEN** both `ConversationListRoute` and `ChatThreadRoute` are registered (so the back stack restores after process death on iOS)

