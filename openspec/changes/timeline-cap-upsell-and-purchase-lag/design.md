## Context

`cap-upsell-parity` (#515) gave every Free daily cap and Premium gate a paywall path with its own `PaywallEntry`. Its sub-agent review found two gaps:

| Gap | Signal (already shipped) | Client today |
|---|---|---|
| #516 timeline soft read cap | `200` + `upsell.soft = true` (Free only) | `SoftLimitBanner` with `timeline_limit_soft`, no CTA |
| #516 timeline hard read cap | `200` + `posts: []` + `upsell.hard = true` (Free only) | `ListCenteredMessageState(timeline_limit_hard)`, no CTA |
| #517 any upsell after a confirmed purchase | the Free `429` / `403` / read-cap flag / Free tier read on entry, until the RevenueCat webhook lands | the upgrade pitch + "Aktifkan Premium", or a second push to the paywall |

`purchaseConfirmed` (`PremiumEntitlementSession`, #512) is account-scoped `StateFlow<Boolean>` state. Composables reach it through `rememberPremiumConfirmed()` (`ui/billing/`, fail-safe `getOrNull`, never-confirmed when unbound), which today feeds the Nearby, username and search ViewModels. `AdFeedController` receives it through a strict `get()` in `mobileModule`.

The Home path already threads `onActivatePremium: (PaywallEntry) -> Unit` from `AppShellScreen` → `HomeScreen` → the three feeds, and `appEntryProvider` maps it with `backStack::openPaywall`.

## Goals / Non-Goals

**Goals:**
- The soft and hard read-cap states open the paywall as `TIMELINE_CAP`, with a matching hero headline.
- No Free upsell tells a confirmed buyer to upgrade or sends them to buy again. It says Premium is being activated, with no paywall CTA. Inline states get a retry.
- The #517 behavior lives in one shared place: one resolver plus the shared components. There are no ViewModel changes (operator brief).

**Non-Goals:**
- **Closing the lag server-side.** An on-demand RevenueCat reconcile is still a separate backend capability (`premium-entitlement-lifecycle` design, Non-Goals). The server stays authoritative, and the viewer retries.
- **Unlocking client gates that read the tier once on entry** (the composer image-attach, the Settings toggles). Unlocking them would only move the Free answer to the server call (the image upload `403`, the toggle `PATCH` `403`), which lags just the same. Those surfaces show the activating notice in that window instead, and unlock on the next open after the webhook.
- **Rewording `timeline_limit_hard`.** The CTA carries the Premium path. docs have no canonical read-cap copy, and the key is already flagged for UX review.
- **A modal for the read cap.** docs/03 makes the frame-18 modal the surface for the four *daily* caps. The read cap is a feed state, as #516 asks.
- **`AppEntryProvider.kt`.** It needs no edit (D2), and a parallel session is editing it.

## Decisions

### D1: The read-cap copy moves into the kit components

`SoftLimitBanner` and the new `HardLimitState` bake in their copy (`timeline_limit_soft` / `timeline_limit_hard`), the way `ListErrorState` bakes in the universal network copy. Only the three feeds use them, with identical copy, and each must change its own rendering while activating (D4). A caller-supplied `text` would only half-control that. `PostFeedList.banner: String?` becomes `softLimitUpsell: (() -> Unit)?`. One parameter carries both "render the banner" and its CTA action, the same idiom as `reportActionOf`.

### D2: `TIMELINE_CAP` rides the existing Home-path callback

Each feed's private `*TimelineContent` gains `onReadCapUpsell: () -> Unit`, and the public screen passes `{ onActivatePremium(PaywallEntry.TIMELINE_CAP) }`. `HomeRoute` already maps any entry to `PaywallRoute(entry)`, so there is no host or `AppEntryProvider.kt` edit. The enum is append-only (kotlinx.serialization encodes by name).

### D3: `HardLimitState` extends the list-state kit (not a fork)

Three feeds share the hard state, so it becomes a kit member next to `SoftLimitBanner`. It is built on `ListScrollableState` with the host's `testTag`, so pull-to-refresh still works. docs/11 § 2.1's kit list is amended in this PR.
- **Layout** follows `ListErrorState`: centered `bodyLarge` `onSurfaceVariant` copy, with a filled `Button` 16dp below. The two kit states that carry an action stay visually consistent.
- **Soft banner CTA** is an end-aligned `TextButton` under the text, the M3 banner action placement. The banner is non-blocking, so a filled button would compete with the feed.

### D4: One resolver, read by the shared rendering layer (#517)

`rememberPremiumActivating(): Boolean` (in `ui/billing/RememberPremiumConfirmed.kt`) is `rememberPremiumConfirmed().collectAsStateWithLifecycle().value`. It is a one-liner over the existing fail-safe resolver, using the docs/11 § 2.2 collector.
- **Shared components** take `premiumActivating: Boolean = rememberPremiumActivating()`: `DailyCapUpsellDialog`, `RadiusPremiumUpsellDialog`, `SoftLimitBanner`, `HardLimitState`. The parameter is the test seam, and the default is the zero-wiring path for every host.
- **Screen-private surfaces** call the resolver directly: the edit upsell dialog, the search and username gate panels, the Settings snackbar text, and the composer attach one-shot. Each is private to one screen, so there is no host to wire.
- **Dialogs** early-return to `PremiumActivatingDialog(onDismiss)` before the upsell's own state (the `rememberSaveable` countdown and its tick `LaunchedEffect`).

This is registered in docs/11 § 2.1 in this PR as the pattern for "a shared component that reads an app-wide UI signal". At HEAD no `ui/components/` composable touches Koin; the existing seam `rememberTimelineAds` is called by screens and passed down. Declaring the idiom keeps it from being an unexplained fork (§ 4).

*Why not per ViewModel:* the choice is purely presentational. The same server answer renders differently depending on a client signal the ViewModels need not model. Doing it there would add a `UiState` field to about ten ViewModels for one bit that every surface renders the same way. The operator brief also rules it out.

*Why not host-passed (the `rememberTimelineAds` shape):* the four daily caps alone have seven hosts, and every host would have to remember to pass the value. A forgotten host silently regresses to the false pitch. The defaulted parameter makes the safe behavior the default.

*Why not a `CompositionLocal` provided at the app root:* that would be a second app-state access pattern beside the established Koin-backed `rememberPremiumConfirmed()`.

*Shared action:* the three inline gates (`HardLimitState`, the search panel, the username panel) share one `PremiumGateAction` (`ui/components/`). It shows the "Aktifkan Premium" / "Coba lagi" swap with the surface's tags, per docs/11 § 4's rule of three. Each surface keeps its own body copy and layout.

*Trade-off:* `getKoin()` throws when Koin was never started. A test that composes a shared component bare passes the value explicitly; screen tests already start Koin. Each component's KDoc and the docs/11 entry say so.

### D5: One activating copy, and a way forward on inline states

- **Copy.** `premium_activating_title` = "Premium sedang diaktifkan". `premium_activating_body` = "Pembelianmu berhasil dan Premium sedang diaktifkan. Coba lagi sebentar lagi ya." The body matches the "Coba lagi sebentar lagi ya" of `timeline_limit_hard`. It is true by construction: the signal is set only after `PaywallViewModel` confirms an active entitlement. It never claims Premium is already active, and never asks for a payment.
- **Dialogs** (the four caps, radius, edit, the composer attach) keep a single "Tutup" wired to each surface's existing dismiss. M3 `AlertDialog` needs a `confirmButton`, so the dismiss sits in that slot. The viewer retries the original action by hand.
- **Inline states** replace the paywall CTA with "Coba lagi" (`cta_retry`) wired to the surface's existing retry path: the hard state reloads page 1, search re-runs the query, and username clears the gate back to the editor (`onCandidateChange(candidate)`). Without it, the full-screen username gate would leave nothing to tap but Back.
- **The soft banner** is not rendered while activating. The soft cap blocks no reading, so neither a pitch nor a "try again" applies.
- **The Settings snackbars** swap their text only. The toggles stay locked until the next open (Non-Goals).

### D6: The composer attach gate reuses its one-shot

The composer resolves `isPremium` once on entry. A buyer coming back from the `IMAGE_ATTACH` paywall to the same composer would otherwise be pushed to the paywall again, and a second purchase would hit the store's already-owned error. The existing `routeToPaywall` one-shot now drives either path. The paywall push happens only when not activating. The `PremiumActivatingDialog` shows while the one-shot is set and activating, and its "Tutup" clears the one-shot (`onPaywallRouted()`). There is no new state and no ViewModel change.

### Standards conformance (docs/11)

- **§ 2.1:** the kit grows by `HardLimitState`, and the shared-component signal idiom (D4) is registered, both in this PR. `PremiumActivatingDialog` lives in `ui/components/` because ≥ 3 dialogs share it, and the resolver lives in `ui/billing/`.
- **§ 2.2:** no new state holder or event stream. Show/hide stays each surface's existing one-shot state (D6 reuses `routeToPaywall`). `collectAsStateWithLifecycle()` collects the signal.
- **§ 2.3:** no new `NavKey`, only an enum value on the registered `PaywallRoute`.
- **§ 4 Pattern Registry:** no undeclared fork.

### Cross-layer scope (docs/12)

- **Layers:** mobile only.
- **Backend:** every signal ships unchanged (`upsell.soft` / `upsell.hard`, the cap `429`s, the gate `403`s, the tier reads). Read-cap tiering is already Free-only.
- **Admin:** no surface. Upsell rendering is not an admin entity.
- **Mobile:** every consumer is covered:
  - the read cap: three feeds × soft and hard;
  - the activating notice: every surface that pitches Premium against a Free answer. That is the four daily caps on seven hosts, radius, edit, the composer attach gate, search, username, the two Settings toggles, and the read cap.
  - **Excluded:** the paywall's own CTA, which is the purchase action itself, not an upsell.

No layer is deferred.

### Mockups

- **Frame 18 (cap dialog):** `PremiumActivatingDialog` reuses the same `AlertDialog` component, slots and tokens. It is the frame-18 surface with a single dismiss action.
- **Frame 1 (Beranda feed):** the soft banner stays the feed's first item, in `secondaryContainer`, plus the end-aligned text button.
- **Frame 17 (paywall):** only one new subhead text.
- **No frame** draws the read-cap banner or hard state. Their layout follows the kit siblings (D3), and the PR flags this for the operator.

## Risks / Trade-offs

- **The signal is in-memory and outlives the lag.**
  - `purchaseConfirmed` stays `true` for the account's session, so a buyer who later hits the both-tier 500/h like burst limiter sees "Premium sedang diaktifkan". → Accepted: before this change they saw an upgrade pitch.
  - A cold start inside the lag window resets the signal, so the Free upsell returns until the webhook lands. → Accepted: the window is normally seconds.
  - If the webhook never lands (a delivery failure), the notice shows for the whole session with no way forward. → Accepted for the client. The fix belongs to the webhook and its alerting, not to the upsell copy.
- **Surfaces that read the tier once** (composer, Settings) keep showing the notice until they are reopened after the webhook. → Accepted (Non-Goals). Reopening is the natural retry.
- **The username retry can re-probe.** "Coba lagi" clears the gate through `onCandidateChange(candidate)`. If that candidate was never probed (a submit inside the 500 ms debounce, or the memo cleared by the confirmation), the existing probe runs. During the lag that probe `403`s back to the gate, and it spends one of the 3/day probes. → Accepted: the retry is a re-check of the server, which is what the viewer asked for. Avoiding it would need a ViewModel change, which this change rules out.
- **Bare component tests need the explicit parameter** (D4). → The KDoc and docs/11 say so, and the existing bare tests are updated in this PR.
- **Parallel session on the Nearby/Global feeds** (#173/#518, PR #559). It adds a `feedReloadKey` and replaces `changeRadius` / `RadiusChangeResult`. → This change touches neither and keeps its feed edits to the hard and soft branches. Whichever PR merges second rebases and re-runs the mobile gate.

## Migration Plan

Client-only. A release ships the behavior, with no data migration. Persisted iOS back stacks keep decoding, because `TIMELINE_CAP` is appended. Rollback = revert the PR.

## Open Questions

None blocking. No mockup frame covers the read-cap banner or hard-state layout, so the PR flags it for operator review.
