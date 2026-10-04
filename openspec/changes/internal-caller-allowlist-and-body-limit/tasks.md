## 1. OIDC caller-principal allowlist (#544, design D1–D5)

- [ ] 1.1 `core/domain/.../oidc/OidcTokenVerifier.kt`: add `OidcVerificationException.PrincipalNotAllowed` ("principal_not_allowed") + KDoc (valid token, caller not allowlisted → 403)
- [ ] 1.2 `infra/oidc/.../GoogleOidcTokenVerifier.kt`: new `allowedPrincipals: Set<String>` constructor param (lowercased at construction); after signature/aud/exp pass, require `email` present + `email_verified` true (boolean or `"true"`) + lowercased email ∈ set, else throw `PrincipalNotAllowed`; KDoc failure mapping updated
- [ ] 1.3 `backend/ktor/.../internal/InternalEndpointAuth.kt`: catch `PrincipalNotAllowed` → `403 {"error":"principal_not_allowed"}` + `logRejection("principal_not_allowed", …)` (no claims logged); subject/span attribute never set on that path
- [ ] 1.4 `Application.kt`: `resolveInternalOidcAllowedPrincipals(config)` (sibling of `resolveInternalOidcAudience`; trimmed, lowercased, blanks dropped; WARN `event=internal_oidc_allowlist_empty` when empty, boot continues); generalise private `csvAudiences` → `csvConfigSet` and reuse; pass the set into the single `GoogleOidcTokenVerifier` construction
- [ ] 1.5 `application.conf`: `oidc.allowedPrincipals = ${?INTERNAL_OIDC_ALLOWED_PRINCIPALS}`

## 2. Global request body limit (#545, design D7–D8)

- [ ] 2.1 `gradle/libs.versions.toml`: `ktor-serverBodyLimit = io.ktor:ktor-server-body-limit-jvm` on the shared `ktor` (3.4.3) ref; `backend/ktor/build.gradle.kts` adds it
- [ ] 2.2 New `backend/ktor/.../common/RequestBodyLimits.kt`: `requestBodyLimitFor(path)` (64 KiB default; `/api/v1/images` = `MAX_IMAGE_BYTES` + 64 KiB; `/admin/reserved-usernames/bulk` = 1 MiB; `/admin/feature-flags/wordlists/` prefix = 4 MiB) + `Application.installRequestBodyLimit()`
- [ ] 2.3 `Application.kt`: call `installRequestBodyLimit()` next to `installAppStatusPages()`
- [ ] 2.4 `AppStatusPages.kt`: keep the `PayloadTooLargeException → 413 payload_too_large` mapping; if the chunked-overflow test shows the receive pipeline wrapping it in `BadRequestException`, unwrap that cause to `413` too

## 3. Tests (one per spec'd scenario)

- [ ] 3.1 Mechanical: every test token minter that feeds a real `GoogleOidcTokenVerifier` adds `email = TEST_OIDC_PRINCIPAL` + `email_verified = true`; every test `GoogleOidcTokenVerifier(...)` passes `allowedPrincipals = setOf(TEST_OIDC_PRINCIPAL)` (one shared test constant); existing suites stay green
- [ ] 3.2 `infra/oidc` `GoogleOidcTokenVerifierTest`: allowlisted → claims returned; foreign email → `PrincipalNotAllowed`; no `email` → `PrincipalNotAllowed`; `email_verified=false` → `PrincipalNotAllowed`; mixed-case email → admitted; empty allowlist + otherwise-valid token → `PrincipalNotAllowed`; foreign email + wrong aud → `AudienceMismatch` (ordering)
- [ ] 3.3 `UnbanWorkerRouteTest`: foreign SA with correct aud → `403` exact body `{"error":"principal_not_allowed"}`, no unban ran (seeded eligible user stays banned), body does not contain the presented or allowlisted email; empty-allowlist verifier → `403`; no-email token → `403`; unverified → `403`; foreign SA + wrong aud → `401 audience_mismatch`; existing JWKS-rotation 200 path uses the allowlisted principal
- [ ] 3.4 `InternalEndpointSpanAttributeTest`: a `403` principal rejection produces no `service.account.id` on the server span
- [ ] 3.5 New `InternalOidcAllowedPrincipalsConfigTest`: unset → empty + WARN `event=internal_oidc_allowlist_empty`; `" , ,"` → empty + WARN; mixed-case comma list → trimmed lowercased set, no WARN
- [ ] 3.6 New `RequestBodyLimitTest` (`testApplication` with the production `installAppStatusPages()` + `installRequestBodyLimit()` + an always-rejecting `authenticate` around the probe route): 1 MiB `Content-Length` to `/api/v1/posts` → `413 payload_too_large`, not `401`, handler/deserialiser never ran; 1 MiB chunked → `413`, deserialisation never completed; exactly 64 KiB → handler ran; `/api/v1/images` 5 MiB + envelope → handler ran, `> 5 MiB + 64 KiB` → `413`; `/admin/reserved-usernames/bulk` 512 KiB and `/admin/feature-flags/wordlists/profanity` 2 MiB → handler ran; resolver table: sibling paths (`/api/v1/images-x`, `/admin/reserved-usernames`, `/admin/feature-flags`) → 64 KiB

## 4. Deploy wiring + docs

- [ ] 4.1 `.github/workflows/deploy-staging.yml`: `--set-env-vars` switches to the `^;^` delimiter and adds `INTERNAL_OIDC_ALLOWED_PRINCIPALS=<scheduler-invoker-staging SA>,<unban-scheduler-staging SA>` + comment block (why plain env, why both SAs, retire step) — if the workflow-edit guard blocks it, report and hand the exact diff to the operator
- [ ] 4.2 `dev/.env.example`: `INTERNAL_OIDC_ALLOWED_PRINCIPALS=` with a comment (empty = every `/internal/*` OIDC call 403s; set to the SA you mint test tokens for)
- [ ] 4.3 `docs/07-Operations.md` § Internal worker schedules: caller allowlist paragraph (env var, staging SAs, fail-closed, `403 principal_not_allowed` triage, retire `unban-scheduler-staging` after #535's script moves the jobs — points at #535's `dev/docs/cloud-scheduler.md` runbook)
- [ ] 4.4 `docs/06-Security-Privacy.md` § Internal Endpoint Security: implementation bullets gain the email allowlist; correct the "Defense in depth" line (the service is `--allow-unauthenticated`, so the IAM-invoker layer is absent today — the token-level principal pin is what stands in for it)
- [ ] 4.5 `docs/10-Setup-Checklist.md` § 2.3 staging Cloud Run notes: allowlist env var + per-environment requirement (prod must set its own scheduler SA)
- [ ] 4.6 `docs/11-Engineering-Standards.md` §3.3: standard plugin set gains `RequestBodyLimit` — the one canonical request-body cap (global 64 KiB, overrides only in `requestBodyLimitFor`), per-route `contentLength()` guards remain as tighter limits (Pattern Registry)
- [ ] 4.7 `docs/09-Versions.md`: row for `io.ktor:ktor-server-body-limit-jvm` (shared `ktor` ref, 2026-10-04, dated check)

## 5. Verification & lifecycle

- [ ] 5.1 Gate (docs/13): `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` + `:infra:oidc:test` (throwaway Postgres if the dev DB is dirty)
- [ ] 5.2 Local verify (verify-loop §A): boot the backend with an empty allowlist → WARN logged, `/internal/unban-worker` bare → `401`; `POST /api/v1/posts` with a 1 MiB body → `413 payload_too_large`; small body → normal `401` (auth)
- [ ] 5.3 Staging branch deploy + smoke (project.md § Staging deploy timing, docs/11 §5 DoD #4): `gcloud scheduler jobs run nearyou-unban-worker-staging` (live job, allowlisted SA) → request log `200`; foreign identity token with the right audience → `403 principal_not_allowed`; 1 MiB `POST /api/v1/posts` → `413`; record evidence in the PR body
- [ ] 5.4 PR title/body current at each phase boundary; body carries `Closes #544` and `Closes #545` on separate lines and names the scheduler SA explicitly (coordination with #535)
- [ ] 5.5 Archive via `/opsx:archive`; hand-edit the now-stale `## Purpose` of `internal-endpoint-auth` (401-only wording → 401 + 403 allowlist) and `backend-bootstrap` (adds the request-body limit), since deltas cannot modify Purpose
