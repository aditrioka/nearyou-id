## Why

The operator's only way to flip a Firebase Remote Config flag today (`search_enabled`, `attestation_mode`, the moderation `match_threshold`, etc.) is the raw Firebase Console — off-platform, un-audited, and easy to get wrong (wrong Client-vs-Server template, no record of who changed what or why). docs/07 § Core Features lists a first-class **Feature Flag Admin** panel for exactly this, audit-logged and rate-limited (docs/08 Phase 3.5 #25); the admin mockup board reserves **frame 20** (`GET /admin/feature-flags`) for it. With the mobile-first demo phase complete, this is the next unblocked, dependency-met admin Core Feature — `:infra:remote-config` already reads the Server template, so this adds the write half behind the same seam.

## What Changes

- **New admin surface `GET /admin/feature-flags`** (Pebble + HTMX, `backend/ktor/.../admin/featureflags` package) rendering the current Server-template Remote Config state for the canonical flag catalog (frame 20): `image_upload_enabled` (bool), `attestation_mode` (enum `enforce`/`warn`/`off`), `search_enabled` (bool), `perspective_api_enabled` (bool), `premium_username_customization_enabled` (bool), `premium_like_cap_override` (bool), and `moderation_match_threshold` (int, `[1,10000]`, default 3). The active environment (staging/production) is surfaced — Remote Config is per-Firebase-project.
- **Gated write action** per flag: HTMX `hx-confirm` + a mandatory free-text **reason** → publishes the new value to the **Server** template → writes exactly one immutable `admin_actions_log` row (`action_type = 'feature_flag_toggled'`, `before_state`/`after_state`, `reason`). CSRF-gated; **role-gated** — security-touching flags (`attestation_mode`) require `owner`/`admin`.
- **Per-admin rate limit of 5 writes/hour** (docs/07 § Security "feature flag toggle: 5/hour") — a bucket distinct from the 20/hour destructive-action cap, reusing the admin rate-limit infrastructure with a new scope.
- **`:infra:remote-config` gains a write/publish method** behind the existing interface seam (Firebase vendor types stay inside the module; plain Kotlin types cross to `:backend:ktor`). Uses the Firebase Admin SDK Remote Config management API (`getServerTemplate` → set parameter → `publishTemplate`) with **etag/version optimistic concurrency** so a concurrent publish is rejected, not silently clobbered. `firebase-admin` is already pinned + actively used → **no new library pin**.
- **Graceful degradation**: when Remote Config **write** credentials are not provisioned on the admin service, the panel renders the current state **read-only** with disabled controls + a notice (mirrors the FCM null-on-unconfigured precedent) rather than erroring.
- **`moderation_match_threshold` write validates input to `[1,10000]`** and rejects out-of-range inline; the reader-side clamp in `content-moderation-keyword-lists` remains the safety net against a bad push.
- **DEFERRED (explicit, not silent)**: editing the **array content** of `moderation_profanity_list` / `moderation_uu_ite_list` (frame-20 per-list "edit" sub-surface — 300+ entries, array/CSV CRUD with diff) is a materially heavier, separable interaction split to a fast-follow change (`admin-moderation-wordlist-editor`, follow-up issue filed at apply/archive). This change renders those two lists **read-only** (entry count + template version + a disabled "edit" affordance). The Moderation Runbook's Firebase-Console path stays the interim for list *content*.
- **No new Flyway migration** (writes existing `admin_actions_log`; reuses admin rate-limit infra) — deliberately does not consume V21, keeping the change parallel-safe against in-flight migration-bearing changes.

## Capabilities

### New Capabilities
- `admin-feature-flags`: the authenticated `/admin/feature-flags` surface — render current Remote Config flag/parameter state, apply role-/CSRF-/rate-limit-gated single-flag writes to the Server template with audit + mandatory reason, validate the threshold parameter, degrade read-only when write creds are absent, and guard the deferred wordlist-content boundary.

### Modified Capabilities
<!-- None. The Remote Config write method added to :infra:remote-config is an implementation extension behind the existing read interface; it changes no requirement of an existing capability spec. The content-moderation-keyword-lists reader/loader contract is untouched (this is the write/admin side). -->

## Impact

- **New code**: `backend/ktor/.../admin/featureflags/**` (route, view-model, Pebble template, vendored CSS already shared); a write/publish method + its `:infra:remote-config` Koin wiring; a 5/hour rate-limit scope on the existing admin rate-limit infra.
- **Modified code**: `:infra:remote-config` public interface (add a publish method, default-null when write-unconfigured); the admin nav/landing to surface the Feature Flags entry (frame 20).
- **Audit**: new `admin_actions_log.action_type` value `feature_flag_toggled` (no schema change — `action_type` is free-text/CHECK-tolerant per the shipped audit pattern; reconcile against the schema in design).
- **External / operator**: the admin Cloud Run service needs a Firebase service account / role with **Remote Config Admin (write)** scope on the bound project (staging first, prod later) — an operator-setup task; until provisioned, the panel is read-only via graceful degradation.
- **No DB migration, no mobile, no client-template params, no new flags beyond the canonical catalog.**
