## Context

The analytics-consent onboarding screen (`mobile-analytics-consent`, [#157](https://github.com/aditrioka/nearyou-id/pull/157)) and its Settings counterpart collect a per-user `analytics` toggle. The toggle value is mirrored locally in `ConsentSnapshotStore` (there is no consent-GET endpoint — the snapshot is the client-side mirror; per `mobile-settings` it stores **the server-echoed `PATCH 200` triple, never a client guess**) and written to the server via `PATCH /api/v1/user/consent`. No code consumes the `analytics` value yet — the consent spec scoped that out. The crash-consent half is consumed by `:infra:sentry` (`mobile-sentry-crash-reporting`, [#299](https://github.com/aditrioka/nearyou-id/pull/299)), whose shape — a `commonMain` interface (`CrashReporter`), a `NoOp*` default, a single implementation, Koin-bound with a config object (`sentryConfig`), included inside `settings.gradle.kts`'s `if (includeMobile.toBoolean())` block — is the template this change follows.

**The blocking constraint surfaced during reconciliation:** the tracker needs a consent value that survives cold starts, but `ConsentSnapshotStore`'s production binding is `InMemoryConsentSnapshotStore` (lost on process death) and only the **Settings** screen writes it (not onboarding). Sentry tolerates this because crash is opt-out (absent → default ON); analytics is opt-in (absent → must suppress), so gating on the in-memory snapshot would suppress for nearly every user (anyone who set consent only at onboarding, and every cold start). Both `mobile-crash-reporting` (its absent-snapshot scenario) and `mobile-settings` (its snapshot requirement) already name durable persistence "consuming the same `ConsentSnapshotStore` seam" as issue [#198](https://github.com/aditrioka/nearyou-id/issues/198). This change resolves #198 as the prerequisite for a reliable gate.

`docs/04` § Amplitude specifies the integration as a KMP HTTP API wrapper (not a vendor SDK), consent-gated, with a defined user-property set and event taxonomy. `docs/06` § Analytics & Tracking Consent requires silent suppression and immediate application of Settings toggle changes.

## Goals / Non-Goals

**Goals:**
- A vendor-SDK-free `:infra:amplitude` module exposing an `AnalyticsTracker` seam + an Amplitude HTTP V2 transport, fail-soft.
- A durable `ConsentSnapshotStore` (resolves #198) so the consent gate is reliable across cold starts; onboarding writes the snapshot.
- A consent gate that **silently suppresses** all emission when analytics consent is off, re-evaluated per fire (immediate effect of Settings changes).
- `identify` with the `docs/04` user-property set; a **foundational, post-auth event slice** wired at existing call sites.
- Zero new library pins; `:infra:amplitude` mobile-gated (no `Dockerfile` impact).
- The Pre-Launch "Amplitude opt-out silent" test, satisfied with a deterministic unit test.

**Non-Goals (deferred — see proposal + spec deferral requirements):**
- The pre-auth `app_opened` event and the `device_id` seam it requires.
- The full event taxonomy (premium / chat / moderation).
- Backend-fired security events (would force a JVM target on `:infra:amplitude`).
- The admin operational-dashboard Amplitude funnel embed.
- The separate RootRouter consent re-gate ([#199](https://github.com/aditrioka/nearyou-id/issues/199)) — stays deferred.
- Background retry/queue of a **failed** consent PATCH — out of scope; #198 here means *durable persistence of the server-acknowledged snapshot*, the resolution the consent specs anticipate. A failed submit still surfaces the existing retryable in-screen error and leaves the last acknowledged snapshot in place (privacy-safe: absent/first-run → suppressed).

## Decisions

**D1 — Amplitude HTTP V2 API wrapper, not a vendor SDK.** `AmplitudeAnalyticsTracker` POSTs to the Amplitude HTTP V2 ingestion endpoint using a Ktor client + `kotlinx.serialization`. Keeps `:infra:*` vendor-SDK-free, needs no expect/actual in the transport (pure `commonMain`), introduces **zero new pins**. *Alternative — the Amplitude Kotlin/Android SDK:* rejected (native Android dep, breaks iOS parity + the DIY-wrapper posture; `docs/04` prescribes the HTTP wrapper). *Verified 2026-06-20 (dated WebSearch):* HTTP V2 (`/2/httpapi`) is current/non-deprecated, authenticates via `api_key` in the body (no auth header), body `{api_key, events:[{event_type, user_id, event_properties, user_properties, time}]}`, ≤1 MB / <2000 events / 30 events·s⁻¹·device⁻¹ — all within a low-volume foundational slice.

**D2 — Durable `ConsentSnapshotStore` via `expect/actual` (resolves #198); write-on-`200`-echo preserved.** Replace `InMemoryConsentSnapshotStore` with a durable binding — Android DataStore / iOS `NSUserDefaults`, the **same no-new-pin storage family backing `SecureTokenStore`** (the established `interface` + per-platform actual seam, `docs/11` §2.5). Write semantics are **unchanged**: the persisted triple is the server-echoed `PATCH 200` body, never a client guess (`mobile-settings` hard requirement — this rules out optimistic local-first writes). The **onboarding** `ConsentScreen` now also writes the snapshot on its `200` (today only Settings does), so a user who consents at onboarding has a durable snapshot. *Consequence:* the Sentry crash gate, reading the same seam, gains durable cross-process decline for free — the resolution `mobile-crash-reporting` explicitly anticipated. *Alternative — server-authoritative consent field on a profile/`me` response:* rejected by the operator in favor of the mobile-only durable path (no backend coupling; converts deferred debt #198 into done).

**D3 — The consent gate lives in `:mobile:app` as a decorator; `:infra:amplitude` stays consent-agnostic.** `ConsentSnapshotStore` lives in `:mobile:app`, and infra MUST NOT depend on app (`docs/11` §4). So `:infra:amplitude` exposes a pure `AnalyticsTracker`; `:mobile:app` provides `ConsentGatedAnalyticsTracker`, which reads `ConsentSnapshotStore.read()?.analytics` **per call** and delegates-or-suppresses. Koin binds `single<AnalyticsTracker> { ConsentGatedAnalyticsTracker(delegate = if (apiKey.isBlank()) NoOpAnalyticsTracker() else AmplitudeAnalyticsTracker(amplitudeConfig), consent = get()) }`. Mirrors Sentry's "consent decision lives in the app Koin module," keeps infra reusable, localizes the privacy policy to the app layer. Per-fire read (not bound-once + re-init) so a Settings toggle takes effect immediately (`docs/06`).

**D4 — Dedicated Amplitude `HttpClient`; never the shared auth-bearer client.** `docs/11` §2.6's "never construct ad-hoc clients per feature" governs feature clients hitting *our* backend (which must share the `Auth { bearer }` + refresh client). Amplitude is a **third party**; sending it the shared client would leak our RS256 JWT in the `Authorization` header. `:infra:amplitude` owns a plain `HttpClient` (ContentNegotiation + JSON + short timeout + `HttpRequestRetry` on idempotent 5xx), **no Auth plugin** — the `:infra:*` "own your vendor transport" pattern. A test asserts no `Authorization` header is sent.

**D5 — US ingestion endpoint by default, config-driven.** Default `https://api2.amplitude.com/2/httpapi` (Indonesia is not EU residency). `amplitudeConfig.endpoint` overrides to `https://api.eu.amplitude.com/2/httpapi` without code change.

**D6 — Identity: `user_id` per event; foundational slice is post-auth only.** Each event carries the authenticated user id (from the existing session/auth layer at the call site). All four foundational events occur post-authentication, so no `device_id` is needed. `app_opened` (pre-auth, needs `device_id` for anonymous→identified stitching) is deferred.

**D7 — `install_date_bucket` is week-level** (e.g. an ISO week bucket), never an exact install timestamp (`docs/04` privacy).

**D8 — `post_viewed` = post-detail open, not per-feed-impression.** The call site is `PostDetailViewModel`/`PostDetailFlow` (a discrete, low-volume action), not a per-list-item impression — avoids the 30 events·s⁻¹ rate ceiling and keeps the semantics unambiguous for the foundational slice. Per-impression view tracking, if ever wanted, rides the deferred taxonomy follow-up.

### Standards conformance (`docs/11`)

- **§2.5 (platform code):** `AnalyticsTracker` and the Amplitude transport are pure `commonMain` (no expect/actual). The durable `ConsentSnapshotStore` uses the established `interface` + per-platform `actual` seam (DataStore/`NSUserDefaults`, the `SecureTokenStore` precedent); iosMain gains an actual → `linkDebugFrameworkIosSimulatorArm64` runs locally.
- **§2.6 (data layer):** follows the `:infra:*` **vendor-seam** pattern (interface + single transport impl in a KMP `:infra:*` module — the `ChatRealtimeSubscriber`/`CrashReporter` precedent), reusing `kotlinx.serialization`; emission is a fail-soft fire-and-forget side effect (no `Outcome` crosses into callers). The durable store reuses the existing `ConsentSnapshotStore` interface (no second consent path — anti-patchwork, mirroring `mobile-settings`'s "reuse the existing consent seam").
- **§4 Pattern Registry:** no new pattern for any listed concern — DI is the existing `single<Interface>` + config-object seam (Sentry/FCM precedent); the consent gate is a standard decorator; the durable store swaps an impl behind an existing interface. **No `docs/11` amendment required** (and none claimed).
- **§5 Definition of Done:** the change introduces **no visual surface change** (event fires are invisible side effects on existing success paths; the durable-store swap and onboarding snapshot write are non-visual), so the §3 verify-loop screenshot gate is **N/A**. The §2 mobile unit-test gates (`:mobile:app:testDevDebugUnitTest` + `testDevReleaseUnitTest`) and `:infra:amplitude` `commonTest` apply; iosMain-touching → `linkDebugFrameworkIosSimulatorArm64`.

## Risks / Trade-offs

- **Fire-and-forget loses events on transient network failure (no outbox).** → Accepted for MVP product analytics; `docs/04` treats Amplitude as best-effort.
- **Failed consent PATCH leaves the prior acknowledged snapshot (no background sync).** → Bounded + spec-aligned: the user sees the existing retryable in-screen error; the durable snapshot keeps the last server-acknowledged value; first-run/absent → suppressed (privacy-safe). Background retry/queue stays out of scope (Non-Goals).
- **Leaking the app JWT to a third party.** → Eliminated by D4 (dedicated client, no Auth plugin); asserted by test.
- **Durable-store swap touches a shared seam (crash + settings read it).** → Intended and spec-anticipated; the change carries the `mobile-crash-reporting` + `mobile-settings` deltas that update their #198 forward-references. Behavior for both is preserved or improved (durable decline), never regressed.
- **Amplitude rate limits (30 events·s⁻¹·device⁻¹).** → Foundational slice is low-volume; `post_viewed` is detail-open (D8), not per-impression. No batching needed at MVP.
- **`api_key` shipped in the mobile binary.** → An Amplitude *ingestion write key* (client-embeddable, like the Sentry DSN), delivered via the same build-config path; blank in dev → `NoOp`.

## Migration Plan

Additive + one impl swap (in-memory → durable `ConsentSnapshotStore`) behind an unchanged interface. No DB migration. Rollback = revert the PR (the consent screen returns to its current no-consumer state; the snapshot store reverts to in-memory). Operator setup is **already done** (`docs/10` § 3.8): the staging Amplitude org/project + the `staging-amplitude-api-key` GCP Secret Manager slot exist (provisioned 2026-05-09); the remaining work is wiring that key into the mobile staging build (the Sentry-DSN build-config path). Until the key reaches the build, `NoOp` is bound and the app behaves as today. At apply, close issue #198 (resolved) and reference it from the updated specs.

## Open Questions

- **Foundational event-property payloads** — the exact `event_properties` per event (e.g. `post_created` carrying a coarse `has_location` boolean? `post_viewed` a feed-source enum?) will be pinned in `tasks.md` against the call-site data in hand, kept privacy-safe (no coordinates, no content). Resolved at implementation; flagged for review input on the property set.
