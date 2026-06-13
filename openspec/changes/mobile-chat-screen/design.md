## Context

`:mobile:app` is a Navigation 3 + Koin + Material 3 Compose Multiplatform app. The established per-screen patterns (to mirror, not reinvent):

- **Navigation**: typed `@Serializable` `NavKey` routes in `screens/routing/NavKeys.kt`; payload-carrying routes (e.g. `PostDetailRoute`) registered in the `navSavedStateConfiguration` polymorphic `SerializersModule` for the iOS-saveable back stack; root-stack pushes overlay the `AppShellScreen` bottom-nav (Home/Notifikasi/Profil sections + the FAB + `PostDetailRoute` use this same root-stack mechanism).
- **Data seam**: `*ApiClient` (`@Serializable` DTOs mirroring the SHIPPED wire) → `*Repository : *Flow` (Koin `single`, interface-seamed for fakes) mapping **HTTP status** → a sealed `*Outcome` (no generic fallthrough), consumed by a route-scoped `ViewModel` (`viewModel { … }`) exposing `StateFlow`s, projected by a **pure, Compose-free** `*UiState` + projection function (unit-tested), rendered by the screen. Terminal-401 → `SessionExpired` outcome → `SessionRedirect` state (the shipped `SessionInvalidator` re-routes to `SignInScreen`).
- **Loading/refresh**: the `mobile-design-system` § "Canonical list loading and refresh pattern" — `isInitialLoad` (skeleton) vs `isRefreshing` (`PullToRefreshBox` indicator over retained content), never two indicators; non-`Content` states render inside a scrollable so pull-to-refresh stays available.
- **Strings**: every UI string via `stringResource(Res.string.X)` from `:shared:resources` (Bahasa Indonesia; the no-hardcoded-strings grep is enforced).

The backend half is **fully shipped** (no change here):
- `chat-conversations` — `POST /api/v1/conversations` (201 new / 200 existing / 403 block / 400 self / 404 unknown); `GET /api/v1/conversations` (cursor, `last_message_at DESC NULLS LAST, created_at DESC`, partner via `LEFT JOIN visible_users` + `COALESCE` placeholders, NOT block-excluded); `GET /api/v1/chat/{id}/messages` (cursor, 50/page cap 100, `created_at DESC, id DESC`, shadow-ban read filter, redacted rows `content: null` + no `redaction_reason`); `POST /api/v1/chat/{id}/messages` (1–2000 chars, bidirectional block → 403 `"Tidak dapat mengirim pesan ke user ini"`, notification emit + realtime publish on success).
- `chat-realtime-broadcast` — publish to channel `realtime:conversation:<lowercase-uuid>`; 9-key snake_case payload (`id`, `conversation_id`, `sender_id`, `content`, `embedded_post_id`, `embedded_post_snapshot`, `embedded_post_edit_id`, `created_at`, `redacted_at`); `content: null` when redacted; `redaction_reason` never serialized; publish skipped for shadow-banned senders; no outbox (REST resync recovers).
- `auth-realtime` — `GET /api/v1/realtime/token` returns a Supabase-compatible HS256 JWT (`{ sub, role: "authenticated", iat, exp }`, TTL 1h); the V15 `participants_can_subscribe` RLS policy authorizes subscription to `conversation:<uuid>` iff the token's `sub` is an active participant (shadow-ban-irrespective).

This change is mobile-only plus one shared domain interface (`:core:domain`) and one new infra module (`:infra:supabase-realtime`). No Flyway migration, no backend code.

## Goals / Non-Goals

**Goals:**
- Ship the conversation-list + thread screens against the shipped REST endpoints, with the full state contract (loading / empty / error / redacted / send-blocked) per `mobile-design-system`.
- Ship live realtime append (subscribe + token + dedupe + reconnect/resync) so a two-device chat demo works without manual refresh.
- Keep the vendor Supabase SDK fenced to `:infra:*` behind a `:core:domain` interface (invariant #16); establish the first mobile realtime-consumer Pattern-Registry entry.
- Add the entry point (Home app-bar "Pesan") and the create-or-return path used to start a thread.

**Non-Goals:**
- Embedded post context cards (`embedded_*`) → `chat-embedded-posts` (D7).
- Profile "Kirim pesan" entry wiring → behind PR #245 (D5).
- Per-conversation unread badge / `last_read_at` write → not in the shipped list-conversations contract (D8).
- FCM token registration + actual push delivery of chat notifications (cross-cutting; this change only shows the permission prompt).
- The post-swap Ktor-WebSocket transport (Month 15+); message search, typing indicators, read receipts, attachments.

## Standards conformance (docs/11 — MUST)

Pattern-Registry patterns this change BUILDS ON (no deviation):
- **State holder** — route-scoped `ViewModel` + `StateFlow` + pure `UiState` projection (mirrors `GlobalTimelineViewModel` / `NearbyTimelineViewModel`; the `isInitialLoad`/`isRefreshing` split).
- **Navigation** — typed `@Serializable` `NavKey` root-stack routes registered in the saveable-config `SerializersModule` (mirrors `PostDetailRoute`).
- **Data layer** — `ApiClient` → `Repository : Flow` → sealed `Outcome` (status-driven, no fallthrough), interface-seamed for fakes.
- **Backend layering** — N/A (no backend code).

NEW Pattern-Registry entries this change REGISTERS (an explicit `tasks.md` item amends `docs/11` § Pattern Registry in this same PR):
- **Mobile realtime-consumer seam** — `ChatRealtimeSubscriber` (`:core:domain`, vendor-SDK-free) + a `:infra:*` supabase-kt implementation, consumed by a ViewModel that merges a cold `Flow<ChatMessageInbound>` into its message list with id-based dedupe and lifecycle-scoped subscribe/unsubscribe. This is the first realtime consumer on mobile; later realtime features (presence, typing) build on this entry rather than re-deriving a parallel one.

No second pattern is introduced for an already-registered concern. The supabase-kt vendor import is confined to `:infra:supabase-realtime` (invariant #16 — `VendorSdkLeakageScanRule`).

## Decisions

### D1 — Subscribe-side realtime lives in a NEW `:infra:supabase-realtime` KMP module, not in the existing `:infra:supabase`

The existing `:infra:supabase` is a **JVM-only backend** module holding the publish-side `SupabaseBroadcastChatClient` (it depends on backend-only Ktor/`-jvm` artifacts and is wired into `:backend:ktor`). The mobile subscribe side needs Android + iOS targets. Rather than multiplatform-ize the backend module (which would drag its JVM publish-side dependencies onto the mobile classpath and entangle two independently-deployed concerns), this change adds a **new** module `:infra:supabase-realtime` with `androidTarget()` + `iosArm64()` + `iosSimulatorArm64()` (mirroring `:shared:resources`' target set), depending on supabase-kt's Realtime KMP artifact. It holds the single class `SupabaseChatRealtimeSubscriber : ChatRealtimeSubscriber`.

- **(rejected) Multiplatform-ize `:infra:supabase`.** Couples the backend JVM publisher and the mobile KMP subscriber in one module; forces the backend's `-jvm` Ktor engine deps to resolve for Native targets. Clean separation (JVM publish vs KMP subscribe) is worth one extra module.
- **(rejected) Put the supabase-kt call directly in `:mobile:app`.** Violates invariant #16 (vendor SDK import outside `:infra:*`). The `:core:domain` interface + `:infra:*` impl is the required shape.

Per the auto-generated-README invariant: the new module is added to `settings.gradle.kts`, a one-line description to `dev/module-descriptions.txt`, and `dev/scripts/sync-readme.sh --write` is run (a `tasks.md` item).

### D2 — `ChatRealtimeSubscriber` domain interface in `:core:domain`; supabase-kt fenced to the infra impl

`:core:domain` gains a vendor-SDK-free interface plus the inbound model:

```kotlin
interface ChatRealtimeSubscriber {
    /** Cold flow of inbound messages for one conversation. Collecting subscribes (fetching a fresh
     *  realtime token + joining channel realtime:conversation:<id>); cancellation unsubscribes. */
    fun subscribe(conversationId: Uuid): Flow<ChatMessageInbound>
}

data class ChatMessageInbound(
    val id: Uuid,
    val conversationId: Uuid,
    val senderId: Uuid,
    val content: String?,        // null when redacted
    val createdAt: Instant,
    val redactedAt: Instant?,
)
```

The three `embedded_*` payload keys are intentionally **dropped** at the boundary (parsed-and-ignored; this change does not render embeds — D7); `redaction_reason` is never on the wire. `senderId` is carried so the ViewModel can apply own-vs-other alignment and the consumer-side shadow-ban filter (D6) without a re-fetch. The infra impl (D1) translates supabase-kt's `RealtimeChannel.broadcastFlow(...)` payloads into `ChatMessageInbound`, fetches the HS256 token via the injected `RealtimeTokenApiClient` before joining, and refreshes the token on the 1h-expiry reconnect (D4).

This **mirrors** the shipped backend `ChatRealtimeClient` publish interface (also in `:core:domain`, vendor-free) — the publish/subscribe split is symmetric and the post-swap `KtorWebSocket*` implementations replace both behind the same two interfaces.

### D3 — Two screens, two root-stack routes; the thread route carries display identity only (no PII)

`ConversationListRoute` is a parameterless `@Serializable data object` (the list is always fetched fresh). `ChatThreadRoute` is a payload-carrying `@Serializable data class`:

```kotlin
@Serializable
data class ChatThreadRoute(
    val conversationId: String,      // conversation UUID — not user PII; needed to fetch + subscribe
    val partnerUsername: String = "",   // display identity for the thread top bar
    val partnerDisplayName: String = "",
) : NavKey
```

It MUST NOT carry the partner's user UUID, message content beyond what's re-fetched, or any coordinate — the back stack persists to disk on iOS (the same PII discipline `PostDetailRoute`/`AgeGateRoute` follow). `conversationId` is a conversation identifier, not user PII, and is required to call `GET /api/v1/chat/{id}/messages` + subscribe to the channel. Both routes register in the `navSavedStateConfiguration` `SerializersModule`. Both are pushed onto the ROOT back stack (overlaying the section shell), exactly like `PostDetailRoute`. `ChatThreadRoute` is reached from a `ConversationListScreen` row tap (carrying that row's partner display fields) and from the create-or-return path (D5).

### D4 — Realtime lifecycle: token-authed subscribe, id dedupe, reconnect, REST resync, teardown

The `ChatThreadViewModel` owns the merge of three message sources into one ordered, deduplicated list keyed by message `id` (UUID):
1. **REST history** — `GET /api/v1/chat/{id}/messages` first page on entry (`isInitialLoad` skeleton), older pages on scroll-up (cursor).
2. **Optimistic sends** — appended locally on send with the server-assigned `id` from the 201 response reconciled in (a temporary client id is replaced by the server id; if the realtime echo or a resync brings the same `id` first, the dedupe collapses them).
3. **Realtime inbound** — `chatRealtimeSubscriber.subscribe(conversationId)` collected in `viewModelScope`; each `ChatMessageInbound` is merged by `id`.

Dedupe rule: a message `id` already present is **updated in place** (so a redaction arriving via realtime flips an existing row's `content` to null) rather than duplicated. Ordering is by `(createdAt, id)`.

**Token + reconnect**: the infra impl fetches a fresh HS256 token (`GET /api/v1/realtime/token`, TTL 1h) immediately before joining the channel; on a transport drop it rejoins with a freshly-fetched token (the old one may be near expiry). On every (re)subscribe the ViewModel triggers a **REST resync** of the first page and merges by id — this is the no-outbox recovery path (`chat-realtime-broadcast`: missed broadcasts are recovered via REST, not replayed). Subscription is cold: collection starts on thread entry and is cancelled (channel unsubscribed) when the route is popped / the ViewModel is cleared.

A realtime failure (token fetch fails, channel errors) NEVER breaks the screen: the thread stays usable in REST-only mode (send still works via REST; the user can pull-to-refresh to resync). The realtime error is logged (no content/token in the log) and the screen shows no error chrome for it — realtime is an enhancement over the always-available REST path.

### D5 — Entry point: Home app-bar "Pesan" action; create-or-return on demand; profile "Kirim pesan" deferred

The bottom-nav shell is Home/Notifikasi/Profil with **no Chat section** (per `mobile-home-tab-host`). The conversation list is reached via a **"Pesan" icon action on the Home brand app bar** (`Icons.Outlined.MailOutline` / chat-bubble — the Material icon set per `mobile-design-system`), pushing `ConversationListRoute` onto the root stack. This is the `mobile-home-tab-host` MODIFIED delta (the app bar currently has no trailing action). Frame 1/Frame 2 of the mockup board confirm a message entry affordance from Home.

Starting a NEW chat with a specific user (the create-or-return `POST /api/v1/conversations`) is invoked from a **"Kirim pesan" action on the profile screen** — which ships in PR #245 (`mobile-profile-screen`), not yet merged. So this change DEFERS the profile entry wiring (tracked as a follow-up to land after #245) and exposes create-or-return through the `ChatRepository` + a `ChatThreadViewModel` entry that accepts a `recipientUserId` for the future caller. The conversation list (reachable now via the app-bar action) is the in-scope entry to existing threads; the demo path is: open Pesan → tap a conversation → send/receive live.

- **(rejected) Add a 4th "Pesan" bottom-nav section.** The shipped shell + mockup fix the sections at three (Home/Notifikasi/Profil); adding a section is a larger nav change outside this scope and conflicts with #245/#246's host modifications.

### D6 — Consumer-side shadow-ban + redaction handling (defense-in-depth)

The server already filters shadow-banned senders' messages from the read path and skips their realtime publish. The client adds defense-in-depth, NOT a re-implementation: a realtime inbound whose `senderId` is the viewer's own id while the viewer is shadow-banned is the only edge the server skip can race; the consumer drops it to match the server's eventual state. Redaction is honored at render: a message with `content == null` (REST or realtime) renders the neutral `chat_message_redacted` placeholder ("*Pesan ini telah dihapus*"), never an empty bubble; the id-keyed dedupe means a redaction arriving after the original collapses onto the same row.

### D7 — Embedded posts are parsed-and-ignored, not sent

The send body is `{ content }` only — `embedded_*` fields are never sent (the backend silently ignores them anyway, but the client omits them). Inbound `embedded_*` payload keys are parsed-and-dropped at the `ChatMessageInbound` boundary. The chat-from-a-post context card + edit-history navigation is a distinct feature (`chat-embedded-posts`, depends on `post-edit-history`) — deferred with a follow-up. This keeps the change to plain-text 1:1 chat.

### D8 — No unread badge in v1 (the shipped list-conversations contract has no unread count)

The shipped `GET /api/v1/conversations` returns conversation id, `created_at`, `last_message_at`, and the partner's profile fields — it does NOT return an unread count or expose a `last_read_at` write endpoint. So the conversation list shows the partner + relative `last_message_at`, with no unread badge. The `last_read_at` column exists on `conversation_participants` but no endpoint updates or reads it for an unread count. The unread badge + `last_read_at` write is deferred to a follow-up (it needs a backend endpoint first) — surfaced as a `follow-up` issue, NOT silently dropped.

### D9 — DTOs mirror the SHIPPED wire (reconciliation)

Mobile `@Serializable` DTOs are generated from the SHIPPED backend serialization (`ConversationRoutes.kt` / `ChatRoutes.kt` / the realtime payload), NOT from a spec JSON example, to avoid the camelCase-vs-snake_case drift that bit the Nearby timeline (`mobile-nearby-timeline` D10). The realtime payload is authoritative-snake_case per `chat-realtime-broadcast` § Payload schema (9 keys). The REST DTO casing is verified field-by-field in the apply phase's pre-implementation re-check (a `tasks.md` item) against the deployed handlers; any divergence between the shipped wire and the `chat-conversations` spec JSON examples is logged as a backend `follow-up` (not fixed here — this is a mobile change), matching the Nearby precedent.

## Risks / Trade-offs

- **supabase-kt is community-maintained** (not an official Supabase library) → it is the Supabase-documented Kotlin client and the only mature KMP option; the `ChatRealtimeSubscriber` interface isolates it so a future swap (or the Month-15 Ktor-WS transport) is a single-module replacement. *Verified 2026-06-13: supabase-kt remains the canonical KMP Supabase client per the Supabase Kotlin docs + supabase-community/supabase-kt; Realtime module is KMP and requires Ktor 3.x (repo is on 3.4.3).* 
- **New library pin to a security-adjacent transport** → mitigated by (a) the HS256 token never persisting to disk (fetched per subscribe), (b) the vendor import fenced to `:infra:supabase-realtime`, (c) RLS authorization enforced server-side (the token only grants what the participant policy allows), (d) a Phase-1 version re-check pinning the current stable build + confirming Ktor/coroutines compatibility before implementation.
- **Realtime adds wall-clock/network nondeterminism** → the `UiState` projection stays pure (maps an already-merged message list + flags to state); the merge/dedupe is unit-tested with synthetic REST+realtime+optimistic inputs; the realtime transport itself is integration-smoke-verified (two-device staging run), not unit-tested against a live socket.
- **Optimistic send vs realtime echo double-render** → the id-keyed dedupe collapses the optimistic row onto the server `id` from the 201; a test covers "optimistic then echo same id ⇒ one row".
- **`mobile-home-tab-host` is modified by three concurrent PRs (#245, #246, this)** → the three deltas are disjoint requirements (Profil-section content / Following-tab content / app-bar "Pesan" action), but they touch the same spec file and likely the same `HomeScreen`/`AppShellScreen` source. Mitigation: the MODIFIED delta here is scoped to a single ADDED requirement (the app-bar action) that does not edit the section/tab requirements #245/#246 touch; the squash order is flagged so whichever lands last rebases the app-bar action onto the merged host. Surfaced in the PR body for reviewer awareness.
- **Realtime failure degrades silently to REST-only** → intentional (REST is always available; pull-to-refresh resyncs); the trade-off is that a user on a flaky connection sees messages only on refresh, which is acceptable for the demo and matches the documented no-outbox recovery model.

## Migration Plan

No data migration. Additive across `:core:domain`, a new `:infra:supabase-realtime`, `:mobile:app`, `:shared:resources`, and `gradle/libs.versions.toml`:
1. Phase-1 pre-implementation re-check: pin the current stable supabase-kt Realtime build; confirm Ktor 3.x + coroutines + Kotlin version compatibility; verify the deployed REST DTO casing.
2. `:core:domain`: add `ChatRealtimeSubscriber` + `ChatMessageInbound` (vendor-free).
3. New `:infra:supabase-realtime` KMP module: supabase-kt impl; `settings.gradle.kts` + `dev/module-descriptions.txt` + `sync-readme.sh --write`.
4. `:shared:resources`: add the chat Bahasa Indonesia strings.
5. `:mobile:app`: API clients (conversations / messages / realtime-token) + DTOs; repositories/flows + outcomes; route-scoped ViewModels + pure UiState projections; the two screens + states; the two routes + SerializersModule registration; the Home app-bar "Pesan" action; the first-send notification-permission prompt; Koin wiring.
6. Tests: pure projection tests, merge/dedupe tests, MockEngine API/repository tests, Robolectric screen tests (Release-variant exclude list), the no-hardcoded-strings grep; staging two-device realtime smoke before archive.

## Open Questions

- **Redacted-message + send-blocked copy** — `chat_message_redacted` ("*Pesan ini telah dihapus*") and the block-send banner reuse the canonical `"Tidak dapat mengirim pesan ke user ini"` (docs-verbatim); the redaction placeholder + the conversation-list `last_message_at` relative-time copy are derived BI — flagged for UX review.
- **"Pesan" app-bar icon glyph** — `MailOutline` vs a chat-bubble (`ChatBubbleOutline`); pick per the Frame 1/2 mockup measurement annex at implementation time (`mobile-ui-foundation`).
