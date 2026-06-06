## Context

`:mobile:app` ships auth (Google), age gate, location-permission flow, post creation, and the **Nearby** timeline (Mobile #5). `HomeScreen` ([`screens/home/HomeScreen.kt`](../../../mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt)) is a thin host rendering only `NearbyTimelineScreen` + the composer FAB; its KDoc forward-references the tab-bar change. Navigation is Navigation 3 (`mobile-nav-swap-to-navigation3`, #149): a root `NavDisplay` over a serializable `NavKey` back stack ([`screens/routing/NavKeys.kt`](../../../mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/NavKeys.kt), [`AppNavSerialization.kt`](../../../mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/AppNavSerialization.kt)) with per-entry ViewModel + saved-state decorators (`mobile-app-scaffold` § "NavDisplay scopes per-entry saveable state and ViewModels via entry decorators").

The backend `GET /api/v1/timeline/global` is **shipped** (`TimelineRoutes.kt` `globalTimelineRoutes` + `GlobalTimelineService`): authenticated-only, optional `cursor`, `X-Session-Id` soft-cap header, and a `GlobalResponse { posts, nextCursor, upsell }` envelope whose `GlobalPostDto` is byte-identical to `NearbyPostDto` **minus `distanceM`** (Global has no spatial filter). The project just re-entered balanced priority (all 10 mobile+admin menu items shipped); this is the next-highest-impact mobile pick — it completes the home navigation shell and consumes the unconsumed Global feed. The deferred `FOLLOW_UPS.md` entries `mobile-home-tab-host` + `mobile-timeline-empty-global-cta` park their work here.

## Goals / Non-Goals

**Goals:**
- A Nearby/Following/Global bottom-nav tab host (Material 3 `NavigationBar` + a serializable `Tab` enum, each tab's screen rendered directly under the `HomeRoute` scope; per-tab `NavDisplay` back stacks deferred — D1), the home-level composer FAB shared across tabs, and iOS-safe tab-selection serialization.
- The Global tab rendering the **live** `GET /api/v1/timeline/global` feed, mirroring the shipped Nearby plumbing and the shipped distance-less wire.
- The Nearby empty state gaining a "lihat Global" CTA (closes `mobile-timeline-empty-global-cta`).
- The Following tab as a **documented empty-state placeholder** that issues no fetch, with the deferral captured as explicit requirements.
- No re-fetch when switching tabs or round-tripping through the composer.

**Non-Goals:**
- Real Following feed (no follow-action UI exists yet → perpetually empty); Nearby radius slider; infinite scroll; relative timestamps; Nav3 adaptive multi-pane scenes; analytics-consent / notifications / profile / chat screens; guest (unauthenticated) Global access; any backend or schema change; extracting a shared timeline/post-card abstraction across feeds (revisit when the real Following feed makes it a 3-feed problem).

## Decisions

### D1 — Tab host = `NavigationBar` + serializable `Tab` enum (screens render directly under `HomeRoute`)

`HomeScreen` becomes a `Scaffold` with a Material 3 `NavigationBar` (3 items) + the home-level FAB. The body is a `when(selectedTab)` that renders the selected tab's screen **directly** (Nearby → `NearbyTimelineScreen`, Following → `FollowingPlaceholderScreen`, Global → `GlobalTimelineScreen`). `selectedTab` is a `@Serializable` `Tab` enum held in `rememberSaveable` (iOS-safe; no reflection). The tab screens compose directly under the `HomeRoute` NavEntry — there is **no** per-tab `NavDisplay`, and **no** new tab-root `NavKey`s are added.

*Rationale:* there is no intra-tab navigation in this change (no post detail / profile yet), so per-tab back stacks would be vestigial structure with nothing to push. Rendering screens directly under `HomeRoute` keeps each feed's `viewModel { }` resolving to the `HomeRoute` store (D2) — exactly how the shipped Nearby feed already resolves its VM — so no-refetch-on-tab-switch falls out for free and the shipped Nearby screen needs **no** VM-resolution change.

*Alternative (rejected): per-tab `NavDisplay` back stacks now* (the shape `FOLLOW_UPS.md` `mobile-home-tab-host` sketched). Rejected for this change because (a) it adds back-stack scaffolding with no intra-tab destination to use it, and (b) a `viewModel { }` resolved *inside* a per-tab `NavDisplay` scopes to that per-tab NavEntry, whose store is cleared on tab switch → re-fetch on every tab return, contradicting the no-refetch requirement; preserving `HomeRoute` scoping would force hoisting both feed VMs out of their screens (a needless refactor of the shipped Nearby screen). Per-tab `NavDisplay` back stacks are deferred to the first intra-tab destination (`FOLLOW_UPS.md` `mobile-home-tab-host-per-tab-backstacks`).

### D2 — Feed load-state ViewModels scoped to the `HomeRoute` NavEntry (survive tab switch + composer)

The Nearby and Global feed load-state (`NearbyTimelineViewModel`, new `GlobalTimelineViewModel`) resolve via `viewModel { }` **inside their screens**, which — because the screens compose directly under `HomeRoute` (D1) — binds them to the `HomeRoute` NavEntry ViewModel store (the root-`NavDisplay` decorator). `HomeRoute` stays in the root back stack across both tab switches (selection is host state) and the composer round-trip (the composer is pushed *above* `HomeRoute` — D3), so the feed VMs are retained: switching away from a feed tab and back, or opening the composer and returning, re-reads the same VM with its loaded state — no re-fetch.

*Rationale:* this is a faithful extension of the shipped `mobile-nearby-timeline` requirement ("Nearby feed load state is scoped to the Home NavEntry … survives the composer round-trip") — the scoping mechanism is **unchanged**; it now additionally survives tab switches, and a second (Global) feed VM joins it under the same entry.

*Alternative (rejected):* per-tab NavEntry scoping — see D1; an inactive tab's store would be cleared on switch, forcing a re-fetch.

### D3 — Composer FAB stays at the home level and pushes onto the ROOT back stack

The FAB lives in `HomeScreen` (one composer affordance across all three tabs — existing design D6) and appends `PostCreationRoute` to the **root** back stack (above `HomeRoute`), so the composer overlays the entire surface including the tab bar. This is exactly today's behavior (`PostCreationRoute` already pushes onto the root stack); only `HomeScreen`'s body changes (tab host instead of Nearby directly).

*Alternative (rejected):* push the composer into the active tab's back stack, or one FAB per tab. Both fragment a single cross-tab affordance and would render the composer under the tab bar.

### D4 — Global feed mirrors the Nearby seam; DTOs mirror the SHIPPED distance-less wire

Add `GlobalTimelineApiClient` + `GlobalTimelineFlow` + `GlobalTimelineRepository` + `GlobalTimelineUiState` + `GlobalTimelineViewModel` + `GlobalTimelineScreen` as parallels of the Nearby files. The request is `GET /api/v1/timeline/global` with optional `cursor` and the `X-Session-Id` header from the **existing** singleton `SessionIdProvider` (reused, not duplicated) — and **no** `lat`/`lng`/`radius_m` (Global has no spatial filter). The response DTOs mirror the shipped `GlobalPostDto`/`GlobalResponse` (`TimelineRoutes.kt`): per-post bare camelCase `id`/`authorUserId`/`content`/`latitude`/`longitude`/`createdAt`, `@SerialName` snake `city_name`/`liked_by_viewer`/`reply_count`, **and NO `distanceM`**; top-level bare `nextCursor` + optional `upsell { soft, hard }`. The Global card renders `city_name` + `content` + `created_at` + read-only `liked_by_viewer`/`reply_count` and **no distance string** (Global has no distance). Outcome mapping is HTTP-status-driven exactly like Nearby (200→`Loaded`; 401→shipped `Auth` plugin; 400→retryable `Error`; 5xx/IO→`NetworkError`). PII discipline: `authorUserId` (UUID) and raw `latitude`/`longitude` are never rendered or logged.

*Rationale:* copy-adapt of a proven seam is the lowest-risk way to add a second feed and matches the project's per-timeline-capability pattern (Nearby/Following/Global are separate backend services too). DTOs come from the shipped code, not the stale snake_case spec JSON examples (the casing-drift trap that bit Nearby).

*Alternative (rejected):* extract a shared `TimelineFeed`/`PostCard` abstraction now. Premature with two near-identical feeds; it would invasively re-open the shipped Nearby capability. Revisit when the real Following feed makes it a genuine 3-feed deduplication.

### D5 — Default authenticated tab = Nearby

The tab host opens on **Nearby** for authenticated users (preserving today's `HomeRoute`→Nearby landing). `docs/03-UX-Design.md` "Default tab: Global" describes the **guest, pre-login** first-open — that flow is deferred (guest Global needs Redis guest rate-limit infra, `docs/02-Product.md` § Global Timeline Status), so it does not govern the authenticated default. "*Nearby and Following are home*" (`docs/02-Product.md`) supports Nearby-as-home.

*Low-stakes; flagged in Open Questions* — trivial to flip to Global if preferred.

### D6 — Following tab = documented empty-state placeholder, fetch deferred (explicit requirements)

The Following tab renders the documented empty-state copy ("*Following empty → arahkan ke Nearby/Global*", `docs/03-UX-Design.md` § Empty State) and **issues no `GET /api/v1/timeline/following` request**. The deferral is captured as explicit spec requirements (positive: placeholder renders via `Res.string`; negative-guard: no following-timeline fetch / no following API client is wired) so the follow-up `mobile-following-timeline-screen` has a requirement to MODIFY (per the project's "capture deferred behaviors as explicit spec requirements" convention).

*Rationale:* there is no follow-action UI on mobile, so the real feed would always be empty — shipping a live-but-always-empty feed is wasted surface and an untestable end-to-end path. The placeholder is a real, documented state, not a dead control.

### D7 — No new dependencies, no Flyway migration

Navigation 3, Koin, the Ktor KMP client, `:shared:distance` (unused by Global — no distance), and Compose Multiplatform Resources are all already pinned and actively used. No `gradle/libs.versions.toml` change. The Nav3 adaptive artifact (`material3-adaptive-navigation3`) is deliberately **not** added — single-pane phone MVP (`mobile-nav3-adaptive-scenes` follow-up owns adaptive scenes). The backend endpoint exists → zero migrations.

### D8 — New Bahasa Indonesia strings in `:shared:resources`

New `Res.string` entries: three tab labels, the Following-placeholder copy, the "lihat Global" CTA, and Global loading/empty copy. Reuse existing strings where they already fit (`signin_error_network`, `cta_retry`, `timeline_loading` for the Global error/retry/loading states; `timeline_empty_nearby` for the Nearby empty body). No hardcoded UI string literals anywhere (CLAUDE.md mobile-strings invariant; enforced by the existing grep-based guard).

## Risks / Trade-offs

- **Feed-VM lifecycle across tab switches is subtle** → D1/D2 keep both feed screens directly under `HomeRoute`, so `viewModel { }` resolves to the `HomeRoute` store and survives tab switches (and the shipped Nearby VM resolution is unchanged); a commonTest asserts no re-fetch across tab switch + composer round-trip.
- **Per-tab `NavDisplay` back stacks are deferred** (no intra-tab nav in this change) → accepted: building them now is vestigial structure with nothing to push; they land with the first intra-tab destination (`FOLLOW_UPS.md` `mobile-home-tab-host-per-tab-backstacks`). The tab host still delivers the bottom-nav shell + shared FAB + per-tab feed-state preservation.
- **Global DTO casing drift** (copying the stale snake_case spec JSON instead of the shipped wire) → mitigated by D4 + a negative regression test (snake_case-only body must NOT populate the camelCase fields), mirroring the Nearby guard.
- **Global "empty" is essentially unreachable** (all-Indonesia feed) → still specified as a state for completeness; the realistic edge is the loading skeleton.
- **Scope is on the larger side** (tab host + a full second feed + two MODIFIED capabilities) → the pieces are genuinely inseparable (a tab host can't ship an empty Global tab when the data is ready, and a Global screen needs a tab to live in); kept lean by reusing `SessionIdProvider` and deferring the shared-card extraction.

## Migration Plan

Pure additive mobile change, no runtime/data migration. Ships behind the normal mobile build; no feature flag. Rollback = revert the PR (no schema/backend state touched).

## Open Questions

- **Default authenticated tab (D5): RESOLVED → Nearby** (proposal review, 2026-06-06). The authenticated default is Nearby (preserves the current landing; "Nearby and Following are home"). The guest pre-login "Default tab: Global" remains deferred with the guest flow.
- **Navigation model (D1): RESOLVED → Approach A** (proposal review, 2026-06-06). Tab selection via a serializable `Tab` enum with screens rendered directly under `HomeRoute`; per-tab `NavDisplay` back stacks deferred to the first intra-tab destination.
