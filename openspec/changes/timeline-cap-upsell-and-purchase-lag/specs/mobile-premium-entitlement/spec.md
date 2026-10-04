## ADDED Requirements

### Requirement: Upsell surfaces show the activating notice during the webhook-lag window

After a confirmed purchase, the server's `users.subscription_status` stays Free until the RevenueCat webhook lands (`subscription-billing-webhook`). In that window a server-gated action can still be refused with a Free answer: a cap `429`, a `403 premium_required` / `radius_premium_only`, or a timeline read-cap flag. `mobile-paywall` accepts that the action may stay gated briefly. What it MUST NOT do is tell someone who just paid to "Upgrade ke Premium".

So while `purchaseConfirmed` is `true`, every Free upsell surface SHALL swap its upgrade pitch for one shared **activating notice**. The notice:
- says the purchase succeeded and Premium is being activated, so the viewer should try again shortly;
- uses two new `:shared:resources` keys, `premium_activating_title` and `premium_activating_body`;
- shows NO "Aktifkan Premium" CTA and pushes no `PaywallRoute`.

The surfaces are:

| Surface | Free upsell | During the lag window |
|---|---|---|
| `DailyCapUpsellDialog` (like / reply / post / chat `429`, every host) | frame-18 cap dialog | `PremiumActivatingDialog` |
| `RadiusPremiumUpsellDialog` (Nearby) | radius upsell dialog | `PremiumActivatingDialog` |
| The post-edit `403 premium_required` upsell | edit upsell dialog | `PremiumActivatingDialog` |
| `SoftLimitBanner` (timeline soft read cap, three feeds) | `timeline_limit_soft` + CTA | `premium_activating_body`, no CTA |
| `HardLimitState` (timeline hard read cap, three feeds) | `timeline_limit_hard` + CTA | `premium_activating_body`, no CTA |
| The search `403` gate panel | `search_premium_gate_body` + CTA | `premium_activating_body`, no CTA |
| The username `403` gate panel | `username_premium_gate_body` + CTA | `premium_activating_body`, no CTA |

`PremiumActivatingDialog` is ONE shared `ui/components/` Material 3 `AlertDialog`. Its title is `premium_activating_title`, its text is `premium_activating_body`, and it has a single "Tutup" (`cta_close`) button. Its `onDismissRequest` and that button both invoke the hoisted `onDismiss`, which each dialog surface wires to the path its own "Tutup" already uses. It holds no navigation reference.

The decision is made in the shared rendering layer, not per ViewModel. It reads one composable, `rememberPremiumActivating()` in `ui/billing/` (the `purchaseConfirmed` value of `rememberPremiumConfirmed()`, collected as Compose state). Each shared component (`DailyCapUpsellDialog`, `RadiusPremiumUpsellDialog`, `SoftLimitBanner`, `HardLimitState`) takes a `premiumActivating: Boolean` parameter defaulted to it, so every host gets the behavior without wiring. A test can also pass it explicitly. The three screen-private gates (edit upsell, search gate, username gate) call the same resolver. No ViewModel, `UiState` or repository changes, and the server stays authoritative: the action is retried by the viewer, not by the client on its own.

The resolution is fail-safe, the same as the existing consumers (§ "Premium-gated surfaces resolve the signal fail-safe"). With no `PremiumEntitlementSession` bound, every surface renders its Free upsell exactly as before.

The signal stays `true` for the rest of the account's session, and a Premium user's only `429` from these endpoints is the both-tier 500/h like burst limiter. So a buyer who later hits that burst limit sees the activating notice rather than an upsell. That is accepted: before this change it showed an upgrade pitch, which is worse.

#### Scenario: The activating dialog renders its copy and a single dismiss

- **GIVEN** `PremiumActivatingDialog` composed under `NearYouTheme` with a recording `onDismiss`
- **WHEN** the dialog renders and "Tutup" is tapped
- **THEN** the tree contains `premium_activating_title`, `premium_activating_body` and a "Tutup" control AND no "Aktifkan Premium" control AND `onDismiss` fires exactly once

#### Scenario: The shared timeline read-cap states swap to the activating notice

- **GIVEN** `SoftLimitBanner` and `HardLimitState` each composed once with `premiumActivating = false` and once with `premiumActivating = true`, with a recording `onActivatePremium`
- **WHEN** each renders
- **THEN** with `false` each shows its limit copy (`timeline_limit_soft` / `timeline_limit_hard`) AND an "Aktifkan Premium" control that fires `onActivatePremium` once AND with `true` each shows `premium_activating_body`, no limit copy and no "Aktifkan Premium" control

#### Scenario: An unbound session renders the Free upsell

- **GIVEN** a Koin graph that does not bind `PremiumEntitlementSession`
- **WHEN** a cap host raises the cap dialog
- **THEN** the frame-18 upsell renders with its "Aktifkan Premium" CTA (no resolution error, no activating notice)

#### Scenario: The resolver follows the signal

- **GIVEN** a bound `PremiumEntitlementSession` whose `purchaseConfirmed` is `false` and a composable reading `rememberPremiumActivating()`
- **WHEN** `onPurchaseConfirmed()` runs, and later a sign-out `syncIdentity()` resets the signal
- **THEN** the read value becomes `true` and recomposes, and then returns to `false`
