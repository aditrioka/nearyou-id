## Why

The three timeline read endpoints (`GET /api/v1/timeline/{nearby,following,global}`) currently have no per-user read-side rate limit, leaving the canonical Layer 2 timeline-read caps (`docs/05-Implementation.md:1159` — *"Timeline read Free | 150 posts/h rolling (hard); 50/session (soft)"*) undocumented in spec form and unenforced in code. The infrastructure to enforce them shipped in `like-rate-limit` (Lettuce-backed `RedisRateLimiter`, hash-tag key standard, `RateLimiter` interface in `:core:domain`); applying it to the timeline trio is the natural next step that completes Phase 1 item 30 (*"Timeline read limit documentation: 50/session soft + 150/hour hard (authoritative)"* — [`docs/08-Roadmap-Risk.md:128`](../../../docs/08-Roadmap-Risk.md)) and Phase 2's read-side throttling story. Without it, an abusive client (or a buggy client in a tight scroll loop) can pull the entire post graph at the user's per-IP authenticated baseline (1000 req/min) — wasting Supabase compute, distorting the cost forecast for soft-launch capacity planning, and blocking the Premium retention story (Free should hit a soft nudge → upsell → conversion; today there is no nudge).

## What Changes

- **New capability** `timeline-read-rate-limit` covering the per-user soft + hard read-side cap shared across all three timeline endpoints. Free: 50 posts/session soft + 150 posts/hour hard. Premium: unconditionally exempt.
- **Counts posts returned** (not requests) — incremented by the size of the response array on each successful timeline read. A page of 20 posts increments by 20; an empty page increments by 0. Pagination accumulates correctly.
- **Soft cap behaviour**: response carries an upsell flag (response-body `upsell.soft = true`) but the post array is still returned in full. Does NOT block; the mobile UI surfaces an upsell banner.
- **Hard cap behaviour**: response array is empty (`posts: []`) + `upsell.hard = true`. Status remains 200 OK (UX prefers an empty list with an upsell banner over a 429 error blocking the entire screen).
- **Single per-user bucket** aggregating across all three endpoints. Per the canonical Redis key shape `timeline_rolling:{user:<user_id>}` ([`docs/05-Implementation.md:803`](../../../docs/05-Implementation.md)) — there is one rolling counter per user, not three. Reading 50 posts from Nearby + 50 from Following + 50 from Global = 150 → hourly hard cap reached. This matches the docs and is the only interpretation consistent with a single `{user:<user_id>}` axis key.
- **Session axis** keyed by the existing `X-Session-Id` header (already in use by [`docs/05-Implementation.md:801`](../../../docs/05-Implementation.md)) AFTER validation against `^[A-Za-z0-9-]{1,64}$` to prevent Redis-key injection. Hash-tag format: `{scope:rate_timeline_session}:{session:<user_id>__<session_id>}` and `{scope:rate_timeline_rolling}:{user:<user_id>}` per the established `{scope:<value>}:{<axis>:<value>}` convention enforced by `RedisHashTagRule` (the canonical doc lines `docs/05-Implementation.md:802-806` use the informal pre-hash-tag shape `timeline_rolling:{user:<user_id>}` for readability — the implementation renders it in the canonical hash-tag form). The session bucket encodes both user-id and session-id INTO the partition-axis value via a `__` separator (axis name `session`) so the rendered literal matches the strict `RedisHashTagRule` regex `^\{scope:[^{}]+}:\{[^{}:]+:[^{}]+}$` exactly — no rule modification needed.
- **TTL**: 1 hour fixed for both keys. NOT WIB-staggered — these are hourly limits, not daily — so no `computeTTLToNextReset(userId)` call. The existing `RateLimitTtlRule` Detekt rule fires only on key strings containing the substring `_day}`; the new keys do not match.
- **Three timeline-route specs MODIFIED** to add a single requirement each — *"the route delegates read-rate-limit accounting to `timeline-read-rate-limit`"*. Their existing read contracts (cursor format, block exclusion, `visible_*` view usage, etc.) are unchanged.
- **Out-of-scope (explicitly deferred)**:
  - Guest timeline reads (`10/session + 30/hour`, keyed by guest JWT `jti`) — requires the Layer 1 attestation-gated guest-session JWT issuance work (Phase 1 item 25), which has not shipped. The status note at [`docs/02-Product.md:181-183`](../../../docs/02-Product.md) records the deferral; this proposal preserves it. Once guest sessions ship, a follow-up change MODIFIES `timeline-read-rate-limit` to add the Guest-axis bucket.
  - Layer 4 per-area anti-spam read counter (separate concern; not in any docs as a Phase 1/2 commitment).
  - Premium "unlimited" hard ceiling — Premium is unconditionally exempt; we accept that an abusive Premium account pulling the entire timeline is bounded only by the per-IP authenticated baseline + database cost. Re-evaluation triggered if a single Premium account contributes >1% of read load (operational signal, not a spec gate).

## Capabilities

### New Capabilities

- `timeline-read-rate-limit`: per-user Layer 2 read-side rate limiting on the three authenticated timeline endpoints. Owns the bucket-key contract, soft/hard cap semantics, response upsell-flag shape, Premium bypass rule, X-Session-Id header contract.

### Modified Capabilities

- `nearby-timeline`: ADD a single requirement — the route delegates read-rate-limit accounting to `timeline-read-rate-limit` per the cap-and-flag contract. No change to cursor/block-exclusion/spatial query behaviour.
- `following-timeline`: ADD the same delegation requirement.
- `global-timeline`: ADD the same delegation requirement.

## Impact

- **Code touched**: `backend/ktor/src/main/kotlin/id/nearyou/app/timeline/` — three route handlers (Nearby, Following, Global) gain a pre-response rate-limit check + post-response increment, mediated through a new shared service. Likely a new `TimelineReadRateLimiter` class composing `RateLimiter` (the existing `:core:domain` interface). DTO additions to surface `upsell.soft` / `upsell.hard` flags.
- **Schema**: zero migrations. All state is in Redis (Upstash); no new Postgres column or index.
- **Redis keys**: two new key families — `{scope:rate_timeline_session}:{session:<user_id>__<session_id>}` (TTL 1h) and `{scope:rate_timeline_rolling}:{user:<user_id>}` (TTL 1h). Keys conform to the existing hash-tag standard's strict regex; no `RedisHashTagRule` modification needed.
- **Detekt**: no new rules. `RateLimitTtlRule` correctly does NOT fire on these hourly endpoints (it scopes to daily-rate-limit call sites). `RedisHashTagRule` enforces the new keys' shape automatically.
- **Tests**: `:backend:ktor` integration tests covering soft cap, hard cap, Premium bypass, X-Session-Id missing handling, and cross-endpoint accumulation (50 reads each on Nearby + Following + Global → hourly hard cap). Aligned with the existing `LikeRateLimitTest` / `ReplyRateLimitTest` / `ChatRateLimitTest` patterns.
- **API contract change**: response shape gains an optional `upsell: { soft?: boolean, hard?: boolean }` object. Backwards-compatible with existing mobile clients (the field is additive and the mobile app currently has no read-rate-limit awareness — DESIGN per [`CLAUDE.md`](../../../CLAUDE.md) § Mobile + Admin Status). Spec-level change, not a breaking change.
- **Operational**: smoke verification against staging Supabase + Upstash Redis required pre-archive (per `/opsx:apply` Section 6 + the pre-archive smoke convention codified in [`openspec/project.md`](../../../openspec/project.md) § Staging deploy timing). `dev/scripts/` may need a `smoke-timeline-read-rate-limit.sh` if existing rate-limit smokes don't cover this surface.
- **Docs**: zero amendments needed in canonical `docs/`. The proposal aligns with `docs/05-Implementation.md:799-806` + `:1159-1160` verbatim. The existing [`docs/02-Product.md:181-183`](../../../docs/02-Product.md) status note about "Guest read-only access + 10/session + 30/hour caps remain deferred" remains accurate; this change ships the authenticated half. Post-archive, a one-line tick under [`docs/10-Setup-Checklist.md`](../../../docs/10-Setup-Checklist.md) Phase 1 #30 (if a checklist entry exists) acknowledges the spec-form authority.
