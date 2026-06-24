## ADDED Requirements

### Requirement: Application-level rate limits on the unauthenticated auth endpoints

The system SHALL apply an in-process, IP-hash-keyed rate limiter to the three unauthenticated auth-exchange endpoints — `POST /api/v1/auth/signin`, `POST /api/v1/auth/signup`, and `POST /api/v1/auth/refresh` — as defense-in-depth, independent of (and not a replacement for) the pre-launch Cloudflare / Cloud Armor edge layer (docs/08 #39).

The limiter SHALL reuse the shipped substrate: the `RateLimiter` `tryAcquireByKey(key, capacity, ttl)` entry point, `IpHasher.hash(call.clientIp)` for the IP axis, and the `429 Too Many Requests` + `Retry-After` response idiom. Each endpoint SHALL use a distinct hash-tag-conforming key of the form `{scope:rate_auth_<endpoint>}:{ip:<hashed-ip>}` and a sliding window (no `_day` marker):

- `signin` — capacity **10**, window **1 minute**, scope `rate_auth_signin`
- `signup` — capacity **5**, window **1 hour**, scope `rate_auth_signup`
- `refresh` — capacity **60**, window **1 minute**, scope `rate_auth_refresh`

The limiter check SHALL run before request-body deserialization and any verification / DB work, and on a rate-limited outcome SHALL respond `429` with a `Retry-After` header (seconds) and the endpoint's existing error envelope, performing none of the endpoint's normal work (id-token verification, signup processing, refresh-token rotation, and login-history recording are all skipped — a `429`'d attempt intentionally leaves no `login_events` row, the limiter itself being the anti-abuse signal).

Because caller identity is unknown before id-token verification, the limiter applies uniformly to every caller and SHALL NOT exempt any user class (including banned/suspended users); the caps are sized to leave ample headroom for legitimate use, including a banned user obtaining their limited-scope appeal token from the `/signin` `403` path.

The binding SHALL fail soft: when the rate limiter is the NoOp / unconfigured-Redis implementation, every call SHALL be admitted (parity with all shipped per-endpoint limiters), so the limiter can never make Redis a hard dependency of authentication.

#### Scenario: Signin over cap returns 429 + Retry-After
- **WHEN** more than 10 `POST /api/v1/auth/signin` requests arrive from the same hashed IP within 1 minute (Redis-backed limiter)
- **THEN** the over-cap request receives `429 Too Many Requests` with a `Retry-After` header carrying a positive seconds value
- **AND** the id-token verification and the `users` lookup for that request are not performed

#### Scenario: Signin under cap is admitted
- **WHEN** 10 or fewer `POST /api/v1/auth/signin` requests arrive from the same hashed IP within 1 minute
- **THEN** each request proceeds to normal sign-in processing (no `429` from the limiter)

#### Scenario: Signup over cap returns 429 + Retry-After
- **WHEN** more than 5 `POST /api/v1/auth/signup` requests arrive from the same hashed IP within 1 hour (Redis-backed limiter)
- **THEN** the over-cap request receives `429 Too Many Requests` with a `Retry-After` header
- **AND** the signup service is not invoked for that request

#### Scenario: Refresh over cap returns 429 + Retry-After
- **WHEN** more than 60 `POST /api/v1/auth/refresh` requests arrive from the same hashed IP within 1 minute (Redis-backed limiter)
- **THEN** the over-cap request receives `429 Too Many Requests` with a `Retry-After` header
- **AND** the refresh-token rotation is not performed for that request

#### Scenario: Auth limiter keys conform to the hash-tag standard and never carry a raw IP
- **WHEN** any of the three auth-endpoint limiter keys is constructed
- **THEN** the key has the two-segment form `{scope:rate_auth_<endpoint>}:{ip:<hashed-ip>}` (passing `RedisHashTagRule`)
- **AND** the `{ip:…}` segment carries the `IpHasher` hash of `call.clientIp`, never the raw client IP (passing `OtelForbiddenAttributeRule`)

#### Scenario: NoOp / unconfigured Redis fails soft (always admits)
- **WHEN** the bound rate limiter is the NoOp / unconfigured-Redis implementation (dev / test / Redis-unset)
- **THEN** every `signin` / `signup` / `refresh` request is admitted with no `429` from the limiter
- **AND** authentication does not depend on Redis availability

#### Scenario: Mobile maps a signin 429 to a rate-limited outcome
- **WHEN** the mobile client receives `429` from `POST /api/v1/auth/signin`
- **THEN** `AuthApiClient` parses the `Retry-After` header into `retryAfterSeconds` (absent → `null`)
- **AND** `AuthRepository` maps it to `SignInOutcome.RateLimited`, surfacing a static "too many attempts, try again shortly" string (no PII) — not the generic network-error state

#### Scenario: Mobile maps a signup 429 to a rate-limited outcome
- **WHEN** the mobile client receives `429` from `POST /api/v1/auth/signup`
- **THEN** `AuthRepository` maps it to `SignUpOutcome.RateLimited` carrying the parsed `retryAfterSeconds`, surfacing the static rate-limited copy — not the generic retryable-error state

#### Scenario: A banned user is still served the appeal token within the signin cap
- **WHEN** a banned/suspended user calls `POST /api/v1/auth/signin` within the signin cap (Redis-backed limiter)
- **THEN** the request proceeds normally and the `403` response carries the limited-scope `appeal_token` (content-moderation-appeal)
- **AND** the limiter does not exempt the banned user (their identity is unknown before id-token verification), but the signin cap (10/min) leaves ample headroom to obtain the appeal token, and the ban-exempt appeal realm itself is not gated by this change

#### Scenario: A rate-limited refresh does not invalidate the session (leader and followers)
- **WHEN** the mobile `TokenRefresher` receives `429` from `POST /api/v1/auth/refresh`
- **THEN** the persisted token pair is kept (the user is not logged out) and `SessionInvalidator.invalidate()` is not called
- **AND** the leader surfaces the `429` as a transient/retryable signal by THROWING (the transport-failure contract, propagated to followers via the shared deferred), NEVER by returning `null` — returning `null` is the rejected-refresh-token / invalidate contract and would log followers out
- **AND** a concurrent follower awaiting the in-flight refresh observes the same transient outcome and also keeps its tokens, distinct from the rejected-refresh-token path that does invalidate
