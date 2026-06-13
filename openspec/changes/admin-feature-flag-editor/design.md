## Context

`:infra:remote-config` already reads the Firebase Remote Config **Server** template (`RemoteConfigClient` → `FirebaseServerConfigSource` / `FirebaseRemoteConfigClient`, null-on-failure), feeding `content-moderation-keyword-lists` and the various `*_enabled` gates. There is no in-product way to *change* a flag — the operator edits the Firebase Console by hand (off-platform, un-audited, Client-vs-Server-template footguns). docs/07 § Core Features specifies a first-class **Feature Flag Admin** panel (audit-logged, rate-limited); docs/08 Phase 3.5 #25 schedules it; the admin mockup board reserves **frame 20** (`GET /admin/feature-flags`). The admin module already has the substrate this needs: session middleware, CSRF gate, role checks, a central `AdminAuditLogger`, and the `DestructiveActionRateLimiter` ledger-count pattern. This change adds the write half behind the existing `:infra:remote-config` seam plus a frame-20 admin surface.

## Goals / Non-Goals

**Goals:**
- A `GET /admin/feature-flags` panel rendering the canonical flag catalog (frame 20) with typed controls and the active environment.
- Single-flag writes to the **Server** template, each gated (CSRF + owner/admin role + 5/hour cap), reason-required, audited once as `feature_flag_toggled`, and protected by optimistic concurrency.
- A write/publish method on `:infra:remote-config` behind the existing interface — vendor types stay inside the module.
- Graceful read-only degradation when write credentials are absent.
- `moderation_match_threshold` write validated to `[1, 10000]`.

**Non-Goals:**
- Editing the **array content** of `moderation_profanity_list` / `moderation_uu_ite_list` (deferred to `admin-moderation-wordlist-editor`; rendered read-only here).
- Any change to how readers/loaders consume Remote Config values (this is the write/admin side only).
- Client-template parameters; new flags beyond the canonical catalog; mobile changes; a DB migration.

## Decisions

### D1 — Remote Config write lives in `:infra:remote-config`, behind the interface
Add a publish method (e.g. `publishServerParameter(key, value, expectedEtag): PublishResult`) to the `:infra:remote-config` public surface, on a **sibling `RemoteConfigPublisher` interface** (not bolted onto the read-only `RemoteConfigClient` — interface segregation keeps the moderation read-consumers, `ModerationListLoader` / `Layer3ConfigLoader`, untouched, and the REST transport is faked in tests via an injectable seam mirroring the read side's `ConfigSource`). **Implemented via the Remote Config REST API, not the Admin SDK** — the Admin Java SDK reads the server template (`getServerTemplate()`) but has **no server-template publish** (`publishTemplate()` targets the *client* template only, which the backend reader never sees, so an SDK publish would be a silent no-op for us). Mechanism (canonical per [Firebase: Modify Remote Config programmatically](https://firebase.google.com/docs/remote-config/automate-rc) + [REST: projects.namespaces](https://firebase.google.com/docs/reference/remote-config/rest/v1/projects.namespaces), verified 2026-06-14): `GET https://firebaseremoteconfig.googleapis.com/v1/projects/{project}/namespaces/firebase-server/remoteConfig` returns the server template JSON + an `ETag`; patch the one parameter's `defaultValue.value`; `PUT` it back with `If-Match: <etag>`. The OAuth bearer token comes from the existing `firebase-admin-sa` `GoogleCredentials` (already on the classpath via `firebase-admin`, scoped to `https://www.googleapis.com/auth/firebase.remoteconfig`); the HTTP call uses the JDK 21 `java.net.http.HttpClient` — **no new dependency**. All Google/REST types (`GoogleCredentials`, the template JSON) stay inside the module; `:backend:ktor` sees plain Kotlin types and a sealed `PublishResult` (`Published(newEtag)` / `StaleVersion` / `WriteUnavailable` / `Failed`). **Alternative rejected:** publishing from `:backend:ktor` directly — violates the "no vendor SDK import outside `:infra:*`" invariant and duplicates the read-seam pattern. The write path reuses the existing `firebase-admin-sa` Secret Manager slot (resolved via `secretKey(env, "firebase-admin-sa")` — the same credential `:infra:remote-config` already initializes `FirebaseApp` with) — **no new secret slot**; the operator step is an IAM role grant on that existing service account (see Migration Plan), not a new credential.

### D2 — No DB migration; `feature_flag_toggled` is a free-text `action_type`
`admin_actions_log.action_type` is `VARCHAR(64) NOT NULL` with **no CHECK constraint** (verified V16). New action types are introduced by adding a typed method to `AdminAuditLogger` (the shipped convention: `user_suspended`, `report_resolved`, `moderation_queue_resolved`, …). This change adds `featureFlagToggled(...)` writing `action_type = 'feature_flag_toggled'` with `before_state`/`after_state`/`reason`. **No Flyway migration** — the change deliberately does not consume V21, keeping it conflict-free against in-flight migration-bearing changes (`admin-chat-message-redaction`, `revenuecat-subscription-webhook`). **Alternative rejected:** a CHECK-constrained enum column — there is no existing CHECK, and adding one would (a) require a migration and (b) break the free-text convention every shipped admin action relies on.

### D3 — Rate limit reuses the ledger-count mechanism as a distinct 5/hour bucket
Mirror `DestructiveActionRateLimiter` (counts rows in `admin_actions_log` over the trailing hour), but filter to `action_type = 'feature_flag_toggled'` with cap `FEATURE_FLAG_TOGGLE_CAP = 5` (docs/07 § Security: "feature flag toggle: 5/hour per admin"). This is a **separate bucket** from the 20/hour destructive cap — feature-flag writes are not in the destructive set and are not counted by it (docs/07 lists the two limits separately). The same ±1 soft-cap tolerance the destructive limiter documents applies and is acceptable for a 5/hour abuse-prevention cap on a solo-to-few-admin panel. The rejected (at/over cap) attempt writes no audit row, so a rejection cannot advance the count. **Alternative rejected:** a Redis token bucket — the admin rate-limit precedent is ledger-count-from-audit-log; introducing Redis for the admin path adds a dependency the panel otherwise doesn't need. Like the destructive limiter, the trailing-hour COUNT runs on the same caller-supplied `Connection` as the success-path audit INSERT, so the gate reads the exact ledger it then appends to (no count/ledger drift).

### D4 — All flag writes require owner/admin; viewing is any admin
Frame 20's banner pins `attestation_mode` (security-touching) to `owner`/`admin`. Rather than a per-flag sensitivity matrix, **all** flag writes require `owner`/`admin` — feature flags are operator-tier configuration, not moderator-tier moderation, and a uniform owner/admin gate is a superset of the mockup's stated constraint (it never grants *more* than the mockup, only restricts at least as much). `GET` stays open to any authenticated admin (viewing flag state is harmless and matches the other read-only admin viewers). **Alternative considered:** a per-flag role tier (moderator allowed for "safe" flags like `search_enabled`). Rejected for this change as needless surface area; a future change can widen specific flags to moderator with its own rationale.

**Implementation (do NOT reuse `requireWriteRole`):** the shipped `AdminRoleGate.requireWriteRole` admits `{owner, admin, moderator}` — reusing it would silently grant moderators flag-write. This change adds an owner/admin-only check (e.g. `requireOwnerOrAdmin` over `WRITE_ROLES_ELEVATED = {owner, admin}`). The owner/admin-excludes-moderator tier is **not novel** — it is the established tier for the most destructive admin actions (`admin-report-queue` `ban_author` and `admin-user-moderation` permanent-unban are both owner/admin-only), so this reuses the existing role model rather than inventing one.

### D5 — Graceful degradation mirrors the FCM null-on-unconfigured precedent
The publish seam returns `WriteUnavailable` (never throws) when Remote Config write credentials are not configured. The route maps that to a read-only render (disabled controls + inline notice). This matches `AndroidFcmTokenProvider`/`IosFcmTokenProvider` returning `null` without a configured `FirebaseApp` — the app builds, boots, and serves; the feature simply advertises itself as unavailable until the operator provisions creds.

### D6 — Optimistic concurrency via the Server-template version
`GET` captures the Server-template `ETag` and embeds it in each write form. The publish sends it as the `If-Match` header on the REST `PUT`; the Remote Config API returns `412 Precondition Failed` when the template has advanced since, which the seam maps to `StaleVersion` → a "reload and retry" prompt. This prevents a second admin's render-then-publish from silently clobbering a concurrent change. No audit row is written on a stale rejection.

### D7 — `attestation_mode` is a validated 3-state enum
`attestation_mode` is not boolean — its domain is `{enforce, warn, off}` (frame 20). The write validates the submitted value against that set server-side; an unknown value is rejected inline (no publish, no audit), exactly like an out-of-range threshold.

### D8 — Wordlist *content* editing is deferred (explicit, guarded)
The frame-20 per-list "edit" affordance for `moderation_profanity_list` / `moderation_uu_ite_list` (300+ entries, array/CSV CRUD with diff + validation) is a materially heavier, separable interaction. It is deferred to a fast-follow `admin-moderation-wordlist-editor` (follow-up issue filed at apply/archive). This change renders the two lists read-only (count + version) and the spec carries a positive deferred requirement + a negative-guard scenario ("no list-content mutation surface exists"), so the boundary is enforced, not just described. The Moderation Runbook's Firebase-Console path remains the interim for list content.

### Standards conformance (docs/11 Pattern Registry — anti-patchwork)
This change builds **only** on already-registered patterns and introduces **no** new pattern:
- **Backend layering (docs/11 §3.1):** route → service/repository → `:infra:*` seam; no vendor SDK in `:backend:ktor`.
- **Admin session / CSRF / role-gate:** the shipped admin middleware + `AdminAuditLogger` + per-route role checks (admin-suspend-unban / report-queue-resolution precedents).
- **Admin "viewer + gated write action + one audit row" pattern:** `admin-report-queue-resolution-actions` precedent (HTMX render + plain-GET fallback, HTML-escape, immutable audit row per applied action).
- **`:infra:*` interface seam:** extend `RemoteConfigClient`'s read-only contract with a write method, keeping vendor types module-internal (the existing read seam is the precedent).
- **Ledger-count rate limit:** `DestructiveActionRateLimiter` mechanism, new scope.

No Pattern-Registry deviation → no docs/11 amendment required. If apply surfaces a forced deviation, it amends docs/11 § Pattern Registry in the same PR.

### Substrate / library note
No `gradle/libs.versions.toml` change. `firebase-admin` (BoM-managed) is already pinned and actively used by `:infra:remote-config` and `:infra:fcm`; this extends that usage to the Remote Config *management* API. Per `openspec/project.md`, the pre-implementation library re-check / propose-time WebSearch gate does **not** fire (extending battle-tested usage, not introducing a substrate).

## Risks / Trade-offs

- **A publish is a live, global side effect (esp. in production).** → Mitigations stack: owner/admin gate + CSRF + `hx-confirm` + mandatory reason + 5/hour cap + per-write audit + optimistic concurrency; the env chip makes staging-vs-production unmistakable on the page.
- **Remote Config Admin write API quotas / per-call latency** (full-template publish per write). → Single-parameter mutation on a freshly-read template; the soft 5/hour cap bounds call volume far below any quota; `WriteUnavailable`/`Failed` degrade rather than 500.
- **Server-vs-Client template mismatch** would make admin edits invisible to the backend reader. → Operate exclusively on the **Server** template (matches `getServerTemplate`); covered by a render+publish round-trip in the verify-loop.
- **Ledger-count rate limit is ±1 (inherited).** → Acceptable for a 5/hour abuse cap; documented, not a correctness gate.
- **Operator-setup dependency (write creds).** → Graceful read-only degradation + a tasks item; staging-first; no code path crashes without it.
- **`moderation_match_threshold` bad value.** → Write-side `[1,10000]` validation *and* the reader-side clamp (defense in depth; the clamp already rejects a cached `0`).
- **A toggled flag is observable in its downstream user flow** (e.g. `premium_username_customization_enabled` OFF → the username-change endpoint returns 503 per docs/08:311; `search_enabled` / `image_upload_enabled` gate their surfaces). → The verify-loop SHOULD exercise at least one user-facing flag end-to-end (not only assert the audit row), so a publish that doesn't actually reach the Server-template reader is caught.

## Migration Plan

- **No DB migration.** Deploy is code-only.
- **Operator step (out-of-band, staging first):** grant the existing `firebase-admin-sa` service account (already used for Remote Config reads + FCM) the Firebase **Remote Config write** IAM role on the bound project — an IAM grant, **no new secret slot**; until then the panel is read-only via D5.
- **Pre-archive:** manual branch deploy to staging (`deploy-staging.yml --ref admin-feature-flag-editor`) + `verify-loop` bring-up of `/admin/feature-flags` with screenshot evidence in the PR body (docs/11 §5 DoD — UI-affecting).
- **Rollback:** revert the code; flag *state* lives in Remote Config, unaffected by a code rollback (a mistaken flag value is itself reverted via the panel or the Console).

## Open Questions

- **Role breadth (D4):** owner/admin for *all* writes is the safe default and is **precedented** — the owner/admin-excludes-moderator tier already gates `ban_author` / permanent-unban. The mockup only explicitly pins `attestation_mode`; widening a "safe" subset (e.g. `search_enabled`) to `moderator` would be a deliberate later change. Resolved: ship owner/admin-for-all.
- **Write-credential provisioning:** whether the existing staging admin runtime SA can simply be granted the Remote Config Admin role, or a dedicated SA is preferred — an operator decision; the feature ships read-only-capable regardless.
