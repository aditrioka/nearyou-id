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

**Notes**:
- Prod Redis REST URL: _________________
- Staging Redis REST URL: _________________

### 3.3 Firebase

- [ ] Signup / use existing Google account - https://console.firebase.google.com
- [ ] Create project `nearyou-prod` (link ke GCP project prod)
- [ ] Create project `nearyou-staging` (link ke GCP project staging)
- [ ] Enable **FCM** (Cloud Messaging)
- [ ] Enable **Phone Authentication** (fallback kalau ada; primary pakai Google/Apple Sign-In)
- [ ] Enable **Remote Config** untuk feature flags
- [ ] Download `GoogleService-Info.plist` (iOS) untuk kedua environment
- [ ] Download `google-services.json` (Android) untuk kedua environment
- [ ] Generate Firebase Admin SDK service account JSON untuk backend (prod + staging)

**Initial Remote Config flags** (create dengan default values):
- [ ] `image_upload_enabled` = false (boolean)
- [ ] `attestation_mode` = "enforce" (string, staging: "off")
- [ ] `search_enabled` = true (boolean)
- [ ] `perspective_api_enabled` = true (boolean)
- [ ] `premium_username_customization_enabled` = true (boolean)
- [ ] `moderation_profanity_list` = [] (string-array, seed nanti)
- [ ] `moderation_uu_ite_list` = [] (string-array, seed nanti)
- [ ] `moderation_match_threshold` = 3 (number)

**Notes**:
- Firebase prod project ID: _________________
- Firebase staging project ID: _________________

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

- [ ] Dari Cloudflare dashboard, enable **R2** (object storage) - butuh payment method
- [ ] Create buckets:
  - [ ] `nearyou-media-prod`
  - [ ] `nearyou-media-staging`
  - [ ] `nearyou-backups` (production backup target)
- [ ] Enable **Cloudflare Images**
  - [ ] Note account hash (buat URL structure)
  - [ ] Note delivery URL pattern (standard vs custom subdomain, verify Pre-Phase 1)
- [ ] Generate R2 S3-compatible API token untuk backend access

**Notes**:
- CF account ID: _________________
- R2 access key: _________________ (location)
- CF Images account hash: _________________

### 3.6 Sentry

- [ ] Signup - https://sentry.io
- [ ] Create organization "NearYouID"
- [ ] Create projects: `nearyou-android`, `nearyou-ios`, `nearyou-backend`
- [ ] Save DSN untuk masing-masing
- [ ] Note: separate staging pakai `environment=staging` tag (single project)
- [ ] Siapkan `sentry-cli` auth token untuk CI/CD (upload ProGuard + dSYM)

**Notes**:
- Sentry org: _________________
- DSN android: _________________ (location)
- DSN ios: _________________ (location)
- DSN backend: _________________ (location)

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

- [ ] Signup - https://resend.com
- [ ] Verify sending domain `nearyou.id` (butuh DNS records di Cloudflare)
  - [ ] Tambah SPF record
  - [ ] Tambah DKIM records (Resend kasih)
  - [ ] Tambah DMARC record
- [ ] Generate API key
- [ ] Test send 1 email untuk verify

**Notes**:
- Resend API key location: _________________

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
- [ ] `prod-firebase-admin-sa` dan `staging-firebase-admin-sa` (JSON file)
- [ ] `prod-apns-p8-key` dan `staging-apns-p8-key` (file content)
- [ ] `prod-resend-api-key` dan `staging-resend-api-key`
- [ ] `prod-r2-access-key` + `prod-r2-secret` dan staging equivalents
- [ ] `prod-cf-images-api-token` dan `staging-cf-images-api-token`
- [ ] `prod-sentry-auth-token` (shared untuk upload, tergantung strategi)
- [ ] `prod-amplitude-api-key` (dan staging)
- [ ] `prod-admin-app-db-connection-string` (DB role `admin_app`, separate dari main API)
- [ ] `prod-main-app-db-connection-string` (DB role `main_app`)
- [ ] `prod-flyway-db-connection-string` (DB role `flyway_migrator`, DDL rights)
- [ ] `prod-cf-worker-csam-secret` (kalau pilih Cloudflare Worker auto-forward path untuk CSAM)

---

## 5. Decisions yang Perlu Diputusin Pre-Phase 1

Per `08-Roadmap-Risk.md`, ini harus locked sebelum build mulai. Document keputusan di `09-Versions.md`.

- [ ] **IAP vs Cloud Armor + VPN** untuk admin panel
  - Rekomen: IAP (free, Google-managed, bisa allowlist Gmail individual)
  - Fallback: Cloud Armor + VPN (kalau workflow IAP ga cocok)
- [ ] **OTel backend vendor**: Grafana Cloud (default) vs Honeycomb vs Cloud Trace
- [ ] **BPS vs OpenStreetMap** untuk polygon kabupaten/kota
  - BPS: public domain / CC-BY, primary choice
  - OSM: ODbL, attribution required
- [ ] **CF Images URL pattern**:
  - Custom subdomain `img.nearyou.id/...` (preferred)
  - Fallback: standard `imagedelivery.net/<hash>/...`
- [ ] **CSAM trigger path**:
  - MVP: admin-triggered manual (review email, paste URL di admin panel)
  - Phase 2+: Cloudflare Worker auto-forward dari 451 responses
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
| 3. Infrastructure Accounts | 45+ | 0 | `[ ]` |
| 4. Secrets | 22 | 0 | `[ ]` |
| 5. Decisions | 9 | 0 | `[ ]` |
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
