# M1 — mobile shell, auth/onboarding, settings, consent/analytics, test infra

### A. Completion matrix
| Capability | Reqs | Completion | Biggest gap |
|---|---|---|---|
| mobile-app-scaffold | 8 | ~95% | iOS test run not CI-gated → K/N DI drift unnoticed (#348) |
| mobile-auth-signin | 15 | ~95% | `AuthRepository` diagnostics never wired (C3) |
| mobile-age-gate | 13 | ~98% | — |
| mobile-analytics-consent | 13 | ~92% | no iOS behavioral test for durable store (#400); onboarding crash decline not applied to live session (C1) |
| mobile-amplitude-analytics | 7 | ~100% of spec | foundational-only spec; taxonomy/identify/app_opened spec'd deferrals (#395–397); keys blank per flavor unless CI-injected (`mobile/app/build.gradle.kts:273,312`) |
| mobile-settings | 19 | ~90% | spec'd deferred rows lost their tracking issue (C5); legal row + iOS settings-nav flow untested (C7) |
| mobile-location | 11 | ~95% | — (`LocationSourceGuardTest.kt:57-152`, `CachingLocationProviderTest`) |
| mobile-home-tab-host | 15 | ~92% | iOS timeline flow tests red (#348) |
| mobile-design-system | 8 | ~92% | runtime language switch spec'd deferral (#203) |
| mobile-crash-reporting | 8 | ~70% | onboarding consent decline (C1), logout clear-user (C2), sign-in diagnostics (C3), symbol upload (C4) all missing |
| shared-resources | 19 | ~90% | catalog guard stopped guarding (#479) |
| mobile-appeal | 5 | ~95% | deferred permanent-ban form untracked (C5) |
| private-profile + hide-distance (client) | 2+2 | ~95% | GET-seeded, Premium-gated, revert-on-fail (`SettingsViewModel.kt:94-117,152-208`, `SettingsScreenTest.kt:364-531`) |

**RevenueCat seam (confirms M3 C1):** `KoinInit.kt:59-63` `configureRevenueCat(apiKey, appUserId = null)`; no `logIn(`/`logOut(` in `mobile/app/src` or `infra/revenuecat/src`. Sign-in (`AuthRepository.kt:117,202`) sets only the crash user; logout (`SettingsViewModel.kt:122-146`) wipes tokens only. Anonymous RC customer survives logout → second account on same device inherits first account's client-side entitlement.

### B. Follow-up validation
| # | Classification | Evidence | Scope |
|---|---|---|---|
| 186 | still-valid-defer | `GoogleSignInClient.kt:36-54` Credential-Manager-only | Trigger: post-launch `GoogleSignInResult.Failed` rate signal — can't fire today because the Failed diagnostic is dropped (C3); fix C3 first, consider `captureMessage` on Failed |
| 189 | **superseded** | every intra-tab destination shipped as root-stack push (`AppEntryProvider.kt:151-153`); mobile-home-tab-host § "The tab host hoists onOpenPost…root-stack PostDetailRoute push", § onOpenSearch / § onOpenProfile re-assert "no per-tab NavDisplay" (spec:45,229) | close |
| 203 | still-valid-defer | only `composeResources/values/` (no `values-en`); mobile-design-system § "Runtime user-selectable language switching is deferred" | Trigger: operator decision to ship a non-ID locale |
| 204 | still-valid-openspec | no hint copy in `strings.xml`, no surface; copy at `docs/03:16` | one-time dismissible hint on first Nearby entry, persisted via device-prefs (like `DurableConsentSnapshotStore`). MODIFY mobile-nearby-timeline. Robolectric + VM tests |
| 266 | still-valid-openspec (part 2 only) | part 1 resolved (`DurableConsentSnapshotStore` android/ios, `PlatformModule.kt:41`, spec § "The consent snapshot is durably persisted"); part 2: `ConsentRoutes.kt:47` PATCH-only | `GET /api/v1/user/consent`, seed `ConsentSettings`, reconcile snapshot after sign-in. Now load-bearing: on new device/reinstall the RootRouter re-gate (`RootRouterScreen.kt:50`) + crash startup gate (`KoinInit.kt:79`) fall back to V2 defaults, overriding a server-side crash decline. MODIFY analytics-consent-update, mobile-settings § "initialize from the last-submitted snapshot", mobile-analytics-consent § re-gate |
| 395 | still-valid-openspec | no `app_opened` / device_id | persist device_id UUID + emit `app_opened`; design decision: pre-auth no consent snapshot → `ConsentGatedAnalyticsTracker` suppresses — queue-until-consent vs emit on first consented launch. MODIFY § "Pre-authentication app_opened…deferred" |
| 396 | still-valid-openspec | only `CreatePostRepository.kt:82` + `AuthRepository.kt:205` emit | `post_liked`, `paywall_viewed`/`subscription_purchased`, chat/moderation events; backend-fired events (need JVM target on `:infra:amplitude`) as separate change. Roadmap Phase 3 (`docs/08:161`) |
| 397 | still-valid-openspec | identify gate exists (`ConsentGatedAnalyticsTracker.kt:36`) but uncalled; no first-launch date / entitlement state | `platform` from `devicePlatform` (`MobileModule.kt:29`), `subscription_status` from self-profile or `premium` flag; persist first-launch date; identify after sign-in |
| 400 | still-valid-regular-pr | iosMain `DurableConsentSnapshotStore.kt` exists; no `iosTest/.../data/consent` | `iosSimulatorArm64Test` round-trip for the three cases, clearing `nearyou_consent_*` keys |
| 479 | still-valid-regular-pr | `strings.xml` 356 entries vs `SharedStringsCatalogTest.kt:569` pins 237 | backfill accessors or derive count structurally (`Res.allStringResources` vs XML key count) |
| 184 | still-valid-openspec (small) | Detekt scans only `src/main/kotlin` of backend/JVM modules (`nearyou.ktor.gradle.kts:20`, `nearyou.detekt.gradle.kts:15`); grep today = zero offenders | `MobileHardcodedStringRule` in `:lint:detekt-rules` (allow `Res.string.*` + `// hardcoded-string-allow:`) + detekt task over mobile `{common,android,ios}Main`. MODIFY shared-resources § "…verified by grep (Detekt rule deferred)" |
| 275 | still-valid-regular-pr | no `androidInstrumentedTest` source set, no `testInstrumentationRunner`; workflows ci/claude/deploy-staging/device-run only | enable variant, smoke test, `instrumented-test.yml` reusing `GCP_TESTLAB_SA_KEY` |
| 348 | still-valid-regular-pr | drift grew: `NearbyTimelineFlowIosTest.kt:60-64` lacks LikeFlow/Report/BlockSubmitter; `RootRouterFlowIosTest.kt:44-65` lacks ConsentSnapshotStore; `AppShellFlowIosTest` lacks FcmTokenRegistrar; NEW `DataExportFlowIosTest.kt:53-60` lacks PrivateProfileRepository (#436) | register the fakes; add at least `compileTestKotlinIosSimulatorArm64` (ideally `iosSimulatorArm64Test` on macOS runner) to `ci.yml` |

### C. New gaps
1. **[high]** Declining crash reporting on onboarding consent (and RootRouter re-gate) doesn't stop Sentry for the current session — `ConsentViewModel.onContinueClick` only writes the snapshot; `ConsentSettingsScreen.kt:62` wires `crashController::applyConsent` but onboarding doesn't; startup gate (`KoinInit.kt:79-81`) runs only at process start → UU-PDP decline ignored until relaunch — mobile-crash-reporting § Consent gating honors the crash consent category ("On decline the app MUST stop reporting for the session") — `screens/consent/ConsentViewModel.kt:96-108` — regular-pr (`onCrashConsentChanged` callback + VM test)
2. **[medium]** Voluntary logout never clears Sentry user ("Keluar" wipes `tokenStore` directly; `clearUser()` only on involuntary `SessionInvalidator.kt:48`, only that path tested `AuthRepositoryTest.kt:419-425`); cold start with persisted token never calls `setUser` — mobile-crash-reporting § User correlation ("Logout clears the user") — `SettingsViewModel.kt:122-146` — regular-pr
3. **[medium]** `AuthRepository` is the only repository built without `diagnosticLog` (no-op default `AuthRepository.kt:58`) → `google_sign_in_failed`, `signin_network_error`, `signup_*` never reach breadcrumbs — mobile-crash-reporting § Repository diagnostics surface as breadcrumbs — `di/MobileModule.kt:211-222` — regular-pr (+ extend `DiagnosticSinkWiringTest`)
4. **[low]** Sentry Gradle plugin not applied (KMP lib only, `libs.versions.toml:291`; doc at `dev/docs/sentry-symbol-upload.md`); no open issue tracks the operator symbol-upload task the spec says must "remain tracked" — mobile-crash-reporting § Symbolication artifacts are configured; CI upload is operator-provisioned — regular-pr (file operator issue)
5. **[low]** Spec'd deferrals lost tracking: "Perjalanan Premium" + "Kelola langganan" (`SettingsScreen.kt:322-333`, `onComingSoon`) were tracked by #267, closed COMPLETED 2026-06-17 while still deferred; permanent-ban in-app appeal form has no open issue (#391 closed). "Kelola langganan" matters to paying users — mobile-settings § deferred rows ("Each deferred row SHALL be tracked…"), mobile-appeal § Permanent-ban appellants — regular-pr (re-file issues)
6. **[low]** mobile-settings Purpose + § ownership still say profile gear deferred to #288 — shipped (`AppEntryProvider.kt:153`, `ProfileScreenTest.kt:108`) — openspec (spec hygiene)
7. **[low]** Missing spec'd tests: legal row (`SettingsScreen.kt:401`, no `SettingsScreenTest` case); iOS flow "open settings → block list → consent → back" absent (`BlockedUsersFlowIosTest.kt:55` isolated render, no ConsentSettings iOS test). (iOS Hapus-Akun flow test also missing but may overlap in-flight #347 — not claimed) — mobile-settings § legal/privacy row; § Settings ships its test trio — regular-pr

### D. Unspecced product surface
1. **Sign in with Apple on iOS** — docs/03 § User Onboarding Flow › Auth Flow: launch-required for App Review; mobile specs only forbid its identifiers ("separate later change", mobile-app-scaffold:118); no issue
2. **Guest Global browse without login** — 10 posts then "Login untuk lihat lebih banyak" + login wall on Nearby/Following/actions (docs/03 § First App Open / Login Wall); backend guest variant spec'd deferral (global-timeline spec:275-277); no mobile spec; RootRouter sends tokenless users straight to SignIn
3. **Onboarding carousel** (frames 10–12: sekitar / privasi lokasi / chat 1:1) — no spec, no impl
4. **Post-signup username reveal** "Username kamu: @{username}…" (docs/03 § Username Auto-Generate) — no string, no screen
5. **Onboarding FAQ / account-loss disclosure** (docs/02:37) — only one-line `account_separation_disclosure` on SignIn (`SignInScreen.kt:185`); no FAQ
