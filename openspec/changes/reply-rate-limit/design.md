## Context

`post-replies` (V8) shipped POST/GET/DELETE on `/api/v1/posts/{post_id}/replies` plus the V10 `post_replied` notification emit, but with no rate limiting — the V8 spec calls this out explicitly at [`docs/05-Implementation.md:715`](../../../docs/05-Implementation.md): "rate limiting remain[s] deferred." The Free 20/day reply cap is documented as canonical at [`docs/01-Business.md:53`](../../../docs/01-Business.md), [`docs/02-Product.md:224`](../../../docs/02-Product.md), and [`docs/05-Implementation.md:1740`](../../../docs/05-Implementation.md) Layer 2.

`like-rate-limit` ([PR #37](https://github.com/aditrioka/nearyou-id/pull/37)) shipped the rate-limit infrastructure required to land this cap: a Redis-backed `RateLimiter` interface in `:core:domain`, a Lettuce-based `RedisRateLimiter` in `:infra:redis`, the `computeTTLToNextReset(userId)` WIB-stagger helper, the hash-tag key format, and the two Detekt rules (`RateLimitTtlRule` + `RedisHashTagRule`) that enforce conventions at every call site. That change's proposal explicitly named the 20/day reply cap as one of four hard prerequisites the new infra was built to serve. This change is the second consumer of the infra (the third if the V9 reports port is counted) and is intended as a near-mechanical port of the like-handler integration — same call sequence, same 429 envelope, same Remote Config flag pattern, scoped down to a single daily window (no burst) and with no idempotent-release path.

The reply path also already integrates with V9 (the auto-hide trigger on `target_type = 'reply'`) and V10 (the `post_replied` notification emit). Neither of those integrations is affected by this change — the limiter runs strictly before any V9/V10 side-effect, so a 429 short-circuits both.

## Goals / Non-Goals

**Goals:**

- Apply the canonical Free 20/day daily cap to `POST /api/v1/posts/{post_id}/replies`, with 429 + `Retry-After` on limit hit, Premium-tier skip, and the `premium_reply_cap_override` Remote Config flag honored server-side.
- Honor the existing `rate-limit-infrastructure` capability contract verbatim — same `tryAcquire` shape, same key hash-tag format, same WIB-stagger TTL via `computeTTLToNextReset`. Verify both Detekt rules pass on the new call site without modification.
- Add `LikeRateLimitTest`-shaped integration coverage at `ReplyRateLimitTest` against the existing CI Redis container (already provisioned by `like-rate-limit`).
- Preserve V8 reply-endpoint contracts byte-for-byte: 201 success path, GET pagination, DELETE author-only idempotent-204, and the V10 `post_replied` emit-suppression rules (self / block / hard-delete-rollback) all unchanged.

**Non-Goals:**

- Burst (rolling-hour) limit on replies — replies have no burst clause per [`docs/02-Product.md:224`](../../../docs/02-Product.md). Future anti-bot work on replies, if needed, would be a separate change with its own design.
- Rate-limiting GET `/api/v1/posts/{post_id}/replies` — read-side limits live at the timeline session/hourly layer per [`docs/05-Implementation.md`](../../../docs/05-Implementation.md). Mirrors the like-rate-limit precedent (which also did not rate-limit GET `/likes`).
- Rate-limiting DELETE `/api/v1/posts/{post_id}/replies/{reply_id}` — the V8 author-only idempotent-204 contract MUST stay intact; rate-limiting deletes would let attackers prevent users from deleting their own replies.
- Mobile-side UX copy for the 429 response — out of scope; mobile already handles 429 generically and the like-button surface from PR #37 is the precedent. A reply-button-specific copy update can ship later.
- New Detekt rules — `RateLimitTtlRule` + `RedisHashTagRule` from `like-rate-limit` already cover the new daily-key call site. No rule-side changes.
- Re-litigating the rate-limit-infrastructure contract — that capability's spec is canonical (Decisions 1–7 in `like-rate-limit/design.md`); this change consumes it as-is.
- DB migrations — none required. State lives entirely in Redis.

## Decisions

### Decision 1 — One limiter, daily only (no burst)

**Choice.** The reply handler runs exactly **one** rate limiter: a daily window keyed `{scope:rate_reply_day}:{user:<uuid>}`, capacity 20 (or `premium_reply_cap_override` if set), TTL via `computeTTLToNextReset(userId)`. No second limiter — no rolling-hour burst.

**Why.** [`docs/02-Product.md:224`](../../../docs/02-Product.md) defines reply caps as "Free 20/day, Premium unlimited" — no burst clause. [`docs/02-Product.md:223`](../../../docs/02-Product.md) defines like caps as "Free 10/day, Premium unlimited (both tiers cap 500/hour burst)" — burst is explicitly called out. The asymmetry is canonical, not an oversight: replies are write-heavy in a way that invites a per-day cap (each row = one notification + one auto-hide-eligible target) but they don't have the same bot-fingerprint-via-velocity surface that likes do (likes are zero-effort on the user side; replies require typing 1–280 chars). Following the canonical line, the reply handler ships with daily-only.

**Alternatives considered.**

- **Add a 100/hour or 60/hour burst on replies anyway**: would be a divergence from canonical docs without a concrete anti-abuse signal. Rejected — divergence belongs in a separate proposal with its own rationale.
- **Reuse the like burst limiter (500/hour) on replies**: numerically too high to be meaningful for replies (a user who can post 500 replies in an hour is hitting daily cap 25× over first), and conceptually wrong (burst is anti-bot for high-velocity actions; replies aren't that). Rejected.

### Decision 2 — `premium_reply_cap_override` mirrors `premium_like_cap_override` byte-for-byte

**Choice.** Add a new Remote Config integer flag `premium_reply_cap_override`, plumbed through the existing `:infra:remote-config` module. Behavior matches `premium_like_cap_override` exactly: read on every Free-tier request, default 20 when unset / malformed / ≤ 0 / SDK error, applied at request time (mid-day flips bind immediately), does NOT affect Premium (which skips the daily limiter entirely).

**Why.** Decision 28 in [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md) and the like-rate-limit precedent ([PR #37](https://github.com/aditrioka/nearyou-id/pull/37)) establish the Remote-Config-override pattern for write-action daily caps. Ops needs the same lever for replies — both for tightening on anti-abuse signals and for loosening on D7 retention impact, both without a mobile release. Symmetry with the like flag means ops uses one mental model for two endpoints.

**Alternatives considered.**

- **Single shared `premium_write_cap_override` flag for both like and reply**: couples two independent dials together; an ops decision to tighten replies (e.g., spam wave) would unintentionally tighten likes. Rejected — separate dials are the right shape.
- **Skip the flag for replies**: tempting because we just shipped the like flag, but it would mean a future spam wave forces a mobile release (or a code change) to adjust the reply cap. The flag is cheap to wire — ship it now.

### Decision 3 — Tier (`subscription_status`) read from auth-time `viewer` principal, NOT a fresh DB SELECT

**Choice.** The reply handler reads `subscription_status` from the request-scope `viewer` principal populated by the `auth-jwt` plugin (which already loads `subscription_status` alongside the user identity for every authenticated request). The handler MUST NOT issue a fresh `SELECT subscription_status FROM users WHERE id = :caller` before the limiter — doing so would violate the "limiter runs before any DB read" guarantee.

**Why.** Identical constraint to the like handler (see `like-rate-limit/specs/post-likes/spec.md` § "Daily rate limit — 10/day Free, unlimited Premium, with WIB stagger" — the "Read-site constraint" sub-paragraph). Adding a DB read would defeat the limiter-before-DB ordering, which is a correctness invariant: a Free user at the cap should get 429 without paying for a `users` round-trip.

**Alternatives considered.**

- **Read it once at handler entry**: same correctness violation in different clothes — still a DB read before the limiter. Rejected.
- **Cache it in Redis**: premature; the auth principal already carries it. Rejected.

### Decision 4 — No `releaseMostRecent` at the reply call site

**Choice.** The reply handler calls `RateLimiter.tryAcquire` exactly once per request and never calls `releaseMostRecent`. Every successful slot acquisition is permanent — there is no "no-op idempotent" path to release back into the bucket.

**Why.** The like handler's release escape hatch exists because `INSERT ... ON CONFLICT (post_id, user_id) DO NOTHING` returning zero rows means the request didn't change DB state and shouldn't burn a slot. `post_replies` has no equivalent UNIQUE constraint — every successful POST is a real new row, even if the user posts the same content twice. There's no "already-replied" no-op to release on. The 404 `post_not_found` path also does not release (matches the like precedent: 404 is a downstream business decision and counts toward the cap as anti-abuse).

**Alternatives considered.**

- **Release on 404**: would let a Free attacker probe non-existent post UUIDs without consuming slots — anti-abuse-negative. Rejected. Same call as like-rate-limit.
- **Release on V10 notification rollback** (the rare case where the V8 transaction rolls back because the post author was hard-deleted between visibility-resolution and emit): the slot would technically have been "consumed without producing a row." But the rollback path is genuinely exceptional (concurrent hard-delete during a reply request — bounded by the visibility-resolution row lock), and burning one slot in that edge case is acceptable noise. The like handler also does not release on a rolled-back transaction. Skip the release call.

### Decision 5 — Limiter ordering: BEFORE visibility resolution AND content guard

**Choice.** The order of operations on `POST /api/v1/posts/{post_id}/replies` is:

1. Auth (`auth-jwt` plugin) — 401 short-circuits.
2. Path UUID validation — 400 `invalid_uuid` short-circuits.
3. **Daily rate limiter** (Free only; Premium skips entirely) — 429 short-circuits.
4. JSON body parsing + V8 content-length guard — 400 `invalid_request` if content empty / >280 / missing.
5. V8 visibility resolution — `visible_posts` SELECT with bidirectional `user_blocks` exclusion → 404 `post_not_found` on miss.
6. V8 `INSERT INTO post_replies (post_id, author_id, content)`.
7. V10 notification emit (same DB transaction).
8. 201 response.

Steps 1–3 MUST run before any DB query. The 401, 400, and rate-limit branches MUST NOT execute a DB statement. The content-length guard (step 4) is an in-memory check so its placement after the limiter does not break the "limiter before DB" ordering, but placing the guard AFTER the limiter (rather than before) is intentional — see "Why" below.

**Why.** Two constraints anchor this ordering:

1. **Limiter before DB.** Rate limiting is the cheapest filter; running a `visible_posts` SELECT before the limiter wastes a DB round-trip on rate-limited callers. Same constraint as the like handler.
2. **Rate limiter before content-length guard, not after.** Putting the content guard before the limiter would let a Free attacker burn arbitrarily many "invalid_request" responses without consuming slots, then slip a valid 281-char-then-trimmed payload past the cap. By rate-limiting FIRST, we make the cap binding on attempt count, not on success count. The like handler doesn't have this constraint (likes have no body), so its ordering is auth → UUID → limiters → visibility → INSERT. Replies add the body-validation step but the same principle applies: the limiter is the throttle on attempts, not the throttle on successes.

A rate-limited request never reaches the content guard, never parses JSON, never queries `visible_posts`, and never inserts a row. A 404-resolution request DOES consume a slot (limiter ran successfully; the 404 is anti-abuse-positive). A 400-content-length-rejected request also consumes a slot (the limiter ran first; bad-content attempts count toward the cap — symmetric with the 404 path).

**Alternatives considered.**

- **Content guard before limiter** (i.e., reject empty/oversize content at JSON-parse time, then run the limiter): leaves an open window for an attacker to send 1000 oversized requests, none of which consume a slot, before sending one valid request that consumes slot 21. Rejected.
- **Limiter strictly between visibility and INSERT** (i.e., after the `visible_posts` SELECT): wastes a DB round-trip on rate-limited callers. Rejected — same reason as the like handler.

### Decision 6 — Reuse the existing `:infra:remote-config` flag pattern, no new module work

**Choice.** Add a single new key constant `premium_reply_cap_override` to whatever shape `:infra:remote-config` exposes for `premium_like_cap_override` (likely a typed accessor or a string-keyed reader). The reply handler reads it via the existing client.

**Why.** `like-rate-limit` already proved out the Remote-Config-override path: SDK error, malformed value, and unset all fall back to the canonical default. Reusing the exact pattern means zero design risk and one new key constant to register. The default-fallback contract is part of the existing `rate-limit-infrastructure` capability's behavior surface; this change does not extend that surface.

**Alternatives considered.**

- **Build a higher-level `RateLimitOverrideConfig` abstraction** with both `like` and `reply` keys: premature abstraction; only two keys exist. Rejected.

### Decision 7 — `ReplyRateLimitTest` as a new test class alongside `ReplyServiceTest`

**Choice.** Add a new integration test class `ReplyRateLimitTest` in `:backend:ktor`, tagged `database`, depending on the existing CI Redis service container that `like-rate-limit` introduced. The class covers the limiter behavior (cap enforcement, Premium skip, override flag, ordering, key shape, WIB rollover). The existing V8 `ReplyServiceTest` is unmodified — its scenarios continue to assert the V8 reply-endpoint contracts byte-for-byte.

**Why.** Mirrors the `LikeRateLimitTest`-vs-`LikeEndpointsTest` split that `like-rate-limit` shipped. Keeping the test classes separate keeps the V8 baseline test class simple and lets the rate-limit test class focus on the new behavior. The CI Redis container is already on the test job from PR #37; no infra setup needed.

**Alternatives considered.**

- **Add the rate-limit scenarios to `ReplyServiceTest`**: bloats the V8 class with concerns it shouldn't carry; harder to grep "what does V8 test cover" later. Rejected.
- **Skip the integration test class, only test the handler unit-style with an in-memory `RateLimiter` fake**: would miss Lua-script-level correctness (e.g., ZADD-then-EXPIRE-then-ZREMRANGEBYSCORE atomicity, hash-tag clustering on Upstash). The like-rate-limit precedent is integration-against-real-Redis, and we're following it. Rejected.

## Risks / Trade-offs

- **[Risk] V10 notification emit running inside the same DB transaction as the `post_replies` INSERT means a notification-INSERT failure rolls back the reply.** → V8 spec already documents this as the canonical V10 contract (see `post-replies/spec.md` § "Notification INSERT failure rolls back the reply"). The rate limiter runs BEFORE the transaction, so a rolled-back transaction still consumes a slot. This is intentional: a request that successfully passed the limiter and then hit a transient downstream failure should not be free-retried; the next attempt counts toward the cap. Bounded edge case (concurrent author hard-delete between visibility-resolution and emit), acceptable noise.
- **[Risk] Premium → Free downgrade mid-request.** → Identical to the like-rate-limit risk: if a RevenueCat webhook flips `users.subscription_status` between the auth-time principal load and the limiter check, the request observed Premium and skipped the limiter, letting a now-Free user exceed the cap by AT MOST one reply during the flip window. Bounded by the auth-time read (single read per request); the next request reads the fresh status. Acceptable.
- **[Risk] `premium_reply_cap_override` set to a value below the in-flight daily counter for a Free user mid-day.** → Same shape as the like flag's analogous risk. The override is read at request time, not at midnight; users whose counter already exceeds the new override start getting 429 immediately. Acceptable — the flag is for tightening (anti-abuse) or loosening (UX retention) and the abrupt cutoff matches Decision 28's intent.
- **[Risk] Lettuce sync-API call blocks the Ktor coroutine dispatcher.** → Mitigation: the existing `RedisRateLimiter` impl already wraps the call in `withContext(Dispatchers.IO)` (per `like-rate-limit/design.md` Decision 1). The reply handler's call site inherits this; no extra wrapping needed.
- **[Trade-off] One Redis round-trip per `POST /reply` (as opposed to two for `POST /like`).** → Lower per-request cost; same Lua-script execution shape; Upstash sub-millisecond SLA. No concern.
- **[Trade-off] No DB-side counter for per-day reply usage.** → Intentional: Redis is authoritative for rate limits. If Redis goes down, the limiter fails-closed (return 503) — better than fail-open which would let the cap be bypassed. Inherited from `rate-limit-infrastructure`.
- **[Documented expectation] Slot consumption on 404 `post_not_found` and 400 `invalid_request` (post-limiter validation failures).** → Both consume a slot. This matches the like-handler 404 behavior and is intentional anti-abuse. A Free attacker probing non-existent post UUIDs OR sending oversized content gets exactly 20 attempts per day, same as a legitimate user. Documented here so future reviewers don't mistake it for a bug.
- **[Out of scope, but worth recording] No DELETE rate limit on replies.** → The V8 author-only idempotent-204 soft-delete contract MUST stay intact. A hypothetical future "delete burst" anti-abuse limiter would be a separate change with its own design.

## Migration Plan

This is **infra-additive** — no DB migration, no breaking API change.

**Steps:**

1. Land the `premium_reply_cap_override` flag plumbing in `:infra:remote-config` (one new key constant).
2. Land the reply-handler rate-limit integration: limiter call before the V8 content guard / visibility resolution / INSERT, 429 envelope on `RateLimited`, Premium-tier skip via `viewer.subscription_status`.
3. Land `ReplyRateLimitTest` (database-tagged) covering the cap, the Premium skip, the override flag, the ordering, the key shape, and WIB rollover — mirrors `LikeRateLimitTest` scenario-by-scenario.

All three live in one feat commit on the same change branch (per the one-PR-per-change convention in [`openspec/project.md`](../../../openspec/project.md)).

**Rollout:**

- Cloud Run deploy uses the existing `KTOR_REDIS_URL` resolution (already provisioned and proven by `like-rate-limit`).
- Staging soak: 24 hours. Smoke test: a Free synthetic account hits 20 replies, 21st returns 429 with `Retry-After`, then waits the staggered window and the 22nd passes. The smoke script can be derived from `dev/like-rate-limit-smoke.sh` (the script that landed in PR #41 for `like-rate-limit`).
- Prod ship via tag `v*`. The `premium_reply_cap_override` flag stays unset at first (20/day Free baseline) — adjust if D7 metrics signal retention impact.

**Rollback:**

- The reply cap is gated on the existence of the limiter call in `ReplyService`. Revert is a clean PR revert: removing the limiter call from the reply handler immediately restores V8 behavior. The `premium_reply_cap_override` flag plumbing in `:infra:remote-config` is harmless if left in (an unread key); cleanup can ship in a follow-up.

## Open Questions

- **Should the `premium_reply_cap_override` ever apply to Premium users?** Decision: NO. Premium skips the daily limiter entirely (matches the like flag's behavior). The override is for Free-tier dial adjustment only. If Premium ever needs a cap (e.g., abuse signal from a single Premium account), that's a separate ban/suspension decision, not a Remote Config dial.
- **Should the daily cap include soft-deleted replies?** Decision: YES (the cap counts INSERTs, not net replies). A user who posts a reply, soft-deletes it, and tries to post another DOES consume two slots. This matches the like cap's "INSERT-counted, unlike doesn't release" rule and makes the cap predictable (every successful POST consumes one slot). Documented as a scenario in the post-replies spec delta.
- **Pre-existing divergence to flag, NOT fixed here:** none found at scoping time. The reply-cap line is consistent across `01-Business.md:53` (Free 20/day Unlimited), `02-Product.md:224` (Free 20/day, Premium unlimited), and `05-Implementation.md:1740` (Layer 2 table). If reconciliation surfaces during the review loop, file under `FOLLOW_UPS.md`.
