## Context

`cap-upsell-parity` (#515) gave every Free daily cap and Premium gate a paywall path with its own `PaywallEntry`. It left two gaps, both found by that PR's sub-agent review:

| Gap | Signal (already shipped) | Client today |
|---|---|---|
| #516 timeline soft read cap | `200` + `upsell.soft = true` (Free only) | `SoftLimitBanner` with `timeline_limit_soft`, no CTA |
| #516 timeline hard read cap | `200` + `posts: []` + `upsell.hard = true` (Free only) | `ListCenteredMessageState(timeline_limit_hard)`, no CTA |
| #517 any upsell after a confirmed purchase | the Free `429` / `403` / read-cap flag until the RevenueCat webhook lands | the upgrade pitch + "Aktifkan Premium" |

`purchaseConfirmed` (`PremiumEntitlementSession`, #512) is account-scoped `StateFlow<Boolean>` state. `rememberPremiumConfirmed()` (`ui/billing/`) resolves it fail-safe from Koin (`getOrNull`, a never-confirmed flow when unbound). It feeds the Nearby, username and search ViewModels and the ad controller today.

The Home path already threads `onActivatePremium: (PaywallEntry) -> Unit` from `AppShellScreen` → `HomeScreen` → the three feeds. `appEntryProvider` maps it with `backStack::openPaywall`.

## Goals / Non-Goals

**Goals:**
- The soft and hard read-cap states open the paywall as `TIMELINE_CAP`, with a matching hero headline.
- No Free upsell tells a confirmed buyer to upgrade. It says Premium is being activated, with no paywall CTA.
- The #517 behavior lives in one shared place: one resolver plus the shared components. No ViewModel changes (operator brief).

**Non-Goals:**
- **Closing the lag server-side.** An on-demand RevenueCat reconcile is still a separate backend capability (`premium-entitlement-lifecycle` design, Non-Goals). The server stays authoritative, and the viewer retries.
- **Automatic retry.** There is no client auto-retry of the gated action and no polling for the tier. The notice says "coba lagi sebentar", and the viewer's own retry (tap, submit, pull-to-refresh) is the retry.
- **Rewording `timeline_limit_hard`.** The CTA carries the Premium path, so the copy stays as is. docs have no canonical read-cap copy, and the key is already flagged for UX review.
- **A dialog for the read cap.** docs/03 makes the frame-18 modal the surface for the four *daily* caps. The read cap is a feed state (an inline banner and an empty-feed state), as #516 asks.
- **`AppEntryProvider.kt`.** It needs no edit (D2), and a parallel session is editing it.

## Decisions

### D1: The read-cap copy moves into the kit components

`SoftLimitBanner` and the new `HardLimitState` bake in their copy (`timeline_limit_soft` / `timeline_limit_hard`), the way `ListErrorState` bakes in the universal network copy. Each is used only by the three feeds with identical copy. They must also swap to the activating body (D4), which a caller-supplied `text` would only half-control. `PostFeedList.banner: String?` becomes `softLimitUpsell: (() -> Unit)?`. One parameter carries both "render the banner" and its CTA action, the same idiom as `reportActionOf`.

*Alternative:* keep `banner: String?` and add `onBannerAction`. Rejected: two parameters that must agree, plus the half-controlled copy.

### D2: `TIMELINE_CAP` rides the existing Home-path callback

Each feed's private `*TimelineContent` gains `onReadCapUpsell: () -> Unit`. The public screen passes `{ onActivatePremium(PaywallEntry.TIMELINE_CAP) }`. `HomeRoute` already maps any entry to `PaywallRoute(entry)`, so there is no host or `AppEntryProvider.kt` edit. The enum is append-only (kotlinx.serialization encodes by name).

### D3: `HardLimitState` extends the list-state kit (not a fork)

docs/11 § 2.1 says a diverging state "composes its own content inside `ListScrollableState`" rather than re-rolling the wrapper. The hard state is shared by three feeds (rule of three), so it becomes a kit member next to `SoftLimitBanner`, built on `ListScrollableState` with the host's `testTag`, so pull-to-refresh still works. docs/11 § 2.1's kit list is amended in this PR. The layout follows `ListErrorState`: centered `bodyLarge` `onSurfaceVariant` copy with a filled `Button` 16dp below. That keeps the two kit states that carry an action visually consistent. The soft banner's CTA is a `TextButton` aligned to the end under the text, the M3 banner action placement: non-blocking, so not a filled button competing with the feed.

### D4: One resolver, read by the shared rendering layer (#517)

`rememberPremiumActivating(): Boolean` (in `ui/billing/RememberPremiumConfirmed.kt`) = `rememberPremiumConfirmed().collectAsState().value`. It is a one-liner over the existing fail-safe resolver, so there is no new Koin access pattern.

- The four shared components (`DailyCapUpsellDialog`, `RadiusPremiumUpsellDialog`, `SoftLimitBanner`, `HardLimitState`) take `premiumActivating: Boolean = rememberPremiumActivating()`. The parameter is the test seam, and the default is the zero-wiring path for every host.
- The three screen-private gates (edit upsell dialog, search gate panel, username gate panel) call the resolver directly. They are private to one screen each, so there is no host to wire.
- The dialogs early-return to `PremiumActivatingDialog(onDismiss)` before the upsell's state (`rememberSaveable` countdown, tick `LaunchedEffect`). The activating notice has no countdown.

*Why not per ViewModel:* the choice is purely presentational. The same server answer renders differently depending on a client signal the ViewModels need not model. A `UiState` field would have to be added to nine ViewModels, for one bit that every surface renders the same way. The operator brief rules it out as well.

*Why not a `CompositionLocal` provided at the app root:* that would be a second app-state access pattern next to the established `rememberPremiumConfirmed()` Koin lookup (a Pattern-Registry fork). Screen tests already bind Koin modules, so they can bind a confirmed session.

*Trade-off:* `getKoin()` throws when no Koin context exists at all. Component tests that compose a shared component bare, with no `startKoin`, pass `premiumActivating` explicitly. Screen tests already start Koin, where `getOrNull` resolves the session or falls back to `false`. This is noted in each component's KDoc.

### D5: One activating copy for every surface

`premium_activating_title` = "Premium sedang diaktifkan". `premium_activating_body` = "Pembelianmu berhasil dan Premium sedang diaktifkan. Coba lagi sebentar ya." The body is shared by the dialogs and the inline gates. It never repeats the surface's upgrade pitch, never claims Premium is already active, and never asks for a payment.

The dialogs keep a single "Tutup" (`cta_close`) wired to each surface's existing dismiss. M3 `AlertDialog` needs a `confirmButton`, so the dismiss is that slot, with no `dismissButton`. The inline gates drop their CTA and keep their layout.

### Standards conformance (docs/11)

- **§ 2.1**: the kit grows by one member (`HardLimitState`), declared in the doc in this PR. `PremiumActivatingDialog` is in `ui/components/` because ≥ 3 dialogs share it. The resolver is in `ui/billing/` ("cross-screen UI seams").
- **§ 2.2**: no new state holder or event stream. Show/hide stays each host's existing one-shot state.
- **§ 2.3**: no new `NavKey`, only an enum value on the registered `PaywallRoute`.
- **§ 4 Pattern Registry**: no fork (D3 and D4 reuse the registered patterns).

### Cross-layer scope (docs/12)

- **Layers**: mobile only.
- **Backend**: every signal ships unchanged (`upsell.soft` / `upsell.hard`, the cap `429`s, the gate `403`s). Read-cap tiering is already Free-only.
- **Admin**: no surface. Upsell rendering is not an admin entity.
- **Mobile**: every consumer of each signal is covered. The read cap covers the three feeds × soft and hard. The activating notice covers every surface that renders "Aktifkan Premium" for a server-Free answer: four daily caps on six hosts, radius, edit, search, username and the read cap. The paywall's own CTA is excluded: it is the purchase action, not an upsell for a server answer.

No layer is deferred.

### Mockups

- Frame 18 (cap dialog): `PremiumActivatingDialog` reuses the same `AlertDialog` component, title/text slots and tokens. It is a variant of the frame-18 surface with a single dismiss action.
- Frame 1 (Beranda feed): the soft banner sits as the feed's first item, in `secondaryContainer`, unchanged except for the end-aligned text button.
- Frame 17 (paywall): a new subhead text only.
- No frame draws the read-cap banner or hard state. Their layout follows the kit siblings (D3), and the PR body flags this for the operator.

## Risks / Trade-offs

- **The signal outlives the lag window.** `purchaseConfirmed` stays `true` for the account's session. After the webhook lands, Free `429`s and `403`s stop, but the both-tier 500/h like burst limiter can still `429` a Premium user, who then sees "Premium sedang diaktifkan". → Accepted: that hit is rare, and before this change it showed an upgrade pitch to a Premium user, which is worse.
- **The read-cap CTA and the webhook lag.** A Free reader who buys from the read-cap paywall returns to a feed that still carries `upsell.hard` until the next fetch after the webhook. The hard state then shows the activating notice, and pull-to-refresh re-fetches. → Accepted. This is the intended behavior.
- **Bare component tests need the explicit parameter** (D4). → Each component's KDoc says so, and the existing bare tests are updated in this PR.
- **Parallel session on `AppEntryProvider.kt` (#173/#518).** → This change does not touch it. A rebase at the end resolves textual drift only.

## Migration Plan

Client-only. A release ships the behavior, with no data migration. Persisted iOS back stacks keep decoding, because `TIMELINE_CAP` is appended. Rollback = revert the PR.

## Open Questions

None blocking. The read-cap banner and hard-state layout has no mockup frame (Mockups). The PR flags it for operator review.
