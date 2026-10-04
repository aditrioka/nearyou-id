## MODIFIED Requirements

### Requirement: The composer image-attach affordance is Premium-gated with a Free upsell

`PostCreationScreen` SHALL render an image-attach affordance whose enabled/visible behavior is driven by the viewer's already-known subscription status: a Premium viewer sees an active attach affordance; a Free viewer sees the affordance route to the shared cap-upsell/paywall surface (the `mobile-cap-upsell-dialog` / paywall pattern) rather than opening the picker. No new Firebase Remote Config client SHALL be introduced; the backend remains the authority on the `image_upload_enabled` flag (a `FeatureDisabled` outcome renders a "not available yet" state). Every UI string SHALL be sourced via `:shared:resources` `Res.string.*`.

During the post-purchase webhook-lag window (`mobile-premium-entitlement` § "Upsell surfaces show the activating notice during the webhook-lag window") a viewer the composer resolved as Free has in fact just bought, typically on the `IMAGE_ATTACH` paywall this affordance opened. The composer reads the tier once, on entry. In that window the attach tap SHALL show the shared `PremiumActivatingDialog` instead of routing to the paywall, so a buyer is never sent to buy again. The picker is still not invoked. The screen's existing Free-attach one-shot is the dialog's visibility, and "Tutup" clears it. There is no new state and no ViewModel change.

#### Scenario: Premium viewer opens the picker

- **WHEN** a Premium viewer activates the attach affordance
- **THEN** the platform image picker is invoked (`ImagePicker.pick()`)

#### Scenario: Free viewer is upsold, not given the picker

- **WHEN** a Free viewer activates the attach affordance
- **THEN** the shared cap-upsell/paywall surface is shown AND `ImagePicker.pick()` is NOT invoked

#### Scenario: No hardcoded UI strings in the attach surface

- **WHEN** inspecting the image-attach composable source
- **THEN** every UI-string-bearing call site sources its text via `stringResource(Res.string.<name>)`; zero literal string arguments appear

#### Scenario: A buyer in the lag window gets the activating notice, not a second paywall

- **GIVEN** a composer whose on-entry tier read is Free AND a Koin graph binding a `PremiumEntitlementSession` on which `onPurchaseConfirmed()` has run, with a recording `onActivatePremium`
- **WHEN** the viewer activates the attach affordance and then taps the notice's "Tutup"
- **THEN** the activating notice is shown and then closes AND `onActivatePremium` is never invoked AND `ImagePicker.pick()` is NOT invoked
