# NearYouID - External Setup Checklist

Operational checklist untuk akun, infrastruktur, domain, credentials, dan datasets yang disiapkan **di luar kodingan**. Komplemen ke `08-Roadmap-Risk.md` Pre-Phase 1 section, fokus ke eksekusi (ceklist yang bisa di-tick).

**How to use**: tick `[x]` saat selesai, tambah catatan inline (lokasi kredensial, tanggal, dll). Kredensial sensitif JANGAN ditulis di file ini — simpan di GCP Secret Manager / 1Password / password manager pilihan; file ini hanya catat "disimpan di mana".

**Status legend**: `[ ]` belum, `[x]` selesai, `[~]` in progress, `[!]` blocked.

---

## 1. Domain & DNS (Start di sini, blocking untuk banyak hal)

Domain `nearyou.id` terdaftar di Hostinger. Langkah berikut memindah DNS management ke Cloudflare (registrasi tetap di Hostinger).

- [ ] Signup Cloudflare account (free plan) - https://dash.cloudflare.com/sign-up
- [ ] Add site `nearyou.id` ke Cloudflare, pilih Free plan
- [ ] Copy 2 nameservers yang Cloudflare kasih
- [ ] Login Hostinger → domain `nearyou.id` → Nameservers → ganti ke Cloudflare NS
- [ ] Tunggu propagasi (biasanya 1-24 jam), verify status "Active" di Cloudflare dashboard
- [ ] Di Cloudflare: enable **CSAM Scanning Tool** di Caching > Configuration
- [ ] Verify email address untuk CSAM notifications (wajib sebelum production)
- [ ] Setup SSL/TLS mode ke "Full (strict)" di Cloudflare
- [ ] Siapkan DNS records untuk subdomain (belum perlu pointing ke server, record bisa ditambah nanti):
  - [ ] `api.nearyou.id` (production API)
  - [ ] `admin.nearyou.id` (production admin panel)
  - [ ] `img.nearyou.id` (production CF Images delivery)
  - [x] `api-staging.nearyou.id` — CNAME → `ghs.googlehosted.com` (Cloud Run domain mapping, DNS-only / grey cloud in Cloudflare). TLS provisioning may take 15–60 min on initial setup.
  - [ ] `admin-staging.nearyou.id`
  - [ ] `img-staging.nearyou.id`

**Notes / credentials location**:
- Cloudflare account email: _________________
- Cloudflare API token (jika dipakai): _________________

---

## 2. Developer Programs (Lead Time Lama, Daftar ASAP)

### 2.1 Apple Developer Program (wajib untuk iOS)

- [ ] Signup Apple Developer account - https://developer.apple.com/programs/enroll
- [ ] Pilih Individual enrollment (solo founder)
- [ ] Bayar $99/tahun
- [ ] Tunggu approval (biasanya 1-3 hari, bisa lebih kalau ada verifikasi tambahan)
- [ ] Setelah approved, enroll ke **Small Business Program** (https://developer.apple.com/app-store/small-business-program/) — gratis, wajib enroll biar fee turun dari 30% ke 15% dari hari pertama
- [ ] Buat App ID di Apple Developer Console untuk `id.nearyou.app` (atau naming kamu)
- [ ] Enable capabilities: Sign in with Apple, App Attest, Push Notifications
- [ ] Generate APNs `.p8` key (Keys section) - simpan aman, tidak bisa re-download
- [ ] Register S2S endpoint URL untuk Sign in with Apple notifications (isi nanti setelah API live)

**Notes**:
- Apple Dev account ID: _________________
- Team ID: _________________
- APNs `.p8` key location: _________________

### 2.2 Google Play Console

- [ ] Signup Google Play Console - https://play.google.com/console/signup
- [ ] Bayar $25 one-time fee (verify harga terkini, bisa beda)
- [ ] Complete identity verification (butuh KTP + selfie)
- [ ] Setelah approved, **verify pricing tier availability**: Rp9,900 / Rp29,000 / Rp249,000 untuk subscription — document tier terdekat kalau exact tidak ada, update `09-Versions.md`
- [ ] Request **Play Integrity quota increase** ke 100k/day via Play Console form (processing up to 1 minggu)

**Notes**:
- Play Console account: _________________
- Developer account ID: _________________

### 2.3 Google Cloud Platform

- [ ] Signup GCP - https://console.cloud.google.com/
- [ ] Create billing account (butuh kartu kredit, free credit $300 baru user)
- [ ] Create project `nearyou-prod` dan `nearyou-staging`
- [ ] Enable APIs di tiap project:
  - [ ] Cloud Run
  - [ ] Cloud Run Jobs
  - [ ] Cloud Scheduler
  - [ ] Secret Manager
  - [ ] Cloud Vision API (Safe Search)
  - [ ] Cloud Build (untuk CI/CD)
  - [ ] Play Integrity API
- [ ] Set **billing alert budget** Rp500.000/hari per project (proteksi)
- [ ] Download Play Integrity API public key (untuk verifikasi attestation)

**Notes**:
- GCP project ID prod: _________________
- GCP project ID staging: `nearyou-staging`
- Billing account ID: _________________

**Staging Cloud Run service** (2026-04-22):
- Service name: `nearyou-backend-staging`
- Region: `asia-southeast1` (Jakarta)
- Raw URL (fallback if custom domain breaks): `https://nearyou-backend-staging-gswrppbqaa-as.a.run.app`
- Custom domain: `https://api-staging.nearyou.id` (pending TLS cert on initial setup)
- Deploy workflow: `.github/workflows/deploy-staging.yml` (auto-triggers on push to `main`; referenced below as `deploy-staging.yml`)
- Secrets: loaded from Secret Manager as `staging-*` (see deploy workflow `--set-secrets`)
- Domain ownership verified via Search Console under `nearyouid.founder@gmail.com`

---

## 3. Core Infrastructure Accounts (Parallel, Free Tier Semua)

Bisa signup berurutan dalam 1-2 sore. Gunakan email dedicated untuk admin NearYouID, jangan campur email pribadi.

### 3.1 Supabase

- [x] Signup - https://supabase.com — done (staging project active)
- [ ] Create project `nearyou-prod` (region Singapore / AWS ap-southeast-1) — pending GCP prod project setup
- [x] Create project `nearyou-staging` (same region) — done; staging URL di Secret Manager `staging-supabase-url` + `staging-db-url`
- [~] Save connection string + JWT secret untuk kedua project — staging done: 6 slots (`staging-db-url`, `staging-db-user`, `staging-db-password`, `staging-supabase-jwt-secret`, `staging-supabase-url`, `staging-supabase-service-role-key`) semua v1 + wired (§ 4.2); prod equivalents pending
- [ ] Prod: stay on Free tier sampai Pre-Launch, lalu upgrade ke Pro ($25/bulan)
- [x] Staging: stay on Free tier selamanya (auto-pause setelah 7 hari idle = OK) — Free tier active

**Notes**:
- Prod project URL: _________________
- Staging project URL: in Secret Manager `staging-db-url` (project `nearyou-staging`). Flyway history V1..V9 verified 2026-04-22, all `success=true`.

### 3.2 Upstash (Redis)

- [x] Signup - https://upstash.com — done (staging DB active)
- [ ] Create Redis database `nearyou-cache-prod` (region Singapore) — pending GCP prod project setup
- [x] Create Redis database `nearyou-cache-staging` — done
- [~] Save REST URL + REST token untuk masing-masing — staging done (`staging-redis-url` v1, wired as `REDIS_URL`; consumed by `:infra:redis` Lettuce client); prod pending
- [x] **TCP/RESP URL must use `rediss://` scheme (TLS), NOT `redis://`.** Upstash enables TLS on port 6379 by default, but the dashboard quick-connect `redis-cli --tls -u redis://...` misleads — the `--tls` flag does the work. Lettuce parses `redis://` as plain TCP → unencrypted socket → Upstash drops it mid-handshake; symptom: `RedisConnectionException: Connection closed prematurely` in Cloud Run logs + rate limit silently no-ops via fail-soft. Fix at the secret value, not code: store `rediss://default:<password>@<host>:6379` in `staging-redis-url` + `redis-url` (prod). Precedent: like-rate-limit task 9.7 smoke, 2026-04-25 (diagnostic trail in archive notes). **Staging fix applied** — deploys + rate-limit infra clean; apply the same scheme to prod when minting.

**Notes**:
- Prod Redis REST URL: _________________
- Staging Redis REST URL: _________________

### 3.3 Firebase

- [x] Signup / use existing Google account (`nearyouid.founder@gmail.com`) - https://console.firebase.google.com
- [ ] Create project `nearyou-prod` (link ke GCP project prod)
- [x] Create project `nearyou-staging` (link ke GCP project staging) — done 2026-04-26; project ID exactly `nearyou-staging` (linked to the existing GCP project, not a fresh Firebase-created one); inherits Blaze plan from GCP billing — no cost impact, FCM + Remote Config stay free-tier
- [x] Enable **FCM** (Cloud Messaging) — `fcm.googleapis.com` auto-enabled when Firebase joined the staging GCP project; verified via `gcloud services list`
- [x] Enable **Phone Authentication** — `identitytoolkit.googleapis.com` auto-enabled in staging; Phone sign-in method still needs an explicit Console UI toggle before first use (backend's FCM/Remote Config flow doesn't require it)
- [x] Enable **Remote Config** untuk feature flags — `firebaseremoteconfig.googleapis.com` + `firebaseremoteconfigrealtime.googleapis.com` auto-enabled in staging
- [ ] Download `GoogleService-Info.plist` (iOS) untuk kedua environment — **deferred** until mobile flavor config + Apple Dev account ready
- [ ] Download `google-services.json` (Android) untuk kedua environment — **deferred** until mobile flavor config decision (`applicationIdSuffix = ".staging"` or not)
- [x] Generate Firebase Admin SDK service account JSON untuk backend — staging done 2026-04-26 (prod pending) as `staging-firebase-admin-sa` v1; local copy deleted post-upload (Secret Manager = single source of truth); Cloud Run runtime SA (`27815942904-compute@developer.gserviceaccount.com`, referenced below as "Cloud Run runtime SA") granted `roles/secretmanager.secretAccessor`

**Initial Remote Config flags** (create dengan default values):

⚠️ **Server vs Client template gotcha** (not in original docs): Firebase Remote Config has **two independent templates per project** — `Client` (mobile/web SDKs) and `Server` (Admin SDK; server-side Remote Config GA since 2023) — which do NOT share parameters. Backend Ktor reads **Server**; mobile reads **Client**. Dual-consumer flags (`image_upload_enabled`, `premium_username_customization_enabled`) must be seeded in both templates and kept in sync manually.

Seeded in **Server template** (staging) — done 2026-04-26, Version 1 published:
- [x] `image_upload_enabled` = false (boolean)
- [x] `attestation_mode` = "off" (string) — staging override; prod will be `"enforce"`
- [x] `search_enabled` = true (boolean)
- [x] `perspective_api_enabled` = true (boolean)
- [x] `premium_username_customization_enabled` = true (boolean)
- [x] `moderation_profanity_list` = [] (JSON array — Firebase has no native string-array type, JSON is the canonical workaround; backend `JSON.parse` to `List<String>`)
- [x] `moderation_uu_ite_list` = [] (JSON array, same as above)
- [x] `moderation_match_threshold` = 3 (number)

End-to-end verified 2026-04-26: Secret Manager credential → OAuth token via SA private key → Server template fetched at `firebaseremoteconfig.googleapis.com/v1/projects/nearyou-staging/namespaces/firebase-server/serverRemoteConfig` → all 8 parameters returned correct defaults (same flow the Firebase Admin SDK uses internally).

**Notes**:
- Firebase prod project ID: _________________
- Firebase staging project ID: `nearyou-staging` (matches GCP project ID — single ID for all staging infra)
- Staging Admin SA email: `firebase-adminsdk-fbsvc@nearyou-staging.iam.gserviceaccount.com`
- Staging Admin SDK credential slot, wiring + consumers: § 4.2 (`staging-firebase-admin-sa`)
- Pending Client template seed: 2 dual-template flags (`image_upload_enabled`, `premium_username_customization_enabled`) need Client-template entries when mobile work starts

### 3.4 RevenueCat

> **STAGING PROVISIONED 2026-06-15** (deferral lifted — trigger fired: #291 `subscription-billing-webhook` merged + #309 `mobile-paywall-screen` in flight). Account + project + **Test Store** product catalog built entirely via the **v2 REST API** (`curl`, not the dashboard UI; the quick-start wizard was skipped — it pushed a Lifetime product we don't sell). Production setup still pending (needs real App Store / Play Store apps → Apple/Google dev accounts).

- [x] Signup - https://www.revenuecat.com — done 2026-06-15 via Google OAuth (`nearyouid.founder@gmail.com`)
- [x] Create project "NearYouID" — done; project id `9d323c42`
- [ ] Add Google Play app (butuh Play Console setup dulu) — deferred to prod
- [ ] Add App Store app (butuh Apple Dev + App Store Connect dulu) — deferred to prod
- [x] Setup sandbox environment — **Test Store** auto-provisioned (app id `app89fcef0707`, type `test_store`); no Apple/Google account needed for dev/staging purchase testing
- [x] Products + entitlement + offering (via v2 API):
  - entitlement `premium` (id `entlcce792ba27`) — 3 products attached
  - offering `default` (id `ofrngcfc30f15d2`, current) with packages `$rc_weekly` / `$rc_monthly` / `$rc_annual`
  - 3 subscription products: `nearyou_premium_weekly` (P1W, `prod3298016d4e`) / `_monthly` (P1M, `prod6d6a34bb40`) / `_yearly` (P1Y, `prod89905b4ab5`)
  - ⚠️ Test Store product **prices** NOT settable via v2 API (PATCH /products → 405; create has no price field) — dashboard-only or default. Non-blocking for test purchases; revisit when #309 SDK wiring is testable.
- [x] Webhook bearer secret + dashboard registration — **DONE 2026-06-15**. `staging-revenuecat-webhook-secret` v1 stored + Cloud Run SA granted + wired `REVENUECAT_WEBHOOK_SECRET` into `deploy-staging.yml` (PR #320, merged). RevenueCat dashboard webhook `nearyou-staging` registered → `/internal/revenuecat-webhook` (Bearer auth). **Verified end-to-end**: correct Bearer → 400 (auth passed, empty body rejected), wrong Bearer → 401. HMAC slot not minted (optional). Prod equivalents pending.
- [x] Note: products (weekly/monthly/yearly) configured 2026-06-15. Pricing comes from RevenueCat **Offerings at runtime**, not docs/01 (those remain "target, verify Pre-Phase 1").

**Notes**:
- RevenueCat account email: `nearyouid.founder@gmail.com`
- Project id: `9d323c42` · Test Store app id: `app89fcef0707`
- Test Store SDK (public) key `test_…`: publishable (mobile-side, not a server secret). **Stored in Secret Manager `staging-revenuecat-test-api-key` v1 (2026-06-15; no Cloud Run grant — mobile-side, not backend-consumed).** Wire into mobile `:infra:revenuecat` when #309 lands.
- Setup-time secret API key (v2) label `nearyou-config-setup` (Project configuration + Apps read/write): used once for API provisioning above. Delete/rotate after use; regenerate when prod config is needed.
- Webhook (#291): backend route `POST /internal/revenuecat-webhook` — **wired end-to-end on staging 2026-06-15** (secret slot v1 + Cloud Run SA + deploy PR #320 merged + dashboard webhook `nearyou-staging` registered + verified). HMAC (`X-RevenueCat-Signature`) intentionally not wired (optional; Bearer-only accepted). Prod pending.

### 3.5 Cloudflare (additional services beyond DNS)

- [x] Dari Cloudflare dashboard, enable **R2** (object storage) — done 2026-04-26; free tier active (10GB storage / 1M Class A ops / 10M Class B ops per month); subscription $0/mo unless free tier exceeded, cancellable anytime via Billing
- [~] Create buckets:
  - [ ] `nearyou-media-prod` — pending GCP prod project setup
  - [x] `nearyou-media-staging` — done 2026-04-26; region APAC (Asia Pacific), storage class Standard, Public Access Disabled (private — served via CF Images later, never directly)
  - [ ] `nearyou-backups` (production backup target) — pending Pre-Launch backup spec
- [ ] Enable **Cloudflare Images** — **deferred (Phase B)** until media spec starts: CF Images = $5/mo minimum regardless of usage, not worth burning while `image_upload_enabled = false`. At enable-time also: configure variants, register `img-staging.nearyou.id` in CF Images Custom Domains, add DNS CNAME (the DNS record alone is useless without CF Images backend registration).
  - [ ] Note account hash (buat URL structure)
  - [ ] Note delivery URL pattern (custom subdomain locked per Decision #32 in `08-Roadmap-Risk.md` — `img-staging.nearyou.id` / `img.nearyou.id`)
- [x] Generate R2 S3-compatible API token untuk backend access — done 2026-04-26; token `nearyou-staging-r2-rw`, Object Read & Write, **bucket-scoped** (only `nearyou-media-staging`, not account-wide — least privilege), TTL forever, no IP filter; credentials → Secret Manager (5 secrets, § 4.2). **E2E smoke PASSED 2026-04-26**: PUT, LIST, GET (sha256 content match), DELETE, HEAD-after-DELETE (404) via boto3 + S3v4 signing — same flow backend Ktor will use via AWS SDK.

**Notes**:
- CF account ID: `c0e93113188e87a99848a2c6cb3e55e9`
- R2 staging credentials: 5 secrets, all v1, all granted to Cloud Run runtime SA — slot names § 4.2
- R2 staging endpoint: `https://c0e93113188e87a99848a2c6cb3e55e9.r2.cloudflarestorage.com`
- CF Images account hash: _________________ (defer with Phase B enable)
- Pending wiring: 5 R2 staging secrets not yet in `deploy-staging.yml --set-secrets` — add when backend media module code lands (separate OpenSpec change, likely with Firebase Admin SDK wiring)

### 3.6 Sentry

- [x] Signup - https://sentry.io — done 2026-04-26 via Google OAuth (`nearyouid.founder@gmail.com`); plan Developer (Free, 5k errors/month); auto-enrolled 14-day Business trial — falls back to Developer at expiry if no payment method is added (do NOT add one; keeps it free)
- [x] Create organization "NearYouID" — done 2026-04-26; slug `nearyouid`, URL `https://nearyouid.sentry.io`; **Data Storage Location: 🇪🇺 European Union (Frankfurt)**, confirmed via DSN `region=de` — ⚠️ permanent, cannot be changed
- [x] Create projects: `nearyou-android`, `nearyou-ios`, `nearyou-backend` — done 2026-04-26; project IDs: backend `4511287333945424`, android `4511287347511376`, ios `4511287349411920`; all 3 on alert frequency "Alert me on high priority issues" (Sentry's algorithmic detection — non-noisy, default), email notifications on (`nearyouid.founder@gmail.com`)
- [x] Save DSN untuk masing-masing — done 2026-04-26; all 3 DSN in Secret Manager (§ 4.2), local files deleted post-upload
- [x] Note: separate staging pakai `environment=staging` tag (single project) — locked per `04-Architecture.md`: ONE Sentry project per platform, env distinguished at runtime via the SDK-init `environment` tag (`staging` vs `production`); same DSN serves both envs (mirror to `prod-sentry-*-dsn` secrets at prod setup)
- [ ] Siapkan `sentry-cli` auth token untuk CI/CD (upload ProGuard + dSYM) — **deferred** until the mobile release build pipeline starts (Phase 3 mobile work per `08-Roadmap-Risk.md`); backend Ktor needs no auth token (no symbolication artifacts to upload — JVM stack traces already readable); generating now = unused secret sitting, rotate-when-needed preferred

**Notes**:
- Sentry org slug: `nearyouid`; org ID: `4511287321165824`
- DSN secrets (`staging-sentry-{backend,android,ios}-dsn`): grants + details § 4.2
- Pending wiring: `staging-sentry-backend-dsn` not yet in `deploy-staging.yml --set-secrets` — add when backend `:infra:sentry` module wires SDK init (separate OpenSpec change)

### 3.7 Grafana Cloud (OTel backend)

OTel foundation shipped 2026-05-07 via PR #66 `observability-otel-foundation`: `:infra:otel` module + OpenTelemetry SDK + auto-instrumentation (Ktor server, JDK/CIO HTTP client, HikariCP, Lettuce) + OTLP/HTTP exporter to Grafana Cloud Tempo. Staging fully wired; production stack + slots pending prod environment buildout.

- [x] Signup - https://grafana.com/auth/sign-up/create-user — done (Free tier)
- [x] Pilih Free tier — done
- [~] Create stack `nearyou-staging` (staging) and `nearyou-prod` (production) — one Grafana Cloud project, two stacks; staging done, prod pending GCP prod project setup
- [~] Mint OTLP/HTTP token per stack with **Read+Write trace permissions only** (no metric/log scope at the `observability-otel-foundation` change); token format `<instance-id>:<api-key>` for the OTLP/HTTP `Authorization: Basic` header. Staging token minted via the OTLP setup wizard (over-grants — see ⚠️ rotation item below); prod pending.
- [~] Populate GCP Secret Manager slots (verbatim names — match `secretKey(env, ...)` lookups in `:infra:otel`):
    - [x] `staging-otel-grafana-otlp-endpoint` v1 — staging Tempo OTLP/HTTP endpoint (e.g., `https://tempo-prod-XX-us-central-0.grafana.net/tempo`); granted to Cloud Run runtime SA
    - [x] `staging-otel-grafana-otlp-token` v1 — staging HTTP Basic credential (base64 of `<instance_id>:<api_token>` from the wizard; `OtelBootstrap` prepends `Basic ` scheme); granted to Cloud Run runtime SA
    - [ ] `otel-grafana-otlp-endpoint` — production Tempo OTLP/HTTP endpoint (pending)
    - [ ] `otel-grafana-otlp-token` — production HTTP Basic credential (pending; mint from least-privilege Access Policy per ⚠️ below, NOT the wizard)
- [~] Confirm IAM: ONLY the staging + production Cloud Run service accounts hold `roles/secretmanager.secretAccessor` on these slots — no CI / dev access. Staging Cloud Run runtime SA granted on both staging slots; prod pending.
- [~] Wire env-var bindings: staging wired as `OTEL_GRAFANA_OTLP_ENDPOINT=staging-otel-grafana-otlp-endpoint:latest` + `OTEL_GRAFANA_OTLP_TOKEN=staging-otel-grafana-otlp-token:latest` (PR #66, 2026-05-07); production deploy workflow doesn't exist yet
- [ ] ⚠️ **Pre-Launch (before prod tag-deploy)**: rotate `otel-grafana-otlp-token` (optionally `staging-otel-grafana-otlp-token` at next maintenance window) from the wizard token to a custom Grafana Cloud Access Policy token — `nearyou-prod-traces-write-only`, realm = stack `nearyouid`, scope `traces:write` only. The wizard token over-grants `metrics:write` + `logs:write` + `profiles:write` + `stacks:read`; Secret Manager IAM is the primary defense, but a wizard token is unacceptable at production tag-deploy. Canonical: `08-Roadmap-Risk.md` § Pre-Launch (Week 18-20) checklist.

**Notes**:
- Grafana stack URLs (staging / prod): _________________ / _________________ (staging URL embedded in `staging-otel-grafana-otlp-endpoint`, fill in human-readable form when convenient; prod URL/endpoint pending)
- Cloud Run SA grants verified: [x] staging  [ ] production
- Pending wiring (prod): all 4 slots above + production deploy workflow

### 3.8 Amplitude

> **Multi-agent dialectic 2026-05-09**: 4-perspective pressure-test (pro-vendor / pro-build / compliance / pragmatist) + synthesizer recommended AMENDing Decision #31 to a default Postgres `product_events` substrate. Founder chose to proceed with Amplitude signup anyway — $0 cost, ~15 min setup ("siapin biar ready, kayak nyewa kotak surat — kosong sekarang, isi nanti pas ada surat"). Decision #31 status quo retained; substrate proposal preserved in conversation history if trigger conditions force a re-visit.

- [x] Signup - https://amplitude.com — done 2026-05-09 via Google OAuth (`nearyouid.founder@gmail.com`); plan Starter (Free, 10M events/month); org renamed `frosty-paper-787498` → `nearyouid` (matches Sentry/Resend pattern), org URL `app.amplitude.com/analytics/nearyouid/...`
- [x] Create project "NearYouID" — done 2026-05-09; single staging project, name kept as auto-generated `default` (internal label only, NOT used in API calls; rename non-trivial in current UI, cosmetic-only); URL scheme (mobile) `amp-3c1a065a74bf5472`; per dialectic outcome + CTO-multi-project recommendation, prod project (`nearyou-prod`) deferred until prod environment exists
- [x] Pilih Free tier (10M events/month) — done; Starter Plan = Free tier; no payment method on file (avoids accidental upgrade)
- [x] Save API key — done, stored as `staging-amplitude-api-key` (key format + upload handling: § 4.2)

**Notes**:
- Amplitude org slug: `nearyouid`; org ID: `428773`; staging project ID: `814353`
- Pending Pre-Launch test (`08-Roadmap-Risk.md` § Pre-Launch security review checklist): "Analytics consent suppression tested (Amplitude opt-out silent)" — gated on `:infra:amplitude` module landing first

### 3.9 Resend (transactional email)

- [x] Signup - https://resend.com — done 2026-04-27 via Google OAuth (`nearyouid.founder@gmail.com`); plan **Free Developer** ($0/mo, 100 emails/day, 3k/month, 1 verified domain); org slug `nearyouid.founder`; region **Tokyo (ap-northeast-1)** — closer to backend (Cloud Run asia-southeast1) + Indonesian users, ~70ms vs ~250ms Ireland; per-region decision (NOT matching Sentry's EU — Sentry has no APAC Frankfurt-equivalent and error tracking is async background; Resend's sync API latency matters)
- [x] Verify sending domain — **`send.nearyou.id`** (subdomain, NOT root `nearyou.id` — isolates email reputation from the main domain); verified 2026-04-27 12:35 AM. Per `04-Architecture.md` strategy: shared Resend account + same verified domain for staging + prod, distinguished by app-level `environment=staging` tag (NOT separate verified domains).
  - [x] Tambah SPF record — `send.send.nearyou.id` TXT = `v=spf1 include:amazonses.com ~all`; added in Cloudflare DNS via Manual setup (NOT auto-configure — DNS write authority stays within Cloudflare, not granted to third-party Resend OAuth); verified via `dig +short TXT send.send.nearyou.id`
  - [x] Tambah DKIM records — `resend._domainkey.send.nearyou.id` TXT = `p=MIGfMA0...wIDAQAB` (long DKIM public key); verified via `dig +short TXT resend._domainkey.send.nearyou.id`
  - [x] Tambah DMARC record — `_dmarc.nearyou.id` TXT = `v=DMARC1; p=none;`; org-wide policy (NOT subdomain-only) per Resend's recommended pattern; **`p=none` = monitoring only**, no enforcement — safe for now, tighten to `p=quarantine`/`p=reject` pre-launch after deliverability tracked. Also added MX bounce record `send.send.nearyou.id` MX 10 → `feedback-smtp.ap-northeast-1.amazonses.com` (Resend's sub-subdomain bounce architecture, NOT a typo).
- [x] Generate API key — done 2026-04-27; name `nearyou-staging`, permission **Full access** (free tier doesn't support scoped/per-domain keys), domain scope All Domains (effectively = `send.nearyou.id` since only one verified); stored in Secret Manager (§ 4.2)
- [x] Test send 1 email untuk verify — **PASSED** 2026-04-27 12:41 AM: HTTP POST `https://api.resend.com/emails`, `Authorization: Bearer ...`, `User-Agent: nearyou-id-setup/1.0`. ⚠️ Default Python `urllib` UA (`Python-urllib/3.9`) is blocked by Cloudflare WAF (error 1010 — bot signature); custom User-Agent required. Arrived in **Inbox (not Spam)** — domain reputation strong out of the gate; Gmail Smart Reply suggestions appeared (trusted-source sign).

**Notes**:
- Resend API key slot + wiring status: § 4.2 (`staging-resend-api-key`)
- Resend org slug: `nearyouid.founder`
- Sender domain: `send.nearyou.id` (verified, Tokyo region); standard from address: `noreply@send.nearyou.id` (system emails, no reply expected)
- DKIM key fingerprint (first 8): `MIGfMA0G...` (full key in DNS at `resend._domainkey.send.nearyou.id`)
- ⚠️ **Staging recipient guard required**: backend code MUST override recipient to a test inbox (hardcoded `nearyouid.founder@gmail.com` OR Resend test address `delivered@resend.dev`) when `environment=staging`, so staging emails can't accidentally reach real users via stale data; implement in the `:infra:resend` module wrapper

### 3.10 GitHub

- [x] Confirm repo `nearyou-id` sudah siap — public, FSL-1.1-ALv2 licensed (per `CLAUDE.md` § Public repository posture); CI workflows live; PR-driven flow enforced
- [~] Setup GitHub Actions secrets untuk CI/CD:
  - [x] `GCP_SA_KEY` (service account untuk deploy ke Cloud Run) — wired; staging deploy workflow runs success consistently (5/5 most-recent)
  - [x] `GCP_PROJECT_ID` + `GCP_REGION` — wired (referenced in `deploy-staging.yml` for Artifact Registry + Cloud Run target); not in original checklist, tracked for completeness
  - [ ] `SENTRY_AUTH_TOKEN` (upload ProGuard/dSYM) — **deferred** until mobile release build pipeline (Phase 3); full reasoning § 3.6 + § 4.2
  - [ ] `SUPABASE_DB_URL_STAGING` (untuk Flyway migrate staging) — **NOT NEEDED**: Flyway runs on Cloud Run startup via `RUN_FLYWAY_ON_STARTUP=true` using the `staging-db-*` Secret Manager slots, no separate GH Actions secret required; strike if/when prod confirms same pattern
  - [ ] `SUPABASE_DB_URL_PROD` — same as above; deferred + likely obsolete
  - [ ] Tokens lain sesuai kebutuhan
- [x] Setup branch protection untuk `main` — **GitHub Ruleset `main-protection` (id 16164557) active 2026-05-09**; targets `~DEFAULT_BRANCH` (= `main`), no bypass list; verified via `gh api repos/aditrioka/nearyou-id/rulesets/16164557`. 6 rules: `creation`, `deletion`, `non_fast_forward`, `required_linear_history`, `pull_request` (squash-only, 0 required approvals — solo dev), `required_status_checks` (contexts `lint`, `test`, `migrate-supabase-parity`; `strict_required_status_checks_policy=false` = up-to-date branch not required). Local pre-push hook (per `CLAUDE.md`) stays as defense-in-depth; the ruleset is the server-side authoritative gate surviving compromised-local / future-collaborator scenarios. Caveat from config time: docs-only PRs bypass checks cleanly (`paths-ignore` workflow-skip), but job-level `if:` skips on mixed PRs may report heavy jobs `skipped` and block merge — workaround: 1 no-op code commit or "Re-run all jobs"; accepted for solo velocity, revisit if friction.

### 3.11 AdMob (bukan blocker sekarang, approval 2-4 minggu)

- [ ] Signup AdMob - https://admob.google.com (bisa ditunda sampai mendekati Phase 4)
- [ ] Link ke Google Play app + App Store app (butuh apps terdaftar dulu)
- [ ] Setup UMP (User Messaging Platform) untuk UU PDP consent
- [ ] Note: ads mulai Month 3+ setelah approval

---

## 4. Secrets yang Perlu Di-generate

Semua masuk GCP Secret Manager dengan namespace `prod-*` dan `staging-*`. **Singkatan di § 4**: "granted" = `roles/secretmanager.secretAccessor` untuk Cloud Run runtime SA; "wired as `X`" = terpasang di `deploy-staging.yml --set-secrets`; "not yet wired (consumer `:infra:*`)" = ditambahkan saat module consumer-nya land via OpenSpec change terpisah.

### 4.1 Crypto Keys

- [ ] **Ktor JWT RS256 keypair** (prod) - 4096-bit RSA
  - Command: `openssl genpkey -algorithm RSA -out prod-ktor-rsa-private.pem -pkeyopt rsa_keygen_bits:4096`
  - Simpan private key di `prod-ktor-rsa-private-key`
  - Public key untuk JWKS endpoint
- [x] **Ktor JWT RS256 keypair** (staging) - same process, secret slot `staging-ktor-rsa-private-key` v1, wired as `KTOR_RSA_PRIVATE_KEY`
- [ ] **JITTER_SECRET** (prod) - 256-bit random
  - Command: `openssl rand -base64 32`
  - Slot: `prod-jitter-secret`
  - ⚠️ Long-lived by design, rotation = re-fuzz semua posts
- [x] **JITTER_SECRET** (staging) - slot `staging-jitter-secret` v1, wired as `JITTER_SECRET`
- [ ] **age keypair** untuk backup encryption
  - Install `age`: `brew install age` (macOS)
  - Generate: `age-keygen -o backup-key.txt`
  - Public key di-bake ke backup Docker image
  - Private key simpan di `prod-backup-age-private-key`
- [~] **CSAM archive AES-256 key** - 256-bit random
  - Slot: `prod-csam-archive-aes-key` (pending) dan `staging-csam-archive-aes-key` v1 (done 2026-05-09, project `nearyou-staging`: `openssl rand -base64 32` piped directly to `gcloud secrets create --data-file=-`, plaintext never touched disk; replication=automatic; labels env=staging,purpose=csam-archive-encryption; length verified 44 bytes = base64(32); granted. NOT yet wired — wire when the CSAM archive writer module lands via OpenSpec change; no CSAM trigger path live on staging yet, secret idle until its consumer ships.)
- [~] **Invite code secret** - 256-bit random untuk HMAC derivation
  - Slot: `prod-invite-code-secret` dan `staging-invite-code-secret`. Staging done (`staging-invite-code-secret` v1, wired as `INVITE_CODE_SECRET`); prod pending.
- [ ] **Admin session cookie signing key** (reserved untuk future signed-cookie mode)
  - Slot: `prod-admin-session-cookie-signing-key` (belum perlu di-generate, reserve slot dulu)

### 4.2 Third-Party Secrets (simpan hasil dari section 3 di atas)

**Supabase + DB connection secrets** (all 7 wired since the 2026-04-22 staging buildout; consumed by HikariCP main pool + Flyway + `:infra:supabase`; prod equivalents pending for all 7):
- [~] `staging-db-url` v1 — Postgres direct connection, wired as `DB_URL`; granted
- [~] `staging-db-user` v1 — wired as `DB_USER`
- [~] `staging-db-password` v1 — wired as `DB_PASSWORD`
- [~] `staging-supabase-url` v1 — wired as `SUPABASE_URL` (centralized via Secret Manager though the URL itself isn't cryptographically secret — rationale in deploy workflow comment)
- [~] `staging-supabase-jwt-secret` v1 — wired as `SUPABASE_JWT_SECRET`
- [~] `staging-supabase-service-role-key` v1 — wired as `SUPABASE_SERVICE_ROLE_KEY`
- [~] `staging-redis-url` v1 — Upstash Redis (`rediss://` scheme), wired as `REDIS_URL`; consumed by Lettuce in `:infra:redis`

- [~] `staging-revenuecat-webhook-secret` **v1 DONE 2026-06-15** (Secret Manager REST; Cloud Run SA granted `secretAccessor`; wired `REVENUECAT_WEBHOOK_SECRET` via PR #320 merged). `prod-revenuecat-webhook-secret` pending. Bearer shared-secret; **slot name per `RevenueCatWebhookRoutes.kt` (`secretKey(env, "revenuecat-webhook-secret")`)** — earlier `-bearer` label was wrong; handler logs `bearer_secret_unset` + 401s until set.
- [ ] `prod-revenuecat-webhook-hmac-secret` dan `staging-revenuecat-webhook-hmac-secret` (opsional; HMAC-SHA256 `X-RevenueCat-Signature`, slot `revenuecat-webhook-hmac-secret`)
- [x] `staging-revenuecat-test-api-key` v1 — Test Store SDK public key (`test_…`, mobile-side, publishable — not a server secret). Done 2026-06-15 via Secret Manager REST API; **no Cloud Run grant** (consumed by the mobile build, not backend). Prod uses real-store SDK keys instead.
- [~] `prod-firebase-admin-sa` dan `staging-firebase-admin-sa` (JSON file) — staging done 2026-04-26 (v1, granted) + wired as `FIREBASE_ADMIN_SA=staging-firebase-admin-sa:latest` 2026-04-29 (PR #60 `fcm-push-dispatch`); consumers `:infra:fcm` + `:infra:remote-config` (latter 2026-05-07, PR #70 `content-moderation-keyword-lists`); prod pending
- [~] `prod-openai-api-key` dan `staging-openai-api-key` — OpenAI Platform API key untuk OpenAI Moderation API (`omni-moderation-latest`, Layer 3 toxicity classifier; consumer `:infra:openai-moderation` `OpenAiModerationClient`). **Vendor pivot 2026-05-11**: spec originally targeted Google Perspective API, which announced sunset (end-of-2026, signups closed Feb 2026) mid-implementation → swapped to OpenAI Moderation. Staging done 2026-05-11: project-scoped key (sk-proj-…) minted on platform.openai.com under the `NearYouID` org; clipboard-pipe upload (`pbpaste | gcloud secrets create`), plaintext never touched disk; slot v1, replication=automatic, labels env=staging,purpose=layer3-moderation; granted; wired as `OPENAI_API_KEY=staging-openai-api-key:latest`. The Moderation endpoint is FREE (no per-call charge), but any platform.openai.com key requires a payment method + $5 minimum prepaid deposit (one-time, idle if only Moderation is used). Prod pending.
- [ ] `prod-apns-p8-key` dan `staging-apns-p8-key` (file content)
- [~] `prod-resend-api-key` dan `staging-resend-api-key` — staging done 2026-04-27: v1, 36 bytes, granted; Free Developer plan key, full-access scope, name `nearyou-staging`; smoke PASSED (Inbox, not Spam — § 3.9); not yet wired (consumer `:infra:resend`). Prod pending: same Resend account + key may be reused (env-prefix mirror) OR mint a separate `nearyou-prod` key for blast-radius isolation — decide pas prod env setup.
- [~] `prod-r2-access-key` + `prod-r2-secret` dan staging equivalents — staging done 2026-04-26 with 5 secrets (more granular than original spec): `staging-r2-access-key-id` v1 (32 bytes), `staging-r2-secret-access-key` v1 (64 bytes), `staging-r2-bucket-name` v1 (`nearyou-media-staging`), `staging-r2-endpoint-url` v1, `staging-r2-account-id` v1 — all granted, local credential files deleted post-upload; prod equivalents pending GCP prod project setup
- [ ] ⚠️ **Pre-Launch (before prod tag-deploy)**: provision `prod-export-peer-hash-secret` (consumer `:infra` — `account-data-export` `PeerIdHasher.fromSecret` via `secretKey(env, "export-peer-hash-secret")`). **Fails OPEN, not closed**: unlike R2/Resend (NoOp when un-provisioned), a blank/absent secret degrades peer-id hashing to the PUBLIC `DEV_DEFAULT_SECRET`, making exported peer hashes correlatable across users. Acceptable pre-prod (synthetic data); HARD blocker before the data-export worker handles real user data in prod. (`staging-export-peer-hash-secret` optional — staging is synthetic-only.)
- [ ] `prod-cf-images-api-token` dan `staging-cf-images-api-token`
- [ ] `prod-sentry-auth-token` (shared untuk upload, tergantung strategi) — **deferred** until mobile release build pipeline (Phase 3 mobile work). Auth token ≠ DSN: token = CI symbolication artifact upload (ProGuard mappings, dSYM); DSN = runtime event ingestion; backend needs no auth token — full reasoning § 3.6.

**Sentry DSN secrets** (added 2026-04-26 — separate from auth token; runtime SDK ingestion; prod equivalents pending):
- [~] `staging-sentry-backend-dsn` v1 — granted
- [~] `staging-sentry-android-dsn` v1 — no Cloud Run grant (mobile DSN consumed by CI build pipeline, not Cloud Run runtime; least privilege)
- [~] `staging-sentry-ios-dsn` v1 — no Cloud Run grant (same reason)

**OTel Grafana Cloud secrets** (added 2026-05-07 via PR #66 `observability-otel-foundation`; consumed by `:infra:otel` `OtelBootstrap.start(...)`; both granted + wired — details § 3.7; prod equivalents pending):
- [~] `staging-otel-grafana-otlp-endpoint` v1 — wired as `OTEL_GRAFANA_OTLP_ENDPOINT`
- [~] `staging-otel-grafana-otlp-token` v1 — wired as `OTEL_GRAFANA_OTLP_TOKEN`; wizard-minted (over-grants metrics/logs/profiles/stacks scope) — rotation to a least-privilege Access Policy token tracked in § 3.7 ⚠️ before prod tag-deploy

- [~] `prod-amplitude-api-key` (pending) dan `staging-amplitude-api-key` v1 — done 2026-05-09: 32 bytes alphanumeric (Amplitude standard format); granted; not yet wired (consumer `:infra:amplitude` SDK init, per Phase 1 line 89 schedule); clipboard-pipe upload (`pbpaste | tr -d '\n\r ' | gcloud secrets create`) — plaintext never touched disk or shell history
- [ ] `prod-admin-app-db-connection-string` (DB role `admin_app`, separate dari main API)
- [ ] `prod-main-app-db-connection-string` (DB role `main_app`)
- [ ] `prod-flyway-db-connection-string` (DB role `flyway_migrator`, DDL rights)
- [ ] `prod-cf-worker-csam-secret` (kalau pilih Cloudflare Worker auto-forward path untuk CSAM)

**Admin login key slots** (added by `admin-login-argon2-totp` / Admin #3; both 256-bit, resolved via `secretKey(env, name)`, provisioned together by `dev/scripts/admin-totp-key-bootstrap.sh`):
- [ ] `staging-admin-totp-secret-aes-key` — AES-256-GCM key for `admin_users.totp_secret_encrypted`; wired as `ADMIN_TOTP_SECRET_AES_KEY` (lazy `aesKeyProvider`, resolved at login-verify time — missing slot fails the first login but does NOT block boot). ⚠ Rotation orphans every existing `totp_secret_encrypted` ciphertext; the script does NOT rotate on re-run.
- [ ] `staging-admin-csrf-hmac-key` — HMAC-SHA256 key for the Signed Double-Submit CSRF token derivation (distinct slot from the AES key — key separation); wired as `ADMIN_CSRF_HMAC_KEY` (lazy, resolved at login/render time). ⚠ Rotation invalidates every in-flight session's CSRF token (forces re-login); the script does NOT rotate on re-run.
- [ ] Both slots grant `secretAccessor` to Cloud Run runtime SA. **Operational — not part of the change PR.** Run the bootstrap script (default = both staging slots).
- [ ] **Staging-test admin row** — provision via `dev/scripts/admin-bootstrap/admin-bootstrap.sh` for the pre-archive smoke (`dev/scripts/smoke-admin-login-argon2-totp.sh`); store the staging-test email + password + base32 TOTP secret in the operator's password manager — NOT in this repo / PR
- [ ] `admin-totp-secret-aes-key` + `admin-csrf-hmac-key` (production, unprefixed) — **deferred to the production-bootstrap milestone**. Run once per slot: `PROJECT_OVERRIDE=nearyou-production SLOT_OVERRIDE=admin-totp-secret-aes-key RUNTIME_SA_OVERRIDE=<prod-sa> dev/scripts/admin-totp-key-bootstrap.sh` (then again with `SLOT_OVERRIDE=admin-csrf-hmac-key`).

---

## 5. Decisions yang Perlu Diputusin Pre-Phase 1

Per `08-Roadmap-Risk.md`, ini harus locked sebelum build mulai. Canonical decisions log lives in `08-Roadmap-Risk.md` § "Open Decisions" (pattern follows existing entries #4 BPS/OSM, #13 IAP, etc.) — `09-Versions.md` is scoped to library version pins only.

- [x] **IAP vs Cloud Armor + VPN** untuk admin panel — **Resolved 2026-04-26: IAP**; allowlist `nearyouid.founder@gmail.com`. Rationale: `08-Roadmap-Risk.md` § Open Decisions #13.
- [x] **OTel backend vendor** — **Resolved 2026-05-07: Grafana Cloud Tempo via OTLP/HTTP** (PR #66 `observability-otel-foundation` shipped `:infra:otel` + exporter; staging emitting traces). Vendor swap (Honeycomb / Cloud Trace) remains a within-`:infra:otel` change per the module's encapsulation contract. Decision: `08-Roadmap-Risk.md` § Open Decisions #12.
- [x] **BPS vs OpenStreetMap** untuk polygon kabupaten/kota — **Resolved + shipped: OSM** (`admin_level=4` provinces + `admin_level=5` kabupaten/kota via Overpass API), live in staging DB via V12 552-row seed (`global-timeline-with-region-polygons` change); attribution surfaced in V12 migration header. Rationale: `08-Roadmap-Risk.md` § Open Decisions #4.
- [x] **CF Images URL pattern** — **Resolved 2026-04-26: custom subdomain `img.nearyou.id` (prod) + `img-staging.nearyou.id` (staging)**; standard `imagedelivery.net` retained as emergency fallback. Rationale: `08-Roadmap-Risk.md` § Open Decisions #32.
- [x] **CSAM trigger path** — **Resolved 2026-04-26: MVP = admin-triggered manual via Admin Panel; Phase 2+ = Cloudflare Worker auto-forward**. Both paths converge to `/internal/csam-webhook` + same archive row (dedup via `csam_detection_archive.source` column); migrate triggers documented. Rationale: `08-Roadmap-Risk.md` § Open Decisions #33.
- [ ] **Verify pricing tiers** di stores (Rp9,900 / Rp29,000 / Rp249,000)
- [ ] **Verify Supabase pricing**: disk add-on per GB, Realtime per concurrent + per message
- [ ] **Verify Google Cloud Vision Safe Search pricing** per image
- [ ] **Verify Google Play Developer fee** (currently $25, mungkin berubah)

---

## 6. Datasets (Content Work, Parallel dengan Coding)

### 6.1 Indonesian Word-Pair Database

> **SCOPE RE-AUDIT 2026-05-09 (E20 audit)**: 600×600 + 100 modifier (= 360k base combinations) likely overengineered for MVP. Cheaper: 50 curated adjectives × 50 curated nouns × 4-digit numeric suffix = 25M unique combinations, collision-resistant generation, curated lists = 1-day work instead of 3-4 days. Decide scope BEFORE the full generation+filter+KBBI cross-check. **Trigger to revisit scope**: when the `anonymous_username` generation OpenSpec change is actually proposed — let real usage (collision-rate target, regeneration triggers, premium customization spec) drive scope, not pre-planning. Original target preserved below for that trigger.

Target: 600 kata sifat × 600 kata benda + 100 modifier = 360k+ base kombinasi (36M dengan fallback).

- [ ] Generate kandidat kata sifat (AI-assisted, topik: alam, cuaca, emosi, warna)
- [ ] Generate kandidat kata benda (AI-assisted, topik: alam, objek, flora/fauna)
- [ ] Manual filter: remove offensive, politically charged, slang tidak netral
- [ ] Cross-check dengan KBBI untuk spelling + validitas
- [ ] Export ke format Flyway migration SQL (`V<n>__seed_word_pairs.sql`)
- [ ] Budget: 3-4 hari
- [ ] Location: `/backend/src/main/resources/db/seed/` atau setara

### 6.2 Reserved Usernames Seed List

- [ ] Draft list: `admin`, `support`, `moderator`, `system`, `nearyou`, `staff`, `official`, `akun_dihapus`, `deleted_user`
- [ ] Tambah semua 1-char dan 2-char strings (alfabet a-z, 0-9)
- [ ] Export ke Flyway insert migration
- [ ] Mark `source = 'seed_system'` (immutable)

### 6.3 BPS / OSM Kabupaten-Kota Polygons

Resolved + shipped via PR [#31](https://github.com/aditrioka/nearyou-id/pull/31) (`global-timeline-with-region-polygons`, 2026-04-25). Full dataset live in staging DB; applied to every staging deploy via Flyway-on-startup.

- [x] Pilih source (BPS preferred, OSM fallback) — **OSM** chosen via Open Decision #4 (`08-Roadmap-Risk.md`)
- [x] Download dataset — Overpass API `area:3600304751` (Indonesia), fetched 2026-04-25 via `dev/scripts/import-admin-regions/fetch-overpass.sh` + `generate-seed.py`
- [x] Process: ~500 kabupaten/kota GeoJSON — **552 rows** (38 provinces at `admin_level=4` + 514 kabupaten/kota at `admin_level=5`)
- [x] DKI Jakarta special: 5 kotamadya + Kepulauan Seribu di level kabupaten — covered natively by OSM `admin_level=5` (Jakarta Pusat/Utara/Selatan/Timur/Barat + Kepulauan Seribu); no hand-curation needed
- [x] Buffer coastal kabupaten +22km untuk maritime extension (12 nautical miles) — 48 of 514 coastal kabupaten carry `ST_Buffer(geom::geometry, 0.198°)` baked into geom at import time; "coastal" = centroid within 50 km of national outline
- [~] Spot-check 10 kabupaten kompleks (Kepulauan Riau, Halmahera, dll) — visual spot-check during PR #31 review; not formally documented per-kabupaten
- [x] Import ke Postgres dengan schema `admin_regions` + GIST index — V11 schema (PR #29) + V12 seed (PR #31); GIST index on `geom` for spatial queries
- [~] Document attribution di Privacy Policy (kalau OSM) — attribution surfaced in `V12__admin_regions_seed.sql` migration header + `docs/01-Business.md` legal checklist; Privacy Policy doesn't exist yet (§ 7 all `[ ]`) — attribution copy migrates there when it's drafted

### 6.4 UU ITE / Profanity Wordlist

- [ ] Draft UU ITE keyword list (AI + manual review, 1 hari)
- [ ] Draft general profanity blocklist (Indonesia + slang)
- [ ] Draft username-specific profanity filter
- [x] Upload ke Firebase Remote Config Server template: `moderation_uu_ite_list`, `moderation_profanity_list` — done 2026-04-26 (staging Server template Version 1; see § 3.3); empty JSON arrays — operational seed lists pending dataset work above
- [x] Also commit fallback files: `/backend/ktor/src/main/resources/moderation/uu_ite.default.txt`, `profanity.default.txt` — shipped via PR #70 `content-moderation-keyword-lists` (2026-05-07) with placeholder sentinels; operational seed lists land via Firebase Remote Config (Layer 2 of the 4-step fallback ladder), repo files are fail-soft last-resort
- [ ] Plan quarterly review cadence (atau on-demand saat regulasi update)

---

## 7. Legal / Compliance Prep (Parallel, bisa mulai dari sekarang)

- [ ] Draft Privacy Policy (Bahasa Indonesia) sesuai UU PDP
  - Data yang dikumpulkan
  - Purpose limitation
  - Retention periods (lihat `06-Security-Privacy.md`)
  - User rights (export, delete)
  - Kontak DPO
- [ ] Draft Terms of Service (Bahasa Indonesia)
  - Clause "Fitur Premium dapat berubah atau ditambahkan seiring waktu"
  - Age restriction 18+
  - Content policy
- [ ] Appoint self sebagai DPO (Data Protection Officer)
- [ ] Draft RoPA (Record of Processing Activities)
- [ ] Siapkan template breach notification untuk PDP Agency (window 3x24 jam)
- [ ] Bookmark Kominfo SOP untuk CSAM reporting

---

## 8. Post-Setup Verification (Before Phase 1 Starts)

- [x] Semua secret di-inject ke Cloud Run environment bisa di-read via Secret Manager API — verified by 5+ consecutive successful staging deploys (`deploy-staging.yml` runs Flyway + boots Ktor; failure here would surface as 503 on `/health/ready`); production verification deferred until prod env exists
- [ ] Dari local dev bisa `supabase start` (Supabase CLI)
- [ ] DNS `nearyou.id` + subdomains resolve benar (dig / nslookup test)
- [ ] Resend test email berhasil delivered
- [ ] Firebase Remote Config flags bisa di-read dari test client
- [ ] RevenueCat sandbox test purchase berhasil (kalau sudah ada test app)
- [ ] GCP billing alert sudah aktif
- [ ] Backup encryption: `age -d` berhasil decrypt test dump

---

## Progress Summary

| Section | Total | Done | Status |
|---------|-------|------|--------|
| 1. Domain & DNS | 14 | 0 | `[ ]` |
| 2. Developer Programs | 15 | 0 | `[ ]` |
| 3. Infrastructure Accounts | 45+ | 43 | `[~]` (Firebase staging + R2 staging + Sentry org/projects/DSNs + Resend domain/key + Grafana Cloud staging stack/token + Supabase staging + Upstash staging + GitHub Actions `GCP_SA_KEY`/`GCP_PROJECT_ID`/`GCP_REGION` + branch-protection ruleset `main-protection` (active 2026-05-09 per § 3.10) done; CF Images deferred Phase B; sentry-cli auth token deferred to mobile build phase; Cloudflare DNS active for `api-staging`) |
| 4. Secrets | 29 | 24 partial | `[~]` (`staging-firebase-admin-sa` v1 + wired, `staging-r2-{access-key-id,secret-access-key,bucket-name,endpoint-url,account-id}` v1, `staging-sentry-{backend,android,ios}-dsn` v1, `staging-resend-api-key` v1, `staging-csam-archive-aes-key` v1 (added 2026-05-09, not yet wired), `staging-amplitude-api-key` v1 (added 2026-05-09, not yet wired), `staging-otel-grafana-otlp-{endpoint,token}` v1 + wired, `staging-{ktor-rsa-private-key,jitter-secret,invite-code-secret}` v1 + wired, `staging-{db-url,db-user,db-password,supabase-url,supabase-jwt-secret,supabase-service-role-key,redis-url}` v1 + wired) |
| 5. Decisions | 9 | 5 | `[~]` (IAP, BPS/OSM, CF Images URL, CSAM trigger — all resolved 2026-04-26; OTel vendor — resolved 2026-05-07 as Grafana Cloud Tempo via PR #66; 4 pricing/quota verifications still open) |
| 6. Datasets | 4 work items | 1.5 | `[~]` (§6.3 polygons shipped via PR #31 — 552 OSM rows + maritime buffer + GIST index live in staging DB; §6.4 RC + fallback files scaffolded via PR #70 with placeholder sentinels; §6.1 word pairs + §6.2 reserved usernames still open) |
| 7. Legal | 6 | 0 | `[ ]` |
| 8. Verification | 8 | 1 | `[~]` (Secret Manager → Cloud Run injection verified via 5+ successful staging deploys; remaining 7 items pending) |

---

## Cross-References

- Spec authority: `08-Roadmap-Risk.md` Pre-Phase 1 section
- Version decisions log: `09-Versions.md`
- Architecture context: `04-Architecture.md` (Deployment Strategy section)
- Security requirements: `06-Security-Privacy.md`
- Operations detail: `07-Operations.md`
