# NearYouID - External Setup Checklist

Operational checklist untuk akun, infrastruktur, domain, credentials, dan datasets yang perlu disiapin **di luar kodingan**. Ini komplemen ke `08-Roadmap-Risk.md` Pre-Phase 1 section, tapi fokus ke eksekusi (ceklist yang bisa di-tick).

**How to use**: tick `[x]` saat selesai, tambah catatan inline (lokasi kredensial, tanggal, dll). Kredensial sensitif JANGAN ditulis di file ini. Simpan di GCP Secret Manager / 1Password / password manager pilihan, file ini hanya catat "disimpan di mana".

**Status legend**: `[ ]` belum, `[x]` selesai, `[~]` in progress, `[!]` blocked.

---

## 1. Domain & DNS (Start di sini, blocking untuk banyak hal)

Domain `nearyou.id` sudah terdaftar di Hostinger. Langkah berikut memindah DNS management ke Cloudflare (registrasi tetap di Hostinger).

- [ ] Signup Cloudflare account (free plan) - https://dash.cloudflare.com/sign-up
- [ ] Add site `nearyou.id` ke Cloudflare, pilih Free plan
- [ ] Copy 2 nameservers yang Cloudflare kasih
- [ ] Login Hostinger → domain `nearyou.id` → Nameservers → ganti ke Cloudflare NS
- [ ] Tunggu propagasi (biasanya 1-24 jam), verify di Cloudflare dashboard status "Active"
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
- [ ] Tunggu approval (1-3 hari biasanya, bisa lebih kalau verifikasi tambahan)
- [ ] Setelah approved, enroll ke **Small Business Program** (fee turun dari 30% ke 15%)
  - https://developer.apple.com/app-store/small-business-program/
  - Gratis, wajib enroll biar fee 15% dari hari pertama
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
- [ ] Setelah approved, **verify pricing tier availability**: Rp9,900 / Rp29,000 / Rp249,000 untuk subscription
  - Document tier terdekat kalau exact tidak ada, update `09-Versions.md`
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
- Deploy workflow: `.github/workflows/deploy-staging.yml` (auto-triggers on push to `main`)
- Secrets: loaded from Secret Manager as `staging-*` (see deploy workflow `--set-secrets`)
- Domain ownership verified via Search Console under `nearyouid.founder@gmail.com`

---

## 3. Core Infrastructure Accounts (Parallel, Free Tier Semua)

Bisa signup berurutan dalam 1-2 sore. Gunakan email dedicated untuk admin NearYouID, jangan campur email pribadi.

### 3.1 Supabase

- [x] Signup - https://supabase.com — done (staging project active)
- [ ] Create project `nearyou-prod` (pilih region Singapore / AWS ap-southeast-1) — pending GCP prod project setup
- [x] Create project `nearyou-staging` (same region) — done. Staging URL stored in GCP Secret Manager `staging-supabase-url` + `staging-db-url`.
- [~] Save connection string + JWT secret untuk kedua project — staging done (`staging-db-url` v1, `staging-db-user` v1, `staging-db-password` v1, `staging-supabase-jwt-secret` v1, `staging-supabase-url` v1, `staging-supabase-service-role-key` v1; all wired in `deploy-staging.yml --set-secrets`). Prod equivalents pending.
- [ ] Prod: stay on Free tier sampai Pre-Launch, lalu upgrade ke Pro ($25/bulan)
- [x] Staging: stay on Free tier selamanya (auto-pause setelah 7 hari idle = OK) — Free tier active

**Notes**:
- Prod project URL: _________________
- Staging project URL: stored in GCP Secret Manager as `staging-db-url` (project `nearyou-staging`). Flyway history V1..V9 verified 2026-04-22, all `success=true`.

### 3.2 Upstash (Redis)

- [x] Signup - https://upstash.com — done (staging DB active)
- [ ] Create Redis database `nearyou-cache-prod` (region Singapore) — pending GCP prod project setup
- [x] Create Redis database `nearyou-cache-staging` — done
- [~] Save REST URL + REST token untuk masing-masing — staging done (`staging-redis-url` v1, wired as `REDIS_URL` in `deploy-staging.yml --set-secrets`; consumed by `:infra:redis` Lettuce client). Prod equivalent pending.
- [x] **TCP/RESP URL must use `rediss://` scheme (TLS), NOT `redis://`.** Upstash enables TLS on port 6379 by default, but the dashboard's quick-connect string is shown as `redis-cli --tls -u redis://...` which is misleading — the `--tls` flag is what's actually doing the work. Lettuce (`:infra:redis`) parses `redis://` as plain TCP and opens an unencrypted socket; Upstash drops the connection mid-handshake. Symptom is `RedisConnectionException: Connection closed prematurely` in Cloud Run logs and the rate limit silently no-ops via the fail-soft path. Solve at the secret value, not in code: store `rediss://default:<password>@<host>:6379` in `staging-redis-url` and `redis-url` (prod). Precedent: like-rate-limit task 9.7 smoke (2026-04-25) failed exactly this way; archive notes capture the full diagnostic trail. **Staging fix applied** — `staging-redis-url` uses `rediss://` scheme; staging deploys + rate-limit infrastructure run cleanly. Apply same scheme to `redis-url` (prod) when minting.

**Notes**:
- Prod Redis REST URL: _________________
- Staging Redis REST URL: _________________

### 3.3 Firebase

- [x] Signup / use existing Google account (`nearyouid.founder@gmail.com`) - https://console.firebase.google.com
- [ ] Create project `nearyou-prod` (link ke GCP project prod)
- [x] Create project `nearyou-staging` (link ke GCP project staging) — done 2026-04-26. Project ID exactly `nearyou-staging` (linked to existing GCP project, not a fresh Firebase-created one). Inherits Blaze plan from GCP billing — no cost impact since FCM + Remote Config stay on free tier.
- [x] Enable **FCM** (Cloud Messaging) — `fcm.googleapis.com` auto-enabled when Firebase added to staging GCP project. Verified via `gcloud services list`.
- [x] Enable **Phone Authentication** — `identitytoolkit.googleapis.com` auto-enabled in staging. Sign-in method (Phone) still needs explicit toggle in Console UI before first use; backend doesn't require it for FCM/Remote Config flow.
- [x] Enable **Remote Config** untuk feature flags — `firebaseremoteconfig.googleapis.com` + `firebaseremoteconfigrealtime.googleapis.com` auto-enabled in staging.
- [ ] Download `GoogleService-Info.plist` (iOS) untuk kedua environment — **deferred** until mobile flavor config + Apple Dev account ready
- [ ] Download `google-services.json` (Android) untuk kedua environment — **deferred** until mobile flavor config decision (`applicationIdSuffix = ".staging"` or not)
- [x] Generate Firebase Admin SDK service account JSON untuk backend (staging done 2026-04-26; prod pending). Staging SA email: `firebase-adminsdk-fbsvc@nearyou-staging.iam.gserviceaccount.com`. Stored in GCP Secret Manager as `staging-firebase-admin-sa` (version 1). Local copy deleted after upload — Secret Manager is single source of truth. Cloud Run runtime SA (`27815942904-compute@developer.gserviceaccount.com`) granted `roles/secretmanager.secretAccessor` on this secret.

**Initial Remote Config flags** (create dengan default values):

⚠️ **Server vs Client template gotcha** (important — not in original docs): Firebase Remote Config now has **two independent templates per project**: `Client` (for mobile/web SDKs) and `Server` (for Admin SDK). Server-side Remote Config went GA in 2023 and uses a separate template — they do NOT share parameters. Backend Ktor reads from **Server** template. Mobile reads from **Client** template. For flags consumed by both sides (`image_upload_enabled`, `premium_username_customization_enabled`), parameters must be seeded in both templates and kept in sync manually.

Seeded in **Server template** (staging) — done 2026-04-26, Version 1 published:
- [x] `image_upload_enabled` = false (boolean) — also needs Client template entry when mobile work starts
- [x] `attestation_mode` = "off" (string) — staging override; prod will be `"enforce"`
- [x] `search_enabled` = true (boolean)
- [x] `perspective_api_enabled` = true (boolean)
- [x] `premium_username_customization_enabled` = true (boolean) — also needs Client template entry when mobile work starts
- [x] `moderation_profanity_list` = [] (JSON array — Firebase has no native string-array type, JSON is the canonical workaround; backend `JSON.parse` to `List<String>`)
- [x] `moderation_uu_ite_list` = [] (JSON array, same as above)
- [x] `moderation_match_threshold` = 3 (number)

End-to-end verified 2026-04-26: pulled credential from Secret Manager → minted OAuth token via SA private key → fetched Server template via `firebaseremoteconfig.googleapis.com/v1/projects/nearyou-staging/namespaces/firebase-server/serverRemoteConfig` → all 8 parameters returned with correct defaults. Same flow Firebase Admin SDK uses internally.

**Notes**:
- Firebase prod project ID: _________________
- Firebase staging project ID: `nearyou-staging` (matches GCP project ID — single ID for all staging infra)
- Staging Admin SA email: `firebase-adminsdk-fbsvc@nearyou-staging.iam.gserviceaccount.com`
- Staging Admin SDK credential: GCP Secret Manager `staging-firebase-admin-sa` (project `nearyou-staging`)
- Wired: `FIREBASE_ADMIN_SA=staging-firebase-admin-sa:latest` in `.github/workflows/deploy-staging.yml --set-secrets` (added 2026-04-29 via PR #60 `fcm-push-dispatch`; `:infra:remote-config` consumer landed 2026-05-07 via PR #70 `content-moderation-keyword-lists`)
- Pending Client template seed: 2 dual-template flags (`image_upload_enabled`, `premium_username_customization_enabled`) need Client-template entries when mobile work starts

### 3.4 RevenueCat

> **DEFERRED 2026-05-09 (E20 audit)**: signup deferred until Phase 4 IAP webhook backend implementation actively starts. No IAP code in flight, no subscription products to validate, no webhook to test. Signing up now = idle account hygiene burden + premature optimization. **Trigger to revisit**: first OpenSpec change for IAP/subscription module proposed. Items below preserved as future work.

- [ ] Signup - https://www.revenuecat.com
- [ ] Create project "NearYouID"
- [ ] Add Google Play app (butuh Play Console setup dulu)
- [ ] Add App Store app (butuh Apple Dev + App Store Connect dulu)
- [ ] Setup sandbox environment (gratis, isolated dari production)
- [ ] Generate webhook bearer secret (for production) + optional HMAC signing key
- [ ] Note: products (weekly/monthly/yearly) di-configure nanti di Phase 4, setelah pricing verified

**Notes**:
- RevenueCat account email: _________________
- Public API key (Android): _________________
- Public API key (iOS): _________________
- Webhook bearer secret location: _________________

### 3.5 Cloudflare (additional services beyond DNS)

- [x] Dari Cloudflare dashboard, enable **R2** (object storage) — done 2026-04-26. Free tier active (10GB storage / 1M Class A ops / 10M Class B ops per month). Subscription $0/mo unless free tier exceeded. Cancellable anytime via Billing.
- [~] Create buckets:
  - [ ] `nearyou-media-prod` — pending GCP prod project setup
  - [x] `nearyou-media-staging` — done 2026-04-26. Region: APAC (Asia Pacific). Storage class: Standard. Public Access: Disabled (private — served via CF Images later, never directly).
  - [ ] `nearyou-backups` (production backup target) — pending Pre-Launch backup spec
- [ ] Enable **Cloudflare Images** — **deferred (Phase B)** until media spec starts. CF Images = $5/mo minimum regardless of usage; not worth burning while `image_upload_enabled = false`. When enabling, also do: configure variants, register custom domain `img-staging.nearyou.id` in CF Images Custom Domains, add DNS CNAME (the DNS record alone is useless without CF Images backend registration).
  - [ ] Note account hash (buat URL structure)
  - [ ] Note delivery URL pattern (custom subdomain locked per Decision #32 in `08-Roadmap-Risk.md` — `img-staging.nearyou.id` / `img.nearyou.id`)
- [x] Generate R2 S3-compatible API token untuk backend access — done 2026-04-26. Token name `nearyou-staging-r2-rw`. Permissions: Object Read & Write. **Bucket-scoped** (only `nearyou-media-staging`, not account-wide — least privilege). TTL: forever. No IP filter. Credentials uploaded to GCP Secret Manager (5 secrets — see § 4.2 below). **End-to-end smoke test PASSED 2026-04-26**: PUT, LIST, GET (sha256 content match), DELETE, HEAD-after-DELETE (404) all verified via boto3 + S3v4 signing — same flow backend Ktor will use via AWS SDK.

**Notes**:
- CF account ID: `c0e93113188e87a99848a2c6cb3e55e9`
- R2 staging credentials: GCP Secret Manager (`staging-r2-access-key-id`, `staging-r2-secret-access-key`, `staging-r2-bucket-name`, `staging-r2-endpoint-url`, `staging-r2-account-id` — all v1, all granted to Cloud Run runtime SA `27815942904-compute@developer.gserviceaccount.com`)
- R2 staging endpoint: `https://c0e93113188e87a99848a2c6cb3e55e9.r2.cloudflarestorage.com`
- CF Images account hash: _________________ (defer with Phase B enable)
- Pending wiring: 5 R2 staging secrets not yet in `.github/workflows/deploy-staging.yml --set-secrets` — will be added when backend media module code lands (separate OpenSpec change, likely with Firebase Admin SDK wiring)

### 3.6 Sentry

- [x] Signup - https://sentry.io — done 2026-04-26 via Google OAuth (`nearyouid.founder@gmail.com`). Plan: Developer (Free, 5k errors/month). Sentry auto-enrolled into 14-day Business trial — will auto-fall-back to Developer plan after expire as long as no payment method added (do NOT add payment to keep free).
- [x] Create organization "NearYouID" — done 2026-04-26. Slug: `nearyouid`. URL: `https://nearyouid.sentry.io`. **Data Storage Location: 🇪🇺 European Union (Frankfurt)** — confirmed via DSN `region=de`. ⚠️ Permanent, cannot be changed.
- [x] Create projects: `nearyou-android`, `nearyou-ios`, `nearyou-backend` — done 2026-04-26. Org ID: `4511287321165824`. Project IDs: backend `4511287333945424`, android `4511287347511376`, ios `4511287349411920`. All 3 projects have alert frequency "Alert me on high priority issues" (Sentry's algorithmic detection — non-noisy, default). Email notifications on (`nearyouid.founder@gmail.com`).
- [x] Save DSN untuk masing-masing — done 2026-04-26. All 3 DSN uploaded to GCP Secret Manager (see § 4.2). Local files deleted post-upload.
- [x] Note: separate staging pakai `environment=staging` tag (single project) — strategy locked per `04-Architecture.md:245`. ONE Sentry project per platform, env distinguished at runtime via SDK init `environment=staging` vs `environment=production` tag. Same DSN used by both envs (mirror to `prod-sentry-*-dsn` secrets when prod env is set up).
- [ ] Siapkan `sentry-cli` auth token untuk CI/CD (upload ProGuard + dSYM) — **deferred** until mobile release build pipeline starts (Phase 3 mobile work per `08-Roadmap-Risk.md`). Backend Ktor doesn't need auth token (no symbolication artifacts to upload — JVM stack traces are already readable). Generating now = unused secret sitting; rotate-when-needed pattern preferred.

**Notes**:
- Sentry org slug: `nearyouid` (URL: `https://nearyouid.sentry.io`)
- Sentry org ID: `4511287321165824`
- DSN backend: GCP Secret Manager `staging-sentry-backend-dsn` v1 (granted to Cloud Run runtime SA)
- DSN android: GCP Secret Manager `staging-sentry-android-dsn` v1 (no Cloud Run SA grant — mobile DSN consumed by CI build pipeline, not Cloud Run runtime; least privilege)
- DSN ios: GCP Secret Manager `staging-sentry-ios-dsn` v1 (no Cloud Run SA grant — same reason as android)
- Pending wiring: `staging-sentry-backend-dsn` not yet in `.github/workflows/deploy-staging.yml --set-secrets` — will be added when backend `:infra:sentry` module wires SDK init (separate OpenSpec change)

### 3.7 Grafana Cloud (OTel backend)

OTel foundation shipped 2026-05-07 via PR #66 `observability-otel-foundation` — `:infra:otel` module + OpenTelemetry SDK + auto-instrumentation (Ktor server, JDK/CIO HTTP client, HikariCP, Lettuce) + OTLP/HTTP exporter to Grafana Cloud Tempo. Staging fully wired; production stack + slots pending prod environment buildout.

- [x] Signup - https://grafana.com/auth/sign-up/create-user — done (Free tier)
- [x] Pilih Free tier — done
- [~] Create stack `nearyou-staging` (staging) and `nearyou-prod` (production) — one Grafana Cloud project, two stacks. Staging done; prod pending GCP prod project setup.
- [~] Mint OTLP/HTTP token for each stack with **Read+Write trace permissions only** (no metric/log scope at the `observability-otel-foundation` change). Token format: `<instance-id>:<api-key>` for the OTLP/HTTP `Authorization: Basic` header. Staging token minted via OTLP setup wizard (over-grants `metrics:write` + `logs:write` + `profiles:write` + `stacks:read` — see ⚠️ rotation item below); prod pending.
- [~] Populate GCP Secret Manager slots (verbatim names — match `secretKey(env, ...)` lookups in `:infra:otel`):
    - [x] `staging-otel-grafana-otlp-endpoint` v1 — staging Tempo OTLP/HTTP endpoint (e.g., `https://tempo-prod-XX-us-central-0.grafana.net/tempo`). Cloud Run runtime SA granted `secretAccessor`.
    - [x] `staging-otel-grafana-otlp-token` v1 — staging HTTP Basic auth credential (base64 of `<instance_id>:<api_token>` from the Grafana OTLP wizard; `OtelBootstrap` prepends `Basic ` scheme). Cloud Run runtime SA granted `secretAccessor`.
    - [ ] `otel-grafana-otlp-endpoint` — production Tempo OTLP/HTTP endpoint (pending)
    - [ ] `otel-grafana-otlp-token` — production HTTP Basic auth credential (pending; mint from least-privilege Access Policy per ⚠️ below, NOT from the wizard)
- [~] Confirm IAM: ONLY the staging + production Cloud Run service accounts have `roles/secretmanager.secretAccessor` on these slots — no CI / dev access. Staging Cloud Run runtime SA (`27815942904-compute@developer.gserviceaccount.com`) granted on both staging slots; prod pending.
- [~] Wire env-var bindings: staging wired in `.github/workflows/deploy-staging.yml --set-secrets` as `OTEL_GRAFANA_OTLP_ENDPOINT=staging-otel-grafana-otlp-endpoint:latest` and `OTEL_GRAFANA_OTLP_TOKEN=staging-otel-grafana-otlp-token:latest` (added in PR #66, 2026-05-07). Production deploy workflow doesn't exist yet.
- [ ] ⚠️ **Pre-Launch (before prod tag-deploy)**: rotate `otel-grafana-otlp-token` (and optionally `staging-otel-grafana-otlp-token` at next maintenance window) from the OTLP-wizard token to a custom Grafana Cloud Access Policy token (`nearyou-prod-traces-write-only`, realm = stack `nearyouid`, scope = `traces:write` only). The wizard token over-grants `metrics:write` + `logs:write` + `profiles:write` + `stacks:read`. GCP Secret Manager IAM is the primary defense, but the wizard token is unacceptable at production tag-deploy. Canonical: `08-Roadmap-Risk.md` § Pre-Launch (Week 18-20) checklist.

**Notes**:
- Grafana stack URLs (staging / prod): _________________ / _________________ (staging URL embedded in `staging-otel-grafana-otlp-endpoint`; fill in human-readable form here when convenient)
- Staging OTLP endpoint: GCP Secret Manager `staging-otel-grafana-otlp-endpoint` v1 (project `nearyou-staging`)
- Staging OTLP token: GCP Secret Manager `staging-otel-grafana-otlp-token` v1 (wizard-minted; rotation to least-privilege Access Policy token tracked above)
- Prod stack URL / endpoint: _________________ (pending)
- Cloud Run SA grants verified: [x] staging  [ ] production
- Pending wiring (prod): all 4 slots above + production deploy workflow

### 3.8 Amplitude

> **Multi-agent dialectic 2026-05-09**: 4-perspective pressure-test (pro-vendor / pro-build / compliance / pragmatist) + synthesizer recommended AMEND Decision #31 to default Postgres `product_events` substrate. Founder reviewed and chose to proceed with Amplitude signup since cost is $0 + setup is ~15 min ("siapin biar ready, kayak nyewa kotak surat — kosong sekarang, isi nanti pas ada surat"). Status quo retained on Decision #31; substrate proposal preserved in conversation history if trigger conditions later force re-visit. Item below ticked accordingly.

- [x] Signup - https://amplitude.com — done 2026-05-09 via Google OAuth (`nearyouid.founder@gmail.com`). Plan: Starter (Free, 10M events/month). Org renamed `frosty-paper-787498` → `nearyouid` (matches Sentry/Resend pattern). Org URL: `app.amplitude.com/analytics/nearyouid/...`. Org ID: 428773.
- [x] Create project "NearYouID" — done 2026-05-09. Single project staging: name kept as auto-generated `default` (Amplitude project name is internal label only, NOT used in API calls — rename non-trivial in current UI, cosmetic-only). Project ID: 814353. URL scheme (mobile): `amp-3c1a065a74bf5472`. Per multi-agent dialectic outcome + CTO-multi-project recommendation: prod project (`nearyou-prod`) deferred until prod environment exists.
- [x] Pilih Free tier (10M events/month) — done. Starter Plan = Free tier. No payment method on file (avoids accidental upgrade).
- [x] Save API key — done. Stored in GCP Secret Manager (see § 4.2). 32 bytes alphanumeric (Amplitude standard format). Never touched disk or shell history (clipboard piped directly to `gcloud secrets create`).

**Notes**:
- Amplitude API key location: GCP Secret Manager `staging-amplitude-api-key` v1 (project `nearyou-staging`, granted to Cloud Run runtime SA `27815942904-compute@developer.gserviceaccount.com`)
- Amplitude org slug: `nearyouid`
- Amplitude org ID: `428773`
- Staging project ID: `814353`
- Pending wiring: `staging-amplitude-api-key` not yet in `.github/workflows/deploy-staging.yml --set-secrets` — will be added when backend `:infra:amplitude` module wires SDK init (separate OpenSpec change, per Phase 1 line 89 schedule)
- Pending Pre-Launch test (`08-Roadmap-Risk.md:339`): "Analytics consent suppression tested (Amplitude opt-out silent)" — gated on `:infra:amplitude` module landing first

### 3.9 Resend (transactional email)

- [x] Signup - https://resend.com — done 2026-04-27 via Google OAuth (`nearyouid.founder@gmail.com`). Plan: **Free Developer** ($0/mo, 100 emails/day, 3k/month, 1 verified domain). Org slug: `nearyouid.founder`. Region: **Tokyo (ap-northeast-1)** — closer to backend (Cloud Run asia-southeast1) + Indonesian users; ~70ms vs ~250ms Ireland. Per-region decision (NOT consistent with Sentry EU because Sentry has no APAC frankfurt-equivalent and error tracking async background; Resend has sync API call latency that matters).
- [x] Verify sending domain — **`send.nearyou.id`** (subdomain, NOT root `nearyou.id`). Verified 2026-04-27 12:35 AM. Subdomain isolates email reputation from main domain. Per `04-Architecture.md:242` strategy: shared Resend account + same verified domain for staging + prod, distinguished by app-level `environment=staging` tag (NOT separate verified domains).
  - [x] Tambah SPF record — `send.send.nearyou.id` TXT = `v=spf1 include:amazonses.com ~all`. Added in Cloudflare DNS via Manual setup (NOT auto-configure — chose to keep DNS write authority within Cloudflare, not granted to third-party Resend OAuth). Verified via `dig +short TXT send.send.nearyou.id`.
  - [x] Tambah DKIM records — `resend._domainkey.send.nearyou.id` TXT = `p=MIGfMA0...wIDAQAB` (long DKIM public key). Verified via `dig +short TXT resend._domainkey.send.nearyou.id`.
  - [x] Tambah DMARC record — `_dmarc.nearyou.id` TXT = `v=DMARC1; p=none;`. Org-wide policy (NOT subdomain-only) per Resend's recommended pattern. **`p=none` = monitoring only**, no enforcement — safe for now. Tighten to `p=quarantine` or `p=reject` pre-launch after deliverability tracked. Also added MX bounce record: `send.send.nearyou.id` MX 10 → `feedback-smtp.ap-northeast-1.amazonses.com` (Resend's sub-subdomain bounce architecture, NOT a typo).
- [x] Generate API key — done 2026-04-27. Name: `nearyou-staging`. Permission: **Full access** (free tier doesn't support scoped/per-domain keys). Domain scope: All Domains (effectively = `send.nearyou.id` since only one verified). Stored in GCP Secret Manager (see § 4.2).
- [x] Test send 1 email untuk verify — **PASSED** 2026-04-27 12:41 AM. Smoke test: HTTP POST to `https://api.resend.com/emails` with `Authorization: Bearer ...` and `User-Agent: nearyou-id-setup/1.0`. ⚠️ **Note**: default Python `urllib` User-Agent (`Python-urllib/3.9`) gets blocked by Cloudflare WAF (error 1010 — bot signature flagged). Custom User-Agent required. Email arrived in **Inbox (not Spam)** — domain reputation already strong out of gate; Gmail Smart Reply suggestions appeared (sign of trusted source).

**Notes**:
- Resend API key location: GCP Secret Manager `staging-resend-api-key` v1 (project `nearyou-staging`, granted to Cloud Run runtime SA `27815942904-compute@developer.gserviceaccount.com`)
- Resend org slug: `nearyouid.founder`
- Sender domain: `send.nearyou.id` (verified, Tokyo region)
- Standard from address: `noreply@send.nearyou.id` (system emails, no reply expected)
- DKIM key fingerprint (first 8): `MIGfMA0G...` (full key in DNS at `resend._domainkey.send.nearyou.id`)
- ⚠️ **Staging recipient guard required**: backend code MUST override recipient to test inbox (e.g., hardcoded `nearyouid.founder@gmail.com` OR `delivered@resend.dev` Resend test address) when `environment=staging` to prevent staging emails accidentally reaching real users via stale data. Implement in `:infra:resend` module wrapper.
- Pending wiring: `staging-resend-api-key` not yet in `.github/workflows/deploy-staging.yml --set-secrets` — will be added when backend `:infra:resend` module wires up (separate OpenSpec change)

### 3.10 GitHub

- [x] Confirm repo `nearyou-id` sudah siap — public, FSL-1.1-ALv2 licensed (per `CLAUDE.md` § Public repository posture); CI workflows live; PR-driven flow enforced
- [~] Setup GitHub Actions secrets untuk CI/CD:
  - [x] `GCP_SA_KEY` (service account untuk deploy ke Cloud Run) — wired; staging deploy workflow runs success consistently (5/5 most-recent)
  - [x] `GCP_PROJECT_ID` + `GCP_REGION` — wired (referenced in `deploy-staging.yml` for Artifact Registry + Cloud Run target). Not in original checklist; tracked here for completeness.
  - [ ] `SENTRY_AUTH_TOKEN` (upload ProGuard/dSYM) — **deferred** until mobile release build pipeline (Phase 3); see § 3.6 + § 4.2 for full reasoning
  - [ ] `SUPABASE_DB_URL_STAGING` (untuk Flyway migrate staging) — **NOT NEEDED**: Flyway runs on Cloud Run startup via `RUN_FLYWAY_ON_STARTUP=true` using the `staging-db-*` Secret Manager slots; no separate GH Actions secret required. Strike if/when prod confirms same pattern.
  - [ ] `SUPABASE_DB_URL_PROD` — same as above; deferred + likely obsolete
  - [ ] Tokens lain sesuai kebutuhan
- [x] Setup branch protection untuk `main` — **GitHub Ruleset `main-protection` (id 16164557) active 2026-05-09**. Targets `~DEFAULT_BRANCH` (= `main`). 6 rules: `creation` + `deletion` + `non_fast_forward` + `required_linear_history` + `pull_request` (squash-only merge, 0 required approvals — solo dev) + `required_status_checks` (3 contexts: `lint`, `test`, `migrate-supabase-parity`; `strict_required_status_checks_policy=false` so up-to-date branch not required). No bypass list. Verified via `gh api repos/aditrioka/nearyou-id/rulesets/16164557`. Local pre-push hook (per `CLAUDE.md`) remains as defense-in-depth; ruleset is now the server-side authoritative gate that survives compromised-local / future-collaborator scenarios. Caveat noted at config time: docs-only PR via `paths-ignore` workflow-skip → checks bypassed cleanly; but per-push job-level `if:` skip on mixed PRs may report `skipped` on heavy jobs and block merge — workaround is push 1 no-op code commit or click "Re-run all jobs". Live with it for solo velocity; revisit if friction.

### 3.11 AdMob (bukan blocker sekarang, approval 2-4 minggu)

- [ ] Signup AdMob - https://admob.google.com (bisa ditunda sampai mendekati Phase 4)
- [ ] Link ke Google Play app + App Store app (butuh apps terdaftar dulu)
- [ ] Setup UMP (User Messaging Platform) untuk UU PDP consent
- [ ] Note: ads mulai Month 3+ setelah approval

---

## 4. Secrets yang Perlu Di-generate

Semua masuk GCP Secret Manager dengan namespace `prod-*` dan `staging-*`.

### 4.1 Crypto Keys

- [ ] **Ktor JWT RS256 keypair** (prod) - 4096-bit RSA
  - Command: `openssl genpkey -algorithm RSA -out prod-ktor-rsa-private.pem -pkeyopt rsa_keygen_bits:4096`
  - Simpan private key di `prod-ktor-rsa-private-key`
  - Public key untuk JWKS endpoint
- [x] **Ktor JWT RS256 keypair** (staging) - same process, secret slot `staging-ktor-rsa-private-key` v1, wired as `KTOR_RSA_PRIVATE_KEY` in `deploy-staging.yml --set-secrets`
- [ ] **JITTER_SECRET** (prod) - 256-bit random
  - Command: `openssl rand -base64 32`
  - Slot: `prod-jitter-secret`
  - ⚠️ Long-lived by design, rotation = re-fuzz semua posts
- [x] **JITTER_SECRET** (staging) - slot `staging-jitter-secret` v1, wired as `JITTER_SECRET` in `deploy-staging.yml --set-secrets`
- [ ] **age keypair** untuk backup encryption
  - Install `age`: `brew install age` (macOS)
  - Generate: `age-keygen -o backup-key.txt`
  - Public key di-bake ke backup Docker image
  - Private key simpan di `prod-backup-age-private-key`
- [~] **CSAM archive AES-256 key** - 256-bit random
  - Slot: `prod-csam-archive-aes-key` (pending) dan `staging-csam-archive-aes-key` v1 (done 2026-05-09; project `nearyou-staging`; generated via `openssl rand -base64 32` piped directly to `gcloud secrets create --data-file=-` so plaintext never touched disk; replication=automatic; labels env=staging,purpose=csam-archive-encryption; verified length 44 bytes = base64(32). Cloud Run runtime SA `27815942904-compute@developer.gserviceaccount.com` granted `roles/secretmanager.secretAccessor`. NOT yet wired in `deploy-staging.yml --set-secrets` — wire pas CSAM archive writer module landed via OpenSpec change. No CSAM trigger path live yet on staging, so secret idle until consumer ships.)
- [~] **Invite code secret** - 256-bit random untuk HMAC derivation
  - Slot: `prod-invite-code-secret` dan `staging-invite-code-secret`. Staging done (`staging-invite-code-secret` v1, wired as `INVITE_CODE_SECRET` in `deploy-staging.yml`); prod pending.
- [ ] **Admin session cookie signing key** (reserved untuk future signed-cookie mode)
  - Slot: `prod-admin-session-cookie-signing-key` (belum perlu di-generate, reserve slot dulu)

### 4.2 Third-Party Secrets (simpan hasil dari section 3 di atas)

**Supabase + DB connection secrets** (all wired in `deploy-staging.yml --set-secrets` since 2026-04-22 staging buildout; consumed by HikariCP main pool + Flyway + `:infra:supabase`):
- [~] `staging-db-url` v1 — Postgres direct connection. Wired as `DB_URL`. Cloud Run runtime SA granted `secretAccessor`. Prod equivalent pending.
- [~] `staging-db-user` v1 — wired as `DB_USER`. Prod equivalent pending.
- [~] `staging-db-password` v1 — wired as `DB_PASSWORD`. Prod equivalent pending.
- [~] `staging-supabase-url` v1 — wired as `SUPABASE_URL` (centralized via Secret Manager though URL itself isn't cryptographically secret — see deploy workflow comment for rationale). Prod equivalent pending.
- [~] `staging-supabase-jwt-secret` v1 — wired as `SUPABASE_JWT_SECRET`. Prod equivalent pending.
- [~] `staging-supabase-service-role-key` v1 — wired as `SUPABASE_SERVICE_ROLE_KEY`. Prod equivalent pending.
- [~] `staging-redis-url` v1 — Upstash Redis (`rediss://` scheme). Wired as `REDIS_URL`; consumed by Lettuce in `:infra:redis`. Prod equivalent pending.

- [ ] `prod-revenuecat-webhook-bearer` dan `staging-revenuecat-webhook-bearer`
- [ ] `prod-revenuecat-webhook-hmac` dan `staging-revenuecat-webhook-hmac` (opsional)
- [~] `prod-firebase-admin-sa` dan `staging-firebase-admin-sa` (JSON file) — staging done 2026-04-26 (`staging-firebase-admin-sa` v1, Cloud Run runtime SA granted secretAccessor) AND wired into `deploy-staging.yml` as `FIREBASE_ADMIN_SA=staging-firebase-admin-sa:latest` 2026-04-29 (PR #60 `fcm-push-dispatch`). Consumed by `:infra:fcm` + `:infra:remote-config`. Prod pending.
- [~] `prod-openai-api-key` dan `staging-openai-api-key` — OpenAI Platform API key for the OpenAI Moderation API (`omni-moderation-latest`, Layer 3 toxicity classifier; consumed by `:infra:openai-moderation` `OpenAiModerationClient`). **Vendor pivot 2026-05-11**: original spec targeted Google Perspective API; Perspective announced sunset (end-of-2026, signups closed Feb 2026) mid-implementation so swap → OpenAI Moderation. Staging done 2026-05-11: project-scoped API key (sk-proj-…), generated via platform.openai.com under `NearYouID` org, uploaded via clipboard-pipe pattern (`pbpaste | gcloud secrets create`) so plaintext never touched disk. Slot `staging-openai-api-key` v1, replication=automatic, labels env=staging,purpose=layer3-moderation. Cloud Run runtime SA `27815942904-compute@developer.gserviceaccount.com` granted `roles/secretmanager.secretAccessor`. Wired in `deploy-staging.yml --set-secrets` as `OPENAI_API_KEY=staging-openai-api-key:latest`. OpenAI Moderation endpoint itself is FREE (no per-call charge); generating any API key on platform.openai.com requires a payment method + $5 minimum prepaid deposit (one-time, idle if only Moderation is used). Prod pending.
- [ ] `prod-apns-p8-key` dan `staging-apns-p8-key` (file content)
- [~] `prod-resend-api-key` dan `staging-resend-api-key` — staging done 2026-04-27 (`staging-resend-api-key` v1, 36 bytes, Cloud Run runtime SA granted secretAccessor). Free Developer plan key, full-access scope, name `nearyou-staging`. End-to-end smoke test PASSED (Inbox delivery, not Spam). Prod equivalent pending; same Resend account + same key may be reused (env-prefix mirror) OR generate separate `nearyou-prod` key for blast-radius isolation — decide pas prod env setup.
- [~] `prod-r2-access-key` + `prod-r2-secret` dan staging equivalents — staging done 2026-04-26 with 5 secrets (more granular than original spec): `staging-r2-access-key-id` v1 (32 bytes), `staging-r2-secret-access-key` v1 (64 bytes), `staging-r2-bucket-name` v1 (`nearyou-media-staging`), `staging-r2-endpoint-url` v1, `staging-r2-account-id` v1. All granted secretAccessor to Cloud Run runtime SA. Local credential files deleted post-upload. Prod equivalents pending GCP prod project setup.
- [ ] `prod-cf-images-api-token` dan `staging-cf-images-api-token`
- [ ] `prod-sentry-auth-token` (shared untuk upload, tergantung strategi) — **deferred** until mobile release build pipeline (Phase 3 mobile work). Auth token ≠ DSN: token is for CI symbolication artifact upload (ProGuard mappings, dSYM); DSN is for runtime event ingestion. Backend doesn't need auth token. See § 3.6 for full reasoning.

**Sentry DSN secrets** (added 2026-04-26 — separate from auth token; runtime SDK ingestion):
- [~] `staging-sentry-backend-dsn` v1 — granted Cloud Run runtime SA (`27815942904-compute@developer.gserviceaccount.com`) `secretAccessor`. Prod equivalent pending.
- [~] `staging-sentry-android-dsn` v1 — no Cloud Run grant (mobile DSN consumed by CI build pipeline, not runtime). Prod equivalent pending.
- [~] `staging-sentry-ios-dsn` v1 — no Cloud Run grant (same reason). Prod equivalent pending.

**OTel Grafana Cloud secrets** (added 2026-05-07 via PR #66 `observability-otel-foundation`; consumed by `:infra:otel` `OtelBootstrap.start(...)`):
- [~] `staging-otel-grafana-otlp-endpoint` v1 — granted Cloud Run runtime SA `secretAccessor`. Wired in `deploy-staging.yml` as `OTEL_GRAFANA_OTLP_ENDPOINT`. Prod equivalent pending.
- [~] `staging-otel-grafana-otlp-token` v1 — granted Cloud Run runtime SA `secretAccessor`. Wired in `deploy-staging.yml` as `OTEL_GRAFANA_OTLP_TOKEN`. Wizard-minted (over-grants metrics/logs/profiles/stacks scope); rotation to least-privilege Access Policy token tracked in § 3.7 ⚠️ before prod tag-deploy. Prod equivalent pending.

- [~] `prod-amplitude-api-key` (pending) dan `staging-amplitude-api-key` v1 (done 2026-05-09; 32 bytes Amplitude standard format; granted secretAccessor to Cloud Run runtime SA; not yet wired in `deploy-staging.yml --set-secrets` — will be added when `:infra:amplitude` module SDK init lands per Phase 1 schedule). Pipe-from-clipboard upload pattern (`pbpaste | tr -d '\n\r ' | gcloud secrets create`) — plaintext key never touched disk or shell history.
- [ ] `prod-admin-app-db-connection-string` (DB role `admin_app`, separate dari main API)
- [ ] `prod-main-app-db-connection-string` (DB role `main_app`)
- [ ] `prod-flyway-db-connection-string` (DB role `flyway_migrator`, DDL rights)
- [ ] `prod-cf-worker-csam-secret` (kalau pilih Cloudflare Worker auto-forward path untuk CSAM)

---

## 5. Decisions yang Perlu Diputusin Pre-Phase 1

Per `08-Roadmap-Risk.md`, ini harus locked sebelum build mulai. Canonical decisions log lives in `08-Roadmap-Risk.md` § "Open Decisions" (pattern follows existing entries #4 BPS/OSM, #13 IAP, etc.) — `09-Versions.md` is scoped to library version pins only.

- [x] **IAP vs Cloud Armor + VPN** untuk admin panel — **Resolved 2026-04-26: IAP**. Allowlist `nearyouid.founder@gmail.com`. Full rationale in `08-Roadmap-Risk.md` § Open Decisions #13.
- [x] **OTel backend vendor** — **Resolved 2026-05-07: Grafana Cloud Tempo via OTLP/HTTP** (PR #66 `observability-otel-foundation` shipped `:infra:otel` + exporter wired to Grafana Cloud; staging emitting traces). Vendor swap (Honeycomb / Cloud Trace) remains a within-`:infra:otel` change per the module's encapsulation contract. Decision tracked at `08-Roadmap-Risk.md` § Open Decisions #12.
- [x] **BPS vs OpenStreetMap** untuk polygon kabupaten/kota — **Resolved + shipped: OSM** (`admin_level=4` provinces + `admin_level=5` kabupaten/kota via Overpass API). Already live in staging DB via V12 552-row seed (`global-timeline-with-region-polygons` change). Attribution surfaced in V12 migration header. Full rationale in `08-Roadmap-Risk.md` § Open Decisions #4.
- [x] **CF Images URL pattern** — **Resolved 2026-04-26: custom subdomain `img.nearyou.id` (prod) + `img-staging.nearyou.id` (staging)**. Standard `imagedelivery.net` retained as emergency fallback. Full rationale in `08-Roadmap-Risk.md` § Open Decisions #32.
- [x] **CSAM trigger path** — **Resolved 2026-04-26: MVP = admin-triggered manual via Admin Panel; Phase 2+ = Cloudflare Worker auto-forward**. Both paths converge to `/internal/csam-webhook` + same archive row (dedup via `csam_detection_archive.source` column). Migrate triggers documented. Full rationale in `08-Roadmap-Risk.md` § Open Decisions #33.
- [ ] **Verify pricing tiers** di stores (Rp9,900 / Rp29,000 / Rp249,000)
- [ ] **Verify Supabase pricing**: disk add-on per GB, Realtime per concurrent + per message
- [ ] **Verify Google Cloud Vision Safe Search pricing** per image
- [ ] **Verify Google Play Developer fee** (currently $25, mungkin berubah)

---

## 6. Datasets (Content Work, Parallel dengan Coding)

### 6.1 Indonesian Word-Pair Database

> **SCOPE RE-AUDIT 2026-05-09 (E20 audit)**: 600×600 + 100 modifier (= 360k base combinations) likely overengineered for MVP. Cheaper alternative pattern: 50 curated adjective × 50 curated noun × 4-digit numeric suffix = 25M unique combinations with collision-resistant generation, and the curated word lists are 1-day work instead of 3-4 days. Decide scope BEFORE starting full generation+filter+KBBI cross-check work. **Trigger to revisit scope**: when `anonymous_username` generation OpenSpec change is actually proposed — let the actual usage pattern (collision rate target, regeneration triggers, premium customization spec) drive the scope decision rather than pre-planning. Original target preserved below for that future trigger.

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
- [x] Buffer coastal kabupaten +22km untuk maritime extension (12 nautical miles) — 48 of 514 coastal kabupaten carry `ST_Buffer(geom::geometry, 0.198°)` baked into geom at import time; "coastal" defined as centroid within 50 km of national outline
- [~] Spot-check 10 kabupaten kompleks (Kepulauan Riau, Halmahera, dll) — visual spot-check during PR #31 review; not formally documented per-kabupaten
- [x] Import ke Postgres dengan schema `admin_regions` + GIST index — V11 schema (PR #29) + V12 seed (PR #31); GIST index on `geom` for spatial queries
- [~] Document attribution di Privacy Policy (kalau OSM) — attribution surfaced in `V12__admin_regions_seed.sql` migration header + `docs/01-Business.md` legal checklist; Privacy Policy itself doesn't exist yet (§ 7 all `[ ]`), so attribution copy needs to migrate there when Privacy Policy is drafted

### 6.4 UU ITE / Profanity Wordlist

- [ ] Draft UU ITE keyword list (AI + manual review, 1 hari)
- [ ] Draft general profanity blocklist (Indonesia + slang)
- [ ] Draft username-specific profanity filter
- [x] Upload ke Firebase Remote Config Server template: `moderation_uu_ite_list`, `moderation_profanity_list` — done 2026-04-26 (staging Server template Version 1; see § 3.3). Empty JSON arrays — operational seed lists pending dataset work above.
- [x] Also commit fallback files: `/backend/ktor/src/main/resources/moderation/uu_ite.default.txt`, `profanity.default.txt` — shipped via PR #70 `content-moderation-keyword-lists` (2026-05-07) with placeholder sentinels; operational seed lists land via Firebase Remote Config (Layer 2 of the 4-step fallback ladder), repo files are fail-soft last-resort.
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

- [x] Semua secret di-inject ke Cloud Run environment bisa di-read via Secret Manager API — verified by 5+ consecutive successful staging deploys (workflow `deploy-staging.yml` runs Flyway + boots Ktor; failure here would surface as 503 on `/health/ready`). Production verification deferred until prod env exists.
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
