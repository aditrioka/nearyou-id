## Why

Two upsell defects left over from `cap-upsell-parity` (PR [#515](https://github.com/aditrioka/nearyou-id/pull/515)):

- **[#516](https://github.com/aditrioka/nearyou-id/issues/516): the Free timeline read cap is a dead end.** `timeline-read-rate-limit` caps Free reading at 50 posts per session (soft) and 150 per hour (hard). The soft banner says "Premium membuka akses baca tanpa batas" but has no button. The hard state on the three feeds has none either. Every other Free cap now opens the paywall with its own `PaywallEntry`. docs/01 § Timeline Read Limit Semantics calls the soft cap a "conversion nudge", and the hard cap returns an "upsell flag", so both are meant to upsell.
- **[#517](https://github.com/aditrioka/nearyou-id/issues/517): a buyer is told to upgrade.** After a confirmed purchase, `subscription_status` stays Free until the RevenueCat webhook lands. A retried like, reply, post, chat, edit, radius change, search or username change still gets the Free answer in that window. The client then shows "Upgrade ke Premium" to someone who just paid. `mobile-paywall` accepts that the action may stay gated briefly, but not that false message. PR [#512](https://github.com/aditrioka/nearyou-id/pull/512) (merged) shipped the `purchaseConfirmed` signal this needs. The issue's "blocked on #512" no longer holds.

## What Changes

- **`PaywallEntry.TIMELINE_CAP`** is appended (append-only, iOS back stacks keep decoding). Its hero subheadline is `paywall_subhead_timeline_cap`, "Baca timeline tanpa batas".
- **The timeline read-cap states get an "Aktifkan Premium" CTA** that opens `PaywallRoute(TIMELINE_CAP)`:
  - **Soft:** `SoftLimitBanner` gains a text-button CTA. It now owns its `timeline_limit_soft` copy, like `ListErrorState` owns its copy. `PostFeedList`'s `banner: String?` becomes `softLimitUpsell: (() -> Unit)?`: non-null renders the banner and is the CTA's action.
  - **Hard:** a new list-kit member, `HardLimitState` (`ListStates.kt`), replaces the three feeds' bare `ListCenteredMessageState(timeline_limit_hard)`. It renders the copy plus an "Aktifkan Premium" button inside the scrollable wrapper, so pull-to-refresh still works.
  - Both limits are Free-only, because Premium skips both buckets server-side, so neither CTA can reach a server-Premium caller.
  - The Home path already carries `onActivatePremium: (PaywallEntry) -> Unit` to the three feeds, and `appEntryProvider` maps any entry to `PaywallRoute(entry)`. **So `AppEntryProvider.kt` does not change.**
- **The activating notice (#517).** While `purchaseConfirmed` is `true`, every Free upsell swaps its upgrade pitch for one shared notice and shows no paywall CTA. The notice uses two new keys, `premium_activating_title` and `premium_activating_body` ("Pembelianmu berhasil dan Premium sedang diaktifkan. Coba lagi sebentar ya.").
  - **Dialogs** render a new shared `PremiumActivatingDialog` (`ui/components/`): `DailyCapUpsellDialog` (all four daily caps, every host), `RadiusPremiumUpsellDialog`, and the post-edit `403` upsell.
  - **Inline surfaces** render the body without their CTA: `SoftLimitBanner`, `HardLimitState`, the search `403` gate and the username `403` gate.
  - **One resolver:** `rememberPremiumActivating()` in `ui/billing/` reads the existing fail-safe `rememberPremiumConfirmed()`. The shared components take it as a defaulted `premiumActivating` parameter, so the six cap hosts and three feeds get it without wiring. The three screen-private gates call it directly. No ViewModel changes.
  - The operator chose to cover the four `403` Premium gates as well as the caps. PR #512 had left them showing the upsell during the lag; that was a "we can't close the lag server-side" non-goal, not a reason to keep the false message.
- **docs/11 § 2.1** names `HardLimitState` in the canonical list-state kit.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `mobile-premium-entitlement`: ADD the requirement that every upsell surface shows the shared activating notice during the webhook-lag window, read through one resolver.
- `mobile-paywall`: `TIMELINE_CAP` joins the entry set and the per-entry hero mapping.
- `mobile-cap-upsell-dialog`: the dialog renders the activating notice while a purchase is confirmed.
- `mobile-global-timeline`, `mobile-following-timeline`, `mobile-nearby-timeline`: the hard and soft read-cap states carry the `TIMELINE_CAP` CTA and swap to the activating notice.
- `mobile-nearby-radius-slider`: the radius upsell shows the activating notice during the lag window.
- `mobile-search`: the `403` gate panel shows the activating notice during the lag window.
- `mobile-premium-username`: the `403` gate panel shows the activating notice during the lag window.
- `mobile-post-editing`: the `403` upsell shows the activating notice during the lag window.

## Impact

- **Mobile only** (`:mobile:app` + `:shared:resources`). There is no backend, admin, schema, wire or dependency change. Every signal already ships: the read-cap `upsell.soft` / `upsell.hard` flags, the cap `429`s, the gate `403`s, and `purchaseConfirmed`.
- **Code**:
  - Shared UI: `ui/components/{ListStates,PostFeedList,DailyCapUpsellDialog,RadiusPremiumUpsellDialog}.kt`, new `ui/components/PremiumActivatingDialog.kt`, `ui/billing/RememberPremiumConfirmed.kt`.
  - Screens: `screens/timeline/{Global,Following,Nearby}TimelineScreen.kt`, `screens/post/EditPostScreen.kt`, `screens/search/SearchScreen.kt`, `screens/username/UsernameCustomizationScreen.kt`, `screens/paywall/PaywallScreen.kt`, `screens/routing/NavKeys.kt`.
  - Strings: `strings.xml`.
  - **Untouched:** `AppEntryProvider.kt` (a parallel session for #173/#518 is editing it). There are no ViewModel changes.
- **Docs**: `docs/11-Engineering-Standards.md` § 2.1 (kit list).
- **Sequencing**: no open PR touches these files. If #173/#518 land first, rebase. The overlap would be textual at most, since this change does not edit `AppEntryProvider.kt`.
