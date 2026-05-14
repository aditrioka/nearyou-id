## Why

`:mobile:app` is still the JetBrains Compose Multiplatform wizard scaffold — a single `MaterialTheme { ... }` with a "Click me!" button and a greeting pulled from the placeholder `:shared:tmp` module. Every subsequent mobile change in [`openspec/project.md`](../../project.md) § Mobile + Admin Scaffolding Priority (Mobile #2-5: Moko Resources, Google Sign-In, age gate, first product screen) needs a real app structure — navigation, DI, and a theming root — to build against. Scaffolding those structures inside the wizard scaffold would force a churn rewrite when the second mobile change lands. This change replaces the wizard once so the next four changes can ship cleanly.

## What Changes

- **Replace** [`mobile/app/src/commonMain/kotlin/id/nearyou/app/App.kt`](../../../mobile/app/src/commonMain/kotlin/id/nearyou/app/App.kt) with an app structure that hosts a typed navigation framework, a Material 3 theme (light + dark, following system preference), and one placeholder start-destination screen.
- **Add** a navigation framework dependency to `:mobile:app` (Voyager vs Decompose vs vanilla state-based — decided in `design.md`).
- **Add** Koin DI to `:mobile:app` (`koin-core` + `koin-compose` + `koin-compose-viewmodel`). Provide a `mobileModule { }` placeholder in commonMain that subsequent changes register bindings into. Wire `startKoin { ... }` from the Android entry point and via a commonMain helper invoked from the iOS entry point.
- **Add** a Material 3 theme wrapper at the navigation host root (light + dark color schemes, follows system preference). Default Material 3 palette is acceptable for the scaffold — brand-specific theming defers to a later change.
- **Add** ONE placeholder `HomeScreen` (name finalized in `design.md`) as the start destination. Renders a centered "NearYouID" label + version text. No network calls. No state machines beyond what the navigation framework requires. This placeholder will be replaced by `mobile-nearby-timeline-screen` (Mobile #5).
- **Remove** the wizard's `:shared:tmp` `Greeting().greet()` usage from `:mobile:app`. `:shared:tmp` itself is preserved (still referenced by `:backend:ktor`); `module-structure` spec's "Existing consumers updated" scenario must be updated to reflect the new consumer list.
- **Keep** both Android (`./gradlew :mobile:app:assembleDebug`) and iOS (KMP framework link tasks) targets green. The shared `App()` composable in commonMain remains the single entry point invoked by `MainActivity` (Android) and `MainViewController` (iOS).
- **Update** [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml) with the new navigation framework and `koin-compose` entries. Pin patch versions per the Pre-Phase 1 Version Pinning Decisions Log convention.

**Explicitly NOT in this change** (deferred to follow-up changes per the Mobile + Admin Scaffolding menu): Moko Resources / shared string catalog (`shared-resources-moko-bootstrap`, Mobile #2), Google Sign-In + JWT storage (`mobile-auth-google-signin-flow`, Mobile #3), age gate (`mobile-age-gate-screen`, Mobile #4), first feature screen with backend data (`mobile-nearby-timeline-screen`, Mobile #5), Sentry KMP wiring (`infra-sentry-kmp-module-isation` follow-up, per the explicit carve-out in [`openspec/project.md`](../../project.md) § Mobile + Admin Scaffolding Priority menu Mobile #1), Ktor client setup, brand-specific theme tokens, and any feature behavior. The scaffold MUST NOT introduce networking, auth, or feature code — this is enforced as a negative requirement in the new capability spec.

## Capabilities

### New Capabilities

- `mobile-app-scaffold`: defines the cross-cutting structure of the `:mobile:app` Compose Multiplatform application — Material 3 theming, typed navigation host, Koin DI wiring, the shared `App()` entry composable, and the explicit no-networking-no-auth-no-features negative constraint that keeps subsequent mobile scaffolding focused.

### Modified Capabilities

- `module-structure`: the "Shared scratch module" requirement's "Existing consumers updated" scenario currently asserts both `mobile/app/build.gradle.kts` and `backend/ktor/build.gradle.kts` reference `projects.shared.tmp`. This change detaches mobile, so the scenario must be updated to reflect that only `backend/ktor/build.gradle.kts` still references it. (`:shared:tmp` itself is preserved; the requirement's intent that consumers migrate to real `:shared:<name>` modules is honored by this change.) Additionally, the "Whole-project build is green" requirement's "no library dependencies added beyond what the wizard already declared" clause is anchored to the bootstrap scaffold state and must be dropped — this change adds Koin Compose and a navigation framework, and future feature changes will continue to add deps inside their module scopes.

## Impact

- **Code**: replaces `mobile/app/src/commonMain/kotlin/id/nearyou/app/App.kt`; touches `mobile/app/src/androidMain/kotlin/id/nearyou/app/MainActivity.kt` (still calls `App()` but may add `startKoin` wiring); touches `mobile/app/src/iosMain/kotlin/id/nearyou/app/MainViewController.kt` (still wraps `App()` but may invoke a commonMain Koin-init helper); updates `mobile/app/build.gradle.kts` with the new navigation + Koin Compose deps; adds new files for the placeholder screen, navigation host, theme module, and the `mobileModule` Koin definition.
- **Dependencies**: adds `koin-compose` and `koin-compose-viewmodel` (versions aligned with the existing Koin 4.1.0 pin in [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml)); adds the chosen navigation framework's dependency (Voyager recommended, decided in `design.md`).
- **Specs**: creates `openspec/specs/mobile-app-scaffold/spec.md`; modifies `openspec/specs/module-structure/spec.md` (Shared scratch module + Whole-project build requirements per § Modified Capabilities).
- **Docs**: no immediate doc updates required — [`docs/04-Architecture.md`](../../../docs/04-Architecture.md) § Mobile Status text describing the wizard scaffold will be naturally outdated once this change lands; that doc update can land as a small follow-up commit during `/opsx:apply` or in a separate docs PR. [`openspec/project.md`](../../project.md) § Mobile + Admin Scaffolding Priority menu's Mobile #1 entry should be marked complete once this archives (the section's flip-trigger condition remains tied to Mobile #5 + Admin #4, not #1).
- **CI**: `./gradlew :mobile:app:assembleDebug` continues to be the canonical Android-side smoke target on every push. The CI workflow does NOT currently run iOS framework link tasks; if iOS CI is not yet wired, do NOT block this change on it — open a Section 6 follow-up task to enable iOS CI later, and verify the iOS build locally instead.
- **Other modules**: `:backend:ktor`'s usage of `:shared:tmp` is unchanged. The `:shared:tmp` module itself is preserved.
- **Migrations**: none. No DB schema changes.
- **Risk surface**: low — pure mobile-scaffold work, no backend behavior change, no security invariant touched, no rate-limit math, no Detekt rule changes. The negative requirement ("no networking, no auth, no feature behavior") is the main guard against scope drift mid-implementation.
