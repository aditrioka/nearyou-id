# NearYouID — Project Context

Location-based social media MVP (Indonesia, 18+ only). Text posts with location, nearby discovery, 1:1 chat, freemium + Premium. Modular monolith on Kotlin Multiplatform. Pre-launch build (~19–20 weeks) with soft launch after Pre-Launch phase.

Full design lives in [`docs/`](../docs/). This file is the minimum context an AI session needs; defer to the docs for anything load-bearing.

---

## Guiding Principles

1. **Vibe coding first** — development is AI-assisted: clean interfaces, isolated modules, standard frameworks over clever abstractions.
2. **Minimize runtime cost** — flat/predictable pricing > pay-per-use. Free tier maximized pre-launch.
3. **Minimize maintenance overhead** — solo operator (Oka). Managed services over self-hosting when self-hosting causes ops headaches.
4. **Portable by design** — every vendor integration sits behind a `:infra:*` abstraction so migrations are frictionless.

Full principle statement + cross-file reference map: [`docs/00-README.md`](../docs/00-README.md).

---

## Tech Stack (summary)

| Layer | Choice |
|---|---|
| Mobile | Kotlin Multiplatform + Compose Multiplatform (Android + iOS) |
| Backend | Ktor on Google Cloud Run (Jakarta) |
| Admin Panel | Ktor server-side + Pebble/Freemarker + HTMX (stateful, NOT an SPA) |
| DB | Supabase Pro (PostgreSQL + PostGIS), Flyway migrations via Cloud Run Jobs |
| Auth | Google Sign-In + Apple Sign-In → Ktor RS256 JWT (REST) + HS256 (Supabase Realtime WSS) |
| Realtime chat | Supabase Realtime Broadcast (Months 1–14) → Ktor WebSocket + Upstash Redis Streams (Month 15+) behind `ChatRealtimeClient` |
| Cache / rate limit | Upstash Redis |
| Media | Cloudflare R2 (non-image, zero egress) + Cloudflare Images (`img.nearyou.id`) + CF CSAM Scanning Tool |
| Push | FCM (Android data-only; iOS alert + NSE) |
| Attestation | Play Integrity + App Attest |
| Subscription | RevenueCat |
| Feature flags | Firebase Remote Config |
| Email | Resend (transactional only) |
| Observability | Sentry KMP (errors), OpenTelemetry → Grafana Cloud (traces), GCP Monitoring (metrics), Amplitude (consent-gated product analytics) |
| Serialization | kotlinx.serialization |
| DI | Koin |

Version pinning lives in the *Version Pinning Decisions Log* (Pre-Phase 1). Full stack table + architecture diagrams: [`docs/04-Architecture.md`](../docs/04-Architecture.md).

---

## Module Structure (dependency isolation)

**Currently scaffolded** (canonical source: [`settings.gradle.kts`](../settings.gradle.kts)):

```
:core:domain              (pure Kotlin, zero vendor deps)
:core:data                (interfaces + DTOs)
:shared:tmp               (KMP scaffold placeholder)
:shared:distance          (renderDistance, JVM target)
:infra:supabase           (DB, auth, realtime broadcast publish)
:infra:redis              (Upstash rate limit + cache)
:infra:fcm                (FCM push dispatch)
:infra:oidc               (internal endpoint OIDC verification)
:infra:otel               (OpenTelemetry tracing — wired 2026-05-07)
:backend:ktor             (routes + Koin wiring)
:mobile:app               (KMP mobile app — current state: see § Mobile-First to Full-Demo Priority below)
:lint:detekt-rules        (custom Detekt rules)
```

**Planned modules** (described in `docs/04-Architecture.md` § Dependency Isolation Pattern as future architecture; **not yet scaffolded** — do NOT `import` from these):

| Module | Status | Trigger to scaffold |
|---|---|---|
| `:shared:resources` | shipped | Mobile #2/#2.5: `shared-resources-moko-bootstrap` ([PR #116](https://github.com/aditrioka/nearyou-id/pull/116)) shipped Moko Resources, swapped to **Compose Multiplatform Resources** via `shared-resources-swap-to-cmp-resources` ([PR #119](https://github.com/aditrioka/nearyou-id/pull/119)). Ships `Res` codegen + `NearYouColorScheme` (light + mechanically-derived dark, 30+ M3 roles) + `NearYouColors` extension-property surface (coral location pin, amber Premium badge, semantic success/warning/link) via `LocalNearYouColors` CompositionLocal + `NearYouTypography` (Plus Jakarta Sans variable) + brand logo variants + 10 foundational Bahasa Indonesia strings |
| `:infra:r2` + `:infra:cloudflare-images` | DESIGN | Image upload feature (Phase 2/3) |
| `:infra:revenuecat` | DESIGN | Premium subscription billing |
| `:infra:resend` | DESIGN | Transactional email module-isation (project smoke-tested 2026-04-27, not yet modular) |
| `:infra:sentry` | SCAFFOLD NEXT | Follow-up `infra-sentry-kmp-module-isation` (split from Mobile #1 if scaffold scope grows; see § Mobile + Admin Scaffolding Priority menu Mobile #1) |
| `:infra:amplitude` | DESIGN | Consent-gated analytics |
| `:infra:attestation` | DESIGN | Play Integrity + App Attest (post-MVP) |
| `:infra:remote-config` | SHIPPED | Firebase Remote Config wordlist + threshold delivery for `content-moderation-keyword-lists` (PR #70). DB-backed flags (`premium_*_cap_override`) remain the per-user override surface; Remote Config is the platform-wide tunable surface. |
| `:infra:postgres-neon` | ABANDONED | Plan B scaffold not pursued; Supabase PITR is the backup posture |
| `:infra:ktor-ws` | ABANDONED | Realtime ships via Supabase Broadcast (`SupabaseBroadcastChatClient`); Ktor WS path retired |

Backend packages inside `:backend:ktor` (regenerate from `find backend/ktor/src/main/kotlin/id/nearyou/app -type d -maxdepth 1` if drift suspected): `auth`, `block`, `chat`, `common`, `config`, `dev`, `engagement`, `follow`, `guard`, `health`, `internal`, `lint`, `moderation`, `notifications`, `post`, `search`, `timeline`, `user`, `admin` (current admin-surface state: see § Mobile-First to Full-Demo Priority). Details + `ChatRealtimeClient` interface in [`docs/04-Architecture.md`](../docs/04-Architecture.md).

Rule: **no vendor SDK import outside `:infra:*`**. Domain/data code depends only on interfaces.

---

## Mobile-First to Full-Demo Priority — phase complete, now Balanced (as of 2026-06-14)

> Supersedes the 2026-05-12 **"Mobile + Admin Scaffolding Priority"** (this section's prior name; retained so existing `§ Mobile + Admin Scaffolding Priority` cross-references still resolve — the historical scaffold menu lives under "Scaffold menu (historical)" below).

**Two priority phases are now complete.** (1) The 2026-05-12 **scaffolding** phase — all five mobile (`mobile-app-scaffold-replace-wizard` → `mobile-nearby-timeline-screen`) and all five admin (`admin-schema-bootstrap` → `admin-suspend-unban-user-action`) scaffold changes squash-merged. (2) The 2026-06-07 **mobile-first** phase — the operator chose (over the generic "balanced mode" the scaffold trigger pointed to) to **finish the mobile app to a full-demo state ahead of admin**, since the backend was already MVP-ready (Phase 1 + Phase 2: auth, posts+geo, the Nearby/Following/Global timeline trio, block/like/reply/report, moderation, notifications API, FCM, chat, search, rate-limit) and mobile was the demo bottleneck. **That mobile-first phase is now done** — its flip trigger fired **2026-06-13** (all five critical-path picks + post-detail + bottom-nav/notifications squash-merged; see the live menu and "Trigger to flip" below).

**New direction (operator decision, 2026-06-14): Balanced — no single priority.** With the mobile demo loop complete, `/next-change` picks the **highest-value item by judgment each cycle** across the three live lanes — **admin** (resume the remaining surfaces, [`docs/07-Operations.md`](../docs/07-Operations.md) § Admin Panel + the admin mockup board, `docs/11` § 3.6), **Phase 4 / premium** (premium / billing / image upload — the revenue loop, [`docs/02-Product.md`](../docs/02-Product.md) + [`docs/08-Roadmap-Risk.md`](../docs/08-Roadmap-Risk.md)), and **mobile follow-ups** (polish + deferred items below) — **surveying in-flight PRs first to dedup** (parallel sessions run concurrently). No lane is privileged; backend hardening stays blocker-only (security invariant gap, pre-launch test requirement, or a dependency for active work). Spec sources per mobile change: [`docs/02-Product.md`](../docs/02-Product.md), [`docs/03-UX-Design.md`](../docs/03-UX-Design.md), [`docs/04-Architecture.md`](../docs/04-Architecture.md).

**Mobile (`:mobile:app`) — full demo loop shipped.** Real Compose Multiplatform app (Navigation 3 + Koin DI + Material 3 light/dark theme + `:shared:resources` CMP Resources). The authenticated core loop is now demoable end-to-end: Google sign-in → age gate → browse Nearby/Following/Global → create a post → open post detail → like/reply → view a profile → follow → see notifications → 1:1 chat → premium-gated search → settings. Built via analytics-consent ([#157](https://github.com/aditrioka/nearyou-id/pull/157)), post-detail ([#159](https://github.com/aditrioka/nearyou-id/pull/159)), bottom-nav sections + notifications ([#162](https://github.com/aditrioka/nearyou-id/pull/162) — Home/Notifikasi/Profil bottom sections, feeds as top tabs), then the five critical-path picks ([#245](https://github.com/aditrioka/nearyou-id/pull/245)–[#249](https://github.com/aditrioka/nearyou-id/pull/249), see the live menu below). **Remaining mobile work is follow-up polish, not demo-blocking** — fold in opportunistically under the balanced cadence (the polish list below + in-flight follower/following lists [#298](https://github.com/aditrioka/nearyou-id/pull/298), Sentry KMP [#299](https://github.com/aditrioka/nearyou-id/pull/299)).

**Admin (`:backend:ktor` `admin` package) — active again under the balanced cadence** (no longer deferred behind mobile). The admin surface is well past scaffold: schema V16, Argon2id + TOTP login, audit-log viewer, suspend/unban, report-queue triage + in-row resolution actions (`admin-report-queue-resolution-actions` shipped + archived 2026-06-08), rejected-identifiers viewer, block registry, privacy-flip monitor; further surfaces in flight (operational dashboard [#296](https://github.com/aditrioka/nearyou-id/pull/296), reserved-usernames editor [#294](https://github.com/aditrioka/nearyou-id/pull/294), chat-message redaction [#290](https://github.com/aditrioka/nearyou-id/pull/290), feature-flag editor [#297](https://github.com/aditrioka/nearyou-id/pull/297)); UI intentionally unstyled so far. Spec source is [`docs/07-Operations.md`](../docs/07-Operations.md) § Admin Panel; the **canonical visual target for every admin surface (existing + planned) is the admin mockup board** (`dev/mockups/nearyou-admin-mockup.html`, binding rule `docs/11` § 3.6).

**Phase 4 / premium (`:backend:ktor` + `:mobile:app`) — now in scope under the balanced cadence.** The revenue loop: premium subscription status + entitlements, billing, image upload. In flight: RevenueCat subscription webhook + status backend ([#291](https://github.com/aditrioka/nearyou-id/pull/291)), premium username customization ([#301](https://github.com/aditrioka/nearyou-id/pull/301)), premium post editing ([#304](https://github.com/aditrioka/nearyou-id/pull/304)). Spec sources: [`docs/02-Product.md`](../docs/02-Product.md) (freemium/Premium feature set) + [`docs/08-Roadmap-Risk.md`](../docs/08-Roadmap-Risk.md) (Phase 4 ordering + open decisions).

### Mobile critical path to full-demo (COMPLETE — all 5 shipped 2026-06-13)

The mobile critical-path picks for an end-to-end demoable app, in dependency order — **all five squash-merged 2026-06-13, completing the mobile-first phase**. Retained for provenance; the live `/next-change` cadence is now Balanced (top of this section). Each was a spec-driven pick (kebab-case, no `-v<N>`, one-PR-per-change); the three prerequisites — analytics-consent ([#157](https://github.com/aditrioka/nearyou-id/pull/157)), post-detail ([#159](https://github.com/aditrioka/nearyou-id/pull/159)), bottom-nav + notifications ([#162](https://github.com/aditrioka/nearyou-id/pull/162)) — shipped just ahead of them.

| # | Theme / change | What it ships | Unblocks |
|---|---|---|---|
| 1 | **`mobile-profile-screen`** (keystone) ✅ shipped ([#245](https://github.com/aditrioka/nearyou-id/pull/245), 2026-06-13) | Profile content for the #162 Profil section: view own + other users, follow/unfollow, block + report (user-level), bio/handle, suspension-countdown state. Wires the shipped follow + block + report endpoints. | Following feed (needs a follow action) + user-level block/report |
| 2 | **`mobile-following-timeline-screen`** ✅ shipped ([#246](https://github.com/aditrioka/nearyou-id/pull/246), 2026-06-13) | Replace `FollowingPlaceholderScreen` with the live `GET /api/v1/timeline/following` feed (mirror the shipped Global/Nearby seam). MODIFIES the `mobile-home-tab-host` deferred-placeholder requirement. | The 3rd timeline tab actually working |
| 3 | **`mobile-chat-screen`** ✅ shipped ([#247](https://github.com/aditrioka/nearyou-id/pull/247), 2026-06-13) | 1:1 chat UI: conversation list + thread, send (block-check + 2000-char guard), Supabase Realtime Broadcast subscribe + REST resync, notification-permission prompt on first send. | The messaging half of the demo |
| 4 | **`mobile-search-screen`** ✅ shipped ([#248](https://github.com/aditrioka/nearyou-id/pull/248), 2026-06-13) | Premium-gated search UI (FTS endpoint), autocomplete, empty/loading/rate-limit states, Free-tier upsell. | Search demo |
| 5 | **`mobile-settings-screen`** ✅ shipped ([#249](https://github.com/aditrioka/nearyou-id/pull/249), 2026-06-13), gear deferred | Settings: block-list management (unblock), analytics-consent toggle (#157), logout, legal link. Screen + routes shipped; the **profile-screen entry gear** is deferred to [#288](https://github.com/aditrioka/nearyou-id/issues/288) (so Settings is built + tested but not yet reachable in-app). Account-deletion entry + suspension-countdown UI deferred (follow-ups, not in this screen — design D8). | Account-management demo + UU-PDP surface |

Polish follow-ups (not demo-blocking, fold in opportunistically under the balanced cadence): `mobile-nearby-timeline-infinite-scroll` (load-more for Nearby + Global), `mobile-timeline-relative-timestamp`, `mobile-nearby-radius-slider`, profile follower/following lists (in flight [#298](https://github.com/aditrioka/nearyou-id/pull/298)). Cross-cutting wiring: FCM token registration shipped ([#250](https://github.com/aditrioka/nearyou-id/pull/250)), analytics-consent shipped ([#157](https://github.com/aditrioka/nearyou-id/pull/157)); still pending — Remote Config client fetch, Sentry KMP (in flight [#299](https://github.com/aditrioka/nearyou-id/pull/299)), Amplitude tracker (consent-gated), attestation expect/actual — sequence each as it gates a scenario.

### Scaffold menu (historical — all 10 shipped, 2026-05 → 2026-06)

The original first-5-per-surface scaffold menu that drove this section from 2026-05-12; retained for provenance (all ten squash-merged).

**Mobile scaffolding:**

| # | Change name | What it shipped | Unblocked |
|---|---|---|---|
| 1 | `mobile-app-scaffold-replace-wizard` | Real CMP app structure — navigation (Voyager/Decompose/vanilla, decided in `design.md`), Koin DI, app theme; replaced the "Click me!" wizard. No networking/auth/features; both Android + iOS targets must build. Rule: Sentry KMP wiring MAY split out as `infra-sentry-kmp-module-isation` if scaffold scope grows beyond ~300 LOC (default-include if it fits; default-split if it adds a full `:infra:sentry` module + dSYM upload + iOS framework reconfig). | Everything else mobile |
| 2 / 2.5 | `shared-resources-moko-bootstrap` → `shared-resources-swap-to-cmp-resources` | `:shared:resources` module (Moko in [PR #116](https://github.com/aditrioka/nearyou-id/pull/116), swapped to CMP Resources one day post-archive in [PR #119](https://github.com/aditrioka/nearyou-id/pull/119) — first test case of the pre-implementation library re-check rule, [PR #118](https://github.com/aditrioka/nearyou-id/pull/118)); 8-10 foundational strings (`app_name`, `error_generic`, `cta_continue`, …); app consumes from the module; verifies the no-hardcoded-UI-strings grep. Visual input was required from the user before proposing (app-icon files + brand logo PNG/SVG + color hexes + typography, folded in per `mobile-app-scaffold-replace-wizard` design Decision 3) — never invent brand values. | Any screen with text |
| 3 | `mobile-auth-google-signin-flow` | Pure DIY Google Sign-In wrapper (expect/actual) → backend `POST /api/v1/auth/signin/google`; stores RS256 JWT (iOS Keychain / Android EncryptedSharedPreferences); Ktor client auth-header interceptor. **Sign-in path only** (existing accounts) — signup + age gate is Mobile #4; until #4, "no account exists" routes back to the sign-in screen. First end-to-end working flow. | All authenticated screens |
| 4 | `mobile-age-gate-screen` | Signup-new-user flow: DOB picker + 18+ enforcement via backend signup endpoint; under-18 rejection relies on the `rejected_identifiers` blocklist (Phase 1 §3). Composes with #3: verified Google ID without an account routes to age-gate-then-signup. | Real signup flow (account creation) |
| 5 | `mobile-nearby-timeline-screen` | First product screen — `GET /api/v1/timeline/nearby`, renders `DistanceRenderer` output from `:shared:distance`, pull-to-refresh, empty/loading/error states. Visual input was required from the user before proposing (UI inspiration: Figma refs, screenshots, layout preferences, state visuals) — this screen set the visual pattern every subsequent screen inherits. | First demoable product flow + the §15 fuzzing audit |

**Admin scaffolding:**

| # | Change name | What it shipped | Unblocked |
|---|---|---|---|
| 1 | `admin-schema-bootstrap` | Flyway migration for `admin_users`, `admin_sessions`, `admin_actions_log`, `admin_webauthn_credentials`, `admin_webauthn_challenges`; backfilled the FK columns previously nullable in `reports.reviewed_by`, `moderation_queue.resolved_by`. Schema-only (no UI/business logic). | `suspension-unban-worker-audit-log-after-phase-3.5` follow-up + every admin REST/UI change |
| 2 | `admin-panel-ktor-htmx-bootstrap` | Admin route subtree (`Application.admin()` extension), Pebble or Freemarker template engine, HTMX wired, basic layout serving a "hello admin" page. No auth gate yet. | Every admin UI feature |
| 3 | `admin-login-argon2-totp` | Login endpoint: Argon2id password verification + TOTP (mandatory per solo-admin period), sets `__Host-admin_session` cookie with `csrf_token_hash`, login UI page. First end-to-end admin flow. | Every admin action requiring auth |
| 4 | `admin-actions-log-viewer` | Read-only audit-log viewer (paginated table; filter by action type / admin / date range; immutable display — UPDATE/DELETE revoked at `admin_app` role). First admin business feature. | Every other admin write action (audit-trail dependency) |
| 5 | `admin-suspend-unban-user-action` | Admin suspend/unban action writing to `admin_actions_log`; wires the existing suspension worker to admin-triggered manual unban. First admin write action. | Moderation workflow MVP |

### Trigger to flip out of mobile-first priority — ✅ FIRED 2026-06-13

The flip condition was: the mobile app's **authenticated core loop demoable end-to-end** — sign in → browse Nearby/Following/Global → create a post → open a post detail → like/reply → view a profile → follow → see notifications → 1:1 chat → search. Concretely, live-menu picks #1–#5 (profile [#245](https://github.com/aditrioka/nearyou-id/pull/245), following feed [#246](https://github.com/aditrioka/nearyou-id/pull/246), chat [#247](https://github.com/aditrioka/nearyou-id/pull/247), search [#248](https://github.com/aditrioka/nearyou-id/pull/248), settings [#249](https://github.com/aditrioka/nearyou-id/pull/249)) squash-merged alongside post-detail ([#159](https://github.com/aditrioka/nearyou-id/pull/159)) + bottom-nav + notifications ([#162](https://github.com/aditrioka/nearyou-id/pull/162)). **All merged by 2026-06-13 → the trigger fired.** At that boundary the operator re-evaluated (2026-06-14) and — rather than resuming admin exclusively or moving to Phase 4 exclusively — chose **Balanced — no single priority**: `/next-change` now picks the highest-value item by judgment across admin, Phase 4 / premium, and mobile follow-ups (see the top of this section). Backend hardening stays blocker-only throughout. (Re-stamped by a docs-only PR, 2026-06-14.)

---

## Environments

Three tiers, identical code, different secrets/config (Pre-Phase 1 bootstraps staging; production not live until Pre-Launch):

- `dev` — local, Supabase CLI + Docker Compose (Ktor + Redis)
- `staging` — Cloud Run + Supabase Free + Upstash Free + RevenueCat sandbox. Subdomains `api-staging|admin-staging|img-staging.nearyou.id`. Synthetic data only, nuke-safe.
- `production` — full-spec. Subdomains `api|admin|img.nearyou.id`.

Secrets namespaced by env prefix in GCP Secret Manager (`staging-*` vs unprefixed prod). Ktor reads `KTOR_ENV` and resolves secrets via the `secretKey(env, name)` helper — direct secret-name reads are a lint violation. Mobile uses Android flavors / iOS xcconfig schemes (`staging` vs `production`). Deploy flow: merge → `main` auto-deploys staging; git tag `v*` → prod after manual approval. Details: [`docs/04-Architecture.md`](../docs/04-Architecture.md) § Deployment.

---

## Coding Conventions & CI Lint Rules

Enforced by CI (full list: [`docs/08-Roadmap-Risk.md`](../docs/08-Roadmap-Risk.md) § Development Tools). Notable:

- **Shadow-ban safety**: business code must query `visible_*` views, never raw `FROM posts|users|post_replies|chat_messages`. Raw reads allowed only in Repository own-content paths and the admin module.
- **Block enforcement**: business queries touching posts/users/chat_messages/post_replies must include the block-exclusion join.
- **Spatial**: non-admin paths must use `display_location` (HMAC-fuzzed), never `actual_location`, for `ST_DWithin`/`ST_Distance`. Exceptions: admin module + the one sanctioned reverse-geocode path (`posts_set_city_tg` trigger, DB-side only).
- **Client IP**: use the `clientIp` request-context value (populated by Cloudflare-aware middleware reading `CF-Connecting-IP`). Direct `X-Forwarded-For` reads are forbidden.
- **Rate-limit TTL**: must call `computeTTLToNextReset(user_id)` (per-user WIB midnight stagger). No hardcoded midnight math.
- **Redis keys**: must include hash tag `{scope:<value>}` for cluster-safe multi-key ops.
- **Username writes**: `UPDATE users SET username = ...` allowed only in signup flow + the single Premium customization transaction + admin module. Legitimate writers annotate `// @allow-username-write: signup|customization`.
- **Privacy flag writes**: `UPDATE users SET private_profile_opt_in = FALSE` only in the privacy flip worker + Settings flow. Annotate `// @allow-privacy-write: worker|user_settings`.
- **Content length guards**: input endpoints must length-check (post/reply 280 chars, etc.).
- **Admin sessions**: every `INSERT INTO admin_sessions` must populate `csrf_token_hash`.
- **Admin-user FKs** on operational tables must use `ON DELETE SET NULL`.
- **Mobile strings**: no hardcoded UI strings; must go through Compose Multiplatform Resources (`stringResource(Res.string.X)` accessor).
- **Partial indexes**: no `NOW()` in `WHERE` (non-immutable; PG rejects).
- **RLS changes**: mandatory test case "JWT `sub` not in `public.users` → deny" on every policy change.
- **Secrets**: Ktor MUST read via the `secretKey(env, name)` helper (per Environments section above). Direct secret-name reads are a lint violation.
- **No vendor SDK import outside `:infra:*`** — domain/data code depends only on interfaces (per Module Structure section above).

Other conventions:
- **Architectural contracts**: mobile state/navigation/data-layer patterns, backend layering + JDBC discipline, Compose performance rules, and the Definition of Done live in [`docs/11-Engineering-Standards.md`](../docs/11-Engineering-Standards.md) — MUST-read at proposal + apply phases. A change deviating from a Pattern-Registry pattern must amend that doc in the same PR (no silent second patterns).
- API versioning: `/api/v1/...` from day one.
- Code style: ktlint.
- Tests: Kotest/JUnit5, Ktor test framework, Docker-based integration tests.
- DB role separation: `main_app`, `admin_app`, `flyway_migrator` — each its own Secret Manager slot. DDL only via `flyway_migrator`.

**Test-data conventions:**
- **Inputs to integration tests against seeded reference tables** (`admin_regions`, `reserved_usernames`, etc.) MUST produce a deterministic outcome regardless of seed cardinality. Either pick inputs that fall outside ALL seeded rows by construction (deep-ocean coords, unicode strings no real row uses) OR set up the test's reference data explicitly per-test. Precedent: 3 timeline-test "legacy NULL row" assertions broke at the V12 seed merge (PR #31) because they used Jakarta-area coords (NULL `city_name` held only while `admin_regions` was empty); fix used `(-10.5, 105.0)` deep-Indian-Ocean coords no kabupaten polygon covers.

---

## Change Delivery Workflow

Direct push to `main` is hook-blocked — every change ships via feature branch + PR + squash-merge.

**gh CLI account (per-repo).** `gh` operations on nearyou-id MUST run under the `aditrioka` account, NOT `adi-at-buku` — a wrong active account makes `gh pr create` fail with a 403 "must be a collaborator" (`gh auth switch` to correct it). Note `aditrioka` may still lack `actions:write` (so `gh run rerun` can 403). (Also project memory `reference_gh_active_account_per_repo`.)

**Change naming.** Kebab-case, descriptive, no `-v<N>` suffix — a change name describes what it adds, not which Flyway version it bumps (a change can ship zero or multiple migrations). Pre-V7 archives follow this (`signup-flow`, `post-creation-geo`, `nearby-timeline-with-blocks`, `following-timeline-with-follow-cascade`); the V7–V9 trio (`post-likes-v7`, `post-replies-v8`, `reports-v9`) used an interim suffix that we standardized away from.

**Branch naming.**
- OpenSpec features: the change name itself (e.g., `post-reposts`, `notifications-api`).
- Archive: `openspec/archive-<change-name>`.
- Infra / tooling / CI / docs-only: `<area>/<slug>` (e.g., `ci/postgres-service`, `docs/workflow-conventions`).

**Sequence per OpenSpec change.** Every spec-driven product change ships as **ONE PR carrying the full lifecycle** (proposal → implementation → archive). Branch name = change name. Result: **one commit on `main` per change** — not three.

1. **`/next-change` opens the PR** with proposal commits (`docs(openspec): propose <change-name>`). The skill scaffolds via `openspec-propose`, runs `openspec validate --strict`, runs the canonical-docs reconciliation pass, pushes, opens the PR, drives the multi-lens review loop. Review fixes land as new commits on the same branch.
2. **`/opsx:apply` pushes feat commits to the same branch.** At first feat commit, retitle via `gh pr edit <pr> --title 'feat(<area>): <what>'` and update the body. Do NOT open a new PR. CI runs per push; staging deploy runs only after the eventual squash-merge.
3. **`/opsx:archive` pushes the archive commit to the same branch** — `openspec archive <change>` run locally; the `openspec/specs/**` updates + move under `archive/` are committed and pushed. No separate archive PR.
4. **Single squash-merge to `main` at end-of-lifecycle**, after the archive commit is pushed and CI is green.

**Precedent transition.** V5–V11 used the OLD 3-PR shape (e.g., `global-timeline-with-region-polygons`: PR [#15](https://github.com/aditrioka/nearyou-id/pull/15) propose + [#29](https://github.com/aditrioka/nearyou-id/pull/29)/[#31](https://github.com/aditrioka/nearyou-id/pull/31) feat + [#35](https://github.com/aditrioka/nearyou-id/pull/35) archive — three squash-merges). That convention is **deprecated**. PR [#37](https://github.com/aditrioka/nearyou-id/pull/37) (`like-rate-limit`) was the first one-PR change; PR [#38](https://github.com/aditrioka/nearyou-id/pull/38) codified the convention. `git log` shows two regimes, pre- and post-#37.

**Iteration rule applies to ALL phases.** Push new commits to the same branch through proposal-review, implementation, AND archive. PR number stays stable from `/next-change` through final squash-merge.

**PR title and body MUST stay current at every phase boundary** (hard rule). The PR is what reviewers see at squash-merge time; it must describe the change as it stands NOW. Refresh points:

- **Proposal review completion** (after the 2-iteration review-loop cap settles): body summarizes blocking-vs-non-blocking findings applied + "ready to start implementation".
- **First feat commit**: retitle (`gh pr edit <pr> --title 'feat(<area>): <name>'` — or whichever conventional-commit prefix matches the dominant work) + body becomes an in-progress shape listing done sections.
- **Every subsequent section / sub-agent dispatch landing a non-trivial commit**: update the body's progress table — 30 seconds that saves the next reviewer a `git log` archaeology session.
- **Archive completion**: body becomes merge-ready — final test counts, capability deltas, post-merge tasks (e.g., staging smoke), and a callout of the one-PR / single-squash convention. Retitle if the dominant prefix changed (usually `feat(<area>):` still fits).

Use `gh pr edit <pr> --body "$(cat <<'EOF' ... EOF)"` (heredoc preserves formatting) and `gh pr edit <pr> --title '<new-title>'`; both are idempotent. Precedent: PR [#37](https://github.com/aditrioka/nearyou-id/pull/37) shipped with multiple refreshes; one skipped archive-phase refresh had to be user-requested — hence this rule.

**Review channels for the change PR.** Two channels run in parallel (per `CLAUDE.md` § Reviewing a PR §7 + `/next-change` Phase D):

- **qodo GitHub App** (auto, GitHub-side): posts on every PR push, ~<1 min. Quota-capped on the free tier; may be silent on docs-only PRs.
- **In-session sub-agent(s)** (skill-driven): for non-trivial proposals MAY dispatch **multiple parallel `general-purpose` sub-agents with different review lenses** — typically four: general / security-and-invariant / OpenSpec format-and-correctness / test-coverage. Each lens catches findings the others miss (PR [#37](https://github.com/aditrioka/nearyou-id/pull/37) round 1: security caught 5 hardening items the general lens didn't; test-coverage caught 3 missing-scenario bugs the security lens didn't). Optionally one round-2 **regression-scan sub-agent** ("did round-1 fixes introduce orphan refs / break scenarios?") — same PR's round 2 found 6 stale references the round-1 sweep missed.
- **Triviality: SHOULD, not MUST.** Trivial proposals (one-requirement spec tweaks) need only one general-lens dispatch.
- **qodo absent at 6 min total** → proceed with sub-agent findings alone (encoded in the skill).
- **"Apply all findings" (blocking + non-blocking) is a valid path** — the skill's `AskUserQuestion` step 13 exposes it directly.

**Pre-implementation library re-check (MUST for substrate-introducing changes).** Before `/opsx:apply` lands the first feat commit of a change that introduces a **new library pin** in [`gradle/libs.versions.toml`](../gradle/libs.versions.toml) OR **activates a previously-unused-but-pinned library**, run a fresh `WebSearch` with the **current calendar date or year** in the query (e.g., `"<library> vs <alternative> 2026 best practice"`, `"<library> production ready May 2026"`, `"<library> deprecated 2026"`). Rationale: the proposal may be days-to-weeks old; the KMP/CMP/JVM ecosystem ships fast, and date-anchored search pulls post-training-cutoff state (new stable releases, deprecations, JetBrains directional shifts, maintainer activity).

Outcomes:
- **Confirms the design-time call** → one-liner in the first apply commit body or a PR comment (`re-check 2026-MM-DD confirms: still best option, no ecosystem shift since proposal`) and proceed.
- **Materially-better alternative surfaces** → STOP; surface via `AskUserQuestion`; user chooses (a) ship the design-time call + file the alternative as a `follow-up` issue, or (b) author a new OpenSpec change (or amend the in-flight design.md Decision in-place) to swap. Never silently switch substrate mid-implementation — that bypasses the proposal-review gate.

Skip when the change touches only existing-pinned + actively-used libraries (extending battle-tested usage isn't substrate selection). Trigger = "new substrate enters the codebase," not "any library touch." Precedent: `shared-resources-moko-bootstrap` (PR [#116](https://github.com/aditrioka/nearyou-id/pull/116), archived 2026-05-27) shipped Moko after a proposal-phase comparison; a 2026-05-28 re-check (one day post-archive) surfaced CMP 1.10 production-stable + 1.11 maturity + JetBrains' directional commitment — material info that would have flipped the call; the swap shipped as `shared-resources-swap-to-cmp-resources`. Codifying this re-check as a workflow step closes the proposal-to-implement drift gap.

**Apply-phase design-revision re-check (MUST when implementation surfaces a hiccup/blocker and you're tempted to revise the spec inline).** Sister rule at a different gate: when `/opsx:apply` hits an implementation-time issue (compile error revealing a framework constraint, runtime crash exposing an API mismatch, library API differing from design-time assumptions) and the natural response is to revise spec/design.md to match what the implementation can do, FIRST run a fresh date-anchored `WebSearch` to verify the proposed workaround is the canonical current pattern — pretrained "canonical pattern" knowledge can be 1–2 years stale, and `implementation issue → rationalize from memory → amend spec` risks shipping a non-canonical workaround. Query templates: `"<framework> <symptom> 2026 best practice"`, `"<library> <API> canonical pattern"`, `"<problem> <framework> recommended workaround"`. Read 2–3 sources; weight JetBrains/Google/official docs over Medium/Stack Overflow.

Outcomes:
- **Revision confirmed canonical** → one-liner in the revision commit body or design.md amendment (`canonical pattern per <source>, verified 2026-MM-DD`) and proceed.
- **A different canonical pattern surfaces** → STOP; surface via `AskUserQuestion`; user chooses (a) implement the actually-canonical pattern + re-amend the spec, or (b) accept the non-canonical workaround as a documented trade-off + file a `follow-up` issue. Never silently ship the rationalized-from-memory revision.

Skip for trivial adjustments that don't touch spec/design (import paths, naming, build-config tweaks). Trigger = "about to amend a spec/design Decision because implementation surprised me." Precedent: `shared-resources-swap-to-cmp-resources` `/opsx:apply` ([PR #119](https://github.com/aditrioka/nearyou-id/pull/119)) hit Compose's "no exception-catching around `@Composable`" invariant on the NearYouTypography defensive guard; first-attempt revision (commit `e229343`) rationalized "drop the guard; build-time validation makes runtime-missing impossible" — disproved by a fresh-dated WebSearch surfacing JetBrains issues #4111 / #3472 / #4387 (real bundled-font runtime failures) AND the canonical `FontFamilyResolver.preload()` + LaunchedEffect pattern that preserves the defensive contract. Codifying this re-check as a workflow step closes the apply-time-rationalization gap.

**CI gates per phase.** CI runs on every push to the unified branch; there is NO per-phase merge gate — the gate is the FINAL squash-merge. Per phase: proposal needs `openspec validate --strict` green; feat needs `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` green; archive needs `openspec validate --specs <capability> --strict` green. Mobile-touching changes additionally gate on flavor-qualified `:mobile:app:testDevDebugUnitTest` + `:mobile:app:testDevReleaseUnitTest` locally; **UI-affecting changes additionally require the manual `verify-loop` bring-up with screenshot evidence in the PR body BEFORE archive** (`docs/11-Engineering-Standards.md` §5 Definition of Done; enforcement: `/opsx:apply` step 7.5 + `/opsx:archive` step 3.5) — tests green alone does not clear a UI change. The bring-up surface is **context-routed automatically** (`scripts/_testing_context.sh`): local session → Android emulator + iOS simulator; cloud sandbox → real device via Firebase Test Lab (`scripts/run_on_device.sh` / the `device-run.yml` PR comment). Always invoke the mobile run via `scripts/test_android.sh` / `scripts/run_on_device.sh` — never hand-pick emulator-vs-farm or call `gradlew connected…` / `gcloud firebase test` directly.

**Staging deploy timing.** `.github/workflows/deploy-staging.yml` triggers on `main` push (auto-deploy after squash-merge) AND exposes `workflow_dispatch` for manual branch deploys. Under the one-PR convention, smoke tests SHOULD run pre-archive against a manual branch deploy — the squash-merge is a one-way door that auto-deploys `main`, so smoking the branch first catches deploy-config bugs (secret-slot drift, env-var renames, TLS scheme, eager-connect crashes) before they ship. Precedent: `reply-rate-limit` (PR [#49](https://github.com/aditrioka/nearyou-id/pull/49)) smoked pre-archive; the prior `like-rate-limit` cycle (PR [#37](https://github.com/aditrioka/nearyou-id/pull/37)) smoked post-merge and needed 3 follow-up fixes against already-shipped staging (PR #43 secret-name, PR #44 lazy-connect, GCP slot TLS-scheme update). `/opsx:apply` codifies the sequence: `gh workflow run deploy-staging.yml --ref <change-name>` → poll deploy run → run `dev/scripts/smoke-<change-name>.sh` → tick Section 6 tasks → `/opsx:archive`.

**Stacked PRs — mostly moot under the one-PR convention.** Proposal/feat/archive commits share one branch, so there's no parent/child PR pair to stack. The footgun below STILL applies when one OpenSpec change genuinely depends on another in-flight change (dependent branch bases off the parent branch, rebases onto `main` once the parent squash-merges).

The pre-#37 stacking footgun: GitHub does NOT auto-retarget a child PR when its parent (base branch) squash-merges — the squash creates a new commit on `main` with a different hash, so the child stays pointed at the orphaned parent branch. Symptoms: the child's merge button merges into the parent branch, and the child's code never lands on `main` even though both PRs show "Merged".

**Two safe patterns:**
1. **Sequence, don't stack** (preferred): merge the parent to `main` first; then open the child with `base: main`. One open PR at a time per logical chain.
2. **If you must stack** (e.g., a low-priority docs PR pre-reviewed in parallel): the moment the parent merges, retarget via `gh pr edit <child> --base main` AND rebase the child onto `main` (`git rebase main <child-branch>` — Git skips commits whose tree matches the squash-merged equivalent). Force-push with `--force-with-lease`.

**Recovery if a child PR already merged into the orphaned parent branch:** rebase the child branch onto current `origin/main` (drops the redundant parent commit), force-push with `--force-with-lease`, open a new PR (head = rebased branch, base = `main`). The merged-but-orphaned PR can be left as-is (noise, not destructive). Precedent: `feat/global-timeline-session-1` recovered via PR #29 after #27 merged into the docs branch instead of `main`.

**Splitting on an offline-prep boundary.** When a change has a heavy offline data-prep step (polygon seeds, ML weights, large fixtures, dataset license verification) that would block review of the rest, the schema/code half MAY ship as a separate **session of commits** on the same change branch (one-PR convention) or a separate feat PR (deprecated 3-PR shape). Preconditions:

1. Target columns / response DTOs / business queries MUST be NULL-tolerant or empty-state-tolerant — no window where a missing seed crashes a request or malforms a response.
2. The split MUST be documented in `design.md` (amend the relevant Decision in-place; reviewers see the rationale at archive time).
3. Both sessions MUST land on `main` (same final squash-merge under one-PR) within the same calendar week — no indefinite "we'll seed it later". If the data half slips, revert the schema commits and ship together.

Precedent (3-PR era): `global-timeline-with-region-polygons` shipped V11 (schema + trigger + view) as Session 1 PR #29 while the OSM dataset was prepared offline; V12 (552-row polygon seed) followed as Session 2 PR #31. Session-scope preamble in `tasks.md`; design amendment in `design.md` Decision 3 ("Status: amended during Session 2"). The 4-step trigger fallback ladder + NULL-as-`""` DTO mapping made the V11-only-deploy state safe.

**Archive commits touching shared specs.** When two changes both add requirements to the same canonical capability spec, their archive commits WILL conflict — `openspec archive` rewrites the spec from each change's delta independently, so the second to land sees a conflict. Resolution:

1. **Squash-merge sequentially**, not in parallel: first PR writes the canonical content; the second rebases onto fresh `main` and resolves pre-squash.
2. **Resolution rule**: prefer the version matching actually-shipped runtime behaviour. Superseded earlier-draft requirements in the second change are DROPPED (one-line note in the resolving commit message), not concatenated — concatenation produces contradictory requirements.
3. Force-push the rebased branch with `--force-with-lease` after `openspec validate --specs <capability> --strict` passes.

Precedent (3-PR era): PR #34 (`coordinate-jitter-lint-rule` archive) merged first; PR #35 (`global-timeline-with-region-polygons` archive) hit a 5-vs-3 requirement conflict on the `coordinate-jitter` spec. Resolution kept #34's 5 (canonical lint rule) + 2 of #35's 3 (trigger as DB-side sanctioned reader + reverse-geocode rationale); dropped #35's "Jitter-rule allowlist extended for V11 .sql file" requirement — superseded by the Kotlin-only rule design #34 shipped.

**When NOT to use OpenSpec.** Infra / tooling / CI / docs-only changes go through regular PRs. OpenSpec is for spec-driven product changes — capability + behavior + WHEN/THEN scenarios. Detekt rules, CI config, `build-logic/`, ops docs, READMEs: regular PR.

**Archive timing under the one-PR convention.** The archive commit lands on the change branch BEFORE the final squash-merge, sequenced per the pre-archive smoke convention (§ Staging deploy timing):

1. Feat + test commits land, CI green on the branch.
2. Manual staging deploy on the branch (`gh workflow run deploy-staging.yml --ref <change-name>`) — only when the change has runtime impact + a smoke script exists.
3. Smoke runs against the branch deploy; tick Section 6 in `tasks.md`.
4. Archive commit lands; `openspec validate --specs <capability> --strict` green.
5. Squash-merge to `main` → auto-deploys `main`-staging (production-equivalent of the image that just smoked green).

The pre-archive smoke is what makes the squash-merge safe (the `like-rate-limit` task-9.7 precedent gap). Docs-only / refactor-only changes: skip steps 2–3, mark Section 6 N/A in the archive commit body. If staging fails post-merge despite the pre-archive smoke (a dependency that only manifests on `main`-deployed staging — same Postgres + Redis + CF Workers), the hotfix ships as a NEW change with its own one-PR lifecycle — never retro-edit the squashed commit. Deploy tasks (typically 8.x) stay unchecked until *prod* infra is provisioned — don't block the squash on those.

**Force-push.** `--force-with-lease` on topic branches is fine (rewrite your own history freely). `main` requires explicit per-push user authorization — hook-enforced.

**CI expectations.** See [`.github/workflows/ci.yml`](../.github/workflows/ci.yml). Three jobs:
- `lint` — runs both `ktlintCheck` AND `detekt` in one job (previously `detekt` was missing — PRs [#31](https://github.com/aditrioka/nearyou-id/pull/31) / [#32](https://github.com/aditrioka/nearyou-id/pull/32)).
- `test` — full suite, excludes only `network`-tagged tests; `database`-tagged tests run against service containers `postgis/postgis:16-3.4` + `redis:7-alpine` mirroring `dev/docker-compose.yml`. DB tests auto-boot the full current Flyway migration set once per test JVM via `KotestProjectConfig.beforeProject()` — don't add per-spec Flyway migrate calls. (A standalone `build`/assemble job was dropped in PR [#45](https://github.com/aditrioka/nearyou-id/pull/45) — redundant with `test`, which compiles main code as a prerequisite.)
- `migrate-supabase-parity` — drops auto-enabled extensions and pre-seeds Supabase's `realtime`/`auth` schema surface (see `dev/supabase-parity-init.sql`) before running Flyway, catching migrations that depend on environment state they don't establish; extend the parity init SQL alongside any new migration that assumes new Supabase-provided state.

`paths-ignore` skips the full lane on docs-only PRs (`docs/**`, `**/*.md`, `.gitignore`, `LICENSE`); `concurrency: cancel-in-progress` aborts stale runs on new pushes.

---

## Key Architectural Decisions

- **Modular monolith**, not microservices. One Ktor deployable.
- **Dual JWT**: RS256 for REST (with JWKS + kid rotation) + HS256 for Supabase Realtime WSS. Third-Party Auth migration is trivial later. See [`docs/05-Implementation.md`](../docs/05-Implementation.md) § Authentication.
- **Coordinate fuzzing**: posts store both `actual_location` and `display_location` (HMAC-SHA256 jitter with `JITTER_SECRET`). Non-admin reads use `display_location` only.
- **Chat write path is Ktor-authoritative**: client writes REST → Ktor persists → Ktor broadcasts. No direct Postgres Changes subscription from clients.
- **CSAM trigger path**: Cloudflare CSAM Tool does NOT emit webhooks. MVP path is admin-triggered from the Admin Panel after CF's daily email; Phase 2 adds an optional CF Worker forwarder.
- **18+ only + under-18 blocklist** (`rejected_identifiers`); no account recovery by design.
- **Backups**: Supabase PITR 7-day + weekly `pg_dump` encrypted via `age` to R2 + append-only deletion log (7-year retention).

---

## Documentation Maintenance

The root [`README.md`](../README.md) is reader-facing (public-repo entry point) with load-bearing sections that drift if unmaintained. Discipline: **mechanical for the high-frequency drift point, manual-with-explicit-trigger for the rest** — soft "remember to update" rules don't survive a months-old codebase, while hard CI failures on doc drift create friction solo-dev velocity can't sustain.

| Trigger | Section to update | Mechanism |
|---|---|---|
| Add a new module to [`settings.gradle.kts`](../settings.gradle.kts) | README § What's in this repo | **Mechanical**: add a one-line entry to [`dev/module-descriptions.txt`](../dev/module-descriptions.txt), then run `dev/scripts/sync-readme.sh --write`. CI runs `--check` (warning-only) on every PR. |
| Add a **backend-included** (non-mobile-gated) module to [`settings.gradle.kts`](../settings.gradle.kts) | [`Dockerfile`](../Dockerfile) builder-stage COPY blocks | **Mechanical**: add `COPY <path>/build.gradle.kts …` (and `COPY <path>/src …` if the backend compiles against it) to the Dockerfile. The builder runs `installDist -PincludeMobile=false`; a non-gated `include()` whose directory isn't COPY'd fails Gradle settings evaluation in the image build → **every staging/prod deploy breaks silently while PR CI stays green** (PR #247 did this). If the module is mobile-only, gate its `include()` behind `if (includeMobile.toBoolean())` instead. Guard: [`dev/scripts/check-dockerfile-module-copies.sh`](../dev/scripts/check-dockerfile-module-copies.sh) — run before pushing build-file changes; belongs in CI's lint lane. |
| Stack swap (e.g., Ktor → Spring, Postgres → CockroachDB, Cloudflare → Fastly) | README § Stack table | **Manual**: same change that swaps the dependency. Reviewer-checked. |
| License posture change | [`LICENSE`](../LICENSE) + README § License | **Manual**: rare event; treated as a real change. |
| Major reorganization in `docs/` (file rename/split, top-level section change) | README § Documentation map | **Manual**: same change as the doc move. |
| Repo visibility flip (private ↔ public) | CLAUDE.md § Critical invariants → "Public repository posture" line | **Manual**: one-time event per flip. |
| Add or remove a CI job | README § Stack table → CI row + workflow file | **Manual**: same change as the workflow edit. |

The `dev/scripts/sync-readme.sh` script:
- Sentinel markers in README (`<!-- AUTOGEN:modules:start -->` / `<!-- AUTOGEN:modules:end -->`) bracket the auto-generated module list.
- `--check` (CI): exits non-zero with a diff if stale; the CI step surfaces it as an annotation, not a hard fail.
- `--write` (local): regenerates the block in-place. Run after editing `dev/module-descriptions.txt` or adding a module.
- A module missing a description is a hard error in both modes — every new module ships with a one-line explanation.

Cheap to extend (more sentinel-bracketed sections + the same script) when other auto-derivable content emerges. Do **not** extend it to prose sections (stack table, license summary, build commands) — they lack a single non-README source of truth, and templating them produces brittle markdown.

## Doc Map

| File | Scope |
|---|---|
| [`docs/00-README.md`](../docs/00-README.md) | Overview, principles, cross-references |
| [`docs/01-Business.md`](../docs/01-Business.md) | Freemium tiers, pricing, payments, ads, GTM, cost forecast |
| [`docs/02-Product.md`](../docs/02-Product.md) | Feature specs (posts, timeline, social, chat, media, search, notifications) |
| [`docs/03-UX-Design.md`](../docs/03-UX-Design.md) | Copy, onboarding, empty/permission/consent flows |
| [`docs/04-Architecture.md`](../docs/04-Architecture.md) | Tech stack, diagrams, modules, deployment, observability, backup, push |
| [`docs/05-Implementation.md`](../docs/05-Implementation.md) | DB schemas, algorithms, auth/session, rate limits, cache keys, feature flags |
| [`docs/06-Security-Privacy.md`](../docs/06-Security-Privacy.md) | Attestation, anti-spam, moderation, CSAM, UU PDP, age gate |
| [`docs/07-Operations.md`](../docs/07-Operations.md) | Admin panel stack, admin schema, IAP/Cloud Armor/WebAuthn |
| [`docs/08-Roadmap-Risk.md`](../docs/08-Roadmap-Risk.md) | Phases, CI lint rules, risk register, open decisions |
| [`docs/09-Versions.md`](../docs/09-Versions.md) | Version pinning decisions log (per-pin rationale) |
| [`docs/10-Setup-Checklist.md`](../docs/10-Setup-Checklist.md) | Environment/account setup checklist |
| [`docs/11-Engineering-Standards.md`](../docs/11-Engineering-Standards.md) | Architectural baseline: mobile/backend contracts, Pattern Registry, version currency policy, Definition of Done |

Before making a non-trivial change, skim the relevant doc — many details (jitter, rotation, race-safe patterns, rate-limit layers) are load-bearing and not duplicated here.
