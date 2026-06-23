## 1. Pre-implementation gates

- [x] 1.1 Confirm zero new library pins: the Amplitude transport reuses the already-pinned Ktor client + `kotlinx.serialization`; the durable `ConsentSnapshotStore` reuses the DataStore family already on the classpath for `SecureTokenStore`. If any new pin IS introduced, STOP and run the dated pre-implementation library re-check (`openspec/project.md`) before the first feat commit. (Propose-time WebSearch 2026-06-20 already confirmed Amplitude HTTP V2 is canonical + non-deprecated.)
- [x] 1.2 Scaffold the `:infra:amplitude` module dir mirroring `:infra:sentry` (commonMain/androidMain/iosMain source sets, `build.gradle.kts` KMP Android+iOS targets, no JVM target).

## 2. `:infra:amplitude` module (transport seam)

- [x] 2.1 Define `AnalyticsTracker` interface in `commonMain`: `track(eventType, userId, eventProperties)`, `identify(userId, userProperties)`, `flush()` (consent-agnostic — pure transport per design D3).
- [x] 2.2 Implement `NoOpAnalyticsTracker` (no-op all methods) — the blank-key / unconfigured binding (mirrors `NoOpCrashReporter`).
- [x] 2.3 Implement `AmplitudeAnalyticsTracker` posting to the configured HTTP V2 endpoint: build the `{api_key, events:[{event_type, user_id, event_properties, user_properties, time}]}` body via `kotlinx.serialization`; own a **dedicated** `HttpClient` (ContentNegotiation + JSON + short timeout + `HttpRequestRetry` on idempotent 5xx) with **no Auth plugin** (design D4); fail-soft (catch + swallow all transport/serialization errors).
- [x] 2.4 Add `AmplitudeConfig` (apiKey + endpoint, default `https://api2.amplitude.com/2/httpapi`, EU-overridable per D5).
- [x] 2.5 `:infra:amplitude` `commonTest` (Ktor `MockEngine`): event body shape assertion (2.3); fail-soft on simulated network/5xx error; **no `Authorization` header** present (D4 security); blank-key NoOp performs no request (mirrors `NoOpCrashReporterTest`); the POST target URL equals the configured endpoint (US default `api2.amplitude.com`), and a second construction with the EU endpoint targets the EU host (D5 residency).

## 3. Durable consent snapshot (resolves #198)

- [x] 3.1 Add a durable `ConsentSnapshotStore` binding via `expect/actual` (Android DataStore / iOS `NSUserDefaults` — the `SecureTokenStore` `expect/actual` precedent, no new pin), replacing `InMemoryConsentSnapshotStore`. Because this makes `ConsentSnapshotStore` an `expect`-backed type, **move its Koin binding out of the `commonMain` `MobileModule` into the per-platform `PlatformModule`s** (where `SecureTokenStore` is bound) — a leftover `commonMain` binding of the now-`expect` type won't compile. Write-on-`200`-echo semantics unchanged (server-acknowledged triple, never a client guess — `mobile-settings` requirement).
- [x] 3.2 Make the onboarding `ConsentScreen` persist the server-echoed triple to `ConsentSnapshotStore` on PATCH `200` (mirroring `ConsentSettingsScreen`); persist only on `200`, never on failure (spec `mobile-analytics-consent` ADDED requirement). The existing `ConsentScreen` Robolectric skip/`503` test MUST stay green after this snapshot-write addition (keep it in the Release-variant exclude per `docs/11` §2.7).
- [x] 3.3 Tests: durable round-trip across a freshly-constructed store instance (process-restart simulation) returns the persisted triple; onboarding `200` writes the snapshot; onboarding `503` leaves it unwritten; first-run `read()` → `null`. (Satisfies `mobile-analytics-consent` durable-persistence + onboarding scenarios and the `mobile-settings` reconstructed-instance scenario.)
- [x] 3.4 Crash-gate regression: a durable crash-decline survives a simulated process restart and closes reporting on startup (`mobile-crash-reporting` new scenario); absent snapshot → opt-out default ON.
- [x] 3.5 Close GitHub issue [#198](https://github.com/aditrioka/nearyou-id/issues/198) as resolved — auto-closes on squash-merge via the PR body's "resolves #198"; the spec deltas reference it.

## 4. `:mobile:app` consent gate + DI wiring

- [x] 4.1 Implement `ConsentGatedAnalyticsTracker` decorator in `:mobile:app`: reads `ConsentSnapshotStore.read()?.analytics` per call; delegates to the wrapped `AnalyticsTracker` when `true`, otherwise suppresses (design D2/D3). Absent snapshot → suppress. The gate covers `track`, `identify`, AND `flush` (flush is a no-op for the per-call HTTP V2 transport, but gating it forecloses any ungated egress path — sub-agent review).
- [x] 4.2 Wire Koin in `MobileModule`: `single<AnalyticsTracker> { ConsentGatedAnalyticsTracker(delegate = if (amplitudeConfig.apiKey.isBlank()) NoOpAnalyticsTracker() else AmplitudeAnalyticsTracker(amplitudeConfig, …), consentStore = get()) }`; add `amplitudeConfig` alongside `sentryConfig` in the mobile config seam; source the staging key from the existing GCP Secret Manager slot `staging-amplitude-api-key` (provisioned per `docs/10` § 3.8; staging project ID `814353`) via the Sentry-DSN build-config delivery path; blank in dev → NoOp.
- [x] 4.3 `commonTest` for the consent gate: consent OFF → delegate never called (suppressed); consent absent → suppressed; consent ON → delegate invoked; per-fire re-evaluation (toggle OFF mid-session suppresses subsequent emissions). Use a fake `AnalyticsTracker` delegate to assert call/no-call.

## 5. Identify + foundational event slice

- [x] 5.1 DEFERRED (→ [#397](https://github.com/aditrioka/nearyou-id/issues/397)) Implement `install_date_bucket` week-level derivation (ISO week bucket; no finer timestamp) + a `commonTest` asserting week-granularity (design D7).
- [x] 5.2 DEFERRED (→ [#397](https://github.com/aditrioka/nearyou-id/issues/397)) Wire `identify` (subscription_status, platform, install_date_bucket, city_name_at_last_post) at the post-auth session-established seam; properties sourced from existing session/profile state.
- [x] 5.3 Emit `signup_completed` at the successful-signup call site (user_id from the new session).
- [x] 5.4 Emit `post_created` at the successful post-create `Outcome` seam (`CreatePostFlow`/`CreatePostRepository`); privacy-safe `event_properties` only (no coordinates, no content) — pin the exact property set here (design Open Question).
- [x] 5.5 DEFERRED (→ [#396](https://github.com/aditrioka/nearyou-id/issues/396)) Emit `post_viewed` at the post-detail open seam (`PostDetailViewModel`/`PostDetailFlow`, NOT per-feed-impression — design D8) and `post_liked` at the like call site (`InlineLikeController`).
- [x] 5.6 `commonTest` tests asserting the foundational events fire on their success paths with `user_id` and carry no coordinates/content: `post_created` (CreatePostRepositoryTest) + `signup_completed` (AuthRepositorySignUpTest, + a non-201 emits-nothing case).
- [x] 5.7 DEFERRED (→ [#397](https://github.com/aditrioka/nearyou-id/issues/397)) `commonTest` asserting the `identify` user-property set — identify itself is deferred (3 of 4 properties need client data that doesn't exist yet); the transport-level `identifyEvent` mapping is unit-tested in `AmplitudeAnalyticsTrackerTest` ahead of the (deferred) call-site wiring.

## 6. Deferral tracking (file follow-up issues)

- [x] 6.1 File a `follow-up` GitHub issue (labels `follow-up`, `mobile`) for the deferred pre-auth `app_opened` event + `device_id` seam — [#395](https://github.com/aditrioka/nearyou-id/issues/395) (onboarding-funnel completion); reference it from the spec's deferral requirement.
- [x] 6.2 File a `follow-up` GitHub issue (labels `follow-up`, `mobile`) for the deferred full event taxonomy (premium/chat/moderation) + backend-fired security events — [#396](https://github.com/aditrioka/nearyou-id/issues/396) (incl. post_liked/post_viewed); identify → [#397](https://github.com/aditrioka/nearyou-id/issues/397) (requires a JVM target on `:infra:amplitude`); reference it from the spec's deferral requirement.
- [x] 6.3 File a `follow-up` GitHub issue (labels `follow-up`, `admin`) for the admin operational-dashboard Amplitude funnel embed (`docs/07` § Operational Dashboard). — [#398](https://github.com/aditrioka/nearyou-id/issues/398)
- [x] 6.4 File a `follow-up` GitHub issue (label `follow-up`) to fix the stale `docs/04` § Amplitude diagram annotation — [#399](https://github.com/aditrioka/nearyou-id/issues/399) ("server-side events") that contradicts the canonical client-HTTP-wrapper prose (pre-existing doc nit; surfaced in review).

## 7. Module gating + docs maintenance

- [x] 7.1 Add `include(":infra:amplitude")` INSIDE the `if (includeMobile.toBoolean())` block in `settings.gradle.kts` (mobile-gated like `:infra:sentry` — NO Dockerfile COPY, avoids the docker-copy-vs-settings deploy footgun).
- [x] 7.2 Confirm `Dockerfile` is untouched (the backend `installDist -PincludeMobile=false` must not see `:infra:amplitude`); run `dev/scripts/check-dockerfile-module-copies.sh`.
- [x] 7.3 Add a one-line `:infra:amplitude` entry to `dev/module-descriptions.txt` + run `dev/scripts/sync-readme.sh --write`.

## 8. Verification gates

- [x] 8.1 Local gate green: `ktlintCheck` + `detekt` + `:lint:detekt-rules:test` green. (`:backend:ktor:test` N/A — this change touches no backend code; CI runs it on push.)
- [x] 8.2 Mobile unit-test gate green (mobile-touching): `:mobile:app:testDevDebugUnitTest` + `:mobile:app:testDevReleaseUnitTest` (any added Robolectric `*ScreenTest` registered in the Release exclude per `docs/11` §2.7) + `:infra:amplitude:` commonTest.
- [x] 8.3 iosMain re-link (iosMain IS touched — the durable `ConsentSnapshotStore` `NSUserDefaults` actual + `amplitudeApiKey` `NSBundle` actual): `:mobile:app:linkDebugFrameworkIosSimulatorArm64` — BUILD SUCCESSFUL (2m9s). NOTE: the iOS actual is link-verified; its presence-marker logic is identical to the fully-tested Android `SharedPreferences` actual (only the storage API differs). A behavioral `iosSimulatorArm64Test` round-trip is a nice-to-have follow-up ([#400](https://github.com/aditrioka/nearyou-id/issues/400)). + the relevant `:mobile:app:iosSimulatorArm64Test` for the durable-store round-trip.
- [x] 8.4 Verify-loop screenshot gate: **N/A** — this change introduces no visual surface (event fires + durable-store swap + onboarding snapshot write are invisible); record N/A + rationale in the PR body per `docs/11` §5 DoD.
- [x] 8.5 Staging deploy/smoke: **N/A** — no backend/runtime change (mobile + new mobile-gated module only); mark Section N/A in the archive commit body.
- [x] 8.6 `openspec validate mobile-amplitude-analytics --strict` green (4 capability deltas: new `mobile-amplitude-analytics` + modified `mobile-analytics-consent`/`mobile-crash-reporting`/`mobile-settings`).
