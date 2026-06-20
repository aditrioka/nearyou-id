# NearYouID - Security, Privacy & Compliance

Device attestation, content moderation, CSAM handling, privacy compliance (UU PDP), age gate (18+ only), shadow ban, analytics consent, and internal endpoint security.

---

## Age Gate (UU PDP Compliance, 18+ Only)

18+ only; enforced in the signup flow and by a DB CHECK constraint on `users.date_of_birth`.

### Signup Policy

- **Mandatory date-of-birth declaration** at onboarding (not an "18+" checkbox)
- **<18**: rejected — user-facing: "Platform ini hanya tersedia untuk pengguna usia 18 tahun ke atas." The hashed identifier is inserted into `rejected_identifiers` (reason = `age_under_18`); no `users` row is created and the DOB is NOT stored.
- **18+**: normal flow, `users` row created with `date_of_birth` recorded.

### Under-18 Bypass Prevention (`rejected_identifiers`)

Schema: `05-Implementation.md`. An under-18 rejection retains only the identifier hash + type — no DOB, email, or personal data. Every signup attempt pre-checks this table; a hit returns the same user-facing message without confirming the rejection reason. This blocks trivial DOB-shopping retries without creating any under-18 data store.

### Verification

- Self-declared DOB + consistency check vs Google/Apple account birthday (if exposed via API)
- **Apple Declared Age Range API**: available on iOS 18+ (publicly shipped, not a future capability); use as cross-check where the user consented at the OS level
- Google Play Families SDK (Android): account categorized as kid → signup rejected
- DB backstop: `CHECK (date_of_birth <= CURRENT_DATE - INTERVAL '18 years')` on `users`

### Storage

- `users.date_of_birth DATE NOT NULL` (stored only for accepted 18+ users; audit + consistency)
- DOB access restricted (admin only, audit logged)

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
- `crash`: Sentry crash + error reporting
- `ads_personalization`: AdMob personalized (also covered by UMP)

### Defaults

All three are prompted at the onboarding consent screen; `analytics` + `ads_personalization` default OFF (opt-in model), `crash` defaults ON (opt-out model — essential for bug fixes, still declinable).

### Enforcement

- Amplitude: check `analytics_consent.analytics` before firing; silently suppress if FALSE
- Sentry: on decline, client-side `Sentry.close()` for the session; backend skips error enrichment with user_id
- AdMob: UMP SDK handles IAB TCF 2.2 consent + server-side `ads_personalization` check

### User Can Change Anytime

Settings > Privasi > "Pengaturan Data" (user-facing). Toggles apply immediately to future events; past events remain (immutable audit) — deleting past analytics data requires the "Hapus Akun" flow.

---

## Device Attestation — DESIGN

> **Status: DESIGN — no code today.** `:infra:attestation` is unscaffolded, signup does not invoke Play Integrity / App Attest verification, and the `attestation_mode` / `attestation_bypass_google_ids_sha256` Remote Config keys have no consumer in `SignupService.kt` yet. Intended posture for when attestation work begins (post-MVP per `docs/08-Roadmap-Risk.md`).

**Mandatory at registration + sensitive operations**:

- Android: Play Integrity API (Classic for signup, Standard for frequent ops), token verified via Google public key
- iOS: App Attest, attestation + assertion verified via Apple root cert
- Reject signup if: emulator, rooted/jailbroken, debuggable, tampered APK/IPA, invalid attestation signature

### Play Integrity Quota Management

- Default quota 10,000 verdicts/day, Google-enforced
- Pre-Phase 1: submit a formal quota increase request (linked Cloud project, target 100k/day, processing up to 1 week)
- Judicious use: Classic only for signup + sensitive ops (account delete, session kick); Standard for frequent attestation (post creation, account <7 days old); cached verdict TTL 1 hour in Redis for regular authenticated requests
- Cache bypass only on a high-risk signal (IP jump, anomaly)
- Monitor daily quota; budget the paid tier in the cost model when MAU >50k and verdicts approach 100k/day

### Attestation Bypass Infrastructure (QA team + beta tester)

- Firebase Remote Config `attestation_mode`: `enforce` | `warn` | `off`
- Whitelist `attestation_bypass_google_ids_sha256`: SHA256 Google IDs of QA accounts
- Android build variants: `production` (enforce), `qa` (off default), `debug` (off)
- iOS build config: production vs internal test (TestFlight internal)
- Every bypass event audit-logged for traceability

### Attestation False-Positive Fallback

Legitimate devices that fail attestation (custom ROM, LineageOS non-root, older HW) get manual review via the support form; the tolerance threshold is Remote Config-tunable. After N persistent failures on one identifier across different sessions, the server inserts it into `rejected_identifiers` (reason = `attestation_persistent_fail`) against attestation-bypass brute force.

---

## Anti-Spam Strategy (Defense-in-Depth)

**Mandatory layers**:

1. **Device attestation mandatory** at registration + sensitive operations (reject list, quotas, QA bypass whitelist: § Device Attestation — DESIGN).
2. **Per-identifier signup rate limit**: max 3 signup attempts per Google/Apple ID hash per 24 hours; attestation fail + signup attempt from the same identifier → permanent block via `rejected_identifiers`.
3. **Behavioral flag for new accounts (<7 days)**: write-operation rate limit 50% of normal; attestation re-checked at post creation (lightweight assertion).
4. **One identifier = 1 active account.** Ban is sticky at the identifier level; Google + Apple = 2 separate identifiers, 2 separate accounts (no linking by design).
5. **Shadow ban capability** (§ Shadow Ban).
6. **Device fingerprint: best-effort correlation signal, NOT primary defense.** The mobile `SignInRequest` / `RefreshRequest` bodies carry `device_fingerprint_hash` only once platform attestation (Play Integrity / App Attest) ships fingerprint generation; until then the field is omitted (spec-optional per `auth-signin`) and refresh-token rows persist `device_fingerprint_hash = NULL`. Lands with the mobile attestation integration ([`docs/08-Roadmap-Risk.md`](08-Roadmap-Risk.md) § Phase 3).

---

## Account Security

- Input validation on every endpoint. All content-length limits + rationale: `01-Business.md` § Content Length Limits.
- HTTPS enforced in production for REST; WSS/TLS for Supabase Realtime
- Google/Apple ID hashed in logs
- Device fingerprinting (correlation signal only)
- Attestation verification at sensitive operations

### Account Recovery: Intentionally None

Losing the Google/Apple account means losing the NearYouID account — by design, disclosed explicitly in onboarding + FAQ. A user-facing "Hapus Akun" button lives in Settings.

### Account Linking Policy (MVP)

No Google↔Apple merging/linking: a user with both has 2 separate NearYouID accounts. Tradeoff: simplicity + identifier ban stickiness outweigh convenience; re-evaluate post-MVP if user request volume is significant.

### Apple S2S Notification Handling

Two Apple-originated account deletion flows, distinguished by `deletion_requests.source`:

- **`consent-revoked`** (user revoked Sign in with Apple): standard user-initiated deletion intent — `deletion_requests` row with `source = 'apple_s2s_consent_revoked'`, `scheduled_hard_delete_at = NOW() + 30 days`; cancellable during the grace window like a normal deletion; sessions kicked immediately (`token_version++`).
- **`account-delete`** (user deleted their Apple ID entirely): Apple-required immediate action, no grace — row with `source = 'apple_s2s_account_delete'`, `scheduled_hard_delete_at = NOW()`, AND a one-shot tombstone+cascade job enqueued synchronously before responding 200 to Apple; the daily worker backstops synchronous failure via `deletion_requests_immediate_idx`. Cannot be cancelled.

The same endpoint also handles `email-disabled` / `email-enabled` (relay-email state mutation only, no deletion-request row): update the `users.apple_relay_email` flag + insert a `notifications` row with `type = 'apple_relay_email_changed'`.

All land at `POST /internal/apple/s2s-notifications` (OIDC-exempt; Apple JWT signature verified against Apple JWKS, `aud` claim = bundle ID, dedup via `transaction_id`). Handler verification + deletion-request source semantics: `05-Implementation.md` Apple Sign-In Specifics.

---

## Content Moderation

### Text Moderation (Multi-Layer)

1. **Manual keyword blocklist**: profanity, slurs, scam patterns
2. **UU ITE content categories**: SARA (suku/agama/ras/antargolongan), defamation, incitement patterns
   - Indonesian-specific wordlist, AI + manual review (Pre-Phase 1 budget 1 day)
   - Higher threshold: Remote Config-tunable `moderation_match_threshold` (default 3) → soft flag to the moderation queue (not auto-hide)
   - Quarterly review cadence with legal advisor
3. **OpenAI Moderation API (Layer 3 toxicity classifier)**:
   - Free tier: no per-call cost on the Moderation endpoint; OpenAI default rate limits apply (~1000 RPM for free-tier accounts)
   - **Vendor history**: the original capability spec (Phase 2 §16 plan) targeted Google Perspective API; mid-implementation Perspective announced sunset (end-of-2026, signups closed Feb 2026), so the vendor pivoted to OpenAI Moderation (`openspec/changes/archive/<timestamp>-text-moderation-perspective-api-layer/proposal.md` § Vendor Swap Amendment). Remote Config flag names + capability name + V9 SQL trigger string retain historical "perspective" branding (operator-facing or schema-fixed); the Kotlin code surface + Sentry events + Redis cache scope are vendor-agnostic (`layer3_*`).
   - Model: `omni-moderation-latest` (GPT-4o-based, multimodal-capable; we use text only)
   - Categories: `harassment`, `harassment/threatening`, `hate`, `hate/threatening`, `illicit`, `illicit/violent`, `self-harm`, `self-harm/intent`, `self-harm/instructions`, `sexual`, `sexual/minors`, `violence`, `violence/graphic` (13 categories, slash-separated subcategory names preserved as Map keys)
   - Scoring: `score = ModerationScore.maxScore() = max(categoryScores.values)` — the per-call max across all 13 categories. Threshold comparisons are STRICTLY greater-than: boundary value `0.80` falls in the FlagOnly band (NOT AutoHide); `score ≤ 0.6` returns NoAction.
   - Score `> 0.8`: auto-hide (`posts.is_auto_hidden = TRUE`) + `moderation_queue` row with `trigger = 'perspective_api_high_score'` (V9 enum value retained as historical artifact)
   - Score `> 0.6` AND `≤ 0.8`: flag to `moderation_queue` only
   - Firebase Remote Config thresholds: `perspective_api_high_score_threshold` (default 0.8), `perspective_api_flag_threshold` (default 0.6), both clamped to `[0.0, 1.0]` on every read
   - User content goes to a third party (OpenAI, US-hosted) — flag for the Pre-Launch Privacy Policy / RoPA update; UU PDP cross-border transfer (Pasal 56) requires Tier 2 (SCC / DPA — OpenAI publishes a DPA) OR Tier 3 (explicit consent in signup flow)
   - Indonesian: omni-moderation explicitly benchmarks Indonesian as top-performing alongside Spanish/German/Italian/Polish/Vietnamese/Portuguese/French/Chinese/English (per the launch announcement) — a step up from Perspective's "partial ID support" caveat
   - Kill switch: feature flag `perspective_api_enabled` (name historical; toggles the OpenAI Moderation dispatch)
4. **Month 6+ scope (if MAU >10k)**: dedicated ID-language moderation (Meta XLM-R open model self-host, or Hive Moderation paid)

> Keyword list storage, hot-reload, and matching engine: `05-Implementation.md` "Content Moderation Keyword Lists". Profanity list is admin-editable via the Admin Panel (audit-logged); UU ITE list reviewed quarterly with the legal advisor.

### Endpoint Flow

```
POST /api/v1/post
→ length validation (280 chars max)
→ Layer 1: profanity blocklist (sync) — match → 400 REJECT pre-INSERT
→ Layer 2: UU ITE category check (sync, threshold per moderation_match_threshold) — match → soft-flag (INSERT proceeds, moderation_queue row inserted, no is_auto_hidden flip)
→ Insert post (Layer 1 + 2 passed)
→ Layer 3: OpenAI Moderation API (async post-INSERT, 3000ms timeout regional baseline for asia-southeast1, fail-open) — score >0.8 → set is_auto_hidden = TRUE + insert moderation_queue row (visible to author, hidden from timeline until reviewed). 3000ms budget covers the bimodal Singapore → OpenAI US TTFB distribution (measured 2026-05-11: ~40% at 550-700ms, ~40% at 1500-1550ms slow path, ~20% gateway-timeout outliers at 15s+). Constructor-tunable via `analyzeTimeoutMillis` on `DefaultLayer3Moderator` for non-Singapore deployments.

**Cloud Run deployment requirement — `--no-cpu-throttling` is MANDATORY for any environment running Layer 3.** Default request-based CPU billing throttles CPU to ~5% when no inbound request is in flight; Layer 3 dispatch is fire-and-forget AFTER the 201 response, so it runs on throttled CPU and every async operation (Redis cache lookup, TLS handshake to OpenAI, response body parse) takes ~20x longer. Empirically (issue [#88](https://github.com/aditrioka/nearyou-id/issues/88) iter 14 vs iter 15 staging data): identical Redis `isEnabled()` calls measured 5-16ms during user requests vs 2800-7400ms on a background heartbeat timer — a 1000x slowdown purely from CPU throttling. Without the flag, Layer 3 dispatches timeout 100% on the 3000ms budget; with it they complete in 300-1200ms total (matching the raw-curl baseline from a one-shot CRJ in the same region). Staging applies the flag in [`.github/workflows/deploy-staging.yml`](../.github/workflows/deploy-staging.yml); production deploy MUST mirror. Trade-off: instance-based billing charges for CPU continuously (~+\$15-25/mo per always-on instance) — the documented Cloud Run pattern for post-response background work ([Cloud Run gets always-on CPU allocation](https://cloud.google.com/blog/products/serverless/cloud-run-gets-always-on-cpu-allocation)).
```

### Legal Documentation

- RoPA includes moderation decision data retention for 1 year
- Yearly transparency report (post-Month 12): removal stats by category

### Premium Username Customization Moderation

A Premium username change (`PATCH /api/v1/user/username`) runs the same text moderation pipeline (profanity blocklist + UU ITE keyword match) against the candidate handle. On hit, the change is REJECTED upfront (the user sees a rejection message and can pick another) and a `moderation_queue` row with `trigger = 'username_flagged'` is inserted for admin awareness (potential pattern signal); repeated flagged attempts from the same user (>3 in 24 hours) raise the user's anomaly score. Admin can explicitly allow a borderline candidate via an override action if context warrants (e.g. a legitimate Indonesian word matching the UU ITE list in an unrelated sense).

**Anti-impersonation (30-day release hold)**: on username change the old handle is held in `username_history` for 30 days before another account can claim it — no immediate impersonation of someone who just changed handles. Schema: `05-Implementation.md`.

### Media Moderation (Month 6+ Image Launch)

**Cloudflare CSAM Scanning Tool** (non-negotiable, free):
- Enable via Dashboard: Caching > Configuration > CSAM Scanning Tool > toggle on + verify email; no NCMEC credential required (available globally)
- Automatic scan on cached images, matched against NCMEC NGO + Industry lists
- On match: URL blocked (HTTP 451) + daily email notification; Cloudflare files third-party reports to NCMEC

The tool emits no webhooks to custom endpoints; the `/internal/csam-webhook` handler is invoked admin-triggered at MVP and via a Cloudflare Worker from Phase 2+ — full three-path architecture: `04-Architecture.md` § System Architecture; auth posture: § Internal Endpoint Security. Whichever path triggers it, the handler's auto-action is fixed policy: hard-delete the post, permanent ban + `token_version` bump, cascade the user's other posts, archive metadata with AES-256-GCM, queue the Kominfo report.

**Google Cloud Vision Safe Search** (explicit content upfront): pay-per-image, synchronous scan at upload; block adult/violent/racy score >0.8 upfront, before the image enters the cache. Complementary to CSAM (general explicit vs known child abuse hash).

> Upload flow + CSAM webhook handler: `02-Product.md` (product flow), `05-Implementation.md` (archive schema + CF IP extraction).

### Kominfo Reporting Obligation (Indonesia)

- Cloudflare auto-files to NCMEC (US clearing house); Oka must report to Kominfo (Ditjen Aptika) within <24 hours of detection
- Admin Panel SOP:
  1. CSAM webhook triggered → admin notification (in-app + Resend email)
  2. Admin reviews metadata (hash + timestamp + user_id_hash, NOT the content itself)
  3. File to Kominfo via official form or email contact point at Ditjen Aptika
  4. Log `kominfo_report_id` to the archive
- Polri Siber optional but recommended for severe cases; documented in the SOP

### CSAM Archive Purge Worker

Cloud Scheduler daily: `WHERE expires_at < NOW() AND kominfo_reported_at IS NOT NULL` → delete rows. Preservation is extended while an investigation is active.

### CSAM Archive Encryption

The archive row's `encrypted_metadata BYTEA` is AES-256-GCM-encrypted with the `csam-archive-aes-key` from GCP Secret Manager; access is admin-role-restricted and decryption happens only via the admin panel service account + an audit log entry. Image hash + matched NCMEC reference stay in plaintext columns (needed for Kominfo filing); no image bytes are ever retained.

---

## Report System

- One-tap report from a post, reply, profile, and chat message; recorded in the `reports` table (`05-Implementation.md`)
- Auto-hide: 3 unique reporters (accounts >7 days old) → `is_auto_hidden = TRUE` pre-review + `moderation_queue` row
- Shadow ban capability; ban sticky via Google/Apple ID

---

## Block User (Privacy + Anti-Harassment)

Block from the profile or a post context menu. Bidirectional invisibility: neither party sees the other's posts or profiles; neither can initiate new DMs with the other (existing history preserved); follow relationships are automatically removed in both directions.

Block vs shadow ban: block = user-initiated, visible friction (user-facing "Pengguna ini diblokir"), symmetric; shadow ban = admin-initiated, invisible to the banned user.

> Schema + query rules + CI lint: `05-Implementation.md` (Block User Implementation).

---

## Shadow Ban

Principle: **all actions succeed from the banned user's perspective, invisible to others** — a high-friction layer, not an invisible shield (a sophisticated adversary detects it within 24-48 hours).

> Database view implementation + CI lint: `05-Implementation.md`.

**Admin entry point (where `is_shadow_banned` is set TRUE).** Report Queue resolution `shadow_ban_author` (`POST /admin/moderation-queue/{id}/resolve`, capability `admin-report-queue`) sets `users.is_shadow_banned = TRUE` on the resolved offending author — the first admin surface to do so. Unlike sibling `suspend_author_7d` / `ban_author` it writes **no** user-facing notification (no `account_action_applied`, no other `notifications` row): that absence is the stealth invariant — a notifying shadow ban would defeat its purpose, invisibility to the banned user. It still writes its `admin_actions_log` row for accountability.

### Known Leak Surfaces (Accepted Risk, Documented)

Shadow ban is not 100% invisible across multiple devices:

| Surface | Leak mechanism | Mitigation |
|---------|----------------|------------|
| Like counter cross-device | Device A shows +1, Device B shows real count | Client caches like state per-user-per-post TTL 5 min. Stale-consistent 5 min is acceptable. |
| Reply visible on device A, missing on device B | Thread fetch on device B returns the canonical list | Client optimistic reply injection, no re-fetch for 30 min. Force refresh then reply "disappears". |
| Follow count on own profile | Device A shows followed=true, device B fresh followed=false | Same 5-minute client cache per follow relationship. |
| Chat "delivered" status | Other party not replying = suspicious | Accepted. Banned user eventually realizes via social signals. Shadow ban = friction + time-buy for admin, not eternal deception. |

### Phased Moderation

Early phase: manual review via Admin Panel; add AI moderation when Premium media upload launches.

---

## Suspension vs Ban

Two distinct admin actions, same underlying columns:

- **7-day suspension**: `UPDATE users SET is_banned = TRUE, suspended_until = NOW() + INTERVAL '7 days', token_version = token_version + 1 WHERE id = :uid`. A daily worker (`/internal/unban-worker`) flips `is_banned = FALSE` and nulls `suspended_until` when the window elapses. See `05-Implementation.md`.
- **Permanent ban**: `UPDATE users SET is_banned = TRUE, suspended_until = NULL, token_version = token_version + 1 WHERE id = :uid`. No automatic unban.

In both cases all active refresh tokens for the user are deleted, so all active sessions are kicked on the next REST call.

---

## Privacy Compliance (UU PDP Indonesia)

### Consent Flow

- Location data is sensitive; explicit consent required at onboarding
- Google/Apple ID is never exposed to other users
- Privacy policy live before public launch
- Chat admin-readable disclosure: explicit at onboarding + Privacy Policy
- Age gate + DOB declaration mandatory (18+ only)
- Analytics consent screen at onboarding (Amplitude + Sentry + AdMob)
- Third-party data processor disclosure in Privacy Policy: Resend (email), Amplitude (analytics), Sentry (error tracking), RevenueCat (subscription), Cloudflare (CDN + CSAM), Firebase (FCM + Remote Config), Google Play / Apple (auth + billing), Supabase (DB), Upstash (cache), Grafana Cloud (OpenTelemetry trace backend — receives pseudonymous trace data: hashed user IDs, parameterized SQL, route patterns)

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

Request recorded in `deletion_requests` (`05-Implementation.md`).

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

**Rationale for chat tombstone over cascade**: User A's deletion must not wipe the whole conversation for User B — tombstone preserves the other party's context while sender PII is still nulled (display name "Akun Dihapus", avatar default). If the message content itself is problematic (third-party PII, doxxing): admin-triggered redaction via `redacted_at`, not default behavior.

**30-day grace period**: data intact, user can restore. After 30 days the hard-delete worker executes and writes an entry to the deletion log.

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

**Delivery**: an async worker packs the ZIP, uploads to R2, creates a signed URL TTL 24 hours, and emails via Resend (user-facing): "Data export kamu siap diunduh".

**SLA**: 7 days (confirm with legal advisor before launch).

### Infrastructure

- **Hard delete worker**: Cloud Scheduler calls `/internal/account-hard-delete-worker` daily (reads `deletion_requests`). _(Shipped on its own subtree as `account-hard-delete-worker`; the original "consolidated `/internal/cleanup`" name is now owned by the `scheduled-retention-cleanup` retention sweeps — see `docs/05` §§112/582/1120.)_
- **Audit log table**: every hard delete logged with timestamp, entity, and reason
- **Deletion log (R2)**: append-only JSONL objects, 7-year retention, input for post-restore reconciliation
- **Data export endpoint**: `/account/export` (§ Data Export Scope Matrix)
- **Breach notification**: template + PDP Agency contact ready; reporting window 3x24 hours mandatory per UU PDP
- **DPO**: Oka himself in the solo phase, with RoPA documentation; external DPO-as-a-service when scale is significant
- **Suspension unban worker**: daily cron flips time-bound suspensions back to active (`05-Implementation.md`)

---

## Internal Endpoint Security

All internal scheduler endpoints are served under `/internal/*` with mandatory OIDC middleware.

### Implementation

- Cloud Scheduler natively supports OIDC tokens
- Ktor middleware verifies the signature via Google JWKS + audience claim matching the service URL
- Service account in GCP Secret Manager

### Covered Endpoints

> **Status (2026-06).** The **Shipped** list below is mounted in `Application.kt`'s `/internal/*` block (each worker gates OIDC on its own subtree); the **DESIGN** list is not yet mounted. If drift suspected, cross-check: `find backend/ktor/src/main -name "*Routes.kt" -path "*internal*"`.

**Shipped:**
- Apple S2S notifications (`/internal/apple/s2s-notifications`) — `AppleS2SRoutes.kt`
- Suspension unban worker (`/internal/unban-worker`, daily) — `admin/UnbanWorkerRoute.kt`
- Privacy flip worker (`/internal/privacy-flip-worker`, hourly) — `admin/PrivacyFlipWorkerRoute.kt`
- Hard delete worker (`/internal/account-hard-delete-worker`, daily) — `account/AccountHardDeleteWorkerRoute.kt`
- Retention cleanup worker (`/internal/cleanup`, daily) — `admin/retention/RetentionCleanupRoutes.kt`; runs the refresh-token + notifications + stale-FCM sweeps (`scheduled-retention-cleanup`)

**DESIGN — not yet implemented:**
- Image lifecycle cleanup
- Reverse geocoding cache warmup
- CSAM webhook handler (`/internal/csam-webhook`)
- Granted entitlement activity gate check (daily)
- CSAM archive purge worker (post-90-day)
- Moderation queue / reports archival (weekly, resolved rows >1 year — deferred follow-up of `scheduled-retention-cleanup`)
- Stream GC (post-swap, weekly)
- RevenueCat webhook (`/internal/revenuecat-webhook`)

**Exceptions to OIDC** (alternative auth) — **ALL DESIGN as of 2026-05-07**: neither endpoint is mounted; this is intended future shape, not active code.

- RevenueCat webhook (`/internal/revenuecat-webhook`) — Bearer token + HMAC signature (vendor doesn't support OIDC). See `05-Implementation.md` § RevenueCat Webhook (also tagged DESIGN).
- CSAM webhook handler (`/internal/csam-webhook`) — when implemented, both supported invocation paths are non-OIDC:
  - **Admin-triggered (MVP)**: the Admin Panel calls the handler internally with the admin's scoped session + a session-bound CSRF-style token; the services share the cluster network, so the call never leaves the trust boundary. (Admin Panel itself is DESIGN per `docs/07-Operations.md`.)
  - **Cloudflare Worker forwarding (Phase 2+)**: the CF Worker watching for `451` responses on the `img.nearyou.id` route signs its POST with a Bearer token pulled from a Worker secret + an HMAC-SHA256 body signature (key reserved as `cf-worker-csam-secret` in GCP Secret Manager); the Ktor handler verifies both before processing. Rate limit 100 req/hour per IP (prevents replay amplification).

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

Pre-Phase 1 task: generate from the third-party SDK list (Sentry, Amplitude, RevenueCat, FCM, AdMob) + merge with app-specific declarations.
