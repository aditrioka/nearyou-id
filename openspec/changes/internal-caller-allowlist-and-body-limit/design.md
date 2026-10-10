## Context

The 2026-10-03 architecture review (`dev/audits/2026-10-03-architecture-review/REPORT.md` § 2 / § 11, BARU-B1 + BARU-B2) found two trust-boundary gaps, both filed as `follow-up` issues and bundled here because both edit `Application.kt`:

- **#544.** `GoogleOidcTokenVerifier` (`:infra:oidc`) verifies signature + `aud` + `exp` and nothing about *who* minted the token. `deploy-staging.yml` deploys with `--allow-unauthenticated`, so Cloud Run's IAM invoker check — the second layer the `suspension-unban-worker` design (archive 2026-04-27, design.md:49) relied on — never runs. Any Google principal that can mint an ID token for `https://api-staging.nearyou.id` (any service account in any project — `--audiences` minting needs SA credentials or impersonation) passes the gate on all nine OIDC workers.
- **#545.** No `RequestBodyLimit` is installed. JSON routes `call.receive<T>()` the whole body before the code-point guards run; only six routes pre-check `contentLength()`, and chunked bodies bypass even those. Cloud Run's own ceiling is 32 MiB per HTTP/1 request on a 512 MiB instance at concurrency 80.

Staging (confirmed by the #535 session): until 2026-10-04 three hand-made Cloud Scheduler jobs (unban, privacy-flip, login-anomaly) ran as the legacy `unban-scheduler-staging` SA and the other six workers had no job. #535 (`dev/scripts/provision-schedulers.sh`, PR #579, merged) then created `scheduler-invoker-staging` and moved all nine jobs onto it; a forced run returned `200` from every worker.

## Goals / Non-Goals

**Goals:**
- Pin the `/internal/*` OIDC caller to an explicit allowlist of service-account emails; deny-by-default when unconfigured.
- Cap every request body at the transport layer, rejecting oversize bodies `413` before auth/handler/deserialisation, without regressing the three surfaces that legitimately exceed 64 KiB.
- Keep local dev boot, the verify-loop harness, and every existing test harness working.

**Non-Goals:**
- Removing `--allow-unauthenticated` / adding an ingress or IAM-invoker layer (BARU-1 / BARU-1b — separate items; this change is defence in depth that stays valuable after them).
- Issuer (`iss`) pinning — the JWKS is Google's ID-token key set, so a valid signature already implies a Google-issued token; not part of #544's scope.
- Per-worker allowlists (one allowlist for the whole OIDC subtree — every worker is invoked by the same scheduler SA).
- Rewriting the existing per-route `contentLength()` guards (the 4 KiB ones stay tighter; the RevenueCat/CSAM 64 KiB ones now equal the default and stay as belt-and-braces).
- Provisioning Cloud Scheduler jobs / SAs (#535).

## Decisions

### D1 — Enforce the allowlist inside `GoogleOidcTokenVerifier`, not in the plugin
The verifier gains an `allowedPrincipals: Set<String>` constructor parameter and throws a new `OidcVerificationException.PrincipalNotAllowed` after the signature/aud/exp checks pass; `InternalEndpointAuth` maps it to `403 {"error":"principal_not_allowed"}`.
- *Why:* one construction site in `Application.kt` vs. one `InternalEndpointAuthConfig` per `install(InternalEndpointAuth)` site (several route blocks) — a forgotten config would silently deny (fail-closed, safe, but noisy). Claims parsing (`email`, `email_verified`) stays in `:infra:oidc` next to the Auth0 `java-jwt` types (vendor-SDK boundary, docs/11 §3.1); the plugin stays a pure exception→status mapper. Matches the issue's suggested seam.
- *Alternative rejected:* `VerifiedClaims` carries `email` and the plugin checks it — spreads the authorization rule across two modules and every plugin install site.
- *Why not `withClaim(...)` on the Auth0 builder:* it raises a generic `InvalidClaimException` that would have to be told apart from other claim failures by message-sniffing (the existing `aud` branch already does that and it's brittle). An explicit post-verify check is clearer and maps 1:1 to the new exception.

### D2 — Pin `email` (+ `email_verified`), not `sub`
For a service-account ID token `sub` is the SA's opaque numeric unique id (it changes if the SA is deleted and recreated, and is unreadable in config); `email` is what Cloud Scheduler binds via `--oidc-service-account-email` and what operators recognise. A `<name>@<project>.iam.gserviceaccount.com` address can only be minted by the owning project, so pinning the email pins project + SA. `email_verified` must be the JSON boolean `true` (what Google ID tokens carry; a string `"true"` is rejected — no leniency on a security claim). Allowlist entries are lowercased when configured; the presented email is matched **exactly**, not case-folded — Google issues SA emails in lowercase, and folding the presented value would let Unicode case-folding (e.g. U+212A KELVIN SIGN → `k`) match an entry (security-review round 1). Verified 2026-10-04: Cloud Scheduler OIDC tokens carry `email` + `email_verified` (docs.cloud.google.com/scheduler/docs/http-target-auth; Cloud Run "Running services on a schedule").

### D3 — `403 principal_not_allowed`, not another `401`
The caller *is* authenticated (valid Google signature for our audience) but not authorized. A distinct status + code lets an operator tell "wrong SA on the Scheduler job" from "broken/expired token" in the job's attempt log. The body stays a single fixed `error` string; neither the presented email nor the allowlist is echoed or logged (existing no-claims rule).

### D4 — Empty allowlist: fail boot in deployed environments, deny-all elsewhere — never "allow all"
`resolveInternalOidcAllowedPrincipals(config)` (sibling of `resolveInternalOidcAudience`) returns the trimmed, lowercased set. When it is empty:
- any `ktor.environment` other than the local `test` / `dev` / `development` — staging, production, unset (= `production`, matching every other env read in `Application.kt`) or a typo like `prod` → **boot fails** naming the variable. An allowlist of local envs rather than a denylist of deployed ones, so a typo fails closed (implementation-review round). A misconfigured deploy becomes a rejected revision (the previous revision keeps serving with its allowlist) instead of a healthy-looking service whose nine workers silently 403 (security-review round 1).
- `test` / `dev` / `development` → boot continues with `WARN event=internal_oidc_allowlist_empty`, and the verifier rejects every caller 403. Local dev, the verify-loop harness and `HealthRoutesTest` (the one test that boots `module()`, pinned to `test`) keep working without the variable.
- *Why not fail-soft:* the repo's secret convention is "unresolved = NoOp/feature off" (`reference_backend_secret_resolution_convention`). For an **authorization** control the NoOp equivalent would be "allow everyone" — exactly the bug being fixed. The spec states the inversion explicitly.

### D5 — Plain Ktor config, not `secretKey()`
SA emails are non-secret (CLAUDE.md public-repository posture: "service-account emails are non-sensitive"). Same treatment as `INTERNAL_OIDC_AUDIENCE`: `application.conf` key `oidc.allowedPrincipals = ${?INTERNAL_OIDC_ALLOWED_PRINCIPALS}`, bound with `--set-env-vars`. The existing private `csvAudiences()` CSV-to-set helper in `Application.kt` is generalised (renamed `csvConfigSet`) and reused — no second parser.

### D6 — `deploy-staging.yml` switches `--set-env-vars` to the `^;^` delimiter
gcloud splits `--set-env-vars` on commas, so a comma-separated allowlist would be torn apart. `gcloud topic escaping`: a leading `^;^` makes `;` the pair delimiter for that flag only. `--update-env-vars` can't be combined with `--set-env-vars` (mutually exclusive group), so the whole flag value moves to the new delimiter. Staging value: `scheduler-invoker-staging@nearyou-staging.iam.gserviceaccount.com` only (least privilege — no job runs as the legacy SA any more). The delimiter switch is kept anyway: the variable is a list by contract, and the next environment or a second scheduler SA must not need a workflow-syntax change.

### D7 — One application-level `RequestBodyLimit` with a path-keyed resolver, not route-level installs
Verified against the 3.4.3 plugin source: `RequestBodyLimit` is a route-scoped plugin whose `onCall` hook checks `Content-Length` and whose `BeforeReceive` hook wraps the body channel with a counting limiter. Installed on the application, `onCall` runs in the `Plugins` phase — **before routing resolves the route** — so a route-level installation cannot relax an application-level `Content-Length` rejection for `/api/v1/images`. A single `bodyLimit { requestBodyLimitFor(it.request.path()) }` resolver is unambiguous, testable as a pure function, and is the one place a future override goes.
- Lives in `backend/ktor/.../common/RequestBodyLimits.kt` as `Application.installRequestBodyLimit()` (sibling of `installAppStatusPages()`), so the test installs the exact production wiring.
- The existing StatusPages `PayloadTooLargeException → 413 payload_too_large` mapping answers every `Content-Length` rejection and chunked raw-byte overflows.
- **Chunked JSON overflow (found in apply):** the limiter closes the channel with `PayloadTooLargeException`, but the kotlinx-JSON converter then yields nothing and ContentNegotiation throws `CannotTransformContentToTypeException` with the cause dropped — which fell through to the StatusPages catch-all as a **500 + ERROR log (a Sentry event per request)**. StatusPages now maps `CannotTransformContentToTypeException` to the existing `400 invalid_request` envelope (any un-transformable body is client input). Routes that wrap `call.receive` in their own `catch` answer their own 400. So the chunked contract is "cut off at the limit, 4xx, never 5xx" — not "always 413"; real clients (mobile Ktor client, Cloud Scheduler, webhooks) send `Content-Length`, so they always get the precise 413.
- **Request decompression off (security-review round 1):** `Compression`'s default `Mode.All` inflates `Content-Encoding` request bodies in the receive pipeline *after* the limiter counted the raw bytes — a 64 KiB gzip body could expand ~1000×. `installResponseCompression()` sets `Mode.CompressResponse`; no client sends compressed request bodies (mobile/shared grep; RevenueCat, Apple and Cloud Scheduler send plain bodies). Response compression (the docs/11 §3.3 egress reason) is unchanged.
- Verified 2026-10-04: Ktor has no default request-body limit (`RequestBodyLimit` defaults to `Long.MAX_VALUE`); the plugin is the canonical mechanism (api.ktor.io `ktor-server-body-limit`). Pinned to `ktor = 3.4.3` — no upgrade (KTOR-9546 blocks 3.5.x).

### D8 — Limit sizing (survey of every body-reading route)
| Path | Largest legitimate body | Limit |
|---|---|---|
| default (posts 280 chars, replies, chat 2000 chars, reports, appeals, auth/signup tokens, user-settings PATCH, RevenueCat + CSAM webhooks (already capped 64 KiB), Apple S2S JWS, admin forms) | a few KiB; webhook JWS/JSON well under 64 KiB | **64 KiB** |
| `/api/v1/images` | 5 MiB image (mobile compresses to ≤ 5 MiB) + multipart headers/boundaries | **5 MiB + 64 KiB** |
| `/admin/reserved-usernames/bulk` | 256 KB CSV text guard, form-urlencoded (`,`/newline expand to 3 bytes) | **1 MiB** |
| `/admin/feature-flags/wordlists/*` | full staged list (`entries`) + `import` text, each ≤ 10 000 × 100 chars | **4 MiB** |

Exact match for the first two, prefix match for the wordlist editor (`/{list}` and `/{list}/preview`). Sibling paths (`/admin/reserved-usernames`, `/api/v1/images-x`) get the default.

### Standards conformance (docs/11)
- **§3.1 layering / vendor boundary:** Auth0 `java-jwt` claim access stays in `:infra:oidc`; `:core:domain` gains only a sealed-class subtype; the Ktor plugin maps exceptions to statuses.
- **§3.3 plugin set (Pattern Registry):** adds `RequestBodyLimit` as the ONE canonical request-body cap. This is a new entry in the standard plugin set, so docs/11 §3.3 is amended in this PR (task 4.6). The per-route `contentLength()` guards remain as tighter, route-specific limits, not a competing pattern.
- **§3.5 testing:** kotest StringSpec, `testApplication`; DB-tagged route tests keep their existing pools.

### Cross-layer scope (docs/12)
Backend only. `/internal/*` workers have no mobile or admin client surface; Cloud Scheduler (ops, #535) is the only caller. The body cap changes no wire contract for compliant clients — the mobile image picker already compresses to ≤ 5 MiB, and every other client body is far below 64 KiB. An image upload above 5 MiB + 64 KiB now gets `413 payload_too_large` (not the route's `image_too_large`); the mobile `ImageUploadRepository` maps unknown codes to the retryable `Unavailable` outcome with a diagnostic, and the compressor makes that body unreachable from the app — so no mobile change. No layer is deferred.

## Risks / Trade-offs

- **[Cloud Scheduler tokens lack `email`/`email_verified` → every worker 403s on staging]** → Google documents both claims; the pre-merge branch-deploy smoke force-runs a live job (`gcloud scheduler jobs run nearyou-unban-worker-staging`) and checks the request log for `200` before the PR is marked ready.
- **[A future environment (prod) deploys without the allowlist → workers inert]** → fail-closed by design; loud via the boot WARN + red Scheduler attempts; docs/10 setup checklist + docs/07 runbook carry the env var as a required step.
- **[An unlisted route legitimately needs > 64 KiB]** → body-reading route survey in D8; a miss surfaces as a visible `413` (not silent corruption) and is fixed by one line in the resolver.
- **[#535's SA name drifts]** → coordinated with the #535 session (confirmed `scheduler-invoker-staging`, merged in #579); the name is stated in both PR bodies.
- **[Branch deploy is ephemeral — a `main` deploy clobbers it]** → run the smoke immediately after the branch deploy; keep it short.
- **[Mixed-case allowlist entry]** → entries lowercased at parse; the presented email is matched exactly (Google issues it lowercase).
- **[A crafted chunked oversize admin form post]** → `AdminCsrfGate` swallows the receive failure and answers its CSRF 403 (with its audit row). Browsers send `Content-Length`, so real admin traffic gets the 413 pre-check; a crafted chunked post needs an authenticated admin session. Accepted; not worth a gate change here.
- **[A future client sends compressed request bodies]** → it would get its raw bytes (and a 400 on parse); re-enable decompression only together with a post-inflation cap.

## Migration Plan

1. #535's script already runs all nine staging jobs as `scheduler-invoker-staging` (2026-10-04), so allowlisting that one SA keeps every job green; the branch-deploy smoke force-runs a live job to prove it before merge.
2. Deploy (branch deploy pre-merge for the smoke; `main` deploy on merge) — no migration, no data change.
3. Retiring the legacy `unban-scheduler-staging` SA (its `run.invoker` binding + the SA) is #535's runbook step (`dev/docs/cloud-scheduler.md`); it was never allowlisted here.
4. **Rollback:** revert the PR. Emergency widening without a revert: `gcloud run services update --update-env-vars` on the allowlist (an empty value fails boot on staging/production, so the previous revision keeps serving).

## Open Questions

None — SA name confirmed with the #535 session; delimiter and plugin behaviour verified against sources.