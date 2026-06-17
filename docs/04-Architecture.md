# NearYouID - Technical Architecture

System architecture, tech stack, module structure, deployment strategy, observability, backup, infrastructure-level design, email delivery, and health checks.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Mobile | Kotlin Multiplatform + Compose Multiplatform |
| Backend | Ktor |
| Admin Panel | Ktor server-side + Pebble/Freemarker + HTMX |
| Database | Supabase Pro (PostgreSQL + PostGIS) |
| Auth | Google Sign-In (Android Credential Manager) + Apple Sign-In (backend verify + Ktor-issued RS256 JWT) + Supabase-compatible HS256 WSS token (RS256 REST 15 min + refresh 30d `family_id` + HS256 Supabase 1h; see `05-Implementation.md` § Authentication) — _iOS currently ships Google Sign-In as a Mobile #3 substrate-proving stopgap (see `mobile-auth-signin-apple-ios`); Apple Sign-In remains the eventual-state iOS primary_ |
| Device Attestation | Play Integrity API (Android), App Attest (iOS) |
| Realtime Chat | Supabase Realtime Broadcast mode via `ChatRealtimeClient` abstraction; swap to DIY Ktor WebSocket + Redis Streams in Month 15+ |
| Cache / Rate Limit | Upstash Redis |
| Message Bus (post-swap) | Upstash Redis Streams (persistent, consumer groups, XAUTOCLAIM) |
| Search | PostgreSQL `tsvector` + `pg_trgm` (GIN index) |
| Media Storage (non-image) | Cloudflare R2 (zero egress fee) |
| Image Processing & Delivery | Cloudflare Images served via custom subdomain `img.nearyou.id` under the zone |
| CSAM Detection | Cloudflare CSAM Scanning Tool (free, enabled at zone level, covers Images delivery) |
| Explicit Content Upfront | Google Cloud Vision Safe Search (blocks adult/violent at upload) |
| Push Notif | Firebase Cloud Messaging (platform-specific: Android data-only, iOS alert+NSE) |
| Transactional Email | Resend (data export links, admin alerts, subscription receipts) |
| Feature Flags / Remote Config | Firebase Remote Config |
| Connection Pool | HikariCP (built-in PgBouncer from Supabase). Max pool size 20 per Ktor instance. |
| Migration | Flyway (runs as Cloud Run Jobs `nearyou-migrate` pre-deploy, using a dedicated DDL-scoped role `flyway_migrator` distinct from `admin_app` and the main API role) |
| Subscription | RevenueCat SDK |
| Deployment | Google Cloud Run (backend) + Cloud Run Jobs (backup + workers + migrations) |
| SSL/TLS | Cloudflare managed SSL for nearyou.id + subdomains (api, img, admin) |
| Observability Traces | OpenTelemetry SDK + Grafana Cloud |
| Observability Metrics | GCP Cloud Monitoring |
| Mobile + Backend Errors | Sentry KMP SDK (unified Android + iOS + backend). dSYM (iOS) + ProGuard mappings (Android) uploaded via CI step. |
| Product Analytics | Amplitude (free tier event quota, opt-in per UU PDP) |
| Localization | Compose Multiplatform Resources |
| Backup | Supabase PITR 7-day + Cloudflare R2 offsite weekly dump (AES-256-GCM via `age` CLI in the backup container) + append-only deletion log |
| Text Moderation | Keyword blocklist + UU ITE categories + Google Perspective API (dev Phase 2 stopgap) |
| Serialization | kotlinx.serialization |

**Version pinning**: all patch-level versions frozen in Pre-Phase 1 in the *Version Pinning Decisions Log*; auto-update policy via Dependabot/Renovate.

---

## System Architecture

### High-Level Diagram (Pre-Swap Period, Months 1-14)

```
Mobile App (KMP)
    │
    ├──HTTPS (REST)──▶  Ktor @ Cloud Run  ──▶ Supabase (Postgres+PostGIS+Auth)
    │                        │
    │                        ├──▶ Upstash Redis (rate limit, cache)
    │                        ├──▶ Cloudflare R2 (non-image media + backups + deletion log)
    │                        ├──▶ Cloudflare Images via img.nearyou.id ──▶ CSAM Tool auto-scan at zone
    │                        ├──▶ RevenueCat (subscription)
    │                        ├──▶ Resend API (transactional email)
    │                        ├──▶ Firebase Remote Config (feature flags)
    │                        ├──▶ Sentry (backend errors)
    │                        ├──▶ Amplitude (server-side events, consent-gated)
    │                        └──▶ OTel Collector ──▶ Grafana Cloud
    │
    └──WSS (Realtime, TLS)──▶ Supabase Realtime (Broadcast mode, private channel + RLS)
                         authenticated via HS256 token issued by Ktor
```

Chat flow: Ktor is the authoritative publisher — client writes via REST, Ktor persists to Postgres and broadcasts via Supabase Realtime, subscribers receive. No direct Postgres Changes subscription from the client.

**In-transit encryption**: all client-to-server channels use TLS (HTTPS for REST, WSS for Realtime); Supabase and Cloudflare endpoints mandate TLS 1.2+ by default.

**Client IP origin**: all inbound traffic transits Cloudflare; Ktor extracts the real client IP from `CF-Connecting-IP`, not `X-Forwarded-For`. Middleware, Cloud Armor allowlist configuration, spoof protection: `05-Implementation.md` "Cloudflare-Fronted IP Extraction".

**CSAM detection trigger path**: the Cloudflare CSAM Scanning Tool emits no webhooks — it blocks matched URLs (HTTP 451) + sends a daily email — so `/internal/csam-webhook` is NOT triggered by Cloudflare directly. Invoked by one of:

- **Primary (MVP)**: admin-triggered in the Admin Panel after reviewing the CF email — admin pastes the matched URL / image_id; the handler runs the downstream actions (hard-delete post, ban user, cascade, archive metadata, Kominfo queue).
- **Automated Phase 2+**: a Cloudflare Worker on the `img.nearyou.id` route watching for `451 Unavailable For Legal Reasons` responses, POSTing the same payload to `/internal/csam-webhook`; tightens time-to-action, optional for MVP.
- **Alternative polling**: a daily Cloud Run Job parsing the email inbox (IMAP / provider API); deferred — adds moving parts.

Full CSAM flow, archive schema, Kominfo SOP: `06-Security-Privacy.md`.

### High-Level Diagram Post-Swap (Month 15+)

```
[Client] ──REST──▶ [Ktor instance N] ──write──▶ [Supabase Postgres]
                          │
                          └──XADD──▶ [Upstash Redis Stream stream:conv:{conv:<id>}]
                                               │
                                               │ XREADGROUP consumer group
                                               ▼
[Client] ◀──WebSocket──▶ [Ktor instance M] (joined consumer group)
```

Redis Streams provide: message persistence within the stream retention window; consumer groups for fan-out to multiple Ktor instances; XAUTOCLAIM failover (instance down → pending messages re-claimed by another instance); MAXLEN trimming (~100 per stream) to bound memory.

---

## Dependency Isolation Pattern

> **Status legend** (this section + the rest of the file): **shipped** — module/package exists in code today, PR ref where useful; **partial** — exists but stubbed/incomplete, gaps called out inline; **DESIGN** — future architecture, not yet scaffolded; **ABANDONED** — explicit kill marker, do not revive without re-deciding.

### Currently scaffolded (shipped)

```
:core:domain              shipped — pure Kotlin, zero vendor deps
:core:data                shipped — interfaces + DTOs
:shared:tmp               shipped — KMP scaffold placeholder
:shared:distance          shipped — renderDistance, JVM target + commonMain
:infra:supabase           shipped — DB, auth, realtime broadcast publish (SupabaseBroadcastChatClient)
:infra:redis              shipped — Upstash rate limit + cache
:infra:fcm                shipped — FCM push dispatch (FcmDispatcher 414 LOC + tests)
:infra:oidc               shipped — internal endpoint OIDC verification
:infra:otel               shipped — OpenTelemetry tracing (commit f9a78f8, archive 2026-05-07-observability-otel-foundation)
:backend:ktor             shipped — Ktor routes, DI wiring via Koin
:mobile:app               shipped — Navigation 3 nav + Koin DI + Material 3 theme + HomeScreen placeholder (see § Mobile Status below)
:lint:detekt-rules        shipped — 7 custom Detekt rules
```

### Planned modules (DESIGN unless marked SCAFFOLD NEXT — do NOT `import` from `DESIGN` rows)

| Module | Status | Trigger to scaffold |
|---|---|---|
| `:shared:resources` | shipped | Mobile #2 / #2.5 (`shared-resources-moko-bootstrap`, [PR #116](https://github.com/aditrioka/nearyou-id/pull/116) → `shared-resources-swap-to-cmp-resources`, PR [#119](https://github.com/aditrioka/nearyou-id/pull/119)) — Compose Multiplatform Resources `Res` accessors + `NearYouColorScheme` + `NearYouTypography` + `NearYouColors` CompositionLocal extension surface + brand logos + Bahasa Indonesia strings. Consumed by `:mobile:app`'s `NearYouTheme` + `HomeScreen`. |
| `:infra:r2` | DESIGN | Image upload feature (Phase 2/3) — Cloudflare R2 (non-image, zero egress) |
| `:infra:cloudflare-images` | DESIGN | Image upload feature — Cloudflare Images (`img.nearyou.id`) + CSAM webhook handler |
| `:infra:revenuecat` | DESIGN | Premium subscription billing (webhook signature verify) |
| `:infra:resend` | DESIGN | Transactional email module-isation (project smoke-tested 2026-04-27) |
| `:infra:sentry` | SCAFFOLD NEXT | Follow-up `infra-sentry-kmp-module-isation` (split from Mobile #1 if scaffold scope grows; see [`openspec/project.md`](../openspec/project.md) § Mobile + Admin Scaffolding Priority menu Mobile #1) |
| `:infra:amplitude` | DESIGN | Consent-gated analytics HTTP client |
| `:infra:attestation` | DESIGN | Play Integrity + App Attest (post-MVP) |
| `:infra:remote-config` | DECISION NEEDED | DB-backed feature flags already operational (`premium_*_cap_override`); a separate Firebase Remote Config module may be redundant or complementary — needs explicit decision before scaffolding |
| `:infra:postgres-neon` | ABANDONED | Plan B scaffold not pursued; Supabase PITR is the backup posture |
| `:infra:ktor-ws` | ABANDONED | Realtime ships via Supabase Broadcast; Ktor WS swap path retired |

### Mobile Status

`:mobile:app` ships a production-shaped Compose Multiplatform scaffold (per `mobile-app-scaffold-replace-wizard`): one commonMain `App()` composable wrapping a Navigation 3 `NavDisplay` over a developer-owned `rememberNavBackStack` of `@Serializable NavKey` routes (swapped from Voyager via `mobile-nav-swap-to-navigation3`, which deliberately supersedes the prior Voyager statement), inside `NearYouTheme` (Material 3 light + dark, system-preference-driven); Koin DI via an idempotent `initKoin()` called from Android `MainActivity.onCreate` and from the iOS Swift `iOSApp.init()` block (which renders `ContentView` → `UIViewControllerRepresentable` → Kotlin `MainViewController()` → `App()`); start destination one placeholder `HomeScreen` rendering only a "NearYouID" label + version. The scaffold has zero networking, auth, or feature behavior — those ship in subsequent mobile changes per [`openspec/project.md`](../openspec/project.md) § Mobile + Admin Scaffolding Priority (Mobile #2 / #2.5 Resources scaffolding — Moko initially, swapped to CMP Resources; #3 Google Sign-In, #4 age gate, #5 first product screen). **Sections of this doc describing mobile-side rendering of features (NSE iOS push handling, App Group setup, push payload handling, attestation flow, etc.) remain forward-looking design** — contracts the backend already serves; the consumer side lands incrementally over Mobile #2-5+.

### Backend Modules (inside `:backend:ktor`)

Authoritative list — regenerate from `find backend/ktor/src/main/kotlin/id/nearyou/app -type d -maxdepth 1` if drift suspected. All packages below are shipped except `admin` (partial):

- **`auth`** — JWT verification, signup flow, JWKS, session/refresh, age gate, Apple S2S, realtime token endpoint
- **`block`** — V5; `POST/DELETE/GET /api/v1/blocks` + `BlockExclusionJoinRule` lint enforcement
- **`chat`** — 1:1 messaging, conversation management, broadcast orchestration, block enforcement; V15; 1,828 LOC; the largest single feature area
- **`common`** — shared utilities
- **`config`** — Koin wiring, secret resolution (`secretKey(env, name)`)
- **`dev`** — local-only development helpers
- **`engagement`** — V7 likes + V8 replies + rate-limit (`like-rate-limit`, `reply-rate-limit` archives)
- **`follow`** — V6; `POST/DELETE/GET /api/v1/follows`
- **`guard`** — block / shadow-ban guard helpers used by services
- **`health`** — `/health/live`, `/health/ready` (parallel async dependency probes)
- **`internal`** — `/internal/*` routes, each under OIDC auth on its OWN route subtree (`/internal/unban-worker`, `/internal/privacy-flip-worker`; the RevenueCat + Apple S2S vendor webhooks live separately with their own shared-secret/signed-payload auth, NOT OIDC)
- **`lint`** — runtime allowlist annotations referenced by Detekt rules
- **`moderation`** — V9 reports/moderation; `POST /api/v1/reports` + rate-limit; admin moderation queue is a 31-LOC stub for future admin UI
- **`notifications`** — V10 in-app notifications; 13-type catalog; read + write paths
- **`post`** — V4 post creation + spatial queries
- **`search`** — V13 Premium search FTS; `GET /api/v1/search` + rate-limit
- **`timeline`** — Nearby / Following / Global timeline endpoints; V11/V12 region polygons
- **`user`** — user profile, V14 FCM token registration
- **`admin`** (partial) — `SuspensionUnbanWorker` + `UnbanWorkerRoute` ONLY. **No admin UI, no admin REST surface, no `/admin/*` routes.** Admin schema (RLS, `admin_sessions`, `csrf_token_hash`) IS shipped in migrations and Detekt-enforced, but no admin REST/UI consumes it yet; per `docs/07-Operations.md` § Admin Panel, admin work is deferred to post-MVP.

The **media module** from earlier drafts (upload validation, Vision Safe Search, CF Images, CSAM webhook) does NOT exist — the entire image-upload + CSAM surface is DESIGN. The earlier **social module** is split into the `block`, `follow`, `engagement`, and `notifications` packages above.

### Chat Realtime Abstraction

```kotlin
interface ChatRealtimeClient {
    fun subscribe(conversationId: String): Flow<ChatMessage>
    suspend fun unsubscribe(conversationId: String)
}
```

Implementations: `:infra:supabase` (`SupabaseBroadcastChatClient`, default during pre-swap Months 1-14); `:infra:ktor-ws` (`KtorWebSocketChatClient`, developed from Month 14+, backed by Redis Streams).

---

## Health Check Endpoints

```
GET /health/live   -> 200 always (liveness, Cloud Run probe)
GET /health/ready  -> 200 if all dependencies reachable within 2s, else 503
```

`/health/ready` checks, run in parallel via `coroutineScope + async`: Postgres SELECT 1 (timeout 500ms), Redis PING (200ms), Supabase Realtime HTTP probe (500ms).

Public endpoints (no auth) but rate-limited to 60 req/min per IP (prevent abuse). Cloud Run deploys with **startup probe** `/health/ready` (the Cloud Run analog to a Kubernetes readiness probe — gates traffic during boot until the new revision is healthy; Cloud Run has no separate `--readiness-probe` flag, `--startup-probe` fills that role) and **liveness probe** `/health/live` (continuous post-startup keepalive).

**Target**: `/health/ready` green >99.9% (accounts for probabilistic tolerance of three parallel dependency checks).

---

## Transactional Email (Resend)

### Use Cases

| Trigger | Template | Volume estimate |
|---------|----------|-----------------|
| Data export ready | Signed R2 URL (TTL 24h) | On-demand |
| Admin CSAM alert | Internal notification | Expected near-zero |
| Subscription receipt summary | Monthly rollup (optional) | Premium count |
| Account deletion confirmation | On request | ~1% of MAU/month |
| Apple Hide My Email change detected | Relay email update notice | Edge case |
| Password reset | N/A (account recovery not available by design) | - |

### Implementation

- Ktor calls the Resend REST API (send endpoint); templates versioned in-repo under `/backend/email-templates/` (HTML + text)
- Retry: 3 attempts, exponential backoff on 5xx; idempotency via `resend_idempotency_key` = SHA256(user_id + event_type + timestamp_minute)
- Failure: log to Sentry + Slack alert if delivery rate <95% (14-day rolling)
- **Bounce handling**: Resend webhook flags the user for admin review on bounces

### PDP-Aware Limits

- Marketing email: only to explicit opt-ins (Settings checkbox, default OFF)
- Transactional email: always sent (service communication exemption per UU PDP)
- Apple Relay email bounce: blacklist the address, log the event; user updates via the sign-in relay detection path

---

## Deployment Strategy

### Environments (Three-Tier Model)

Three environments run concurrently from Phase 3.5 onward, differing in purpose, spec, and user population.

| Env | Purpose | User base | Lifecycle |
|-----|---------|-----------|-----------|
| `dev` (local) | Day-to-day coding, unit + integration tests | Oka's laptop | Always |
| `staging` (cloud, minimal-spec) | Pre-release validation on prod-like infra with throwaway data | QA + internal beta testers only | From Phase 3.5 onward, runs indefinitely (including post-launch for ongoing feature development) |
| `production` (cloud, full-spec) | Live users | Real MAU | From Public Launch onward |

**Why three even post-launch**: new feature work (Month 6 image upload, Month 15 realtime swap, future features) must be validated against a prod-like stack before hitting real users — staging is the buffer where breaking changes are caught.

### dev (local)

Supabase CLI for local parity:
```bash
npx supabase start  # spin up local Postgres + PostGIS + GoTrue + Realtime + PostgREST
```

Docker Compose adds: the Ktor backend (build + run locally); Redis (`redis:alpine`, connection string to Ktor); stub interfaces for CF Images, Sentry, Grafana, Amplitude, Resend; a Flyway container against the local Supabase Postgres.

Parity ~90% with prod; the main gap, cross-region latency, is not replicable locally — covered by the Phase 2 benchmark against staging.

### staging (cloud, minimal-spec, NOT prod-equivalent)

**Explicit spec reduction**: free tiers + scale-to-zero — sized for QA smoke tests and pre-deploy validation, NOT to match production traffic.

**Stack**:
- **Backend**: separate Cloud Run service `nearyou-api-staging`, scale-to-zero, min-instance 0, max 2 instances
- **Database**: separate Supabase project on **Free tier** (500MB cap, 7-day idle auto-pause accepted; CI smoke ping on active sprint days keeps it warm, quiet weekends let it pause)
- **Cache**: separate Upstash Redis **Free tier** database
- **Object storage**: separate Cloudflare R2 bucket `nearyou-staging`, 10GB Free tier
- **Images**: separate Cloudflare Images account usage tracked on the same zone, served via `img-staging.nearyou.id`, CSAM Tool enabled zone-wide
- **Subscription**: RevenueCat **sandbox mode** (free, fully isolated from production)
- **Email**: Resend sandbox sender (shared account, tagged `environment=staging`; low volume negligible)
- **Push**: separate Firebase project (free) + separate FCM credentials + separate APNs `.p8` key (sandbox APNs endpoint `api.sandbox.push.apple.com`)
- **Feature flags**: Firebase Remote Config parameter conditions with an `environment == 'staging'` filter (single Firebase project, logical separation via conditions)
- **Observability**: Sentry `environment=staging` (shared project, free-tier headroom); Amplitude shared with prod via an `environment` user property (does NOT pollute prod funnels — dashboards filter); Grafana Cloud shared (tagged)
- **Attestation**: Play Integrity + App Attest default to the `attestation_bypass_google_ids_sha256` Remote Config whitelist (QA accounts bypass enforcement)

**Subdomain map**: `api-staging.nearyou.id` (Ktor API), `admin-staging.nearyou.id` (Admin Panel), `img-staging.nearyou.id` (Cloudflare Images delivery).

**Secret Manager namespace**: all staging secrets prefixed `staging-*` in GCP Secret Manager (e.g. `staging-ktor-rsa-private-key`, `staging-supabase-jwt-secret`, `staging-revenuecat-webhook-secret`, `staging-jitter-secret`, `staging-age-private-key`, `staging-csam-archive-aes-key`, `staging-admin-app-db-connection-string`, `staging-firebase-admin-sa`, `staging-apns-key-p8`, `staging-resend-api-key`). Production keeps the current names (e.g. `ktor-rsa-private-key`), implicitly namespaced `prod-*` going forward; a migration script renames production slots in Pre-Phase 1.

**Staging data policy**: synthetic seed data only; no production PII ever copied to staging; the DB can be nuked + reseeded without audit-trail concerns; `rejected_identifiers` and `csam_detection_archive` always empty (reset on deploy).

**Cost**: ~Rp15-40k/month marginal (staging line item in `01-Business.md` cost table).

### production (cloud, full-spec)

Current Launch Phase stack — all production secrets, domains, and service instances are those without the `staging-` prefix or `-staging` subdomain suffix.

**Subdomain map**: `api.nearyou.id`, `admin.nearyou.id`, `img.nearyou.id`.

### Config Separation Pattern

Ktor `application.conf` (HOCON) reads the environment from the `KTOR_ENV` environment variable:

```hocon
ktor {
    environment = ${?KTOR_ENV}  # "staging" | "production"
}
```

Code path:
```kotlin
val env = environment.config.property("ktor.environment").getString()
val secretPrefix = if (env == "staging") "staging-" else ""
val connectionString = secretManager.access("${secretPrefix}admin-app-db-connection-string")
```

**CI/CD flow**:
- Merge to `main` → auto-deploys to staging (Cloud Run revision rollout + Flyway migration against the staging DB)
- Git tag `v*` (e.g. `v1.0.3`) → deploys to production after a manual approval gate in GitHub Actions
- Rollback: both environments support Cloud Run revision rollback via the previous revision tag; Flyway rollback is manual (see § Flyway Migration Deployment)

**Mobile client config**:
- Android build flavors: `staging` (points at `api-staging.nearyou.id`, attestation bypass), `production` (points at `api.nearyou.id`, attestation enforce)
- iOS **env × build-type build-configuration matrix** (`mobile-ios-build-config-matrix`, mirroring Android's flavor × build-type variants): `Dev Debug`, `Staging Debug`, `Prod Debug`, `Prod Release` — each a committed Xcode build configuration + shared scheme. Each config's `iosApp/Configuration/<Config>.xcconfig` `#include`s its env xcconfig (`Dev`/`Staging`/`Production.xcconfig` — bundle id, `APP_API_BASE_URL`, `ASSETCATALOG_COMPILER_APPICON_NAME`) **and** its CocoaPods-generated `Pods-iosApp.<config>.xcconfig` (debug- or release-typed), so `pod install`'s per-config mapping links the correct Pods. Resolution: `Dev Debug` → `id.nearyou.app.dev` + `localhost:8080` + `AppIcon-Dev`; `Staging Debug` → `.staging` + `api-staging.nearyou.id` + `AppIcon-Staging`; `Prod Debug`/`Prod Release` → `id.nearyou.app` + the fail-fast placeholder API + cobalt `AppIcon`. No `ASSETCATALOG_COMPILER_APPICON_NAME` is hardcoded in `project.pbxproj` (icon resolves per-config from xcconfig).
- QA testers get the staging flavor via Firebase App Distribution / TestFlight internal; public App Store + Play Store listings ship the production flavor only

### Pre-Launch Development Phase (~19-20 weeks, when staging is bootstrapping)

Before Phase 3.5, only `dev` exists on a laptop. Staging is provisioned during Phase 3.5 alongside the Admin Panel build; production is NOT spun up until Pre-Launch (Weeks 17-19), when the seed-user soft launch begins.

Free tier for all dev-phase components: Cloud Run (backend) + Cloud Run Jobs (backup + migrations); Supabase Free (500MB, 1-week idle auto-pause acceptable in dev); Upstash Free; Cloudflare R2 Free (10GB); FCM; App Attest; Grafana Cloud; Sentry; Amplitude; Firebase Remote Config; Play Integrity (10k verdicts/day — request increase to 100k pre-launch); Resend (3,000 emails/month). Domain: Niagahoster (~Rp15k/month, needed from the start for DNS + SSL).

**Note**: Supabase Free auto-pauses after 7 days idle — switch to Supabase Pro at the start of the 500-user soft launch so the project stays hot for seed users. Staging remains Free tier indefinitely (auto-pause on quiet weekends is acceptable for a QA-only environment).

### Launch Phase (Month 1+, ~Rp620-700k/month)

All production-cost components active (plus staging minimal-spec in parallel):
- Cloud Run scale-to-zero (production: min-instance 0 initially, bump to 1 post-scale)
- Supabase Pro base fee (production only; staging stays Free tier)
- Cloud Run Jobs (minimal compute, ~Rp10k/month; backup + migration + workers)
- Upstash pay-as-you-go (production)
- R2 free tier suffices until Month 12
- Apple Developer $99/year (~Rp133k/month)
- Google Play Developer $25 one-time (verify current rate Pre-Phase 1)
- Resend free tier suffices (expected <500/month pre-launch volume)
- Staging marginal metered usage (~Rp15-40k/month)

### Scale Phase (Month 12+, variable)

- Cloud Run auto-scales per traffic
- Supabase Realtime overage (swap plan at Month 15)
- Supabase Pro DB size monitoring: alert at 60%, 75%, 90%; start Month 3. Realistic at 30k MAU: DB 6-10GB vs 8GB cap. Disk upgrade per GB (billed separately from compute; verify actual rate in Pre-Phase 1).
- Upstash scales per usage (Streams are heavier than Pub/Sub; re-benchmark in Month 12)
- R2 + CF Images when image upload launches in Month 6
- Sentry team plan once the free tier is exceeded
- Amplitude upgrade when MAU >25k if the free-tier event quota is exceeded
- Resend Pro tier when volume exceeds 3k/month (around Months 13-15)
- Staging typically remains free-tier, metered usage only (line item ~Rp30-40k/month at Month 12+)

### Flyway Migration Deployment

- Migrations in `/backend/src/main/resources/db/migration/V<n>__<desc>.sql` (Flyway's required migration filename convention)
- CI/CD pre-deploy step triggers Cloud Run Jobs `nearyou-migrate-staging` or `nearyou-migrate-prod` (separate jobs, separate connection strings from GCP Secret Manager), env var pointing at the target Supabase connection string
- The job runs `flyway migrate`: exit 0 on success, non-zero blocks the deploy; failure alerts via Slack webhook
- Rollback: manual (Flyway does not auto-rollback in production; DBA-level responsibility)
- Deploy order enforced: the staging migration must succeed + smoke tests pass before the production migration kicks off

**Staging vs prod cold-start caveat (`RUN_FLYWAY_ON_STARTUP`)**: for pre-launch convenience, staging runs Flyway on every Cloud Run cold-start (gated by `RUN_FLYWAY_ON_STARTUP=true` in `.github/workflows/deploy-staging.yml`). With 70+ accumulated migrations this dominates startup time (~25s mean as of 2026-05); the canonical <3s p99 production target assumes the dedicated `nearyou-migrate-prod` Job path above. Production tag-deploy MUST NOT set `RUN_FLYWAY_ON_STARTUP`.

**Cold-start regression measurement caveat**: Cloud Monitoring's `run.googleapis.com/container/startup_latencies` distribution metric uses exponential histogram buckets (~2.5s wide at the 25-27s range), so sub-second mean deltas — the regime where most instrumentation cost lands (~100-300ms) — are invisible to bucket-based percentile interpolation when pre- and post-change samples cluster in the same bucket. Mean (computed from `sum/count`) preserves ms-level resolution: **use mean-delta for sub-second cold-start regression checks**, p99-bucket-shift only for >2s deltas. Per-event log-based measurement (Cloud Run logs each cold-start) is the high-resolution alternative if needed.

### Cross-Cloud Latency Mitigation

GCP Cloud Run (Jakarta) + Supabase (AWS Singapore) + Upstash + Cloudflare: +15-30ms per DB round-trip vs co-located.

**Phase 2 benchmark mandatory scope**: representative dataset (10k-50k post simulation, Jakarta dense); timeline endpoint p95 <200ms; Cloud Run cold start p99 <3 seconds; auth check round-trip <30ms; spatial query isolated <50ms; load test 100 concurrent requests; re-benchmark Broadcast mode cost per message at realistic scale.

**Mitigation ladder**:
1. HikariCP pool tuning (max 20 conn default)
2. **Batch query via CTE** (combine multiple queries into a single round-trip) — MANDATORY in Phase 2
3. Materialized view for hot timeline regions (refresh every 1-5 minutes)
4. Supabase read replica add-on
5. Migrate co-located: Neon (GCP Jakarta) or Railway Postgres GCP — swap `:infra:supabase` for `:infra:postgres-neon`; 2-3 weeks with the abstraction layer in place
6. Last resort: self-host Postgres on GCP Jakarta

**Plan B from Day 1**: `:infra:postgres-neon` scaffold + migration script draft ready by Phase 2 — don't wait for the benchmark to fail.

---

## Observability Stack

### OpenTelemetry (backend tracing)

OTel SDK in Ktor (auto-instrument: HTTP server, HTTP client, Postgres JDBC, Redis Lettuce); backend on the Grafana Cloud free tier.

**Trace sampling**: head sampling 100% during the dev Phase 2 benchmark; production 10% base + 100% errors + 100% slow (p95 >500ms); tail sampling via OTel Collector if volume is high.

**Instrumentation priorities**:
- **Mandatory spans**: HTTP request on Ktor, Supabase API calls, Redis calls, PostGIS spatial queries, CF Images calls, Resend API calls
- **Mandatory attributes**: `user_id` (hashed; UserPrincipal-backed `/api/v1/*` requests), `service.account.id` (hashed OIDC `sub`; Cloud-Scheduler-OIDC-backed `/internal/*` requests), `endpoint`, `db.statement` (parameterized), `supabase.realtime.channel`, `cloud.region` — full enforced attribute contract (including the forbidden-attributes list): [`openspec/specs/observability-otel-foundation/spec.md`](../openspec/specs/observability-otel-foundation/spec.md)
- **Trace context propagation**: W3C Trace Context on all outbound HTTP

### Sentry KMP (unified crash + error reporting)

Mobile crash reporting (Android + iOS) via the Sentry KMP SDK; backend errors via Sentry Java; unified dashboard for correlation. Setup via the `:infra:sentry` module:

```kotlin
expect object SentryProvider {
    fun init(dsn: String, environment: String)
    fun captureException(t: Throwable, context: Map<String, Any>)
    fun setUser(userId: String, username: String?)
}
```

**Symbol / mapping upload (CI step, mandatory)**: Android — Gradle task `sentry-cli upload-proguard` + `uploadSentrySymbolsDebug/Release`; iOS — Xcode build phase `sentry-cli upload-dsym` or Fastlane integration; backend — no symbol upload (Kotlin JVM stack traces readable).

### Amplitude (product analytics, opt-in)

- Free tier event quota; KMP integration via an HTTP API wrapper in `:infra:amplitude`
- **Consent-gated**: check `users.analytics_consent.analytics = TRUE` before firing an event; silently suppress if off
- **User properties (set on identify)**: `subscription_status`, `platform`, `install_date_bucket` (week-level only, for privacy), `city_name_at_last_post`
- Event taxonomy seed:
  - Onboarding: `app_opened`, `onboarding_started`, `onboarding_completed`, `signup_completed`, `age_gate_triggered`
  - Timeline: `post_viewed`, `post_created`, `post_liked`, `post_replied`
  - Premium: `paywall_viewed`, `paywall_dismissed`, `subscription_purchased`, `subscription_grace_entered`
  - Chat: `chat_opened`, `chat_message_sent`, `chat_preview_toggle`
  - Security: `attestation_failed`, `rate_limit_hit`, `refresh_token_reused`, `csam_detected`
  - Moderation: `user_blocked`, `user_reported`
- Funnel tracking: Onboarding (install → signup → first post), Premium (paywall → purchase), Retention (D1/D7/D30 cohort)

---

## Backup Strategy (Cloud Run Jobs)

### Layers

1. **Supabase PITR (existing)**: 7-day Point-in-Time Recovery, automatic, included in Pro. Protects against recent user error + table corruption.

2. **Logical dump to R2 (weekly, encrypted)**: Cloud Scheduler (Sunday 02:00 WIB) triggers Cloud Run Jobs `nearyou-backup-weekly`: `pg_dump --format=custom --compress=9` piped through the `age` CLI (`age -r <public_key>`), uploaded to R2 via `aws s3 cp`. **Why `age` not `openssl enc -aes-256-gcm`**: `openssl enc` doesn't stream AES-GCM safely across distros (CBC/CTR stream reliably; GCM needs per-chunk IV/tag handling `enc` doesn't automate); `age` (ChaCha20-Poly1305) streams natively, handles key management cleanly, and is packaged in Alpine. Recipient public key in the backup image, private key in GCP Secret Manager (`backup-age-private-key`). Runtime limit 168 hours (vs Cloud Run HTTP 60 minutes). Retention: 12 weekly (3 months) + 12 monthly (1 year) + 5 yearly. Storage: ~1-4GB/dump at 30k MAU = 12-48GB × R2 storage rate = ~Rp5-15k/month.

3. **Schema-only backup (daily, unencrypted)**: separate Cloud Run Jobs, `pg_dump --schema-only` to R2; no PII so unencrypted is acceptable; fast recovery — rebuild an empty schema in minutes for disaster.

4. **Append-only deletion log (PII integrity)**: the hard-delete worker writes `{user_id, deleted_at, reason, cascade_summary}` to the R2 object `deletion-log/{year}/{month}/{day}.jsonl`; retention 7 years (outlasts any backup window); object versioning enabled on the R2 bucket (tamper-evident).

5. **Monthly verify test + deletion reconciliation**: a Cloud Run Job downloads the latest dump, decrypts via `age -d`, restores to an ephemeral local Postgres, applies deletion-log entries [dump_timestamp, now] for reconciliation, smoke-tests (row counts, index check, verify no resurrected tombstoned users), and reports to a Slack webhook.

6. **Disaster recovery runbook**: (1) Supabase regional outage → decrypt + restore the R2 dump to a new Postgres instance + deletion-log reconciliation, ~4h RTO; (2) accidental corruption within 7 days → Supabase PITR, <1h RTO; (3) Supabase account compromise → decrypt + restore R2 to an independent provider + deletion-log reconciliation, ~8h RTO; (4) backup encryption key compromise → rotate the `age` keypair, re-encrypt historical dumps from the PITR source (if within window) or accept loss beyond the 7-day PITR horizon.

### Post-restore reconciliation script (mandatory)

```
1. Decrypt dump (age -d -i <private_key_file> <dump>.age > <dump>)
2. Restore dump to new Postgres
3. Download deletion-log objects for range [dump_timestamp, now]
4. For each deletion entry:
   - Re-apply tombstone (set users.deleted_at, null PII)
   - Re-apply cascade (delete chat tokens, follow relations, etc)
   - Log to new audit entry with restore_reconciliation flag
5. Verify: SELECT COUNT(*) FROM users WHERE id IN (deletion_log) AND deleted_at IS NULL = 0
6. Only then serve traffic
```

### Dockerfile (ultra-minimal)

```dockerfile
FROM postgres:alpine
RUN apk add --no-cache curl aws-cli jq age
COPY backup.sh /backup.sh
COPY restore-reconcile.sh /restore-reconcile.sh
ENTRYPOINT ["/backup.sh"]
```

---

## Push Notification Infrastructure

> **Status (2026-05-07).** Server-side push dispatch is **shipped** (`infra/fcm/` — `FcmDispatcher` 414 LOC + composite + payload builders + 6 tests, 1,292 test LOC), as are FCM token registration (V14 + `POST /api/v1/user/fcm-token` in `user/FcmTokenRoutes.kt`) and token cleanup-on-send. **Client-side handling is the spec source for mobile push scaffolding** — the Android preference-check, iOS NSE, App Group setup, body rewrite, and batching below are the contracts the future mobile-push change will implement (change-by-change menu: [`openspec/project.md`](../openspec/project.md) § Mobile + Admin Scaffolding Priority). The scheduled-cleanup `/internal/cleanup` job below is also DESIGN — only the immediate on-send cleanup ships today.

### Platform-Specific Delivery Mode

**Android** (DESIGN — client side): data-only FCM messages, high priority (`priority: "high"`); the app renders locally after a preference check.

**iOS** (DESIGN — client side): alert push with `mutable-content: 1` + Notification Service Extension (NSE). Rationale: silent push (`content-available:1` without alert) is aggressively throttled by iOS — delayed by hours or dropped — so it is not reliable for chat messaging. Alert push guarantees delivery; the NSE modifies the body at runtime per user preference. The `mutable-content: 1` flag is set on the APNs payload FCM constructs; Ktor specifies it via the FCM Admin SDK `apns.payload.aps.mutableContent = true`.

**APNs setup**: key-based authentication (`.p8` key from the Apple Developer Console), stored in GCP Secret Manager, used by the FCM SDK via server credentials.

### FCM Token Registration

Client calls `POST /api/v1/user/fcm-token` with body `{token, platform, app_version}`; the server upserts into `user_fcm_tokens` (schema in `05-Implementation.md`), deduping by `(user_id, platform, token)` and refreshing `last_seen_at = NOW()` on every upsert.

Client must re-register when: the app first opens after install; the FCM token-refresh SDK callback fires (periodic token rotation); the user logs out + re-logs in; the app is reinstalled.

### Token Cleanup (Two Complementary Paths)

- **Expired tokens (immediate)**: when an FCM send returns 404/410 (UNREGISTERED or INVALID_ARGUMENT), the specific `(user_id, platform, token)` row is deleted on the spot.
- **Stale tokens (scheduled)** — DESIGN: weekly `/internal/cleanup` job deletes `WHERE last_seen_at < NOW() - INTERVAL '30 days'`, guarding against tokens that stopped being re-registered without an explicit expiration event.

### Per-Conversation Batching

Max 1 push per 10 seconds per conversation; bursts merge into a user-facing "3 pesan baru dari {username}" (count of messages queued in the window).

### iOS NSE Implementation — DESIGN

- NSE reads the preference from App Group shared UserDefaults (suite `group.id.nearyou.shared`)
- Rewrites the body if the preference is ON: takes `body_full` from the data payload, truncates to 100 chars
- Backend sends full content in the `body_full` data field (NSE-only access, not in the default alert body)

### iOS NSE setup checklist (mandatory in iOS Phase 3) — DESIGN

- Xcode: App Group capability enabled in both the app target and the NSE target
- Developer Console: App Group ID registered (`group.id.nearyou.shared`)
- Provisioning profiles updated for both targets
- NSE code: `UserDefaults(suiteName: "group.id.nearyou.shared")`
- Main app: writes the preference to the same suite
- Entitlements file: `com.apple.security.application-groups` array
- Test: push to a physical device, toggle the preference, verify the body rewrite

### Android Implementation — DESIGN

- App checks the preference in local storage before rendering the notification
- Data-only FCM wakes the app; the app handles display

### FCM Quota

Free tier supports millions/day; monitor delivery rate in the Firebase Console. Fallback when FCM fails (user offline, token expired): silent drop — the user sees it on next app open via the in-app `notifications` list.

---

## iOS Privacy Manifest (PrivacyInfo.xcprivacy)

Required by Apple since iOS 17 (enforced May 2024); app rejection risk if missing. File location: `iosApp/PrivacyInfo.xcprivacy`.

Declare:
- `NSPrivacyCollectedDataTypes`: e.g. precise location (purpose: App Functionality), user ID (App Functionality + Analytics, if opt-in), crash data (App Functionality)
- `NSPrivacyAccessedAPITypes`: Required Reasons API usage — `NSUserDefaults` (CA92.1), `FileTimestamp` (C617.1), `SystemBootTime` (35F9.1 if used)
- `NSPrivacyTracking`: FALSE by default (unless ads tracking is opted-in via UMP; if TRUE, also add `NSPrivacyTrackingDomains`)

Pre-Phase 1 task: draft the manifest from the third-party SDK list (Sentry, Amplitude, RevenueCat, FCM, AdMob); each SDK may have its own manifest that merges.

---

## Admin Panel Data Access

The Admin Panel Ktor service connects to Supabase Postgres via a dedicated service account (separate connection string in GCP Secret Manager) with scoped role `admin_app` — row-level access to all operational tables, cannot alter schema (scope detail: § Database Role Separation below). All admin actions go through the Ktor admin module — no direct SQL console for admin users. The credentials are distinct from the main API's, so a leak of one does not auto-compromise the other.

### Admin Session Mechanism

The Admin Panel is a stateful Ktor + HTMX application, not an SPA. Sessions use classic server-side cookies:

- Cookie name `__Host-admin_session`, attributes `Secure; HttpOnly; SameSite=Strict; Path=/` — NO `Domain` attribute (the `__Host-` prefix locks the cookie to the origin that sets it per RFC 6265bis §4.1.3.2; adding `Domain` would make the browser drop the `Set-Cookie`)
- Opaque 256-bit random token (base64url); SHA256 at rest in `admin_sessions.session_token_hash`
- Separate CSRF token issued per session (SHA256 at rest in `admin_sessions.csrf_token_hash`), verified via the `X-CSRF-Token` header on every state-changing request
- Session timeout 30 min idle via `last_active_at`; cookie rotates on role escalation

Full mechanism + enforcement paths: see `05-Implementation.md` Admin Session Cookie Mechanism. WebAuthn ceremony state lives in `admin_webauthn_challenges` with a 5-min TTL and a consumed-guard against replay.

### Database Role Separation

Three database roles with explicit scoping, each with its connection string in a distinct GCP Secret Manager slot:

- **`main_app`**: the main Ktor API. Row-level access to operational tables; no DDL; cannot read `csam_detection_archive.encrypted_metadata`.
- **`admin_app`**: the Admin Panel Ktor service. Row-level access to all operational tables including the archive decrypt helper; no DDL; no UPDATE/DELETE on `admin_actions_log` (full schema + immutability contract: `07-Operations.md` § Admin Actions Log).
- **`flyway_migrator`**: used only by the Flyway Cloud Run Job during migrations. DDL rights; no business-table reads at runtime (separate from `main_app`/`admin_app`).

---

## Post-Swap Chat Architecture (Month 15+)

Ktor post-swap uses Redis Streams as the message bus between instances — NOT in-memory (Cloud Run scales horizontally) and NOT Pub/Sub (no persistence).

**Write flow**: REST write → Postgres insert → `XADD stream:conv:{conv:<id>} * message_id <uuid> ...` into the persistent Redis Stream.

**Read flow**: every Ktor instance with an active client WebSocket joins the consumer group `conv:<id>`:

```
XREADGROUP GROUP conv:<id> instance:<instance_id> BLOCK 5000 STREAMS stream:conv:{conv:<id>} >
```

**Failover**: instance A down → pending messages re-claimed by instance B via `XAUTOCLAIM stream:conv:{conv:<id>} conv:<id> instance:<B> 5000 0`. No message loss for connected clients.

**Trimming**: `XTRIM stream:conv:{conv:<id>} MAXLEN ~ 100` after each XADD; clients fetch older history via REST (persistence in Postgres, not Redis).

**Idle stream GC**: a Cloud Scheduler worker weekly `DEL`s streams with no activity for >7 days.

**Cost impact (realistic)**: at 10k MAU ~Rp120k/month (3.6M commands/month × Upstash rate), scaling linearly to ~Rp600k/month at 50k MAU. Higher than the Pub/Sub estimate but the reliability is worth it. Re-benchmark in Month 12 with production data before committing to the swap at Month 15.

### Swap Triggers (Cost-Based)

Verify current Supabase Realtime pricing in Pre-Phase 1.

Monitoring metrics on the admin dashboard: (1) peak concurrent connections per month, (2) total Realtime events (including fan-out + presence), (3) message fan-out ratio, (4) Realtime cost per MAU.

**Swap trigger** — ANY hit consistently for 3 months → plan the swap: Realtime overage >Rp500k/month; chat latency p95 >500ms for 2 weeks; cost per MAU >Rp30/MAU; peak concurrent >5,000.

**Realistic swap timeline**: 4-5 weeks total — development + testing 2-3 weeks, staged canary rollout 5% → 20% → 50% → 100% over 1-2 weeks.
