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

- [ ] Signup - https://supabase.com
- [ ] Create project `nearyou-prod` (pilih region Singapore / AWS ap-southeast-1)
- [ ] Create project `nearyou-staging` (same region)
- [ ] Save connection string + JWT secret untuk kedua project
- [ ] Prod: stay on Free tier sampai Pre-Launch, lalu upgrade ke Pro ($25/bulan)
- [ ] Staging: stay on Free tier selamanya (auto-pause setelah 7 hari idle = OK)

**Notes**:
- Prod project URL: _________________
- Staging project URL: stored in GCP Secret Manager as `staging-db-url` (project `nearyou-staging`). Flyway history V1..V9 verified 2026-04-22, all `success=true`.

### 3.2 Upstash (Redis)

- [ ] Signup - https://upstash.com
- [ ] Create Redis database `nearyou-cache-prod` (region Singapore)
- [ ] Create Redis database `nearyou-cache-staging`
- [ ] Save REST URL + REST token untuk masing-masing
- [ ] **TCP/RESP URL must use `rediss://` scheme (TLS), NOT `redis://`.** Upstash enables TLS on port 6379 by default, but the dashboard's quick-connect string is shown as `redis-cli --tls -u redis://...` which is misleading — the `--tls` flag is what's actually doing the work. Lettuce (`:infra:redis`) parses `redis://` as plain TCP and opens an unencrypted socket; Upstash drops the connection mid-handshake. Symptom is `RedisConnectionException: Connection closed prematurely` in Cloud Run logs and the rate limit silently no-ops via the fail-soft path. Solve at the secret value, not in code: store `rediss://default:<password>@<host>:6379` in `staging-redis-url` and `redis-url` (prod). Precedent: like-rate-limit task 9.7 smoke (2026-04-25) failed exactly this way; archive notes capture the full diagnostic trail.

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
- Pending wiring: `staging-firebase-admin-sa` not yet in `.github/workflows/deploy-staging.yml --set-secrets` — will be added when backend Firebase Admin SDK code lands (separate OpenSpec change introducing `:infra:remote-config` + `:infra:fcm` modules)
- Pending Client template seed: 2 dual-template flags (`image_upload_enabled`, `premium_username_customization_enabled`) need Client-template entries when mobile work starts

### 3.4 RevenueCat

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

- [ ] Signup - https://grafana.com/auth/sign-up/create-user
- [ ] Pilih Free tier
- [ ] Create stack "nearyou"
- [ ] Save OTel endpoint + API key (Tempo/Prometheus)

**Notes**:
- Grafana stack URL: _________________
- OTel endpoint: _________________

### 3.8 Amplitude

- [ ] Signup - https://amplitude.com
- [ ] Create project "NearYouID"
- [ ] Pilih Free tier (10M events/month)
- [ ] Save API key

**Notes**:
- Amplitude API key location: _________________

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

- [ ] Confirm repo `nearyou-id` sudah siap (sepertinya sudah berdasarkan folder `.github`)
- [ ] Setup GitHub Actions secrets untuk CI/CD (diisi setelah service credentials tersedia):
  - [ ] `GCP_SA_KEY` (service account untuk deploy ke Cloud Run)
  - [ ] `SENTRY_AUTH_TOKEN` (upload ProGuard/dSYM)
  - [ ] `SUPABASE_DB_URL_STAGING` (untuk Flyway migrate staging)
  - [ ] `SUPABASE_DB_URL_PROD`
  - [ ] Tokens lain sesuai kebutuhan
- [ ] Setup branch protection untuk `main` (require PR review + CI green)

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
- [ ] **Ktor JWT RS256 keypair** (staging) - same process, secret slot `staging-ktor-rsa-private-key`
- [ ] **JITTER_SECRET** (prod) - 256-bit random
  - Command: `openssl rand -base64 32`
  - Slot: `prod-jitter-secret`
  - ⚠️ Long-lived by design, rotation = re-fuzz semua posts
- [ ] **JITTER_SECRET** (staging) - slot `staging-jitter-secret`
- [ ] **age keypair** untuk backup encryption
  - Install `age`: `brew install age` (macOS)
  - Generate: `age-keygen -o backup-key.txt`
  - Public key di-bake ke backup Docker image
  - Private key simpan di `prod-backup-age-private-key`
- [ ] **CSAM archive AES-256 key** - 256-bit random
  - Slot: `prod-csam-archive-aes-key` dan `staging-csam-archive-aes-key`
- [ ] **Invite code secret** - 256-bit random untuk HMAC derivation
  - Slot: `prod-invite-code-secret` dan `staging-invite-code-secret`
- [ ] **Admin session cookie signing key** (reserved untuk future signed-cookie mode)
  - Slot: `prod-admin-session-cookie-signing-key` (belum perlu di-generate, reserve slot dulu)

### 4.2 Third-Party Secrets (simpan hasil dari section 3 di atas)

- [ ] `prod-supabase-jwt-secret` dan `staging-supabase-jwt-secret`
- [ ] `prod-revenuecat-webhook-bearer` dan `staging-revenuecat-webhook-bearer`
- [ ] `prod-revenuecat-webhook-hmac` dan `staging-revenuecat-webhook-hmac` (opsional)
- [~] `prod-firebase-admin-sa` dan `staging-firebase-admin-sa` (JSON file) — staging done 2026-04-26 (`staging-firebase-admin-sa` v1, Cloud Run runtime SA granted secretAccessor); prod pending
- [ ] `prod-apns-p8-key` dan `staging-apns-p8-key` (file content)
- [~] `prod-resend-api-key` dan `staging-resend-api-key` — staging done 2026-04-27 (`staging-resend-api-key` v1, 36 bytes, Cloud Run runtime SA granted secretAccessor). Free Developer plan key, full-access scope, name `nearyou-staging`. End-to-end smoke test PASSED (Inbox delivery, not Spam). Prod equivalent pending; same Resend account + same key may be reused (env-prefix mirror) OR generate separate `nearyou-prod` key for blast-radius isolation — decide pas prod env setup.
- [~] `prod-r2-access-key` + `prod-r2-secret` dan staging equivalents — staging done 2026-04-26 with 5 secrets (more granular than original spec): `staging-r2-access-key-id` v1 (32 bytes), `staging-r2-secret-access-key` v1 (64 bytes), `staging-r2-bucket-name` v1 (`nearyou-media-staging`), `staging-r2-endpoint-url` v1, `staging-r2-account-id` v1. All granted secretAccessor to Cloud Run runtime SA. Local credential files deleted post-upload. Prod equivalents pending GCP prod project setup.
- [ ] `prod-cf-images-api-token` dan `staging-cf-images-api-token`
- [ ] `prod-sentry-auth-token` (shared untuk upload, tergantung strategi) — **deferred** until mobile release build pipeline (Phase 3 mobile work). Auth token ≠ DSN: token is for CI symbolication artifact upload (ProGuard mappings, dSYM); DSN is for runtime event ingestion. Backend doesn't need auth token. See § 3.6 for full reasoning.

**Sentry DSN secrets** (added 2026-04-26 — separate from auth token; runtime SDK ingestion):
- [~] `staging-sentry-backend-dsn` v1 — granted Cloud Run runtime SA (`27815942904-compute@developer.gserviceaccount.com`) `secretAccessor`. Prod equivalent pending.
- [~] `staging-sentry-android-dsn` v1 — no Cloud Run grant (mobile DSN consumed by CI build pipeline, not runtime). Prod equivalent pending.
- [~] `staging-sentry-ios-dsn` v1 — no Cloud Run grant (same reason). Prod equivalent pending.
- [ ] `prod-amplitude-api-key` (dan staging)
- [ ] `prod-admin-app-db-connection-string` (DB role `admin_app`, separate dari main API)
- [ ] `prod-main-app-db-connection-string` (DB role `main_app`)
- [ ] `prod-flyway-db-connection-string` (DB role `flyway_migrator`, DDL rights)
- [ ] `prod-cf-worker-csam-secret` (kalau pilih Cloudflare Worker auto-forward path untuk CSAM)

---

## 5. Decisions yang Perlu Diputusin Pre-Phase 1

Per `08-Roadmap-Risk.md`, ini harus locked sebelum build mulai. Canonical decisions log lives in `08-Roadmap-Risk.md` § "Open Decisions" (pattern follows existing entries #4 BPS/OSM, #13 IAP, etc.) — `09-Versions.md` is scoped to library version pins only.

- [x] **IAP vs Cloud Armor + VPN** untuk admin panel — **Resolved 2026-04-26: IAP**. Allowlist `nearyouid.founder@gmail.com`. Full rationale in `08-Roadmap-Risk.md` § Open Decisions #13.
- [ ] **OTel backend vendor**: Grafana Cloud (default) vs Honeycomb vs Cloud Trace
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

- [ ] Pilih source (BPS preferred, OSM fallback)
- [ ] Download dataset
- [ ] Process: ~500 kabupaten/kota GeoJSON
- [ ] DKI Jakarta special: 5 kotamadya + Kepulauan Seribu di level kabupaten
- [ ] Buffer coastal kabupaten +22km untuk maritime extension (12 nautical miles)
- [ ] Spot-check 10 kabupaten kompleks (Kepulauan Riau, Halmahera, dll)
- [ ] Import ke Postgres dengan schema `admin_regions` + GIST index
- [ ] Document attribution di Privacy Policy (kalau OSM)

### 6.4 UU ITE / Profanity Wordlist

- [ ] Draft UU ITE keyword list (AI + manual review, 1 hari)
- [ ] Draft general profanity blocklist (Indonesia + slang)
- [ ] Draft username-specific profanity filter
- [ ] Upload ke Firebase Remote Config: `moderation_uu_ite_list`, `moderation_profanity_list`
- [ ] Also commit fallback files: `/backend/src/main/resources/moderation/uu_ite.default.txt`, `profanity.default.txt`
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

- [ ] Semua secret di-inject ke Cloud Run environment bisa di-read via Secret Manager API
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
| 3. Infrastructure Accounts | 45+ | 26 | `[~]` (Firebase staging + R2 staging + Sentry org/projects/DSNs + Resend domain/key done; CF Images deferred Phase B; sentry-cli auth token deferred to mobile build phase; Cloudflare DNS active for `api-staging`; Supabase + Upstash staging live per inline notes) |
| 4. Secrets | 22 | 10 partial | `[~]` (`staging-firebase-admin-sa` v1, `staging-r2-{access-key-id,secret-access-key,bucket-name,endpoint-url,account-id}` v1, `staging-sentry-{backend,android,ios}-dsn` v1, `staging-resend-api-key` v1) |
| 5. Decisions | 9 | 4 | `[~]` (IAP, BPS/OSM, CF Images URL, CSAM trigger — all resolved 2026-04-26; OTel + 4 pricing/quota verifications still open) |
| 6. Datasets | 4 work items | 0 | `[ ]` |
| 7. Legal | 6 | 0 | `[ ]` |
| 8. Verification | 8 | 0 | `[ ]` |

---

## Cross-References

- Spec authority: `08-Roadmap-Risk.md` Pre-Phase 1 section
- Version decisions log: `09-Versions.md`
- Architecture context: `04-Architecture.md` (Deployment Strategy section)
- Security requirements: `06-Security-Privacy.md`
- Operations detail: `07-Operations.md`
