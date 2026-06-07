## Context

`:mobile:app`'s authenticated shell today **is** the three-feed tab host (`mobile-home-tab-host`): the bottom `NavigationBar` items are literally Nearby / Following / Global, and there is no top bar. That leaves nowhere for Notifications, Profile, Search, or Chat to live as first-class surfaces — they'd have to be top-bar icons or pushed routes. Meanwhile the shipped `in-app-notifications` read API is unused on mobile:

- `GET /api/v1/notifications?cursor=&limit=&unread=` → `{ items: NotificationDto[], next_cursor: String? }`
- `GET /api/v1/notifications/unread-count` → `{ count: Long }`
- `PATCH /api/v1/notifications/{id}/read` → `204` (or `404 not_found`)
- `PATCH /api/v1/notifications/read-all` → `{ marked_read: Int }`

The wire is fixed by `backend/ktor/src/main/kotlin/id/nearyou/app/notifications/NotificationRoutes.kt` and **diverges from the `in-app-notifications` spec prose** (D2). This change does two things in one: (1) restructures the bottom nav into top-level **sections** (Home / Notifikasi / Profil) with the feeds becoming a top tab row inside Home, and (2) delivers the Notifikasi section's `NotificationsScreen` (mirroring the shipped `GlobalTimeline` seam) with its unread badge — which makes `docs/03-UX-Design.md`'s "unread badge count in the tab bar" literally true.

**Concurrency:** the `mobile-post-detail-screen` session (#159) is in flight and also modifies `mobile-home-tab-host` (+ `HomeScreen`/`AppEntryProvider`). #159 is **additive** — it hoists an `onOpenPost(...)` lambda and pushes a root-stack `PostDetailRoute`, explicitly keeping the no-per-tab-back-stack model. This restructure is designed to **absorb** that (D9).

## Goals / Non-Goals

**Goals:**
- Restructure the bottom nav into a section shell (Home / Notifikasi / Profil); move the three feeds to a top `PrimaryTabRow` inside Home; preserve the shipped per-feed `HomeRoute`-scoped no-re-fetch behavior.
- A `NotificationsScreen` (loading / content / empty / error + mark-read + mark-all-read + pull-to-refresh) hosted by the Notifikasi section, mirroring the `GlobalTimeline` seam.
- Correct parsing of the **shipped** wire DTOs (opaque `next_cursor`, `{count}`, `{marked_read}`, `204`/`404 not_found`) — guarded by a negative-regression test.
- Unread badge on the Notifikasi nav item from `unread-count`.
- Full test coverage (Robolectric shell + screen tests; commonTest projection/ViewModel/MockEngine; iOS flow test).

**Non-Goals (deferred — each an explicit spec requirement + `FOLLOW_UPS.md`):**
- **Deep-link tap-through** to post/reply/profile (blocked on #159's `PostDetailScreen` AND a backend `GET /api/v1/posts/{id}` by-id endpoint — both absent).
- **Actor-username rendering** (list DTO carries only `actor_user_id` UUID — needs a backend `visible_users` join).
- **Infinite scroll / load-more** (`next_cursor` parsed, not consumed).
- **Live/polling unread badge** updates (one-shot fetch only).
- **Real Profil surface** — the Profil section is a placeholder this change; the profile/settings screen is a separate future change.
- Per-tab `NavDisplay` back stacks (still deferred — `FOLLOW_UPS.md` `mobile-home-tab-host-per-tab-backstacks`).
- Any backend change, Flyway migration, or new library pin. No rate-limit states (the notifications read endpoints carry no per-endpoint rate limit / `upsell`).

## Decisions

### D1 — Mirror the GlobalTimeline seam for the notifications surface
`NotificationsApiClient` → `NotificationsRepository` (HTTP-status → sealed `NotificationsOutcome`) behind a `NotificationsFlow` interface → a NavEntry-scoped `NotificationsViewModel` → a Compose-free `NotificationsUiState` + pure `notificationsUiState(outcome, inFlight)` projection → `NotificationsScreen`. Koin singletons; `single<NotificationsFlow> { get<NotificationsRepository>() }` lets a `FakeNotificationsFlow` drive screen tests.
- *Why:* proven across Nearby/Global; the Compose-free projection makes outcome→state mapping deterministically unit-testable. *Alternative — bespoke inline-state screen:* rejected (untestable without composing UI).

### D2 — DTOs are generated from the SHIPPED wire, NOT the `in-app-notifications` spec
The spec prose is stale vs its own `NotificationRoutes.kt`. Mobile DTOs MUST match the shipped code:

| Concern | Shipped wire (source of truth) | Stale spec prose |
|---|---|---|
| list unread filter | `unread=true` | `unread_only=true` |
| `next_cursor` | **opaque base64url** token | "ISO8601 timestamp" |
| `NotificationDto.body_data` | non-null `JsonElement` (defaults `{}`) | nullable |
| unread-count body | `{ count: Long }` | `{ unread_count }` |
| read-all body | `{ marked_read: Int }` | `{ marked }` |
| mark-read success | `204 No Content` | `200 no body` |
| mark-read not-found | `404 { code: "not_found" }` | `404 notification_not_found` |
| malformed cursor | `400 { code: "invalid_cursor" }` | `400 invalid_request` |
| `limit` overflow | clamped `[1,50]` (no error) | `400 invalid_request` |

A commonTest negative-regression fixture proves the shipped keys parse and the stale shapes (`unread_count`, `marked`) do NOT populate.
- *Why:* exact precedent — `mobile-global-timeline` reconciled the same trap (PR #128/#132). The spec staleness is logged to `FOLLOW_UPS.md` (bucket b); NOT fixed here.

### D3 — Bottom nav becomes a top-level section shell; feeds become a top tab row in Home
A new `AppShellScreen` renders the `Scaffold` + bottom `NavigationBar` of sections **Home / Notifikasi / Profil** (serializable `Section` enum in `rememberSaveable`, default Home). `HomeScreen` is reworked to be the Home section's content: a `PrimaryTabRow` of the three feeds (serializable `Tab` enum, default Nearby) over the feed body — the feeds are no longer the bottom bar. The composer FAB stays on the Home section.
- *Why:* the conventional, scalable app-shell pattern (sections in the bottom bar, feed filters as top tabs) — matches `docs/03-UX-Design.md` "unread badge count in the tab bar" and leaves room for Profile/Search/Chat as future sections. *Alternatives:* (a) keep feeds as the bottom bar + a top-bar bell for notifications — rejected (doesn't scale; the badge wouldn't be "in the tab bar"); (b) a 4th feed-peer bottom item for notifications — rejected (notifications isn't a feed). *Scope note:* this MODIFIES the shipped `mobile-home-tab-host`; the capability name is retained (renaming a shipped capability spec dir is out of scope) with the spec body documenting the broadened "app shell" role.

### D4 — Actor-username rendering deferred: generic-actor copy + body_data excerpts
The list DTO returns `actor_user_id` (a UUID) but **no username**. Rows render type-keyed Bahasa Indonesia copy with a **generic actor** + `body_data` excerpts; the UUID is NEVER rendered or logged. `chat_message` (docs/03 "Pesan baru dari {username}") also drops to generic copy pending enrichment. The screen MUST tolerate all 13 enum values + an unknown/future `type` (generic fallback, no crash) + a missing excerpt key.
- *Why:* rendering a UUID is a PII/UX non-starter; generic copy matches FCM's "Seseorang …" masking fallback (verified safe vs `fcm-push-dispatch` `ActorUsernameLookup` over `visible_users`). Block/self/shadow-ban suppression happens **write-time** in `NotificationEmitter`, so the list contains only already-permitted rows — the client renders every returned row verbatim safely (no client-side re-filtering). *Alternative — backend list-endpoint actor-username join:* out of scope (backend change) → `FOLLOW_UPS.md` `mobile-notifications-actor-username-enrichment`.

### D5 — Deep-link tap-through deferred: tap marks read only
Tapping a row issues `PATCH …/{id}/read` (optimistic flip; `204` → success, `404` → no-op, other failure → revert) but does NOT navigate. Negative-guard: no navigation to a post/reply/profile route is wired from a row.
- *Why:* the deep-link targets don't exist — blocked on BOTH the in-flight `mobile-post-detail` screen (#159) AND a backend `GET /api/v1/posts/{id}` by-id endpoint (#159's proposal explicitly assigns that endpoint to "the future notifications change"). → `FOLLOW_UPS.md` `mobile-notifications-deep-link-targets` (depends on #159 + the backend by-id endpoint).

### D6 — Unread badge on the Notifikasi nav item; one-shot
The Notifikasi `NavigationBarItem` shows a Material 3 `Badge` when `unread-count > 0`, fetched on shell (re)composition/resume and refreshed when leaving the Notifikasi section. No live/push/polling updates.
- *Why:* the badge belongs on the bottom-nav item (now that notifications is a section) — this is exactly `docs/03`'s "unread badge count in the tab bar", so the restructure **resolves** the earlier badge-placement open question. Live updates need a refresh strategy (polling cost / FCM-driven invalidation) out of scope → `FOLLOW_UPS.md` `mobile-notifications-live-unread-badge`.

### D7 — `NotificationsViewModel` is shell-NavEntry-scoped (no re-fetch on section switch)
Resolved via `viewModel { }` under the shell's NavEntry decorator, mirroring the `HomeRoute`-scoped feed ViewModels. It constructs on the first composition of the Notifikasi section, `loadFirstPage()` once, and survives section switches without re-fetch. Freshness comes from pull-to-refresh + the badge.
- *Why:* consistent with the feeds' no-re-fetch-on-switch invariant (D3); a section switch shouldn't reload the inbox. *Alternative — re-fetch on every Notifikasi entry:* rejected (needs a section-entry hook + diverges from the feed scoping model; pull-to-refresh covers manual freshness).

### D8 — Outcome + state mapping (no rate-limit states; optimistic mark-read with revert)
`NotificationsOutcome` (sealed, HTTP-status-driven, no fallthrough): `200 → Loaded(items, nextCursor)`; `400 → Error` (retryable, diagnostic logs **status/type only, no PII/body**); `5xx`/network-IO `→ NetworkError`; `401` delegated to the shipped `Auth` `refreshTokens`. `NotificationsUiState`: `Loading` / `Content(rows)` / `Empty` / `Error`(+retry). Mark-read/mark-all-read are optimistic local mutations: `204`/`404` keep the read flip; other transport failure reverts it; no full re-fetch.

### D9 — Absorb the in-flight #159 (`mobile-post-detail-screen`)
#159 adds (to the current host) an `onOpenPost(...)` hoisted lambda wired at the `AppEntryProvider` call site to a root-stack `PostDetailRoute` push (no per-tab back stacks). This restructure keeps that mechanism: each feed top-tab still hoists `onOpenPost(...)`, and the shell/`AppEntryProvider` still appends `PostDetailRoute` to the root stack. So the two changes are reconcilable, not contradictory. Merge ordering: whichever squash-merges second rebases + reconciles `HomeScreen`/`AppEntryProvider` + the `mobile-home-tab-host` spec delta, per `openspec/project.md` § "Archive commits touching shared specs" (preserve #159's `onOpenPost` ADDED requirement alongside this restructure's MODIFIED requirements).
- *Why:* #159 deliberately stayed shallow (root-stack pushes), which makes absorption mechanical. *Alternative — sequence behind #159 (Path C):* viable but the user chose to bundle (Path B); this decision records how to keep them compatible.

## Risks / Trade-offs

- **#159 merge collision** (both touch `HomeScreen`/`AppEntryProvider` + the `mobile-home-tab-host` spec) → reconcilable per D9; the restructure absorbs #159's additive `onOpenPost`. Coordinate squash-merge order; the second rebases. The deeper risk is *runtime* nav-model drift if #159 changes scope — re-check #159 at `/opsx:apply` time.
- **Scope size** (restructure + notifications in one change) → larger implementation + review surface than a single screen. Mitigation: the notifications seam is a verbatim copy-adapt of `GlobalTimeline`; the restructure is mechanical (sections + top tabs); both have explicit spec scenarios.
- **Generic-actor copy reads impersonally** ("Seseorang menyukai…") → acceptable v1 (matches FCM masking); username enrichment is a tracked follow-up.
- **Stale `in-app-notifications` spec** → mobile targets the shipped wire + a negative-regression test pins it; spec reconciliation is a separate bucket-(b) follow-up.
- **Optimistic mark-read divergence** (PATCH fails after the row flipped) → revert on non-204/404; the next refresh reconciles (D8) — covered by a spec scenario.
- **Badge staleness** (one-shot) → bounded by refresh-on-leave; live updates deferred.

## Migration Plan

Pure additive + restructure mobile change. No schema/migration, no backend deploy, no new dependency. Rollback = revert the commits; nothing else consumes the new types. **Merge coordination with #159 required** (D9). Verify via `:mobile:app:testDevDebugUnitTest` + `:mobile:app:testDevReleaseUnitTest` + `:mobile:app:iosSimulatorArm64Test` + root `detekt`/`ktlintCheck`.

## Open Questions

1. **Badge placement — RESOLVED by the restructure.** With Notifikasi now a bottom-nav section, the unread badge sits on its `NavigationBarItem`, which literally satisfies `docs/03-UX-Design.md`'s "unread badge count in the tab bar." No docs amendment needed (the restructure aligns to the doc rather than diverging from it).
2. **Bottom-nav item set.** This change ships **Home / Notifikasi / Profil** (Profil a placeholder). Search (Premium) and Chat are intentionally NOT sections yet (future changes add them); confirm the 3-item set is the desired MVP shell during review.
3. **Capability naming.** `mobile-home-tab-host` is retained though it now describes the whole shell; a capability rename to `mobile-app-shell` is a possible later cleanup (out of scope here).
