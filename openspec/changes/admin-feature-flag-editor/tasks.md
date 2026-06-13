## 1. Pre-flight

- [ ] 1.1 Re-confirm `admin_actions_log.action_type` is `VARCHAR(64)` with no CHECK (no migration needed) and that `feature_flag_toggled` is unused; confirm next free migration stays V21 (this change consumes none).
- [ ] 1.2 Read frame 20 of `dev/mockups/nearyou-admin-mockup.html`; generate its measurement annex (`dev/scripts/mockup-measure.sh nearyou-admin-mockup 20`) for spacing/typography/token redlining (docs/11 §3.6).
- [ ] 1.3 Confirm the `:infra:remote-config` seam: `RemoteConfigClient` is the public interface the new publish method attaches to; `FirebaseServerConfigSource` / `FirebaseRemoteConfigClient` are the impl behind it. Confirm the `DestructiveActionRateLimiter` (caller-supplied-`Connection` COUNT) + `AdminAuditLogger` (typed per-action method) conventions to extend.

## 2. `:infra:remote-config` write seam (design D1)

- [ ] 2.1 Add a sealed `PublishResult` (`Published(newVersion)` / `StaleVersion` / `WriteUnavailable` / `Failed`) of plain Kotlin types to the module's public surface.
- [ ] 2.2 Add `publishServerParameter(key, value, expectedVersion): PublishResult` to the public `RemoteConfigClient` interface, implemented via the Firebase Admin SDK Remote Config management API (`getServerTemplate` → set the one parameter → `publishTemplate`), keeping all vendor types module-internal.
- [ ] 2.3 Implement optimistic concurrency (design D6): reject when the Server-template version has advanced past `expectedVersion` → `StaleVersion`; never overwrite a concurrent change.
- [ ] 2.4 Return `WriteUnavailable` (never throw) when write credentials are not configured (design D5); expose a capability flag the route can read to render read-only.
- [ ] 2.5 Koin-wire the write seam; unit-test the result mapping with a faked Admin SDK template (published / stale-version / unavailable / failure).

## 3. Backend route + service (`backend/ktor/.../admin/featureflags`)

- [ ] 3.1 Define the canonical flag catalog as typed metadata: key, kind (`bool` / `enum{enforce,warn,off}` / `int`), security-sensitivity, and (for the two wordlists) read-only summary. Single source of truth for render + validation.
- [ ] 3.2 `GET /admin/feature-flags` route (session-gated): read current Server-template values + version via the seam, render frame 20; emit no audit row, no publish (spec R1/R2).
- [ ] 3.3 Write route `POST /admin/feature-flags/{key}` (HTMX): parse new value + mandatory reason + the rendered version; orchestrate the gate chain (3.4–3.8) then publish + audit.
- [ ] 3.4 Reason validation: reject blank/whitespace reason — no publish, no audit (spec R3).
- [ ] 3.5 No-op guard: reject when the submitted value equals the current value — no publish, no audit (spec R3).
- [ ] 3.6 Per-parameter value validation: `attestation_mode` ∈ {enforce,warn,off} + booleans parsed strictly (spec R8, D7); `moderation_match_threshold` integer ∈ [1,10000] (spec R7) — reject inline otherwise.
- [ ] 3.7 On success, publish via the seam (handle `StaleVersion` → reload-and-retry prompt; `WriteUnavailable`/`Failed` → safe error, no audit, no 500) (spec R9/R10).
- [ ] 3.8 On a successful publish, write exactly one `admin_actions_log` row through `AdminAuditLogger.featureFlagToggled(...)` (`action_type='feature_flag_toggled'`, `before_state`/`after_state`, reason) (spec R3).

## 4. Gates (CSRF, role, rate-limit)

- [ ] 4.1 CSRF: require `X-CSRF-Token` matching the session on the write route; missing → 403 no publish; mismatch → 403 + `admin_csrf_violation` (spec R4), reusing the shipped CSRF middleware.
- [ ] 4.2 Role-gate: writes require `owner`/`admin` (design D4) — `moderator` → 403, no publish, no audit (spec R5). Introduce an owner/admin-only check (e.g. `requireOwnerOrAdmin` / `WRITE_ROLES_ELEVATED = {owner, admin}`); do NOT reuse `AdminRoleGate.requireWriteRole`, which admits `moderator`. GET available to any authenticated admin; write controls disabled in the render for non-owner/admin.
- [ ] 4.3 Feature-flag rate limiter: count `feature_flag_toggled` rows in the trailing hour on the SAME caller-supplied `Connection` as the success-path audit INSERT (mirror `DestructiveActionRateLimiter`), `FEATURE_FLAG_TOGGLE_CAP = 5`; at/over cap → reject with no publish + no audit (spec R6, design D3). Distinct bucket from the 20/hour destructive cap.

## 5. Template + render (Pebble + HTMX, frame 20)

- [ ] 5.1 Pebble template for frame 20: flag table (typed controls per 3.1), moderation-params card (threshold editable; the two lists read-only count+version, disabled "edit" affordance per D8), env chip, audit/rate-limit banner; all values HTML-escaped (spec R2).
- [ ] 5.2 Per-write `hx-confirm` + reason input; HTMX partial swap on success/validation-error; plain-`GET` fallback render (match shipped admin viewers).
- [ ] 5.3 Read-only degraded render when the seam reports `WriteUnavailable` — disabled controls + inline notice (spec R10).
- [ ] 5.4 Add the Feature Flags entry to the admin nav/landing (frame 20 breadcrumb `Konfigurasi › Feature Flags`).

## 6. Tests

- [ ] 6.1 Route tests — render (R1/R2: catalog types, env, HTML-escape, no-audit-on-GET), unauth redirect.
- [ ] 6.2 Write happy-path test — publish + single `feature_flag_toggled` audit row with before/after/reason (R3).
- [ ] 6.3 Gate tests — blank-reason reject, no-op reject (R3); CSRF missing vs mismatch (mismatch writes `admin_csrf_violation`, the one rejection that audits) (R4); moderator reject + owner/admin allow (R5); rate-limit boundary: 5th write passes + 6th rejected + bucket independence from the destructive cap (R6).
- [ ] 6.4 Validation tests — threshold inclusive bounds: 1/10000/50 accept, 0/10001/non-integer reject (R7); `attestation_mode` each of `enforce`/`warn`/`off` accept + unknown value reject; non-boolean value for a boolean flag reject (value-validation requirement).
- [ ] 6.5 Concurrency + degradation tests — stale-version publish rejected without clobber/audit (R9); write-unconfigured renders read-only and a write fails safely with no audit/no 500 (R10).
- [ ] 6.6 Deferred-guard test — no endpoint/control mutates `moderation_profanity_list`/`moderation_uu_ite_list` content; both render read-only (R11).
- [ ] 6.7 `:infra:remote-config` seam unit tests (from 2.5) green; ensure new DB-tagged route tests autoClose their HikariPool (CI connection budget).

## 7. Definition of Done (docs/11 §5)

- [ ] 7.1 `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` green locally (both lint frameworks).
- [ ] 7.2 Pattern-Registry conformance: confirm no new pattern introduced (design § Standards conformance); if a deviation was forced, amend `docs/11` § Pattern Registry in this PR.
- [ ] 7.3 Manual `verify-loop` bring-up of `/admin/feature-flags` (local Ktor boot + admin login/TOTP) — render, a successful toggle (publish + audit), a rejected toggle (role/CSRF/rate-limit/validation), and the read-only degraded state; capture screenshots into the PR body (UI-affecting gate).
- [ ] 7.4 Pre-archive staging branch deploy (`gh workflow run deploy-staging.yml --ref admin-feature-flag-editor`) + smoke `/admin/feature-flags` (unauthenticated 302 → login at minimum; authenticated render if creds allow).

## 8. Operator setup + follow-ups + docs

- [ ] 8.1 Operator task (out-of-band, NOT a code blocker — staging first): grant the existing `firebase-admin-sa` service account (already used for Remote Config reads + FCM, resolved via `secretKey(env, "firebase-admin-sa")`) the Firebase **Remote Config write** IAM role on the bound project — an IAM grant, no new secret slot; document in `docs/07` § Data Access Pattern / Secret Management Runbook. Until provisioned, the panel is read-only via graceful degradation.
- [ ] 8.2 File the deferred-wordlist follow-up issue (`gh issue create --label follow-up --label admin`): `admin-moderation-wordlist-editor` — array/CSV content CRUD for `moderation_profanity_list`/`moderation_uu_ite_list` (frame-20 "edit" sub-surface), referencing this change's read-only guard.
- [ ] 8.3 Docs reconciliation: tick docs/07 § Feature Flag Admin / docs/08 Phase 3.5 #25 status as shipped (or note the wordlist-content half deferred); confirm the Moderation Runbook interim-edit note still reads correctly alongside the new panel.
