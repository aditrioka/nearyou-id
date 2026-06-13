# Tasks: mobile-search-screen

> Mobile-only (`:mobile:app` + `:shared:resources`). No backend change, no Flyway migration, no `libs.versions.toml` touch. Consumes the shipped `premium-search` endpoint (`GET /api/v1/search`) as-is.

## 1. Routing — SearchRoute

- [x] 1.1 Add `@Serializable data object SearchRoute : NavKey` to `screens/routing/NavKeys.kt` (parameterless — the query is owned by the screen) and register it in the `navSavedStateConfiguration` polymorphic `SerializersModule` alongside the existing routes
- [x] 1.2 commonTest: `SearchRoute` encodes + decodes via the polymorphic serializer (iOS-safe saved-state round-trip) AND a registration-presence assertion

## 2. Data seam — ApiClient, Repository, Flow, Outcome

- [x] 2.1 Add `data/search/SearchApiClient.kt`: `GET /api/v1/search` with `q` + `offset` query params (first page `offset=0`/omitted; load-more passes the retained `next_offset`); NO spatial params; Bearer attached by the shipped `Auth` plugin (do NOT reimplement). Define the `@Serializable` DTOs mirroring the SHIPPED snake_case wire from `backend/ktor/.../search/SearchRoutes.kt`: `SearchResponse { results, @SerialName("next_offset") nextOffset: Int? }`; `SearchResultDto { @SerialName("post_id") postId, @SerialName("author_id") authorId, @SerialName("author_username") authorUsername, @SerialName("author_display_name") authorDisplayName, content (bare), @SerialName("created_at") createdAt, rank: Float (bare) }`
- [x] 2.2 Add `data/search/SearchFlow.kt` (interface seam: `suspend fun search(query: String, offset: Int): SearchOutcome`) + the sealed `SearchOutcome` (`Results(hits, nextOffset)` / `PremiumGate` / `RateLimited(retryAfterSeconds)` / `Disabled` / `Error` / `SessionExpired` / `NetworkError`)
- [x] 2.3 Add `data/search/SearchRepository.kt` implementing `SearchFlow`: status-keyed mapping per the spec (200→Results; 403→PremiumGate; 429→RateLimited from the `Retry-After` header, absent/unparseable→`RateLimited(0)`; 503→Disabled; 400→Error+log; terminal 401→SessionExpired; 5xx/IO→NetworkError; defined `else`→NetworkError fallback — NO generic "load failed" copy)
- [x] 2.4 MockEngine tests (`SearchApiClient` / `SearchRepository`): endpoint path + `q`/`offset` params (no spatial params); shipped snake_case parse; **camelCase negative-guard** (a `postId`/`nextOffset` body does NOT bind); `next_offset` parse; `Retry-After` parse on 429 + the absent→`RateLimited(0)` floor; each status→outcome mapping (200/403/429/503/400/terminal-401/5xx/IO)

## 3. Query guard + UiState projection (pure, commonMain)

- [x] 3.1 Add the pure query-guard helper (`ui/search/` or `data/search/`): trim leading/trailing Unicode whitespace + count Unicode code points; eligible iff `2..100`; expose for the field's 100-cap + the no-fetch-below-2 gate
- [x] 3.2 Add `ui/search/SearchUiState.kt`: the Compose-free `SearchUiState` (Idle / Loading / Results(with/without load-more) / EmptyResults / Error / PremiumGate / RateLimited / Disabled / SessionExpired) + the pure `searchUiState(query, outcome, isLoading, isLoadingMore)` projection (no PII — no `author_id`, no `rank`; no wall-clock)
- [x] 3.3 commonTest `SearchUiStateTest`: every outcome → its state deterministically (incl. `Results(nextOffset != null)`→load-more shown, `Results(nextOffset = null)`→hidden, `Results(empty)`→EmptyResults, below-2 query→Idle); query-guard test (trim, 2/100 boundaries, below-2 no-fetch eligibility)

## 4. Rate-limit countdown formatter

- [x] 4.1 Reuse the **minutes-only** pure commonMain countdown formatter established by `mobile-cap-upsell-dialog` (`capCountdownMinutes` — NOT the hours+minutes `capCountdown` "14 j 19 mnt" split; search shows "X menit" only per `docs/03-UX-Design.md:245`) for the search rate-limit modal (minutes rounded up; non-positive floored to 1 minute; monotonic `delay`, no wall-clock) — the search modal is a distinct copy string (`search_rate_limited`), not the like-cap body
- [x] 4.2 commonTest for the formatter as applied to the search modal (e.g. 1740 → "29 menit"; 0 → "1 menit" floor; ticks down per minute)

## 5. SearchScreen + result card

- [x] 5.1 Add `screens/search/SearchResultCard.kt`: author letter avatar + `authorDisplayName` + `@authorUsername` (reuse the `mobile-post-card` avatar/identity sub-treatments) + `content` + the `postDateLabel` ISO-date treatment; NO action row, NO city, NO distance; `author_id` + `rank` NEVER rendered; tappable → hoisted `onOpenPost(hit)`. Content descriptions via `stringResource`; no literals
- [x] 5.2 Add `screens/search/SearchScreen.kt`: a minimal top bar (back affordance → hoisted `onBack`; M3 search text field with `search_hint`; clear affordance) + the state surface below filling the remaining space. Render exactly one `SearchUiState` (Idle prompt / Loading / Results list + load-more / EmptyResults / Error+retry / PremiumGate panel / RateLimited modal / Disabled / SessionExpired redirect). Navigation-free (no back-stack reference); under `NearYouTheme`; zero hardcoded UI strings
- [x] 5.3 "Lihat lebih banyak" load-more: shown while `nextOffset != null`; activating issues `offset = nextOffset`, **appends** the page to the retained list, updates `nextOffset`; empty returned page → terminal (control hidden); list stays mounted during the load-more (≤1 in-list indicator)
- [x] 5.4 PremiumGate panel: `search_premium_gate_body` + `search_premium_gate_cta` ("Aktifkan Premium") — v1 CTA is an informational no-op (no paywall destination; negative-guard: no route appended)
- [x] 5.5 RateLimited surface: `search_rate_limited` formatted with the live countdown (formatter from §4); non-positive floored to 1 minute (no flash-clear); auto-clears at zero

## 6. ViewModel + Koin wiring

- [x] 6.1 Add the `SearchRoute`-scoped `SearchViewModel` (resolved via `viewModel { … }` under the root `NavDisplay`'s `rememberViewModelStoreNavEntryDecorator()` for `SearchRoute`): holds the query, in-flight flags, retained outcome, retained `nextOffset`; issues the query via `SearchFlow` on a 500 ms debounce AND on keyboard submit (only when the guard passes); load-more appends; exposes the projection inputs. State owned by the ViewModel, NOT composition `remember`
- [x] 6.2 Register in `di/MobileModule.kt`: `single { SearchApiClient(...) }`, `single { SearchRepository(...) }`, `single<SearchFlow> { get<SearchRepository>() }`
- [x] 6.3 commonTest `SearchViewModel`: submit issues `search(query, 0)`; below-2 query issues nothing; debounce + submit both fire (counting `FakeSearchFlow`); load-more issues `search(query, nextOffset)` and appends; each outcome surfaced

## 7. Home app-bar entry point (MODIFY mobile-home-tab-host)

- [x] 7.1 Add the search action icon to the Home-section `CenterAlignedTopAppBar` `actions` slot (Material search icon, `contentDescription = search_icon_cd`) — Home-section only; invokes a hoisted `onOpenSearch`
- [x] 7.2 Hoist `onOpenSearch` through `AppShellScreen` → wire `onOpenSearch = { backStack.add(SearchRoute) }` at the `appEntryProvider` call site (NOT inside the screens); map `SearchRoute` → `SearchScreen(onBack = { backStack.removeLastOrNull() }, onOpenPost = { hit -> backStack.add(PostDetailRoute(postId = hit.postId, content = hit.content, cityName = "", distanceM = null, createdAtIso = hit.createdAt, likedByViewer = false, replyCount = 0, authorUsername = hit.authorUsername, authorDisplayName = hit.authorDisplayName)) })` in `appEntryProvider`
- [x] 7.3 Tests: shell renders the search action icon only on the Home section (absent on Notifikasi/Profil) and `onOpenSearch` fires on tap; the `appEntryProvider` `onOpenSearch` appends `SearchRoute`; the result-tap `onOpenPost` appends a `PostDetailRoute` with the hit fields + documented defaults (`cityName=""`, `distanceM=null`, `likedByViewer=false`, `replyCount=0`) and no PII

## 8. Strings (:shared:resources)

- [x] 8.1 Add Bahasa Indonesia strings (CMP Resources): `search_hint` ("Cari postingan"), `search_idle_prompt`, `search_empty_results` ("Tidak ada hasil untuk '%1$s'. Coba kata kunci lain." — `docs/03:244`, query-formatted), `search_premium_gate_body`, `search_premium_gate_cta` ("Aktifkan Premium"), `search_rate_limited` ("Kamu sudah mencapai batas pencarian. Reset dalam %1$s." — `docs/03:245`), `search_disabled`, `search_load_more` ("Lihat lebih banyak" — `docs/03:243`), `search_icon_cd`, `search_clear_cd`, `search_back_cd`. Reuse existing `timeline_loading`, `signin_error_network`, `cta_retry`, `timeline_session_redirect`. All UI copy via `stringResource` — no literals

## 9. Screen test + build wiring

- [x] 9.1 Robolectric `SearchScreenTest` (via `FakeSearchFlow`): search input + clear; each visual state (Idle / Loading / Results / EmptyResults / Error+retry / PremiumGate / RateLimited / Disabled / SessionExpired); the "Lihat lebih banyak" append; the result-tap `onOpenPost` payload (hit fields + defaults, no PII); no-hardcoded-strings source guard. Include the negative/clear assertions: SessionExpired shows the neutral redirect AND not `signin_error_network`/`cta_retry`; Disabled shows the kill-switch copy AND not `signin_error_network`; RateLimited auto-clears when the countdown reaches zero (test clock)
- [x] 9.2 Add `**/SearchScreenTest*` to the `mobile/app/build.gradle.kts` Release-variant `*ScreenTest` exclude block; verify `:mobile:app:testDevReleaseUnitTest` passes
- [x] 9.3 Add `iosTest` `SearchFlowIosTest` (mirroring `NearbyTimelineFlowIosTest` / `PostDetailFlowIosTest`): exercise the search flow over a `FakeSearchFlow` on the iOS/Native target so the new `SearchRoute` + data seam compile + run on Kotlin/Native (the universal per-screen `*FlowIosTest` convention)

## 10. Deferrals (file as `follow-up` GitHub issues — NOT silent)

- [x] 10.1 File `follow-up` issue: username autocomplete / typeahead (`docs/03:241`) — needs a NEW backend autocomplete endpoint (none shipped); out of this mobile-only scope → [#252](https://github.com/aditrioka/nearyou-id/issues/252)
- [x] 10.2 File `follow-up` issue: proactive "upsell on tap before typing" (`docs/03:240`) — needs client-held `subscription_status`; the reactive-on-403 `PremiumGate` is the v1 surface; revisit when a `/me` read / the in-flight profile lands → [#253](https://github.com/aditrioka/nearyou-id/issues/253)
- [x] 10.3 File `follow-up` issue: route the PremiumGate / rate-limit upsell CTA to a real paywall screen (Phase 4 / DESIGN-status billing) — the v1 panel is informational → [#254](https://github.com/aditrioka/nearyou-id/issues/254)
- [x] 10.4 File `follow-up` issue: enrich the search-origin `PostDetailRoute` (`cityName`/`distanceM`/`likedByViewer`/`replyCount`) once a by-id post / per-viewer like-status endpoint lands (v1 uses documented defaults) → [#255](https://github.com/aditrioka/nearyou-id/issues/255)

## 11. Verification gate (pre-push)

- [x] 11.1 Run `./gradlew :shared:resources:generateComposeResClass` (or the project's resource-gen task) so the new string accessors exist, then `./gradlew ktlintCheck detekt :lint:detekt-rules:test` — fix any lint before pushing (CI runs both frameworks)
- [x] 11.2 Run `:mobile:app:testDevDebugUnitTest` + `:mobile:app:testDevReleaseUnitTest` (the Release variant proves the `*ScreenTest` exclusion); all green
- [x] 11.3 Manual verification (docs/11 §5 DoD): build `:mobile:app:assembleStagingDebug`, run on a device/Robo via `scripts/run_on_device.sh`, capture the Cari surface (Idle prompt → results → empty-state → and a forced 403/429/503 state if reachable on staging), attach the evidence to the PR body
