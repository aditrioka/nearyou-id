## Context

`:backend:ktor` logs through SLF4J → logback → STDOUT (Cloud Logging). The single StatusPages `Throwable` handler is the only place a 500's stack trace is logged (`event=unhandled_exception method=… path=…`, ERROR). Operator-actionable failures elsewhere (moderation-list cascade exhausted, Layer-3 threshold misconfig, worker failures) are ERROR/WARN lines whose KDocs already assume a "logback → Sentry appender" pipeline. OpenTelemetry (`:infra:otel`) exports traces to Grafana Cloud; nothing aggregates or alerts on errors.

The mobile half shipped in `mobile-sentry-crash-reporting` (PR #299): `:infra:sentry` is a **mobile-only KMP module** (androidTarget + iOS, Android-gated in `settings.gradle.kts`), so the backend cannot depend on it. Issue #316 tracks the backend half.

## Goals / Non-Goals

**Goals:**
- Every backend 500 and every `ERROR` log becomes a Sentry event, with zero per-call-site changes.
- Same PII posture as mobile (no IP/host, scrubbed payloads) — stricter on identity (no user at all).
- Link each event to its request trail (`call_id`) and deploy (`release`, `environment`).
- Safe by default: no DSN → nothing happens; bad DSN → startup continues.

**Non-Goals:**
- Sentry performance tracing / profiling (OTel owns traces).
- Sentry Logs product (`options.logs`) — Cloud Logging stays the log store.
- Per-user enrichment (see D4).
- Alert rules / dashboard configuration in Sentry (operator, in the Sentry UI).
- Production Secret Manager slot + prod deploy workflow (no prod deploy workflow exists yet; prod cutover owns it).

## Decisions

**D1 — New JVM module `:infra:sentry-jvm`, not a JVM target on `:infra:sentry`.** `:infra:sentry` applies the Android library plugin and is excluded from the JDK-only Docker builder; adding a JVM target would drag it back into the backend build. The `:infra:revenuecat` (mobile KMP) / `:infra:revenuecat-api` (backend JVM) split is the precedent. The module is an unconditional `include` + `Dockerfile` COPY (a real backend dependency — guarded by `check-dockerfile-module-copies.sh`).

**D2 — Capture via the official logback `SentryAppender` attached programmatically, not per-call-site `captureException`.** The codebase already treats `log.error(...)` as the Sentry contract (KDocs in `Layer3ConfigLoader`, `ModerationListLoader`, `TextModerator`). Attaching the appender to the root logger at `minimumEventLevel = ERROR` turns every existing ERROR line — including the StatusPages 500 handler — into an event. Programmatic attach (not `logback.xml`) keeps the DSN on the project's secret-resolution path and keeps tests/dev silent when no DSN is set. Alternative rejected: a Ktor plugin calling `Sentry.captureException` — duplicates the StatusPages seam and misses non-request ERRORs. `Sentry.init` runs first with default `InitPriority` (MEDIUM); the appender's own `start()` re-init at `LOWEST` is then skipped by the SDK's `InitUtil.shouldInit` priority check, so our options win.

**D3 — Log-derived breadcrumbs OFF (`minimumBreadcrumbLevel = OFF`).** On the JVM the SDK keeps scopes in thread-locals; Ktor coroutines hop across a shared Netty/IO pool, so breadcrumbs from unrelated requests (other users) would accumulate on a thread and ride along on the next event — misleading and a cross-user privacy leak. Correlation instead uses the `call_id` tag → Cloud Logging, which has the full, correctly-scoped request trail. A per-call scope fork (`SentryContext` coroutine element) would fix isolation but is new plumbing with no current consumer.

**D4 — No user identity on backend events.** The mobile side sets the opaque JWT `sub`, gated on crash consent. Backend consent-aware enrichment would need the user's `analytics_consent.crash` per request (a DB read the JWT doesn't carry), and `beforeSend` runs on the logging thread (no JDBC there — docs/11 §3.2). Attaching no user satisfies docs/06 ("backend skips error enrichment with user_id" on decline) for everyone; the `call_id` tag leads an operator to the user via first-party Cloud Logging. `beforeSend` also nulls `event.user` defensively.

**D5 — `beforeSend` scrubber mirrors mobile `PiiScrubber` (pattern reuse, second site).** Same name/shape (`internal object PiiScrubber { fun scrub(text) }`), superset patterns: mobile's coordinate pair / bearer / JWT, plus email, IPv4, IPv6 (≥4 hex groups, so `HH:MM:SS` isn't hit), and Postgres `Key (col)=(value)` (pgJDBC includes server `Detail:` in exception messages — e.g. a unique-violation echoing an email). Applied to `message.formatted` and every `exceptions[].value`. Extracting one shared scrubber would need a new KMP module with jvm+android+ios targets for ~10 lines; docs/11 §4 rule-of-three says extract at the 3rd site, so the KDoc cross-links the two copies instead.

**D6 — Tags and options.** `environment` = `ktor.environment` (same value OTel uses); `release` = `K_REVISION` (Cloud Run sets it per revision; the revision maps 1:1 to the `GITHUB_SHA::12` image in the Cloud Run console) — zero deploy-workflow plumbing. `addContextTag("call_id")` promotes the MDC value to a tag. `addInAppInclude("id.nearyou")` for grouping. `sendDefaultPii = false`, `attachServerName = false`. `tracesSampleRate` left unset (tracing off). Default uncaught-exception handler + shutdown-hook flush stay on (no `ApplicationStopped` subscription needed).

**D7 — DSN in Secret Manager (`sentry-backend-dsn`), resolved by the unprefixed name.** A DSN is a write-only ingest key (the mobile runbook calls it non-sensitive), but every backend runtime config value already flows through Secret Manager with `--set-secrets`, and `EnvVarSecretResolver.resolve("sentry-backend-dsn")` → `SENTRY_BACKEND_DSN` keeps one convention (resolving the `secretKey()`-prefixed slot is the #381 silent-NoOp bug). The name deliberately avoids `SENTRY_DSN`, which the SDK reads implicitly via external configuration. The mapping goes into `deploy-staging.yml`'s *pending* list: mapping a nonexistent slot fails the deploy.

**D8 — Vendor-free test fixture `SentryEventRecorder`.** `:backend:ktor` tests may not import `io.sentry` (the leakage scan covers `backend/ktor/src`, test sources included). `:infra:sentry-jvm` publishes a `java-test-fixtures` recorder (the `:infra:otel` `SpanRecorder` precedent) that starts the bootstrap with a recording transport and exposes captured events as a plain data class. The public `SentryBootstrap.start` signature stays vendor-free; the transport hook is `internal`.

Version: `io.sentry:sentry` + `io.sentry:sentry-logback` 8.58.0 (one shared `version.ref`, no BOM) — verified 2026-09-25 against Maven Central `maven-metadata.xml` (latest release, published 2026-09-23); `sentry-logback` 8.58.0 depends only on `io.sentry:sentry` at the same version (no mixed-version risk, which the SDK hard-fails on). Compatible with our logback 1.5.32.

### Standards conformance

- **Backend layering (docs/11 §3.1)**: vendor SDK only inside `:infra:*`; `:backend:ktor` touches one vendor-free call in `Application.module()`. Mirrors `OtelBootstrap.start(env, …)`'s shape (exception-safe, idempotent, `event=…_disabled reason=…` log vocabulary).
- **HTTP/plugins (docs/11 §3.3)**: no new plugin; reuses the single StatusPages envelope and the CallId `call_id` MDC.
- **JDBC discipline (§3.2)**: nothing on the logging path touches the DB (D4).
- **Reuse-first (§4)**: second-site mirror of `PiiScrubber`, cross-linked (D5).
- **Pattern Registry**: no new pattern for a registered concern; no docs/11 amendment.
- **Cross-layer scope (docs/12)**: backend-only, operator-facing (the Sentry dashboard is the surface). No mobile/admin wire contract changes; no deferred layers. The admin dashboard deliberately does not embed Sentry (`templates/admin/index.peb`).

## Risks / Trade-offs

- [Regex scrubbing is best-effort] → it's the backstop behind "no user, no request data, no breadcrumbs"; event bodies are only the log message + exception chain. Compressed IPv6 (`::1`) is not matched — known ceiling, ops IPs only.
- [An ERROR-log storm burns the free-tier quota] → Sentry groups by fingerprint; the free tier caps and drops, it doesn't bill. Rate-limit in Sentry UI if needed.
- [Thread-local scope bleed if someone later calls `Sentry.setTag`/`setUser` in request code] → the leakage scan keeps `io.sentry` out of `:backend:ktor`, so only `:infra:sentry-jvm` can touch scopes.
- [`K_REVISION` is not a git sha] → the Cloud Run console maps revision → image tag; switch to a `SENTRY_RELEASE` env var if release-health or suspect-commits are wanted.

## Migration Plan

1. Merge with no DSN provisioned → staging logs `event=sentry_disabled reason=dsn_missing`; behavior unchanged.
2. Operator: create the Sentry backend project (same org as mobile), store its DSN in `staging-sentry-backend-dsn`, grant the Cloud Run SA `secretAccessor`, move `SENTRY_BACKEND_DSN=staging-sentry-backend-dsn:latest` from the pending comment into the live `--set-secrets` list, redeploy → `event=sentry_enabled`.
3. Rollback: remove the mapping (or empty the secret) → no-op path. No data migration.

## Open Questions

None blocking. Consent-aware per-user enrichment (D4) can be revisited if operators find `call_id` → Cloud Logging too slow in practice.
