## MODIFIED Requirements

### Requirement: `/internal/*` routes require Google OIDC bearer token

The Ktor backend SHALL gate every route mounted under the `/internal/*` route subtree behind a Ktor plugin (`InternalEndpointAuth`) that verifies a Google OIDC bearer token before dispatch. A request reaching an `/internal/*` route MUST present an `Authorization: Bearer <token>` header whose value is a Google-issued OIDC JWT. Missing, malformed, or non-bearer Authorization headers MUST short-circuit the request with HTTP `401 Unauthorized` before any handler logic runs.

The plugin MUST verify four properties on every request:

1. **Signature**: the JWT signature MUST validate against Google's published JWKS at `https://www.googleapis.com/oauth2/v3/certs`. JWKS responses MUST be cached with rotation-aware refresh — when a token's `kid` header references a key not present in cache, the verifier MUST force one JWKS refresh before rejecting.
2. **Audience**: the JWT `aud` claim MUST equal the configured audience value, supplied via plain Ktor `application.conf` config (key `oidc.internalAudience`, resolved from the `INTERNAL_OIDC_AUDIENCE` environment variable). The audience is the deployed Cloud Run service URL — a public, non-secret value — and therefore is read via plain Ktor config rather than the project's `secretKey(env, name)` helper, which is reserved for genuine secret material. See the "Configured audience is required at boot" requirement below for boot-time validation.
3. **Expiry**: the JWT `exp` claim MUST be in the future relative to the verification clock. The `iat` claim, when present, MUST NOT be in the future (with a 60-second skew tolerance to absorb clock drift).
4. **Caller principal**: the JWT `email` claim MUST be present, the `email_verified` claim MUST be `true`, AND the email — compared case-insensitively — MUST be a member of the configured allowed-principals set (see the "Allowed caller principals are configured, and an empty allowlist denies every caller" requirement below). The `sub` claim (Google's opaque numeric service-account unique id) is NOT used for this check: the email is the identity Cloud Scheduler binds via `--oidc-service-account-email`, and a `<name>@<project>.iam.gserviceaccount.com` address can only be minted by the owning project. Property 4 is evaluated only after properties 1–3 pass, so it is never reached by an unauthenticated token.

Verification failure on any of properties 1–3 MUST short-circuit with HTTP `401 Unauthorized`. A failure on property 4 alone — a genuine, unexpired, correctly-audienced Google token whose principal is not allowlisted (including a token with no `email` claim or with `email_verified` not `true`) — MUST short-circuit with HTTP `403 Forbidden` and body `{"error": "principal_not_allowed"}` before any handler logic runs: the caller is authenticated but not authorized. The response body MUST NOT echo the offending token, JWT claims, signature failure detail, or any verifier exception message — only a short fixed vocabulary `error` field as documented in the response-shape requirement below.

The full original verifier exception MUST be logged at WARN with a token correlation id derived as the first 16 hex chars of `SHA-256(raw token bytes)` so operators can correlate failures with Cloud Scheduler invocation logs without ever logging JWT claims or the raw token. The `jti` claim is intentionally NOT used (logging any claim would conflict with the no-claims rule below); the truncated SHA-256 form is unconditional. A property-4 rejection is logged the same way (`reason=principal_not_allowed`) and MUST NOT log the presented `email` or the configured allowlist.

A property-4 rejection happens inside the verifier, before the plugin stores the verified subject on the call; it therefore MUST NOT set `OidcSubjectKey` and MUST NOT write the `service.account.id` span attribute (a rejected caller does not enrich the trace surface).

#### Scenario: Missing Authorization header is rejected
- **WHEN** a request to `POST /internal/unban-worker` is sent without any `Authorization` header
- **THEN** the response status is `401 Unauthorized` AND no handler logic runs

#### Scenario: Non-Bearer scheme is rejected
- **WHEN** a request to `POST /internal/unban-worker` is sent with `Authorization: Basic dXNlcjpwYXNz`
- **THEN** the response status is `401 Unauthorized`

#### Scenario: Malformed JWT structure is rejected
- **WHEN** a request to `POST /internal/unban-worker` is sent with `Authorization: Bearer not.a.jwt`
- **THEN** the response status is `401 Unauthorized`

#### Scenario: Invalid signature is rejected
- **WHEN** a request presents a JWT whose signature does not validate against Google's JWKS
- **THEN** the response status is `401 Unauthorized` AND the response body MUST NOT contain the offending token or the verifier's exception message

#### Scenario: Audience mismatch is rejected
- **WHEN** a request presents a Google-signed JWT whose `aud` claim is `https://example.com/other-service` and the configured `oidc.internalAudience` is `https://api-staging.nearyou.id`
- **THEN** the response status is `401 Unauthorized`

#### Scenario: Expired token is rejected
- **WHEN** a request presents a JWT whose `exp` claim is 60 seconds in the past relative to the server clock
- **THEN** the response status is `401 Unauthorized`

#### Scenario: Valid Cloud Scheduler token is admitted
- **WHEN** a request presents a Google-signed JWT whose signature validates, whose `aud` matches the configured audience, whose `exp` is in the future, AND whose `email` is an allowlisted principal with `email_verified = true`
- **THEN** the plugin admits the request to the route handler

#### Scenario: Foreign service account with the correct audience is forbidden
- **WHEN** a request to `POST /internal/unban-worker` presents a Google-signed, unexpired JWT whose `aud` equals the configured audience AND whose `email` is `attacker@other-project.iam.gserviceaccount.com` (verified, but not in the allowlist)
- **THEN** the response status is `403 Forbidden` AND the body is exactly `{"error": "principal_not_allowed"}` AND no handler logic runs

#### Scenario: Token without an email claim is forbidden
- **WHEN** a request presents a Google-signed, unexpired, correctly-audienced JWT that carries no `email` claim
- **THEN** the response status is `403 Forbidden` with `{"error": "principal_not_allowed"}`

#### Scenario: Unverified email is forbidden
- **WHEN** a request presents a Google-signed, unexpired, correctly-audienced JWT whose `email` is allowlisted but whose `email_verified` claim is `false`
- **THEN** the response status is `403 Forbidden` with `{"error": "principal_not_allowed"}`

#### Scenario: Allowlist comparison ignores case
- **WHEN** the allowlist holds a principal in lowercase AND a valid token presents the same address with different letter case in its `email` claim
- **THEN** the plugin admits the request to the route handler

#### Scenario: Authentication failures are reported before the principal check
- **WHEN** a request presents a Google-signed JWT from a non-allowlisted principal whose `aud` does NOT match the configured audience
- **THEN** the response status is `401 Unauthorized` with `{"error": "audience_mismatch"}` (property 4 is never evaluated for a token that fails properties 1–3)

#### Scenario: Principal rejection writes no principal-correlation span attribute
- **WHEN** a request presents an otherwise-valid token from a non-allowlisted principal AND the plugin rejects it `403`
- **THEN** the server span for that request does NOT carry `service.account.id` (the subject is never stored on the call)

#### Scenario: JWKS rotation refresh resolves the new key
- **WHEN** a request from an allowlisted principal presents a JWT whose `kid` header references a key not in the JWKS cache AND that key IS present in the live Google JWKS endpoint (post-rotation)
- **THEN** the verifier forces one JWKS refresh, the cache is updated, signature verification succeeds against the refreshed key, AND the request is admitted to the route handler

#### Scenario: JWKS rotation refresh still does not resolve the kid
- **WHEN** a request presents a JWT whose `kid` header references a key not in the JWKS cache AND that key is also absent from the live Google JWKS endpoint after the forced refresh
- **THEN** the verifier returns `401` with body `{"error": "invalid_token"}` (no further refresh attempts; no infinite loop)

### Requirement: Rejection response body uses a sanitized error vocabulary

When the plugin rejects a request, the response body SHALL be a JSON object with exactly one field `error` whose value is one of a fixed short vocabulary.

With status `401 Unauthorized`:
- `"missing_authorization"` — no `Authorization` header
- `"invalid_scheme"` — header present but not `Bearer`
- `"invalid_token"` — JWT structurally malformed, signature invalid, or claims fail verification
- `"expired_token"` — JWT signature valid but `exp` in the past
- `"audience_mismatch"` — JWT signature valid but `aud` does not match configured audience

With status `403 Forbidden`:
- `"principal_not_allowed"` — JWT signature, audience, and expiry all valid, but the caller principal fails the allowlist check (no `email` claim, `email_verified` not `true`, email not allowlisted, or the allowlist is empty)

The response body MUST NOT contain the offending token bytes, the JWT claims (including the presented `email`), signature failure details, JWKS contents, the configured audience value, the configured allowlist, or any verifier-internal exception message. Stack traces MUST NOT appear. The original verification failure context MUST be logged at WARN with full detail for operator debugging, within the no-claims logging rule.

#### Scenario: Sanitized response on signature failure
- **WHEN** a request presents a JWT with an invalid signature
- **THEN** the response body parses as JSON with exactly `{"error": "invalid_token"}` AND does NOT contain the offending token, the JWT claims, the JWKS, or any exception message

#### Scenario: Sanitized response on audience mismatch
- **WHEN** a request presents a JWT whose `aud` does not match the configured audience
- **THEN** the response body is `{"error": "audience_mismatch"}` AND does NOT echo the configured audience value

#### Scenario: Sanitized response on principal rejection
- **WHEN** a request presents an otherwise-valid JWT whose `email` is not allowlisted
- **THEN** the response status is `403` AND the body is exactly `{"error": "principal_not_allowed"}` AND does NOT contain the presented email or any allowlisted email


## ADDED Requirements

### Requirement: Allowed caller principals are configured, and an empty allowlist denies every caller

Application startup SHALL read the allowed-principals set from Ktor config key `oidc.allowedPrincipals`, resolved from the `INTERNAL_OIDC_ALLOWED_PRINCIPALS` environment variable: a comma-separated list of service-account emails, each entry trimmed and lowercased, blank entries dropped. Service-account emails are non-secret configuration (public-repository posture), so the value is read via plain Ktor config rather than the `secretKey(env, name)` helper — the same rationale as the OIDC audience.

An unset, blank, or all-blank-entries value SHALL NOT fail boot (local development, test harnesses, and a fresh environment keep booting) but SHALL be **fail-closed**: the resolved set is empty, startup logs a WARN line `event=internal_oidc_allowlist_empty`, and every request to an OIDC-gated `/internal/*` route — including one carrying an otherwise-valid token — is rejected `403 {"error": "principal_not_allowed"}`. This deliberately inverts the repository's fail-soft "unresolved secret = no-op" convention: for an authorization control, "unconfigured" MUST mean "deny every caller", never "admit every caller".

Each deployed environment SHALL set the allowlist to exactly the Cloud Scheduler service account(s) that invoke its workers. For staging that is `scheduler-invoker-staging@nearyou-staging.iam.gserviceaccount.com` — the account `dev/scripts/provision-schedulers.sh` (#535) binds to every worker job — plus, during the migration window only, `unban-scheduler-staging@nearyou-staging.iam.gserviceaccount.com` (the account the pre-#535 staging jobs run as), which SHALL be removed from the allowlist once every staging job runs as `scheduler-invoker-staging`.

#### Scenario: Unset allowlist boots and denies every caller
- **WHEN** `INTERNAL_OIDC_ALLOWED_PRINCIPALS` is unset AND the application starts
- **THEN** boot succeeds AND a WARN `event=internal_oidc_allowlist_empty` is logged AND a request to `POST /internal/unban-worker` with an otherwise-valid token is rejected `403` with `{"error": "principal_not_allowed"}`

#### Scenario: Blank allowlist entries resolve to an empty, denying set
- **WHEN** `oidc.allowedPrincipals` is `" , ,"`
- **THEN** the resolved allowed-principals set is empty AND the WARN `event=internal_oidc_allowlist_empty` is logged (fail-closed, not "allow all")

#### Scenario: Comma-separated allowlist is trimmed and lowercased
- **WHEN** `oidc.allowedPrincipals` is `" Scheduler-A@p.iam.gserviceaccount.com , scheduler-b@p.iam.gserviceaccount.com ,"`
- **THEN** the resolved set is exactly `{scheduler-a@p.iam.gserviceaccount.com, scheduler-b@p.iam.gserviceaccount.com}` AND no WARN is logged


## RENAMED Requirements

- FROM: `### Requirement: 401 response body uses a sanitized error vocabulary`
- TO: `### Requirement: Rejection response body uses a sanitized error vocabulary`
