# Design: consent-rootrouter-regate

## Context

`RootRouterScreen` (the `RootRoute` start destination) reads the persisted token once via `AuthFlow.isAuthenticated()` and fires one of two injected lambdas — `onAuthenticated` → `replaceAll(HomeRoute)`, `onUnauthenticated` → `replaceAll(SignInRoute)` (routing-as-lambdas, scaffold design Decision 2/6). Consent is only interposed on the signup path (`AgeGateRoute onSignedUp → replaceAll(ConsentRoute)`), so a token-bearing user who never completed consent lands on Home.

Since then, `ConsentSnapshotStore` shipped (#198): a durable per-device store written **only** on a consent `PATCH 200` — by `ConsentViewModel` at onboarding and by `ConsentSettingsViewModel` in settings — and read per-fire by the consent-gated wrappers (analytics tracker, crash gate, ads gate). `read() == null` therefore already means exactly "this device has never seen a completed consent submit".

## Goals / Non-Goals

**Goals:**

- Interpose `ConsentRoute` at launch for an authenticated user who never completed consent (issue #199's action item).
- Keep the router's recording-callback testability and the `replaceAll` auth-boundary discipline.

**Non-Goals:**

- No new persistence (`consent_completed_at` column/flag), no backend or wire change, no consent-GET endpoint.
- No change to `ConsentScreen` itself, its skip affordance, or the settings surface.
- No cross-device consent sync (there is no consent-GET; per-device re-gate is the accepted MVP shape).

## Decisions

1. **Snapshot presence is the completion flag** (vs a new `consent_completed_at` flag). The issue prescribes "`consent_completed_at` (or equivalent)"; `ConsentSnapshotStore.read() != null` is that equivalent, already durable (SharedPreferences / NSUserDefaults), already written at exactly the right moment (`PATCH 200`, before the `done` one-shot). A second flag would be a parallel source of truth to keep in sync for zero benefit.
2. **Third routing lambda `onConsentPending`** (vs an enum-returning resolver or navigating inside the router). Matches the existing two-lambda shape and keeps the router testable with recording callbacks and free of back-stack imports. `AppEntryProvider` wires it to `backStack.replaceAll(ConsentRoute)`.
3. **`replaceAll(ConsentRoute)`, reusing the existing `ConsentRoute` entry.** Auth-boundary transitions use clear-and-set; the stack holds `[ConsentRoute]` alone, so back-press cannot bypass the gate — identical to the signup path, and `onDone → replaceAll(HomeRoute)` already exists. Zero new nav wiring beyond the one lambda.
4. **Check order: token first, snapshot second, only when authenticated.** The unauthenticated branch is untouched; `read()` is synchronous and cheap, called inside the existing `LaunchedEffect` after `isAuthenticated()` returns true. The splash/no-decision-before-read contract (§6.8c) is preserved unchanged.
5. **A post-failure skipper is re-gated on the next launch — intended.** The skip is a non-trapping *session* affordance (spec: "shown only after a failure"), not a durable consent waiver; a skipper never wrote a snapshot. Re-showing the screen with the V2 defaults costs one tap ("Simpan & lanjutkan") and converts an implicit default into an explicit choice.

## Risks / Trade-offs

- [Reinstall / new device / pre-#198 consenting user has no local snapshot → re-gated once despite having consented server-side] → Accepted: there is no consent-GET to reconcile against; the re-shown screen seeds the privacy-safe V2 defaults and `PATCH` is idempotent. One extra submit, no data loss. Pre-launch, so the pre-#198 population is synthetic.
- [Every launch now does one extra synchronous prefs read on the authenticated path] → Negligible (same store the analytics wrapper reads per event).
- [A future consent revision (new category) will NOT re-gate existing snapshot-holders] → Out of scope; a versioned re-gate would MODIFY this requirement when a category is added.
