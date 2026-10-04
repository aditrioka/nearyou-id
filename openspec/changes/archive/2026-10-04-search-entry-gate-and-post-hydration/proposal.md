## Why

The Cari surface still carries two v1 limitations that `mobile-search` explicitly deferred and that now have their prerequisites shipped:

- **#253 — no upsell on tap.** `docs/03-UX-Design.md` § Search UX says "Free users see an upsell on tap". v1 only gates reactively: a Free viewer lands on the Idle prompt, types, waits for the debounce, and only then sees the upsell from the backend's `403 premium_required`. The client signals the proactive gate needs now exist: the on-entry self-profile `isPremium` read (used by the username, composer and Nearby gates) and the `purchaseConfirmed` entitlement signal (`mobile-premium-entitlement`, PR #512).
- **#255 — the detail opens with placeholder fields.** A result tap pushes `PostDetailRoute` with `cityName = ""`, `distanceM = null`, `likedByViewer = false`, `replyCount = 0` and no `imageUrl`, because the search wire carries none of them. The full by-id read (`SinglePostApiClient.fetchFullPost`, `single-post-read`) has since shipped and already backs the notification deep-link. So a search-origin detail can open fully populated: the real like state, reply count and city, and the image, which is dropped today.

## What Changes

- **On-entry Premium gate (#253).** `SearchViewModel` resolves the viewer's tier on entry with the established self-profile read (`ProfileFlow` + `SelfUserIdProvider`), ORed with `purchaseConfirmed`. While the viewer is known Free, the surface that would be the Idle prompt renders the existing Premium-gate upsell panel ("Aktifkan Premium" → `PaywallRoute(SEARCH_GATE)`) before anything is typed.
  - The reactive `403` gate stays the authoritative backstop.
  - The search field stays usable, and an eligible query is still sent, so the server decides. A `premium_billing_retry` viewer reads as Free on the profile wire but is Premium for search, and still gets results.
  - A server answer that proves Premium (`Results` / `RateLimited`) clears the on-entry gate for the rest of the visit.
  - A confirmed purchase lifts the gate live. The existing once-only re-run of a `403`-gated query is kept, not rewritten.
  - The resolving window renders the Idle prompt, and a failed read degrades to it too. Neither ever shows an error wall.
- **Search result tap hydrates the detail (#255).** A tap resolves the post through the by-id `single-post-read` (the notification deep-link's resolution path) and pushes `PostDetailRoute` with the real `cityName` / `likedByViewer` / `replyCount` / `imageUrl`.
  - A per-card spinner shows while the fetch is in flight. The latest tap wins.
  - If the read is `Unavailable`, the push falls back to the v1 payload (hit fields + documented defaults), so a tap is never stranded.
  - `distanceM` stays `null`: search has no spatial origin, and the by-id projection carries no coordinates.
- **Shared by-id post resolution.** `PostTargetResolution` moves from the notifications package to `post/`, together with one `SinglePostFullResult → PostTargetResolution` mapping. The `Resolved → PostDetailTarget` mapping becomes a shared function next to `PostDetailTarget`. Notifications and search consume the same two mappers, so there is no second resolver (docs/11 Pattern Registry, rule of three). This is a mechanical move with no behavior change for notifications.
- **`SearchViewModel` exposes one `uiState`.** It gets one `uiState: StateFlow<SearchScreenUiState>` via `stateIn` (docs/11 §2.2 known debt: "consolidate when next touched"). The pure `searchUiState(...)` projection is unchanged, and the raw `outcome` stays as the white-box seam (the `GlobalTimelineViewModel` precedent).
- **One target→route helper.** The search entry maps the resolved `PostDetailTarget` through the same mapping the Home feed taps and the push-tap effect use. That mapping is factored into one `internal` helper in `screens/routing/`, used by `AppEntryProvider` and `PushTapNavigationEffect`, so the field list is not repeated a fourth time.
- **Spec.** The `mobile-search` deferral requirement now keeps only username autocomplete deferred (#252).
- **Load-more de-duplication.** This is a pre-existing crash surfaced in review. A load-more page that repeats a retained hit (`OFFSET` paging over rank ties) no longer appends it twice; the list keys on `postId`.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `mobile-search`: eight requirements change.
  - The Premium gate also renders on entry for a known-Free viewer. Known-Premium is sticky.
  - The screen-state projection gains the explicit `viewerKnownFree` input.
  - The screen and query-guard requirements now say "Idle, or the on-entry gate for a known-Free viewer".
  - A result tap hydrates `PostDetailRoute` from the by-id read, with the v1 defaults as the `Unavailable` fallback. A query change or leaving the screen cancels the read.
  - The `SearchViewModel` seam requirement gains the self-profile and by-id dependencies and the single `uiState`.
  - The deferral requirement drops the proactive upsell (shipped) and keeps autocomplete (#252).
  - Pagination de-duplicates a repeated hit by `postId`.
- `mobile-post-detail`: § "By-id post reads never gate the header's first paint" gains the search-result entry as a third pre-push lookup. Unlike the notification entry, its fallback on failure still pushes, because the hit is renderable.

## Impact

- **Mobile (`:mobile:app` commonMain):**
  - `screens/search/` (`SearchViewModel`, `SearchScreen`, `SearchResultCard`, `SearchUiState`);
  - `search/` (`SearchFlow` gains `resolvePostTarget`; `SearchRepository` takes `SinglePostApiClient`);
  - `post/PostTargetResolution.kt` (moved type + shared mapping);
  - `notifications/` (`NotificationsFlow`, `NotificationsRepository`: imports and the shared mapping);
  - `screens/notifications/NotificationNavigation.kt` (shared mapper);
  - `screens/home/HomeScreen.kt` (`PostDetailTarget` mapper);
  - `screens/routing/` (`AppEntryProvider`: search entry; `PushTapNavigationEffect` + a new shared target→route helper);
  - `screens/search/SearchStates.kt` (the non-result state composables moved out of `SearchScreen.kt`, which is past the docs/11 ~400-line soft cap);
  - `di/MobileModule.kt` (`SearchRepository` wiring).
- **Tests:**
  - commonTest: `SearchViewModelTest` (on-entry Free/Premium/failure/confirm/server-evidence, tap hydration + fallback + supersede), a `SearchRepository` `resolvePostTarget` test, and `FakeSearchFlow` + notification fakes (imports);
  - androidUnitTest: `SearchScreenTest` (on-entry upsell before typing, hydrated tap payload, per-card spinner);
  - iosTest: `SearchFlowIosTest`, kept green and extended with the on-entry gate.
- **Not touched:** the backend, admin, the wire, the DB, `PostDetailScreen` (owned by a concurrent change), dependencies.
- **No new strings.** The gate reuses `search_premium_gate_body` and `cta_activate_premium`.
- **Issues:** closes #253 and #255. Username autocomplete stays deferred to #252.
