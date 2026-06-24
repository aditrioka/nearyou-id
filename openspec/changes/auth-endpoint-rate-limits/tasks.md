## 1. Backend — AuthRateLimiter

- [ ] 1.1 Add `backend/ktor/.../auth/AuthRateLimiter.kt` mirroring `PostRateLimiter`: wraps a `RateLimiter` (default `InMemoryRateLimiter`); `trySignin/trySignup/tryRefresh(hashedIp)` methods; per-endpoint literal `keyFor` (`{scope:rate_auth_signin}:{ip:$hashedIp}` etc.); caps `SIGNIN_CAP=10`/`SIGNUP_CAP=5`/`REFRESH_CAP=60` + windows `SIGNIN_WINDOW=1min`/`SIGNUP_WINDOW=1h`/`REFRESH_WINDOW=1min` as constructor-overridable companion constants; sealed `Outcome.Allowed(remaining)` / `Outcome.RateLimited(retryAfterSeconds)`.
- [ ] 1.2 Confirm key construction hoists `hashedIp` out of the literal and uses literal scopes (RedisHashTagRule), non-`_day` keys with hardcoded `Duration` ttl (RateLimitTtlRule passes burst keys), hashed-IP value only (OtelForbiddenAttributeRule); the limiter calls **`tryAcquireByKey`** (the IP/axis-agnostic entry point — `tryAcquire` is user-axis and would trip `IpAxisMustUseTryAcquireByKeyRule`).

## 2. Backend — route guards + DI

- [ ] 2.1 `auth/routes/AuthRoutes.kt`: add `authRateLimiter: AuthRateLimiter` param; guard at the top of `/signin` and `/refresh` (before `call.receive`) — `val hashedIp = IpHasher.hash(call.clientIp)`; on `RateLimited` set `Retry-After` + respond `429` with the existing `errorBody("rate_limited", …)` envelope + `return@post`.
- [ ] 2.2 `auth/signup/SignupRoutes.kt`: add `authRateLimiter` param; same guard on `/signup` using its `SignupErrorBody` envelope.
- [ ] 2.3 `Application.kt`: construct `AuthRateLimiter(rateLimiter)` on the shared env-aware `rateLimiter` and thread it into the `authRoutes(...)` + `signupRoutes(...)` call sites.

## 3. Backend — tests

- [ ] 3.1 `signin` — over-cap (11th call same IP within window) → `429` + positive `Retry-After` AND the endpoint's work is NOT performed (id-token verifier + `users` lookup not invoked — spy/fake assert); under-cap → admitted (InMemoryRateLimiter test double).
- [ ] 3.2 `signup` — over-cap → `429` + `Retry-After` AND `signupService.signup` NOT invoked; under-cap → admitted.
- [ ] 3.3 `refresh` — over-cap → `429` + `Retry-After` AND `tokens.rotate` NOT invoked; under-cap → admitted.
- [ ] 3.4 NoOp fail-soft — with `NoOpRateLimiter`, all three endpoints always admit (no `429` from the limiter).
- [ ] 3.5 Key-shape / IP-hash assertions — the three `keyFor` outputs match `{scope:rate_auth_<endpoint>}:{ip:<16-hex>}` and never contain the raw IP.
- [ ] 3.6 Banned-user appeal interaction — a banned user's `/signin` within cap still returns `403` + `appeal_token` (the limiter does not break the appeal-token path).

## 4. Mobile — client 429 mapping

- [ ] 4.1 `AuthApiClient`: parse the `Retry-After` header into `retryAfterSeconds: Long?` on non-2xx (absent/garbage → `null`); add it to `SignInApiResult.HttpError`.
- [ ] 4.2 `SignInOutcome`: add `data class RateLimited(val retryAfterSeconds: Long)`; `AuthRepository.exchangeIdToken` maps `api.status == 429 -> SignInOutcome.RateLimited(...)`.
- [ ] 4.3 `SignUpOutcome`: add `data class RateLimited(val retryAfterSeconds: Long)`; `AuthRepository.attemptSignUp` maps `api.status == 429 -> SignUpOutcome.RateLimited(...)`.
- [ ] 4.4 `TokenRefresher.performRefresh`: branch on `response.status.value == 429` BEFORE the terminal-invalidate branch — keep the token pair, do NOT call `sessionInvalidator.invalidate()`, and surface it by THROWING a dedicated transient/retryable exception (the transport-failure path → `completeExceptionally` so followers also keep tokens), NEVER by returning `null` (which is the invalidate contract).

## 5. Mobile — UI copy + state

- [ ] 5.1 Add `Res.string` entries (CMP Resources) for the rate-limited copy (no PII), e.g. `signin_error_rate_limited` / `age_gate_error_rate_limited` (Indonesian "terlalu banyak percobaan, coba lagi sebentar").
- [ ] 5.2 `SignInUiState` / `AgeGateUiState`: map the new `RateLimited` outcome to the new string key.

## 6. Mobile — tests

- [ ] 6.1 `AuthApiClient`: `Retry-After` present → `retryAfterSeconds` parsed; absent → `null`.
- [ ] 6.2 `AuthRepository`: signin `429` → `SignInOutcome.RateLimited`; signup `429` → `SignUpOutcome.RateLimited`.
- [ ] 6.3 `TokenRefresher`: `429` → token pair kept + `invalidate()` NOT called + the leader THROWS a transient exception (not `null`); a concurrent follower also keeps tokens (observes the thrown transient, not a `null`-driven logout); a non-429 non-2xx still invalidates (unchanged).
- [ ] 6.4 UI-state mapping test for the new `RateLimited` outcome → expected string key.

## 7. Docs + spec sync

- [ ] 7.1 Amend docs/05 §Layer-1: document the shipped auth-endpoint limiter (three scopes/caps/windows) + note the conforming `{scope:…}:{ip:…}` key shape supersedes the illustrative `rate:guest_issue:{ip:…}` table shapes; reaffirm CF/Cloud-Armor (docs/08 #39) stays a separate pre-launch layer.
- [ ] 7.2 `openspec validate auth-endpoint-rate-limits --strict` passes.

## 8. Gates + verification

- [ ] 8.1 `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` green.
- [ ] 8.2 `./gradlew :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest` green.
- [ ] 8.3 Manual verification of the rate-limited UI state per docs/11 §5 (verify-loop / mobile-ui-foundation) — screenshot evidence in the PR body before archive.
