## RENAMED Requirements

- FROM: `### Requirement: Toggles default to analytics OFF, crash ON, ads OFF (the V2 column default), with no GET round-trip`
- TO: `### Requirement: Toggles default to analytics OFF, crash ON, ads OFF (the V2 column default), then show the server's stored consent until the user acts`

## MODIFIED Requirements

### Requirement: Toggles default to analytics OFF, crash ON, ads OFF (the V2 column default), then show the server's stored consent until the user acts

`ConsentScreen`'s initial toggle state SHALL be `analytics = false`, `crash = true`, `ads_personalization = false`: the `users.analytics_consent` V2 default. The default state SHALL be injectable for testability (the `ConsentViewModel` initial-value parameters), not read from wall-clock or platform state.

On entry, `ConsentViewModel` SHALL issue one consent read (`GET /api/v1/user/consent` via `ConsentFlow.fetchConsent()`):

- **Applying the result.** When the read succeeds before the user has changed any toggle or tapped continue, the toggles SHALL take the server's stored values. The two entry paths behave differently:
  - at signup the server returns the just-created V2 defaults, so nothing visibly changes;
  - at a RootRouter re-gate it shows the account's real choices, so "continue" re-affirms them instead of overwriting a prior decline with the defaults.
- **Late result.** A read that resolves after the user acted SHALL be discarded.
- **Failed read.** A failed read SHALL keep the defaults and show no error.
- **No snapshot write.** The read SHALL NEVER write `ConsentSnapshotStore`. Only a confirmed submit `200` writes it, so the read alone cannot satisfy the RootRouter re-gate's completion flag.

#### Scenario: Default toggle states match the V2 column default

- **WHEN** `ConsentScreen` is composed with no prior interaction and the consent read is unavailable
- **THEN** the Analytics switch is OFF AND the Crash Reporting switch is ON AND the Ads Personalization switch is OFF

#### Scenario: The server's stored consent is shown before the user acts and is never persisted by the read

- **GIVEN** a `ConsentViewModel` over a fake `ConsentFlow` whose read returns `{analytics = true, crash = false, ads_personalization = true}` AND an empty recording `ConsentSnapshotStore`
- **WHEN** the ViewModel is constructed and the read completes with no user interaction
- **THEN** `uiState` shows analytics ON, crash OFF, ads ON AND `ConsentSnapshotStore.read()` is still `null`

#### Scenario: A read that resolves after the user toggled is discarded

- **GIVEN** a `ConsentViewModel` whose consent read suspends
- **WHEN** the user toggles Analytics ON and only then the read resolves with `{analytics = false, crash = false, ads_personalization = false}`
- **THEN** `uiState` still shows Analytics ON, Crash ON, Ads OFF (the user's state, not the read)

### Requirement: The consent snapshot is durably persisted across sessions

The device-local `ConsentSnapshotStore` SHALL be durably persisted across process restarts via a platform `expect/actual` binding (Android `SharedPreferences` / iOS `NSUserDefaults` — non-secret consent flags, the no-new-pin storage family beside `SecureTokenStore`), replacing the prior in-memory binding. The persisted triple SHALL always be a server-acknowledged value: the `PATCH 200` echo (`ConsentResponse`) or the `GET /api/v1/user/consent` read written by the sign-in / settings reconcile. It SHALL never be a client-side guess. The store SHALL also support `clear()`, used by the explicit logout wipe (`mobile-settings`) so consent never carries over to another account on the same device.

This durable persistence resolves GitHub issue [#198](https://github.com/aditrioka/nearyou-id/issues/198) `mobile-analytics-consent-persist-hardening`: a consent choice acknowledged by the server survives process death, so a consumer reading the snapshot (the analytics tracker, the crash gate) honors the user's last server-acknowledged choice across cold starts. Background retry/queue of a *failed* PATCH remains out of scope — the existing in-screen retry + skip is unchanged; a failed submit simply leaves the last acknowledged snapshot in place (or absent, for a first-run user).

#### Scenario: A consent acknowledged before process death survives a restart

- **GIVEN** a successful submit (onboarding or settings) that persists `{analytics = true, crash = true, ads_personalization = false}` to the durable store
- **WHEN** the app is relaunched (a fresh process reads the durable store, not the same in-memory instance)
- **THEN** `ConsentSnapshotStore.read()` returns `{analytics = true, crash = true, ads_personalization = false}` (the persisted triple, not absent and not the V2 default)

#### Scenario: A first-run user with no submit reads an absent snapshot

- **GIVEN** a fresh install where no consent has been submitted
- **WHEN** `ConsentSnapshotStore.read()` is called
- **THEN** it returns `null` (consumers apply their own safe default — analytics suppressed, crash opt-out-default ON)

#### Scenario: A cleared snapshot reads as absent, durably

- **GIVEN** a durable store holding `{analytics = true, crash = false, ads_personalization = true}`
- **WHEN** `clear()` is called and a fresh store instance (simulating a restart) reads it
- **THEN** `read()` returns `null`

### Requirement: RootRouter re-gates token-bearing users who never completed consent

Because consent lives only in the signup→Home transition, a user who force-quits at `ConsentScreen` (or uses the post-failure skip) holds a valid token and would otherwise reach `HomeScreen` on the next launch with consent bypassed. That bypass was deferred as benign while nothing consumed consent; the consent-gated wrappers (analytics tracker, crash gate, ads gate) have since landed, so `RootRouterScreen` SHALL now re-gate.

On launch, after the token read resolves authenticated, `RootRouterScreen` SHALL read `ConsentSnapshotStore.read()` once. When the snapshot is `null`, it SHALL route via an `onConsentPending` lambda, wired in `appEntryProvider` to `backStack.replaceAll(ConsentRoute)`, instead of `HomeRoute`. A `null` snapshot means no server-acknowledged consent is mirrored on this device. The snapshot is written only by:

- a consent submit `200` (the onboarding `ConsentViewModel` and consent settings);
- the sign-in reconcile, which mirrors a returning account's server consent.

Snapshot presence is the completion flag — no separate `consent_completed_at` field SHALL be introduced. The router itself SHALL NOT issue a consent read: the server cannot distinguish never-confirmed defaults from confirmed defaults, so filling the snapshot here would silently satisfy the gate for a same-device skipper.

The rest of the routing contract:

- The re-gated `ConsentRoute` entry is the existing one (`onDone → replaceAll(HomeRoute)`), so the stack holds `[ConsentRoute]` alone and back-press cannot bypass the gate.
- An authenticated user WITH a snapshot routes to `HomeRoute` exactly as before.
- The unauthenticated branch (`SignInRoute`) and the splash/no-decision-before-read contract are unchanged.
- A post-failure skipper never wrote a snapshot and SHALL therefore be re-gated on the next launch — the skip is a non-trapping session affordance, not a durable consent waiver.

This resolves GitHub issue [#199](https://github.com/aditrioka/nearyou-id/issues/199) `mobile-analytics-consent-rootrouter-regate`.

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

## ADDED Requirements

### Requirement: Sign-in reconciles the consent snapshot from the server

On a successful sign-in (`POST /api/v1/auth/signin` → `200`, `SignInOutcome.Success`), `AuthRepository` SHALL reconcile the device consent snapshot from the server through `ConsentReconciler` before returning the outcome:

- read `GET /api/v1/user/consent`;
- on success, write the triple to the durable `ConsentSnapshotStore`;
- apply the `crash` value to `CrashReportingController.applyConsent` at once (close the session on a decline, re-init on consent).

This way a returning account on a new device or a reinstall gets four things:

- it honors its server-side crash decline from sign-in onward, both in the running session and at every later cold start (the `KoinInit` startup gate reads the snapshot);
- it lands on Home without a RootRouter re-gate on the next launch;
- it overwrites any snapshot left by another account.

The reconcile is bounded:

- It SHALL be bounded by a 3-second timeout (`CONSENT_RECONCILE_TIMEOUT`).
- It SHALL run concurrently with the existing RevenueCat identity bind, so the added sign-in wait never exceeds that one budget.
- A failed, non-`200`, or timed-out read SHALL leave the snapshot untouched, apply nothing, and still return `SignInOutcome.Success`.

Sign-up (`SignUpOutcome.Success`) SHALL NOT reconcile: it routes to `ConsentScreen`, whose submit writes the snapshot.

#### Scenario: A successful sign-in writes the server consent and applies the crash decline

- **GIVEN** an `AuthRepository` whose sign-in exchange succeeds, a fake `ConsentFlow` read returning `{analytics = false, crash = false, ads_personalization = false}`, an empty `ConsentSnapshotStore`, and a recording crash-consent callback
- **WHEN** `signInWithGoogle()` completes
- **THEN** it returns `SignInOutcome.Success` AND the snapshot store holds `{analytics = false, crash = false, ads_personalization = false}` AND the crash-consent callback received `false`

#### Scenario: A failed consent read never blocks sign-in

- **GIVEN** a successful sign-in exchange and a consent read that is unavailable (returns `null`)
- **WHEN** `signInWithGoogle()` completes
- **THEN** it returns `SignInOutcome.Success` AND the snapshot store is unchanged AND no crash-consent callback fires

#### Scenario: A hung consent read is abandoned at the timeout

- **GIVEN** a successful sign-in exchange and a consent read that never completes (virtual time)
- **WHEN** `signInWithGoogle()` runs
- **THEN** it returns `SignInOutcome.Success` after `CONSENT_RECONCILE_TIMEOUT` AND the snapshot store is unchanged

#### Scenario: A sign-in overwrites a snapshot left by another account

- **GIVEN** a snapshot store holding `{analytics = true, crash = true, ads_personalization = true}` and a consent read returning `{analytics = false, crash = true, ads_personalization = false}`
- **WHEN** `signInWithGoogle()` completes
- **THEN** the snapshot store holds `{analytics = false, crash = true, ads_personalization = false}`

#### Scenario: Sign-up does not reconcile

- **GIVEN** a successful sign-up exchange and a fake `ConsentFlow` that counts reads
- **WHEN** `signUp(...)` completes with `SignUpOutcome.Success`
- **THEN** zero consent reads were issued
