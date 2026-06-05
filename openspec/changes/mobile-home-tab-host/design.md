## Context

`:mobile:app` ships auth (Google), age gate, location-permission flow, post creation, and the **Nearby** timeline (Mobile #5). `HomeScreen` ([`screens/home/HomeScreen.kt`](../../../mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt)) is a thin host rendering only `NearbyTimelineScreen` + the composer FAB; its KDoc forward-references the tab-bar change. Navigation is Navigation 3 (`mobile-nav-swap-to-navigation3`, #149): a root `NavDisplay` over a serializable `NavKey` back stack ([`screens/routing/NavKeys.kt`](../../../mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/NavKeys.kt), [`AppNavSerialization.kt`](../../../mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/AppNavSerialization.kt)) with per-entry ViewModel + saved-state decorators (`mobile-app-scaffold` § "NavDisplay scopes per-entry saveable state and ViewModels via entry decorators").

The backend `GET /api/v1/timeline/global` is **shipped** (`TimelineRoutes.kt` `globalTimelineRoutes` + `GlobalTimelineService`): authenticated-only, optional `cursor`, `X-Session-Id` soft-cap header, and a `GlobalResponse { posts, nextCursor, upsell }` envelope whose `GlobalPostDto` is byte-identical to `NearbyPostDto` **minus `distanceM`** (Global has no spatial filter). The project just re-entered balanced priority (all 10 mobile+admin menu items shipped); this is the next-highest-impact mobile pick — it completes the home navigation shell and consumes the unconsumed Global feed. The deferred `FOLLOW_UPS.md` entries `mobile-home-tab-host` + `mobile-timeline-empty-global-cta` park their work here.

## Goals / Non-Goals

**Goals:**
- A Nearby/Following/Global bottom-nav tab host with **per-tab Navigation-3 back stacks**, the home-level composer FAB shared across tabs, and iOS-safe back-stack serialization.
- The Global tab rendering the **live** `GET /api/v1/timeline/global` feed, mirroring the shipped Nearby plumbing and the shipped distance-less wire.
- The Nearby empty state gaining a "lihat Global" CTA (closes `mobile-timeline-empty-global-cta`).
- The Following tab as a **documented empty-state placeholder** that issues no fetch, with the deferral captured as explicit requirements.
- No re-fetch when switching tabs or round-tripping through the composer.

**Non-Goals:**
- Real Following feed (no follow-action UI exists yet → perpetually empty); Nearby radius slider; infinite scroll; relative timestamps; Nav3 adaptive multi-pane scenes; analytics-consent / notifications / profile / chat screens; guest (unauthenticated) Global access; any backend or schema change; extracting a shared timeline/post-card abstraction across feeds (revisit when the real Following feed makes it a 3-feed problem).

## Decisions

### D1 — Tab host = `NavigationBar` over per-tab Nav3 back stacks (nested `NavDisplay`s)

`HomeScreen` becomes a `Scaffold` with a Material 3 `NavigationBar` (3 items) + the home-level FAB. The body renders the **selected tab's** content through per-tab Navigation-3 back stacks: a `rememberSaveable` map `Tab → NavBackStack` (one saveable `NavKey` list per tab, seeded with that tab's root key), each rendered by its own `NavDisplay` carrying the same `rememberViewModelStoreNavEntryDecorator()` + `rememberSavedStateNavEntryDecorator()` the root host uses. Selected tab is `rememberSaveable`. New tab-root keys (`NearbyTabRoot`, `FollowingTabRoot`, `GlobalTabRoot`) are `@Serializable` `data object`s registered in the `AppNavSerialization` polymorphic `SerializersModule` exactly like the existing keys (Nav3 reflection serialization is unavailable on Kotlin/Native — `mobile-app-scaffold` § "Back stack uses serializable NavKey routes").

*Rationale:* the whole point of a "tab host" (vs a tab switcher) is independent per-tab navigation — a future post-detail / profile push must land in the active tab's stack without touching siblings. Building the per-tab back-stack scaffold now matches the spec source (`FOLLOW_UPS.md` `mobile-home-tab-host`: "*over per-tab Nav3 back stacks*") and avoids a host rework when the first intra-tab destination lands.

*Alternative (rejected):* a single `rememberSaveable` tab enum with a `when(tab){…}` rendering each screen directly, no per-tab back stacks. Smaller, but carries no per-tab navigation state — it would need a full host rework the moment any tab gains an intra-tab push, and it doesn't match the spec source's stated shape.

### D2 — Feed load-state ViewModels scoped to the `HomeRoute` NavEntry (survive tab switch + composer)

The Nearby and Global feed load-state (`NearbyTimelineViewModel`, new `GlobalTimelineViewModel`) are resolved under the **`HomeRoute`** NavEntry ViewModel store (the existing root-`NavDisplay` decorator), NOT under the per-tab NavEntry stores. The per-tab back stacks (D1) hold *navigation* state; the feeds' *load* state lives at the home level.

*Rationale:* `HomeRoute` stays in the root back stack across both tab switches and the composer round-trip (the composer is pushed *above* `HomeRoute` — D3), so a home-scoped ViewModel is the simplest construct that guarantees "no re-fetch on tab switch / composer return." This is a faithful extension of the existing `mobile-nearby-timeline` requirement ("Nearby feed load state is scoped to the Home NavEntry … survives the composer round-trip") — the scoping is unchanged; it now additionally survives tab switches, and a second (Global) feed VM joins it under the same entry.

*Alternative (rejected):* scope each feed VM to its per-tab NavEntry store. Cleaner conceptually, but an inactive tab's `NavDisplay` subtree may be disposed on switch, clearing its NavEntry `ViewModelStore` and forcing a re-fetch on return — the exact behavior we must avoid.

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

- **Nested `NavDisplay`s (root + per-tab) ViewModel-store lifecycle is subtle** → D2 sidesteps the trap by scoping feed VMs to `HomeRoute`, not per-tab entries; a commonTest asserts no re-fetch across tab switch + composer round-trip.
- **Per-tab back stacks add structure with no user-visible payoff yet** (no intra-tab nav in this change) → accepted: it is the designated tab-host scaffold and avoids a later host rework; the cost is a `rememberSaveable` map + three tab-root keys.
- **Global DTO casing drift** (copying the stale snake_case spec JSON instead of the shipped wire) → mitigated by D4 + a negative regression test (snake_case-only body must NOT populate the camelCase fields), mirroring the Nearby guard.
- **Global "empty" is essentially unreachable** (all-Indonesia feed) → still specified as a state for completeness; the realistic edge is the loading skeleton.
- **Scope is on the larger side** (tab host + a full second feed + two MODIFIED capabilities) → the pieces are genuinely inseparable (a tab host can't ship an empty Global tab when the data is ready, and a Global screen needs a tab to live in); kept lean by reusing `SessionIdProvider` and deferring the shared-card extraction.

## Migration Plan

Pure additive mobile change, no runtime/data migration. Ships behind the normal mobile build; no feature flag. Rollback = revert the PR (no schema/backend state touched).

## Open Questions

- **Default authenticated tab (D5): Nearby vs Global?** Proposed: Nearby (preserves current landing). Flip to Global if the product prefers the "entry point" framing even for authenticated users. Low-stakes; resolvable at review.
