## MODIFIED Requirements

### Requirement: `/health/*` rate-limit at 60 req/min per IP

Both `/health/live` and `/health/ready` SHALL be rate-limited at 60 requests per minute per client IP via the `RateLimiter.tryAcquireByKey(key, capacity, ttl)` overload defined in the `rate-limit-infrastructure` capability. The limiter is keyed on the client IP read from the `clientIp` request-context value populated by the `client-ip-extraction` capability. Direct reads of `X-Forwarded-For` are forbidden per the project critical-invariant in [`CLAUDE.md`](CLAUDE.md) and enforced by the `RawXForwardedForRule` Detekt rule introduced in `client-ip-extraction`. The handler MUST NOT invoke the user-keyed `tryAcquire(userId, ...)` overload with a sentinel UUID — IP-axis call sites use `tryAcquireByKey` exclusively.

The Redis key shape MUST follow the hash-tag standard: `{scope:health}:{ip:<hashed-ip>}` with a 60-second window. The `<hashed-ip>` segment MUST be the result of `IpHasher.hash(call.clientIp)` (16-hex truncated SHA-256, exported from `:infra:otel`) — raw IP literals embedded in the Lua key leak into Tempo span attributes via `db.statement` on the Lettuce `EVALSHA` span and into structured logs via the `key=` field, contradicting the project's `CF-Connecting-IP` privacy posture and exposing real customer IPs at production tag-deploy. The 60-request capacity applies to the union of `/health/live` and `/health/ready` requests from the same IP — both endpoints share the same bucket (the hash function is deterministic per IP, so same client always hashes to same key).

When the cap is exceeded, the response status MUST be `429 Too Many Requests` with a `Retry-After` header containing the seconds until the oldest counted request ages out (matching `Outcome.RateLimited.retryAfterSeconds` from `RateLimiter`).

**Cloud Run probe bypass via User-Agent.** Cloud Run native HTTP probes set the `User-Agent` header to `GoogleHC/<version>` (and Kubernetes-style probes use `kube-probe/<version>`). Requests whose `User-Agent` matches the regex `^(GoogleHC|kube-probe)/` SHALL bypass the rate-limit check entirely (no `tryAcquireByKey` invocation, no bucket consumption, no 429 risk). This matters because:

- Cloud Run probes do NOT see `127.0.0.1` as the request source — the container observes a Google-internal proxy address that is NOT a stable constant and that COULD collide with real client buckets in pathological cases. The earlier "loopback isolation" approach was incorrect-as-stated.
- Without the bypass, a misconfiguration that aligns the probe's apparent source IP with a hot scraper's IP could produce a 429 → liveness probe failure → unintended container restart loop.
- `User-Agent`-based bypass is forgeable from outside, but the cost-of-forgery is exactly the rate-limit cap (60 req/min), so the worst case is "scraper bypasses the cap by setting `User-Agent: GoogleHC/...`" — the same outcome as if the scraper rotated IPs. The trade-off is acceptable for an unauthenticated public health endpoint.

#### Scenario: 60th request within 60 seconds succeeds
- **WHEN** an IP issues 60 requests to `/health/live` within a 60-second window
- **THEN** all 60 responses are `200 OK`

#### Scenario: 61st request returns 429 with Retry-After
- **WHEN** an IP issues 61 requests to `/health/live` within a 60-second window
- **THEN** the 61st response is `429 Too Many Requests` AND the `Retry-After` header is present AND its value is a positive integer (seconds)

#### Scenario: live and ready share the same bucket
- **WHEN** an IP issues 30 requests to `/health/live` AND 31 requests to `/health/ready` within a 60-second window (61 total)
- **THEN** the 61st request (regardless of which endpoint) returns `429 Too Many Requests`

#### Scenario: Hash-tag key shape uses hashed IP
- **WHEN** the rate limiter is invoked for IP `1.2.3.4` on `/health/live`
- **THEN** the Redis key passed to `tryAcquireByKey` matches the pattern `{scope:health}:{ip:[0-9a-f]{16}}` where the trailing 16-hex segment equals `IpHasher.hash("1.2.3.4")` AND the literal `1.2.3.4` does NOT appear anywhere in the constructed key AND the user-keyed `tryAcquire(userId, ...)` overload is NOT invoked (no sentinel-UUID workaround anywhere in the call path)

#### Scenario: Same IP hashes to same key (rate-limit consistency)
- **WHEN** the same IP `1.2.3.4` issues two requests to `/health/live` within the 60-second window
- **THEN** both requests construct the byte-identical Redis key (deterministic hash) AND consume from the same Redis bucket AND the second request observes `Allowed.remaining == 58` (capacity 60, two consumed)

#### Scenario: Distinct IPs hash to distinct keys (rate-limit isolation)
- **WHEN** IP `1.2.3.4` issues a request to `/health/live` AND IP `5.6.7.8` issues a request to `/health/live`
- **THEN** the two requests construct DIFFERENT Redis keys (the 16-hex hash differs) AND consume from independent Redis buckets

#### Scenario: Cloud Run probe User-Agent bypasses rate limit
- **WHEN** a request with `User-Agent: GoogleHC/1.0` arrives at `/health/ready`
- **THEN** the rate-limit check is skipped (no `tryAcquireByKey` invocation) AND the request is processed normally; even after 1000 such requests in a minute, no 429 is returned

#### Scenario: Kubernetes-style probe User-Agent also bypasses
- **WHEN** a request with `User-Agent: kube-probe/1.27` arrives at `/health/live`
- **THEN** the rate-limit check is skipped (covers future migration to a K8s deployment without a spec amendment)

#### Scenario: Forged User-Agent costs a scraper nothing it didn't already have
- **WHEN** a scraper sets `User-Agent: GoogleHC/forged` and issues 1000 requests in a minute
- **THEN** the requests succeed (the bypass is honored) — this is documented and accepted, since the equivalent outcome is achievable by IP rotation on an unauthenticated public endpoint anyway
