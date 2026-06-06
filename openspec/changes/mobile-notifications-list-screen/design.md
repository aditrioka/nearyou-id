## Context

`:mobile:app` ships the auth → age-gate → location → Nearby/Global feeds → post-composer flow, but has no notifications surface. The backend `in-app-notifications` capability shipped the read API months ago and is unused on mobile:

- `GET /api/v1/notifications?cursor=&limit=&unread=` → `{ items: NotificationDto[], next_cursor: String? }`
- `GET /api/v1/notifications/unread-count` → `{ count: Long }`
- `PATCH /api/v1/notifications/{id}/read` → `204` (or `404 not_found`)
- `PATCH /api/v1/notifications/read-all` → `{ marked_read: Int }`

The wire shape is fixed by `backend/ktor/src/main/kotlin/id/nearyou/app/notifications/NotificationRoutes.kt` and **diverges from the `in-app-notifications` spec prose** (see D2). The shipped `:mobile:app` already has a battle-tested feed seam — `GlobalTimelineApiClient`/`Repository`/`Flow`/`ViewModel` + `GlobalTimelineUiState` projection + `GlobalTimelineScreen` + the test triad — that this change copy-adapts.

Concurrency note: a sibling `/next-change` session is building `mobile-post-detail-screen` (which also touches `HomeScreen.kt` to make post cards tappable and will introduce per-tab back stacks). This design deliberately keeps the notifications entry-point on the **root** back stack to avoid coupling to that work (D3).

## Goals / Non-Goals

**Goals:**
- A `NotificationsScreen` rendering the caller's notification feed (loading / content / empty / error states), backed by the shipped read API, mirroring the `GlobalTimeline` seam exactly.
- Correct parsing of the **shipped** wire DTOs (opaque `next_cursor`, `{count}`, `{marked_read}`, `204`/`404 not_found`) — guarded by a negative-regression test.
- A reachable, testable entry-point: a `HomeScreen` bell → `NotificationsRoute` on the root back stack + a one-shot unread badge.
- Mark-read on row tap + mark-all-read; pull-to-refresh.
- Full test triad (Robolectric screen test + commonTest projection/MockEngine + iOS flow test).

**Non-Goals (deferred, each an explicit spec requirement + `FOLLOW_UPS.md`):**
- **Deep-link tap-through** to the target post/reply/profile (destination screens unbuilt).
- **Actor-username rendering** (list DTO carries only `actor_user_id` UUID — needs a backend enrichment).
- **Infinite scroll / load-more** (`next_cursor` parsed but not consumed).
- **Live/polling unread badge** updates (badge is one-shot).
- Any backend change, Flyway migration, or new library pin.
- Rate-limit states — the notifications read endpoints carry **no per-endpoint rate limit / `upsell` object** on the wire (read-side throttling lives at the session/hourly layer and surfaces as ordinary 200s), so this screen has no hard/soft-limit states (unlike the timeline screens).

## Decisions

### D1 — Mirror the GlobalTimeline seam file-for-file
`NotificationsApiClient` (Ktor `HttpClient`) → `NotificationsRepository` (maps HTTP result → sealed `NotificationsOutcome`) bound behind a `NotificationsFlow` interface → a NavEntry-scoped `NotificationsViewModel` exposing the outcome + in-flight flag → a Compose-free `NotificationsUiState` + pure projection `notificationsUiState(outcome, inFlight)` → `NotificationsScreen`. All Koin singletons in `mobileModule`; `single<NotificationsFlow> { get<NotificationsRepository>() }` lets a `FakeNotificationsFlow` drive screen tests.
- *Why:* proven across Nearby/Global/(home host); the Compose-free projection makes the outcome→state mapping deterministically unit-testable. *Alternative — bespoke screen with inline state:* rejected (inconsistent, untestable without composing UI).

### D2 — DTOs are generated from the SHIPPED wire, NOT the `in-app-notifications` spec
The `in-app-notifications` spec prose is stale vs its own `NotificationRoutes.kt`. Mobile DTOs MUST match the shipped code:

| Concern | Shipped wire (source of truth) | Stale spec prose (do NOT follow) |
|---|---|---|
| list unread filter param | `unread=true` | `unread_only=true` |
| `next_cursor` | **opaque base64url** token (pass back verbatim) | "ISO8601 timestamp" |
| `NotificationDto.body_data` | non-null `JsonElement` (defaults `{}`) | nullable |
| unread-count body | `{ count: Long }` | `{ unread_count }` |
| read-all body | `{ marked_read: Int }` | `{ marked }` |
| mark-read success | `204 No Content` | `200 no body` |
| mark-read not-found | `404 { code: "not_found" }` | `404 notification_not_found` |
| malformed cursor | `400 { code: "invalid_cursor" }` | `400 invalid_request` |
| `limit` overflow | clamped to `[1,50]` (no error) | `400 invalid_request` |

`NotificationDto` wire names: bare `id`, `type`; `@SerialName` snake `actor_user_id` (nullable), `target_type` (nullable), `target_id` (nullable), `body_data` (object), `created_at`, `read_at` (nullable). A commonTest negative-regression fixture proves the shipped keys parse and that the stale-spec shapes (`unread_count`, `marked`) would NOT populate. The shared `Json` (`ignoreUnknownKeys`, `explicitNulls=false`) is reused.
- *Why:* exact precedent — `mobile-global-timeline` reconciled the same stale-snake_case trap (PR #128/#132). *Alternative — follow the spec:* rejected (silent parse failures at runtime). The spec staleness itself is logged to `FOLLOW_UPS.md` for a separate docs reconciliation (bucket b); it is NOT fixed by this mobile change.

### D3 — Entry-point: a HomeScreen top-bar bell → `NotificationsRoute` on the ROOT back stack
A bell `IconButton` (icon + `contentDescription` via `stringResource`) at the `HomeScreen` level invokes an injected `onOpenNotifications` lambda that appends a new `NotificationsRoute` `NavKey` to the **root** back stack (above `HomeRoute`), exactly as the composer FAB pushes `PostCreationRoute`. `NotificationsScreen` thus overlays the tab bar.
- *Why:* root-stack push reuses the established home-affordance pattern and is **independent of the deferred per-tab back stacks** (`FOLLOW_UPS.md` `mobile-home-tab-host-per-tab-backstacks`), which the in-flight `mobile-post-detail-screen` session will introduce. *Alternatives:* (a) a 4th bottom-nav tab — rejected (the home host is canonically exactly 3 tabs per `mobile-home-tab-host`); (b) per-tab back-stack push — rejected (depends on unbuilt per-tab back stacks). *Note:* this adds a top bar / action region to `HomeScreen.kt`, a small overlap with the post-detail session's card-tap change — different regions, trivial rebase; entry-point requirement kept inside the new capability (not a `mobile-home-tab-host` delta) to avoid an archive-time spec conflict.

### D4 — Actor-username rendering deferred: generic-actor copy + body_data excerpts
The list DTO returns `actor_user_id` (a UUID) but **no username**. Rows render type-keyed Bahasa Indonesia copy with a **generic actor** and `body_data` excerpts; the UUID is NEVER rendered or logged. Type→copy mapping (all via `Res.string`):

| `type` | v1 copy (generic actor) | source |
|---|---|---|
| `post_liked` | "Seseorang menyukai postingan kamu" (+ `post_excerpt`) | `body_data.post_excerpt` |
| `post_replied` | "Ada balasan baru di postingan kamu" (+ `reply_excerpt`) | `body_data.reply_excerpt` |
| `followed` | "Seseorang mulai mengikuti kamu" | — |
| `post_auto_hidden` | "Salah satu postingan kamu disembunyikan untuk ditinjau tim moderasi." | system |
| `chat_message` | "Kamu menerima pesan baru" | `body_data.preview` |
| (8 reserved types) + any unknown | a safe generic fallback ("Notifikasi baru") | — |

The screen MUST tolerate all 13 enum values **and** an unknown/future `type` (render the generic fallback; never crash).
- *Why:* rendering a UUID is a PII/UX non-starter; generic copy matches FCM's "Seseorang …" masking fallback and is honest for v1. *Alternative — backend list-endpoint actor-username join* (mirroring `fcm-push-dispatch`'s `ActorUsernameLookup` over `visible_users`): out of scope (backend change) → `FOLLOW_UPS.md` `mobile-notifications-actor-username-enrichment`.

### D5 — Deep-link tap-through deferred: tap marks read only
Tapping a row issues `PATCH /api/v1/notifications/{id}/read` (optimistically flips the row to read; `204` → success, `404` → already-read/not-owned no-op) but does NOT navigate anywhere. Negative-guard requirement: no navigation to a post/reply/profile route is wired from a notification row.
- *Why:* the deep-link targets (post-detail, profile) don't exist yet (post-detail in flight separately; profile unbuilt). Modeled as positive (tap→read) + negative-guard so the follow-up has a requirement to MODIFY (per the `mobile-home-tab-host` Following-placeholder precedent). → `FOLLOW_UPS.md` `mobile-notifications-deep-link-targets`.

### D6 — Unread badge: one-shot, no polling
`HomeScreen` fetches `GET /api/v1/notifications/unread-count` on (re)composition/resume and shows a badge on the bell when `count > 0`. It refreshes when returning from `NotificationsScreen` (the user likely read some). No live/push/polling updates.
- *Why:* scope discipline — live updates need a refresh strategy (polling cost or FCM-driven invalidation) out of scope for v1. *Alternative — defer the badge entirely:* considered, but the unread signal is the point of a notifications entry-point and the one-shot fetch is cheap; the live-update refinement is the deferral, not the badge itself.

### D7 — `NotificationsViewModel` scoped to the `NotificationsRoute` NavEntry
Unlike the feed tabs (scoped to `HomeRoute` so they survive tab switches without re-fetch), `NotificationsScreen` is its own root-stack destination; its ViewModel scopes to the `NotificationsRoute` NavEntry (via the `rememberViewModelStoreNavEntryDecorator` per `mobile-app-scaffold`). `loadFirstPage()` runs once on construction; pull-to-refresh + error-retry re-fetch.
- *Why:* a notifications inbox SHOULD show fresh data each time it's opened — a fresh fetch on push is desired, not a bug. *Alternative — HomeRoute-scoped survival:* rejected (notifications isn't a persistent tab; staleness on re-open would be wrong).

### D8 — Outcome + state mapping (simpler than the timeline; no rate-limit states)
`NotificationsOutcome` (sealed, HTTP-status-driven, no generic fallthrough): `200 → Loaded(items, nextCursor)`; `400 (invalid_cursor — not expected on first page) → Error` (retryable, logged); `5xx`/network-IO `→ NetworkError`; `401` delegated to the shipped `Auth` `refreshTokens` plugin (NOT reimplemented). `NotificationsUiState`: `Loading` / `Content(rows)` / `Empty` ("Belum ada notifikasi") / `Error` (+ retry control). Mark-read/mark-all-read are side-effect actions that re-derive state from the local list (optimistic) without a full re-fetch.

## Risks / Trade-offs

- **`HomeScreen.kt` overlap with the in-flight `mobile-post-detail-screen` session** → keep the bell/top-bar edit minimal and self-contained; whichever lands first, the other rebases trivially (different regions). Entry-point spec requirement kept in the new capability (no `mobile-home-tab-host` delta) so the two changes don't conflict on a shared spec file at archive time (per `openspec/project.md` § "Archive commits touching shared specs").
- **Generic-actor copy may read impersonally** ("Seseorang menyukai…") → acceptable v1 (matches FCM masking); username enrichment is a tracked follow-up.
- **Stale `in-app-notifications` spec** → mobile targets the shipped wire + a negative-regression test pins it; spec reconciliation is a separate bucket-(b) follow-up.
- **Optimistic mark-read divergence** (PATCH fails after the row was flipped) → on a non-204/404 transport failure, revert the optimistic flip and surface no blocking error (the next refresh reconciles); keep it simple.
- **Badge staleness** (one-shot) → bounded by refresh-on-return; live updates deferred with user-visible expectation that the badge updates on app/screen transitions, not in real time.

## Migration Plan

Pure additive mobile change. No schema/migration, no backend deploy, no new dependency. Rollback = revert the commits; nothing else consumes the new types. Verify via `:mobile:app:testDevDebugUnitTest` + `:mobile:app:testDevReleaseUnitTest` + `:mobile:app:iosSimulatorArm64Test` + root `detekt`/`ktlintCheck`.

## Open Questions

1. **Badge placement** — `docs/03-UX-Design.md` says "Unread badge count in the **tab bar**," but this design places a bell + badge in a HomeScreen **top bar** (the 3-tab bottom bar is canonical and has no notifications slot). The top-bar bell is the lower-risk fit; flagged for proposal review to confirm vs. a bottom-bar badge variant.
2. **Badge inclusion vs. split** — the one-shot badge is bundled here; if review prefers a tighter "screen-only" first cut, the badge (D6) is the clean split point.
