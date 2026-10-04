## Why

Two upsell defects are left over from `cap-upsell-parity` (PR [#515](https://github.com/aditrioka/nearyou-id/pull/515)):

- **[#516](https://github.com/aditrioka/nearyou-id/issues/516): the Free timeline read cap is a dead end.** `timeline-read-rate-limit` caps Free reading at 50 posts a session (soft) and 150 an hour (hard). The soft banner says "Premium membuka akses baca tanpa batas" but has no button, and the hard state on the three feeds has none either. Every other Free cap now opens the paywall with its own `PaywallEntry`. docs/01 § Timeline Read Limit Semantics calls the soft cap a "conversion nudge", and the hard cap returns an "upsell flag", so both are meant to upsell.
- **[#517](https://github.com/aditrioka/nearyou-id/issues/517): a buyer is told to upgrade.** After a confirmed purchase, `subscription_status` stays Free until the RevenueCat webhook lands. A retried like, reply, post, chat, edit, radius change, search or username change still gets the Free answer in that window. So does a screen that read the tier on entry (Settings toggles, the composer's image attach). The client then shows "Upgrade ke Premium" to someone who just paid, or sends them to the paywall to buy again. `mobile-paywall` accepts that the action may stay gated briefly, but not that message. PR [#512](https://github.com/aditrioka/nearyou-id/pull/512) (merged) shipped the `purchaseConfirmed` signal this needs, so the issue's "blocked on #512" no longer holds.

## What Changes

- **`PaywallEntry.TIMELINE_CAP`** is appended. It is append-only, so iOS back stacks keep decoding. Its hero subheadline is `paywall_subhead_timeline_cap`, "Baca timeline tanpa batas".
- **The timeline read-cap states get an "Aktifkan Premium" CTA** that opens `PaywallRoute(TIMELINE_CAP)`:
  - **Soft:** `SoftLimitBanner` gains an end-aligned text-button CTA. It now owns its `timeline_limit_soft` copy, like `ListErrorState`. `PostFeedList`'s `banner: String?` becomes `softLimitUpsell: (() -> Unit)?`.
  - **Hard:** a new list-kit member, `HardLimitState` (`ListStates.kt`), replaces the feeds' bare `ListCenteredMessageState(timeline_limit_hard)`. It shows the copy plus a filled "Aktifkan Premium" button inside the scrollable wrapper, so pull-to-refresh still works.
  - Both limits are Free-only (Premium skips both buckets server-side), so neither CTA can reach a server-Premium reader.
  - The Home path already carries `onActivatePremium: (PaywallEntry) -> Unit` to the three feeds, so **`AppEntryProvider.kt` does not change**.
- **The activating notice (#517).** While `purchaseConfirmed` is `true`, every Free upsell swaps its pitch for one shared notice and shows no paywall CTA. The copy is `premium_activating_title` / `premium_activating_body`: "Premium sedang diaktifkan", "Pembelianmu berhasil dan Premium sedang diaktifkan. Coba lagi sebentar lagi ya."
  - **Dialogs** render a new shared `PremiumActivatingDialog` (`ui/components/`) with a single "Tutup". This covers `DailyCapUpsellDialog` (the four daily caps on seven hosts), `RadiusPremiumUpsellDialog`, the post-edit `403` upsell, and the composer's image-attach gate. That gate no longer pushes the paywall a second time.
  - **Inline states** show the body with "Coba lagi" through each surface's existing retry path. `HardLimitState` reloads page 1, the search `403` gate re-runs the query, and the username `403` gate returns to the editor.
  - **Settings** "Sembunyikan jarak" / "Profil privat" snackbars read the notice instead of "Aktifkan Premium untuk…".
  - **The soft banner** simply hides, because it blocks no reading.
  - **One resolver.** `rememberPremiumActivating()` in `ui/billing/` reads the existing fail-safe `rememberPremiumConfirmed()`. The shared components take it as a defaulted `premiumActivating` parameter, so their hosts get it with no wiring, and the screen-private surfaces call it directly. No ViewModel changes.
  - The operator chose to cover every surface that pitches Premium against a Free answer, not only the caps #517 names. PR #512 had left the `403` gates showing the upsell during the lag. That was a "can't close the lag server-side" non-goal, not a reason to keep the false message.
- **docs/11 § 2.1** names `HardLimitState` in the list-state kit and registers the "shared component reads an app-wide UI signal through a defaulted parameter" idiom.
- **docs/03 § Rate Limit Communication** gains the read-cap CTA and the webhook-lag activating copy.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `mobile-premium-entitlement`: ADD the requirement that every upsell surface shows the shared activating notice during the webhook-lag window, read through one resolver.
- `mobile-paywall`: `TIMELINE_CAP` joins the entry set and the per-entry hero mapping.
- `mobile-cap-upsell-dialog`: the frame-18 dialog renders the activating notice while a purchase is confirmed, and that notice has no countdown.
- `mobile-global-timeline`, `mobile-following-timeline`, `mobile-nearby-timeline`: the hard and soft read-cap states carry the `TIMELINE_CAP` CTA. While activating, the hard state shows the notice plus a reload, and the soft banner hides.
- `mobile-nearby-radius-slider`: the radius upsell shows the activating notice during the lag window.
- `mobile-search`, `mobile-premium-username`: the `403` gate panel shows the activating notice plus a retry. The stale `*_premium_gate_cta` key names are corrected to `cta_activate_premium`.
- `mobile-post-editing`: the `403` upsell shows the activating notice.
- `mobile-image-attachment`: the Free attach tap shows the activating notice instead of a second paywall during the lag window.
- `mobile-settings`: the Premium toggles' upsell snackbar reads the activating notice during the lag window.

## Impact

- **Mobile only** (`:mobile:app` + `:shared:resources`). There is no backend, admin, schema, wire or dependency change. Every signal already ships: the read-cap `upsell.soft` / `upsell.hard`, the cap `429`s, the gate `403`s, the tier reads, and `purchaseConfirmed`.
- **Code:**
  - Shared UI: `ui/components/{ListStates,PostFeedList,DailyCapUpsellDialog,RadiusPremiumUpsellDialog}.kt`, new `ui/components/PremiumActivatingDialog.kt`, and `ui/billing/RememberPremiumConfirmed.kt`.
  - Screens: `screens/timeline/{Global,Following,Nearby}TimelineScreen.kt`, `screens/post/{EditPostScreen,PostCreationScreen}.kt`, `screens/search/SearchScreen.kt`, `screens/username/UsernameCustomizationScreen.kt`, `screens/settings/SettingsScreen.kt`, `screens/paywall/PaywallScreen.kt`, and `screens/routing/NavKeys.kt`.
  - Strings: `strings.xml`.
  - **Untouched:** `AppEntryProvider.kt`. No ViewModel changes.
- **Docs:** `docs/11-Engineering-Standards.md` § 2.1, `docs/03-UX-Design.md` § Rate Limit Communication.
- **Sequencing:** PR [#559](https://github.com/aditrioka/nearyou-id/pull/559) (#173/#518) also edits `NearbyTimelineScreen.kt` / `GlobalTimelineScreen.kt` (a `feedReloadKey`) and replaces `changeRadius`. This change edits only the feeds' hard and soft branches and uses neither API. Whichever PR merges second rebases and re-runs the mobile gate.
