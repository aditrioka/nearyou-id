## 1. Discovery & infra verification

- [ ] 1.1 Re-read [`docs/01-Business.md:53`](../../../docs/01-Business.md), [`docs/02-Product.md:224`](../../../docs/02-Product.md), and [`docs/05-Implementation.md:1740`](../../../docs/05-Implementation.md) to confirm the canonical reply cap is exactly "Free 20/day, Premium unlimited" with NO burst clause; if any source has drifted, file a `FOLLOW_UPS.md` entry and surface to the user before proceeding.
- [ ] 1.2 Re-read [`openspec/specs/rate-limit-infrastructure/spec.md`](../../../openspec/specs/rate-limit-infrastructure/spec.md) (shipped in `like-rate-limit`) to confirm the `RateLimiter` interface contract is exactly what this change consumes — `tryAcquire(userId, key, capacity, ttl): Outcome` and `releaseMostRecent(userId, key)` — and that the WIB-stagger helper `computeTTLToNextReset(userId)` lives in `id.nearyou.app.core.domain.ratelimit`.
- [ ] 1.3 Re-read [`openspec/specs/post-replies/spec.md`](../../../openspec/specs/post-replies/spec.md) (V8 baseline + V9 auto-hide + V10 `post_replied` emit) to confirm the V8 ordering invariants this change layers in front of: auth → UUID validation → JSON body parse + content-length guard → `visible_posts` resolution → INSERT + V10 emit in one transaction → 201.
- [ ] 1.4 Verify `:infra:redis` and the CI Redis service container are already on the test job (PR #37 / `like-rate-limit`) — no new infra setup needed for this change. Spot-check `.github/workflows/ci.yml` for `redis:7-alpine` service and `KotestProjectConfig.bootstrapRedis()` in `:backend:ktor` test setup.
- [ ] 1.5 Verify the `auth-jwt` plugin's `UserPrincipal` carries `subscriptionStatus: String` (added by `like-rate-limit` task 6.1.1). If absent or named differently, this is a defect in the auth path that MUST be fixed there first — do NOT add a fresh DB SELECT in the reply handler as a workaround. **Also confirm RevenueCat downgrade webhook bumps `token_version` on `EXPIRATION` / cancellation events**; if it does not, the Premium→Free abuse window is bounded by JWT TTL (15 min) rather than next-request semantics — file a `FOLLOW_UPS.md` entry and proceed (this change does NOT widen scope to fix it).
- [ ] 1.6 Verify the mobile reply-button surface in the current Android + iOS builds correctly handles HTTP 429 from `POST /replies` (does not crash, does not 5xx-translate). The like-button surface from PR #37 is the precedent. If the reply button doesn't handle 429, file a follow-up mobile-side fix ticket but do NOT block this change — the backend 429 contract is correct regardless of mobile-side handling, and the generic timeline rate-limit screen catches uncaught 429s.

## 2. `premium_reply_cap_override` Remote Config flag plumbing

- [ ] 2.1 Add a key constant `premium_reply_cap_override` alongside `premium_like_cap_override` in `:infra:remote-config` (or wherever `RemoteConfig` exposes typed keys — match the existing shape).
- [ ] 2.2 Verify `RemoteConfig.getLong("premium_reply_cap_override")` resolves through the same code path the like flag uses; no new resolver needed.
- [ ] 2.3 (Optional, do as documentation if no code change is needed) Add a one-line comment in the `RemoteConfig` impl noting that `premium_reply_cap_override` mirrors `premium_like_cap_override` byte-for-byte (default-fallback shape, request-time read, mid-day-flip-binds-immediately).

## 3. Apply rate limit to `POST /api/v1/posts/{post_id}/replies`

- [ ] 3.1 Inject `RateLimiter` and `RemoteConfig` into `ReplyService` (mirror the V9 `ReportService` and the V12 `LikeService` injection precedent — service-owned, not route-owned). The route handler delegates via a `ReplyService.Result` sealed type that adds `Result.RateLimited(retryAfterSeconds)` to the V8/V10 result set.
- [ ] 3.2 Implement `resolveDailyCap(userId)`: reads `RemoteConfig.getLong("premium_reply_cap_override")`. Coerce unset / null / ≤ 0 / oversized / SDK error to default 20. slf4j WARN-log each fallback path (matches the like-flag log shape; future Sentry SDK adoption picks these up).
- [ ] 3.3 Implement the daily-limiter call: skip if `viewer.subscriptionStatus in ('premium_active', 'premium_billing_retry')`; otherwise call `tryAcquire(userId, "{scope:rate_reply_day}:{user:$userId}", cap, computeTTLToNextReset(userId))` inside `withContext(Dispatchers.IO)`. On `Outcome.RateLimited`: return `Result.RateLimited(retryAfterSeconds)`; the route maps to HTTP 429 + `error.code = "rate_limited"` + `Retry-After` header.
- [ ] 3.4 Verify the limiter call sits in the correct ordering slot per the spec: auth → UUID validation → **daily limiter (Free only)** → V8 content-length guard → V8 `visible_posts` resolution → V8 INSERT + V10 emit (one transaction) → 201. The limiter MUST run before JSON body parsing and content-length validation (see Decision 5 in `design.md`).
- [ ] 3.5 Verify the reply handler does NOT call `RateLimiter.releaseMostRecent` on any code path — there is no idempotent re-action equivalent to the like-handler's `INSERT ... ON CONFLICT DO NOTHING` no-op (see Decision 4 in `design.md`). Add a code comment at the call site to document this.
- [ ] 3.6 Audit-log the override-applied path: when the cap resolves to a non-default value, log `event=remote_config_override_applied key=premium_reply_cap_override value=<cap> user_id=<uuid>` at INFO. Match the like-flag audit-log shape.
- [ ] 3.7 Verify `RateLimitTtlRule` and `RedisHashTagRule` (Detekt) pass on the new call site without modification — the `_day}` substring in the daily key matches `RateLimitTtlRule`'s daily-key pattern, and the `{scope:rate_reply_day}:{user:<uuid>}` shape passes `RedisHashTagRule`. Run `./gradlew detekt -PincludeMobile=false` locally to confirm.
- [ ] 3.8 Verify the V8 reply-endpoint contracts (POST 201 response shape, GET pagination, DELETE author-only idempotent-204) and the V10 `post_replied` emit suppression rules (self / block / hard-delete-rollback) are all unchanged at the response level. Existing `ReplyServiceTest` MUST pass byte-for-byte against the modified `ReplyService`.

## 4. Tests for `POST /reply` rate limit

- [ ] 4.1 Implement `ReplyRateLimitTest` in `backend/ktor/src/test/kotlin/id/nearyou/app/engagement/ReplyRateLimitTest.kt`, tagged `database`. Run against `InMemoryRateLimiter` (extracted in `like-rate-limit` task 7.1) — the Lua-level correctness gate is `:infra:redis`'s `RedisRateLimiterIntegrationTest`; this class is the HTTP-level + service-level gate testing what `ReplyService` does *with* the limiter.
- [ ] 4.2 Cover all 24 scenarios from `specs/post-replies/spec.md` § "Integration test coverage — reply rate limit", named in `@Test`-equivalent strings 1–24 verbatim. The list:
   1. 20 replies in a day succeed for Free user.
   2. 21st reply rate-limited; 429 + Retry-After + no `post_replies` row + zero `notifications` rows (verified via INSERT-row-count snapshot).
   3. Retry-After ±5s of expected (frozen `AtomicReference<Instant>` clock).
   4. Premium (`premium_active`) skips the daily limiter — 50 replies succeed (50 chosen distinct from override-30 in scenario 6).
   5. Premium billing retry (`premium_billing_retry`) skips the daily limiter.
   6. `premium_reply_cap_override = 30` raises the cap; 31st rejected after a successful 30th.
   7. `premium_reply_cap_override = 5` lowers the cap mid-day; user previously at 7 is rejected at 8.
   8. `premium_reply_cap_override = 0` falls back to default 20.
   9. `premium_reply_cap_override = "thirty"` falls back to default 20 (assert response status only; WARN log is implementation-detail).
  10. Remote Config network failure (SDK throws) falls back to default 20; no 5xx propagated.
  11. 404 `post_not_found` consumes a slot (does NOT release).
  12. 400 `invalid_request` post-limiter consumes a slot.
  13. Daily limit hit short-circuits before content-length guard (281-char content at slot 21 → 429, NOT 400 — behavioral proof).
  14. Daily limit hit short-circuits before `visible_posts` SELECT (non-existent post UUID at slot 21 → 429, NOT 404 — behavioral proof; no DB-statement spy required).
  15. Hash-tag key shape: daily key = `{scope:rate_reply_day}:{user:<uuid>}` via a `SpyRateLimiter`.
  16. WIB rollover: at the per-user reset moment the cap restores (frozen `AtomicReference<Instant>` clock advanced past `computeTTLToNextReset(userId)` + 1s).
  17. Soft-delete does NOT release a daily slot: POST 5 → DELETE 1 (bucket stays at 5) → POST 15 more (bucket reaches 20) → 21st rejected with 429 → bucket stays at 20.
  18. DELETE works at the daily cap (20/20, DELETE returns 204, no limiter consulted).
  19. GET unaffected by the daily cap (caller at 21/20 still gets V8 200).
  20. Tier read from `viewer` principal: `SpyRateLimiter` confirms `tryAcquire` invoked AND HTTP 201 returned. Combined with scenario 14, proves no DB read between auth and limiter.
  21. The reply handler MUST NOT call `releaseMostRecent` — assert via `SpyRateLimiter` that `releaseMostRecent` is never invoked across all 24 scenarios.
  22. Null `subscriptionStatus` on viewer principal treated as Free — limiter applied, 21st request rejected.
  23. V10 transaction rollback does NOT release the slot — slot stays consumed when emit fails.
  24. `premium_reply_cap_override = Long.MAX_VALUE` (or > 10,000) clamps to default 20.
- [ ] 4.3 Verify `ReplyServiceTest` (the V8 baseline test class) still passes byte-for-byte. The new rate-limit scenarios live in `ReplyRateLimitTest`; the V8 test class is unchanged.
- [ ] 4.4 Pre-push gauntlet: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test :infra:redis:test :core:domain:test -PincludeMobile=false`. All green.

## 5. Pre-push + CI verification

- [ ] 5.1 `openspec validate reply-rate-limit --strict` — green at HEAD.
- [ ] 5.2 `./gradlew ktlintCheck detekt -PincludeMobile=false` — green. Both Detekt rules (`RateLimitTtlRule`, `RedisHashTagRule`) produce 0 findings on the new `ReplyService` call site.
- [ ] 5.3 `./gradlew :backend:ktor:test -PincludeMobile=false` — green. `ReplyRateLimitTest` 24/24 (per the spec test-coverage list) + `ReplyServiceTest` baseline byte-for-byte preserved.
- [ ] 5.4 Push to the `reply-rate-limit` branch and verify CI green: `lint`, `build`, `test`, `migrate-supabase-parity` all SUCCESS. Redis + Postgres service containers healthy.

## 6. Smoke test against staging (post-CI-green)

- [ ] 6.1 Adapt `dev/scripts/smoke-9.7-like-rate-limit.sh` to a new `dev/scripts/smoke-reply-rate-limit.sh` — same shape (mint dev JWT, hit endpoint 21 times, assert first 20 = 201, 21st = 429 with `Retry-After` and `error.code = "rate_limited"`). Reuse the same Cloud-Run-job-seeded synthetic Free user pattern.
- [ ] 6.2 Seed ≥21 visible posts authored by a *different* user (avoids self-reply edge cases on the global timeline).
- [ ] 6.3 Run the smoke against the latest staging revision after CI green. Confirm the 21st response carries `Retry-After` matching `computeTTLToNextReset(userId)` for that specific synthetic user (within ±5s).
- [ ] 6.4 If staging Redis is unreachable / fail-soft path triggers (per the lessons in `like-rate-limit` task 9.7), fix the underlying cause (TLS scheme, secret slot value, etc.) before declaring the smoke green — do NOT accept a "all 21 returned 201 because limiter fail-softed open" result as a successful smoke.

## 7. Doc sync (in the archive commit, not the feat commit)

- [ ] 7.1 Update [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md) Phase 2 item 3 — append a "Reply rate limit shipped" cross-reference covering the 20/day Free cap, `premium_reply_cap_override` flag, and the explicit "no burst" decision. Remove the "Reply rate limit (20/day Free) remains deferred" note.
- [ ] 7.2 Update [`docs/05-Implementation.md`](../../../docs/05-Implementation.md) § Rate Limiting Implementation — extend the "shipped" callout block (added by `like-rate-limit`) with the new reply-cap entry pointing at `openspec/specs/post-replies/spec.md`.
- [ ] 7.3 Verify [`docs/01-Business.md:53`](../../../docs/01-Business.md), [`docs/02-Product.md:224`](../../../docs/02-Product.md), and [`docs/05-Implementation.md:1740`](../../../docs/05-Implementation.md) Layer 2 row need NO edits — the canonical "Free 20/day, Premium unlimited" line already matches what's shipping. Confirm at archive time and document the no-op in the archive commit body.
- [ ] 7.4 Run `openspec archive reply-rate-limit --yes`. Expected output: `~ 1 modified` on `openspec/specs/post-replies/spec.md` (4 ADDED requirements appended). Per the one-PR-per-change convention codified in PR #38, this archive commit lands on the same `reply-rate-limit` branch as the proposal + feat commits — NOT a separate archive PR.
