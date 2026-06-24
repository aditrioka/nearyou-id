## Context

The three unauthenticated auth-exchange endpoints (`/signin`, `/signup`, `/refresh`) have no rate protection (issue #214; audit finding 01-#16). The project already ships a mature Redis rate-limit substrate (`rate-limit-infrastructure` capability): a `RateLimiter` interface in `:core:domain` with a Redis-backed Lua sliding-window implementation in `:infra:redis`, an axis-agnostic `tryAcquireByKey(key, capacity, ttl)` entry point, an `IpHasher` for hashing `call.clientIp`, a `NoOpRateLimiter` fail-soft binding for dev/test, and a `429 + Retry-After` response idiom. The closest existing precedent is `HealthRoutes.checkRateLimit` — an IP-hash-keyed anti-scrape limiter using `tryAcquireByKey` + `{scope:health}:{ip:$hashedIp}`. This change reuses all of it; there is no new infrastructure.

The constraint that shapes the numbers is the docs/05 §Layer-1 **CGNAT note**: IP-keyed limits risk false-positives behind carrier-grade NAT, public WiFi, and corporate NAT where many real users share one egress IP. The wave-8 "composer-429" lesson is the other shaping constraint: a server-side `429` is worthless if the client maps it to a generic error — the client outcome mapping MUST ship in the same change.

## Goals / Non-Goals

**Goals:**
- A cheap, always-present in-process limiter on `/signin`, `/signup`, `/refresh`, keyed by hashed client IP, reusing the shipped substrate verbatim.
- Numbers that bound abuse (credential hammering, account enumeration, mass signup, refresh-path flooding) **without** locking out legitimate shared-IP (CGNAT) users.
- Full client mapping in the same change: a rate-limited user sees a correct "try again shortly" state, and a rate-limited *refresh* never logs the user out.
- Fail-soft parity with every existing limiter: an unconfigured/NoOp Redis always admits (the limiter never becomes a hard dependency that can take auth down).

**Non-Goals:**
- Cloudflare rate rules / Cloud Armor allow-only-CF ingress (docs/08 #39) — a separate, still-wanted pre-launch edge layer. This change is explicitly additive to it, not a replacement.
- The guest-token issuance flow + its Layer-1 pre-issuance limits (docs/05 §Layer-1 table) — guest mode is unbuilt; out of scope.
- Per-user (Layer 2) or per-identifier (Layer 3) limits on these endpoints — the only axis available pre-auth is the IP.
- Multi-tier (burst + sustained) limits per endpoint — a single window per endpoint matches the altitude of every shipped per-endpoint limiter; revisit only if telemetry demands it.

## Decisions

### D1 — Reuse `tryAcquireByKey` via a new `AuthRateLimiter`, not the route-bare pattern

`HealthRoutes` calls the bare `RateLimiter` directly. The feature limiters (`PostRateLimiter`, `FollowRateLimiter`, …) wrap the shared `rateLimiter` in a small per-feature class with literal `keyFor` functions and a sealed `Outcome`. We follow the **feature-limiter** convention (one `AuthRateLimiter` with `trySignin/trySignup/tryRefresh`), because there are three distinct caps/windows and the per-feature class keeps the scope literals + caps in one testable place. **Alternative considered:** three separate limiter classes — rejected, they share one axis (IP) and one shape; one class with three methods is the rule-of-three-respecting fit.

### D2 — Key shape: `{scope:rate_auth_<endpoint>}:{ip:$hashedIp}` (NOT the issue's `rate:{ip:…}`)

The issue body proposed `rate:{ip:<hashed-ip>}`. That **fails `RedisHashTagRule`**, which requires the two-segment `{scope:<x>}:{axis:<y>}` form (precedent: #327). The shipped conforming shape — literal scope hash-tag + simple-name `ip` interpolation, exactly as `PostRateLimiter.keyFor` and `HealthRoutes` do it — is used instead: `{scope:rate_auth_signin}:{ip:$hashedIp}`, `{scope:rate_auth_signup}:{ip:$hashedIp}`, `{scope:rate_auth_refresh}:{ip:$hashedIp}`. The hash is hoisted out of the literal (`val hashedIp = IpHasher.hash(call.clientIp)`) per the `HealthRoutes` block-interpolation-false-positive note, and the value is a 16-hex hash so `OtelForbiddenAttributeRule` (raw-IP fence) passes. None of the scopes end in `_day`, so they take the **sliding window** (correct for burst/short windows) and `RateLimitTtlRule` passes a hardcoded `Duration` ttl on a non-`_day` key.

### D3 — The numbers (finalizing the issue's "starting numbers", minding CGNAT)

The issue gave starting numbers and **explicitly delegated finalizing them to design.md, "mind the docs/05 CGNAT note"**. Finalized:

| Endpoint | Cap / window | vs. issue | Rationale |
|---|---|---|---|
| `signin` | **10 / 1 min** per IP-hash | unchanged | Interactive (gated by a Google/Apple ceremony) → legitimate per-IP rate is low even under modest CGNAT sharing. Catches credential/enumeration hammering. 1-min window resets fast, so CGNAT accumulation is bounded to a single minute. |
| `signup` | **5 / 1 hour** per IP-hash | unchanged | Account creation is rare per real user; bounds mass-registration. The anti-abuse-strongest of the three and operator-named — kept. CGNAT exposure (an event with >5 genuine signups/hour behind one NAT) is rare; acceptable defense-in-depth given fail-soft + a future flag knob. |
| `refresh` | **60 / 1 min** per IP-hash | **changed from 60/hour** | **Deliberate deviation.** Refresh fires automatically/in-background for *every* active user on each ~15-min access-token TTL (≈4 refreshes/user/hour). A per-**hour** cap accumulates those across *all* users sharing a CGNAT/public-WiFi/corporate-NAT egress IP — e.g. ~15 active users behind one IP would exceed 60/hour and get logged out — the precise CGNAT false-positive the docs/05 note warns against. A per-**minute** flood cap has a window short enough that it never accumulates across the hour (tolerates ~60 concurrent refreshing users behind one IP/min, well above realistic shared-IP density) while still stopping a genuine refresh flood. Same protective intent, CGNAT-safe shape. |

**On what the refresh limiter actually protects (scoped honestly):** its primary value is bounding *flood amplification* of the refresh path, not a tight bound on the rotate DB-write. A successful rotation (the DB write) requires a *valid* refresh token — an attacker without one is rejected cheaply (`RefreshTokenInvalidException`, no successful write), and `60/min` is a loose ceiling for a single attacker who *does* hold a valid token. Sustained single-IP abuse is the **edge layer's** job (docs/08 #39); this in-process limiter is the cheap always-present floor, not the tight bound. The per-minute window is chosen for CGNAT safety first.

The numbers are conservative-by-design (favor never blocking a real user over tight bounds), since this is defense-in-depth behind a future edge layer, the binding fails soft, and a future Remote-Config knob can tune them without a redeploy if telemetry shows otherwise. The IP-axis `signup` cap here is **additive** to the (still-unbuilt) docs/06 per-identifier signup limit (3/24h per Google/Apple ID hash, Layer 3) — a different axis; neither is the whole signup-abuse story alone.

### D4 — Guard placement: before `call.receive`, respond `429 + Retry-After`

The limiter check runs **first** in each handler — before body deserialization or any DB/verification work — so a flood of garbage bodies is bounced cheaply. On `RateLimited` it sets the `Retry-After` header (seconds) and responds `429` with the endpoint's existing error envelope (`{"error":{"code":"rate_limited",…}}` for signin/refresh; signup's envelope shape), then returns. The mobile client keys on **status (429)**, not the body code (consistent with the existing status-driven auth mapping), and reads `Retry-After` for the backoff hint.

### D5 — Client mapping (same change): `RateLimited` outcome + refresh ≠ logout

- `AuthApiClient` parses `Retry-After` on any non-2xx into `retryAfterSeconds: Long?` (absent/garbage → `null`), carried on `SignInApiResult.HttpError`. Mirrors the shipped `SearchApiClient` Retry-After parse.
- `SignInOutcome.RateLimited(retryAfterSeconds)` and `SignUpOutcome.RateLimited(retryAfterSeconds)` are added; `AuthRepository` maps `api.status == 429` to them. Today a `429` silently lands in the `else -> NetworkError` (signin) / `else -> RetryableError` (signup) bucket — a defined-but-wrong state.
- **`TokenRefresher.performRefresh`**: today *any* non-2xx calls `sessionInvalidator.invalidate()` → logout + re-route to SignInScreen. A `429` is **not** a rejected refresh token — it must keep the token pair and surface a transient/retryable condition. **Leader/follower contract (load-bearing):** the single-flight leader publishes its result to followers via a shared `CompletableDeferred`, where `null` means "session invalidated" (followers log out) and a thrown exception means "transport failure" (followers rethrow, tokens kept). So a `429` MUST be surfaced by **throwing a transient/retryable exception** (the transport-failure path: `completeExceptionally` → followers rethrow, tokens kept), **never by returning `null`** (which is the invalidate contract and would log every follower out). Implemented by branching on `response.status.value == 429` before the terminal-invalidate branch and throwing a dedicated transient exception. This is a real correctness fix the limiter would otherwise *introduce* (without it, a refresh flood from a CGNAT peer would log innocent users — leader and all followers — out).
- UI: `SignInUiState` / `AgeGateUiState` map the new `RateLimited` outcome to a static `Res.string.*` key (no PII), an Indonesian "terlalu banyak percobaan, coba lagi sebentar" copy. Strings via Compose Multiplatform Resources only (mobile invariant).

### D6 — Fail-soft parity

`AuthRateLimiter` is wired on the shared env-aware `rateLimiter` in `Application.kt`. In dev/test (or when the Redis secret is unset) that is `NoOpRateLimiter`, which always returns `Allowed` — so auth is never gated by Redis availability, exactly as every other limiter behaves. Production routes through the Redis-backed `RedisRateLimiter`.

## Risks / Trade-offs

- **CGNAT false-positives on signin/signup** → Mitigated by short windows (signin 1-min) and conservative caps; signup's 1-hour window is the residual exposure, accepted as defense-in-depth (rare to have >5 genuine signups/hour behind one NAT) and recoverable (fail-soft + future flag). Refresh — the worst CGNAT offender — is specifically de-risked by D3 (per-minute, not per-hour).
- **Limiter becomes an auth availability dependency** → Mitigated by D6 fail-soft (NoOp/unconfigured always admits); the limiter can never take sign-in down.
- **Raw IP leaking into a Redis key / telemetry span** → Mitigated by `IpHasher.hash` + the `OtelForbiddenAttributeRule` fence (16-hex value only); the raw `call.clientIp` never reaches the Lua key.
- **A `429` on refresh logging users out** → This is the failure the change must *prevent*, not cause: D5's `TokenRefresher` branch keeps tokens and skips `invalidate()` on `429`. Covered by a dedicated regression test (tokens kept + `invalidate()` not called).
- **A banned user being 429'd on `/signin` cannot obtain their appeal token** → The limiter runs before identity is known, so it cannot exempt banned users. Accepted: the appeal token is obtained once from a normal `/signin` `403` (then stashed client-side via `appealSession`), and the `10/min` signin cap leaves ample headroom for a real human to obtain it; the ban-exempt appeal realm itself is not gated by this change. Pinned by a spec scenario.
- **An environment with no `CF-Connecting-IP` / `X-Forwarded-For` collapses every caller into one `{ip:<hash-of-"unknown">}` bucket** (the inverse of CGNAT — `clientIp` falls back to the literal `"unknown"`), which could mass-rate-limit all callers → Low risk in practice: Cloud Run sets `X-Forwarded-For`, so the fallback is not hit in staging/prod; and where Redis is unconfigured the limiter is NoOp (fail-soft, admits all). Noted so a future ingress-misconfig is recognized as the cause rather than a limiter bug.
- **Numbers wrong at real traffic** → Conservative defaults + fail-soft + a future Remote-Config tuning knob; revisit when staging/prod telemetry exists. Not a launch blocker either way.

## Migration Plan

Pure additive deploy — no DB migration, no new secret (reuses the existing Redis seam). On deploy the limiter activates in any environment where the Redis-backed `rateLimiter` is bound; dev/test/staging-without-Redis stay fail-soft. Rollback is a plain revert (no schema or data state). Docs/05 §Layer-1 is amended in the same PR.

## Open Questions

None blocking. Post-launch tuning of the three caps against real traffic is expected and cheap (constructor defaults today; a Remote-Config knob is a trivial follow-up if telemetry warrants it).
