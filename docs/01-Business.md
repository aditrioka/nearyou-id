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
| **Image upload (1/post, 50/day, 5MB)** | No | Yes | **Month 6** (post CSAM + moderation stack ready, gated via Firebase Remote Config; feature spec: `02-Product.md`) |

**Paywall disclosure mandatory during Months 1-5**: the paywall shows only features available NOW — no image-upload mention. ToS clause (Bahasa Indonesia, user-facing): "Fitur Premium dapat berubah atau ditambahkan seiring waktu."

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

**Dual-value rationale for username**: the 60-char schema cap guarantees auto-generation a unique handle even on the 8-hex-UUID fallback; Premium customization via the Settings endpoint enforces the 30-char cap app-side — no schema change needed, defense-in-depth check.

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

Authoritative Redis TTL implementation + WIB stagger: `05-Implementation.md` Layer 2 rate limit table.

### Timeline Read Limit Semantics

Two layers on Free-tier timeline reads:

1. **Per-session soft cap — 50 posts Free, 10 Guest**: same-session UX upsell trigger; resets on app close/reopen (new session_id); NOT the authoritative limit.
2. **Per-user rolling hard cap — 150 posts/hour Free, 30/hour Guest**: authoritative, enforced independent of session_id; on hit, return empty + upsell flag.

Soft cap = conversion nudge; rolling hard cap = abuse prevention.

### Limit Rationale

Daily caps target write-heavy actions — post, reply, chat, like (a like = `post_likes` insert + notification write); Reply 20/day + Like 10/day Free double as anti-spam backstops and Premium upsell triggers. Reads cost negligible (timeline scroll: caps above); username change: cooldown, no daily counter.

### Hide Distance Mechanics (Premium Feature)

- Activate: viewers stop seeing **the distance number** on this user's posts; symmetric — the activator also stops seeing others' distance numbers
- **Scope: distance number only** — city name stays visible; the 5km floor still applies globally
- No ordering leak (all timelines sort by time)
- Implementation: **shared module `:shared:distance`** (`jvmMain` + native targets) — single source of truth for distance *rendering* via the pure `DistanceRenderer.render(distanceMeters)`, consumed by both the Ktor backend and the mobile app. Hide-distance suppression is **server-side field omission upstream of the renderer** (the Nearby read omits `distanceM` when the symmetric author-OR-viewer rule applies); it is NOT a renderer parameter. The flag is premium-effective + read-gated (`hide-distance` capability)
- **Mandatory test checklist** spans all surfaces incl. backend-rendered: Timeline card, Post detail, Profile page, Chat context card, Search result, Notification list (push body: only "Pesan baru dari {username}", never distance). NOTE: as shipped, only the Nearby timeline emits a distance number; every other surface is already distance-free, so the rule is a verified no-op there.

> `DistanceRenderer.render()` algorithm (floor at 5km, round to nearest 1km above 5km) + jitter order (`JitterEngine`): `05-Implementation.md`.

### Premium Tenure Counter

Badge for subscription duration; accumulates across cancel + re-subscribe (no reset). Rewards financial loyalty, not daily usage.

---

## Pricing & Payment

### Multi-Period Pricing (Target, Verify Pre-Phase 1)

| Period | Target Price | Effective/day | Net (fee 15%) |
|---------|--------------|--------------|------------------|
| Weekly | Rp9,900 | Rp1,414 | Rp8,415 |
| Monthly | Rp29,000 | Rp967 | Rp24,650 |
| Yearly | Rp249,000 | Rp682 | Rp211,650 |

**Pre-Phase 1 task**: verify tier availability in Google Play Console + App Store Connect; if a tier is unavailable, pick the closest below the target psychology and re-run the weighted average forecast.

**Daily tier dropped**: Apple lacks daily duration for auto-renewable subscriptions; dropped for cross-platform parity.

### Platform Fee: 15% Flat

- Google Play: 15% subscription (existing small business rate)
- Apple: 15% after Small Business Program enrollment (mandatory)

March 2026 Google Play fee reform: US/UK/EEA subscription fees become 10% service + 5% Play Billing = 15% net (same as existing), rolling out by June 2026; Indonesia joins the worldwide rollout September 2027. 15% flat holds until then; re-forecast Month 18 if Indonesia arrives earlier.

### Developer Program Fees (One-Time + Recurring)

- Google Play Developer account: **$25 one-time fee** (verify current rate in Pre-Phase 1; Google adjusts periodically)
- Apple Developer Program: $99/year recurring (~Rp133k/month amortized)

### Payment Stack

- **Abstraction**: RevenueCat SDK wraps Google Play Billing + StoreKit; free to $2.5k MTR, then 1% of MTR
- **Webhook authentication (mandatory)**: `Authorization: Bearer <shared_secret>` validated against GCP Secret Manager; `X-RevenueCat-Signature` HMAC-SHA256 if enabled in the RevenueCat Dashboard; signature mismatch → reject + audit-log the attempt
- **Billing fail handling**: 7-day grace on a 3-state status, schema-enforced via CHECK (constraint: `05-Implementation.md`) — `free` / `premium_active` (normal) / `premium_billing_retry` (`BILLING_ISSUE` webhook received, grace timer running, Premium access REMAINS active); after 7 days or `EXPIRATION` with `BILLING_ERROR` reason → `free`
- **Cancellation vs expiration**: `CANCELLATION` keeps `premium_active` until the period ends; only `EXPIRATION` flips to `free`
- **Privacy flip grace**: downgraded users with a private profile are NOT auto-flipped — push + in-app banner (user-facing): "Private profile akan jadi public dalam 72 jam. Tap untuk Premium ulang atau confirm switch public."
- **Downgrade**: Premium → Free — Nearby cap reverts to 20km, premium features disabled, private profile flips per the 72h grace above

### Subscription Analytics Integrity

Revenue analytics MUST use event-level tracking — a user-level flag loses information during transitions. `subscription_events` schema: `05-Implementation.md`.

`event_type`: `initial_purchase`, `renewal`, `grant`, `cancellation`, `billing_issue`, `expiration`. `source`: `paid`, `referral`, `manual_admin`.

MRR/ARR queries MUST filter `WHERE source = 'paid' AND event_type IN ('initial_purchase', 'renewal')`; granted entitlements count in the engagement metric, NOT revenue projection.

### Granted Entitlement Stacking (Referral Bonus)

- Active Premium when the grant is processed: EXTEND current period by 1 week (native RevenueCat `GRANT` stacking)
- Not Premium, or previously Premium but lapsed: 1-week trial (lapsed = treated as new)
- **Invitee cap**: one referral-based grant per invitee, tied to their own registration ticket — rewarded for their own registration only, never again from referring others
- **Inviter cap**: one referral-based grant per inviter lifetime, unlocked exactly at the confirmed 5th successful referral — no further grants ever (mechanics: § Inviter Reward Gate)
- **Idempotency**: `granted_entitlements` UNIQUE `(referral_ticket_id, user_id)` guards invitee grants + duplicate 5th-milestone attempts; inviter lifetime cap enforced by the `users.inviter_reward_claimed_at` sentinel column; worker: INSERT ON CONFLICT DO NOTHING + RevenueCat `dedup_key`

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

Open signup; invite codes add bonuses behind an activity gate — invitees rewarded per successful registration, inviters once per lifetime at the 5-referral milestone (mechanics below).

### Mechanism

- Signup open to all (18+ only, per the age gate); invite code in Settings
- **Invitee reward**: 1 week of free Premium via the RevenueCat Granted Entitlements API once the registration passes the activity gate — exactly once per invitee, tied to their own registration ticket
- **Inviter reward**: 1 week of free Premium, exactly once per lifetime, at the confirmed 5th successful referral (not at 1-4, not at 6+); later successes still reward their invitees normally, the inviter gets nothing more (§ Inviter Reward Gate)

### Bonus Release Criteria (Multi-Stage Gating, applies to every ticket)

**ALL must pass** for a ticket to count as a "successful referral":

1. **Invitee registered** → status `pending_activity`
2. **Invitee activity gate (14-day window)**: logged in on ≥3 different days; ≥2 posts (Free tier limit forces real engagement); ≥5 app sessions — derived server-side by sessionizing the durable `login_events` store (a 30-min idle-gap boundary; `login-history-tracking` / V34), replacing the consent-gated client `session_start` event. Login-days + app-sessions are security-purpose signals, always collected (not analytics-consent-gated).
3. **Anti-collision (device-fingerprint based)**: the **voiding** signals are the invitee's `device_fingerprint` NOT matching the inviter's `login_events` device fingerprints in the last 90 days, AND the invitee's Google/Apple identifier NOT seen on a device the inviter used (the "recently-seen" signal, realized as identity-on-an-inviter-device). The invitee IP subnet (/24) is **recorded but non-voiding** — Indonesian carrier-grade NAT (Telkomsel/Indosat/XL) makes a shared /24 common among unrelated users, so subnet-alone voiding would false-void legitimate same-carrier referrals (operator decision; `login-history-tracking` design D8a — supersedes the original "/24 not among the inviter's last 10 login subnets" voiding criterion). All anti-collision signals are evaluated by the activity-check worker against `login_events`, not at signup.
4. **Account age sanity**: inviter account >30 days old (prevents new-account farming)

**Ticket expiration**: `referral_tickets.expires_at = created_at + 14 days`; the worker rejects tickets past `expires_at`.

> Schemas (`referral_tickets`, `granted_entitlements`, `users.inviter_reward_claimed_at`) + the worker job: `05-Implementation.md`.

### Inviter Reward Gate (Lifetime: 5 Successful Referrals = 1 Reward)

- One referral-based grant per inviter lifetime: the 1-week Premium reward fires exactly when the **5th** successful referral is confirmed (ticket → `status = 'granted'`) — no per-referral stacking, no second-lifetime award, no top-ups
- Referrals 1-4 increment the inviter's success counter without producing a grant; 6+ continue rewarding their respective invitees and nothing else
- `users.inviter_reward_claimed_at` is set the moment the 5th-referral grant is issued; every code path checks this sentinel before attempting a new inviter grant — duplicates are structurally impossible

### Bonus Stacking Behavior

Stacking and caps for referral grants: § Granted Entitlement Stacking (Referral Bonus) — applies unchanged.

### Anti-Abuse Limits

- Max 3 referrals/week burst rate on ticket creation, regardless of position on the 0-5 lifetime track
- Inviter ban (shadow or hard) voids all pending tickets the inviter is responsible for
- A claimed inviter reward never repeats — later successful referrals unlock invitee rewards only

### Analytics Separation

- Webhook `GRANT` → `source = 'referral'`; `INITIAL_PURCHASE` / `RENEWAL` → `source = 'paid'` (event-level, `subscription_events` source field)
- Dashboard MRR/ARR: filter per § Subscription Analytics Integrity
- Monthly report: both metrics (paid + granted subscribers); referral premium counts in the engagement metric (DAU/MAU), NOT revenue projection

### Pre-launch Landing Page

Cosmetic waitlist; all approved automatically at launch.

---

## Go-to-Market Strategy

### Density-First

Geographic expansion only after threshold: min 10 new posts/day within a 20km radius, sustained 2 weeks. One dense city beats 10 sparse cities.

### Burn Rate Constraint

Max Rp2M/month, max 6 months — past that, kill or pivot. Total burn budget Rp12M; forecast health against it: § Health Check vs Burn Budget.

### Seed Strategy

- Target: 500 active MAU pre public launch; area: 1 campus/community/friend circle (physical radius 1-2km)
- Soft launch "done": 500 MAU + minimum 20 posts/day in Nearby for 1 week
- **Supabase tier note**: soft launch runs Supabase Pro, not Free — Free auto-pauses after 7 days of inactivity; Pro includes PITR + compute backing for active users

---

## Financial Forecast

### Assumptions

- Month 1 = Public Launch Day 1, all features ready except image upload (Month 6)
- MAU Premium conversion 2% Android / 3% iOS (blended 2.2%); weighted average net revenue per premium user Rp29,500/month (15% fee)
- Growth ~25%/month early, slowing to ~15% after Month 6
- OTP cost Rp0 (Google/Apple free); attestation Rp0 at MVP scale (100k/day quota sufficient up to >50k MAU)
- Ads revenue starts Month 3 (post AdMob approval)
- Realtime cost follows the broadcast-mode fan-out formula; Redis Streams post-swap cost realistic (higher than Pub/Sub but reliability is worth it)
- Email (Resend) negligible pre-scale (3k/month free tier covers the first 6 months)

### Supabase Realtime Cost Model (Broadcast Mode)

Verify actual pricing Pre-Phase 1 (estimates use the current rate). Month 24 (50k MAU): peak concurrent = 50k × 15% active-at-peak = 7.5k; messages/month = 9M direct + 9M broadcast fan-out = 18M; **total Realtime overage ~Rp1.9M/month**.

### Redis Streams Cost Model (Post-Swap)

Commands/month at 10k MAU: ~3.6M (XADD + XREADGROUP + XACK + XAUTOCLAIM).

| MAU | Commands/month | Upstash Cost |
|-----|----------------|--------------|
| 10k | 3.6M | ~Rp120k |
| 25k | 9M | ~Rp300k |
| 50k | 18M | ~Rp600k |

Re-benchmark in Month 12 with production data before locking in the swap at Month 15.

### Cloudflare Images Cost Model

Delivery per MAU (with mitigation): 3 daily sessions × 20 posts viewed/session × 5% image exposure × 1 variant (single variant mandatory for cost control) × 0.7 lazy-load factor (~30% cut) = MAU × 63/month.

| Month | MAU | Storage (image) | Storage cost | Delivery/mo | Delivery cost | Total CF Images |
|-------|-----|-----------------|--------------|-------------|---------------|-----------------|
| 6 | 1k | 5k | Rp4k | 63k | Rp10k | Rp14k |
| 12 | 7.5k | 50k | Rp40k | 475k | Rp78k | Rp118k |
| 18 | 25k | 200k | Rp160k | 1.6M | Rp260k | Rp420k |
| 24 | 50k | 500k | Rp400k | 3.15M | Rp515k | Rp915k |

### Email (Resend) Cost Model

Resend free tier: 3,000 emails/month. Typical usage: data export links, subscription events, admin CSAM alerts.

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

**Staging cost basis**: Supabase Free (idle auto-pause acceptable for a non-user-facing env), Cloud Run scale-to-zero + min-instance 0, Upstash Redis Free, Cloudflare R2 Free (10GB), RevenueCat sandbox (free); Firebase Remote Config + Sentry + Amplitude share production projects via `environment=staging` tagging (no cost separation). Line item = marginal metered usage (occasional Cloud Run beyond free allocation, Supabase Pro warm-up in active sprint weeks). Architecture + config separation pattern: `04-Architecture.md`.

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

Max cumulative deficit Rp1.91M (Month 5) vs Rp12M burn budget → margin ~6.3x, healthy; staging adds ~Rp70k cumulative through Month 5.

### Decision Point: Swap to Ktor WebSocket + Redis Streams

Month 24: Realtime overage ~Rp1.9M/month vs Redis Streams ~Rp600k/month → net saving ~Rp1.3M/month at 50k MAU; a swap done at Month 15 saves an estimated ~Rp12-18M over a 24-month horizon.

**Realistic swap timeline (4-5 weeks total)**: wrap-abstraction development + testing (Streams API more complex than Pub/Sub) 2-3 weeks; staged rollout canary 5% then 20% then 50% then 100%, 1-2 weeks.

**Self-host Supabase migration (if required, Month 18+)** — plan 2-phase: read replica first, full cutover later:

- Postgres + PostGIS migration: 1 week
- Auth (GoTrue) self-host or swap to Ory Kratos: 1-2 weeks
- Realtime swap: 1-2 weeks (coincides with the Ktor WS swap)
- JWT rotation + rolling client re-auth: 1 week (skip if RS256 + JWKS is in place from the start)
- DNS cutover + monitoring handover: 3-5 days
- **Total**: 4-6 weeks solo, 2-3 weeks with a contractor

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

Industry benchmark for social apps: 15-25% — 18.1% sits at the edge of the efficient band. The Ktor WS swap at Month 15+ (eliminating Supabase Realtime overage) drops it to ~13.6%.

### Cost Driver Analysis

- Cloud Run + Supabase Realtime overage: combined ~54% of total infra cost at scale — the **swap decision at Month 15** is the biggest cost-optimization lever
- CF Images becomes significant Month 12+ (mitigation crucial)
- Redis Streams post-swap enters the cost model; an acceptable trade vs Pub/Sub reliability
- Observability stack (OTel + Sentry + Amplitude) flat at ~2% of revenue, acceptable
- Email cost small in absolute terms; significant Resend free-tier headroom

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
- RevenueCat webhook signature verification pass rate 100% (less = investigate)

### Product (via Amplitude, opt-in analytics)

- Onboarding conversion (install → first post) >40%
- Premium paywall conversion >2% Android, >3% iOS
- D1 retention >50%, D7 >30%, D30 >15%
- Analytics opt-in rate baseline (conversion to tracked cohort)
