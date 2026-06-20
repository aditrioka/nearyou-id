## ADDED Requirements

### Requirement: `/internal/csam-webhook` provides its own non-OIDC auth on two paths

The `/internal/csam-webhook` route SHALL opt out of the `InternalEndpointAuth` OIDC plugin (per the existing "Plugin is mounted on the `/internal/*` subtree, with vendor-webhook opt-out" requirement) and provide its own authentication on **two** supported invocation paths, both non-OIDC:

1. **Admin-internal (MVP)** — the request is authenticated by the admin's scoped session plus a session-bound CSRF-style token, reusing the existing `/admin/*` `AdminCsrfGate` + admin-session seam (the `__Host-admin_session` cookie + `csrf_token_hash` contract — NOT a hand-rolled second check), AND is **role-gated to `owner`/`admin`**: a read-only admin role MUST be rejected even with a valid session + CSRF (parity with every other destructive admin action). A request lacking valid admin-session + CSRF credentials, OR carrying a non-privileged role, OR replaying a CSRF token bound to a different admin session, MUST be rejected.
2. **Cloudflare Worker (Phase 2+)** — the request carries a `Bearer` token **and** an `HMAC-SHA256` body signature keyed by `secretKey(env, "cf-worker-csam-secret")`; the handler MUST verify **both** before processing, and MUST rate-limit this path to 100 requests/hour per client IP (replay-amplification guard).

A bare valid Google OIDC token (with neither admin-session+CSRF nor Bearer+HMAC) MUST NOT authorize the route — the OIDC plugin does not run on it, so an OIDC token alone is rejected by the route's own auth.

#### Scenario: OIDC token alone does not authorize the CSAM webhook
- **WHEN** `POST /internal/csam-webhook` is reached with a valid Google OIDC bearer token but no admin-session+CSRF and no CF-Worker Bearer+HMAC headers
- **THEN** the request is rejected by the route's own auth (it is NOT admitted by the OIDC plugin, which does not apply to this route)

#### Scenario: CF-Worker path requires both Bearer and HMAC
- **WHEN** `POST /internal/csam-webhook` is reached on the CF-Worker path with a valid `Bearer` token but a missing or invalid `HMAC-SHA256` body signature
- **THEN** the request is rejected (both factors are mandatory; one alone does not authorize)

#### Scenario: CF-Worker path is rate-limited per IP
- **WHEN** the CF-Worker path receives more than 100 requests within one hour from the same client IP
- **THEN** requests beyond the limit are rejected (replay-amplification guard)

#### Scenario: Admin-internal path requires admin session and CSRF
- **WHEN** `POST /internal/csam-webhook` is reached on the admin-internal path without a valid admin session or without the matching CSRF token
- **THEN** the request is rejected before any takedown logic runs

#### Scenario: Admin-internal path rejects a read-only admin role
- **WHEN** `POST /internal/csam-webhook` is reached on the admin-internal path with a valid admin session AND matching CSRF token BUT the session's role is read-only (not `owner`/`admin`)
- **THEN** the request is rejected by the role gate before any takedown logic runs

#### Scenario: Admin-internal CSRF token replayed in a different session is rejected
- **WHEN** a valid CSRF token captured from admin session S1 is presented on a `POST /internal/csam-webhook` request bound to a different admin session S2
- **THEN** the request is rejected (the CSRF token must match the invoking session's `csrf_token_hash`; a cross-session replay does not validate)

### Requirement: `/internal/csam-archive-purge` inherits OIDC gating

The `/internal/csam-archive-purge` worker route is an ordinary Cloud-Scheduler-invoked endpoint and SHALL inherit the default `InternalEndpointAuth` OIDC gating (it is NOT a vendor-webhook opt-out). A request without a valid Google OIDC bearer token MUST be rejected `401`.

#### Scenario: Purge worker requires a valid OIDC token
- **WHEN** `POST /internal/csam-archive-purge` is reached without a valid Google OIDC bearer token
- **THEN** the response status is `401 Unauthorized` (the route inherits the OIDC plugin like every other scheduled worker)
