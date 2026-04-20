# 08 - Roadmap & Risk

Development phases, dev tooling with CI lint rules, risk register. Related files: `01-Business.md` (GTM + financial forecast), `04-Architecture.md` (tech stack), `05-Implementation.md` (technical implementations referenced per phase), `06-Security-Privacy.md` (security controls), `07-Operations.md` (admin panel build).

---

## Development Phases (19-20 Weeks Pre-Launch)

### Pre-Phase 1 (Week 1) - Blocking Clock Items

1. **Enable Cloudflare CSAM Scanning Tool** on zone `nearyou.id` + verify email (30 minutes after domain setup + CF zone active)
2. **Verify CF Images delivery scope**: test upload via `img.nearyou.id/cdn-cgi/imagedelivery/...`, verify CSAM scan log, confirm the custom subdomain delivery URL pattern works (fallback: standard `imagedelivery.net` hostname)
3. Verify pricing tiers in Google Play Console + App Store Connect (Rp9,900 / Rp29,000 / Rp249,000). Document final values in the Version Pinning Decisions Log (internal artifact).
4. **Verify Supabase disk size pricing** (separate from compute add-on, document actual rate per GB)
5. **Verify Supabase Realtime pricing** current rate per concurrent + per message
6. **Verify Google Cloud Vision Safe Search pricing** per image
7. **Verify Google Play Developer one-time fee** (currently $25 last confirmed; confirm current rate before budgeting)
8. IAP vs Cloud Armor + VPN decision for the admin panel deployment pattern
9. OTel backend vendor decision: Grafana Cloud default
10. Stack freeze meeting + Version Pinning Decisions Log
11. **Request Play Integrity quota increase** to 100k/day via the Play Console form (processing up to 1 week)
12. **JWT strategy finalized**: RS256 primary for REST, HS256 companion for Supabase WSS
13. **Generate RSA key pair** for Ktor REST JWT, store in GCP Secret Manager, draft JWKS endpoint spec
14. **Generate JITTER_SECRET** (256-bit random) for coordinate fuzzing, store in GCP Secret Manager
15. **Generate backup `age` keypair** for `pg_dump` stream encryption (public key baked into backup image, private key in GCP Secret Manager `backup-age-private-key`)
16. **Generate `csam-archive-aes-key`** (AES-256) for encrypting `csam_detection_archive.encrypted_metadata`, store in GCP Secret Manager
17. **Set up Resend account** + verify sending domain `nearyou.id` + store API key in GCP Secret Manager
18. **Set up Firebase Remote Config** + draft initial flags (`image_upload_enabled=false`, `attestation_mode=enforce`, `search_enabled=true`, `perspective_api_enabled=true`)
19. Repo setup + CI/CD skeleton (GitHub Actions) + Sentry dSYM/ProGuard upload steps drafted
20. Indonesian word-pair dataset: 600 adjectives × 600 nouns + 100 modifiers (AI-assisted + filter + manual scan, budget 3-4 days)
21. BPS or OpenStreetMap kabupaten/kota polygon dataset (decision + attribution strategy) + 12-mile maritime buffer, import into Postgres with `admin_regions` schema + GIST index + spot-check 10 complex kabupaten
22. UU ITE content wordlist seed (AI + manual review, 1 day)
23. **Reserved usernames seed list** drafted (admin, support, moderator, system, nearyou, staff, official, akun_dihapus, all 1-2 char strings) for `reserved_usernames` Flyway insert
24. Google Cloud Console OAuth client setup (Android + iOS + Web)
25. Apple Developer account + "Sign in with Apple" capability + App Attest setup + S2S endpoint registration + APNs `.p8` key generation
26. Play Integrity API setup in Google Cloud + public key fetch
27. Supabase project setup + shared HS256 JWT secret in GCP Secret Manager
28. **Scoped `admin_app` DB role** created in Supabase (row-level access to operational tables, no DDL, no UPDATE/DELETE on `admin_actions_log`) + connection string stored in GCP Secret Manager (`admin-app-db-connection-string`)
29. RLS policy setup on `realtime.messages` with **regex-guarded `split_part` pattern** + mandatory test cases (including malformed/SQL-injection topics)
30. **RevenueCat webhook secrets generated** (bearer + optional HMAC) in GCP Secret Manager
31. Abstraction layer scaffolding (`:core:domain`, `:core:data`, `:shared:distance`, `:shared:resources`, `:infra:*` modules including `:infra:attestation`, `:infra:otel`, `:infra:sentry`, `:infra:amplitude`, `:infra:resend`, `:infra:remote-config`, `:infra:postgres-neon` backup path)
32. R2 backup bucket `nearyou-backups` + deletion-log object prefix `deletion-log/` + Cloud Run Jobs project scaffold (backup + migration, not a Ktor endpoint)
33. **iOS `PrivacyInfo.xcprivacy` draft** with data types + Required Reasons API declarations
34. **Staging environment bootstrap**:
    - Separate Supabase project `nearyou-staging` (Free tier)
    - Separate Upstash Redis `nearyou-cache-staging` (Free tier)
    - Separate Cloudflare R2 bucket `nearyou-staging`
    - Separate Firebase project `nearyou-staging` (FCM + Remote Config)
    - Separate RevenueCat sandbox environment
    - Subdomain DNS: `api-staging.nearyou.id`, `admin-staging.nearyou.id`, `img-staging.nearyou.id`
    - GCP Secret Manager namespace migration: existing production secrets renamed to reflect implicit `prod-*` prefix going forward (or documented convention); new `staging-*` secret slots created for each category (`staging-ktor-rsa-private-key`, `staging-supabase-jwt-secret`, `staging-revenuecat-webhook-secret`, `staging-jitter-secret`, `staging-age-private-key`, `staging-csam-archive-aes-key`, `staging-admin-app-db-connection-string`, `staging-firebase-admin-sa`, `staging-apns-key-p8`, `staging-resend-api-key`)
    - CI/CD branching rule: `main` branch auto-deploys staging; git tag `v*` deploys prod (manual approval gate)
    - Mobile build flavors wired: Android `staging` vs `production`, iOS `Staging` vs `Production` xcconfig schemes
    - Staging attestation default: `attestation_mode=off` in the staging Firebase Remote Config (QA accounts bypass enforcement)
35. **Premium username customization bootstrap**:
    - Firebase Remote Config flag `premium_username_customization_enabled` (default TRUE) seeded for both staging and production
    - Username profanity blocklist drafted (AI + manual review, budget 0.5 day)
36. **Content moderation keyword lists bootstrap**:
    - Firebase Remote Config string-array parameters `moderation_profanity_list` and `moderation_uu_ite_list` created for both staging and production
    - Repo-committed fallback files at `/backend/src/main/resources/moderation/profanity.default.txt` and `uu_ite.default.txt`
    - `moderation_match_threshold` numeric parameter (default 3) for the Aho-Corasick matcher
    - GCP Secret Manager slot `content-moderation-fallback-list` for the integrity-checked fallback (optional Phase 2 hardening)
37. **Invite-code secret generation**: generate 256-bit random `invite-code-secret` in GCP Secret Manager for deterministic invite-code HMAC derivation
38. **CSAM trigger path decision**: document admin-triggered handler path (MVP) vs Cloudflare Worker auto-forward path (Phase 2+). If Worker path is chosen early, generate `cf-worker-csam-secret` in GCP Secret Manager and draft the Worker script
39. **Cloudflare ingress enforcement**: configure Cloud Run ingress + Cloud Armor policy to allow only Cloudflare edge IP ranges (prevents direct `*.run.app` hits that would bypass `CF-Connecting-IP`). Fallback: XFF-last-hop middleware check documented in `05-Implementation.md`.
40. **Admin session cookie mechanism**: document `__Host-admin_session` cookie format (Secure, HttpOnly, SameSite=Strict, 256-bit opaque token, SHA256 at rest) + `X-CSRF-Token` header + `admin_sessions.csrf_token_hash` column. Reserve GCP Secret Manager slot `admin-session-cookie-signing-key` for the optional future signed-cookie mode.
41. **WebAuthn challenges table**: `admin_webauthn_challenges` schema prepared for Phase 3.5 admin WebAuthn rollout (challenge state per registration/authentication ceremony, 5-minute TTL, consumed-guard to prevent replay).
42. **Privacy flip infrastructure**: `users.privacy_flip_scheduled_at` column scaffolded in the initial schema migration; hourly `/internal/privacy-flip-worker` deferred to Phase 4 build alongside the RevenueCat webhook handler.
43. **Invite code prefix column**: `users.invite_code_prefix VARCHAR(8) NOT NULL UNIQUE` included in the initial schema so invite-code resolution at signup is O(1) via the UNIQUE index (rather than an O(N) scan over all users).

### Phase 1: Core Backend (Weeks 2-4)

1. User service + Google Sign-In + Apple Sign-In verification (server-side)
2. **Age gate enforcement (18+ only)**: DOB input at signup, reject under-18 + insert hashed identifier into `rejected_identifiers`, `users.date_of_birth` column with DB-level `CHECK (date_of_birth <= CURRENT_DATE - INTERVAL '18 years')`
3. **`rejected_identifiers` blocklist** pre-check at signup for DOB-shopping bypass (anti under-18 bypass)
4. **Suspension unban worker** (`/internal/unban-worker`, daily Cloud Scheduler) for time-bound `suspended_until` flips
5. Device attestation verification (Play Integrity + App Attest) at signup + sensitive operations
6. **Attestation judicious use pattern**: Classic for signup, Standard for frequent ops, cached verdict 1 hour in Redis
7. **Attestation bypass via Remote Config + Google ID SHA256 whitelist**
8. **RS256 JWT issuance for REST API** + JWKS endpoint at `/.well-known/jwks.json` + kid rotation scaffold; `token_version` claim explicit in payload
9. **HS256 JWT issuance for Supabase WSS** via `/api/v1/realtime/token`
10. Refresh token reuse detection + family revocation logic + full schema with UNIQUE index on `token_hash`
11. Client state machine for token refresh (priority order, preemptive, mutex single-flight, 30s overlap, dual-token aware)
12. Token version revocation (Redis cache + invalidation hooks + DB fallback)
13. Post creation + PostGIS storage (dual column: `actual_location` NOT NULL + `display_location` NOT NULL with **HMAC-SHA256(JITTER_SECRET, post_id) jitter**)
14. `renderDistance` implementation with fuzz-first / floor 5km / round-to-1km ordering in `:shared:distance`
15. Nearby + Following + Global timeline spatial queries (using `display_location`)
16. **Block user feature**: schema + endpoints + cascade follow removal + CI lint
17. **Analytics consent schema** (`users.analytics_consent JSONB`) + consent-aware Amplitude wrapper (suppress if off)
18. **FCM token registration endpoint** (`POST /api/v1/user/fcm-token`)
19. **Health check endpoints** (`/health/live`, `/health/ready`) + Cloud Run probe config
20. **Content length validation middleware** (post 280, reply 280, chat 2000, bio 160, display_name 50, username schema-max 60 / Premium-custom-max 30)
21. Schema + Flyway migrations (deployed via Cloud Run Jobs `nearyou-migrate-staging` then `nearyou-migrate-prod` pre-deploy):
    - `users` full schema with `private_profile_opt_in`, **`privacy_flip_scheduled_at TIMESTAMPTZ`** (hourly worker deadline for the 72h downgrade grace), **`invite_code_prefix VARCHAR(8) NOT NULL UNIQUE`** (O(1) inviter lookup at signup), `analytics_consent`, `subscription_status` with 3-state CHECK constraint, `suspended_until`, DOB CHECK for 18+ enforcement, **`username VARCHAR(60)`** (accommodates auto-generated fallback), **`username_last_changed_at TIMESTAMPTZ`** (Premium change cooldown)
    - `reserved_usernames` with `source` column (`seed_system` vs `admin_added`) + `updated_at` + Flyway seed insert (all seeds marked `source = 'seed_system'`, immutable via `reserved_usernames_protect_seed` DB trigger) + `reserved_usernames_updated_at_trigger` auto-maintaining `updated_at`
    - **`username_history`** schema for the 30-day release hold on old handles after Premium username change (plain B-tree `released_at` index, NOT partial; PostgreSQL rejects `WHERE released_at > NOW()` as non-immutable)
    - `rejected_identifiers` anti-bypass blocklist
    - `posts` with dual location + `content_tsv` generated column + GIN indexes (FTS + trgm) + `is_auto_hidden` flag + **`content VARCHAR(280)`**
    - `posts_timeline_cursor_idx` composite index
    - `posts_nearby_cursor_idx` composite spatial+time
    - `refresh_tokens` full schema + indexes
    - `post_edits` with UNIQUE `(post_id, edited_at)` temporal ordering + **`content_snapshot VARCHAR(280)`** + **`post_id ... ON DELETE CASCADE`** (retention bound to parent post lifetime, not fixed 1-year)
    - `follows` schema + bidirectional indexes + CHECK self-follow
    - `post_likes` schema
    - `post_replies` schema with soft-delete and block-aware read path + **`content VARCHAR(280)`** + **`is_auto_hidden BOOLEAN DEFAULT FALSE`** (mirrors the post-level flag for the 3-unique-reporters auto-hide)
    - `reports` schema with unique reporter/target constraint + **`reporter_id ... ON DELETE CASCADE`** (resolves NOT NULL + SET NULL conflict) + **`reviewed_by ... ON DELETE SET NULL`** (admin churn doesn't erase moderation history)
    - `moderation_queue` schema with unique target/trigger constraint (including `username_flagged` trigger for Premium username candidate rejections)
    - `notifications` schema for DB-persisted in-app list with expanded `type` CHECK covering `chat_message_redacted`, `privacy_flip_warning`, `username_release_scheduled`, `apple_relay_email_changed`
    - `conversations` + `conversation_participants` with **slot-based partial unique index** + advisory lock approach
    - `chat_messages` with `embedded_post_*` + **`redacted_at` + `redacted_by` (admin FK, SET NULL) + `redaction_reason`** + **atomicity CHECK coupling `redacted_at` with `redacted_by`** + **`CHECK (content IS NOT NULL OR embedded_post_id IS NOT NULL OR embedded_post_snapshot IS NOT NULL)`** (prevents fully empty messages) + **`embedded_post_edit_id ... ON DELETE SET NULL`** (handles cascade when post is hard-deleted)
    - `subscription_events` event-level tracking
    - `referral_tickets` (with `expires_at DEFAULT NOW() + INTERVAL '14 days'`) + `granted_entitlements` with UNIQUE `(referral_ticket_id, user_id)` + partial unique index enforcing one `grant_role = 'inviter'` row per user for the lifetime cap + `users.inviter_reward_claimed_at` sentinel column
    - `admin_regions` schema (province, kabupaten_kota, maritime buffered)
    - `admin_users` + `admin_webauthn_credentials` + **`admin_webauthn_challenges`** (5-min TTL ceremony state with consumed-guard) + `admin_sessions` with **`csrf_token_hash`** column
    - `user_blocks` schema
    - `user_fcm_tokens` schema with `last_seen_at` freshness column
    - `csam_detection_archive` (90-day preservation, AES-256-GCM metadata; plain B-tree `expires_at` index, NOT partial; **UNIQUE on `image_hash` and partial UNIQUE on `cf_match_id`** for deduplication across trigger paths; **`source` column** distinguishing `admin_manual` vs `cf_worker` vs `email_poll`)
    - `admin_actions_log` immutable audit table (UPDATE/DELETE revoked at `admin_app` role)
    - `deletion_requests` audit table
22. Create `visible_posts` + `visible_users` database views + CI lint rule to detect raw `FROM posts` / `FROM users` / `FROM post_replies`
23. RLS audit (regex-guarded `split_part`, mandatory test cases pass including malformed topic handling, SQL injection attempts)
24. 4-layer rate limiting + WIB stagger in Redis TTL from Day 1 + `computeTTLToNextReset(user_id)` shared function + CI lint + **hash tag key format standard** + `geocode:{geocell:<lat2dp>_<lng2dp>}` convention
25. Attestation-gated guest session + pre-issuance rate limits (IP + fingerprint + global circuit breaker)
26. Username atomic flow at signup with `reserved_usernames` pre-check + `username_history` on-release-hold pre-check (INSERT + catch `unique_violation` + progressive fallback, producing a handle ≤60 chars)
27. Remote Config integration with Firebase (feature flags including `premium_username_customization_enabled`, force update, attestation mode + bypass list, image upload gate, search kill switch)
28. `/internal/*` route prefix + OIDC middleware
29. Anomaly detection metrics: JWT verify fail rate, subscribe rate per sub, refresh token reuse log, RevenueCat webhook signature fail, `rejected_identifiers` insert rate
30. Timeline read limit documentation: 50/session soft + 150/hour hard (authoritative)
31. CI lint rules:
    - Detect raw `FROM posts` / `FROM users` / `FROM post_replies` in the business module
    - Enforce `computeTTLToNextReset(user_id)` for rate limit TTL
    - Mandatory test case "JWT sub not in public.users then deny" on every RLS policy change
    - Detect hardcoded strings in mobile source (must go via Moko Resources resource file)
    - **Detect `ST_DWithin` or `ST_Distance` using `actual_location` in non-admin paths (must use `display_location`)**
    - **Detect raw Redis keys without hash tag `{scope:<value>}`**
    - **Detect business queries without block-exclusion join (applies to `posts`, `users`, `chat_messages`, `post_replies`; except Repository own-content + admin)**
    - Detect content input endpoints without a length guard
    - **Detect GCP Secret Manager accesses that hardcode a secret name without going through the environment-aware `secretKey(env, name)` helper (enforces `staging-*` vs prod namespace)**
    - **Detect `UPDATE users SET username = ...` in non-admin, non-username-customization paths (Premium username change must go through the single dedicated transaction flow; signup is the only other allowed writer; the lint rule recognizes the annotation `// @allow-username-write: signup` or `// @allow-username-write: customization` on the enclosing function, and fails CI on any other write site)**
    - **Detect partial-index definitions with `NOW()` in the `WHERE` clause** (PostgreSQL rejects non-immutable predicates; enforce plain B-tree + runtime filter instead)
    - **Detect direct reads of `X-Forwarded-For` for client IP** (must use the `clientIp` request-context value populated by the Cloudflare-aware middleware)
    - **Detect direct `UPDATE users SET private_profile_opt_in = FALSE` outside the privacy flip worker and user-initiated Settings flip** (recognize annotation `// @allow-privacy-write: worker` or `// @allow-privacy-write: user_settings`)
    - **Detect INSERT into `admin_sessions` without populating `csrf_token_hash`** (NOT NULL column; runtime violation caught at Flyway but lint catches earlier)
    - **Detect raw `FROM admin_users` joins that cascade user deletes** (admin FKs must be `ON DELETE SET NULL` on all operational tables)

### Phase 2: Social Features (Weeks 5-6)

1. Follow/unfollow system (using `follows` schema)
2. Following + Global timeline with polygon reverse geocoding (using `actual_location`)
3. Like + reply (20/day Free limit, 280 chars max, flat structure, block-aware)
4. **Report feature**: `reports` insert endpoint, reason picker, auto-hide trigger at 3 unique reporters (accounts >7 days), `moderation_queue` row insert
5. **In-app notifications API**: list endpoint, mark-read endpoint, unread count, FCM parallel dispatch, `notifications` table write-path
6. **Search feature (Premium, PostgreSQL FTS + pg_trgm)**: endpoint + query pattern + GIN index verification + rate limit 60/hour
7. FCM push infrastructure
8. **Platform-specific push payload builder**:
   - Android: data-only
   - iOS: alert + mutable-content + NSE handshake via App Group
9. **Chat via Supabase Realtime Broadcast mode**:
   - Ktor publishes via Supabase client
   - Client subscribes via `conversation:<id>` channel pattern
   - REST fallback for resync
   - Block check before send
10. `ChatRealtimeClient` + `RealtimeTokenProvider` abstraction
11. Reconnection handling via Supabase SDK
12. **CTE batching mandatory** on timeline endpoint
13. OpenTelemetry instrumentation (Ktor server, HTTP client, Postgres JDBC, Redis Lettuce, Resend HTTP)
14. **Phase 2 benchmark expanded**:
    - Dataset 10k-50k posts Jakarta dense
    - Target p95 <200ms timeline
    - Cold start Cloud Run p99 <3 seconds
    - Auth check <30ms
    - Spatial query isolated <50ms
    - FTS search query isolated <150ms
    - Load test 100 concurrent
    - Re-benchmark Broadcast mode cost per message at realistic scale
15. Coordinate fuzzing path audit (all spatial queries use `display_location` except reverse geocoding + admin)
16. Perspective API integration (stopgap text moderation, 500ms timeout fail-open, kill switch flag, writes to `moderation_queue` on high score)

### Phase 3: Mobile App Android + iOS (Weeks 7-10)

KMP code is ~70% shared; iOS incremental ~1.3-1.5x.

**Shared (KMP)**:
- Auth screens (Google/Apple branching)
- **Age gate screen (DOB picker + 18+ flow)**
- **Analytics consent screen (UU PDP opt-in)**
- Consent flow (location + notifications)
- Post creation + location picker + content length guard
- Nearby + Following + Global timeline + sliding window session tracking
- Profile + settings (analytics consent toggle, block list management, suspension countdown UI)
- Chat UI (block check, content length)
- Search UI (Premium gate)
- Block user + Report action UI
- In-app notifications list UI (backed by `/api/v1/notifications`)
- `DistanceRenderer` from `:shared:distance` module (cross-runtime with backend)
- Pure DIY Google/Apple Sign-In wrapper (expect/actual)
- Attestation expect/actual abstraction (`:infra:attestation`)
- FCM token registration on token refresh callback
- Remote Config client fetch on cold start + foreground
- Sentry KMP setup via `:infra:sentry` (unified with backend Sentry)
- Amplitude tracker via `:infra:amplitude` (event taxonomy, consent-aware)
- Moko Resources strings via `:shared:resources`

**iOS-specific**:
- **`PrivacyInfo.xcprivacy` finalized** with merged SDK declarations (Sentry, Amplitude, RevenueCat, FCM, AdMob)
- Keychain token storage (~2 days)
- Core Location bridge (~2 days)
- APNs + `.p8` key integration (~2 days)
- StoreKit subscription (~4 days)
- Sign in with Apple Swift bridge + cinterop (~6 days)
- App Attest bridge (~3 days)
- Apple S2S notification endpoint + Apple JWKS verification (~1 day)
- "Restore Purchases" button in Settings (mandatory per App Store Review 3.1.1)
- **Notification Service Extension (core implementation for chat preview toggle)** (~2 days)
- App Group UserDefaults for NSE shared preference read (~0.5 day). Full checklist must be followed (see `04-Architecture.md` Push Notification section)
- Sentry Cocoa SDK cinterop (~0.5 day) + dSYM upload build phase
- App Store submission + iteration (~5-10 calendar days)

**Android-specific**:
- Credential Manager integration (~2 days)
- Google Play Billing (via RevenueCat, ~2 days) + Google Play Developer Console setup ($25 one-time fee, confirm current rate in Pre-Phase 1)
- POST_NOTIFICATIONS runtime permission handling
- Play Integrity API integration (Classic + Standard) (~2 days)
- Sentry Android SDK (~0.5 day) + ProGuard mappings upload via Gradle task
- Build variants: production (attestation enforce) / qa (off) / debug (off)

**iOS minimum version**: iOS latest-3 (>95% active device coverage, required for StoreKit + App Attest features).

### Phase 3.5: Admin Panel + UU PDP Compliance (Weeks 11-13)

1. Ktor + HTMX admin panel (uses `admin-app-db-connection-string` from GCP Secret Manager, scoped role)
2. Admin panel deployment: IAP primary OR Cloud Armor + VPN fallback (per Pre-Phase 1 decision), subdomain `admin.nearyou.id`
3. Admin login via `admin_users` + Argon2id password + TOTP mandatory (solo admin period)
4. WebAuthn infrastructure (~5 days of work: backend 1.5d + frontend 1d + enrollment UI 0.5d + recovery path 1d + cross-browser testing 1d). Ready for the second admin hire; TOTP enforced during the solo admin period.
5. Admin session management via `admin_sessions` with 30-minute idle timeout
6. Hard delete worker (Cloud Scheduler calls `/internal/cleanup` OIDC-authed) + **deletion log write to R2**; consumes `deletion_requests`
7. Tombstone + cascade delete worker logic (chat messages preserved, sender tombstoned)
8. Image lifecycle worker (cascade CF Images on post hard-delete)
9. Refresh token cleanup worker (expired daily + stale 90-day weekly)
10. FCM token cleanup worker: stale 30-day weekly + **on-send 404/410 immediate delete path** wired into the FCM send helper
11. Notifications purge worker (>90 days, weekly)
12. Moderation queue + reports archival worker (resolved rows >1 year, weekly)
13. **WebAuthn challenge cleanup worker** (weekly, deletes `admin_webauthn_challenges` rows where `expires_at < NOW() - INTERVAL '1 day' AND consumed_at IS NULL`)
13. **Backup via Cloud Run Jobs**:
    - Container image build (`postgres:alpine` + `age` CLI + `aws-cli` + `jq`)
    - Cloud Scheduler triggers
    - Weekly logical dump encrypted via `age` stream + daily schema-only + monthly verify test
    - **Post-restore reconciliation script with `age -d` decrypt step**
    - Slack webhook for failures
14. **Flyway migration Cloud Run Job** `nearyou-migrate` runnable pre-deploy
15. Data export endpoint + async job (scope matrix implementation) + Resend email delivery
16. Audit log infrastructure (`admin_actions_log` immutable; UPDATE/DELETE revoked at `admin_app` role level)
17. Post edit history UI (user-facing "Riwayat edit" modal with `ROW_NUMBER` version display, admin panel filter)
18. Shadow ban enforcement verification via views + CI lint rule passes
19. Anomaly detection dashboards (Sentry widget + Amplitude funnel embed + Slack webhook): JWT verify fail, refresh reuse, subscribe rate per sub, attestation failure rate, age gate rejection rate, webhook signature fail, email delivery rate, `rejected_identifiers` insert rate
20. Attestation fallback manual review queue + support form integration
21. Referral manual grant path (rate-limited, audit logged)
22. **Subscription grace period monitor** (daily check for expiry, manual expedite capability)
23. **Chat message redaction capability** for admin (severe violations only)
24. **Premium Username Change Oversight (admin)**: read-only `username_history` viewer, surface `moderation_queue` entries with `trigger = 'username_flagged'`, admin override action for borderline candidates (rate-limited 10/hour), manual release of old handle from 30-day hold (rate-limited 5/hour), all audit-logged
25. **Feature flag admin UI** (toggle Remote Config including `premium_username_customization_enabled`, audit logged, rate-limited)
26. **Reserved usernames editor** (paginated view + filter by `source`, add single + CSV bulk add, edit reason, remove with `source = 'seed_system'` protection at UI + DB trigger, audit log via `admin_actions_log` with `reserved_username_added` / `reserved_username_edited` / `reserved_username_removed` action types)
27. **Rejected identifiers viewer** (read-only, anti-abuse audit + support manual clear path for legitimate adult re-verification)

### Phase 4: Premium + Payment + Ads (Weeks 14-16)

1. RevenueCat KMP SDK integration
2. **RevenueCat webhook signature verification** (Bearer token + HMAC + idempotency via `revenuecat_event_id`)
3. Set up 3 products + entitlement (final prices from Pre-Phase 1 verification)
4. Webhook handler with 3-state subscription status (values constrained by CHECK):
   - `INITIAL_PURCHASE`, `RENEWAL`: set `premium_active` + insert event `source=paid`
   - `BILLING_ISSUE`: set `premium_billing_retry` + 7d grace + notification (Premium access REMAINS active) + insert event
   - `EXPIRATION` with `BILLING_ERROR` reason: check grace, downgrade to `free` if elapsed + insert event
   - `CANCELLATION`: confirmation notification, no immediate action + insert event
   - `GRANT` (referral): set source=`referral` + insert event. Invitee grants fire on every successful ticket; the inviter grant fires exactly once in the inviter's lifetime at the 5th successful referral and never again.
5. **Subscription grace state machine**: daily cleanup worker checks grace expiry
6. **Privacy flip 72h warning**: on RevenueCat `EXPIRATION` (or grace elapse) handler sets `users.privacy_flip_scheduled_at = NOW() + 72h` (idempotent via `COALESCE`), inserts `notifications` row with `type = 'privacy_flip_warning'`, sends FCM push + in-app banner. Re-subscribe within window clears the column. **Hourly `/internal/privacy-flip-worker`** flips `private_profile_opt_in = FALSE` once the deadline elapses, busts Redis profile cache, writes audit log `privacy_flip_applied`.
7. **Apple S2S account-delete immediate path**: the `/internal/apple/s2s-notifications` handler for `account-delete` events inserts a `deletion_requests` row with `source = 'apple_s2s_account_delete'` + `scheduled_hard_delete_at = NOW()` AND synchronously enqueues a one-shot tombstone+cascade job before responding 200 to Apple. If the sync job fails, the daily backstop via `deletion_requests_immediate_idx` picks it up within 24 hours. `consent-revoked` events use `source = 'apple_s2s_consent_revoked'` + 30-day grace (same as user-initiated).
8. Event-level `subscription_events` tracking (paid vs referral)
9. Granted entitlement stacking logic (extend-if-active, grant-if-not) + invitee one-grant-per-ticket enforcement + inviter lifetime single-grant enforcement via `users.inviter_reward_claimed_at` sentinel and `grant_role = 'inviter'` partial unique index + idempotent worker with `dedup_key`
10. Premium access control backend (RevenueCat entitlement status)
11. **Premium username customization feature**:
    - `PATCH /api/v1/user/username` endpoint with 30-day cooldown, 30-day release hold, profanity + UU ITE moderation check, 3-day availability probe rate limit, feature flag gate (`premium_username_customization_enabled`)
    - Mobile UI in Settings (Premium entry point, paywall for Free, live availability probe, submit confirmation modal, cooldown countdown)
    - Downgrade UX banner (username stays as-is, further changes disabled until Premium renewed)
    - Test coverage: cooldown enforcement, release hold blocks claim, reserved list blocks claim, moderation flagged candidates get `username_flagged` queue entry, Free user rejected with paywall, feature flag OFF returns 503
12. Post editing (Premium, 30-minute window, race-safe transaction with `FOR UPDATE` + temporal `edited_at` versioning)
13. Post edit history UI
14. Chat context card edit history navigation (snapshot embed, redirect to current + banner, hard-delete handling)
15. Hide Distance toggle (via shared `DistanceRenderer`)
16. **Image upload infrastructure BUILT but gated by `image_upload_enabled=FALSE` flag (launch in Month 6)**:
    - **Cloudflare CSAM Tool verification** + webhook handler at `/internal/csam-webhook`
    - **Google Cloud Vision Safe Search upload pre-check** (block adult/violent upfront)
    - Cloudflare Images integration via **img.nearyou.id subdomain** + hard limit policy + single variant delivery + stricter lazy-load
    - **CSAM webhook auto-action**: delete post, ban user, cascade, **archive metadata 90 days in `csam_detection_archive` with AES-256-GCM encryption** (dedup via UNIQUE on `image_hash` + partial UNIQUE on `cf_match_id`; ON CONFLICT DO UPDATE enriches without resetting), audit log
    - **CSAM admin review queue + Kominfo report workflow**
17. Anomaly detection (rolling 30-day baseline per user)
18. AdMob KMP integration via manual expect/actual (~5 days) + UMP consent
19. Google Play Data Safety form
20. Apple Privacy Nutrition Labels (consistent with `PrivacyInfo.xcprivacy`)
21. Referral activity gate (login/post/session thresholds + device/IP/identifier checks), invitee reward per successful registration, inviter reward unlocked exactly once in the inviter's lifetime at the 5th successful referral with no further grants thereafter (enforced via `users.inviter_reward_claimed_at` sentinel + `grant_role = 'inviter'` partial unique index)
22. **Referral ticket creation flow**: `POST /api/v1/auth/signup` accepts optional `invite_code`; server resolves code to `inviter_user_id` via **O(1) lookup on `users.invite_code_prefix` UNIQUE index** (no full-table scan), validates inviter (exists, not deleted, not banned, account age >30d, not self), validates invitee (no device/IP collision, burst rate OK), INSERTs `referral_tickets` with status `pending_activity`. Failures are silent to the invitee (anti-probing); all attempts audit-logged.
23. **Daily referral activity check worker** (`/internal/referral-activity-check`, Cloud Scheduler) scans `status = 'pending_activity' AND expires_at > NOW()`, runs activity gate, flips expired tickets to `status = 'expired'`
24. **Chat message redaction admin capability**: `PATCH /admin/chat-messages/:id/redact` endpoint writes `redacted_at`, `redacted_by` (CSRF-token verified), `redaction_reason`; inserts `notifications` row with `type = 'chat_message_redacted'` for affected conversation participants; audit-logged with `admin_chat_redaction` action type.

### Pre-Launch (Weeks 17-19)

1. Soft launch with 500 seed users (Supabase Pro engaged at this point to avoid Free-tier idle auto-pause on a live user base)
2. Privacy policy + ToS live (chat admin-readable disclosure + image launch timeline disclosure + 18+ age policy + third-party processor disclosure + analytics consent + block feature disclosure + report feature disclosure)
3. DPO appointment + RoPA documentation
4. Stress test + final bug fix
5. **Security review checklist**:
   - Attestation working (reject emulator, pass legit device) + bypass whitelist functional
   - Refresh token reuse detection tested (simulate attack)
   - Shadow ban views complete (CI lint passes, `FROM posts` / `FROM users` / `FROM post_replies` audit)
   - Block feature CI lint passes + symmetric test (posts + replies + chat + profile + search)
   - Report auto-hide trigger tested (3 unique reporters then `is_auto_hidden = TRUE` + queue entry)
   - Data export scope verified (includes block list, analytics consent, notifications, reports submitted)
   - RLS test cases pass (non-participant deny, `left_at` deny, shadow-banned deny, sub-not-in-users deny, malformed topic handled, SQL injection blocked)
   - WIB stagger load test simulation
   - CSAM webhook end-to-end tested with a CF test image + Kominfo report workflow
   - CSAM archive retention + purge worker tested; `encrypted_metadata` decrypt via admin panel tested + audit logged
   - Subscription grace state transitions tested (with CHECK constraint enforcement verified)
   - RevenueCat webhook signature verification tested (reject forged)
   - Referral idempotency tested (concurrent worker runs; invitee-grant uniqueness per ticket; inviter lifetime cap verified with 10 successful referrals from the same inviter produces exactly one `grant_role = 'inviter'` row triggered at the 5th ticket, and zero thereafter)
   - Post edit concurrency tested
   - 1:1 conversation slot race tested (10 concurrent invite attempts)
   - NSE iOS preview toggle tested on a physical device
   - **Jitter reversibility impossibility test** (unit test: same `post_id` always produces the same `display_location`; without JITTER_SECRET, cannot derive `actual_location`)
   - **`renderDistance` test matrix** (4.5km fuzz+floor+round = "5km"; 7.4km = "7km"; 7.6km = "8km")
   - **Backup `age` decrypt + restore reconciliation test** (dump decrypt + apply deletion log + verify no tombstoned resurrected)
   - **Age gate tests**: DOB <18 rejected, DOB exactly 18 years old accepted, DOB change attempts blocked, DB CHECK constraint rejects direct insert of under-18 DOB
   - **Under-18 bypass prevention test**: reject then retry same Google ID with different DOB then still rejected via `rejected_identifiers`
   - **Suspension unban worker test**: time-bound `suspended_until` elapses then `is_banned = FALSE`; permanent ban with `suspended_until IS NULL` untouched
   - **FCM token registration tested** (token refresh, expired token 404/410 immediate delete, stale 30-day cleanup)
   - **Health check endpoints tested** (503 when DB down, rate limit enforcement)
   - **Feature flag toggle tested** (`image_upload_enabled` flip: UI + backend both respond)
   - **Analytics consent suppression tested** (Amplitude opt-out silent)
   - **Content length guard tested** (501-char post rejected, 2001-char chat rejected)
   - **Search authorization tested** (Free tier rejects, Premium allows, rate limit)
   - **Notifications API tested** (unread count accurate, mark-read persists, pagination, 90-day purge)
   - **Admin panel role scope test**: `admin_app` role cannot run DDL, cannot UPDATE/DELETE `admin_actions_log`, can read `csam_detection_archive.encrypted_metadata` only via the admin decrypt helper
   - **iOS PrivacyInfo.xcprivacy validator** (Apple-provided tool)
   - **Premium username customization tests**: Free user gets paywall, Premium user succeeds within cooldown rules, 30-day cooldown enforced (second attempt within window → 429), reserved candidate → rejected, on-release-hold candidate → rejected, profanity/UU ITE candidate → `username_flagged` queue entry + rejection, feature flag OFF → 503, Downgrade-to-Free keeps custom username (no revert) but blocks further changes
   - **Username history release hold test**: Alice changes `oldname` → `newname`; Bob's attempt to claim `oldname` during the 30-day window is rejected; after 30 days elapses, `oldname` becomes claimable
   - **Reserved usernames editor test**: admin can add/remove `admin_added` rows, cannot remove `seed_system` rows (UI blocked + DB trigger rejects as second line of defense), CSV bulk add skips duplicates with a report
   - **Environment separation tests**: staging Supabase project cannot be reached with production Ktor credentials (connection string mismatch); production Cloud Run service cannot hit staging Supabase (secret namespace mismatch); Flyway migration runs against staging first, prod only after success
   - **Mobile build flavor test**: Android `staging` flavor connects to `api-staging.nearyou.id`, `production` flavor connects to `api.nearyou.id`; iOS schemes verified equivalently
   - **Partial-index rejection test**: attempting to create a partial index with `WHERE released_at > NOW()` or `WHERE expires_at > NOW()` fails at `CREATE INDEX` time (documents the reason CI lint rule exists); verified fixed schemas apply cleanly
   - **post_replies auto-hide trigger test**: 3 unique reporters on a reply → `post_replies.is_auto_hidden = TRUE` + `moderation_queue` row inserted with `target_type = 'reply'`
   - **chat_messages empty-message CHECK test**: `INSERT` with both `content` and `embedded_post_id` NULL fails at the DB; `INSERT` with only `embedded_post_snapshot` populated (embed post hard-deleted scenario) succeeds
   - **embedded_post_edit cascade test**: hard-delete a post → `post_edits` rows cascade-deleted → `chat_messages.embedded_post_edit_id` set NULL → chat render falls back to snapshot-only
   - **Privacy flip 72h test**: Premium user with private profile → RevenueCat `EXPIRATION` webhook → `privacy_flip_scheduled_at` set to NOW() + 72h + warning notification fired; re-subscribe within window → column cleared; timer elapses without re-subscribe → hourly worker flips `private_profile_opt_in = FALSE` + busts Redis cache; Free user without private profile → no flip scheduled; permanent cancellation mid-window → behavior documented (worker still flips unless re-subscribed).
   - **Apple S2S immediate delete test**: simulated `account-delete` notification → `deletion_requests` row with `source = 'apple_s2s_account_delete'` + `scheduled_hard_delete_at = NOW()`; synchronous tombstone+cascade job runs before response; daily backstop worker picks up the row if the sync path fails. Separately: `consent-revoked` → `source = 'apple_s2s_consent_revoked'` + 30-day grace (user-cancellable).
   - **Admin session cookie test**: login sets `__Host-admin_session` with correct attributes (Secure, HttpOnly, SameSite=Strict, Path=/, Domain=admin.nearyou.id); state-changing request without `X-CSRF-Token` returns 403; request with mismatched token returns 403 + audit log `admin_csrf_violation`; session cookie rotates on role escalation.
   - **Admin CSAM handler authorization test**: read-only admin cannot invoke `/internal/csam-webhook` via Admin Panel (role check rejects); `admin` role succeeds with valid CSRF token; replay of the same CSRF token in a different session rejected.
   - **WebAuthn challenge replay test**: challenge marked `consumed_at = NOW()` on successful ceremony; second attempt with the same challenge rejected; expired challenge (>5 min) rejected.
   - **Chat message redaction test**: admin redacts a message → `redacted_at` + `redacted_by` both set (atomicity CHECK enforces); client renders "Pesan ini telah dihapus oleh moderator."; recipient receives `chat_message_redacted` notification; audit log entry `admin_chat_redaction`.
   - **CSAM archive dedup test**: same `image_hash` submitted via admin_manual → first insert succeeds with `source = 'admin_manual'`; later CF Worker invocation for the same hash → ON CONFLICT enriches `cf_match_id` without resetting `source`; UNIQUE constraint prevents double-archive.
   - **Notifications type enum test**: inserting a row with `type = 'chat_message_redacted'` / `'privacy_flip_warning'` / `'username_release_scheduled'` / `'apple_relay_email_changed'` succeeds; unknown type rejected by CHECK.
   - **Invite code prefix lookup test**: invite code resolution uses the UNIQUE index path (EXPLAIN shows index scan, not seq scan); collision retry falls back to 10-char prefix; 3 consecutive collisions fall back to random UUID suffix.
   - **Reports FK test**: hard-delete a user who submitted reports → their `reports` rows cascade-deleted (matches Data Export scope: user has their copy pre-deletion); admin who reviewed reports deleted → `reviewed_by` set NULL, reports retained.
   - **CSAM admin-trigger E2E test**: simulate CF email notification content → admin pastes URL in Admin Panel → `/internal/csam-webhook` fires → post hard-deleted, user banned, cascade applied, archive row written with AES-256-GCM metadata, Kominfo queue entry created, full audit log
   - **Cloudflare IP extraction test**: request with `CF-Connecting-IP: 1.2.3.4` → `clientIp` = `1.2.3.4`; request without header → fallback chain works; direct `*.run.app` hit blocked by Cloud Armor (or XFF-last-hop middleware in pre-Cloud-Armor config)
   - **Redis hash-tag format test**: all rate-limit key constructions produce `{scope:<value>}` form; MULTI/EXEC with `rate:guest_issue:{ip:x}` + `rate:guest_issue_day:{ip:x}` on same slot succeeds (same hash tag → same slot)
   - **Username regex test matrix**: valid (`abc`, `a_b.c`, `user1.test_2`) all accept; invalid (`.abc`, `abc.`, `a..b`, `_abc`, `ab`, 31-char) all reject; consecutive-dot `a..b` caught by the application-layer check not the regex
   - **Referral ticket creation test**: valid invite code at signup → ticket inserted with `status = 'pending_activity'`; inviter <30d old → no ticket + silent; self-invite (code derived from invitee's own user_id) → no ticket; fingerprint collision with inviter last 90d → no ticket; invitee never informed of the failure reason
   - **reserved_usernames trigger test**: `DELETE FROM reserved_usernames WHERE username = 'admin'` fails (seed_system protection); `UPDATE reserved_usernames SET source = 'admin_added' WHERE username = 'admin'` fails (seed migration blocked); `updated_at` auto-refreshes on any other legitimate UPDATE

### Public Launch (Week 20)

Go live on App Store + Play Store. Monitor density metrics before expanding to a second city. `image_upload_enabled = FALSE` (flipped in Month 6 after the dogfood period).

---

## Development Tools

- IDE: Android Studio + KMP plugin, Xcode for iOS
- Code quality: ktlint
- Testing: Kotest/JUnit5, Docker-based integration tests, Ktor test framework
- Local dev: Supabase CLI (`supabase start`) + Docker Compose (Ktor + Redis)
- CI/CD: GitHub Actions (build, test, lint, Docker, deploy Cloud Run, Sentry dSYM/ProGuard upload, Flyway migration job trigger)
- **CI lint rules**:
  - Detect raw `FROM posts` / `FROM users` / `FROM post_replies` in the business module (must go via `visible_*` views; allowed only in Repository own-content path + admin module)
  - Detect business queries without block-exclusion join (applies to posts, users, chat_messages, post_replies)
  - Enforce `computeTTLToNextReset(user_id)` for rate limit TTL (prevent hardcoded midnight)
  - Mandatory test case "JWT sub not in public.users then deny" on every RLS policy change
  - Detect hardcoded strings in mobile source (must go via Moko Resources resource file)
  - Detect `ST_DWithin`/`ST_Distance` using `actual_location` in non-admin paths
  - Detect raw Redis keys without hash tag `{scope:<value>}`
  - Detect content input endpoints without a length guard
  - Detect GCP Secret Manager accesses that hardcode a secret name without going through the environment-aware `secretKey(env, name)` helper
  - Detect `UPDATE users SET username = ...` outside the signup flow and the single Premium username customization transaction (admin module exempted; annotation `// @allow-username-write: signup` or `// @allow-username-write: customization` required on legitimate writers)
  - Detect partial-index definitions with `NOW()` in the `WHERE` clause (PostgreSQL rejects non-immutable predicates)
  - Detect direct reads of `X-Forwarded-For` for client IP (must use the `clientIp` request-context value populated by the Cloudflare-aware middleware)
  - Detect direct `UPDATE users SET private_profile_opt_in = FALSE` outside the privacy flip worker and Settings flow (annotation `// @allow-privacy-write: worker` or `// @allow-privacy-write: user_settings`)
  - Detect INSERT into `admin_sessions` without populating `csrf_token_hash`
  - Detect admin-user FK definitions on operational tables missing `ON DELETE SET NULL`
- API versioning: `/api/v1/...` from the start
- Dependency update: Dependabot or Renovate
- AI coding assistant: Firebender, Augment Code, Claude Code

---

## Risk Register

### High Priority

| Risk | Impact | Mitigation |
|------|--------|------------|
| Username collision at scale | Degraded UX | Dataset 600×600 + 3-part fallback = 36M combinations + atomic UNIQUE constraint + retry + `reserved_usernames` pre-check |
| Slow spatial query in dense Jakarta | Missed response time target | Composite index + partial index, CTE batching mandatory in Phase 2, backup migration to Neon/Railway |
| Google Play Billing policy change | Revenue impact | Monitor changelog; Indonesia fee stable until Sep 2027 |
| iOS App Store rejection | Launch delay | Restore Purchases button, Sign in with Apple + S2S notifications, PrivacyInfo.xcprivacy finalized, compliance upfront |
| Supabase DB size cap | Budget + availability miss | Monitor in Month 3, disk upgrade (separate from compute add-on), snapshot compression |
| Cloudflare Images cost abuse | Budget miss | Hard limit + single variant + stricter lazy-load + anomaly detection + human-in-the-loop + feature flag kill switch |
| Attestation false-positive blocks legitimate user | Conversion loss | Manual review path via support, tolerance threshold in Remote Config + bypass whitelist for QA |
| Persistent attestation fail = abuse signal | Brute-force signup | `rejected_identifiers` insert with `attestation_persistent_fail` reason after N failures |
| Refresh token leak without reuse detection | Eternal account access | Family revocation on reuse + security event log + admin alert |
| Shadow ban filter leak via new query | Moderation defeat | Database view pattern + CI lint rule enforcement (includes `post_replies`) |
| Store pricing tier Rp9,900 unavailable | Revenue forecast mismatch | Pre-Phase 1 verification, fallback to closest tier, re-run forecast |
| Coordinate jitter reversibility attack | Premium user triangulation | HMAC-SHA256 with server secret JITTER_SECRET, not publicly derivable |
| RLS policy regression in future commits | Shadow ban leak or auth bypass | CI test case "JWT sub not in users table then deny" mandatory + regex guard tests |
| WIB stagger forgotten on new endpoint | Thundering herd returns | Centralize `computeTTLToNextReset` + lint rule |
| Post edit concurrency race | Duplicate audit rows + version corruption | UNIQUE `(post_id, edited_at)` temporal + `FOR UPDATE` lock |
| iOS FCM silent push unreliable | Missed chat notifications | Alert push + NSE for iOS, data-only for Android only |
| Supabase Realtime JWT rotation | Auth bypass or downtime | HS256 companion incident-only rotation, RS256 REST separate, migration to Third-Party Auth for scheduled rotation |
| 1:1 conversation slot race | >2 participants | Partial unique index + advisory lock serialize |
| CSAM scope miss (only CF CDN, not Images) | Legal + reputation | Serve via `img.nearyou.id` subdomain under CSAM-enabled zone; verify in Pre-Phase 1 |
| Under-18 user signs up without gate | UU PDP + platform policy violation | Mandatory DOB input at signup + 18+ age gate + DB CHECK constraint + `rejected_identifiers` anti-bypass |
| DOB-shop bypass (under-18 user retries with different DOB) | Under-18 user slips through | `rejected_identifiers` blocklist check pre-insert; only identifier hash stored, no DOB |
| Backup restore re-introduces deleted PII | UU PDP breach | Append-only deletion log R2 + post-restore reconciliation mandatory |
| `openssl enc` streams AES-GCM unsafely | Backup corruption or unverifiable integrity | Use `age` CLI (ChaCha20-Poly1305 AEAD) for stream encryption; `age` keypair managed in GCP Secret Manager |
| CSAM archive metadata plaintext | PII leak if DB compromised | AES-256-GCM with `csam-archive-aes-key` from GCP Secret Manager; decrypt only via admin panel helper + audit log |
| Redis Pub/Sub message loss | Unreliable chat post-swap | Redis Streams with consumer group + XAUTOCLAIM, not Pub/Sub |
| RevenueCat webhook forgery | Free user granted Premium | Bearer token + HMAC signature + idempotency via `revenuecat_event_id` |
| Backup pg_dump plaintext exposure | PII leak if R2 creds compromised | `age` stream encryption at the backup container before upload |
| Missing PrivacyInfo.xcprivacy | App Store rejection | Pre-Phase 1 draft + Phase 3 finalize + validator test Pre-Launch |
| Analytics tracking without consent | UU PDP violation | Consent screen at onboarding + `analytics_consent` schema + suppress wrapper |
| FCM token refresh not registered | Push fails silently | Client SDK callback calls server endpoint + cleanup worker (404/410 immediate + stale weekly) |
| Suspension not lifted at expiry | Legitimate user permanently locked out | Daily `/internal/unban-worker` flips `is_banned = FALSE` + nulls `suspended_until`; permanent bans untouched |
| `subscription_status` drift to invalid state | Entitlement bug | CHECK constraint on the 3-state enum + webhook handler enforces transition rules |
| Feature flag misflip at runtime | Production regression | Admin rate limit 5/hour + audit log + dogfood rehearsal for Month 6 |
| Block feature leak via direct query | Harassment persists | CI lint rule + repository layer enforcement + symmetric test (includes `post_replies`) |
| Auto-hide reporter sybil attack | Legitimate content hidden by 3 sock accounts | Account-age filter (>7 days) + unique reporter per target + admin review gate before ban |
| Admin credentials shared with main API | Blast-radius multiplication on leak | Scoped `admin_app` role + separate connection string in GCP Secret Manager |

### Medium Priority

| Risk | Impact | Mitigation |
|------|--------|------------|
| AdMob approval delay | Revenue delay | Submit right after soft launch, plan revenue without ads |
| Google/Apple Sign-In edge cases | Conversion loss | Thorough testing, contextual fallback error flow |
| WebSocket connection storm | Backend crash | Rate limit + circuit breaker in Ktor |
| Cloud Run cold start | First request slow | Monitor P99, min-instance post-swap |
| Supabase JWT secret leak | Auth bypass | Incident rotation runbook, TTL 1 hour, anomaly detection |
| Cross-cloud latency misses p95 target | Degraded UX | Plan B Day 1 (CTE batching mandatory, Neon/Railway migration scaffolded) |
| Admin account compromise via TOTP phishing | Mass abuse | WebAuthn mandatory before second admin, YubiKey is trivially cheap |
| Play Integrity quota exceeded (>10k verdicts/day default) | Signup blocked | Pre-Phase 1 request for 100k/day quota increase, monitor, judicious use pattern |
| CF Images delivery escalation | Budget miss | Variant reduction + stricter lazy-load from Phase 4 |
| CF Images custom subdomain URL structure unverified | Delivery broken at launch | Pre-Phase 1 verification; fallback to standard `imagedelivery.net` hostname |
| Apple S2S endpoint downtime | Email bounce + stale Hide My Email | Apple retry limited, batch send re-check flag >1000 recipients, email change detection at sign-in |
| OTel vendor outage | Blind for benchmark + incident investigation | Fallback to Cloud Monitoring basic metrics |
| IAP setup blocker (no Workspace) | Admin panel deployment delay | Fallback Cloud Armor + VPN documented |
| Referral activity gate false negative | Legitimate invitee misses bonus | Support manual grant path via admin panel, rate-limited |
| Chat snapshot storage growth | DB size pressure | Compress JSONB >200 bytes (gzip), archive R2 >6 months |
| Sentry KMP SDK beta stability | Crash reporting gap | Monitor releases, fallback to Crashlytics Android + Sentry Cocoa iOS |
| Amplitude free tier exceeded during rapid growth | Analytics gap or cost spike | Migrate to paid tier or self-host PostHog |
| CSAM false positive blocks legitimate image | User trust impact | CF provides review unblock path, admin panel surfaces pending reviews |
| Broadcast mode message delivery failure rate | Message lost | Ktor retry 3x + client REST fallback for resync |
| CSAM 90-day retention conflicts with cascade delete | Evidence loss | `csam_detection_archive` table bypasses cascade, AES-256-GCM encrypted metadata |
| Redis Streams cost higher than estimate | Budget pressure | Aggressive XTRIM, batch XADD, re-benchmark in Month 12 |
| Resend deliverability issue (bounce) | Data export undelivered | Monitor Resend dashboard + Slack alert <95% delivery, SPF/DKIM/DMARC verified |
| Search query flood abuse | DB CPU pressure | 60/hour rate limit + feature flag kill switch + benchmark Phase 2 |
| Flyway migration fails in prod | Deploy blocked | Pre-deploy Cloud Run Job gate + Slack alert + manual rollback runbook |
| Notifications table growth unbounded | DB size pressure | 90-day auto-purge worker + partial index on unread only |
| `reserved_usernames` list gaps | Branding or impersonation risk | Admin editor with paginated view + filter by source + CSV bulk add + `seed_system` row immutability (UI + DB trigger) + quarterly review |
| Premium user abuses username change to impersonate another user | Harassment, brand impersonation | 30-day cooldown + 30-day release hold on old handles via `username_history` + profanity/UU ITE moderation check + admin `username_flagged` queue + `premium_username_customization_enabled` kill switch |
| Username change sub-microsecond race (two Premium users claim same handle simultaneously) | Data inconsistency or duplicate | `FOR UPDATE` lock in the change transaction + UNIQUE schema constraint + 409 CONFLICT retry prompt |
| Custom username collides with on-release-hold handle from a recent change | Impersonation if claim allowed prematurely | Pre-INSERT check against `username_history` WHERE `released_at > NOW()` at both signup and Premium change paths |
| Free user churn driven by 10/day like cap being too restrictive | Retention drop | Monitor D7/D30 like-engagement cohorts; `premium_like_cap_override` flag can loosen the Free cap server-side without a mobile release if needed |
| Staging secrets leak into production deployment | Cross-environment contamination, test data in prod | CI lint rule requiring `secretKey(env, name)` helper; Cloud Run service binds explicit `KTOR_ENV` env var; separate Cloud Run services; separate GCP Secret Manager `staging-*` vs prod namespace; separate Supabase projects |
| Staging Supabase Free auto-pause interrupts an active QA session | QA session interrupted mid-test | CI smoke ping on active sprint days to keep project warm; explicit wake-up command documented in staging runbook; accept occasional cold-start on quiet weekends as non-blocking |
| Admin panel reserved_usernames deletion wipes a critical system entry | Username squatting of `admin`, `support`, etc. | `source = 'seed_system'` rows blocked at the Admin UI AND at a DB trigger (belt-and-suspenders); removal audit-logged |
| Mobile build flavor misconfiguration ships staging API URL to production listing | Production users hit QA backend | xcconfig/flavor verified in Pre-Launch checklist; release build smoke test checks resolved `API_BASE_URL` = `api.nearyou.id` |
| Partial index predicate with `NOW()` fails at `CREATE INDEX` | Flyway migration blocked + deploy halted | CI lint rule detects partial-index `NOW()`; `username_history_released_idx` and `csam_archive_expires_idx` use plain B-tree + runtime filter |
| CSAM trigger assumed as CF webhook but CF Tool emits no webhooks | CSAM events go unprocessed; legal + reputational risk | Documented admin-triggered handler (MVP) + CF Worker auto-forward (Phase 2+) + optional email-polling job; SOP in Admin Panel CSAM viewer |
| Chat message inserted with NULL content AND NULL embed | Empty conversation bubbles | `CHECK (content OR embedded_post_id OR embedded_post_snapshot IS NOT NULL)` at schema + application-layer guard |
| Embedded post_edit purged leaving dangling FK in chat_messages | Query errors on embed render | `embedded_post_edit_id ... ON DELETE SET NULL`; snapshot-only render fallback |
| post_replies auto-hide flag missing from schema | Reports against replies silently no-op | `is_auto_hidden BOOLEAN` added to `post_replies` schema; reports auto-hide trigger now writes it |
| `X-Forwarded-For` spoofed by direct hit to `*.run.app` bypassing Cloudflare | Rate-limit evasion + audit log falsified | Cloud Run ingress allow-only-CF + Cloud Armor CF-range allowlist; middleware uses `CF-Connecting-IP`; CI lint forbids raw XFF reads |
| Redis key built without hash tag breaks multi-key ops on Upstash cluster | Lua/MULTI errors at scale | Hash-tag format standardized (`rate:guest_issue_global:{global:1}` etc.); CI lint rejects raw keys; `stream:conv:{conv:<id>}` aligned across Architecture + Implementation |
| Invite-code enumeration reveals who invited whom | Privacy leak | Invite codes are HMAC-derived from `user_id` with server-only `invite-code-secret`; non-enumerable; signup-time resolution failures silent to the invitee |
| `reserved_usernames` seed row accidentally modified via direct SQL | Critical handles like `admin` / `akun_dihapus` squatted | DB trigger `reserved_usernames_protect_seed` blocks UPDATE/DELETE on `source = 'seed_system'` rows; belt-and-suspenders with Admin UI guard |
| `reports.reporter_id` NOT NULL with ON DELETE SET NULL | Cascade failure on user hard-delete; blocks tombstone worker | Schema migration sets `ON DELETE CASCADE` on reporter_id; `reviewed_by` SET NULL preserves moderation history through admin churn |
| Privacy flip 72h grace window with no deadline tracking | UX promise unenforced or applied inconsistently across replicas | `users.privacy_flip_scheduled_at` column + idempotent RevenueCat webhook trigger (COALESCE guard) + hourly `/internal/privacy-flip-worker` + re-subscribe cancellation path + partial index for scan efficiency |
| Apple `account-delete` waits for daily worker | UP TO 24h delay on Apple-mandated immediate deletion | Synchronous tombstone+cascade enqueue in S2S handler before responding 200 to Apple; `deletion_requests_immediate_idx` backstop + `source = 'apple_s2s_account_delete'` semantics distinct from `consent-revoked` |
| `notifications.type` CHECK missing new event types | Inserts fail for `chat_message_redacted` / `privacy_flip_warning` etc. | Enum expanded to 13 types with full catalog table documenting trigger, actor, and `body_data` shape |
| `chat_messages.redacted_at` without `redacted_by` | Admin accountability lost on redaction | `redacted_by UUID REFERENCES admin_users(id) ON DELETE SET NULL` added with atomicity CHECK coupling the two columns |
| CSAM archive duplicates on handler re-trigger | Double-archive, split audit trail, confused Kominfo filing | UNIQUE on `image_hash` + partial UNIQUE on `cf_match_id` + ON CONFLICT DO UPDATE enrichment + `source` column tracking which path canonically wrote the row |
| Admin session cookie format / CSRF unspecified | Session fixation, CSRF exploit, unclear rotation | `__Host-admin_session` opaque token (SHA256 at rest) + `csrf_token_hash` column + `X-CSRF-Token` header verification + rotation on role escalation + cookie attributes Secure/HttpOnly/SameSite=Strict/Path=/ |
| WebAuthn ceremony challenge replay | Authentication bypass via replayed attestation | `admin_webauthn_challenges` table with 5-min TTL + `consumed_at` guard + weekly cleanup for stale unused challenges |
| Invite code O(N) scan at signup | Latency grows linearly with user base; eventual signup timeout | `users.invite_code_prefix VARCHAR(8) NOT NULL UNIQUE` populated at insert time; resolution becomes O(1) index scan with documented collision retry path |
| Content moderation fallback silently skipped on Remote Config outage | Profanity/UU ITE filter degraded without alert | 4-step `ModerationListLoader` with explicit fallback order (Redis cache → Remote Config → repo file → Secret Manager slot) + Sentry WARN on each fallback step + circuit breaker counter + integration test covering each tier |
| `UPDATE users SET username` lint cannot distinguish signup from violation | False positives block legitimate signup path | Annotation convention `// @allow-username-write: signup` recognized by the lint rule; alternative paths require explicit justification comment or fail CI |
| `admin_users` FK hard-delete on operational tables orphans historical context | Admin churn erases `reviewed_by` / `resolved_by` / `redacted_by` attribution | All FKs to `admin_users(id)` from operational tables use `ON DELETE SET NULL` so records persist with null attribution after admin deletion (rare but possible) |

### Acceptable Risks

| Risk | Rationale |
|------|-----------|
| Cloud / managed service lock-in | Migration path via abstraction, standard Postgres portability |
| DIY auth complexity | Oka's profile can handle it, AI-assisted development |
| Single region deployment | MVP scope, multi-region post-scale |
| Token revocation latency max 5 minutes (cache TTL) | Matches industry norm, not destructive in window |
| Banned user can read chat for max 1 hour (Supabase JWT TTL) | Write rejected instantly via REST, read is non-destructive |
| Shadow ban leak surfaces multi-device | Sophisticated adversary will detect in 24-48 hours. Friction + time buy, not invisible shield. |
| Coordinate jitter precision loss (~500m) | Acceptable for social app, 5km floor dominant anyway |
| Referral activity gate false negative edge case | Manual grant path supported, acceptable vs abuse prevention ROI |
| Chat messages retained after sender tombstone | UX continuity for other participant > strict cascade, PII already nulled via tombstone |
| No account linking (Google + Apple = 2 accounts) | MVP simplicity + identifier ban stickiness; re-evaluate post-MVP if user volume |
| No nested reply threading | Flat structure sufficient for MVP, reduces UI complexity |
| No E2E chat encryption | Moderation access required per business model; disclosed upfront in the Privacy Policy |
| Search limited to simple tsvector (no Indonesian stopwords) | Upgrade path to custom dictionary in Month 6+; acceptable MVP coverage |
| `rejected_identifiers` retained indefinitely (hash only) | Anti-abuse > data minimization; only the identifier hash is stored, no DOB/email/name. Legitimate adult re-verification path available via support. |

---

## Open Decisions (Defer Post-MVP)

These items have documented defaults; finalize per listed trigger.

### 1. Expansion to Second+ City

Quantitative criteria beyond the density threshold are not final. Finalize closer to Month 6 based on soft launch data.

### 2. Content Moderation Appeal Process

Appeal workflow is designed alongside the Admin Panel build in Phase 3.5. Minimum: a form in Settings to submit an appeal + workflow in the Admin Panel for review. Users banned or suspended can submit; `is_shadow_banned` users cannot (by design, as visibility of the appeal form would confirm the state).

### 3. Word-Pair Dataset (AI-Assisted)

Target 600×600 + 100 modifiers. Multi-pass:
- Pass 1: AI generation with structured prompt
- Pass 2: automated filter (length, chars, blacklist, collision with `reserved_usernames`)
- Pass 3: manual scan ~1.5 hours
- Pass 4: optional native speaker review
- Seed migration via Flyway
- Post-launch: blacklist update mechanism via Admin Panel

Budget: 3-4 days.

### 4. Kabupaten/Kota Polygon Dataset

~500 kabupaten/kota GeoJSON. BPS (public domain / CC-BY) primary or OpenStreetMap (ODbL, attribution required). Pre-Phase 1 decision.

### 5. Post-MVP Feature Expansion

Out of scope: story/ephemeral, group chat, voice note, video post, event/meetup, account linking/merge, nested reply threading, mention/hashtag.

### 6. Data Export SLA

Spec: 7 days. Confirm with legal advisor before launch.

### 7. DPO External Engagement

DPO-as-a-service vendor in Indonesia when scale is significant.

### 8. Attestation Fallback for Legitimate Users

Manual review path for custom ROM, LineageOS non-root, older device without HW attestation. Form + admin workflow in Phase 3.5. After N persistent failures the identifier is added to `rejected_identifiers` with reason `attestation_persistent_fail`; legitimate-user unlock path is the support form.

### 9. Secondary Admin Hire Criteria

When to hire? Candidates: report queue backlog >100 pending for 3 consecutive days, or admin ops time >20 hours/week consistently for 1 month.

### 10. UU ITE Wordlist Review Cadence

Quarterly with legal advisor, or on-demand when regulations update. Finalize in Pre-Phase 1.

### 11. Swap Commitment at Month 15

Lock now or re-evaluate with Month 12 data. Default: plan Month 14, re-confirm in Month 12 with actual cost metrics.

### 12. OTel Vendor Final Decision

Grafana Cloud (recommended default) vs Honeycomb vs Cloud Trace. Pre-Phase 1 decision.

### 13. IAP vs Cloud Armor Admin Panel

IAP preferred (free, Google-managed), can allowlist individual Gmail accounts without requiring a Workspace domain. Cloud Armor + VPN fallback if the workflow doesn't fit. Pre-Phase 1 decision.

### 14. Chat Snapshot Compression Threshold

Gzip >200 bytes from the start, or wait for DB size alert at 60%. Default: implement gzip in Phase 4, monitor growth.

### 15. Referral Manual Grant Scope in Phase 3.5

Include in Phase 3.5 or defer post-launch based on support ticket volume. Default: include a minimal path in Phase 3.5 (critical for user trust).

### 16. Broadcast Mode Performance at Scale

Re-confirm in Month 3 with production data whether Broadcast mode is actually cheaper than Postgres Changes. If not, revert or push the swap to Month 12.

### 17. Sentry KMP Stability

Monitor KMP SDK releases. If there's a major issue, fallback to Crashlytics Android + Sentry Cocoa iOS (dual pipeline).

### 18. Amplitude Tier Migration Threshold

At what MAU do we migrate to the paid tier? Default: 100k MAU. Monitor actual pricing.

### 19. Third-Party Auth Migration Timing

Lock the MAU threshold for migrating to Supabase Third-Party Auth. Default: 10k MAU or when the security posture requirement rises (e.g. scheduled quarterly rotation becomes a compliance need). With RS256 + JWKS already in place from Phase 1, migration is trivial (2-3 days).

### 20. JITTER_SECRET Rotation Strategy

Long-lived secret by design. Rotation requires re-fuzzing the entire posts table (Cloud Run Jobs batch). Trigger rotation only if compromise is detected. Document the incident runbook in Pre-Phase 1.

### 21. Redis Streams vs Self-Hosted Redis Post-Scale

At >100k MAU, Upstash Streams cost can be significant. Evaluate migrating to self-hosted Redis on Cloud Run Jobs (containerized). Re-evaluate in Month 20.

### 22. Search Upgrade Path

Default: PostgreSQL `simple` tsvector for MVP. Upgrade trigger: MAU >10k or search quality complaint volume. Options: custom Indonesian dictionary in Postgres, Meilisearch self-host, Typesense. Re-evaluate in Month 6.

### 23. Email Provider Migration

Default: Resend free tier up to ~3k/month (Months 12-13). Upgrade to Resend Pro. Alternative trigger: deliverability issue, swap to Amazon SES or Postmark. Re-evaluate in Month 10.

### 24. Account Linking (Google + Apple)

Deferred from MVP. Re-evaluate if user support volume for "why do I have 2 accounts" is significant (>5% of tickets). Implementation complexity: identifier ban sticky semantics + re-balance.

### 25. `rejected_identifiers` Adult Re-Verification Path

Edge case: a user who declared an under-18 DOB mistakenly (e.g. typo) later re-attempts as a legitimate adult. MVP path: support form + manual admin clear via the Rejected Identifiers viewer. Post-MVP: consider an automated re-verification flow (ID document or Apple Declared Age Range cross-check).

### 26. Auto-Hide Threshold Tuning

Default: 3 unique reporters (accounts >7 days old). Trigger-tune based on false-positive rate from admin review. If >20% of auto-hidden posts are restored on review, raise the threshold to 5 reporters or add a trust score component.

### 27. Premium Username Customization Abuse Monitoring

Default: 30-day cooldown + 30-day release hold + profanity/UU ITE filter. Monitor `username_flagged` queue volume + support ticket rate for impersonation complaints. Re-tune cooldown length (options: tighten to 60 days, loosen to 14 days) based on Month 3-6 data. Kill switch via `premium_username_customization_enabled` flag if abuse pattern emerges.

### 28. Free-Tier Like Cap (10/day) Validation

Default: 10 likes/day Free. Monitor D7 and D30 retention cohorts for like-engaged users vs like-restricted users. If retention gap is significant (>5% at D30), loosen to 20/day or 30/day via `premium_like_cap_override` Remote Config flag (no mobile release required). Re-evaluate in Month 3.

### 29. Staging Environment Sizing

Default: Supabase Free (accept 7-day idle auto-pause) + Cloud Run scale-to-zero + Upstash Free + CF R2 Free. If QA velocity is blocked by wake-up latency during active sprints, upgrade staging Supabase to Pro at the smallest tier (~Rp400k/month). Trigger for upgrade: >2 QA sessions per week blocked by Supabase idle-pause for 3 consecutive weeks.

### 30. Mobile Build Flavor vs Runtime Config

Default: build-time flavors (Android) / xcconfig (iOS) that bake `API_BASE_URL` into the APK/IPA. Alternative: runtime config fetched from Firebase Remote Config on first launch. Tradeoff: build-time is safer (no runtime switch exploit surface) but requires separate App Distribution / TestFlight builds for staging. Stick with build-time for MVP.
