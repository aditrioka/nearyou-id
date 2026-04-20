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
| Auth | Google Sign-In (Android Credential Manager) + Apple Sign-In (backend verify + Ktor-issued RS256 JWT) + Supabase-compatible HS256 WSS token |
| Device Attestation | Play Integrity API (Android), App Attest (iOS) |
| Realtime Chat | Supabase Realtime Broadcast mode via `ChatRealtimeClient` abstraction; swap to DIY Ktor WebSocket + Redis Streams in Month 15+ |
| Cache / Rate Limit | Upstash Redis |
| Message Bus (post-swap) | Upstash Redis Streams (persistent, consumer groups, XAUTOCLAIM) |
| Search | PostgreSQL `tsvector` + `pg_trgm` (GIN index) |
| Media Storage (non-image) | Cloudflare R2 (zero egress fee) |
| Image Processing & Delivery | Cloudflare Images served via custom subdomain `img.nearyouid.com` under the zone |
| CSAM Detection | Cloudflare CSAM Scanning Tool (free, enabled at zone level, covers Images delivery) |
| Explicit Content Upfront | Google Cloud Vision Safe Search (blocks adult/violent at upload) |
| Push Notif | Firebase Cloud Messaging (platform-specific: Android data-only, iOS alert+NSE) |
| Transactional Email | Resend (data export links, admin alerts, subscription receipts) |
| Feature Flags / Remote Config | Firebase Remote Config |
| Connection Pool | HikariCP (built-in PgBouncer from Supabase). Max pool size 20 per Ktor instance. |
| Migration | Flyway (runs as Cloud Run Jobs `nearyou-migrate` pre-deploy, using a dedicated DDL-scoped role `flyway_migrator` distinct from `admin_app` and the main API role) |
| Subscription | RevenueCat SDK |
| Deployment | Google Cloud Run (backend) + Cloud Run Jobs (backup + workers + migrations) |
| SSL/TLS | Cloudflare managed SSL for nearyouid.com + subdomains (api, img, admin) |
| Observability Traces | OpenTelemetry SDK + Grafana Cloud |
| Observability Metrics | GCP Cloud Monitoring |
| Mobile + Backend Errors | Sentry KMP SDK (unified Android + iOS + backend). dSYM (iOS) + ProGuard mappings (Android) uploaded via CI step. |
| Product Analytics | Amplitude (free tier event quota, opt-in per UU PDP) |
| Localization | Moko Resources or Compose MP Resources |
| Backup | Supabase PITR 7-day + Cloudflare R2 offsite weekly dump (AES-256-GCM via `age` CLI in the backup container) + append-only deletion log |
| Text Moderation | Keyword blocklist + UU ITE categories + Google Perspective API (dev Phase 2 stopgap) |
| Serialization | kotlinx.serialization |

**Version pinning**: All patch-level versions frozen in Pre-Phase 1 in the *Version Pinning Decisions Log*. Auto-update policy via Dependabot/Renovate.

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
    │                        ├──▶ Cloudflare Images via img.nearyouid.com ──▶ CSAM Tool auto-scan at zone
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

Chat flow: Ktor as authoritative publisher. Client writes via REST, Ktor persists to Postgres, Ktor broadcasts via Supabase Realtime, subscribers receive. There is no direct Postgres Changes subscription from the client.

**In-transit encryption**: all client-to-server channels use TLS (HTTPS for REST, WSS for Realtime). Supabase and Cloudflare endpoints mandate TLS 1.2+ by default.

**Client IP origin**: all inbound traffic transits Cloudflare. Ktor extracts the real client IP from the `CF-Connecting-IP` header, not from `X-Forwarded-For`. See `05-Implementation.md` "Cloudflare-Fronted IP Extraction" for middleware, Cloud Armor allowlist configuration, and spoof protection.

**CSAM detection trigger path**: the Cloudflare CSAM Scanning Tool does NOT emit webhooks. It blocks matched URLs (HTTP 451) and sends a daily email notification to a configured address. The `/internal/csam-webhook` handler in Ktor is NOT triggered by Cloudflare directly; it is invoked by one of the following supported paths:

- **Primary (MVP)**: an admin-triggered action in the Admin Panel after reviewing the CF email notification. The admin pastes the matched URL / image_id, the handler executes the downstream actions (hard-delete post, ban user, cascade, archive metadata, Kominfo queue).
- **Automated Phase 2+**: a Cloudflare Worker attached to the `img.nearyouid.com` route that watches for `451 Unavailable For Legal Reasons` responses and POSTs to `/internal/csam-webhook` with the same payload shape. This tightens the time-to-action but is optional for MVP.
- **Alternative polling**: a daily Cloud Run Job that parses the Resend-received email inbox (via IMAP or the email provider's API). Deferred as it adds moving parts.

Full CSAM detection flow, archive schema, and Kominfo SOP: see `06-Security-Privacy.md`.

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

Redis Streams provide:
- Message persistence during the stream retention window
- Consumer groups for fan-out to multiple Ktor instances
- XAUTOCLAIM for failover: instance down, pending messages re-claimed by another instance
- MAXLEN trimming (~100 per stream) to bound memory usage

---

## Dependency Isolation Pattern

```
:core:domain              (pure Kotlin, zero vendor dependencies)
:core:data                (interface definitions, DTO)
:shared:distance          (renderDistance logic, JVM + native targets)
:shared:resources         (Moko Resources strings for UI)
:infra:supabase           (Supabase implementation)
:infra:r2                 (Cloudflare R2 implementation)
:infra:cloudflare-images  (Cloudflare Images + CSAM webhook handler)
:infra:revenuecat         (RevenueCat implementation, webhook signature verify)
:infra:redis              (Upstash Redis + Streams implementation)
:infra:attestation        (Play Integrity + App Attest)
:infra:resend             (Resend transactional email wrapper)
:infra:remote-config      (Firebase Remote Config wrapper)
:infra:otel               (OpenTelemetry tracing)
:infra:sentry             (Sentry KMP wrapper for Android/iOS/JVM)
:infra:amplitude          (Amplitude HTTP client wrapper, consent-aware)
:infra:postgres-neon      (scaffold for backup migration path)
:infra:ktor-ws            (Ktor WebSocket, develop from Month 14+)
:backend:ktor             (Ktor routes, DI wiring via Koin)
:mobile:app               (KMP + Compose Multiplatform)
```

### Backend Modules (inside `:backend:ktor`)

- **User module**: authentication, user profile, subscription status, attestation verification, Apple S2S handler, age gate enforcement (18+ only), FCM token registration, analytics consent, suspension unban worker
- **Post module**: post creation, timeline generation (Nearby + Following + Global), spatial queries, edit history (transactional), search (FTS)
- **Social module**: follow, like, reply, notification write-path
- **Chat module**: 1:1 messaging, post embedding with snapshot, conversation management, broadcast orchestration, block enforcement
- **Media module**: upload validation, Vision Safe Search pre-check, CF Images integration, CSAM webhook handler, feature-flag check (`image_upload_enabled`)
- **Moderation module**: block, report, shadow ban logic, moderation queue, `rejected_identifiers` check at signup
- **Admin module**: `/admin/*` routes on a separate subdomain
- **Internal module**: `/internal/*` routes (OIDC auth)
- **Health module**: `/health/live`, `/health/ready` (no auth, rate-limited)

### Chat Realtime Abstraction

```kotlin
interface ChatRealtimeClient {
    fun subscribe(conversationId: String): Flow<ChatMessage>
    suspend fun unsubscribe(conversationId: String)
}
```

Implementations:
- `:infra:supabase` (`SupabaseBroadcastChatClient`, default during pre-swap period Months 1-14)
- `:infra:ktor-ws` (`KtorWebSocketChatClient`, developed from Month 14+, backed by Redis Streams)

---

## Health Check Endpoints

```
GET /health/live   -> 200 always (liveness, Cloud Run probe)
GET /health/ready  -> 200 if all dependencies reachable within 2s, else 503
```

`/health/ready` checks (run in parallel via `coroutineScope + async`):
- Postgres SELECT 1 (timeout 500ms)
- Redis PING (timeout 200ms)
- Supabase Realtime HTTP probe (timeout 500ms)

Public endpoints (no auth) but rate-limited to 60 req/min per IP (prevent abuse). Cloud Run deployed with readiness probe `/health/ready` and liveness probe `/health/live`.

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

- Ktor calls the Resend REST API (send endpoint)
- Templates versioned in the repo under `/backend/email-templates/` (HTML + text)
- Retry logic: 3 attempts with exponential backoff on 5xx
- Idempotency via `resend_idempotency_key` = SHA256(user_id + event_type + timestamp_minute)
- Failure: log to Sentry + Slack alert if delivery rate <95% (14-day rolling)
- **Bounce handling**: Resend webhook flags the user for admin review on bounces

### PDP-Aware Limits

- Marketing email: only to explicit opt-ins (Settings checkbox, default OFF)
- Transactional email: always sent (service communication exemption per UU PDP)
- Apple Relay email bounce: blacklist the address, log the event, user can update via the sign-in relay detection path

---

## Deployment Strategy

### Environments (Three-Tier Model)

NearYouID runs three environments concurrently from Phase 3.5 onward. They differ in purpose, spec, and user population.

| Env | Purpose | User base | Lifecycle |
|-----|---------|-----------|-----------|
| `dev` (local) | Day-to-day coding, unit + integration tests | Oka's laptop | Always |
| `staging` (cloud, minimal-spec) | Pre-release validation on prod-like infra with throwaway data | QA + internal beta testers only | From Phase 3.5 onward, runs indefinitely (including post-launch for ongoing feature development) |
| `production` (cloud, full-spec) | Live users | Real MAU | From Public Launch onward |

**Why three environments even post-launch**: after Public Launch, new feature work (Month 6 image upload, Month 15 realtime swap, future features) must be validated against a prod-like stack before hitting real users. Staging is the buffer where breaking changes are caught.

### dev (local)

Supabase CLI for local parity:
```bash
npx supabase start  # spin up local Postgres + PostGIS + GoTrue + Realtime + PostgREST
```

Docker Compose for:
- Ktor backend (build + run locally)
- Redis (`redis:alpine` with connection string to Ktor)
- Stub interfaces for CF Images, Sentry, Grafana, Amplitude, Resend
- Flyway container runs against the local Supabase Postgres

Parity ~90% with prod. Main gap: cross-region latency not replicable, covered in the Phase 2 benchmark against staging.

### staging (cloud, minimal-spec, NOT prod-equivalent)

**Explicit spec reduction**: staging runs on free tiers and scale-to-zero configurations. It is NOT sized to match production traffic; it is sized for QA smoke tests and pre-deploy validation.

**Stack**:
- **Backend**: separate Cloud Run service `nearyou-api-staging`, scale-to-zero, min-instance 0, max 2 instances
- **Database**: separate Supabase project on **Free tier** (500MB cap, 7-day idle auto-pause accepted). CI smoke ping on active sprint days keeps it warm; quiet weekends let it pause.
- **Cache**: separate Upstash Redis **Free tier** database
- **Object storage**: separate Cloudflare R2 bucket `nearyou-staging` on the 10GB Free tier
- **Images**: separate Cloudflare Images account usage tracked on the same zone, served via `img-staging.nearyouid.com` with CSAM Tool enabled zone-wide
- **Subscription**: RevenueCat **sandbox mode** (free, fully isolated from production)
- **Email**: Resend sandbox sender (shared account, tagged `environment=staging`, low volume negligible)
- **Push**: separate Firebase project (free) + separate FCM credentials + separate APNs `.p8` key (sandbox APNs endpoint `api.sandbox.push.apple.com`)
- **Feature flags**: Firebase Remote Config parameter conditions with `environment == 'staging'` filter (single Firebase project, logical separation via conditions)
- **Observability**: Sentry `environment=staging` (shared Sentry project, free tier headroom), Amplitude project shared with prod using `environment` user property (does NOT pollute prod funnels because dashboards filter), Grafana Cloud shared (tagged)
- **Attestation**: Play Integrity + App Attest in staging use the `attestation_bypass_google_ids_sha256` Remote Config whitelist by default (QA accounts bypass enforcement)

**Subdomain map**:
- `api-staging.nearyouid.com` (Ktor API)
- `admin-staging.nearyouid.com` (Admin Panel)
- `img-staging.nearyouid.com` (Cloudflare Images delivery)

**Secret Manager namespace**:
- All staging secrets prefixed `staging-*` in GCP Secret Manager (e.g. `staging-ktor-rsa-private-key`, `staging-supabase-jwt-secret`, `staging-revenuecat-webhook-secret`, `staging-jitter-secret`, `staging-age-private-key`, `staging-csam-archive-aes-key`, `staging-admin-app-db-connection-string`, `staging-firebase-admin-sa`, `staging-apns-key-p8`, `staging-resend-api-key`)
- Production keeps the current names (e.g. `ktor-rsa-private-key`) implicitly namespaced as `prod-*` going forward; migration script renames production slots in Pre-Phase 1

**Staging data policy**:
- Synthetic seed data only
- No production PII ever copied to staging
- DB can be nuked + reseeded as needed without audit trail concerns
- `rejected_identifiers` and `csam_detection_archive` always empty (reset on deploy)

**Cost**: ~Rp15-40k/month marginal (staging line item in `01-Business.md` cost table)

### production (cloud, full-spec)

Current Launch Phase stack. All production secrets, domains, and service instances are those without the `staging-` prefix or without the `-staging` subdomain suffix.

**Subdomain map**:
- `api.nearyouid.com`
- `admin.nearyouid.com`
- `img.nearyouid.com`

### Config Separation Pattern

Ktor `application.conf` (HOCON) reads the current environment from the `KTOR_ENV` environment variable:

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
- Merge to `main` branch → auto-deploys to staging (Cloud Run revision rollout + Flyway migration against staging DB)
- Git tag `v*` (e.g. `v1.0.3`) → deploys to production after manual approval gate in GitHub Actions
- Rollback: both environments support Cloud Run revision rollback via previous revision tag; Flyway rollback is manual (no auto-downgrade in production, DBA-level responsibility)

**Mobile client config**:
- Android build flavors: `staging` (points at `api-staging.nearyouid.com`, attestation bypass), `production` (points at `api.nearyouid.com`, attestation enforce)
- iOS xcconfig schemes: `Staging`, `Production` with `API_BASE_URL` and Firebase `GoogleService-Info.plist` swapped via build settings
- QA testers get the staging flavor via Firebase App Distribution / TestFlight internal
- Public App Store + Play Store listings ship the production flavor only

### Pre-Launch Development Phase (~19-20 weeks, when staging is bootstrapping)

Before Phase 3.5, only `dev` exists on a laptop. During Phase 3.5, staging is provisioned and comes up alongside the Admin Panel build. Production is NOT spun up until Pre-Launch (Weeks 17-19) when the seed-user soft launch begins.

Free tier for all dev-phase components:
- Backend: Cloud Run free tier
- Backup: Cloud Run Jobs free tier
- Migrations: Cloud Run Jobs free tier
- DB: Supabase Free (500MB, 1-week idle auto-pause acceptable in dev)
- Cache: Upstash Free
- Storage: Cloudflare R2 Free (10GB)
- Push: FCM
- Domain: Niagahoster (~Rp15k/month, needed from the start for DNS + SSL)
- Play Integrity: free (10k verdicts/day; request increase to 100k pre-launch)
- App Attest: free
- Grafana Cloud: free tier
- Sentry: free tier
- Amplitude: free tier
- Resend: free tier (3,000 emails/month)
- Firebase Remote Config: free

**Note**: Supabase Free auto-pauses after 7 days idle. Switch to Supabase Pro at the start of the 500-user soft launch so the project stays hot for seed users. Staging remains on Free tier indefinitely (auto-pause on quiet weekends is acceptable for a QA-only environment).

### Launch Phase (Month 1+, ~Rp620-700k/month)

All production-cost components active (plus staging minimal-spec running in parallel):
- Cloud Run scale-to-zero (production: min-instance 0 initially, bump to 1 post-scale)
- Supabase Pro base fee (production only; staging stays Free tier)
- Cloud Run Jobs (minimal compute, ~Rp10k/month; backup + migration + workers)
- Upstash pay-as-you-go (production)
- R2 free tier is enough until Month 12
- Apple Developer $99/year (~Rp133k/month)
- Google Play Developer $25 one-time (verify current rate Pre-Phase 1)
- Resend free tier is enough (expected <500/month pre-launch volume)
- Staging marginal metered usage (~Rp15-40k/month)

### Scale Phase (Month 12+, variable)

- Cloud Run auto-scales per traffic
- Supabase Realtime overage (swap plan at Month 15)
- Supabase Pro DB size monitoring: alert at 60%, 75%, 90%. Start Month 3. Realistic at 30k MAU: DB 6-10GB vs 8GB cap. Disk upgrade per GB (billed separately from compute, verify actual rate in Pre-Phase 1).
- Upstash scales per usage (Streams are heavier than Pub/Sub; re-benchmark in Month 12)
- R2 + CF Images when image upload launches in Month 6
- Sentry team plan once the free tier is exceeded
- Amplitude upgrade when MAU >25k if free tier event quota is exceeded
- Resend Pro tier when volume exceeds 3k/month (around Months 13-15)
- Staging typically remains free-tier, metered usage only (line item ~Rp30-40k/month at Month 12+)

### Flyway Migration Deployment

- Migrations in `/backend/src/main/resources/db/migration/V<n>__<desc>.sql` (Flyway's required migration filename convention)
- CI/CD pre-deploy step triggers Cloud Run Jobs `nearyou-migrate-staging` or `nearyou-migrate-prod` (separate jobs with separate connection strings from GCP Secret Manager) with env var pointing to the target Supabase connection string
- Job runs `flyway migrate`, exits 0 on success, non-zero on fail (blocks deploy)
- Failure alert via Slack webhook
- Rollback: manual (Flyway does not auto-rollback in production; DBA-level responsibility)
- Deploy order enforced: staging migration must succeed + smoke tests pass before production migration kicks off

### Cross-Cloud Latency Mitigation

GCP Cloud Run (Jakarta) + Supabase (AWS Singapore) + Upstash + Cloudflare: per DB round-trip +15-30ms vs co-located.

**Phase 2 benchmark mandatory scope**:
- Representative dataset: 10k-50k post simulation, Jakarta dense
- Target p95 <200ms for the timeline endpoint
- Cold start Cloud Run p99 <3 seconds
- Auth check round-trip <30ms
- Spatial query isolated <50ms
- Load test 100 concurrent requests
- Re-benchmark Broadcast mode cost per message at realistic scale

**Mitigation ladder**:
1. HikariCP pool tuning (max 20 conn default)
2. **Batch query via CTE mandatory** (combine multiple queries into a single round-trip). MANDATORY in Phase 2.
3. Materialized view for hot timeline regions (refresh every 1-5 minutes)
4. Supabase read replica add-on
5. Migrate co-located: Neon (GCP Jakarta) or Railway Postgres GCP. Swap `:infra:supabase` for `:infra:postgres-neon`. 2-3 weeks with the abstraction layer in place.
6. Last resort: self-host Postgres on GCP Jakarta

**Plan B from Day 1**: `:infra:postgres-neon` scaffold + migration script draft ready by Phase 2; don't wait for the benchmark to fail.

---

## Observability Stack

### OpenTelemetry (backend tracing)

- OTel SDK in Ktor (auto-instrument: HTTP server, HTTP client, Postgres JDBC, Redis Lettuce)
- Backend: Grafana Cloud free tier

**Trace sampling**:
- Head sampling 100% during dev Phase 2 benchmark
- Production: 10% base + 100% for errors + 100% for slow (p95 >500ms)
- Tail sampling via OTel Collector if volume is high

**Instrumentation priorities**:
- **Mandatory spans**: HTTP request on Ktor, Supabase API calls, Redis calls, PostGIS spatial queries, CF Images calls, Resend API calls
- **Mandatory attributes**: `user_id` (hashed), `endpoint`, `db.statement` (parameterized), `supabase.realtime.channel`, `geo.cloud_region`
- **Trace context propagation**: W3C Trace Context on all outbound HTTP

### Sentry KMP (unified crash + error reporting)

- Mobile crash reporting on Android + iOS via Sentry KMP SDK
- Backend error reporting via Sentry Java
- Unified dashboard for correlation
- Setup via `:infra:sentry` module:

```kotlin
expect object SentryProvider {
    fun init(dsn: String, environment: String)
    fun captureException(t: Throwable, context: Map<String, Any>)
    fun setUser(userId: String, username: String?)
}
```

**Symbol / mapping upload (CI step, mandatory)**:
- Android: Gradle task `sentry-cli upload-proguard` + `uploadSentrySymbolsDebug/Release`
- iOS: Xcode build phase `sentry-cli upload-dsym` or Fastlane integration
- Backend: no symbol upload (Kotlin JVM stack traces readable)

### Amplitude (product analytics, opt-in)

- Free tier event quota
- KMP integration via HTTP API wrapper in `:infra:amplitude`
- **Consent-gated**: check `users.analytics_consent.analytics = TRUE` before firing an event. Silently suppress if off.
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

1. **Supabase PITR (existing)**: 7-day Point-in-Time Recovery, automatic, included in Pro plan. Protects against recent user error + table corruption.

2. **Logical dump to R2 (weekly, encrypted)**:
   - Cloud Scheduler (Sunday 02:00 WIB) triggers Cloud Run Jobs `nearyou-backup-weekly`
   - Container runs `pg_dump --format=custom --compress=9` then pipes through `age` CLI (`age -r <public_key>`) before uploading to R2 via `aws s3 cp`
   - **Why `age` and not `openssl enc -aes-256-gcm`**: `openssl enc` does not stream AES-GCM safely across all supported distros (CBC/CTR are the reliable streaming modes; GCM requires chunking with per-chunk IV/tag handling that `enc` does not automate). `age` is a modern authenticated encryption tool (ChaCha20-Poly1305 under the hood), streams natively, handles key management cleanly, and is packaged in Alpine.
   - Recipient public key in the backup image, private key in GCP Secret Manager (`backup-age-private-key`)
   - Runtime limit 168 hours (vs Cloud Run HTTP 60 minutes)
   - Retention: 12 weekly (3 months) + 12 monthly (1 year) + 5 yearly
   - Storage: ~1-4GB/dump at 30k MAU = 12-48GB × R2 storage rate = ~Rp5-15k/month

3. **Schema-only backup (daily, unencrypted)**:
   - Separate Cloud Run Jobs, `pg_dump --schema-only` to R2
   - No PII; unencrypted is acceptable
   - Fast recovery: rebuild empty schema in minutes for disaster

4. **Append-only deletion log (PII integrity)**:
   - The worker that executes hard-delete writes an entry to the R2 object `deletion-log/{year}/{month}/{day}.jsonl`
   - Format: `{user_id, deleted_at, reason, cascade_summary}`
   - Retention 7 years (outlasts any backup window)
   - Object versioning enabled on the R2 bucket (tamper-evident)

5. **Monthly verify test + deletion reconciliation**:
   - Cloud Run Jobs that download the latest dump, decrypt via `age -d`, and restore to an ephemeral local Postgres
   - Apply deletion log entries [dump_timestamp, now] for reconciliation
   - Smoke test (row counts, index check, verify no resurrected tombstoned users)
   - Report result to Slack webhook

6. **Disaster recovery runbook**:
   - Scenario 1: Supabase regional outage then decrypt + restore R2 dump to a new Postgres instance, apply deletion log reconciliation, ~4h RTO
   - Scenario 2: Accidental corruption within 7 days then Supabase PITR, <1h RTO
   - Scenario 3: Supabase account compromise then decrypt + restore R2 to an independent provider + deletion log reconciliation, ~8h RTO
   - Scenario 4: Backup encryption key compromise then rotate `age` keypair, re-encrypt historical dumps from PITR source (if within window) or accept loss beyond the 7-day PITR horizon

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

### Platform-Specific Delivery Mode

**Android**: data-only FCM messages. App handles rendering locally with preference check. High priority (`priority: "high"`).

**iOS**: alert push with `mutable-content: 1` + Notification Service Extension (NSE).
- Rationale: silent push on iOS (`content-available:1` without alert) is aggressively throttled by the system and can be delayed by hours or dropped. Not reliable for chat messaging.
- Alert push guarantees delivery, with the NSE used to modify the body at runtime based on user preference.
- The `mutable-content: 1` flag is set on the APNs payload that FCM constructs; the Ktor server specifies it via the FCM Admin SDK `apns.payload.aps.mutableContent = true`.

**APNs setup**: key-based authentication (`.p8` key from Apple Developer Console), stored in GCP Secret Manager, used by the FCM SDK via server credentials.

### FCM Token Registration

Client calls server endpoint: `POST /api/v1/user/fcm-token` with body `{token, platform, app_version}`. Server upserts into the `user_fcm_tokens` table (schema in `05-Implementation.md`), deduping by `(user_id, platform, token)`. On every upsert the server refreshes `last_seen_at = NOW()`.

Client must re-register when:
- App first opens after install
- FCM token refresh SDK callback fires (periodic token rotation)
- User logs out + re-logs in
- App is reinstalled

### Token Cleanup (Two Complementary Paths)

- **Expired tokens (immediate)**: when the server attempts to send a push via FCM and the response is 404/410 (UNREGISTERED or INVALID_ARGUMENT), the specific `(user_id, platform, token)` row is deleted on the spot.
- **Stale tokens (scheduled)**: weekly `/internal/cleanup` job deletes `WHERE last_seen_at < NOW() - INTERVAL '30 days'`. Guards against tokens that stopped being re-registered without an explicit expiration event.

### Per-Conversation Batching

- Max 1 push per 10 seconds per conversation
- Burst merges into user-facing "3 pesan baru dari {username}" (count messages queued in the window)

### iOS NSE Implementation

- NSE reads preference from App Group shared UserDefaults (suite `group.id.nearyouid.shared`)
- Rewrites body if preference is ON: takes `body_full` from the data payload, truncates to 100 chars
- Backend sends full content in the `body_full` data field (NSE-only access, not in the default alert body)

### iOS NSE setup checklist (mandatory in iOS Phase 3)

- Xcode: App Group capability enabled in both the app target and the NSE target
- Developer Console: App Group ID registered (`group.id.nearyouid.shared`)
- Provisioning profiles updated for both targets
- NSE code: `UserDefaults(suiteName: "group.id.nearyouid.shared")`
- Main app: writes preference to the same suite
- Entitlements file: `com.apple.security.application-groups` array
- Test: push to a physical device, toggle preference, verify body rewrite

### Android Implementation

- App checks preference in local storage before rendering the notification
- Data-only FCM wakes the app; the app handles display

### FCM Quota

Free tier supports millions/day. Monitor delivery rate in the Firebase Console. Fallback when FCM fails (user offline, token expired): silent drop, user sees it on next app open via the in-app `notifications` list.

---

## iOS Privacy Manifest (PrivacyInfo.xcprivacy)

Required by Apple since iOS 17 (enforced May 2024). App rejection risk if missing.

File location: `iosApp/PrivacyInfo.xcprivacy`

Declare:
- `NSPrivacyCollectedDataTypes`: e.g. precise location (purpose: App Functionality), user ID (purpose: App Functionality + Analytics, if opt-in), crash data (purpose: App Functionality)
- `NSPrivacyAccessedAPITypes`: declare Required Reasons API usage
  - `NSUserDefaults` (CA92.1)
  - `FileTimestamp` (C617.1)
  - `SystemBootTime` (35F9.1 if used)
- `NSPrivacyTracking`: FALSE by default (unless ads tracking is opted-in via UMP; if TRUE, also add `NSPrivacyTrackingDomains`)

Pre-Phase 1 task: draft the manifest from the third-party SDK list (Sentry, Amplitude, RevenueCat, FCM, AdMob). Each SDK may have its own manifest that merges.

---

## Admin Panel Data Access

The admin panel Ktor service connects to Supabase Postgres via a dedicated service account (separate connection string in GCP Secret Manager), with a scoped role `admin_app` that has row-level access to all operational tables but cannot alter schema. All admin actions go through the Ktor admin module (no direct SQL console from admin users). The service account credentials are distinct from the main API's credentials so a leak of one does not auto-compromise the other.

### Admin Session Mechanism

The Admin Panel is a stateful Ktor + HTMX application, not an SPA. Sessions use classic server-side cookies:

- Cookie name `__Host-admin_session`, attributes `Secure; HttpOnly; SameSite=Strict; Path=/; Domain=admin.nearyouid.com`
- Opaque 256-bit random token (base64url); SHA256 at rest in `admin_sessions.session_token_hash`
- Separate CSRF token issued per session (SHA256 at rest in `admin_sessions.csrf_token_hash`), verified via `X-CSRF-Token` header on every state-changing request
- Session timeout: 30 min idle via `last_active_at`; cookie rotates on role escalation

Full mechanism + enforcement paths: see `05-Implementation.md` Admin Session Cookie Mechanism. WebAuthn ceremony state lives in `admin_webauthn_challenges` with 5-min TTL and consumed-guard against replay.

### Database Role Separation

Three database roles with explicit scoping:

- **`main_app`**: used by the main Ktor API. Row-level access to operational tables. No DDL. Cannot read `csam_detection_archive.encrypted_metadata`.
- **`admin_app`**: used by the Admin Panel Ktor service. Row-level access to all operational tables including the archive decrypt helper. No DDL. No UPDATE/DELETE on `admin_actions_log`.
- **`flyway_migrator`**: used only by the Flyway Cloud Run Job during migrations. DDL rights. No business-table reads at runtime (separate from `main_app`/`admin_app`).

Each role's connection string lives in a distinct GCP Secret Manager slot.

---

## Post-Swap Chat Architecture (Month 15+)

Ktor post-swap uses Redis Streams as the message bus between instances, NOT in-memory (Cloud Run horizontal scale) and NOT Pub/Sub (no persistence).

**Write flow**: REST write, Postgres insert, then `XADD stream:conv:{conv:<id>} * message_id <uuid> ...` into Redis Stream persistent.

**Read flow**: every Ktor instance with an active client WebSocket joins the consumer group `conv:<id>`:

```
XREADGROUP GROUP conv:<id> instance:<instance_id> BLOCK 5000 STREAMS stream:conv:{conv:<id>} >
```

**Failover**: instance A down, pending messages re-claimed by instance B via `XAUTOCLAIM stream:conv:{conv:<id>} conv:<id> instance:<B> 5000 0`. No message loss for connected clients.

**Trimming**: `XTRIM stream:conv:{conv:<id>} MAXLEN ~ 100` after each XADD. Client fetches history via REST for older messages (persistence in Postgres, not Redis).

**Idle stream GC**: Cloud Scheduler worker weekly `DEL`s streams with no activity for >7 days.

**Cost impact (realistic)**: at 10k MAU ~Rp120k/month (3.6M commands/month × Upstash rate), linear scale to 50k MAU ~Rp600k/month. Higher than the Pub/Sub estimate but reliability is worth it. Re-benchmark in Month 12 with production data before committing to the swap at Month 15.

### Swap Triggers (Cost-Based)

Verify current Supabase Realtime pricing in Pre-Phase 1.

Monitoring metrics on the admin dashboard:
1. Peak concurrent connections per month
2. Total Realtime events (including fan-out + presence)
3. Message fan-out ratio
4. Realtime cost per MAU

**Swap trigger**: ANY hit consistently for 3 months, then plan the swap:
- Realtime overage >Rp500k/month
- Chat latency p95 >500ms for 2 weeks
- Cost per MAU >Rp30/MAU
- Peak concurrent >5,000

**Realistic swap timeline**: 4-5 weeks total (development + testing 2-3 weeks, staged rollout canary 5% then 20% then 50% then 100% over 1-2 weeks).
