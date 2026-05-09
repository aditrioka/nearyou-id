## Context

The three timeline read endpoints (`GET /api/v1/timeline/{nearby,following,global}`) ship today without per-user read-side rate limits. The canonical Layer 2 caps live in [`docs/05-Implementation.md:1159-1160`](../../../docs/05-Implementation.md) — *"Timeline read Free | 150 posts/h rolling (hard); 50/session (soft)"* + *"Timeline read Guest | 30 posts/h rolling (hard); 10/session (soft)"* — and the conceptual key shapes live in [`docs/05-Implementation.md:799-806`](../../../docs/05-Implementation.md) (informal pre-hash-tag form: `timeline_offset:{user:<user_id>}:<session_id>` and `timeline_rolling:{user:<user_id>}`). The implementation renders these in the canonical hash-tag form per the infrastructure standard. The infrastructure to enforce them — Lettuce-backed `RedisRateLimiter`, `RateLimiter` interface in `:core:domain`, hash-tag key standard, `RedisHashTagRule` Detekt rule, `RateLimitTtlRule` Detekt rule — shipped in `like-rate-limit` and was further refined by `rate-limit-ip-hashing` + `rate-limit-infrastructure-success-path-log`. Three per-endpoint write-side limits (likes, replies, chat) plus the report limiter and the `/health/*` anti-scrape limiter all consume that infrastructure today; this change adds the read-side timeline limiter as the next consumer.

The Guest path (10/session + 30/hour, keyed by guest JWT `jti`) is **explicitly deferred** — it requires the Layer 1 attestation-gated guest-session JWT issuance work (Phase 1 item 25 per [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md)), which has not shipped. This change ships the authenticated half; a follow-up MODIFIES `timeline-read-rate-limit` to add the guest axis once the issuance work lands.

## Goals / Non-Goals

**Goals:**

- Enforce per-user 50-posts-per-session soft + 150-posts-per-hour rolling hard caps on the three authenticated timeline endpoints, faithful to the canonical cap values in `docs/05-Implementation.md:1159`.
- Surface an upsell signal in the response (soft cap reached → banner; hard cap reached → empty list + banner) without a 429 error code, so the mobile UX prefers an empty list with banner over a hard error.
- Use a single per-user rolling bucket aggregating reads across all three timeline tabs.
- Use the existing `RateLimiter` interface in `:core:domain` and the Lettuce-backed implementation in `:infra:redis` — no new infrastructure, no new modules.
- Keep Premium users unconditionally exempt (consistent with all other Layer 2 limiters; no daily-WIB-stagger because timeline limits are hourly, not daily).
- Faithfully follow the `{scope:<value>}:{<axis>:<value>}` hash-tag standard so `RedisHashTagRule` passes WITHOUT modifying the rule (the session-id is encoded INTO the partition-axis value via a `__` separator, not as a trailing key segment).
- Validate `X-Session-Id` header values to prevent Redis-key injection through malicious header content.

**Non-Goals:**

- Guest timeline rate limit (`10/session + 30/hour` keyed by guest JWT `jti`). Deferred until guest-session issuance lands; a follow-up change MODIFIES this capability with the new requirement.
- Layer 4 per-area anti-spam read counter (separate concern; not in any docs as a Phase 1/2 commitment).
- Premium "unlimited" hard ceiling. Premium is unconditionally exempt; an abusive Premium account is bounded only by the per-IP authenticated baseline (`docs/05-Implementation.md:1142`: 1000 req/min/IP) + downstream DB cost.
- Timeline-tab-transition counter (a separate UX-side metric — out of scope).
- Mobile UI changes. The mobile `:mobile:app` surface is DESIGN per [`CLAUDE.md`](../../../CLAUDE.md) § Mobile + Admin Status; client-side banner rendering will land alongside the broader mobile-screen build.
- A `tryAcquireBatch` primitive on the `RateLimiter` interface. Defer to a future change if a second batch-counting consumer appears (rule of three).
- A request-level concurrency lock to bound parallel-admission over-shoot. The existing per-IP authenticated baseline is the bound; documented in § "Risks / Trade-offs".

## Decisions

### Decision 1: Single per-user rolling bucket aggregating across all three timeline endpoints

Per [`docs/05-Implementation.md:803`](../../../docs/05-Implementation.md), the canonical key is `timeline_rolling:{user:<user_id>}` — a single user-axis key, NOT three (one-per-endpoint). All three timeline routes (`Nearby`, `Following`, `Global`) consume the same rolling bucket.

**Rationale:** A user's "150 posts/hour" budget should be the cross-tab total, not 450/hour distributed across 3 tabs. The canonical key shape is the source of truth and matches this interpretation. With the per-page cap of 30, the realistic cross-tab cap-reach scenario is `2 pages on Nearby (60 posts) + 2 pages on Following (60 posts) + 1 page on Global (30 posts) = 150 → rolling cap reached on the 6th request` (matches the integration test scenario in `tasks.md` task 8.7).

**Alternative considered:** Per-endpoint buckets (`{scope:rate_timeline_rolling}:{scope:nearby}:{user:U}` × 3). Rejected because it 3× the canonical limit without a docs basis and complicates cross-tab anti-abuse.

### Decision 2: Count posts returned, not requests

The bucket increments by `posts.size` per successful timeline response (typically 0–30 entries per request, per the existing per-page cap of 30). Counts ARE NOT requests-as-units. Implementation: the route handler invokes `tryAcquire` once for the pre-check (1 slot consumed) and `(N - 1).coerceAtLeast(0)` additional best-effort `tryAcquire` calls (parallel coroutines, fire-and-forget on outcome) to bump the bucket by the actual response count.

**Rationale:** The canonical docs say "150 posts/h" and "50/session". A user reading 5 pages of 30 posts each (= 150 posts) should hit the cap on the 6th request, not the 31st. Counting posts honors the spec literally.

**Alternative considered:** Count requests (5 req/h hard, 2 req/session soft). Rejected because the canonical spec is explicit about post-count, not request-count, and request-counting drifts the actual cap by the page-size variance.

### Decision 3: Atomic admit-or-reject via the existing `tryAcquire` interface — no `tryAcquireBatch` introduced

The existing `RateLimiter.tryAcquire(userId, key, capacity, ttl)` is the only seam used. The route handler:
1. Pre-checks via a single `tryAcquire` call. If `RateLimited`, return empty + `upsell.hard = true`. If `Allowed`, 1 slot is consumed.
2. Runs the timeline DB query, gets `N` posts.
3. Issues `(N - 1).coerceAtLeast(0)` additional best-effort `tryAcquire` calls (parallel coroutines, fire-and-forget on outcome). Each call adds an entry while there is room; once the cap is reached, subsequent calls return `RateLimited` and add nothing — but the request is already mid-response and posts are already returned.

The shipped `RedisRateLimiter` Lua sliding-window guarantees the bucket size is always `≤ capacity`. So at any point the rolling bucket holds 0–150 entries, the session bucket holds 0–50.

**Single-request over-admission tolerance:** up to `N - 1 = 29` posts when a user is near the cap (e.g., user at 121/150 reads 30 posts; bucket reaches 150; user's NEXT request will be cap-blocked).

**Concurrent over-admission tolerance:** with `K` concurrent requests by the same user at slot `(150 - 1)`, all `K` rolling pre-checks may admit before any post-increment lands; total over-admit is bounded by `K × N` rather than `N`. For MVP volume + mobile clients' typical concurrency (≤ 3 simultaneous timeline reads across tabs), this bound is acceptable. The authenticated per-IP baseline (1000 req/min/IP) caps the upper limit further.

**Alternative considered:** Add `tryAcquireBatch(userId, key, count, capacity, ttl)` to the `RateLimiter` interface. Rejected from this change's scope to keep the proposal tight; a future change MAY add the batch primitive if a second batch-counting consumer appears (rule of three).

**Alternative considered:** Single ZADD with multiple member arguments via a custom Lua script in `:infra:redis`. Rejected — would require a `:infra:redis` change parallel to this one, and the over-admission tolerance is acceptable.

**Alternative considered:** Add a request-level Redis SETNX or pg advisory lock to bound parallel admission. Rejected — adds latency on the hot path; the parallelism bound is documented and the per-IP baseline is the upper limit.

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

`upsell.soft = true` is set when the **session pre-check returned `RateLimited`** — i.e., the session bucket was at 50/50 capacity at pre-check time. `upsell.hard = true` is set when the **rolling pre-check returned `RateLimited`** at request time (so `posts: []` is returned). Both flags are optional in the response — when both are false, the `upsell` object MUST be omitted entirely (additive, no breaking change for clients that don't read it).

**Pre-check semantics, not post-increment:** the `softCapReached` signal is captured at the time of the session pre-check `tryAcquire` call (step 6 of the limiter ordering), not after the post-increment. This means the 51st post-delivery (when bucket was at 50/50 entering the request) triggers `upsell.soft = true`; the 50th delivery does NOT (bucket was at 49 entering the pre-check, which returned Allowed).

**Rationale:** Pre-check semantics are simpler to reason about and align with the spec scenarios. Post-increment-based detection would require an extra `ZCARD` after the post-increment loop to read the new bucket size, which is more Redis traffic for negligible UX benefit (the soft-cap nudge fires at the same human-perceptible moment either way).

**Alternative considered:** Response header `X-Timeline-Upsell-Soft: true`. Rejected — forces the mobile client to inspect HTTP headers in addition to body, friction we don't need.

**Alternative considered:** Always include `upsell` (even when both flags are false). Rejected for backwards-compatibility — additive-only is safer. Mobile clients will parse `?.upsell?.soft ?? false` defensively.

### Decision 5: Hard cap returns 200 OK + empty array, NOT 429

When the rolling bucket is at capacity at request time, the response is HTTP 200 with `posts: []`, `next_cursor: null`, `upsell.hard: true`. Status is NOT 429.

**Rationale:** Per UX heuristic and the docs' "soft per-session + hard rolling per-hour" framing, the canonical user experience at the cap is "empty list + 'You've hit your hourly limit, upgrade to Premium for unlimited reads' banner". A 429 status would force the mobile client to render an error screen, which is a worse UX than an empty list with a context-aware banner. The route is a READ — a 429 conventionally means "you sent too many requests"; here the user simply has no remaining read budget, which is closer to "no data" semantics.

**Alternative considered:** 429 with `Retry-After`. Rejected — the like/reply/chat write limiters return 429 because the action FAILED; here the read action is not "failing", the budget is just exhausted.

### Decision 6: `X-Session-Id` validated to a UUID-friendly regex; falls back to `no-session` on missing or malformed

The `X-Session-Id` header value is validated against `^[A-Za-z0-9-]{1,64}$` (alphanumeric + hyphens, length 1-64). On missing OR malformed (regex mismatch) header, the session-id is substituted with the literal string `no-session` and the bucket key becomes `{scope:rate_timeline_session}:{session:<user_id>__no-session}`. The endpoint MUST NOT reject the request with HTTP 400 for a missing or malformed `X-Session-Id`.

**Rationale:** The validated value flows directly into the partition-axis segment of the Redis key. Without validation, a malicious client could set `X-Session-Id: }malicious{user:victim}` to (a) corrupt the rendered key shape (escape into adjacent keys), (b) bypass the soft cap with arbitrary unique session-ids (each fresh value spawns a new 50-slot bucket), or (c) exploit Redis key-parser quirks. The regex is restrictive enough to exclude all Redis-structural characters (`{`, `}`, `:`, `\`, control chars) while permissive enough to accept real UUIDs (canonical form: `4f8b9c1e-2d3a-4b5c-9d1e-7f8a9b0c1d2e`).

The `no-session` fallback bucket aggregates all no-session reads in the hour. This means a user repeatedly without `X-Session-Id` shares a single 50-slot bucket, providing a deterministic soft-cap UX even for clients that don't set the header.

**Alternative considered:** Reject with HTTP 400 `missing_session_id`. Rejected — would block early mobile development and admin tooling; fallback is more pragmatic for MVP.

**Alternative considered:** Skip the soft-cap entirely when no session id is present. Rejected — a malicious user could deliberately omit the header to dodge the soft-cap UX nudge. Fallback bucket closes the loophole.

**Alternative considered:** No validation; trust mobile clients to send well-formed UUIDs. Rejected — the session-id is user-controlled input flowing into a Redis key; invalidate-by-default is the correct security posture (mirrors the `clientIp` / `IpHasher` invariant for IP-axis keys).

### Decision 7: Fixed 1-hour TTL for both buckets, no `computeTTLToNextReset`

Both `{scope:rate_timeline_session}:{session:U__SID}` and `{scope:rate_timeline_rolling}:{user:U}` use `Duration.ofHours(1)` directly. The existing `computeTTLToNextReset(userId)` helper applies only to DAILY caps that need WIB-stagger to prevent thundering-herd at midnight.

**Rationale:** Hourly limits naturally distribute across the hour by user activity timing; midnight-stagger doesn't apply. The `RateLimitTtlRule` Detekt rule (per [`openspec/specs/rate-limit-infrastructure/spec.md`](../../../openspec/specs/rate-limit-infrastructure/spec.md)) fires only on keys whose literal contains the substring `_day}` and correctly skips hourly keys.

### Decision 8: Hash-tag key shapes encode session-id into the partition-axis value (no rule modification)

- **Session bucket**: `{scope:rate_timeline_session}:{session:<user_id>__<session_id>}` — both segments wrapped, separated by exactly one `:`. The `<user_id>__<session_id>` composite value (joined by exactly two underscores) is the partition axis; the new axis name `session` describes the partition dimension. The fallback shape on no-session is `{scope:rate_timeline_session}:{session:<user_id>__no-session}`.
- **Rolling bucket**: `{scope:rate_timeline_rolling}:{user:<user_id>}` — vanilla single-pair shape, identical to existing daily/hourly keys (`{scope:rate_like_day}:{user:U}`, `{scope:rate_like_burst}:{user:U}`, etc.).

Both shapes match the strict `RedisHashTagRule` regex `^\{scope:[^{}]+}:\{[^{}:]+:[^{}]+}$` exactly — no trailing-segment exception is needed and the rule does NOT need modification.

**Rationale:** The canonical docs at `docs/05-Implementation.md:802-806` use an informal trailing-segment shape (`timeline_offset:{user:U}:<sid>`) that pre-dates the hash-tag standard introduced by `like-rate-limit`. To preserve the shipped `RedisHashTagRule` strict regex (which other rate-limit keys depend on), we encode the session-id INTO the partition-axis value using a `__` separator instead of as a trailing key segment. CRC16 hashing operates on the second hash tag's bytes (`session:<user_id>__<session_id>`), so two users with the same session-id but different user-ids land in different cluster slots, and same-user-different-session combinations also land in different slots — independent buckets, as required.

**Alternative considered:** Extend `RedisHashTagRule` to allow optional trailing `:<value>` after the strict canonical shape (a regex change in the rate-limit-infrastructure capability). Rejected — would require a MODIFIED rate-limit-infrastructure spec in this same change (scope-creep) AND would complicate the rule for marginal benefit. The composite-axis-value shape is cleaner and uses zero rule changes.

**Alternative considered:** Hash session-id into the entry's nonce (not the key). Rejected — would collapse all sessions for the same user into a single 50-slot bucket, breaking the per-session-bucket-independence semantics that this Decision 8 establishes (and that the spec's "Session bucket per-session — independent buckets" scenario explicitly requires).

### Decision 9: Limiter ordering — pre-check before DB, post-increment after response

1. Auth (existing `auth-jwt` plugin).
2. Path/query parameter validation (existing — invalid `cursor`, etc.).
3. **Premium short-circuit**: read `viewer.subscriptionStatus` from the auth-time principal. If `premium_active` or `premium_billing_retry`, SKIP both buckets entirely (no Redis calls).
4. **`X-Session-Id` validation** (per Decision 6): produce a sanitized session-id (the original value if it passes the regex, else `no-session`).
5. **Pre-check on rolling bucket**: `tryAcquire(userId, "{scope:rate_timeline_rolling}:{user:U}", capacity = 150, ttl = 1.hour)`. If `RateLimited`, return HTTP 200 with `posts: []`, `next_cursor: null`, `upsell.hard: true`. STOP. (No DB query executes; the session pre-check is NOT consulted; saves Postgres + Redis cost on the cap-hit path.)
6. **Pre-check on session bucket**: `tryAcquire(userId, "{scope:rate_timeline_session}:{session:U__<sanitized_sid>}", capacity = 50, ttl = 1.hour)`. The outcome is recorded as `softCapReached: Boolean = (outcome == RateLimited)`. The pre-check NEVER blocks the request.
7. Execute the timeline DB query → `posts: List<TimelinePost>` of length `N` (0 ≤ N ≤ 30).
8. **Post-increment**: issue `(N - 1).coerceAtLeast(0)` additional best-effort `tryAcquire` calls on each bucket (rolling + session) in parallel coroutines. Outcome of these calls is ignored (the response is already shaped).
9. Build response: `posts`, `next_cursor`, and `upsell` per Decision 4 (omit the entire `upsell` object if both flags are false; `upsell.soft = softCapReached` from step 6).
10. Return HTTP 200 with the response body.

Steps 5 + 6 MUST run BEFORE the timeline DB query (no Postgres round-trip on cap-hit). Step 8 runs AFTER the response is built (post-increment is non-blocking on the response build).

**Rationale:** Pre-check before DB is cost-saving and prevents expensive scans on capped users. Post-increment after response is correct because we count posts returned (not pre-allocated). Step 6 is a "pre-check that always admits" — it consumes 1 slot but never blocks; the soft cap is purely advisory.

**Hard-cap short-circuit ordering:** when step 5 returns RateLimited, step 6 is NOT executed. Therefore `softCapReached` is unobserved on hard-capped requests, and the response carries only `upsell.hard = true` (not `upsell.soft = ...`).

### Decision 10: Premium bypass is unconditional (both buckets skipped entirely)

Premium users (`subscription_status IN ('premium_active', 'premium_billing_retry')`) skip BOTH buckets entirely — no Redis calls at all. The bucket keys for Premium users are NEVER written. Mirrors the existing like/reply/chat limiter pattern.

**Rationale:** Premium is "unlimited reads" per [`docs/01-Business.md:18`](../../../docs/01-Business.md). Skipping both buckets keeps the Premium read path Redis-free (lower latency, lower cost). The `subscription_status` is read from the auth-time principal (the `auth-jwt` plugin already loads it per the `auth-jwt` capability) — NO fresh DB SELECT in the rate-limit path.

**Stale principal handling:** if the user's subscription expires between auth-time JWT issuance and a request, the JWT principal still says `premium_active` and the request bypasses both caps. On the next JWT refresh, the user becomes Free and is subject to both caps. This mirrors the `auth-jwt § Stale principal across an admin mid-flight flip` semantics. The window is bounded by JWT TTL.

### Decision 11: Shadow-banned reader's reads still count

A `is_shadow_banned = TRUE` user's timeline reads consume bucket slots normally. The rate limit is read-side, not visibility-side; shadow-ban does not exempt the user from the cap. (Their visibility into other users' content is filtered through `visible_*` views — orthogonal concern.)

**Rationale:** The rate limit exists to bound DB cost + abuse, both of which apply to shadow-banned readers as much as to visible readers. Exempting them would be a side-channel signaling that they're shadow-banned, defeating the shadow-ban purpose.

### Decision 12: No Firebase Remote Config override flag in this change

Unlike `like-rate-limit` (which ships `premium_like_cap_override`) and the chat/reply analogues, this change does NOT ship a `timeline_read_cap_override` Remote Config flag.

**Rationale:** The like/reply/chat caps were tied to a known retention-vs-conversion tradeoff (Decision 28 in [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md)) that the founder anticipated needing to tune ops-side. The timeline-read cap doesn't have an analogous documented tradeoff; the canonical 150-posts/hour is set per Phase 1 #30 as authoritative. If a tunability need emerges post-launch, a follow-up change MODIFIES this capability to add a Remote Config flag (mirroring the like/reply/chat shape). Avoiding the flag now keeps the surface tight + reduces failure modes (the like-rate-limit follow-ups include a `firebase-admin-server-template-evaluate-bypass-removal` entry tracking SDK regressions on Remote Config reads — fewer Remote Config reads = less exposure).

## Risks / Trade-offs

- **Single-request over-admission near cap (up to 29 posts/request)** → Mitigation: documented in Decision 3; bounded by `N - 1` where N is the per-page cap of 30. User's NEXT request after over-admit is cap-blocked, so the over-shoot is one-time per cap window. Acceptable for MVP.
- **Concurrent over-admission amplification** → Mitigation: documented in Decision 3 + Spec § "Concurrent same-user request bound". With K concurrent requests by the same user at near-cap, total over-admit is bounded by `K × N`. The per-IP authenticated baseline (1000 req/min/IP) caps K from above. Acceptable for MVP; re-evaluate if production telemetry shows abuse.
- **`X-Session-Id` fallback bucket means multi-no-session-request user shares one soft-cap bucket** → Mitigation: documented in Decision 6; affects only the soft cap (UX nudge, not blocking). Production mobile clients will set the header per the existing contract; only DESIGN-stage mobile + integration scripts hit the fallback.
- **Cross-endpoint single bucket may surprise users who think tabs are independent** → Mitigation: documented as canonical behavior in `docs/05-Implementation.md:803`; the mobile UX banner copy will explain "you've read 150 posts this hour" without naming a tab. Acceptable.
- **Best-effort post-increment vs atomic batch** → Mitigation: see Decision 3. Future `tryAcquireBatch` primitive can replace the loop if rule-of-three triggers.
- **Soft-cap session bucket holds slot even if request fails downstream** → Mitigation: rare (DB query failures are the main case). The fallback is acceptable since soft cap is advisory; an over-counted user gets the upsell banner one request earlier than ideal, no functional harm.
- **Empty-array hard-cap response shape might confuse existing API consumers expecting non-empty pagination** → Mitigation: the `upsell.hard: true` flag is the explicit signal; clients that don't check the flag still see `posts: []` + `next_cursor: null` (which is also the natural "no more results" shape). Behavior is consistent with existing pagination semantics.
- **Stale principal bypass during mid-flight subscription expiry** → Mitigation: documented in Decision 10. JWT TTL bounds the window. A Premium-account compromise that exfiltrates the JWT remains unbounded for the JWT lifetime regardless of this rate limit — out of scope here, addressed by JWT refresh cadence + token-version revocation.

## Migration Plan

1. **Land the spec + design + tasks artifacts** (this PR's proposal phase). No code changes.
2. **Implementation phase** (`/opsx:apply`): wire the new rate-limit gate into the three timeline route handlers. New class `TimelineReadRateLimiter` in `:backend:ktor/timeline/`. New `upsell` field in the response DTO (additive). Tests added per `tasks.md`.
3. **No Flyway migration**. All state is in Redis (Upstash); no Postgres column or index added.
4. **Pre-archive smoke** (`/opsx:apply` Section 6, not duplicated in tasks.md): manual `gh workflow run deploy-staging.yml --ref timeline-read-rate-limit` → poll deploy → run a smoke script that exercises the cap → tick Section 6.
5. **Squash-merge** auto-deploys staging. Production deploy follows the regular tag-cut path post-launch.

**Rollback strategy:** revert the route-handler wiring (the rate-limit gate is a pre-DB short-circuit; reverting restores the prior unrestricted behavior). Redis keys age out within 1 hour. No DB rollback needed (no schema changes).

## Open Questions

- **Should the Premium "unlimited" gain a sanity ceiling (e.g., 5000/hour) to bound a compromised Premium account's read abuse?** Default in this proposal: NO, faithful to docs. Re-evaluate post-launch if a compromised Premium account exhibits abuse pattern; track via a follow-up change `timeline-read-rate-limit-premium-sanity-ceiling`.
- **Should the soft cap (50/session) trigger any server-side log/metric in addition to the response flag?** Default: NO additional logging in this change. Add a Sentry breadcrumb or OpenTelemetry attribute in a follow-up if ops needs cap-hit-rate visibility.
- **Empty timeline (`posts: []` because user follows nobody on Following) — should it consume a slot?** Default: YES, the pre-check consumes 1 slot regardless. Otherwise an empty-Following caller has no rate limit at all on Following reads. The post-increment is `(N - 1).coerceAtLeast(0) = 0` (skipped), so the actual slots consumed for an empty response is exactly 1. Documented in spec scenarios.
- **`upsell` object in non-cap state — include with both flags false, or omit entirely?** Default: OMIT entirely (additive-only response shape; clients defensively read `?.upsell?.soft ?? false`). If the mobile UX team prefers explicit-always-present for parsing simplicity, settle in implementation.
