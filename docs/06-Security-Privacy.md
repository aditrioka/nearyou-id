# NearYouID - Security, Privacy & Compliance

Device attestation, content moderation, CSAM handling, privacy compliance (UU PDP), age gate (18+ only), shadow ban, analytics consent, and internal endpoint security.

---

## Age Gate (UU PDP Compliance, 18+ Only)

Registration is restricted to users aged 18 and above. This is enforced at the application layer (signup flow) and as a DB-level CHECK constraint on `users.date_of_birth`.

### Signup Policy

- **Mandatory date-of-birth declaration** at onboarding (not a "18+" checkbox)
- **<18**: Rejected. User-facing: "Platform ini hanya tersedia untuk pengguna usia 18 tahun ke atas." The hashed identifier is inserted into `rejected_identifiers` (reason = `age_under_18`) to block retry with a different DOB. No `users` row is created; DOB is NOT stored on rejection.
- **18+**: Normal flow, `users` row created with `date_of_birth` recorded.

### Under-18 Bypass Prevention (`rejected_identifiers`)

Schema in `05-Implementation.md`. The identifier hash + type is the only data retained for an under-18 rejection; no DOB, email, or personal data is kept. On every signup attempt the server pre-checks this table and returns the same user-facing message if a row is found (does not confirm the rejection reason to the user). This stops trivial DOB-shopping bypass without creating any under-18 data store.

### Verification

- Self-declared DOB + consistency check vs Google/Apple account birthday (if exposed via API)
- **Apple Declared Age Range API**: available on iOS 18+ (publicly shipped; not a future capability). Use for cross-check where the user has consented at the OS level.
- Google Play Families SDK (Android) if account is categorized as kid (signup rejected)
- DB-level backstop: `CHECK (date_of_birth <= CURRENT_DATE - INTERVAL '18 years')` on `users`

### Storage

- `users.date_of_birth DATE NOT NULL` (only stored for accepted 18+ users, for audit + consistency)
- DOB field access is restricted (admin only, audit logged)

---

## Analytics & Tracking Consent (UU PDP)

UU PDP articles 20-22: personal data processing requires explicit consent for non-essential purposes.

### Consent Categories

Stored in `users.analytics_consent JSONB`:

```json
{
  "analytics": false,
  "crash": true,
  "ads_personalization": false
}
```

- `analytics`: Amplitude, product analytics
- `crash`: Sentry crash + error reporting (essential, opt-out only)
- `ads_personalization`: AdMob personalized (also covered by UMP)

### Defaults

All three categories are prompted at the onboarding consent screen. Default OFF for `analytics` and `ads_personalization` (opt-in model). Default ON for `crash` (opt-out model, essential for bug fixes, but user can still decline).

### Enforcement

- Amplitude event tracking: check `analytics_consent.analytics` before firing; silently suppress if FALSE
- Sentry: default ON for crash; if user declines, SDK `Sentry.close()` for their session (client-side), backend skips error enrichment with user_id
- AdMob: UMP SDK handles IAB TCF 2.2 consent + server-side check `ads_personalization`

### User Can Change Anytime

Settings > Privasi > "Pengaturan Data" (user-facing). Toggle change takes effect immediately for future events. Past events remain (immutable audit). A user wanting deletion of past analytics data uses the "Hapus Akun" flow.

---

## Device Attestation

**Mandatory at registration + sensitive operations**:

- Android: Play Integrity API (Classic for signup, Standard for frequent ops), verify token via Google public key
- iOS: App Attest, verify attestation + assertion via Apple root cert
- Reject signup if: emulator, rooted/jailbroken, debuggable, tampered APK/IPA, attestation signature invalid

### Play Integrity Quota Management

- Default quota 10,000 verdicts/day, Google-enforced
- Pre-Phase 1: submit formal quota increase request (linked Cloud project, target 100k/day, processing up to 1 week)
- Judicious use: Classic only for signup + sensitive ops (account delete, session kick). Standard for frequent attestation (post creation <7 days old account). Cached verdict TTL 1 hour in Redis for regular authenticated requests.
- Cache bypass only when a high-risk signal is detected (IP jump, anomaly)
- Monitor daily quota; budget for paid tier in the cost model when MAU >50k and verdicts approach 100k/day

### Attestation Bypass Infrastructure (QA team + beta tester)

- Firebase Remote Config key `attestation_mode`: `enforce` | `warn` | `off`
- Whitelist `attestation_bypass_google_ids_sha256`: list of SHA256 Google ID for QA accounts
- Android build variant: `production` (enforce), `qa` (off default), `debug` (off)
- iOS build config: production vs internal test (TestFlight internal)
- Audit log every bypass event for traceability

### Attestation False-Positive Fallback

Users with a legitimate device but failed attestation (custom ROM, LineageOS non-root, older HW) can request manual review via support form. Tolerance threshold configurable via Remote Config. After N persistent attestation failures on the same identifier across different sessions, the server inserts the identifier into `rejected_identifiers` (reason = `attestation_persistent_fail`) to avoid attestation-bypass brute force.

---

## Anti-Spam Strategy (Defense-in-Depth)

**Mandatory layers**:

1. **Device attestation mandatory** at registration + sensitive operations. Reject emulator/rooted/tampered/invalid signature. Bypass whitelist via Remote Config for QA accounts.
2. **Per-identifier signup rate limit**:
   - Max 3 signup attempts per Google/Apple ID hash per 24 hours
   - Attestation fail + signup attempt from the same identifier then permanent block via `rejected_identifiers`
3. **Behavioral flag for new accounts (<7 days)**:
   - Rate limit 50% of normal on write operations
   - Re-check attestation at post creation (lightweight assertion)
4. **One identifier = 1 active account**. Ban is sticky at the identifier level. Google + Apple = 2 separate identifiers, 2 separate accounts (no linking by design).
5. **Shadow ban capability** (see the Shadow Ban section).
6. **Device fingerprint: best-effort correlation signal, NOT primary defense**.

---

## Account Security

- Input validation on every endpoint (length limits: post 280, reply 280, chat 2000, bio 160, display_name 50, username schema 60 / Premium custom 30)
- HTTPS enforced in production for REST; WSS/TLS enforced for Supabase Realtime
- Google/Apple ID hashed in logs
- Device fingerprinting (correlation signal only)
- Attestation verification at sensitive operations

### Account Recovery: Intentionally None

Losing your Google/Apple account means losing your NearYouID account. By design. Disclosed in onboarding + FAQ explicitly. A user-facing "Hapus Akun" button lives in Settings.

### Account Linking Policy (MVP)

There is no merging/linking between Google and Apple accounts. A user who has both will have 2 separate NearYouID accounts. Tradeoff: simplicity + identifier ban stickiness outweigh convenience. Re-evaluate post-MVP if user request volume is significant.

### Apple S2S Notification Handling

Two distinct Apple-originated account deletion flows, each with its own `deletion_requests.source` value:

- **`consent-revoked`** (user revoked Sign in with Apple): treated as a standard user-initiated deletion intent. Handler inserts `deletion_requests` row with `source = 'apple_s2s_consent_revoked'` and `scheduled_hard_delete_at = NOW() + 30 days`. User can cancel during the grace window like a normal deletion. Sessions are kicked immediately (`token_version++`).
- **`account-delete`** (user deleted their Apple ID entirely): Apple-required immediate action, no grace. Handler inserts `deletion_requests` row with `source = 'apple_s2s_account_delete'` and `scheduled_hard_delete_at = NOW()`, AND synchronously enqueues a one-shot tombstone+cascade job before responding 200 to Apple. The daily worker backstops any synchronous failure via `deletion_requests_immediate_idx`. This flow cannot be cancelled.

Additional events handled by the same endpoint (no deletion-request row; relay-email state mutation only):

- `email-disabled` / `email-enabled`: update `users.apple_relay_email` flag; insert `notifications` row with `type = 'apple_relay_email_changed'`.

Both flows land at `POST /internal/apple/s2s-notifications` (OIDC-exempt, Apple JWT signature verified against Apple JWKS, `aud` claim = bundle ID, dedup via `transaction_id`). See `05-Implementation.md` Apple Sign-In Specifics for handler verification and the deletion-request schema source semantics.

---

## Content Moderation

### Text Moderation (Multi-Layer)

1. **Manual keyword blocklist**: profanity, slurs, scam patterns
2. **UU ITE content categories**: SARA (suku/agama/ras/antargolongan), defamation, incitement patterns
   - Indonesian-specific wordlist, AI + manual review (Pre-Phase 1 budget 1 day)
   - Higher threshold: 1 match = soft flag to the moderation queue (not auto-hide)
   - Quarterly review cadence with legal advisor
3. **Google Perspective API (dev Phase 2 stopgap)**:
   - Free tier: 1 QPS, adequate for dev Phase 2 volume
   - Attributes: `TOXICITY`, `SEVERE_TOXICITY`, `IDENTITY_ATTACK`, `THREAT`
   - Score >0.8 = auto-hide (`posts.is_auto_hidden = TRUE`) + queue to `moderation_queue`
   - Score 0.6-0.8 = flag to `moderation_queue` only
   - ID language: partial support (mixed ID/EN), accept imperfection in stopgap
   - Feature flag `perspective_api_enabled` for kill switch
4. **Month 6+ scope (if MAU >10k)**: dedicated ID-language moderation (Meta XLM-R open model self-host, or Hive Moderation paid)

> Keyword list storage, hot-reload mechanism, and matching engine: see `05-Implementation.md` "Content Moderation Keyword Lists". Profanity list is editable by admins via the Admin Panel with audit-logged changes; UU ITE list is reviewed quarterly with the legal advisor.

### Endpoint Flow

```
POST /api/v1/post
→ length validation (280 chars max)
→ keyword blocklist (sync)
→ UU ITE category check (sync)
→ Perspective API (async, 500ms timeout, fail-open)
→ Insert post
→ If flagged: set is_auto_hidden = TRUE + insert moderation_queue row (visible to author, hidden from timeline until reviewed)
```

### Legal Documentation

- RoPA includes moderation decision data retention for 1 year
- Yearly transparency report (post-Month 12): stats on removals by category

### Premium Username Customization Moderation

When a Premium user attempts to change their username via `PATCH /api/v1/user/username`, the server runs the same text moderation pipeline (profanity blocklist + UU ITE keyword match) against the candidate handle. On hit:

- The change is REJECTED upfront (the user sees a rejection message and can pick another)
- A `moderation_queue` row is inserted with `trigger = 'username_flagged'` for admin awareness (potential pattern signal)
- Repeated flagged attempts from the same user (>3 in 24 hours) raise the user's anomaly score

Admin can explicitly allow a borderline candidate via an override action if context warrants (e.g. a legitimate Indonesian word that matches the UU ITE list in an unrelated sense).

**Anti-impersonation (30-day release hold)**: when a user changes their username, the old handle is held in `username_history` for 30 days before another account can claim it. This prevents immediate impersonation of someone who just changed handles. See `05-Implementation.md` for the `username_history` schema.

### Media Moderation (Month 6+ Image Launch)

**Cloudflare CSAM Scanning Tool** (non-negotiable, free):
- Enable via Dashboard: Caching > Configuration > CSAM Scanning Tool > toggle on + verify email
- No NCMEC credential required (available globally)
- Automatic scan on cached images, matched against NCMEC NGO + Industry lists
- On match: URL blocked (HTTP 451), daily email notification
- Cloudflare files third-party reports to NCMEC

**Important: Cloudflare CSAM Scanning Tool does NOT emit webhooks to custom endpoints.** The downstream `/internal/csam-webhook` handler must be invoked by one of the supported paths:

- **Primary (MVP)**: admin reviews the CF email notification and triggers the handler manually via the Admin Panel. The admin pastes the matched URL / image_id from the email; the handler executes the auto-action (hard-delete post, permanent ban + token_version bump, cascade other posts of the user, archive metadata with AES-256-GCM, queue Kominfo report).
- **Automated Phase 2+**: a Cloudflare Worker attached to the `img.nearyou.id` route watches for `451` responses and POSTs to `/internal/csam-webhook` with a signed payload. The signature is verified with a dedicated CF-Worker-secret; see the Internal Endpoint Security section.
- **Alternative (deferred)**: daily Cloud Run Job that parses the inbound email via IMAP or the email provider API.

**Google Cloud Vision Safe Search** (explicit content upfront):
- Pay-per-image, scan synchronous at upload
- Block adult/violent/racy score >0.8 upfront before the image enters the cache
- Complementary to CSAM (different category: general explicit vs known child abuse hash)

> Upload flow + CSAM webhook handler: see `02-Product.md` (product flow) and `05-Implementation.md` (archive schema + CF IP extraction). Full architecture trigger paths: `04-Architecture.md`.

### Kominfo Reporting Obligation (Indonesia)

- Cloudflare auto-files to NCMEC (US clearing house)
- Oka must report to Kominfo (Ditjen Aptika) within <24 hours of detection
- SOP step in the Admin Panel:
  1. CSAM webhook triggered then admin notification (in-app + Resend email)
  2. Admin reviews metadata (hash + timestamp + user_id_hash, NOT the content itself)
  3. File to Kominfo via official form or email contact point at Ditjen Aptika
  4. Log `kominfo_report_id` to the archive
- Polri Siber is optional but recommended for severe cases; documented in the SOP

### CSAM Archive Purge Worker

Cloud Scheduler daily checks `WHERE expires_at < NOW() AND kominfo_reported_at IS NOT NULL`, deletes rows. Preservation is extended if investigation is active.

### CSAM Archive Encryption

Archive row encrypts metadata (`encrypted_metadata BYTEA`) with AES-256-GCM using the `csam-archive-aes-key` from GCP Secret Manager. Access restricted to the admin role; decryption happens only via the admin panel service account + audit log entry. Image hash + matched NCMEC reference are stored in plaintext columns (needed for Kominfo filing), but no image bytes are ever retained.

---

## Report System

- One-tap report from a post, reply, profile, and chat message
- Recorded in the `reports` table (see `05-Implementation.md`)
- Auto-hide: 3 unique reporters (accounts >7 days old) then `is_auto_hidden = TRUE` pre-review + `moderation_queue` row
- Shadow ban capability
- Ban is sticky via Google/Apple ID

---

## Block User (Privacy + Anti-Harassment)

Users can block other users from the profile or a post context menu. Effect is bidirectional invisibility:
- Blocker and blocked cannot see each other's posts or profiles
- Blocker and blocked cannot initiate new DMs with each other (existing history preserved)
- Follow relationships automatically removed in both directions on block

Block differs from shadow ban: block = user-initiated, with visible friction (user-facing "Pengguna ini diblokir"), symmetric. Shadow ban = admin-initiated, invisible to the banned user.

> Schema + query rules + CI lint: see `05-Implementation.md` (Block User Implementation).

---

## Shadow Ban

Principle: **all actions succeed from the banned user's perspective, invisible to others**. High-friction layer, not an invisible shield (a sophisticated adversary will detect within 24-48 hours).

> Database view implementation + CI lint: see `05-Implementation.md`.

### Known Leak Surfaces (Accepted Risk, Documented)

Shadow ban is not 100% invisible across multiple devices:

| Surface | Leak mechanism | Mitigation |
|---------|----------------|------------|
| Like counter cross-device | Device A shows +1, Device B shows real count | Client caches like state per-user-per-post TTL 5 min. Stale-consistent 5 min is acceptable. |
| Reply visible on device A, missing on device B | Thread fetch on device B returns the canonical list | Client optimistic reply injection, no re-fetch for 30 min. Force refresh then reply "disappears". |
| Follow count on own profile | Device A shows followed=true, device B fresh followed=false | Same 5-minute client cache per follow relationship. |
| Chat "delivered" status | Other party not replying = suspicious | Accepted. Banned user eventually realizes via social signals. Shadow ban = friction + time-buy for admin, not eternal deception. |

### Phased Moderation

Early phase: manual review via Admin Panel. Add AI moderation when Premium media upload launches.

---

## Suspension vs Ban

Two distinct admin actions, same underlying columns:

- **7-day suspension**: `UPDATE users SET is_banned = TRUE, suspended_until = NOW() + INTERVAL '7 days', token_version = token_version + 1 WHERE id = :uid`. A daily worker (`/internal/unban-worker`) flips `is_banned = FALSE` and nulls `suspended_until` when the window elapses. See `05-Implementation.md`.
- **Permanent ban**: `UPDATE users SET is_banned = TRUE, suspended_until = NULL, token_version = token_version + 1 WHERE id = :uid`. No automatic unban.

In both cases, all active refresh tokens for the user are deleted, so all active sessions are kicked on the next REST call.

---

## Privacy Compliance (UU PDP Indonesia)

### Consent Flow

- Location data is sensitive; explicit consent required at onboarding
- Google/Apple ID is never exposed to other users
- Privacy policy live before public launch
- Chat admin-readable disclosure: explicit at onboarding + Privacy Policy
- Age gate + DOB declaration mandatory (18+ only)
- Analytics consent screen at onboarding (Amplitude + Sentry + AdMob)
- Third-party data processor disclosure in Privacy Policy: Resend (email), Amplitude (analytics), Sentry (error tracking), RevenueCat (subscription), Cloudflare (CDN + CSAM), Firebase (FCM + Remote Config), Google Play / Apple (auth + billing), Supabase (DB), Upstash (cache)

### Retention Policy

| Data | Retention |
|------|-----------|
| Active post + location | While the post exists |
| Soft-deleted post (author) | 30 days then hard delete |
| Post edit history | 1 year |
| Session trail | 90 days auto-purge |
| Location on app open | Not stored, request-only |
| "Hapus Akun" user account | 30-day grace then tombstone (PII null, messages retained with "Akun Dihapus" label) |
| Moderation action log | 1 year (audit & appeal) |
| Moderation queue (resolved rows) | 1 year |
| Reports (resolved) | 1 year |
| Notifications (in-app list) | 90 days auto-purge |
| Attestation verdict cache | 1 hour (Redis) |
| Refresh token family log | Until all family members expired |
| Chat `embedded_post_snapshot` | Indefinite (part of the conversation) |
| Chat messages | Indefinite (tombstone sender, retain content) |
| CSAM detection archive | 90+ days minimum, until Kominfo + investigation fulfilled |
| `rejected_identifiers` (under-18 bypass list) | Indefinite (anti-abuse, only identifier hash stored) |
| `username_history` entries | Indefinite (audit); the `released_at > NOW()` guard enforces a 30-day claim-block window for the old handle |
| Deletion log (R2) | 7 years (backup integrity) |
| Email sent via Resend | 30-day log retention in Resend dashboard |
| Amplitude event data | 5 years (default tier retention) |
| Sentry error data | 90 days (default tier) |

### Account Deletion (Tombstone Pattern)

Request is recorded in `deletion_requests` (see `05-Implementation.md`).

**Tombstone pattern for the user row**:
- `deleted_at` column set
- PII nulled: `display_name`, `bio`, `google_id_hash`, `apple_id_hash`, `device_fingerprint_hash`, `date_of_birth`, `email`
- Username replaced with `deleted_user_<uuid_prefix>`
- Profile endpoint: 404 or user-facing "Akun Dihapus" placeholder

**Cascade delete** (permanently gone on hard-delete):
- Session tokens, refresh tokens (all families)
- Location history (non-post)
- Google/Apple ID hash
- Follow relationships (both directions)
- FCM tokens
- User blocks (both directions)
- Notifications addressed to the user

**Anonymize/Tombstone** (remain, with sender/author becoming user-facing "Akun Dihapus"):
- Chat messages (preserved for the other participant's UX)
- Posts + location field
- Replies
- Likes (count remains accurate)
- Reports submitted by the user (audit integrity)
- Post edit history

**Rationale for chat tombstone over cascade**: It breaks UX if User A deleting their account wipes out the entire conversation for User B. Tombstone preserves context for the other party. Sender PII is still nulled (display name becomes "Akun Dihapus", avatar default). If the message content itself is problematic (third-party PII, doxxing), admin-triggered redaction via the `redacted_at` field (not default behavior).

**30-day grace period**: data intact, user can restore. After 30 days, the hard-delete worker executes and writes an entry to the deletion log.

### Data Export Scope Matrix

Endpoint `/account/export` returns JSON + CSV ZIP:

| Data Category | Included | Format | Notes |
|---------------|----------|--------|-------|
| User profile (name, bio, username) | Yes | JSON | Current state |
| Username change history (own) | Yes | CSV | From `username_history`: old_username + new_username + changed_at |
| Date of birth | Yes | JSON | Self-info |
| Google/Apple ID (hashed) | Yes | JSON | Self-reference only |
| Analytics consent history | Yes | JSON | Current state |
| Posts (active) | Yes | CSV | Includes actual_location, city_name, timestamp |
| Posts (soft-deleted in grace) | Yes | CSV | Marked `deleted_at` |
| Post edit history (own) | Yes | CSV | All versions chronological |
| Likes given | Yes | CSV | post_id + timestamp |
| Replies given | Yes | CSV | content + parent_post_id + timestamp |
| Follow list | Yes | CSV | user_id hash + timestamp |
| Block list | Yes | CSV | blocked_id hash + timestamp |
| Chat messages (sent + received) | Yes | CSV | conversation_id + content + timestamp + peer_id_hash |
| Reports submitted by the user | Yes | CSV | target_id_hash + reason + timestamp |
| Notifications received | Yes | CSV | type + target + timestamp + read state |
| Reports received about the user | No | - | Out of scope (affects third parties) |
| Moderation actions applied to the user | Yes | CSV | action_type + timestamp (admin_id omitted) |
| Session history (fingerprint, IP) | Yes | CSV | 90-day window only |
| Premium subscription history | Yes | CSV | tier + start/end + source (paid/referral) |
| Attestation verdicts (own device) | No | - | Internal security data |
| Admin audit log about the user | No | - | Security integrity |
| CSAM detection archive | No | - | Out of scope, legal preservation |
| `rejected_identifiers` hash | No | - | Anti-abuse signal, may cross other users |

**Delivery**: async worker packs a ZIP, uploads to R2, creates a signed URL TTL 24 hours, and emails via Resend (user-facing): "Data export kamu siap diunduh".

**SLA**: 7 days (confirm with legal advisor before launch).

### Infrastructure

- **Hard delete worker**: Cloud Scheduler calls `/internal/cleanup` daily (consolidated endpoint; reads `deletion_requests`)
- **Audit log table**: every hard delete is logged with timestamp, entity, and reason
- **Deletion log (R2)**: append-only JSONL objects, 7-year retention, input for post-restore reconciliation
- **Data export endpoint**: `/account/export`, async job packs JSON+CSV ZIP + Resend email delivery
- **Breach notification**: template + PDP Agency contact ready. Window to report: 3x24 hours mandatory per UU PDP
- **DPO**: appoint Oka himself in the solo phase, with RoPA documentation. External DPO-as-a-service when scale is significant
- **Suspension unban worker**: daily cron to flip time-bound suspensions back to active (see `05-Implementation.md`)

---

## Internal Endpoint Security

All internal scheduler endpoints served under `/internal/*` with mandatory OIDC middleware.

### Implementation

- Cloud Scheduler natively supports OIDC tokens
- Ktor middleware verifies the signature via Google JWKS + audience claim matching service URL
- Service account in GCP Secret Manager

### Covered Endpoints

- Hard delete worker (`/internal/cleanup`)
- Image lifecycle cleanup
- Session purge + refresh token cleanup
- Reverse geocoding cache warmup
- Apple S2S notifications (`/internal/apple/s2s-notifications`)
- CSAM webhook handler (`/internal/csam-webhook`)
- Granted entitlement activity gate check (daily)
- Subscription grace period downgrade worker (daily)
- CSAM archive purge worker (post-90-day)
- Suspension unban worker (`/internal/unban-worker`, daily)
- FCM token cleanup (weekly)
- Notifications purge (weekly, >90 days)
- Moderation queue / reports archival (weekly)
- Stream GC (post-swap, weekly)

**Exceptions to OIDC** (use alternative auth):
- RevenueCat webhook (`/internal/revenuecat-webhook`): Bearer token + HMAC signature (vendor doesn't support OIDC). See `05-Implementation.md`.
- CSAM webhook handler (`/internal/csam-webhook`): invoked via two supported paths, both non-OIDC.
  - **Admin-triggered (MVP)**: the Admin Panel calls the handler internally using the admin's scoped session + a session-bound CSRF-style token. Since both services share the cluster network, the call never leaves the trust boundary.
  - **Cloudflare Worker forwarding (Phase 2+)**: the CF Worker signs its POST with a Bearer token pulled from a Worker secret and an HMAC-SHA256 body signature (key in GCP Secret Manager as `cf-worker-csam-secret`). The Ktor handler verifies both before processing. Rate-limited to 100 req/hour per IP (prevents replay amplification).

**Backup NOT via `/internal/*` endpoint**: backup runs as a standalone Cloud Run Jobs container, not an HTTP endpoint.

**Health check endpoints** (`/health/live`, `/health/ready`) are PUBLIC (no auth) but rate-limited, intentionally not under `/internal/*`.

**Defense in depth**: network-level (GCP IAM Cloud Scheduler-only invoke) + token-level (OIDC verify origin).

---

## iOS App Privacy Manifest

File: `iosApp/PrivacyInfo.xcprivacy` (required since iOS 17 per Apple; app rejection risk if missing).

Declare:
- `NSPrivacyCollectedDataTypes`: precise location (App Functionality), user ID (App Functionality + Analytics if opt-in), crash data (App Functionality), email (Account Management), purchase history (App Functionality)
- `NSPrivacyAccessedAPITypes` with Required Reasons:
  - `NSUserDefaults` (reason `CA92.1`)
  - `FileTimestamp` (reason `C617.1`)
- `NSPrivacyTracking`: TRUE if AdMob is active + user opts in to ads personalization
- `NSPrivacyTrackingDomains`: AdMob + analytics domains if tracking is TRUE

Pre-Phase 1 task: generate manifest from the third-party SDK list (Sentry, Amplitude, RevenueCat, FCM, AdMob) + merge with app-specific declarations.
