# Proposal: consent-rootrouter-regate

## Why

Consent lives only in the signup→Home transition, so a user who force-quits at `ConsentScreen` (or uses the post-failure skip) holds a valid token and is routed straight to `HomeRoute` on the next launch — consent bypassed. That was deliberately deferred as benign for MVP (issue [#199](https://github.com/aditrioka/nearyou-id/issues/199)) *until the suppress-wrappers land*. They have landed (`ConsentGatedAnalyticsTracker`, the crash gate, the ads gate all read `ConsentSnapshotStore`), so a bypassing user now permanently sits at the implicit V2 defaults instead of ever making an explicit choice. The issue's trigger condition is met; this change ships the re-gate it prescribes.

## What Changes

- `RootRouterScreen` gains a consent-pending routing branch: an authenticated user **without a persisted consent snapshot** (`ConsentSnapshotStore.read() == null`) is routed to `ConsentRoute` instead of `HomeRoute`. Snapshot presence is the "`consent_completed_at` or equivalent" completion flag the issue prescribes — the snapshot is written only on a consent `PATCH 200` (by `ConsentViewModel` at onboarding and by consent settings), so `null` ⇔ "never completed a consent submit". No new flag, no schema change.
- `AppEntryProvider` wires the new `onConsentPending` lambda to `backStack.replaceAll(ConsentRoute)` (auth-boundary transition — same `replaceAll` discipline as the other two branches). The existing `ConsentRoute` entry (`onDone → replaceAll(HomeRoute)`) is reused unchanged.
- The `mobile-analytics-consent` spec requirement "RootRouter does not re-gate returning token-bearing users on consent completion (deferred)" is MODIFIED into the active re-gate requirement, flipping its negative-guard scenario.
- A user who skipped after a submit failure never wrote a snapshot and is therefore re-gated on the next launch — intended: the skip is a non-trapping *session* affordance, not a permanent consent waiver.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `mobile-analytics-consent`: the deferred "RootRouter does not re-gate returning token-bearing users on consent completion" requirement becomes "RootRouter re-gates token-bearing users who never completed consent" — authenticated + no consent snapshot → `ConsentRoute`; authenticated + snapshot present → `HomeRoute` (unchanged); unauthenticated → `SignInRoute` (unchanged).
- `mobile-auth-signin`: "RootRouterScreen routes based on token presence" is reconciled — the token-present branch now notes the consent-pending interposition (delegating its detail to `mobile-analytics-consent`) and its Home-routing scenarios gain a snapshot-present GIVEN; the presence-only token gate, splash contract, and unauthenticated branch are unchanged.

## Impact

- **Code**: `mobile/app/src/commonMain/.../screens/routing/RootRouterScreen.kt` (inject `ConsentSnapshotStore`, third routing lambda), `AppEntryProvider.kt` (wire `onConsentPending`), `RootRouterScreenTest.kt` (new routing coverage; existing authenticated test gains a snapshot-present fixture).
- **Layers**: mobile-only. Backend/admin: none — consent submission, the `PATCH` endpoint, and the snapshot store contract are unchanged (docs/12 cohesion: this is the mobile-hardening leg of an already-shipped vertical slice).
- **Issue**: closes [#199](https://github.com/aditrioka/nearyou-id/issues/199).
