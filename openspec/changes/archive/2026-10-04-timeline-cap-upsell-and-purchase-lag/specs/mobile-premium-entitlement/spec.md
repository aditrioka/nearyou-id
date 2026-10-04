## ADDED Requirements

### Requirement: Upsell surfaces show the activating notice during the webhook-lag window

While `purchaseConfirmed` is `true`, no Free upsell surface SHALL tell the buyer to "Upgrade ke Premium" or send them to the paywall to buy again. Each SHALL show the shared activating notice described below instead.

After a confirmed purchase, the server's `users.subscription_status` stays Free until the RevenueCat webhook lands (`subscription-billing-webhook`). In that window the server, or a client gate seeded from a server read, can still answer as Free:
- a cap `429`;
- a `403 premium_required` / `radius_premium_only`;
- a timeline read-cap flag;
- a Free tier read on screen entry.

`mobile-paywall` accepts that a server-gated action may stay gated briefly. This requirement adds its own rule: no upsell surface SHALL tell a buyer who just paid to "Upgrade ke Premium", or send them to the paywall to buy again.

While `purchaseConfirmed` is `true`, every Free upsell surface SHALL swap its upgrade pitch for the shared **activating notice**:
- The notice says the purchase succeeded and Premium is being activated, so the viewer should try again shortly.
- It uses two new `:shared:resources` keys: `premium_activating_title` ("Premium sedang diaktifkan") and `premium_activating_body` ("Pembelianmu berhasil dan Premium sedang diaktifkan. Coba lagi sebentar lagi ya.").
- It shows NO "Aktifkan Premium" CTA and pushes no `PaywallRoute`.

The surfaces:

| Surface | Free upsell | During the lag window |
|---|---|---|
| `DailyCapUpsellDialog`: like / reply / post / chat `429` on the seven hosts (three feeds, post-detail, composer, chat thread, share picker) | frame-18 cap dialog | `PremiumActivatingDialog` |
| `RadiusPremiumUpsellDialog` (Nearby) | radius upsell dialog | `PremiumActivatingDialog` |
| Post-edit `403 premium_required` upsell | edit upsell dialog | `PremiumActivatingDialog` |
| Composer image-attach gate (tier read on entry) | push `PaywallRoute(IMAGE_ATTACH)` | `PremiumActivatingDialog`, no push |
| `HardLimitState` (timeline hard read cap, three feeds) | `timeline_limit_hard` + "Aktifkan Premium" | `premium_activating_body` + "Coba lagi" → page-1 reload |
| `SoftLimitBanner` (timeline soft read cap, three feeds) | `timeline_limit_soft` + "Aktifkan Premium" | not rendered (the soft cap blocks no reading) |
| Search `403` gate panel | `search_premium_gate_body` + CTA | `premium_activating_body` + "Coba lagi" → the same query |
| Username `403` gate panel | `username_premium_gate_body` + CTA | `premium_activating_body` + "Coba lagi" → back to the editor |
| Settings "Sembunyikan jarak" / "Profil privat" Free tap (tier read on entry) | `settings_*_premium_only` snackbar | `premium_activating_body` snackbar |

`PremiumActivatingDialog` is ONE shared `ui/components/` Material 3 `AlertDialog`:
- title `premium_activating_title`, text `premium_activating_body`;
- a single "Tutup" (`cta_close`) in the confirm slot;
- its `onDismissRequest` and that button both invoke the hoisted `onDismiss`, which each surface wires to the path its own dismiss already uses;
- it holds no navigation reference.

The swap is made in the shared rendering layer, not per ViewModel. One composable, `rememberPremiumActivating()` in `ui/billing/`, reads the `purchaseConfirmed` value of the existing fail-safe `rememberPremiumConfirmed()`, collected with `collectAsStateWithLifecycle()`.
- The shared components (`DailyCapUpsellDialog`, `RadiusPremiumUpsellDialog`, `SoftLimitBanner`, `HardLimitState`) take a `premiumActivating: Boolean` parameter defaulted to it, so every host gets the behavior with no wiring. A test composing one with no Koin context passes the value explicitly.
- The screen-private surfaces (edit upsell, search gate, username gate, the Settings snackbar text, the composer attach one-shot) call the resolver directly.

No ViewModel, `UiState` or repository changes. The retries reuse each surface's existing path (the feed reload, the search retry, the username candidate change). The server stays authoritative: the viewer retries, and the activating notice itself never retries on its own. Pre-existing confirmation-driven re-evaluation, such as search's once-only re-run and the username gate clearing, is unchanged.

The resolution is fail-safe, like the existing consumers (§ "Premium-gated surfaces resolve the signal fail-safe"). With no `PremiumEntitlementSession` bound, every surface renders its Free upsell exactly as before.

Two limits are accepted:
- **The signal lives only in memory.** It stays `true` for the rest of the account's session, so a buyer who later hits the both-tier 500/h like burst limiter sees the activating notice. Before this change they saw an upgrade pitch, which is worse. A cold start inside the lag window resets the signal, and the Free upsell returns until the webhook lands.
- **Tier read once on entry.** A surface that read the tier on screen entry (the composer, Settings) keeps showing the notice until it is reopened after the webhook.

#### Scenario: The activating dialog renders its copy and a single dismiss

- **GIVEN** `PremiumActivatingDialog` composed under `NearYouTheme` (light, then dark) with a recording `onDismiss`
- **WHEN** the dialog renders and "Tutup" is tapped
- **THEN** the tree contains `premium_activating_title`, `premium_activating_body` and a "Tutup" control AND no "Aktifkan Premium" control AND `onDismiss` fires exactly once

#### Scenario: The shared timeline read-cap states follow the signal

- **GIVEN** `SoftLimitBanner` and `HardLimitState` each composed once with `premiumActivating = false` and once with `premiumActivating = true`, with recording `onActivatePremium` / `onRetry`
- **WHEN** each renders and its control is activated
- **THEN** with `false` each shows its limit copy AND an "Aktifkan Premium" control that fires `onActivatePremium` once AND with `true` the banner renders nothing while the hard state shows `premium_activating_body` and a "Coba lagi" control that fires `onRetry` once, never `onActivatePremium`

#### Scenario: An unbound session renders the Free upsell

- **GIVEN** a Koin graph that does not bind `PremiumEntitlementSession`
- **WHEN** a cap host raises the cap dialog, or a composable reads `rememberPremiumActivating()`
- **THEN** the frame-18 upsell renders with its "Aktifkan Premium" CTA AND the resolver reads `false` (no resolution error, no activating notice)

#### Scenario: The resolver follows the signal

- **GIVEN** a bound, signed-in `PremiumEntitlementSession` whose `purchaseConfirmed` is `false` and a composable reading `rememberPremiumActivating()`
- **WHEN** `onPurchaseConfirmed()` runs, and later the session is signed out and `syncIdentity()` resets the signal
- **THEN** the read value becomes `true` and recomposes, and then returns to `false`
