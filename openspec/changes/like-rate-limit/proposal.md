## Why

`post-likes` (V7) shipped without the canonical Free-tier 10/day + 500/hour-burst like cap that anchors the freemium upsell in [`docs/01-Business.md:55`](../../../docs/01-Business.md), [`docs/02-Product.md:223`](../../../docs/02-Product.md), and the Layer 2 table in [`docs/05-Implementation.md:1733`](../../../docs/05-Implementation.md). The cap was deferred because the foundational rate-limit infrastructure (Phase 1 item 24 in [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md) — a real Redis client, a shared `RateLimiter` interface, and the `computeTTLToNextReset(user_id)` WIB-stagger helper) hadn't shipped yet. The `reports` change (V9) ran into the same gap and shipped its own in-process rate limiter with a self-documented note that the Redis-backed implementation was deferred to "a separate change" — see [`backend/ktor/src/main/kotlin/id/nearyou/app/moderation/ReportRateLimiter.kt:13-19`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/moderation/ReportRateLimiter.kt). This change closes both loops: it ships the infrastructure plus the like cap as the first user-facing consumer, and ports the V9 reports limiter onto the new infra so the in-process stopgap can be retired.

The infrastructure shipped here is also a hard prerequisite for at least four already-planned changes — the 20/day reply cap, the 10/day post creation cap, the 60/hour Premium search rate limit, and the 50/hour follow rate limit — each of which will reuse the same primitives without re-litigating the design.

## What Changes

- **NEW: `:infra:redis` module** — Lettuce-based Redis client wired through Koin, configured via the existing `secretKey(env, name)` helper. Connects to Upstash in staging/prod and the local Docker Compose Redis in dev.
- **NEW: `RateLimiter` interface + Redis-backed implementation** — supports two windowing strategies: daily-with-WIB-stagger (TTL via `computeTTLToNextReset`) and rolling-hour. Returns `Allowed(remaining)` or `RateLimited(retryAfterSeconds)`. Provides a `releaseMostRecent(userId)` escape hatch for the no-op-idempotent path (mirrors the V9 `ReportRateLimiter` precedent).
- **NEW: `computeTTLToNextReset(userId)` shared helper** — implements the WIB-stagger formula from [`docs/05-Implementation.md:1751-1755`](../../../docs/05-Implementation.md): `offset_seconds = hash(user_id) % 3600`, effective reset distributed linearly across 00:00–01:00 WIB. Lives in `:core:domain` (no Redis dependency) so daily-rate-limit call sites and tests can use it without an `:infra:redis` import.
- **MODIFIED: `POST /api/v1/posts/{post_id}/like`** — two limiters run in order before any DB work:
  - **Daily**: 10/day Free, unlimited Premium. Key `{scope:rate_like_day}:{user:<uuid>}`. TTL via `computeTTLToNextReset`. Free vs Premium gating reads `users.subscription_status` (existing column from V3).
  - **Burst**: 500/hour, both tiers (anti-bot, per [`docs/02-Product.md:223`](../../../docs/02-Product.md) "Both tiers cap 500/hour burst"). Key `{scope:rate_like_burst}:{user:<uuid>}`. Sliding-hour window.
  - On limit hit: HTTP 429 with `error.code = "rate_limited"` and a `Retry-After` header set to seconds-until-oldest-counted-event-ages-out. Daily limiter exhaustion gets the daily TTL; burst limiter exhaustion gets the rolling-hour delta.
  - Idempotent re-like (the existing `INSERT ... ON CONFLICT DO NOTHING` returning 0 rows) MUST NOT consume a slot. The handler calls `releaseMostRecent` on both limiters before returning 204, mirroring the V9 reports 409 release path.
- **NEW: `premium_like_cap_override` Firebase Remote Config flag** — when set to a positive integer, overrides the 10/day Free cap server-side without a mobile release (Decision 28 in [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md)). Default unset = 10. Premium users are unaffected.
- **NEW: two Detekt rules in `:lint:detekt-rules`** (mirroring the `CoordinateJitterRule` + `BlockExclusionJoinRule` allowlist-by-path + annotation pattern from PR #32):
  - `RateLimitTtlRule` — every Kotlin call site that constructs a daily Redis rate-limit key MUST also call `computeTTLToNextReset(...)` for the TTL. Hardcoded `86400`, `Duration.ofDays(1)`, or midnight math at a daily limiter site fails CI. Allowlist via `/src/test/` path + `@AllowDailyTtlOverride("<reason>")` annotation.
  - `RedisHashTagRule` — any Kotlin string literal that builds a rate-limit key (matching pattern `rate:*` or starting with `{scope:`) MUST contain a `{scope:<value>}` hash tag segment. Same allowlist pattern.
- **MODIFIED: `ReportRateLimiter` (V9)** — port from in-process `ConcurrentHashMap` to the new `RateLimiter` interface. Public behavior identical (the existing V9 test suite must still pass without modification). Removes the "deferred to a separate change" comment.

## Capabilities

### New Capabilities

- `rate-limit-infrastructure`: shared Redis-backed rate-limit primitives (interface + implementation), the `computeTTLToNextReset(userId)` WIB-stagger helper, the hash-tag key format requirement, and the two Detekt rules (`RateLimitTtlRule`, `RedisHashTagRule`) that enforce the conventions at every call site.

### Modified Capabilities

- `post-likes`: adds the daily-cap, burst-cap, `premium_like_cap_override` Remote Config gate, 429-with-Retry-After contract, idempotent-re-like-no-slot-consumed, and limiter-ordering requirements to `POST /api/v1/posts/{post_id}/like`. The delta uses `## ADDED Requirements` exclusively (not `## MODIFIED`) because every new requirement is strictly additive: the existing V7 requirement "POST /like creates a like (idempotent)" continues to describe the un-rate-limited success path verbatim, and the new "Limiter ordering" requirement layers in front of it without altering its 204-on-success contract. Both coexist at archive — un-rate-limited callers still see the V7 contract; rate-limited callers see the new 429 contract. This mirrors the V10 in-app-notifications precedent, which also added emit-side-effect requirements without rewriting the underlying endpoint contracts.
- `reports`: notes that the V9 in-process rate limiter has been ported to the shared `RateLimiter` infra. No client-visible behavior change. (The 10/hour cap, hash-tag key shape, and 409-release contract are preserved verbatim.)

## Impact

- **Code**:
  - New module `:infra:redis` (Lettuce + Koin module).
  - New interface `RateLimiter` (and Redis-backed impl) reachable from `:backend:ktor`.
  - New helper `computeTTLToNextReset(userId)` in `:core:domain` (and matching unit tests).
  - New flag plumbing for `premium_like_cap_override` in the existing `:infra:remote-config` module.
  - New Detekt rules + their fixtures under `:lint:detekt-rules`.
  - `LikeService` (or its handler) gains two pre-DB rate-limit checks.
  - `ReportRateLimiter.kt` rewritten to delegate to the new interface; the V9 test suite is the regression gate.
- **DB**: none. No new tables, no new columns, no new migrations.
- **Dependencies**: adds the Lettuce client to `gradle/libs.versions.toml` (single new pin, recorded per the existing pinning policy in [`docs/09-Versions.md`](../../../docs/09-Versions.md)).
- **API**: `POST /api/v1/posts/{post_id}/like` adds 429 to its response set; the 204 success path is unchanged. No request shape change. Mobile already handles 429 generically (timeline rate-limit screen), but the like-button-specific copy may be tightened in a follow-up UI change (out of scope here).
- **Infra**: requires the staging/prod Upstash Redis project to be reachable from Cloud Run (already provisioned in Pre-Phase 1 per `staging-redis-url` Secret Manager slot); local dev uses the existing Docker Compose Redis service.
- **CI**: Detekt rule additions; `:lint:detekt-rules:test` gains positive-fail fixtures for both new rules. No expected impact on existing CI runtime beyond the small fixture set.
- **Out of scope (explicitly deferred)**: reply 20/day cap, post 10/day cap, Layer 1 guest pre-issuance limits, search 60/hour, follow 50/hour, block 30/hour, username availability probe 3/day, chat 50/day. Each is a separate change that consumes this infra.
