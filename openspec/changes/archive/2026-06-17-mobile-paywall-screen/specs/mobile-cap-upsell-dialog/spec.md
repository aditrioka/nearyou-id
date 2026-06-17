## RENAMED Requirements

- FROM: `### Requirement: Premium CTA navigation is deferred`
- TO: `### Requirement: Premium CTA navigates to the paywall`

## MODIFIED Requirements

### Requirement: Premium CTA navigates to the paywall

Tapping "Aktifkan Premium" SHALL invoke the hoisted `onActivatePremium` callback; every host surface SHALL wire that callback to push `PaywallRoute(entry = PaywallEntry.LIKE_CAP)` onto the root back stack (the `mobile-paywall` capability — mockup frame 17, `docs/03-UX-Design.md` § Paywall & Premium Disclosure) AND dismiss the dialog. The `DailyCapUpsellDialog` component itself SHALL remain navigation-free: it holds no back-stack reference and performs NO navigation side-effect of its own — it only invokes the hoisted `onActivatePremium` and `onDismiss`. The navigation is owned by the host surface (the feed / post-detail surface that showed the dialog), keeping the component a pure, reusable presentation piece. This resolves the v1 dismiss-only placeholder: the CTA is no longer a dead-end, and GitHub issue [#235](https://github.com/aditrioka/nearyou-id/issues/235) `mobile-paywall-screen` (the former `follow-up`) is closed by the change that introduces this behavior.

#### Scenario: The Premium CTA invokes the hoisted callback and the host pushes the paywall

- **GIVEN** the dialog shown on a feed surface whose host wires `onActivatePremium` over a test root back stack
- **WHEN** the "Aktifkan Premium" button is tapped
- **THEN** `onActivatePremium` fires exactly once AND the host appends `PaywallRoute(entry = PaywallEntry.LIKE_CAP)` to the root back stack AND the dialog is dismissed

#### Scenario: The dialog component itself holds no navigation reference

- **WHEN** inspecting `DailyCapUpsellDialog.kt`
- **THEN** the component holds no back-stack reference and performs no navigation itself — it only invokes the hoisted `onActivatePremium` / `onDismiss` (navigation is the host's responsibility)

#### Scenario: Scrim/back dismissal still behaves as Tutup and does not navigate

- **GIVEN** the dialog composed with recording `onDismiss` / `onActivatePremium` callbacks over a test root back stack
- **WHEN** the dialog's `onDismissRequest` fires (scrim tap / back)
- **THEN** `onDismiss` is invoked exactly once AND `onActivatePremium` is not invoked AND no `PaywallRoute` is appended to the back stack
