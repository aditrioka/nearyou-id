# NearYouID - Business Strategy

Business model, pricing, monetization, go-to-market, and financials.

---

## Business Model

### Freemium Tiers (Phase-Gated Rollout)

| Feature | Free | Premium | Available from |
|-------|------|---------|----------------|
| Price | Free | Weekly/Monthly/Yearly (verified Pre-Phase 1) | Month 1 |
| Posts per day | 10 | Unlimited | Month 1 |
| Chats per day | 50 | Unlimited | Month 1 |
| Replies per day | 20 | Unlimited | Month 1 |
| Likes per day | 10 (cap 500/hour burst) | Unlimited (cap 500/hour burst) | Month 1 |
| Timeline scroll | Guest 10/session (soft) + 30/hour (hard), Free 50/session (soft) + 150/hour (hard) | Unlimited | Month 1 |
| Edit post (30 min window) | No | Yes | Month 1 |
| Private profile (opt-in) | No | Yes | Month 1 |
| Customize username | No (stuck with auto-generated) | Yes (1 change per 30 days) | Month 1 |
| Nearby filter range | 20km fixed | 10/20/50/100 km | Month 1 |
| Hide distance | No | Yes (distance number only) | Month 1 |
| Search | No | Yes (PostgreSQL FTS + pg_trgm) | Month 1 |
| Ads | Yes | No | Month 1 |
| Premium badge + tenure | No | Yes | Month 1 |
| Block user | Yes | Yes | Month 1 |
| **Image upload (1/post, 50/day, 5MB)** | No | Yes | **Month 6** (post CSAM + moderation stack ready, gated via Firebase Remote Config) |

**Paywall disclosure mandatory during Months 1-5**: the paywall screen shows only features available NOW (does not mention image upload). ToS clause (Bahasa Indonesia, user-facing): "Fitur Premium dapat berubah atau ditambahkan seiring waktu."

### Content Length Limits

| Content Type | Max Length | Enforcement | Rationale |
|--------------|------------|-------------|-----------|
| Post content | 280 chars | Client + server (reject 400) | Matches X/Twitter Free tier; tweets <100 chars get 17% more engagement |
| Reply content | 280 chars | Client + server | Parity with posts for conversation flow |
| Chat message | 2000 chars | Client + server | Generous for DMs, smaller than X DM (10k) to control storage |
| Bio | 160 chars | Client + server | Matches X/Twitter (Instagram 150, TikTok 80) |
| Display name | 50 chars | Client + server | Matches X/Twitter |
| Username (system-generated) | 60 chars (schema) | Schema constraint `VARCHAR(60)` | Accommodates worst-case `adjective_noun_modifier_uuid8hex` fallback |
| Username (Premium custom) | 30 chars | App-level check on `PATCH /api/v1/user/username` | Matches Instagram's 30-char handle cap |

**Dual-value rationale for username**: the schema accepts up to 60 characters so the auto-generation algorithm can always produce a unique handle even on the fallback path with an 8-hex UUID suffix. When a Premium user customizes their username via the Settings endpoint, the application layer enforces the tighter 30-character cap (no schema change needed; defense-in-depth check).

Emoji counted as grapheme cluster via `java.text.BreakIterator` server-side, `Intl.Segmenter` client-side.

### Free-Tier Write Quota Summary (Single Source of Truth)

| Action | Free limit | Premium limit | Burst cap |
|--------|------------|---------------|-----------|
| Post | 10/day | Unlimited | n/a |
| Reply | 20/day | Unlimited | n/a |
| Chat message | 50/day | Unlimited | n/a |
| Like | 10/day | Unlimited | 500/hour (both tiers, anti-bot) |
| Follow | n/a | n/a | 50/hour (both tiers) |
| Block/Unblock | n/a | n/a | 30/hour (both tiers) |
| Report | n/a | n/a | 20/hour (both tiers) |
| Search query | 403 (Premium only) | 60/hour | n/a |
| Image upload (Month 6+) | 0 | 50/day | 1/60 sec throttle |
| Username change | n/a (stuck with auto-generated) | 1/30 days | 3 availability probes/day |

See `05-Implementation.md` Layer 2 rate limit table for the authoritative Redis TTL implementation with WIB stagger.

### Timeline Read Limit Semantics

Free tier timeline reads have two layers of limits:

1. **Per-session soft cap (50 posts Free, 10 posts Guest)**: the goal is a UX upsell trigger within the same session. Resets if the user closes and reopens the app (new session_id). This is NOT the authoritative limit.
2. **Per-user rolling hard cap (150 posts/hour Free, 30 posts/hour Guest)**: authoritative. Enforced independent of session_id. If hit, return empty + upsell flag.

Rationale: soft cap is a conversion nudge, rolling hard cap is abuse prevention.

### Limit Rationale

Write-heavy actions have daily caps: post, reply, chat, and like (since every like is a `post_likes` row insert + notification write). Reply 20/day and Like 10/day Free serve as anti-spam backstops and Premium upsell triggers. Read operations cost negligible; timeline scroll uses session + rolling caps documented above. Username change is rate-limited by cooldown, not by daily counter.

### Hide Distance Mechanics (Premium Feature)

- Activate: all viewers no longer see **the distance number** on this user's posts
- Symmetric: the user who activates it also does not see distance numbers on other users' posts
- **Scope: distance number only**. City name remains visible.
- The 5km floor still applies globally
- No leak risk via ordering (all timelines sort by time)
- Implementation: **shared module `:shared:distance`** with `jvmMain` + native targets. The Ktor backend and the mobile app both depend on this module. Single source of truth for `renderDistance(post, viewer)`.
- **Mandatory test checklist** covers all surfaces including backend-rendered ones: Timeline card, Post detail, Profile page, Chat context card, Search result, Notification list (push body does NOT include distance, only "Pesan baru dari {username}").

> See also: `05-Implementation.md` for the `renderDistance()` algorithm (fuzz first, floor at 5km, round to nearest 1km above 5km) and jitter order.

### Premium Tenure Counter

Badge for subscription duration. Does not reset on cancel + re-subscribe (accumulates). Rewards financial loyalty, not daily usage.

---

## Pricing & Payment

### Multi-Period Pricing (Target, Verify Pre-Phase 1)

| Period | Target Price | Effective/day | Net (fee 15%) |
|---------|--------------|--------------|------------------|
| Weekly | Rp9,900 | Rp1,414 | Rp8,415 |
| Monthly | Rp29,000 | Rp967 | Rp24,650 |
| Yearly | Rp249,000 | Rp682 | Rp211,650 |

**Pre-Phase 1 task**: verify tier availability in Google Play Console + App Store Connect. If the exact tier is unavailable, pick the closest tier below the target psychology and re-run the weighted average forecast.

**Daily tier dropped**: Apple does not support daily duration for auto-renewable subscriptions. Dropped for cross-platform parity.

### Platform Fee: 15% Flat

- Google Play: 15% subscription (existing small business rate)
- Apple: 15% after enrolling in the Small Business Program (enrollment mandatory)

Context: As of March 2026, Google announced Play Store fee reform. US/UK/EEA subscription fees become 10% service + 5% Play Billing = 15% net (same as existing), rolling out by June 2026. Indonesia is part of the worldwide rollout in September 2027. Until then, the 15% flat assumption is valid. Re-forecast in Month 18 if the Indonesia rollout arrives earlier.

### Developer Program Fees (One-Time + Recurring)

- Google Play Developer account: **$25 one-time fee** (verify current rate in Pre-Phase 1; Google periodically adjusts)
- Apple Developer Program: $99/year recurring (~Rp133k/month amortized)

### Payment Stack

- **Abstraction**: RevenueCat SDK wraps Google Play Billing + StoreKit. Free up to $2.5k MTR, 1% of MTR above that.
- **Target conversion**: 2% Android, 3% iOS
- **Webhook authentication** (mandatory):
  - `Authorization: Bearer <shared_secret>` header validated against the secret in GCP Secret Manager
  - `X-RevenueCat-Signature` HMAC-SHA256 verification if enabled in the RevenueCat Dashboard
  - Reject requests with signature mismatch + audit log the attempt
- **Billing fail handling**: 7-day grace period with a 3-state subscription status:
  - `premium_active`: subscription active normally
  - `premium_billing_retry`: `BILLING_ISSUE` webhook received, grace timer running, Premium access REMAINS active
  - After 7 days or `EXPIRATION` webhook with `BILLING_ERROR` reason: downgrade to `free`
- **Subscription status enum (schema-enforced via CHECK)**: `free`, `premium_active`, `premium_billing_retry`. See `05-Implementation.md` for the constraint definition.
- **Cancellation vs expiration**: On `CANCELLATION` webhook the status remains `premium_active` until the period ends (no immediate change). Only `EXPIRATION` flips the status to `free`.
- **Privacy flip grace**: users who downgrade to Free and have a private profile are NOT automatically flipped. Send push + in-app banner (user-facing): "Private profile akan jadi public dalam 72 jam. Tap untuk Premium ulang atau confirm switch public."
- **Downgrade**: Premium to Free, Nearby cap reverts to 20km, premium features disabled. Private profile opt-in flips with 72h warning.

### Subscription Analytics Integrity

Revenue analytics MUST use event-level tracking (not a user-level flag that loses information during transitions). See `05-Implementation.md` for the `subscription_events` schema.

`event_type`: `initial_purchase`, `renewal`, `grant`, `cancellation`, `billing_issue`, `expiration`.
`source`: `paid`, `referral`, `manual_admin`.

MRR/ARR queries MUST filter `WHERE source = 'paid' AND event_type IN ('initial_purchase', 'renewal')`. Granted entitlements are counted in the engagement metric, NOT revenue projection.

### Granted Entitlement Stacking (Referral Bonus)

- User already has active Premium when a grant is processed: EXTEND current period by 1 week
- User is not Premium: grant a 1-week trial
- User was previously Premium but lapsed: grant a 1-week trial (treat as new)
- **Invitee cap**: one referral-based grant per invitee, tied to the invitee's registration ticket (the invitee receives a reward only for their own registration, never again from referring others)
- **Inviter cap**: one referral-based grant in the inviter's entire lifetime, unlocked exactly when the inviter's 5th successful referral is confirmed. No further grants ever, regardless of how many additional successful referrals follow.
- **Idempotency**: the `granted_entitlements` table has UNIQUE `(referral_ticket_id, user_id)` guarding invitee grants and duplicate attempts for the 5th-referral milestone. The inviter lifetime cap is enforced by the `users.inviter_reward_claimed_at` sentinel column. The worker uses INSERT ON CONFLICT DO NOTHING + RevenueCat `dedup_key` parameter.

---

## Ads Implementation

### Ad Network

- Primary: Google AdMob
- Mediation: AppLovin MAX in Phase 2+

### Placement

| Location | Format | Frequency | eCPM Estimate |
|--------|--------|-----------|---------------|
| Timeline (Nearby/Following/Global) | Native | Every 5-7 posts | Rp500-1,500 |
| Profile (other users) | Banner | Per screen, max 1 per 15-20 post scroll | Rp150-400 |
| Chat list | Native between conversations | 1 per screen | Rp500-1,500 |
| Chat screen | **No ads** | Preserve trust | - |

### Interstitial Popup (Minimal)

- App open number 5, 10, 15
- After "post submitted" (1 out of 5 times)
- Never while typing in chat or viewing a profile

### Privacy Compliance

- AdMob UMP SDK mandatory (UU PDP)
- Non-personalized ads fallback if declined (eCPM reduced by 30-50%, mandatory)
- Data minimization: share city-level location, not precise coordinates

### KMP Integration (Manual expect/actual)

- `interface AdProvider` in `:core:data`
- Android: Google Mobile Ads SDK + UMP SDK native
- iOS: cinterop to Google-Mobile-Ads + Google-UserMessagingPlatform frameworks
- Phase 4 allocation: ~5 days

### Revenue Estimate (After Platform Cut ~32%)

| MAU | Impressions/month | CPM | Net Revenue |
|-----|-------------------|-----|----------------|
| 1,000 | 600k | Rp400 | Rp163k |
| 7,500 | 4.5M | Rp400 | Rp1.2M |
| 25,000 | 15M | Rp500 | Rp5.1M |
| 50,000 | 30M | Rp500 | Rp10.2M |

AdMob approval takes 2-4 weeks; ads revenue starts Month 3+.

---

## Referral System

### Philosophy

Signup is open; invite codes provide bonuses with an activity gate. Invitees are rewarded per successful registration. Inviters are rewarded once, as a lifetime milestone at 5 successful referrals.

### Mechanism

- Signup open to all (18+ only, per the age gate)
- Invite code in Settings
- **Invitee reward**: 1 week of free Premium via RevenueCat Granted Entitlements API, granted once the invitee's registration passes the activity gate. Each invitee receives this reward exactly once, tied to their own registration ticket.
- **Inviter reward**: 1 week of free Premium, granted exactly once in the inviter's lifetime, triggered when the inviter's 5th successful referral is confirmed (not at referrals 1-4, not at referrals 6+).
- **After the 5th successful referral**: further successful referrals still reward their invitees normally, but provide no additional benefit to the inviter. No per-referral stacking, no second-lifetime award, no top-ups.

### Bonus Release Criteria (Multi-Stage Gating, applies to every ticket)

**ALL must pass** for a ticket to be counted as a "successful referral":

1. **Invitee registered** then status `pending_activity`
2. **Invitee activity gate (14-day window)**:
   - Logged in on at least 3 different days
   - Posted at least 2 times (Free tier limit forces real engagement)
   - Opened the app at least 5 sessions (tracked via `session_start` event)
3. **Anti-fingerprint-collision**:
   - Invitee `device_fingerprint` does NOT match the inviter's in the last 90 days
   - Invitee IP subnet (/24) does NOT match the inviter's last 10 login subnets
   - Invitee Google/Apple ID hash is NOT in the inviter's "recently seen" list
4. **Account age sanity**:
   - Inviter account is >30 days old (prevents new-account farming)

**Ticket expiration**: `referral_tickets.expires_at = created_at + 14 days`. Worker rejects tickets past `expires_at`.

> Schemas for `referral_tickets`, `granted_entitlements`, `users.inviter_reward_claimed_at`, and the worker job: see `05-Implementation.md`.

### Inviter Reward Gate (Lifetime: 5 Successful Referrals = 1 Reward)

- Every inviter is capped at one referral-based grant in their lifetime.
- The reward (1 week Premium) triggers exactly when the inviter's **5th** successful referral is confirmed (ticket moves to `status = 'granted'`).
- Successful referrals at positions 1, 2, 3, and 4 increment the inviter's success counter but do not produce any grant to the inviter.
- Successful referrals at positions 6 and beyond do not produce any grant to the inviter either. They continue to reward their respective invitees and nothing else.
- The `users.inviter_reward_claimed_at` sentinel column is set the moment the 5th-referral grant is issued. Any code path checks this sentinel before attempting a new inviter grant; duplicates are structurally impossible.

### Bonus Stacking Behavior

- User with active Premium at grant time: EXTEND current period by 1 week (RevenueCat `GRANT` stacking native)
- User not Premium: grant a 1-week trial
- User previously Premium but lapsed: grant a 1-week trial (treat as new)
- Invitee receives at most one referral-based grant (one registration, one reward)
- Inviter receives at most one referral-based grant in their lifetime, and only at the 5-referral milestone

### Anti-Abuse Limits

- Max 3 referrals/week burst rate (applies to ticket creation regardless of where the inviter sits on the 0-5 lifetime track)
- Inviter ban (shadow or hard) voids all pending tickets the inviter is responsible for
- Once the inviter reward is claimed, further successful referrals continue to unlock invitee rewards only; the inviter never receives another grant

### Analytics Separation

- Event-level tracking via the `subscription_events` table (source field)
- Webhook event `GRANT` then `source = 'referral'`
- Webhook events `INITIAL_PURCHASE`, `RENEWAL` then `source = 'paid'`
- Dashboard MRR/ARR: `WHERE source = 'paid' AND event_type IN ('initial_purchase', 'renewal')`
- Monthly report: both metrics (paid + granted subscribers)
- Forecast: referral premium counted in engagement metric (DAU/MAU), NOT revenue projection

### Pre-launch Landing Page

Cosmetic waitlist; all approved automatically at launch.

---

## Go-to-Market Strategy

### Density-First

Geographic expansion only after threshold:
- Min 10 new posts/day within a 20km radius, sustained for 2 weeks
- One dense city beats 10 sparse cities

### Burn Rate Constraint

- Max Rp2M/month
- Max 6 months
- Past that, kill or pivot

Total burn budget: Rp12M. Peak cumulative deficit forecast: Rp1.91M in Month 5. Margin ~6.3x vs burn budget.

### Seed Strategy

- Target: 500 active MAU pre public launch
- Area: 1 campus/community/friend circle (physical radius 1-2km)
- Soft launch "done": 500 MAU + minimum 20 posts/day in Nearby for 1 week
- **Supabase tier note**: Soft launch uses Supabase Pro (not Free) because Free tier auto-pauses after 7 days of inactivity and Pro includes PITR + compute backing for active users.

---

## Financial Forecast

### Assumptions

- Month 1 = Public Launch Day 1 with all features ready (except image upload in Month 6)
- MAU Premium conversion: 2% Android, 3% iOS (blended 2.2%)
- Weighted average net revenue per premium user: Rp29,500/month (15% fee)
- Growth ~25%/month early on, slowing to ~15% after Month 6
- OTP cost Rp0 (Google/Apple free)
- Attestation cost Rp0 at MVP scale (quota of 100k/day is sufficient up to >50k MAU)
- Ads revenue starts Month 3 (post AdMob approval)
- Realtime cost follows fan-out formula (broadcast mode)
- Redis Streams post-swap cost realistic (higher than Pub/Sub but reliability is worth it)
- Email (Resend) cost negligible pre-scale (free tier of 3k/month covers the first 6 months)

### Supabase Realtime Cost Model (Broadcast Mode)

Verify actual pricing Pre-Phase 1. Estimates based on the current rate:

Month 24 (50k MAU) estimate:
- Peak concurrent: 50k × 15% active-at-peak = 7.5k
- Messages/month: 9M direct + 9M broadcast fan-out = 18M
- **Total Realtime overage Month 24**: ~Rp1.9M/month

### Redis Streams Cost Model (Post-Swap)

Commands/month at 10k MAU: ~3.6M (XADD + XREADGROUP + XACK + XAUTOCLAIM).

| MAU | Commands/month | Upstash Cost |
|-----|----------------|--------------|
| 10k | 3.6M | ~Rp120k |
| 25k | 9M | ~Rp300k |
| 50k | 18M | ~Rp600k |

Re-benchmark in Month 12 with production data before locking in the swap at Month 15.

### Cloudflare Images Cost Model

Delivery volume per MAU (with mitigation):
- Daily sessions: 3
- Posts viewed per session: 20
- Image exposure rate: 5% of posts viewed
- Variants per image: 1 (single variant mandatory for cost control)
- Lazy-load cut: ~30%

Math: MAU × 3 sessions × 20 posts × 5% image × 1 variant × 0.7 lazy factor = MAU × 63/month.

| Month | MAU | Storage (image) | Storage cost | Delivery/mo | Delivery cost | Total CF Images |
|-------|-----|-----------------|--------------|-------------|---------------|-----------------|
| 6 | 1k | 5k | Rp4k | 63k | Rp10k | Rp14k |
| 12 | 7.5k | 50k | Rp40k | 475k | Rp78k | Rp118k |
| 18 | 25k | 200k | Rp160k | 1.6M | Rp260k | Rp420k |
| 24 | 50k | 500k | Rp400k | 3.15M | Rp515k | Rp915k |

### Email (Resend) Cost Model

Resend free tier: 3,000 emails/month. Typical usage: data export links, subscription events, admin CSAM alerts. Expected volume:

| Month | MAU | Emails/month estimate | Resend tier | Cost |
|-------|-----|----------------------|-------------|------|
| 1-6 | <1k | <500 | Free | Rp0 |
| 7-12 | 7.5k | ~2,500 | Free | Rp0 |
| 13-18 | 25k | ~8,000 | Pro ($20/mo) | ~Rp320k |
| 19-24 | 50k | ~16,000 | Pro ($20/mo) | ~Rp320k |

### Cost Breakdown Per Component

| Component | Month 1-3 | Month 6 | Month 12 | Month 18 | Month 24 |
|----------|-----------|---------|----------|----------|----------|
| Cloud Run (auto-scale) | 50k | 80k | 300k | 900k | 2,000k |
| Cloud Run Jobs (backup + workers + migrations) | 10k | 10k | 10k | 15k | 20k |
| Supabase Pro | 400k | 400k | 400k | 500k | 600k |
| Supabase Realtime overage | 0 | 0 | 100k | 800k | 1,900k |
| Upstash Redis (cache + rate limit) | 0 | 0 | 80k | 175k | 325k |
| Upstash Streams (post-swap Month 15+) | 0 | 0 | 0 | 300k | 600k |
| Cloudflare R2 (incl backup + deletion log) | 0 | 3k | 23k | 55k | 103k |
| Cloudflare Images + CSAM Tool | 0 | 14k | 118k | 420k | 915k |
| Resend email | 0 | 0 | 0 | 320k | 320k |
| OpenTelemetry (Grafana Cloud) | 0 | 0 | 30k | 65k | 100k |
| Sentry KMP | 0 | 0 | 420k | 420k | 420k |
| Amplitude | 0 | 0 | 0 | 0 | 300k |
| **Staging environment (minimal-spec)** | **15k** | **20k** | **30k** | **40k** | **40k** |
| Apple Developer | 133k | 133k | 133k | 133k | 133k |
| Domain | 15k | 15k | 15k | 15k | 15k |
| **Total Infra** | **623k** | **675k** | **1,659k** | **4,158k** | **7,791k** |

**Staging cost basis**: Supabase Free (auto-pauses on idle, acceptable for a non-user-facing environment), Cloud Run scale-to-zero + min-instance 0, Upstash Redis Free tier, Cloudflare R2 Free tier (10GB), RevenueCat sandbox (free), Firebase Remote Config + Sentry + Amplitude projects shared with production via `environment=staging` tagging (no cost separation). The line item covers marginal metered usage (occasional Cloud Run scaled beyond free allocation, Supabase Pro warm-up during active sprint weeks). Full architecture + config separation pattern: see `04-Architecture.md`.

### Net Projection

| Month | MAU | Revenue | Infra | Net | Cumulative |
|-------|-----|---------|-------|-----|------------|
| 1 | 100 | 59,000 | 623,000 | -564,000 | -564,000 |
| 2 | 150 | 95,000 | 623,000 | -528,000 | -1,092,000 |
| 3 | 250 | 187,500 | 635,000 | -447,500 | -1,539,500 |
| 4 | 500 | 377,000 | 655,000 | -278,000 | -1,817,500 |
| 5 | 750 | 580,000 | 675,000 | -95,000 | -1,912,500 |
| 6 | 1,000 | 812,000 | 675,000 | +137,000 | -1,775,500 |
| 7 | 1,500 | 1,220,000 | 815,000 | +405,000 | -1,370,500 |
| 9 | 3,000 | 2,436,000 | 1,080,000 | +1,356,000 | ~+420,000 |
| 12 | 7,500 | 6,067,500 | 1,659,000 | +4,408,500 | ~+10,900,000 |
| 18 | 25,000 | 21,325,000 | 4,158,000 | +17,167,000 | ~+72,800,000 |
| 24 | 50,000 | 42,650,000 | 7,791,000 | +34,859,000 | ~+204,700,000 |

### Milestones

| Milestone | Timeline | Value |
|-----------|----------|-------|
| Peak cumulative deficit | Month 5 | -Rp1.91M |
| Monthly break-even | Month 6 | +Rp137k |
| Cumulative break-even | Months 9-10 | ~+Rp420k |
| Target Rp10M/month | Months 13-14 | - |
| Target Rp20M/month | Months 19-20 | - |
| Target Rp40M/month | Months 26-27 | - |

### Health Check vs Burn Budget

- Max cumulative deficit: Rp1.91M (Month 5)
- Burn budget: Rp12M
- Margin: ~6.3x (remains healthy; staging environment adds ~Rp70k cumulative through Month 5)

### Decision Point: Swap to Ktor WebSocket + Redis Streams

Realtime overage in Month 24 is ~Rp1.9M/month vs Redis Streams ~Rp600k/month. Net saving of ~Rp1.3M/month at 50k MAU.

**Realistic swap timeline**: 4-5 weeks total:
- Development + testing wrap abstraction (Streams API is more complex than Pub/Sub): 2-3 weeks
- Staged rollout canary 5% then 20% then 50% then 100%: 1-2 weeks

**Self-host Supabase migration (if required, Month 18+)**:
- Postgres + PostGIS migration: 1 week
- Auth (GoTrue) self-host or swap to Ory Kratos: 1-2 weeks
- Realtime swap: 1-2 weeks (coincides with the Ktor WS swap)
- JWT rotation + rolling client re-auth: 1 week (or skip if RS256 + JWKS is already in place from the start)
- DNS cutover + monitoring handover: 3-5 days
- **Total**: 4-6 weeks solo, or 2-3 weeks with a contractor
- Plan 2-phase: read replica first, full cutover later

**Estimated saving if swap is done at Month 15**: ~Rp12-18M over a 24-month horizon.

### Infra Cost % vs Revenue in Month 24

| Component | % of Revenue |
|----------|--------------|
| Cloud Run + Jobs | 4.7% |
| Supabase Pro | 1.4% |
| Supabase Realtime overage | 4.5% |
| Upstash Redis | 0.8% |
| Upstash Streams (post-swap) | 1.4% |
| R2 (incl backup + deletion log) | 0.2% |
| Cloudflare Images + CSAM | 2.1% |
| Resend email | 0.8% |
| OpenTelemetry | 0.2% |
| Sentry KMP | 1.0% |
| Amplitude | 0.7% |
| Apple Dev + Domain | 0.3% |
| **Total infra** | **~18.1%** |

Industry benchmark for social apps: 15-25%. Stack at 18.1% sits at the edge of the efficient band. If the Ktor WS swap happens at Month 15+ (eliminating Supabase Realtime overage), this drops to ~13.6%.

### Cost Driver Analysis

- **Cloud Run + Supabase Realtime overage**: combined ~54% of total infra cost at scale
- **Swap decision at Month 15** is the biggest lever for cost optimization
- CF Images becomes significant in Month 12+ (mitigation crucial)
- Redis Streams post-swap enters the cost model, an acceptable trade vs Pub/Sub reliability
- Observability stack (OTel + Sentry + Amplitude) flat at ~2% of revenue, acceptable
- Email cost small in absolute terms, significant headroom in the Resend free tier

---

## Success Metrics

### Platform

- API response: <500ms
- Uptime: 99.9%
- Freemium conversion: 2% Android, 3% iOS
- Spatial query: <100ms standard radius
- Timeline endpoint p95: <200ms end-to-end (cross-cloud)
- Cold start Cloud Run p99: <3 seconds
- Density threshold: 10 posts/day within 20km sustained over 2 weeks per new city
- Health check: `/health/ready` green >99.9% (accounts for parallel dependency check tolerance)

### Security

- Attestation pass rate >98% (legitimate users)
- Refresh token reuse detection rate <0.01% (baseline)
- Shadow ban leak incidents = 0
- RLS denial rate stable (sudden spike = potential bug or attack)
- JWT verify fail rate <0.1% (spike = potential secret compromise)
- CSAM detection count expected <0.01% of uploads; any non-zero triggers urgent review
- Subscription grace resolution rate (returning to active vs expiring)
- Age gate rejection rate baseline (spike = potential exploit attempt)
- RevenueCat webhook signature verification pass rate 100% (anything less warrants investigation)

### Product (via Amplitude, opt-in analytics)

- Onboarding conversion (install → first post) >40%
- Premium paywall conversion >2% Android, >3% iOS
- D1 retention >50%, D7 >30%, D30 >15%
- Analytics opt-in rate baseline (conversion to tracked cohort)
