## MODIFIED Requirements

### Requirement: Rate limit 10 submissions per hour per user

The endpoint SHALL enforce a rate limit of 10 submissions/hour/user via the shared `RateLimiter` interface (introduced by the `rate-limit-infrastructure` capability) backed by Redis, keyed `{scope:rate_report}:{user:<user_id>}` (hash-tag format). The capacity is 10; the TTL is `Duration.ofHours(1)`. The 11th submission within a 1-hour rolling window MUST return HTTP 429 with error code `rate_limited` and a `Retry-After` header set to the seconds until the oldest counted submission ages out. The rate limit check MUST run BEFORE any DB work (target existence, INSERT, auto-hide). Duplicate requests that hit 409 MUST NOT consume a rate-limit slot — the route MUST call `RateLimiter.releaseMostRecent` on the 409 path before returning, mirroring the V9 contract.

The previously in-process `ReportRateLimiter` (V9) is replaced by a thin wrapper that delegates to the shared `RateLimiter` interface. The `Outcome` sealed interface (`Allowed(remaining)` / `RateLimited(retryAfterSeconds)`), the `keyFor(userId)` static helper, the `cap` and `window` constructor parameters, and all four V9 spec scenarios (10-succeed, 11th-rate-limited, Retry-After-reflects-oldest, 409-does-not-consume) MUST be preserved at the spec-contract level. No client-visible behavior change.

**V9 test-suite scope clarification.** V9's `ReportRateLimiterTest` constructs the limiter directly with an injected `clock: () -> Instant` (in-process `ConcurrentHashMap`) and asserts behavior against the in-memory bucket. The injection seam does NOT survive the port: Redis Lua reads `redis.call('TIME')`, which is server-side and not addressable from the JVM clock fixture. The V9 contract assertions (10-succeed-window, 11th-429, Retry-After-reflects-oldest, 409-release-on-empty-bucket) are reproduced in `RedisRateLimiterIntegrationTest` (see `rate-limit-infrastructure` capability) running against a real Redis service container — that is the regression gate going forward. V9's existing `ReportRateLimiterTest` is retained against an in-memory test double of the `RateLimiter` interface for fast unit-level checks of the moderation route's plumbing (cap/window wiring, 409-release call site), but is no longer the canonical correctness gate.

#### Scenario: 10 submissions within an hour succeed
- **WHEN** a user submits 10 distinct valid reports within a 60-minute window
- **THEN** all 10 return HTTP 204

#### Scenario: 11th submission rate-limited
- **WHEN** a user has 10 accepted reports in the last hour AND submits an 11th valid report
- **THEN** the response is HTTP 429 with `error.code = "rate_limited"` AND the response carries a `Retry-After` header with a positive integer value AND no `reports` row is inserted

#### Scenario: Retry-After reflects oldest counted submission
- **WHEN** the 11th request is rejected
- **THEN** the `Retry-After` value is approximately the number of seconds until the oldest counted submission's timestamp is more than 1 hour in the past

#### Scenario: 409 duplicate does not consume a rate-limit slot
- **WHEN** a user has 9 accepted reports and submits a 10th report that collides with a prior report (UNIQUE violation → 409)
- **THEN** the response is HTTP 409 AND a subsequent valid distinct report still succeeds (not 429)

#### Scenario: Redis key uses hash-tag format
- **WHEN** the rate-limit check runs against Redis
- **THEN** the key used has the form `{scope:rate_report}:{user:<uuid>}`
