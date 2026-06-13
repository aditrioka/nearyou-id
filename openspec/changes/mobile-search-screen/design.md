# Design: mobile-search-screen

## Context

The `premium-search` backend capability is shipped and frozen. `GET /api/v1/search?q=<query>&offset=<n>` (`backend/ktor/.../search/SearchRoutes.kt`) runs the ordering parse-offset → auth → length-guard → kill-switch → Premium-gate → rate-limit → repository, and returns:

- `200` → `SearchResponse { results: List<SearchResultDto>, next_offset: Int? }`, ranked by `ts_rank(...) DESC, created_at DESC`, 20 per page (`LIMIT 20 OFFSET :offset`).
- `400` → `{"error":"invalid_query_length"}` (post-NFKC length ∉ `2..100`) or `{"error":"invalid_offset"}` (`< 0`, non-integer, or `> 10000`).
- `403` → `{"error":"premium_required","upsell":true}` (the viewer's authoritative `subscription_status` is `free`).
- `429` → `{"error":"rate_limited"}` + a `Retry-After` header (seconds, ≥ 1) — the 60/hour Layer-2 cap.
- `503` → `{"error":"search_disabled"}` (the `search_enabled` Remote Config kill switch).
- `401` → the standard auth envelope (missing/invalid JWT), auto-emitted by the `AUTH_PROVIDER_USER` plugin.

**Shipped wire shape** (`SearchRoutes.kt`, the source of truth — NOT any stale spec JSON example; the PR #128 / post-detail casing-drift precedent):

```kotlin
@Serializable data class SearchResponse(
    val results: List<SearchResultDto>,            // bare
    @SerialName("next_offset") val nextOffset: Int?,
)
@Serializable data class SearchResultDto(
    @SerialName("post_id")             val postId: String,
    @SerialName("author_id")           val authorId: String,        // UUID — PII, never rendered
    @SerialName("author_username")     val authorUsername: String,
    @SerialName("author_display_name") val authorDisplayName: String,
    val content: String,                                            // bare
    @SerialName("created_at")          val createdAt: String,
    val rank: Float,                                                // bare — never rendered
)
```

This is **snake_case** (like post-detail's `ReplyDto`), distinct from the timelines' camelCase `nextCursor`. The mobile DTOs are generated from this shipped source; a negative-guard test asserts a camelCase body does NOT bind.

**Canonical UX**: `docs/03-UX-Design.md` § Search UX (Premium) (search bar at the top of the Timeline; Premium-only with a Free upsell on tap; query on Enter or after a 500 ms pause; 20-per-page results with "Lihat lebih banyak"; the empty-state and 60/hour modal copy) and `docs/02-Product.md` § Search. **There is NO dedicated search-results frame in the mockup board** (`dev/mockups/nearyou-screens-mockup.html` mentions "Pencarian" only as a Paywall feature bullet, frame 17 line 1721) — so the visual substrate is the shared `mobile-design-system` capability plus the M3 `SearchBar`/search-field idiom (the same idiom the chat conversation-list mockup uses, frame line 659). `docs/11` § 2.8 (mockup-binding) is satisfied by deriving look from the design-system tokens since no binding frame exists; this absence is recorded here per the mockup-consult rule.

## Goals / Non-Goals

**Goals:**

- A Premium-gated `Cari` screen consuming the shipped `GET /api/v1/search`, reachable from the Home brand app bar, closing the discovery half of the demo loop with zero backend work.
- Mirror the proven `mobile-global-timeline` / `mobile-post-detail` seam exactly (Repository behind a `*Flow` interface, sealed status-keyed `*Outcome`, pure `*UiState` projection, route-scoped ViewModel, Nav3 serializable NavKey) — no second networking pattern.
- Faithfully surface every backend state (Premium gate, rate-limit modal, kill switch) so the demo shows the real product behavior.

**Non-Goals:**

- Username autocomplete/typeahead (needs a new backend endpoint — deferred, issue filed).
- Proactive upsell before typing (needs client `subscription_status` — deferred; reactive-on-`403` is v1).
- Paywall navigation from the upsell CTA (Phase 4 / DESIGN-status billing — deferred, informational panel only).
- Any backend change; any change to the `premium-search` query, gate, length guard, offset cap, rate limit, `'simple'` tsvector, or private-profile gate (all consumed as-is — server is authoritative).

## Pattern Registry conformance (docs/11 § 4)

This change introduces **no new pattern** for any Pattern-Registry concern — it reuses the listed mobile patterns verbatim:

- **State holder** — a `HomeRoute`-independent, `SearchRoute`-scoped `SearchViewModel` resolved via `viewModel { … }` under the root `NavDisplay`'s `rememberViewModelStoreNavEntryDecorator()` for `SearchRoute` (the `mobile-app-scaffold` § entry-decorator pattern; the same mechanism `PostDetailRoute` uses for a pushed route). One-shot UI signals are nullable state cleared by a callback, NOT `Channel`/`SharedFlow` (docs/11 § 2.2).
- **Data layer** — `SearchRepository` bound behind a `SearchFlow` interface as a Koin singleton; a sealed `SearchOutcome` keyed on the HTTP **status code** (not a parsed `error.code`) with a defined fallback branch (no generic "load failed" copy) — identical in shape to `GlobalTimelineOutcome` / `PostDetailOutcome`.
- **UI-state projection** — a Compose-free `SearchUiState` data/sealed type plus a pure `searchUiState(...)` function, unit-testable in commonTest without composing the UI (the `globalTimelineUiState(...)` precedent).
- **Navigation** — a `@Serializable` `SearchRoute` NavKey registered in the `navSavedStateConfiguration` polymorphic `SerializersModule`, appended to the **root** back stack (the `PostDetailRoute` / `PostCreationRoute` precedent). Back-stack appends live at the `appEntryProvider` call site, not inside screens (docs/11 nav contract).
- **Design system** — the `mobile-design-system` substrate owns insets/loading/icons/state-copy contract; all UI strings via `:shared:resources` CMP Resources (`stringResource` only — no literals); Material 3 icons for the search/clear/back affordances.

No deviation is introduced, so no docs/11 § Pattern Registry amendment task is required.

## Decisions

**D1 — `SearchRoute` is parameterless (unlike `PostDetailRoute`).** The search query is entered *in* the screen (the text field is the input), so the route carries no payload — it is a `@Serializable data object SearchRoute` registered in the polymorphic module and pushed onto the root back stack. A serialized round-trip test pins iOS-safe saveability. *Alternative rejected*: a query-carrying route (e.g. seeded from a deep link) — no deep-link entry exists; it would serialize a transient input into the back stack for no benefit.

**D2 — The Premium gate is reactive on `403`, not proactive.** `SearchScreen` lets the user type and issues the query; a `403 premium_required` maps to a `PremiumGate` outcome → the upsell panel. This is decoupled from any client-held `subscription_status` (which the app does not yet carry on a stable seam) and is always correct because the server's per-request `findById` read is authoritative (even a mid-session downgrade returns `403` on the next call). `docs/03:240`'s "upsell on tap" (before typing) is functionally equivalent but needs a client premium flag; it is deferred (issue filed) with the reactive gate as the v1 surface. *Alternative rejected*: gating the search icon's visibility on a client premium flag — couples the Home app bar to an unshipped client subscription seam and hides a discoverable feature from Free users (the upsell IS the conversion surface).

**D3 — Mirror the SHIPPED snake_case wire; negative-guard the camelCase form.** The mobile `SearchResultDto`/`SearchResponse` use `@SerialName` snake_case (`post_id`, `author_id`, `author_username`, `author_display_name`, `created_at`, `next_offset`) with bare `content`/`rank`/`results`, generated from `SearchRoutes.kt`. A MockEngine test asserts a camelCase-only body (`postId`/`nextOffset`) does NOT populate the DTO — the same regression guard `mobile-post-detail` ships for its snake_case reply wire. The shared `Json` already sets `ignoreUnknownKeys` + `explicitNulls = false`. *Why it matters*: the timelines are camelCase and post-detail is snake_case; search is snake_case — assuming the wrong casing silently yields empty results.

**D4 — `SearchOutcome` is status-keyed with a defined fallback (no generic fallthrough).** The repository maps each result to exactly one member:

| Result | `SearchOutcome` | Screen state |
|---|---|---|
| `200` | `Results(hits, nextOffset)` | Results / EmptyResults (empty `hits`) |
| `403` | `PremiumGate` | upsell panel |
| `429` | `RateLimited(retryAfterSeconds)` | the docs/03:245 modal + countdown |
| `503` | `Disabled` | kill-switch state |
| `400` | `Error` (retryable) | error + retry (shouldn't occur given D6, but mapped, not a crash) |
| terminal `401` | `SessionExpired` | neutral redirect placeholder (no retry) |
| `5xx` / IO | `NetworkError` | error + retry |
| any other non-2xx | `NetworkError` (defined fallback) | error + retry |

The `when` over the `Int` status keeps a defined `else` → `NetworkError` (required because the match is over an `Int`); the "no generic fallthrough" rule bans a generic *copy*, not a fallback branch — exactly the `mobile-global-timeline` § "Fetch outcome mapping" posture. Terminal `401` branches to `SessionExpired` ahead of the fallback (the shipped `Auth` plugin still owns the refresh attempt + re-route; this only guarantees the brief pre-re-route render is a neutral redirect, never the connectivity copy).

**D5 — The `Retry-After` header is the only rate-limit reset signal.** The 429 body is bare `{"error":"rate_limited"}`; the reset is the `Retry-After` header (seconds). The repository parses it into `RateLimited(retryAfterSeconds)`; an absent/stripped/unparseable header (proxy-rewritten to an HTTP-date) maps to `RateLimited(0)`, and the screen floors a non-positive value to one minute (the shipped post-detail / cap-dialog floor precedent — never a flash-dismiss). The modal countdown is formatted by a pure commonMain formatter (minutes rounded up; "X menit" per `docs/03:245`) decremented via monotonic `delay` (no wall-clock API) — reusing the established countdown approach from `mobile-cap-upsell-dialog` where practical (the formatter is the reusable seam; the search modal is a distinct copy string, `docs/03:245`, not the like-cap body). *Alternative rejected*: a static at-open countdown — diverges from the docs "Reset dalam X menit" live treatment.

**D6 — Client-side query guard mirrors the backend `2..100` to avoid guaranteed-400 round trips.** The screen trims + counts Unicode code points and only issues a request for `2..100`; below 2 it renders the **Idle** directive prompt (no request), and the field caps input at 100. The query fires on a 500 ms debounce after the last keystroke AND on the keyboard submit action (`docs/03:242`). This is a UX optimization, not a security boundary — the backend guard remains authoritative (and a `400 invalid_query_length` is still mapped to `Error`, not a crash, should the two ever diverge). *Alternative rejected*: firing on every keystroke — burns the 60/hour budget and the GIN heap-fetch path the backend length-guard exists to protect.

**D7 — Pagination is a "Lihat lebih banyak" button, not infinite scroll.** `docs/03:243` prescribes a "Lihat lebih banyak" control over a 20-per-page result set. The screen retains `next_offset` on `Results`; tapping the button issues `offset = next_offset`, **appends** the new page to the retained list, and updates `next_offset`. `next_offset == null` is terminal (the button is hidden). A returned empty page is also terminal even if `next_offset != null` (the documented FTS+OFFSET boundary from `premium-search` § Pagination — clients treat `results = []` as terminal). This is lighter than the deferred timeline infinite-scroll (#188) and matches the docs verbatim. *Alternative rejected*: infinite scroll — not what docs/03 specifies and heavier than the demo needs.

**D8 — The search result card is a NEW lighter card, not the shared `PostCard`.** The shipped search wire carries only `postId`/`authorId`/`authorUsername`/`authorDisplayName`/`content`/`createdAt`/`rank` — **no** `cityName`, `distanceM`, `likedByViewer`, or `replyCount`. So a search-result card renders the author identity (letter avatar + `authorDisplayName` + `@authorUsername`, reusing `PostCard`'s avatar/identity sub-treatments so they cannot drift) + `content` + the `created_at` date treatment (the existing `postDateLabel` ISO-date helper, `createdAt.substringBefore('T')` — true relative formatting stays deferred to the `mobile-timeline-relative-timestamp` follow-up). It renders **no** engagement action row (the wire has no like/reply state) and **no** city/distance. PII discipline: `author_id` (UUID) and `rank` are NEVER rendered; nothing is logged (`HttpClientFactory` stays `LogLevel.HEADERS`). *Alternative rejected*: reusing the feed `PostCard` — it requires `cityName`/`distanceM`/`likedByViewer`/`replyCount` and an interactive action row backed by data search does not return; faking those couples the card to absent fields.

**D9 — Result tap opens `PostDetailRoute` with documented default fields (v1 limitation).** Tapping a result invokes a hoisted `onOpenPost`, wired at the `appEntryProvider` call site to `backStack.add(PostDetailRoute(...))`. The search wire lacks `cityName`/`distanceM`/`likedByViewer`/`replyCount`, so the pushed route uses `cityName = ""` (the empty-city convention the detail header already tolerates), `distanceM = null` (no spatial origin — same as Global), `likedByViewer = false`, and `replyCount = 0`, with `postId`/`content`/`createdAtIso`/`authorUsername`/`authorDisplayName` carried from the hit. This is an explicit, spec'd v1 limitation: no per-viewer like-status endpoint and no by-id post GET exist, and `PostDetailScreen` renders its header solely from nav args (no single-post re-fetch), so the like toggle's initial `likedByViewer` and the header `replyCount` may be cosmetically stale until the screen's authoritative `/likes/count` + `/replies` sub-resource fetches resolve — the same posture `mobile-post-detail` already documents for its payload-rendered header. The like endpoints are idempotent (a re-like releases its slot), so a stale-`false` initial state cannot corrupt server state. *Alternative rejected*: deferring result→detail navigation entirely — breaks the demo loop (you could search but not open a result); the default-fields compromise is the smaller, reversible cost (a future `mobile-search-result-detail-enrichment` follow-up can carry richer fields once a by-id/viewer-like endpoint lands).

**D10 — The search entry point is an action icon in the Home brand app bar (Home-section only).** `docs/03:240` puts the search bar "at the top of the Timeline." The Home-section `CenterAlignedTopAppBar` (brand logo, shipped by `mobile-timeline-card-redesign`) gains a trailing search **action icon** — visible only on the Home section (mirroring the composer FAB's Home-only scoping), with a `stringResource` content description. Tapping it pushes `SearchRoute` (call-site `backStack.add`, like `onOpenComposer`). *Alternative rejected*: a persistent inline search field in the app bar — the M3 `SearchBar` expand-in-place pattern is heavier, competes with the brand logo for the app-bar's centered slot, and the focused full-screen `Cari` surface matches the app's existing icon→pushed-screen idiom (composer, post detail).

## Risks / Trade-offs

- **The search-origin `PostDetailRoute` default fields (D9)** are the one behavioral compromise — a Premium user opening a searched post they had previously liked sees the like control start un-liked until `/likes/count` resolves (cosmetic, self-correcting, idempotent-safe). Mitigated by spec'ing it as an explicit requirement + scenario (not a silent default) and filing a follow-up for endpoint-backed enrichment.
- **No autocomplete in v1** (`docs/03:241`) — the demo shows submit/debounce search, not typeahead. Mitigated: the typeahead needs a backend endpoint that doesn't exist; filing a follow-up keeps it visible. The core search demo is fully functional without it.
- **The upsell CTA is informational** (no paywall destination) — a Free user sees the gate copy but cannot purchase in-app yet (Phase 4 billing). Mitigated: the panel still demonstrates the gate; the CTA is a dismiss/no-op placeholder with a tracking issue, the same posture `mobile-cap-upsell-dialog` took for its "Aktifkan Premium" button (operator-authorized placeholder).
- **Kill-switch staleness** — the backend caches `search_enabled` up to 5 minutes (a `premium-search` documented property); the mobile client just renders whatever status the server returns, so there is no additional client-side staleness to manage.

## Migration / Rollout

Mobile-only, no migration, no flag of its own (the `search_enabled` kill switch already gates the backend). The screen ships behind the new Home app-bar search icon; on a `503` it self-presents the disabled state. One squash-merge; no sequencing dependency on the in-flight profile (#245) / following (#246) / chat (#247) picks (disjoint files — a new `screens/search/` + `data/search/` tree plus an additive `mobile-home-tab-host` app-bar action). The `mobile-home-tab-host` MODIFY touches the same spec as #245/#246 (each adds an additive app-bar/section wiring), so a trivial spec-file rebase is possible if two land in the same window — additive, non-conflicting requirement reproductions.
