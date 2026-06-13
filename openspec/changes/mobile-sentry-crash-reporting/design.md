## Context

The mobile app has no crash/error reporting. `:infra:sentry` is the only module flagged **SCAFFOLD NEXT** (`project.md` § Module Structure), and the codebase already reserves seams for it:

- `consent/ConsentFlow.kt` + `ConsentApiClient.kt` already capture and persist the `crash` consent flag (`PATCH /api/v1/user/consent` → `{analytics, crash, ads_personalization}`).
- `diagnostics/DiagnosticSink.kt` is a Koin-bound `fun interface`; its default `ConsoleDiagnosticSink` drops in release builds *"until a Sentry/OTel sink lands."*
- `CoordinateMaskingLogger` + the coordinate-free / no-token discipline establish the PII contract crash payloads must honor.
- `:infra:otel` (archived `observability-otel-foundation`) is the in-repo precedent for an observability-infra module with PII scrubbing (`ForbiddenAttributeStripper`, `UserIdHasher`, `IpHasher`).

This change lands a Sentry-backed `CrashReporter` into those seams. It is deliberately **mobile-only + new-infra-module** scoped — no Flyway migration, no `:backend:ktor` edits — so it lands in parallel with the in-flight admin / billing / backend PRs without rebase contention.

## Standards conformance (docs/11 Pattern Registry)

| Concern | Canonical pattern (docs/11) | This change |
|---|---|---|
| expect/actual & platform code (§2.5) | commonMain **interface** + per-platform actuals bound in Koin platform modules; `expect class`/`object` reserved (still Beta) | **Followed** — `CrashReporter` interface in commonMain; androidMain/iosMain actuals bound via `di/PlatformModule.kt`. Deliberately chosen over docs/04 § Sentry KMP's illustrative `expect object SentryProvider` sketch. |
| Vendor-SDK isolation (invariant #16, §2.6 realtime-seam precedent) | vendor SDK fenced in a KMP `:infra:*` module, `implementation`-scoped so it never reaches `:mobile:app`'s compile classpath (mirror `:infra:supabase-realtime`) | **Followed** — Sentry KMP SDK only inside `:infra:sentry`. |
| Mobile testing (§2.7) | commonTest (kotlin.test) for logic; iosTest (not Kotest on K/N); Koin resolution test | **Followed**. |

**No Pattern-Registry deviation → no docs/11 amendment required.** One non-behavioral reconciliation: docs/04 § Sentry KMP sketches `expect object SentryProvider`, which predates the §2.5 interface mandate. We treat that snippet as illustrative-not-binding and file a follow-up to refresh it to the interface form (it is not a behavior change, so it does not gate this PR).

## Goals / Non-Goals

**Goals:**
- A vendor-neutral `CrashReporter` interface (`:infra:sentry`) with Android + iOS Sentry actuals, consumed by `:mobile:app` via Koin.
- Automatic native crash + unhandled-exception capture on both platforms, tagged with release + environment, with the signed-in user correlated.
- Consent-gated to `users.analytics_consent.crash` (opt-out default ON) honoring the docs/06 enforcement contract.
- PII-scrubbed payloads (no coordinates, tokens, post bodies) via a `beforeSend` hook composing with `CoordinateMaskingLogger`.
- Repository diagnostics surfaced as Sentry breadcrumbs through the **existing** `DiagnosticSink` seam.

**Non-Goals:**
- **Backend Sentry-Java error capture** — explicitly out of scope (the backend has OTel for traces; adding Sentry there would collide with in-flight `:backend:ktor` work). Captured as a negative-guard requirement + follow-up issue.
- Advanced CMP-specific instrumentation (UI-event auto-breadcrumbs beyond the SDK's defaults, session replay, performance/tracing transactions) — not needed for launch; the SDK's CMP support is crash/error-only today (see Decision 3).
- iOS dSYM Fastlane automation — a Gradle/Xcode build phase + operator CI step is the MVP; Fastlane is deferred.
- Changing any consent-capability requirement — the `crash` flag is already captured; this change only consumes it.

## Decisions

### Decision 1 — commonMain `CrashReporter` interface, not `expect object`
A vendor-free `interface CrashReporter` in `:infra:sentry` commonMain (`init(config)`, `captureException(t, context)`, `setUser(id)`, `clearUser()`, `addBreadcrumb(crumb)`, `close()`), with `SentryCrashReporter` actuals in androidMain/iosMain bound by Koin platform modules. **Why over docs/04's `expect object SentryProvider`:** docs/11 §2.5 mandates interface + Koin platform binding (expect class/object is still Beta; reserve expect/actual for top-level functions). The interface also makes the Open Decision #17 fallback (dual native pipeline) a drop-in impl swap. *Alternative considered:* `expect object` per the docs/04 sketch — rejected as a Pattern-Registry violation and harder to fake in tests.

### Decision 2 — `:infra:sentry` KMP module, vendor SDK `implementation`-scoped
The Sentry KMP SDK dependency is declared `implementation` inside `:infra:sentry` only; `:mobile:app` depends on `:infra:sentry`'s API (the interface), never the SDK. Mirrors the `:infra:supabase-realtime` seam (docs/11 §2.6) and satisfies invariant #16 (vendor-SDK-leakage lint). *Alternative:* SDK directly in `:mobile:app` — rejected (invariant violation, no portability seam).

### Decision 3 — Sentry Kotlin Multiplatform SDK (`getsentry/sentry-kotlin-multiplatform`)
The official KMP SDK wraps the native Sentry Android + Cocoa SDKs behind one API. *verified 2026-06-14: at v0.26.0 (active, regular releases) but pre-1.0; docs state it "works in CMP projects … currently supports capturing crashes and errors" though CMP is "not natively supported or fully tested" for advanced features. Our scope is exactly crash/error capture, which IS supported.* The pre-1.0 status + CMP caveat are why Decisions 1–2 (interface seam) matter: they bound the blast radius. **Contingency (Open Decision #17, docs/08):** if the KMP SDK proves unstable in practice, swap the `:infra:sentry` impl to a Crashlytics-Android + Sentry-Cocoa-iOS dual pipeline behind the same `CrashReporter` interface — no `:mobile:app` change. A mandatory dated re-check runs at `/opsx:apply` before the first feat commit (project.md § pre-implementation library re-check).

### Decision 4 — Flavor-aware init at startup; DSN is a client-embeddable ingest key
`init` runs as early as possible (Android `Application.onCreate`, iOS app entry) before app code can crash, with `environment` = the build flavor (`dev` / `staging` / `production`) and `release` = the app version, resolved from the existing `/config` seam + flavor source sets. The Sentry **DSN is a write-only ingest key, not a secret** (designed to ship in clients) — consistent with the "slot names in source, secrets in Secret Manager" posture; the real DSN value comes from the operator, never invented, and is supplied per-flavor (placeholder until provided).

### Decision 5 — User correlation with the opaque JWT `sub` only
On auth, `setUser(id = jwtSubject)` using the opaque provider subject already held by `auth/JwtSubject.kt`; `clearUser()` on logout. **Never** send username, email, or display name (PII). The `sub` is opaque and access-controlled in Sentry; hashing it is optional defense-in-depth, not required. Mirrors `:infra:otel`'s `UserIdHasher` intent (correlatable but not directly identifying).

### Decision 6 — Consent gating (opt-out default ON)
`crash` defaults ON per docs/06 § Defaults. The settings toggle (`ConsentSettingsViewModel`) applies immediately: decline → `CrashReporter.close()` for the session; re-consent → re-init. On cold start, init ON (opt-out), then honor the last-known `crash` value read from the existing **`ConsentSnapshotStore`** (`data/consent` — the seam the consent settings screen already reads), calling `close()` if declined. *Alternative:* gate init itself (don't init until consent confirmed) — rejected: opt-out semantics mean ON is the expected default, and gating init would lose early-startup crashes for the majority who never decline. The brief init-then-close window for decliners is acceptable and carries no event (nothing crashed in those milliseconds).

**Durability caveat (proposal-review finding):** the current `ConsentSnapshotStore` binding (`InMemoryConsentSnapshotStore`) is process-lifetime only; durable on-disk persistence is deferred to **issue #198**, and a null read falls back to the V2 defaults (crash ON). So an in-process decline is honored, but a decline does **not** survive process death until #198 lands durable storage behind the same `ConsentSnapshotStore` interface — this change consumes that seam and does **not** fork a parallel store. Crash is the first opt-out-default-ON consent category to surface this gap (analytics/ads default OFF, so their null-fallback is privacy-safe). Tracked as a known limitation, not a silent hole.

### Decision 7 — PII scrubbing: `sendDefaultPii = false` + a `beforeSend` hook
Initialize with **`sendDefaultPii = false`** so the SDK never infers/attaches the client IP (`{{auto}}`) or host identifiers (`server_name`, device name) — client IP is UU-PDP personal data, and IP→coarse-location would undercut the coordinate-fuzzing posture. A `beforeSend` hook additionally strips/redacts coordinates, tokens, and free-text post/chat bodies from event payloads (message, breadcrumbs, extras), **mirroring** the established coordinate-free discipline — note `CoordinateMaskingLogger` is `private` to `:mobile:app`'s `HttpClientFactory`, so this is pattern-reuse, not a cross-module import — rather than a parallel redactor. Breadcrumbs sourced from `DiagnosticSink` are already coordinate-free by contract; `beforeSend` is the backstop for SDK-captured native context. *The `sendDefaultPii`/IP clause was added in response to the proposal-review security lens (the only real exfil angle found).*

### Decision 8 — Reuse the `DiagnosticSink` seam for breadcrumbs
Bind a `SentryDiagnosticSink` (or compose the existing sink with a Sentry breadcrumb call) into the existing Koin `DiagnosticSink` binding, replacing `ConsoleDiagnosticSink`'s release drop-in. Anti-patchwork: the default sink's own doc reserves this seam ("until a Sentry/OTel sink lands"). Debug builds may keep console output alongside breadcrumbs.

### Decision 9 — Symbol upload: Gradle config in-change, CI/workflow operator-side
Android ProGuard/R8 mapping upload via the Sentry Gradle plugin; iOS dSYM via an Xcode build phase / `sentry-cli upload-dif`. The Gradle-side wiring ships in this change. The `.github/workflows/**` invocation + the `SENTRY_AUTH_TOKEN` secret are **agent-hook-blocked** (workflow edits are blocked for the agent) → handed to the operator as a documented task. Without symbol upload, crashes still report but stack traces are unsymbolicated (degraded, not broken).

## Risks / Trade-offs

- **Sentry KMP SDK pre-1.0 / CMP "not fully tested"** → Mitigation: interface seam (Decisions 1–2) bounds the blast radius; Open Decision #17 dual-pipeline fallback documented; dated re-check at apply; scope limited to the supported crash/error path.
- **iOS K/N actual won't compile on Linux CI** → Mitigation: run `:mobile:app:iosSimulatorArm64Test` / `linkDebugFrameworkIosSimulatorArm64` locally before push (memory: K/N ObjC category members need explicit imports; commonTest Kotest doesn't run on Native — use kotlin.test in iosTest).
- **Unsymbolicated stacks until the operator wires the CI upload step** → Mitigation: ship Gradle config now; document the operator CI/secret task explicitly; reporting works meanwhile.
- **Early-startup init before consent read** (decliners) → Mitigation: opt-out default means ON is expected; `close()` fires on the first consent read; no event is generated in the window.
- **A crash-decline does not survive process death until #198** (the consent snapshot is in-memory today) → Mitigation: in-process decline is honored via `ConsentSnapshotStore`; durability lands free when #198 ships durable storage behind the same interface; documented as a known limitation (Decision 6), not a silent hole. Low exposure: only affects a user who actively declined the opt-out-default-ON category, in the window between process death and their next consent interaction.
- **DSN value not yet provisioned** → Mitigation: per-flavor placeholder + operator-supplied real DSN; a missing/blank DSN no-ops the SDK safely (init guard) rather than crashing.

## Migration Plan

Additive, behind a new module — no schema, no rollback complexity.
1. Scaffold `:infra:sentry` (interface + actuals + tests); pin the SDK; register in `settings.gradle.kts` + `dev/module-descriptions.txt` + `sync-readme.sh --write`.
2. Wire `:mobile:app` (Koin, startup init, `DiagnosticSink` swap, consent gate, `setUser`, `beforeSend`).
3. Gradle symbol-upload config; hand the CI/workflow + secret task to the operator.
4. Verify-loop bring-up (startup init observed on a device surface) + the unit/Koin/iOS tests.
**Rollback:** remove the `:mobile:app` Koin binding (reverts to `ConsoleDiagnosticSink`) or drop the module; no data migration to unwind.

## Open Questions

1. **RESOLVED (proposal review) — Local consent persistence for cold-start gating.** The existing `ConsentSnapshotStore` (`data/consent`) is the seam, already read by the consent settings screen. Its current binding is in-memory (process-lifetime); durable cross-process persistence is deferred to issue #198. The crash gate consumes this seam now (in-process decline honored) and inherits durability when #198 lands — no parallel store. See Decision 6's durability caveat.
2. **DSN provisioning** — the per-flavor Sentry DSN values are operator-supplied; placeholders ship until provided (a missing DSN safely no-ops).
3. **Breadcrumb volume/PII for `DiagnosticSink`** — confirm the existing sink messages remain within the coordinate-free contract when surfaced as breadcrumbs (they are by contract; `beforeSend` is the backstop).
