# Tasks: consent-rootrouter-regate

## 1. Router re-gate

- [ ] 1.1 `RootRouterScreen.kt`: inject `ConsentSnapshotStore` (koinInject, alongside `AuthFlow`); add the third routing lambda `onConsentPending: () -> Unit`; in the `LaunchedEffect`, when `isAuthenticated()` is true, call `snapshotStore.read()` — `null` → `onConsentPending()`, non-null → `onAuthenticated()`; unauthenticated branch unchanged. Update the KDoc (routing contract + why snapshot presence is the completion flag).
- [ ] 1.2 `AppEntryProvider.kt`: wire `onConsentPending = { backStack.replaceAll(ConsentRoute) }` on the `RootRoute` entry; update the entry-map KDoc line for `RootRoute` (and the `ConsentRoute` comment to note it is also reached from the re-gate).

## 2. Tests (RootRouterScreenTest — Robolectric CMP runner)

- [ ] 2.1 Bind a `ConsentSnapshotStore` in the test Koin module (`InMemoryConsentSnapshotStore`, pre-seeded or empty per case); the existing `authenticated_routesToHome` test gains a snapshot-present fixture.
- [ ] 2.2 New test: authenticated + `read() == null` → `ConsentScreen` visible (consent marker, e.g. the CTA "Simpan & lanjutkan"), HOME_MARKER + SIGNIN_MARKER absent (spec scenario "A token-bearing user without a consent snapshot is re-gated to ConsentRoute").
- [ ] 2.3 Confirm `unauthenticated_routesToSignIn` and the splash in-flight test pass unchanged (unauthenticated branch never reads the snapshot; no-decision-before-read contract preserved).

## 3. Spec sync + verification

- [ ] 3.1 `openspec validate consent-rootrouter-regate --strict` passes.
- [ ] 3.2 Gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest`.
- [ ] 3.3 Manual verify (UI-affecting, docs/11 §5 DoD): on the emulator, sign in → force-quit at `ConsentScreen` → relaunch → assert the consent screen re-appears; complete it → relaunch → assert Home. Screenshot evidence in the PR body.
- [ ] 3.4 PR body carries `Closes #199`; title/body current at each phase boundary.
