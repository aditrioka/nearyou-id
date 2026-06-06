## Context

The mobile onboarding chain is shipped end-to-end except one screen. As of Mobile #4 (`mobile-age-gate-screen`, archived 2026-05-31), a new user flows `SignInScreen` → `GoogleSignInClient` ceremony → `404 user_not_found` → `AgeGateScreen` (DOB + 18+ gate) → `POST /api/v1/auth/signup` → `SecureTokenStore` → **`RootRouterScreen` routes straight to `HomeScreen`**. Location permission is an in-screen gate at `HomeScreen` (Mobile #6, `mobile-location-permission-flow`), not a routed onboarding step. [`docs/03-UX-Design.md`](../../../docs/03-UX-Design.md) § User Onboarding Flow sequences the **Analytics & Tracking Consent screen** between the age gate and location permission — i.e., between signup-success and `HomeScreen`. Mobile #4's design Non-Goals explicitly deferred it to "a later change."

The persistence substrate is already shipped and stable:
- `backend/ktor/src/main/resources/db/migration/V2__auth_foundation.sql:22` — `users.analytics_consent JSONB NOT NULL DEFAULT '{"analytics": false, "crash": true, "ads_personalization": false}'`. The default IS the documented privacy-safe posture (analytics opt-in, crash opt-out-able default-on, ads opt-in).
- `backend/ktor/.../user/FcmTokenRoutes.kt` — the existing authed-self `/api/v1/user/*` write pattern (`POST /api/v1/user/fcm-token`) this change mirrors.
- `:mobile:app` has the Ktor KMP client + `Auth { bearer }` interceptor (Mobile #3), Material 3 `Switch`, Navigation 3 serializable `NavKey` routing, and CMP Resources — no new substrate.

This change is **backend (one thin endpoint) + mobile**: no schema, no new library pins. The consent-aware tracking SDKs (Amplitude/Sentry/AdMob UMP) are **out of scope** — this collects and stores consent only; the suppress-wrappers are a separate later piece (roadmap §403 lists them distinctly; Pre-Launch §298 "Analytics consent suppression tested").

## Goals / Non-Goals

**Goals:**
- Ship `ConsentScreen` (three per-category toggles + explainers + continue CTA) and the `ConsentRepository.submitConsent(...)` orchestration so a just-created account records its UU-PDP consent choice.
- Ship `PATCH /api/v1/user/consent` (authed own-row write) as the persistence endpoint, decoupled from signup and reusable by the future Settings toggle.
- Interject the screen into the onboarding chain at the documented position (after age-gate signup, before `HomeScreen`) with one router branch, no rework.
- Map every submit result to an explicit outcome with no generic fallthrough, preserving Mobile #3/#4 PII discipline and copy-via-resources discipline.

**Non-Goals:**
- The consent-aware SDK suppress wrappers (Amplitude/Sentry/AdMob UMP) and `:infra:amplitude` / `:infra:sentry` module scaffolding — deferred; this change does not read `analytics_consent` anywhere.
- The Settings-screen consent toggle (re-edit post-onboarding) — depends on a Settings screen that does not exist; deferred (`mobile-analytics-consent-settings-toggle`).
- A retroactive consent prompt for accounts created before this screen — they keep the V2 default and use the future Settings toggle (roadmap §150).
- Ads, AdMob UMP integration, and any platform-specific (native) consent SDK.
- A `consent_completed_at` schema column / RootRouter consent re-gate — see D4 (safe defaults make it unnecessary for MVP correctness).

## Decisions

### D1 — Separate authed `PATCH /api/v1/user/consent`, NOT folded into `POST /api/v1/auth/signup`

Consent persists via a **new, authenticated** `PATCH /api/v1/user/consent` (new `user/ConsentRoutes.kt`), not by extending the shipped signup body.

**Rationale:** consent is captured *after* the account exists (the screen renders post-`201`, when the user already holds a freshly-minted RS256 JWT), so an authed self-update is the natural shape — and it mirrors the existing `POST /api/v1/user/fcm-token` authed-self pattern exactly. It also keeps the screen decoupled from the shipped `age-gate`/`auth-signup` flow (no change to `AuthWireFormatTest`-pinned wire contracts) and is **reused verbatim by the future Settings toggle**, which has no signup call to piggyback on.

- **(rejected) Add `analytics_consent` to the `/signup` request body.** Couples consent to account creation, mutates a shipped + wire-pinned contract, and is useless to the future Settings re-edit path (no signup there). Also forces the consent UI to render *before* `201`, contradicting the "after age gate" sequence (the user has no account yet to consent on).
- **(rejected) `PUT /api/v1/user/analytics-consent` (resource-style).** Equivalent; `PATCH /api/v1/user/consent` is shorter and consistent with the `user/fcm-token` sibling. The body carries the complete triple regardless of verb (see D5).

### D2 — Consent interjects in the signup path only; the `mobile-age-gate` 201-terminus moves to `ConsentScreen`

`RootRouterScreen` is modified so signup `201` routes to `ConsentScreen` (which routes to `HomeScreen` on submit) instead of straight to `HomeScreen`. The **returning-user sign-in terminus is unchanged** (`/signin 200` → `HomeScreen` directly).

**Rationale:** the consent screen is **first-run onboarding** — it belongs to account creation, which on mobile is the age-gate/signup path (age-gate itself only appears in the `404`-new-user branch). A returning user consented at their original signup; re-prompting every sign-in would be wrong UX and is not what `docs/03-UX-Design.md` describes ("*After the age gate…*"). This mirrors exactly how Mobile #4 modified `mobile-auth-signin`'s `404` terminus — one router branch, no rework — so the modification is a single MODIFIED requirement in the `mobile-age-gate` capability (its `201`→`HomeScreen` requirement becomes `201`→`ConsentScreen`).

### D3 — Initial toggle values are the V2 defaults, hardcoded; no GET round-trip

`ConsentScreen` initial state is **analytics = OFF, crash = ON, ads_personalization = OFF** — hardcoded to match the V2 column default the just-created account already holds. The screen does **not** GET current consent before rendering.

**Rationale:** the account was created seconds earlier with exactly the V2 defaults, so a GET would round-trip to fetch values the client already knows. (The future Settings toggle — which edits an *aged* account whose values may differ — WILL need a GET; that read endpoint is deferred with the Settings follow-up, not built here.) The defaults double as the explainer-doc posture: `docs/03-UX-Design.md` says "*Default: OFF … crash reporting default ON, user can still decline*."

### D4 — Privacy-safe defaults make consent best-effort first-run: non-trapping failure + no RootRouter re-gate

Two edges of the chosen placement (consent lives only in the signup→Home transition) are resolved by the same property — **the V2 defaults are privacy-safe and no SDK reads `analytics_consent` yet**:

1. **PATCH failure is non-trapping.** On a transport/`5xx` failure the screen shows a retryable error (`consent_error_retryable` + `cta_retry`) AND offers proceed-to-Home (`consent_skip`); it never hard-traps the user in onboarding. A user who proceeds keeps the server's safe defaults (analytics OFF, ads OFF, crash ON) and can re-edit via the future Settings toggle.
2. **No consent re-gate in RootRouter.** A user who force-quits at `ConsentScreen` has a valid token, so the next launch routes them to `HomeScreen` (bypassing consent). This change does **not** add a `consent_completed_at` flag + RootRouter check to prevent that.

**Rationale:** because (a) no tracking SDK is wired in this change and (b) when the wrappers DO land they read `analytics_consent` whose default is analytics=false/ads=false (a bypassing user is **not** tracked) and crash=true (which `docs/03-UX-Design.md` documents as the intended opt-out-able default, not a consent violation), a bypass or a failed-persist leaves the user in a documented, privacy-safe state. Adding a schema column + re-gate now would be scope the safe-defaults make unnecessary for MVP correctness. The honest debt is captured as **explicit deferred requirements** (negative-guards in the specs) + two FOLLOW_UPs (`mobile-analytics-consent-persist-hardening`, `mobile-analytics-consent-rootrouter-regate`) so the hardening has a requirement to MODIFY once the suppress-wrappers make persist-reliability load-bearing.

- **(rejected for MVP) `consent_completed_at` column + RootRouter GET + re-gate.** Correct long-term, but adds a Flyway migration + a read endpoint + a RootRouter round-trip to close a hole that is benign while no SDK reads consent. Deferred, not silently dropped.

### D5 — Full-triple body, status-driven outcome mapping (mirror the age-gate discipline)

The request body is the complete triple `{"analytics": Boolean, "crash": Boolean, "ads_personalization": Boolean}` (snake_case `@SerialName` matching the JSONB sub-keys); the endpoint **full-object-writes** `users.analytics_consent` (no partial-merge ambiguity — the screen always submits all three). The mobile `ConsentOutcome` mapping keys on **HTTP status + transport-failure type**, NOT on a parsed `error.code`: `200`→Success (route Home); `401 invalid/expired token`→token-invalid terminal (reuse `signin_error_token_invalid`); `5xx`/`503`/IO→retryable; `400`→retryable with a logged diagnostic (a 400 here is a client bug, never expected). The `response` body is not logged; the JWT/`sub` is never logged.

**Rationale:** this is the same status-driven robustness the `mobile-age-gate` spec pinned (D2/D8 there) after a flat-vs-nested error-body footgun. Keeping consent status-driven avoids re-introducing a parse-dependent mapping.

### D6 — Own-row write; no RLS-policy change; no write-allowlist annotation

The endpoint executes `UPDATE users SET analytics_consent = :payload WHERE id = :jwtSub` (JWT-`sub`-scoped, an own-content write — allowed raw per the shadow-ban carve-out for own-content/Repository paths). It changes **no RLS policy**, so the "RLS policy change → mandatory JWT-`sub`-not-in-`public.users`→deny test" invariant does not trigger; an **own-row-authorization** test is included instead (the write targets only the caller's row). `analytics_consent` is on **neither** the username-write nor the privacy-flag-write allowlist (those guard `username` and `private_profile_opt_in` specifically), so no `// @allow-*-write` annotation is required — confirmed as a pre-flight task against `:lint:detekt-rules`.

### D7 — No new substrate; no Flyway migration

Ktor KMP client + `Auth { bearer }`, Material 3 `Switch`, Navigation 3 serializable `NavKey`, kotlinx.serialization, and CMP Resources are all already pinned and actively used. No entry is added/activated in `gradle/libs.versions.toml`, so the pre-implementation library re-check is skip-eligible (extending an already-active library is not substrate selection). `users.analytics_consent` exists (V2) → **zero migrations**, hence zero migration-number contention with any in-flight change.

## Open Questions

- **OQ1 — `consent_skip` affordance wording/visibility.** D4 makes failure non-trapping via a proceed-anyway path. Should the skip be visible always, or only *after* a failed PATCH attempt (so the happy path has a single "Lanjutkan" CTA and skip appears only on error)? Leaning **only-after-failure** (keeps the consent screen from reading as optional on the happy path). Flagged for proposal review; resolved at implementation if unchallenged.
- **OQ2 — Settings-toggle read endpoint naming.** The future Settings re-edit needs a `GET /api/v1/user/consent`. Not built here (D3 needs no GET). Recording the likely shape now so the `mobile-analytics-consent-settings-toggle` follow-up doesn't re-litigate it: `GET /api/v1/user/consent` → `200 {analytics, crash, ads_personalization}`, same authz as the PATCH.

## Risks

- **Consent-bypass-on-relaunch (D4).** Mitigated by privacy-safe defaults + no-SDK-reads-yet; hardening deferred with an explicit negative-guard requirement + FOLLOW_UP. The risk is benign until the suppress-wrappers land — sequencing the wrapper change after the re-gate follow-up is the mitigation owner.
- **Strings drift.** The three category explainers cite Amplitude/Sentry/AdMob by name (`docs/03-UX-Design.md` verbatim). If a vendor swaps later, the copy needs an update — acceptable; the strings are doc-sourced and reviewed against the doc in tasks.
