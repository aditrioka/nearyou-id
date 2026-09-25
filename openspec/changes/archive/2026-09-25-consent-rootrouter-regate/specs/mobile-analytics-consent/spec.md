# mobile-analytics-consent — delta (consent-rootrouter-regate)

## RENAMED Requirements

- FROM: `### Requirement: RootRouter does not re-gate returning token-bearing users on consent completion (deferred)`
- TO: `### Requirement: RootRouter re-gates token-bearing users who never completed consent`

## MODIFIED Requirements

### Requirement: RootRouter re-gates token-bearing users who never completed consent

Because consent lives only in the signup→Home transition, a user who force-quits at `ConsentScreen` (or uses the post-failure skip) holds a valid token and would otherwise reach `HomeScreen` on the next launch with consent bypassed. That bypass was deferred as benign while nothing consumed consent; the consent-gated wrappers (analytics tracker, crash gate, ads gate) have since landed, so `RootRouterScreen` SHALL now re-gate: on launch, after the token read resolves authenticated, it SHALL read `ConsentSnapshotStore.read()` once and, when the snapshot is `null` (no consent `PATCH 200` was ever acknowledged on this device — the snapshot is written only on a `200`, by the onboarding `ConsentViewModel` and by consent settings), route via a `onConsentPending` lambda wired in `appEntryProvider` to `backStack.replaceAll(ConsentRoute)` instead of `HomeRoute`. Snapshot presence is the completion flag — no separate `consent_completed_at` field SHALL be introduced. The re-gated `ConsentRoute` entry is the existing one (`onDone → replaceAll(HomeRoute)`), so the stack holds `[ConsentRoute]` alone and back-press cannot bypass the gate. An authenticated user WITH a snapshot routes to `HomeRoute` exactly as before; the unauthenticated branch (`SignInRoute`) and the splash/no-decision-before-read contract are unchanged. A post-failure skipper never wrote a snapshot and SHALL therefore be re-gated on the next launch — the skip is a non-trapping session affordance, not a durable consent waiver. This resolves GitHub issue [#199](https://github.com/aditrioka/nearyou-id/issues/199) `mobile-analytics-consent-rootrouter-regate`.

#### Scenario: A token-bearing user without a consent snapshot is re-gated to ConsentRoute

- **GIVEN** an `AuthFlow` resolving `isAuthenticated() == true` AND a `ConsentSnapshotStore` whose `read()` returns `null`
- **WHEN** the app launches and `RootRouterScreen` resolves the start destination
- **THEN** `backStack.replaceAll(ConsentRoute)` is invoked (not `HomeRoute`); the visible entry post-route is `ConsentRoute` (the `ConsentScreen` composable), from which `onDone` (a submit `200` or the post-failure skip) routes to `HomeRoute`

#### Scenario: A token-bearing user with a consent snapshot routes to Home unchanged

- **GIVEN** an `AuthFlow` resolving `isAuthenticated() == true` AND a `ConsentSnapshotStore` whose `read()` returns a persisted `ConsentSnapshot`
- **WHEN** the app launches and `RootRouterScreen` resolves the start destination
- **THEN** `backStack.replaceAll(HomeRoute)` is invoked; no `ConsentRoute` is interposed

#### Scenario: An unauthenticated user is not consent-gated

- **GIVEN** an `AuthFlow` resolving `isAuthenticated() == false` (regardless of snapshot state)
- **WHEN** the app launches and `RootRouterScreen` resolves the start destination
- **THEN** `backStack.replaceAll(SignInRoute)` is invoked — the consent check never runs on the unauthenticated branch
