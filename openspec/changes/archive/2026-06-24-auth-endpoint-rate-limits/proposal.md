## Why

The three unauthenticated auth-exchange endpoints — `POST /api/v1/auth/signin`, `POST /api/v1/auth/signup`, `POST /api/v1/auth/refresh` — currently have **no rate protection at any layer**. The 2026-06-10 holistic audit (finding 01-#16; operator-approved 2026-06-11, issue #214) established the facts: docs/05 §Layer-1 (per-IP) is "future work" and unbuilt; no Cloudflare rate-limit rules exist; `api-staging.nearyou.id` CNAMEs straight to Cloud Run (not even CF-proxied); and the Cloud Armor allow-only-CF ingress is an undone pre-launch task (docs/08 #39). So each of these endpoints can be hammered for credential-stuffing / account enumeration (`404 user_not_found` vs `200`), mass account creation, or refresh-path DB-write flooding, with nothing in front of them.

This change adds a light, defense-in-depth, **app-level** limiter on those three endpoints, reusing the already-shipped Redis rate-limit substrate. It does **not** replace the pre-launch Cloudflare / Cloud Armor work (docs/08 #39 + CF rate rules) — both layers are wanted; this is the cheap in-process layer that exists regardless of edge posture.

## What Changes

- **Backend — new `AuthRateLimiter`** (in the `auth` package, mirroring `PostRateLimiter`) wrapping the shared env-aware `RateLimiter`, with one per-endpoint method + literal `keyFor` per endpoint, keyed by **hashed client IP** (`IpHasher.hash(call.clientIp)`):
  - `signin` — **10 / 1 minute** per IP-hash (sliding window)
  - `signup` — **5 / 1 hour** per IP-hash (sliding window)
  - `refresh` — **60 / 1 minute** per IP-hash (sliding window) — see design.md for the deliberate deviation from the issue's `60/hour` (CGNAT safety)
- **Backend — route guards**: each handler checks its limiter **first** (before `call.receive`); on `RateLimited` it responds `429 Too Many Requests` + `Retry-After` header + the endpoint's existing error envelope, then returns. Wired through `Application.kt` DI on the shared `rateLimiter` (NoOp fail-soft in dev/test always admits — parity with every shipped limiter).
- **Mobile — client 429 mapping (same change, the wave-8 "composer-429" lesson)**:
  - `AuthApiClient` parses the `Retry-After` header into `retryAfterSeconds` on non-2xx; carried on `SignInApiResult.HttpError`.
  - `SignInOutcome.RateLimited(retryAfterSeconds)` + `SignUpOutcome.RateLimited(retryAfterSeconds)` added; `AuthRepository` maps `429` to them (today a `429` silently falls into the generic `NetworkError` / `RetryableError` bucket).
  - **`TokenRefresher` correctness fix**: a `429` on refresh must **not** invalidate the session. Today any non-2xx hits `sessionInvalidator.invalidate()` → logout; a rate-limited refresh must keep the token pair and surface a transient/retryable condition.
  - New static `Res.string.*` copy for the rate-limited state (no PII), surfaced by `SignInUiState` / `AgeGateUiState`. (The `refresh` path has no screen — a refresh `429` is handled silently as a transient/retryable condition with no user-visible string.)
- **Docs** — amend docs/05 §Layer-1 to document the shipped auth-endpoint limiter (scopes / caps / windows) and note the conforming `{scope:…}:{ip:…}` key shape supersedes the illustrative pre-`RedisHashTagRule` `rate:guest_issue:{ip:…}` table shapes; reaffirm CF / Cloud Armor (docs/08 #39) stays a separate wanted pre-launch layer.

No DB migration. No new infra module — pure Redis + app code on the existing substrate.

## Capabilities

### New Capabilities

_None._ This extends an existing capability.

### Modified Capabilities

- `rate-limit-infrastructure`: ADD one requirement — application-level rate limits on the three unauthenticated auth endpoints (per-endpoint cap/window/IP-hash-key scenarios + NoOp fail-soft parity + the client outcome-mapping scenarios, including refresh-429-does-not-invalidate).

## Impact

- **Backend code**: new `backend/ktor/.../auth/AuthRateLimiter.kt`; route guards in `auth/routes/AuthRoutes.kt` (signin + refresh) and `auth/signup/SignupRoutes.kt`; DI wiring + signature additions in `Application.kt`. New backend tests for the three endpoints (over-cap → 429 + Retry-After, under-cap admit, NoOp fail-soft admit, key-shape/IP-hash assertions).
- **Mobile code**: `AuthApiClient` (Retry-After parse + `HttpError.retryAfterSeconds`), `AuthRepository` (signin/signup 429 mapping), `SignInOutcome` / `SignUpOutcome` (new `RateLimited` member), `TokenRefresher` (429 ≠ invalidate), `SignInUiState` / `AgeGateUiState` (copy mapping), new `Res.string` entries. New mobile unit tests.
- **APIs**: the three endpoints gain a `429` response shape (`Retry-After` header + envelope) — additive, no breaking change to existing 2xx/4xx contracts.
- **Detekt / invariants honored**: `RedisHashTagRule` (two-segment conforming keys), `RateLimitTtlRule` (burst keys with hardcoded `Duration` ttl pass; no `_day` marker), `OtelForbiddenAttributeRule` (hashed IP only in `{ip:}`), the `clientIp` invariant.
- **Docs**: docs/05 §Layer-1 amended (stated here so reviewers don't flag the divergence).
- **Out of scope (explicit)**: Cloudflare rate rules + Cloud Armor allow-only-CF ingress (docs/08 pre-launch task #39) — a separate pre-launch edge layer, unchanged by this change. The IP-axis `signup` cap is also **additive** to the still-unbuilt docs/06 per-identifier signup limit (3/24h per Google/Apple ID hash, a different Layer-3 axis) — neither alone is the whole signup-abuse story.
