## Why

`post-replies` (V8) shipped without the canonical Free-tier 20/day reply cap that anchors the freemium upsell in [`docs/01-Business.md:53`](../../../docs/01-Business.md), [`docs/02-Product.md:224`](../../../docs/02-Product.md), and the Layer 2 table in [`docs/05-Implementation.md:1740`](../../../docs/05-Implementation.md). The cap was deferred because the foundational rate-limit infrastructure — Phase 1 item 24 in [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md) — hadn't shipped yet. V8 self-documented the gap at [`docs/05-Implementation.md:715`](../../../docs/05-Implementation.md): "rate limiting remain[s] deferred." Phase 2.3 in [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md) explicitly calls this out: "Reply rate limit (20/day Free) remains deferred — separate future change."

The `like-rate-limit` change ([PR #37](https://github.com/aditrioka/nearyou-id/pull/37)) shipped that infrastructure as the `rate-limit-infrastructure` capability (Redis-backed `RateLimiter` interface in `:core:domain`, Lettuce-backed `RedisRateLimiter` in `:infra:redis`, `computeTTLToNextReset(userId)` WIB-stagger helper, hash-tag key format, `RateLimitTtlRule` + `RedisHashTagRule` Detekt enforcement). That proposal explicitly named the **20/day reply cap** as one of four hard prerequisites the new infra was built to serve. This change executes that port to `POST /api/v1/posts/{post_id}/replies`.

Replies differ from likes in three structural ways that simplify this change:

1. **No burst limit.** Likes have a 500/hour burst clause (per [`docs/02-Product.md:223`](../../../docs/02-Product.md): "Both tiers cap 500/hour burst"). Replies do NOT — the canonical line at [`docs/02-Product.md:224`](../../../docs/02-Product.md) is just "Free 20/day, Premium unlimited" with no burst clause. So this change ships a daily-only limiter, no rolling-hour limiter.
2. **No idempotent re-action path.** The `POST /like` handler treats `INSERT ... ON CONFLICT DO NOTHING` returning zero rows as a no-op and releases the slot via `releaseMostRecent`. `POST /replies` has no equivalent — every successful POST is a real new row in `post_replies` (no UNIQUE constraint on `(post_id, author_id)` content). So `releaseMostRecent` is NOT needed at the reply call site.
3. **Soft-delete is not rate-limited.** `DELETE /api/v1/posts/{post_id}/replies/{reply_id}` is the V8 author-only idempotent-204 soft-delete. It MUST stay un-rate-limited — the V8 contract guarantees idempotent 204 regardless of state, and a rate-limited delete would let attackers prevent users from deleting their own replies under load.

## What Changes

- **MODIFIED: `POST /api/v1/posts/{post_id}/replies`** — one limiter runs in order before any DB work:
  - **Daily**: 20/day Free, unlimited Premium. Key `{scope:rate_reply_day}:{user:<uuid>}`. TTL via `computeTTLToNextReset(userId)`. Free vs Premium gating reads `users.subscription_status` from the request-scope `viewer` principal populated by the auth-jwt plugin (NOT a fresh DB SELECT — same constraint as the like handler).
  - On limit hit: HTTP 429 with `error.code = "rate_limited"` and a `Retry-After` header set to seconds-until-oldest-counted-event-ages-out (the daily TTL window). The 429 envelope matches the like-rate-limit precedent byte-for-byte.
  - Limiter ordering: auth → UUID validation → daily rate limiter → existing V8 visibility resolution (`visible_posts` SELECT with bidirectional `user_blocks` exclusion) → existing V8 content-length guard → `INSERT INTO post_replies` → V10 notification emit → 201 success. The limiter MUST run before any DB query.
  - 404 `post_not_found` consumes a slot (does NOT release) — matches the like-rate-limit precedent: the limiter ran successfully; the 404 is a downstream business decision and counts toward the cap as anti-abuse.
  - 400 `invalid_uuid` / 400 `invalid_request` (content guard) / 401 unauthenticated do NOT consume a slot — they all run BEFORE the limiter (matching the like handler's ordering).
- **NEW: `premium_reply_cap_override` Firebase Remote Config flag** — when set to a positive integer N, overrides the 20/day Free cap server-side without a mobile release (mirrors the `premium_like_cap_override` Decision 28 pattern in [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md)). Unset / malformed / ≤ 0 / SDK error → fall back to default 20. Premium users are unaffected (skip the daily limiter entirely).
- **DELETE `/api/v1/posts/{post_id}/replies/{reply_id}` is NOT rate-limited.** The V8 author-only idempotent-204 soft-delete contract MUST be preserved verbatim. A future "delete burst" anti-abuse limiter would be a separate change.
- **GET `/api/v1/posts/{post_id}/replies` is NOT rate-limited.** Read-side timeline limits (50/session soft + 150/hour hard from [`docs/05-Implementation.md`](../../../docs/05-Implementation.md)) cover read traffic; per-endpoint read limiters are explicitly deferred per the like-rate-limit precedent (which also did not rate-limit GET `/api/v1/posts/{post_id}/likes`).

## Capabilities

### New Capabilities
None. This change consumes the existing `rate-limit-infrastructure` capability (shipped in `like-rate-limit`) without extending it.

### Modified Capabilities
- `post-replies`: adds the daily-cap, `premium_reply_cap_override` Remote Config gate, 429-with-`Retry-After` contract, and limiter-ordering requirements to `POST /api/v1/posts/{post_id}/replies`. The delta uses `## ADDED Requirements` exclusively (not `## MODIFIED`) because every new requirement is strictly additive: the existing V8 requirement "POST replies — INSERT and success response" continues to describe the un-rate-limited 201 success path verbatim, and the new "Limiter ordering" requirement layers in front of it without altering its 201-on-success contract. This mirrors the `like-rate-limit` precedent on `post-likes` and the V10 `in-app-notifications` precedent on emit-side-effect requirements.

## Impact

- **Code**:
  - `ReplyService` (or its handler) gains one pre-DB rate-limit check (daily). The integration point is symmetric with the existing `LikeService` integration shipped in `like-rate-limit`.
  - New flag plumbing for `premium_reply_cap_override` in the existing `:infra:remote-config` module (one new key alongside `premium_like_cap_override`).
- **DB**: none. No new tables, no new columns, no new migrations.
- **Dependencies**: none new. Lettuce is already on the classpath from `like-rate-limit`.
- **API**: `POST /api/v1/posts/{post_id}/replies` adds 429 to its response set; the 201 success path is unchanged. No request shape change. Mobile already handles 429 generically (timeline rate-limit screen and the like-button surface from PR #37); the reply-button-specific copy can be tightened in a follow-up UI change (out of scope here).
- **Infra**: requires the staging/prod Upstash Redis project to be reachable from Cloud Run (already provisioned in Pre-Phase 1; same `staging-redis-url` / prod `redis-url` Secret Manager slots already used by the like cap and the ported V9 reports limiter).
- **CI**: no new lint rules. `RateLimitTtlRule` + `RedisHashTagRule` (from `like-rate-limit`) already cover the new `{scope:rate_reply_day}:{user:<uuid>}` call site by virtue of the daily-key shape (`*_day}` substring) and the hash-tag format requirement. The new reply-handler test class will exercise both rules' positive-pass paths via fixture coverage.
- **Out of scope (explicitly deferred — each is a separate change)**:
  - Post 10/day cap (the next obvious infra consumer)
  - Search 60/hour Premium cap
  - Follow 50/hour cap
  - Block 30/hour cap
  - Chat 50/day cap
  - Username availability probe 3/day cap
  - Layer 1 guest pre-issuance limits (attestation-gated)
  - Layer 4 per-area anti-spam limit (50 posts in 1 km / 1 hour)
  - Mobile UI tightening of the 429 response on the reply surface (the generic 429 screen suffices for V12-era staging soak)
  - Read-side per-endpoint limits on GET `/replies`
