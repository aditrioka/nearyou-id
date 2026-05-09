## Context

The three timeline read endpoints (`GET /api/v1/timeline/{nearby,following,global}`) ship today without per-user read-side rate limits. The canonical Layer 2 caps live in [`docs/05-Implementation.md:1159-1160`](../../../docs/05-Implementation.md) — *"Timeline read Free | 150 posts/h rolling (hard); 50/session (soft)"* + *"Timeline read Guest | 30 posts/h rolling (hard); 10/session (soft)"* — and the Redis key shapes live in [`docs/05-Implementation.md:799-806`](../../../docs/05-Implementation.md) — `timeline_offset:{user:<user_id>}:<session_id>` (TTL 1h, soft) and `timeline_rolling:{user:<user_id>}` (TTL 1h, hard). The infrastructure to implement these — Lettuce-backed `RedisRateLimiter`, `RateLimiter` interface in `:core:domain`, hash-tag key standard, `RedisHashTagRule` Detekt rule — shipped in `like-rate-limit` and was further refined by `rate-limit-ip-hashing` + `rate-limit-infrastructure-success-path-log`. Three per-endpoint write-side limits (likes, replies, chat) plus the report limiter and the `/health/*` anti-scrape limiter all consume that infrastructure today; this change adds the read-side timeline limiter as the next consumer.

The Guest path (10/session + 30/hour, keyed by guest JWT `jti`) is **explicitly deferred** — it requires the Layer 1 attestation-gated guest-session JWT issuance work (Phase 1 item 25 per [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md)), which has not shipped. This change ships the authenticated half; a follow-up MODIFIES `timeline-read-rate-limit` to add the guest axis once the issuance work lands.

## Goals / Non-Goals

**Goals:**

- Enforce per-user 50-posts-per-session soft + 150-posts-per-hour rolling hard caps on the three authenticated timeline endpoints, faithful to the canonical cap values in `docs/05-Implementation.md:1159`.
- Surface an upsell signal in the response (soft cap reached → banner; hard cap reached → empty list + banner) without a 429 error code, so the mobile UX prefers an empty list with banner over a hard error.
- Use a single per-user rolling bucket aggregating reads across all three timeline tabs, matching the canonical `timeline_rolling:{user:<user_id>}` key shape (a single key, not three).
- Use the existing `RateLimiter` interface in `:core:domain` and the Lettuce-backed implementation in `:infra:redis` — no new infrastructure, no new modules.
- Keep Premium users unconditionally exempt (consistent with all other Layer 2 limiters; no daily-WIB-stagger because timeline limits are hourly, not daily).
- Faithfully follow the `{scope:<value>}:{<axis>:<value>}` hash-tag standard so `RedisHashTagRule` passes.

**Non-Goals:**

- Guest timeline rate limit (`10/session + 30/hour` keyed by guest JWT `jti`). Deferred until guest-session issuance lands; a follow-up change MODIFIES this capability with the new requirement.
- Layer 4 per-area anti-spam read counter (separate concern; not in any docs as a Phase 1/2 commitment).
- Premium "unlimited" hard ceiling. Premium is unconditionally exempt; an abusive Premium account is bounded only by the per-IP authenticated baseline (`docs/05-Implementation.md:1142`: 1000 req/min/IP) + downstream DB cost.
- Timeline-tab-transition counter (a separate UX-side metric — out of scope).
- Mobile UI changes. The mobile `:mobile:app` surface is DESIGN per [`CLAUDE.md`](../../../CLAUDE.md) § Mobile + Admin Status; client-side banner rendering will land alongside the broader mobile-screen build.

## Decisions

### Decision 1: Single per-user rolling bucket aggregating across all three timeline endpoints

Per [`docs/05-Implementation.md:803`](../../../docs/05-Implementation.md), the canonical key is `timeline_rolling:{user:<user_id>}` — a single user-axis key, NOT three (one-per-endpoint). All three timeline routes (`Nearby`, `Following`, `Global`) consume the same rolling bucket.

**Rationale:** A user's "150 posts/hour" budget should be the cross-tab total, not 450/hour distributed across 3 tabs. The canonical key shape is the source of truth and matches this interpretation. A user pulling 50 posts each on Nearby + Following + Global = 150 → rolling cap reached.

**Alternative considered:** Per-endpoint buckets (`timeline_rolling:{scope:nearby}:{user:U}` × 3). Rejected because it 3× the canonical limit without a docs basis and complicates cross-tab anti-abuse.

### Decision 2: Count posts returned, not requests

The bucket increments by `posts.size` per successful timeline response (typically 0–30 entries per request, per the existing per-page cap of 30). Counts ARE NOT requests-as-units. Implementation: the route handler invokes `tryAcquire` once for the pre-check and `posts.size - 1` additional `tryAcquire` calls (best-effort, see Decision 3) to bump the bucket by the actual response count.

**Rationale:** The canonical docs say "150 posts/h" and "50/session". A user reading 5 pages of 30 posts each (= 150 posts) should hit the cap on the 6th request, not the 31st (5 requests = 150 posts ≠ 5 requests = 5 cap). Counting posts honors the spec literally.

**Alternative considered:** Count requests (5 req/h hard, 2 req/session soft). Rejected because the canonical spec is explicit about post-count, not request-count, and request-counting drifts the actual cap by the page-size variance (a request returning 12 posts at end-of-feed still consumes a full slot, under-counting the user's actual read volume).

### Decision 3: Atomic admit-or-reject via the existing `tryAcquire` interface — no `tryAcquireBatch` introduced

The existing `RateLimiter.tryAcquire(userId, key, capacity, ttl)` is the only seam used. The route handler:
1. Pre-checks via a single `tryAcquire` call. If `RateLimited`, return empty + `upsell.hard = true`. If `Allowed`, 1 slot is consumed.
2. Runs the timeline DB query, gets `N` posts.
3. Issues `N - 1` additional best-effort `tryAcquire` calls (parallel coroutines, fire-and-forget on outcome). Each call adds an entry to the bucket if there's room; some may return `RateLimited` once the cap is reached, but the request is already mid-response and posts are already returned.

**Trade-off:** Over-admission tolerance is up to `N - 1 = 29` posts when a user is near the cap (e.g., user at 149/150 reads 30 posts; bucket becomes 150 because additional calls return RateLimited; the user's NEXT request will be cap-blocked). For MVP volume this is acceptable bound.

**Alternative considered:** Add `tryAcquireBatch(userId, key, count, capacity, ttl)` to the `RateLimiter` interface. Rejected from this change's scope to keep the proposal tight; a future change MAY add the batch primitive if a second batch-counting consumer appears (rule of three). The spec captures the BEHAVIOR (atomic-admit semantics where possible, best-effort batch otherwise), not the underlying primitive shape.

**Alternative considered:** Single ZADD with multiple member arguments via a custom Lua script in `:infra:redis`. Rejected — would require a `:infra:redis` change parallel to this one, and the over-admission tolerance is acceptable.

### Decision 4: Soft cap signal via response-body `upsell.soft` boolean (not a header, not a 429)

The response body gains an optional top-level `upsell` object:

```json
{
  "posts": [...],
  "next_cursor": "...",
  "upsell": {
    "soft": true,
    "hard": false
  }
}
```

`upsell.soft = true` when the post-increment session bucket count is `>= 50`. `upsell.hard = true` when the rolling bucket is already at capacity AT REQUEST TIME (so `posts: []` is returned). Both flags are optional in the response — when both are false, the `upsell` object MAY be omitted entirely (additive, no breaking change for clients that don't read it).

**Rationale:** The mobile client already needs to consume the response body for `posts` + `next_cursor`; adding a sibling `upsell` object is the most natural shape. Headers (`X-Timeline-Upsell-Soft`) would force the mobile client to inspect HTTP headers in addition to body, friction we don't need. A 429 status code is too aggressive — UX prefers an empty list with banner over a hard error.

**Alternative considered:** Response header `X-Timeline-Upsell-Soft: true`. Rejected as above.

**Alternative considered:** Always include `upsell` (even when both flags are false). Rejected for backwards-compatibility with existing clients — additive-only is safer. Mobile clients will parse `?.upsell?.soft ?? false` defensively.

### Decision 5: Hard cap returns 200 OK + empty array, NOT 429

When the rolling bucket is at capacity at request time, the response is HTTP 200 with `posts: []`, `next_cursor: null`, `upsell.hard: true`. Status is NOT 429.

**Rationale:** Per UX heuristic and the docs' "soft per-session + hard rolling per-hour" framing, the canonical user experience at the cap is "empty list + 'You've hit your hourly limit, upgrade to Premium for unlimited reads' banner". A 429 status would force the mobile client to render an error screen, which is a worse UX than an empty list with a context-aware banner. The route is a READ — a 429 conventionally means "you sent too many requests"; here the user simply has no remaining read budget, which is closer to "no data" semantics.

**Alternative considered:** 429 with `Retry-After`. Rejected — the like/reply/chat write limiters return 429 because the action FAILED; here the read action is not "failing", the budget is just exhausted.

### Decision 6: `X-Session-Id` missing → fall back to a deterministic pseudo-session bucket keyed on `user_id` alone

If the request omits `X-Session-Id`, the soft-cap bucket key falls back to `rate:timeline_session:{user:<user_id>}:no-session` — a deterministic per-user, no-session-id bucket. Soft cap still applies (50 posts trigger the upsell flag), but all of the user's reads in that hour share the same fallback bucket regardless of how many sessions they had.

**Rationale:** Production mobile clients SHOULD set `X-Session-Id` per the existing `docs/05-Implementation.md:801` contract, but DESIGN-stage mobile (`:mobile:app` per [`CLAUDE.md`](../../../CLAUDE.md)) doesn't. Web clients, curl-style integrations, and admin tooling won't either. Rejecting with 400 would block legitimate MVP development; falling back to a deterministic bucket preserves the soft-cap guarantee at minor precision cost.

**Alternative considered:** Reject with HTTP 400 `missing_session_id`. Rejected — would block early mobile development and admin tooling; fallback is more pragmatic for MVP.

**Alternative considered:** Skip the soft-cap entirely when no session id is present. Rejected — the user could deliberately omit the header to dodge the soft-cap UX nudge. Fallback bucket closes the loophole.

### Decision 7: Fixed 1-hour TTL for both buckets, no `computeTtlToNextReset`

Both `rate:timeline_session:{user:U}:<sid>` and `rate:timeline_rolling:{user:U}` use `Duration.ofHours(1)` directly. The existing `computeTtlToNextReset(userId)` helper applies only to DAILY caps that need WIB-stagger to prevent thundering-herd at midnight.

**Rationale:** Hourly limits naturally distribute across the hour by user activity timing; midnight-stagger doesn't apply. The `RateLimitTtlRule` Detekt rule (per [`openspec/specs/rate-limit-infrastructure/spec.md`](../../../openspec/specs/rate-limit-infrastructure/spec.md)) fires only on keys containing `_day}` markers and correctly skips hourly keys.

### Decision 8: Hash-tag key shapes per the existing `{scope:<value>}:{<axis>:<value>}` standard

- Session bucket: `rate:timeline_session:{user:<user_id>}:<session_id>` — hmm, this needs careful thought. The existing standard uses `{scope:<value>}:{<axis>:<value>}` where BOTH segments are wrapped. Our session-id is the third segment. Closest conformant shape:

  `{scope:rate_timeline_session}:{user:<user_id>}` — hash-tag-conformant, with `<session_id>` either appended outside the hash tags or stored as the entry's nonce (since the bucket is per-user-per-session, it's a unique key per session).

  Actual shape: `{scope:rate_timeline_session}:{user:<user_id>}:<session_id>` — extends the existing `{scope:rate_X}:{user:U}` shape with a trailing `:<session_id>` segment. CRC16 hashing is determined by the FIRST hash tag pair (`{scope:rate_timeline_session}` vs `{user:U}`) — both hash tags resolve to the same slot, the trailing `:<session_id>` does not affect slot selection. This conforms to `RedisHashTagRule`'s allowance pattern (matches `{scope:...}:{user:...}` regardless of trailing segments).

- Rolling bucket: `{scope:rate_timeline_rolling}:{user:<user_id>}` — vanilla hash-tag, identical shape to existing daily/hourly keys.

**Rationale:** Both keys conform to the established standard. The session-id is intentionally outside the hash tags so cluster slot selection is determined by `(scope, user)`; the session-id only varies the entry within the same Redis slot (no CROSSSLOT risk).

### Decision 9: Limiter ordering — pre-check before DB, post-increment after response

1. Auth (existing `auth-jwt` plugin).
2. Path/query parameter validation (existing — invalid `cursor`, etc.).
3. **Premium short-circuit**: read `viewer.subscriptionStatus` from the auth-time principal. If `premium_active` or `premium_billing_retry`, SKIP both buckets entirely (no Redis calls).
4. **Pre-check on rolling bucket**: `tryAcquire(userId, "{scope:rate_timeline_rolling}:{user:U}", capacity = 150, ttl = 1.hour)`. If `RateLimited`, return HTTP 200 with `posts: []`, `next_cursor: null`, `upsell.hard: true`. STOP. (No DB query executes; saves Postgres + Redis cost on the cap-hit path.)
5. **Pre-check on session bucket**: `tryAcquire(userId, "{scope:rate_timeline_session}:{user:U}:<sid>", capacity = 50, ttl = 1.hour)`. The session bucket is purely for soft-cap detection; if it returns `RateLimited`, the response will have `upsell.soft: true` set later. The session pre-check does NOT block the response — it just records that the soft cap was reached. (We still call `tryAcquire` so the bucket gets a slot consumed.)
6. Execute the timeline DB query → `posts: List<TimelinePost>` of length `N` (0 ≤ N ≤ 30).
7. **Post-increment**: issue `N - 1` additional best-effort `tryAcquire` calls on each bucket (rolling + session) in parallel coroutines. Outcome of these calls is ignored (the response is already shaped).
8. Build response: `posts`, `next_cursor`, and `upsell.soft = (sessionBucketSize >= 50)` / `upsell.hard = false` (by definition not hard-capped on this request — the pre-check at step 4 admitted us). `upsell` object omitted entirely if both flags are false.
9. Return HTTP 200 with the response body.

Steps 4 + 5 MUST run BEFORE the timeline DB query (no Postgres round-trip on cap-hit). Step 7 runs AFTER the response is built (post-increment is non-blocking on the response build).

**Rationale:** Pre-check before DB is cost-saving and prevents expensive scans on capped users. Post-increment after response is correct because we count posts returned (not pre-allocated). Step 5 is a "pre-check that always admits" — it consumes 1 slot but never blocks; the soft cap is purely advisory.

### Decision 10: Premium bypass is unconditional (both buckets skipped entirely)

Premium users (`subscription_status IN ('premium_active', 'premium_billing_retry')`) skip BOTH buckets entirely — no Redis calls at all. The bucket keys for Premium users are NEVER written. Mirrors the existing like/reply/chat limiter pattern.

**Rationale:** Premium is "unlimited reads" per [`docs/01-Business.md:18`](../../../docs/01-Business.md). Skipping both buckets keeps the Premium read path Redis-free (lower latency, lower cost). The `subscription_status` is read from the auth-time principal (the `auth-jwt` plugin already loads it per the `auth-jwt-principal-fields-documentation` change archived 2026-05-07) — NO fresh DB SELECT in the rate-limit path.

### Decision 11: Shadow-banned reader's reads still count

A `is_shadow_banned = TRUE` user's timeline reads consume bucket slots normally. The rate limit is read-side, not visibility-side; shadow-ban does not exempt the user from the cap. (Their visibility into other users' content is filtered through `visible_*` views — orthogonal concern.)

**Rationale:** The rate limit exists to bound DB cost + abuse, both of which apply to shadow-banned readers as much as to visible readers. Exempting them would be a side-channel signaling that they're shadow-banned, defeating the shadow-ban purpose.

### Decision 12: No Firebase Remote Config override flag in this change

Unlike `like-rate-limit` (which ships `premium_like_cap_override`) and the chat/reply analogues, this change does NOT ship a `timeline_read_cap_override` Remote Config flag.

**Rationale:** The like/reply/chat caps were tied to a known retention-vs-conversion tradeoff (Decision 28 in [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md)) that the founder anticipated needing to tune ops-side. The timeline-read cap doesn't have an analogous documented tradeoff; the canonical 150-posts/hour is set per Phase 1 #30 as authoritative. If a tunability need emerges post-launch, a follow-up change MODIFIES this capability to add a Remote Config flag (mirroring the like/reply/chat shape). Avoiding the flag now keeps the surface tight + reduces failure modes (the like-rate-limit follow-ups include a `firebase-admin-server-template-evaluate-bypass-removal` entry tracking SDK regressions on Remote Config reads — fewer Remote Config reads = less exposure).

## Risks / Trade-offs

- **Over-admission near cap (up to 29 posts/request)** → Mitigation: documented in Decision 3; bounded by `N - 1` where N is the per-page cap of 30. User's NEXT request after over-admit is cap-blocked, so the over-shoot is one-time per cap window. Acceptable for MVP.
- **`X-Session-Id` fallback bucket means multi-session same-user soft cap is over-restrictive** → Mitigation: documented in Decision 6; affects only the soft cap (UX nudge, not blocking). Production mobile clients will set the header per the existing contract; only DESIGN-stage mobile + integration scripts hit the fallback.
- **Cross-endpoint single bucket may surprise users who think tabs are independent** → Mitigation: documented as canonical behavior in `docs/05-Implementation.md:803`; the mobile UX banner copy will explain "you've read 150 posts this hour" without naming a tab. Acceptable.
- **Best-effort post-increment vs atomic batch** → Mitigation: see Decision 3. Future `tryAcquireBatch` primitive can replace the loop if rule-of-three triggers.
- **Read rate limit absent on the V11 city_name path** → Confirmed N/A: the rate limit applies at the route level (`GET /api/v1/timeline/...`) regardless of the response shape; V11 added a `city_name` field but didn't alter the post-count semantics.
- **Soft-cap session bucket holds slot even if request fails downstream** → Mitigation: rare (DB query failures are the main case). The fallback is acceptable since soft cap is advisory; an over-counted user gets the upsell banner one request earlier than ideal, no functional harm.
- **Empty-array hard-cap response shape might confuse existing API consumers expecting non-empty pagination** → Mitigation: the `upsell.hard: true` flag is the explicit signal; clients that don't check the flag still see `posts: []` + `next_cursor: null` (which is also the natural "no more results" shape). Behavior is consistent with existing pagination semantics.

## Migration Plan

1. **Land the spec + design + tasks artifacts** (this PR's proposal phase). No code changes.
2. **Implementation phase** (`/opsx:apply`): wire the new rate-limit gate into the three timeline route handlers. New class `TimelineReadRateLimiter` in `:backend:ktor/timeline/`. New `upsell` field in the response DTO (additive). Tests added per `tasks.md`.
3. **No Flyway migration**. All state is in Redis (Upstash); no Postgres column or index added.
4. **Pre-archive smoke** (`/opsx:apply` Section 6): manual `gh workflow run deploy-staging.yml --ref timeline-read-rate-limit` → poll deploy → run a smoke script that exercises the cap (50+ reads against staging hits soft cap; 150+ hits hard cap) → tick Section 6.
5. **Squash-merge** auto-deploys staging. Production deploy follows the regular tag-cut path post-launch.

**Rollback strategy:** revert the route-handler wiring (the rate-limit gate is a pre-DB short-circuit; reverting restores the prior unrestricted behavior). Redis keys age out within 1 hour. No DB rollback needed (no schema changes).

## Open Questions

- **Should the Premium "unlimited" gain a sanity ceiling (e.g., 5000/hour) to bound a compromised Premium account's read abuse?** Default in this proposal: NO, faithful to docs. Re-evaluate post-launch if a compromised Premium account exhibits abuse pattern; track via a follow-up change `timeline-read-rate-limit-premium-sanity-ceiling`.
- **Should the soft cap (50/session) trigger any server-side log/metric in addition to the response flag?** Default: NO additional logging in this change. Add a Sentry breadcrumb or OpenTelemetry attribute in a follow-up if ops needs cap-hit-rate visibility.
- **Empty timeline (`posts: []` because user follows nobody on Following) — should it consume a slot?** Default: YES, the pre-check consumes 1 slot regardless. Otherwise an empty-Following caller has no rate limit at all on Following reads. The post-increment is `N - 1 = -1` (skipped), so the actual slots consumed for an empty response is exactly 1. Documented in spec scenarios.
- **`upsell` object in non-cap state — include with both flags false, or omit entirely?** Default: OMIT entirely (additive-only response shape; clients defensively read `?.upsell?.soft ?? false`). If the mobile UX team prefers explicit-always-present for parsing simplicity, settle in implementation.
