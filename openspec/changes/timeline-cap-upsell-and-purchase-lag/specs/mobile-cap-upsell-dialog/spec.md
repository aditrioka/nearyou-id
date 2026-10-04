## MODIFIED Requirements

### Requirement: Premium CTA navigates to the paywall

Tapping "Aktifkan Premium" SHALL invoke the hoisted `onActivatePremium` callback. Every host surface SHALL wire that callback to dismiss the dialog AND push `PaywallRoute(entry = <the cap's entry>)` onto the root back stack (the `mobile-paywall` capability — mockup frame 17, `docs/03-UX-Design.md` § Paywall & Premium Disclosure). The entry SHALL name the cap that fired:

| Cap | Hosts | Entry |
|---|---|---|
| like | the three feeds, post-detail | `PaywallEntry.LIKE_CAP` |
| reply | post-detail | `PaywallEntry.REPLY_CAP` |
| post | the composer | `PaywallEntry.POST_CAP` |
| chat | the chat thread, the share-to-chat picker | `PaywallEntry.CHAT_CAP` |

**The post-purchase webhook-lag window** (`mobile-premium-entitlement` § "Upsell surfaces show the activating notice during the webhook-lag window"). When the `purchaseConfirmed` signal is `true` at the moment a cap `429` would raise this dialog, the buyer has paid and only the server tier still lags. The dialog SHALL then render the shared `PremiumActivatingDialog` instead of the upsell: the `premium_activating_title` / `premium_activating_body` copy and a single "Tutup" button wired to `onDismiss`. It SHALL show no "Aktifkan Premium" CTA, no cap body and no countdown, and `onActivatePremium` is unreachable. The dialog reads the signal itself (a defaulted `premiumActivating` parameter resolved through the fail-safe `rememberPremiumActivating()`), so every host gets this behavior with no host or ViewModel change.

The `DailyCapUpsellDialog` component itself SHALL remain navigation-free: it holds no back-stack reference and performs NO navigation side-effect of its own — it only invokes the hoisted `onActivatePremium` and `onDismiss`. The navigation is owned by the host surface, keeping the component a pure, reusable presentation piece. (This resolved the v1 dismiss-only placeholder — issue [#235](https://github.com/aditrioka/nearyou-id/issues/235) — and is extended to every cap by issue [#493](https://github.com/aditrioka/nearyou-id/issues/493).)

#### Scenario: The Premium CTA invokes the hoisted callback and the host pushes the paywall

- **GIVEN** the dialog shown on a feed surface whose host wires `onActivatePremium` over a test root back stack
- **WHEN** the "Aktifkan Premium" button is tapped
- **THEN** `onActivatePremium` fires exactly once AND the host appends `PaywallRoute(entry = PaywallEntry.LIKE_CAP)` to the root back stack AND the dialog is dismissed

#### Scenario: Each cap host pushes its own entry

- **GIVEN** the cap dialog raised on post-detail by a reply `429`, on the composer by a post `429`, and on the chat thread by a send `429`, each under the real `appEntryProvider`
- **WHEN** "Aktifkan Premium" is tapped on each
- **THEN** the root back stack's top entry is respectively `PaywallRoute(REPLY_CAP)`, `PaywallRoute(POST_CAP)`, and `PaywallRoute(CHAT_CAP)` AND the dialog is dismissed

#### Scenario: The dialog component itself holds no navigation reference

- **WHEN** inspecting `DailyCapUpsellDialog.kt`
- **THEN** the component holds no back-stack reference and performs no navigation itself — it only invokes the hoisted `onActivatePremium` / `onDismiss` (navigation is the host's responsibility)

#### Scenario: Scrim/back dismissal still behaves as Tutup and does not navigate

- **GIVEN** the dialog composed with recording `onDismiss` / `onActivatePremium` callbacks over a test root back stack
- **WHEN** the dialog's `onDismissRequest` fires (scrim tap / back)
- **THEN** `onDismiss` is invoked exactly once AND `onActivatePremium` is not invoked AND no `PaywallRoute` is appended to the back stack

#### Scenario: A confirmed purchase swaps the cap upsell for the activating notice

- **GIVEN** the dialog composed with `premiumActivating = true` and recording `onDismiss` / `onActivatePremium` callbacks
- **WHEN** the dialog renders and "Tutup" is tapped
- **THEN** the rendered tree contains `premium_activating_title` and `premium_activating_body` AND contains no "Aktifkan Premium" node, no `cap_dialog_title` and no countdown AND `onDismiss` fires exactly once AND `onActivatePremium` is never invoked

#### Scenario: A host reads the signal with no host wiring

- **GIVEN** a cap host screen (the Global feed) whose Koin graph binds a `PremiumEntitlementSession` on which `onPurchaseConfirmed()` has run
- **WHEN** an inline like returns the Free like-cap `429`
- **THEN** the activating notice is shown in place of the like-cap upsell AND no `PaywallRoute` is pushed
