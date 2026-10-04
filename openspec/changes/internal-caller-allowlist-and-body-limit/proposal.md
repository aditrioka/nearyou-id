## Why

Two trust-boundary gaps found by the 2026-10-03 architecture review (BARU-B1 / BARU-B2), both Bucket 1 (free, staging, before the feature-complete milestone):

- **#544 — any Google service account can call the nine `/internal/*` workers.** `GoogleOidcTokenVerifier` checks only signature + `aud` + `exp`. The Cloud Run service is `--allow-unauthenticated`, so the GCP IAM-invoker layer that the `suspension-unban-worker` design assumed ("OIDC token + GCP IAM Cloud-Scheduler-only-invoke") does not exist: any SA in any project can mint `aud=https://api-staging.nearyou.id` and trigger unban, hard-delete, data-export, cleanup, anomaly, orphan-image, referral, CSAM-purge and privacy-flip runs. The workers are idempotent, so the impact is early execution + DB load, not data loss — but the caller identity is unpinned.
- **#545 — no request-body cap.** No `RequestBodyLimit` is installed; 13+ route files call `call.receive<…>()` and only 6 guard `contentLength()` by hand. An unauthenticated large JSON POST is fully buffered before any code-point guard runs — memory pressure on a 512 MiB instance at concurrency 80 (Cloud Run itself allows 32 MiB HTTP/1 bodies).

Both touch `Application.kt` and are one trust-boundary theme, so they ship as one bundle.

## What Changes

- **Caller-identity allowlist on `/internal/*` (#544).** After signature/audience/expiry pass, the OIDC verifier also requires the token's `email` claim (with `email_verified = true`) to be in an allowlist read from `INTERNAL_OIDC_ALLOWED_PRINCIPALS` (comma-separated SA emails, Ktor config `oidc.allowedPrincipals`). A valid token from a non-allowlisted principal is rejected **`403 {"error":"principal_not_allowed"}`** (authenticated but not authorized — distinct from the existing 401 vocabulary).
  - **Empty / unset allowlist = fail-CLOSED**: boot still succeeds (local dev + test harnesses keep working), a WARN is logged at boot, and every OIDC-gated `/internal/*` request is 403. This is deliberately the opposite of the repo's fail-soft "blank secret = NoOp" convention — for an authorization control, "unconfigured" must mean "deny".
  - Staging allowlist (wired in `deploy-staging.yml`): `scheduler-invoker-staging@nearyou-staging.iam.gserviceaccount.com` (the SA the #535 `provision-schedulers.sh` creates for all 9 jobs) + `unban-scheduler-staging@nearyou-staging.iam.gserviceaccount.com` (the SA the 3 live staging jobs use today; retired after #535's script migrates them).
- **Global request-body cap (#545).** `RequestBodyLimit` (Ktor 3.4.3 `ktor-server-body-limit`) installed application-wide: **64 KiB default**, path overrides for the three surfaces that legitimately exceed it — `POST /api/v1/images` (5 MiB image + 64 KiB multipart envelope), `POST /admin/reserved-usernames/bulk` (1 MiB, so the route's own 256 KB → 400 guard band stays authoritative), `POST /admin/feature-flags/wordlists/*` (4 MiB — the editor posts the full staged list, capped at 10 000 × 100-char entries). Oversize → `413 {"error":{"code":"payload_too_large",…}}` (the existing StatusPages mapping) **before** auth, routing handlers or deserialisation when `Content-Length` is declared, and mid-stream for chunked bodies. Existing per-route `contentLength()` guards stay as tighter belt-and-braces.
- **Docs:** `docs/11` §3.3 standard plugin set gains `RequestBodyLimit` (Pattern Registry — one canonical body-size cap); `docs/07` § Internal worker schedules + `docs/10` setup checklist document the allowlist env var, the SA, and the retire step for `unban-scheduler-staging`; `dev/.env.example` gains the variable.

No migration. No mobile or admin UI change. No new secret slot (SA emails are non-secret config per the public-repo posture).

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `internal-endpoint-auth`: the OIDC gate gains a fourth check (caller principal allowlist, 403 on mismatch); new requirement for the allowlist config with fail-closed empty behaviour; the sanitized rejection vocabulary gains `principal_not_allowed` (403).
- `backend-bootstrap`: new requirement — a global `RequestBodyLimit` (64 KiB default + path overrides) rejects oversize bodies 413 before handler/deserialisation.

## Impact

- **Code:** `infra/oidc/.../GoogleOidcTokenVerifier.kt` (allowlist check), `core/domain/.../OidcTokenVerifier.kt` (new `PrincipalNotAllowed` exception), `backend/ktor/.../internal/InternalEndpointAuth.kt` (403 mapping), `backend/ktor/.../Application.kt` (allowlist config + `RequestBodyLimit` install), new `backend/ktor/.../common/RequestBodyLimits.kt`, `application.conf`.
- **Dependencies:** `gradle/libs.versions.toml` adds `io.ktor:ktor-server-body-limit-jvm` at the pinned `ktor = 3.4.3` (no Ktor upgrade — 3.5.x is blocked by KTOR-9546).
- **Deploy:** `.github/workflows/deploy-staging.yml` adds `INTERNAL_OIDC_ALLOWED_PRINCIPALS` to `--set-env-vars` (switches the flag to the `^;^` gcloud delimiter so the comma-separated list survives).
- **Tests:** every OIDC route test's minted token gains `email`/`email_verified`; new foreign-SA → 403, empty-allowlist → 403, oversize → 413 (Content-Length + chunked) tests.
- **Operational coupling:** #535 (`provision-schedulers.sh`) must keep using `scheduler-invoker-staging`; a future prod deploy must set the prod allowlist or every worker 403s (visible in Cloud Scheduler job status — fail-closed by design).
- **Cross-layer (docs/12):** backend-only. `/internal/*` has no mobile/admin client; the body cap is transport hardening with no wire-contract change for compliant clients (mobile compresses images to ≤ 5 MiB before upload).
