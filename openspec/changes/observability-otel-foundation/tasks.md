## 1. Pre-implementation baseline

- [ ] 1.1 Verify Open Decision #12 vendor default — re-read `docs/08-Roadmap-Risk.md:586-588` to confirm Grafana Cloud Tempo remains the recommended default at task-execution time. If the user has flipped the decision since proposal review, reopen the design § D3 and amend the bootstrap config-block scope before implementation.
- [ ] 1.2 Capture baseline cold-start p99 — run `./gradlew :backend:ktor:run` (or the production-shape jar invocation) on a representative machine, measure `Application.module()` startup wall-clock time across 5 cold starts. Record the median + p99 in the PR description (without OTel SDK init) for later regression comparison.
- [ ] 1.3 Confirm `opentelemetry-instrumentation-ktor` library availability for Ktor 3.4.x — check Maven Central. If unavailable, plan to fall back to the manual `Ktor` interceptor implementation described in design § Risks (first row).
- [ ] 1.4 Re-read `docs/04-Architecture.md:386-399`, `docs/05-Implementation.md:2042`, and `docs/01-Business.md:381,455` to confirm the canonical instrumentation contract, sampling profile, and cost projection match the spec before scaffolding code.

## 2. Provision secrets + Grafana Cloud project

- [ ] 2.1 Create a Grafana Cloud free-tier project for staging + production (one project, two stacks: `nearyou-staging`, `nearyou-prod`) per `docs/10-Setup-Checklist.md:220-229`.
- [ ] 2.2 Mint the OTLP/HTTP token for each stack (Read+Write trace permissions only — no metric/log scope at this change). Store via the existing GCP Secret Manager workflow:
    - `staging-otel-grafana-otlp-endpoint` (e.g., `https://tempo-prod-XX-us-central-0.grafana.net/tempo`).
    - `staging-otel-grafana-otlp-token` (bearer token).
    - `otel-grafana-otlp-endpoint` (production stack URL).
    - `otel-grafana-otlp-token` (production bearer token).
- [ ] 2.3 Confirm zero CI / dev access leakage — only the staging + production Cloud Run service accounts have IAM grants to read these secrets. Document the SA grant in the PR description.
- [ ] 2.4 Update `docs/10-Setup-Checklist.md:220-229` to tick the Grafana Cloud setup checkboxes once the secrets are provisioned (not in this code-change, but a follow-up doc tick-off — note in PR description).

## 3. Pin OTel libraries + log decisions

- [ ] 3.1 Add to `gradle/libs.versions.toml`:
    - `opentelemetry-bom` (use the `io.opentelemetry:opentelemetry-bom` dependency to manage SDK + API + context versions consistently).
    - `opentelemetry-sdk` (resolved by BOM).
    - `opentelemetry-exporter-otlp` (resolved by BOM).
    - `opentelemetry-instrumentation-jdbc` (separate version line — the instrumentation packages ship from the `opentelemetry-java-instrumentation` repo, not the SDK BOM).
    - `opentelemetry-instrumentation-lettuce-5_1`.
    - `opentelemetry-instrumentation-ktor` if available, OR mark it as "manual" in the version table with an explanatory comment.
- [ ] 3.2 Add a row to `docs/09-Versions.md` Version Pinning Decisions Log per the existing log shape (provenance: this change, version-as-of-pin: latest stable on Maven Central at pin date, rationale: recommended Java OTel pipeline + Grafana Cloud's recommended client, next review: 2026-Q3 — six months out, matches the existing log cadence).
- [ ] 3.3 Run `./gradlew :backend:ktor:dependencies | grep opentelemetry` after the pin to verify resolved versions match the table; record the resolved versions in the PR description.

## 4. Scaffold `:infra:otel` module

- [ ] 4.1 Add `:infra:otel` to `settings.gradle.kts` (alongside `:infra:fcm`, `:infra:oidc`, etc.).
- [ ] 4.2 Create `infra/otel/build.gradle.kts` mirroring the convention of `infra/redis/build.gradle.kts` (Kotlin JVM target, Detekt + ktlint plugins, dependency on `:core:domain` for `KtorEnv`, dependency on the OTel BOM + SDK + exporter pins from step 3).
- [ ] 4.3 Create `infra/otel/src/main/kotlin/id/nearyou/infra/otel/OtelConfig.kt` — value class wrapping `endpoint`, `token`, `samplingProfile`, `samplingRatio` (the production base ratio constant); read by `OtelBootstrap`.
- [ ] 4.4 Create `infra/otel/src/main/kotlin/id/nearyou/infra/otel/SamplingProfile.kt` — enum `Dev` / `Staging` / `Production`, each backed by a factory function returning the configured `Sampler` per spec § "Sampling profile per environment matches the canonical profile".
- [ ] 4.5 Create `infra/otel/src/main/kotlin/id/nearyou/infra/otel/UserIdHasher.kt` — `hash(uuid: UUID): String` returning first 16 hex chars of `SHA-256(uuid.bytes)`, deterministic, exposed as a public top-level function. Single-source-of-truth for the project's hashed user.id pattern.
- [ ] 4.6 Create `infra/otel/src/main/kotlin/id/nearyou/infra/otel/WithSpan.kt` — `withSpan(name: String, attributes: Map<String, Any> = emptyMap(), block: () -> T): T` per spec § "`withSpan` helper is the canonical manual-span surface".
- [ ] 4.7 Create `infra/otel/src/main/kotlin/id/nearyou/infra/otel/ErrorSlowForceKeepProcessor.kt` — `SpanProcessor` implementation that re-exports RecordOnly spans matching the force-keep predicate per design § D4. Configure via `BatchSpanProcessor` with separate exporter pipeline.
- [ ] 4.8 Create `infra/otel/src/main/kotlin/id/nearyou/infra/otel/OtelBootstrap.kt` — the `start(env: KtorEnv)` entrypoint. Reads `OtelConfig` via `secretKey(env, ...)` (passing in the secret-helper as a constructor dependency since `:infra:otel` doesn't depend on the secret module directly; design choice — surface the dependency at the call site in `Application.module()`). Configures the `SdkTracerProvider` per the env's sampling profile, registers it as the global `OpenTelemetry`, returns a handle for graceful shutdown. Idempotent: tracks initialization in an `AtomicBoolean`.
- [ ] 4.9 Create `infra/otel/src/test/kotlin/id/nearyou/infra/otel/UserIdHasherTest.kt` — covers spec scenarios: deterministic, distinct-input-distinct-output, output-format-regex.
- [ ] 4.10 Create `infra/otel/src/test/kotlin/id/nearyou/infra/otel/SamplingProfileTest.kt` — covers spec scenarios: dev=100%, prod 1000-seed ratio between 5%–15%, prod force-keep on `http.status_code=500`, prod force-keep on slow span (800ms > 500ms threshold), prod drop on healthy fast span outside ratio.
- [ ] 4.11 Create `infra/otel/src/test/kotlin/id/nearyou/infra/otel/WithSpanTest.kt` — covers spec scenarios: block returns normally → OK status, block throws → ERROR status + recordException + re-throw, caller-provided attributes appear verbatim.
- [ ] 4.12 Create `infra/otel/src/test/kotlin/id/nearyou/infra/otel/OtelBootstrapTest.kt` — covers spec scenarios: first-call wires SDK, second-call no-op INFO line, exporter misconfiguration falls back to no-op without crashing, token-absent emits exporter_disabled INFO line + LoggingSpanExporter wired, endpoint-absent same, both-present wires live OTLP exporter, OTLP token VALUE never appears in startup logs.

## 5. Wire `:infra:otel` into `:backend:ktor`

- [ ] 5.1 Add `:infra:otel` as a project dependency in `backend/ktor/build.gradle.kts`.
- [ ] 5.2 In `backend/ktor/src/main/kotlin/.../Application.kt`, invoke `OtelBootstrap.start(env)` as the FIRST call inside `Application.module()` (before any other `install(...)` or DI wiring) so subsequent module inits get auto-instrumented.
- [ ] 5.3 Wire HikariCP JDBC instrumentation: replace the existing `HikariDataSource` construction site with the OTel-instrumented variant via `JdbcTelemetry.create(openTelemetry).wrap(dataSource)` (or equivalent). Set `setStatementSanitizationEnabled(true)`. Verify the wrapped data source is the one Koin binds.
- [ ] 5.4 Wire Lettuce Redis instrumentation: pass `LettuceTelemetry.create(openTelemetry).newTracing()` into `ClientResources.Builder.tracing(...)`. Verify the resulting `RedisClient` is the one Koin binds.
- [ ] 5.5 Wire outbound HTTP client instrumentation: configure the `HttpClient` (Apache or JDK) with the OTel client interceptor. Verify outbound requests from the Resend wrapper, Supabase REST wrapper, and Supabase Realtime publish all carry `traceparent`.
- [ ] 5.6 If `opentelemetry-instrumentation-ktor` is unavailable for Ktor 3.4.x, install the manual server interceptor in `Application.module()` immediately after `OtelBootstrap.start(env)` per design § Risks (first row). Verify every inbound HTTP request produces a server span with `http.route` = Ktor route pattern.
- [ ] 5.7 Add `geo.cloud_region` as a per-resource attribute on the SDK builder (read from the `K_SERVICE_REGION` env var; default to `"unknown"` if absent). This applies the attribute to every span exported from the JVM without per-span code changes.
- [ ] 5.8 Add hashed `user.id` to the Ktor server span via the existing auth principal extraction site — when a request principal exists, set `Span.current().setAttribute("user.id", UserIdHasher.hash(principal.userId))`. Do not set `user.id` for unauthenticated requests.

## 6. Modify shipped capabilities to consume OTel surface

- [ ] 6.1 Update the chat send route handler at `POST /api/v1/chat/{conversation_id}/messages` — wrap the existing `ChatRealtimeClient.publish(...)` call in `withSpan("chat.realtime.publish", mapOf("conversation_id" to conversationId.toString(), "message_id" to messageId.toString())) { ... }`. The WARN log emission stays unchanged; ADD a `Span.current().addEvent("chat_realtime_publish_failed", attrs)` call inside the existing failure branches (both `PublishResult.Failure` and the catch block) with `error.type` = `error_class`. Verify status is set to ERROR via `Span.current().setStatus(StatusCode.ERROR)`.
- [ ] 6.2 Add corresponding test cases in `chat-realtime-broadcast` test surface (likely `:backend:ktor` test) per the modified spec scenarios — span captured on Failure, span captured on thrown exception, OK status on Success, no raw chat content in span attributes, service role key VALUE never in spans.
- [ ] 6.3 Update `FcmDispatcher` — wrap the FCM Admin SDK send call (per token) in `withSpan("fcm.dispatch", mapOf("messaging.system" to "fcm", "messaging.destination.kind" to "topic", "user.id" to UserIdHasher.hash(userId))) { ... }`. ADD `Span.current().addEvent("fcm_dispatch_failed", attrs)` inside each WARN log emission site (INVALID_ARGUMENT, 5xx, network timeout, per-token partial-failure, dispatch-after-shutdown). Wrap each `addEvent` in a try/catch that swallows any exception per spec § "Span recording failure does not block dispatch". Verify the success path leaves the span at OK status with no failure event.
- [ ] 6.4 Add corresponding test cases in `fcm-push-dispatch` test surface — span event paired with each WARN scenario, span carries no raw token, span carries hashed user.id (not raw UUID), span recording failure does not block dispatch.
- [ ] 6.5 Implement manual `traceparent` injection on the Firebase Admin SDK send. Approach: configure the Firebase Admin SDK's HTTP transport via `FirebaseOptions.Builder.setHttpTransport(...)` to use a transport that injects the active context's `traceparent` header on outbound requests. If the FCM SDK's transport API does not allow header injection, log this as an out-of-scope item in `FOLLOW_UPS.md` and ship the local span without cross-service propagation (per design § D8 fallback).

## 7. Tests + lint pass

- [ ] 7.1 Run `./gradlew :infra:otel:test` — all `:infra:otel` unit tests pass.
- [ ] 7.2 Run `./gradlew :backend:ktor:test` — existing tests still pass; new chat + fcm span-pairing tests pass.
- [ ] 7.3 Run `./gradlew :lint:detekt-rules:test` — existing Detekt rule suite passes (no new rules in this change; scope deferred per design § D10).
- [ ] 7.4 Run `./gradlew ktlintCheck detekt` — both lint frameworks pass per `CLAUDE.md` § "Pre-push verification".
- [ ] 7.5 Verify cold-start regression budget — re-measure cold-start p99 with OTel SDK init. If regression > 200ms, file follow-up: defer SDK init to a coroutine post-first-request (lazy init) per design § Risks. Record the measured deltas in the PR description.

## 8. Staging soak

- [ ] 8.1 Merge to `main` (squash-merge from this PR per the one-PR-per-change convention) → auto-deploy to `api-staging.nearyou.id`.
- [ ] 8.2 Run a 5-minute `wrk` load against the timeline endpoint (representative shape: `wrk -t4 -c20 -d5m -s scripts/timeline-soak.lua https://api-staging.nearyou.id/api/v1/timeline/nearby`). Capture `wrk` summary in PR comment.
- [ ] 8.3 In Grafana Cloud Tempo, search for traces from the soak window. Verify:
    - Server spans appear with `http.route` = `/api/v1/timeline/nearby` (route pattern, not raw URL).
    - JDBC spans appear with `db.statement` = parameterized form (no UUID values visible).
    - Lettuce spans appear for the rate-limit Lua script.
    - `user.id` attributes are 16-char hex strings, not raw UUIDs.
    - `geo.cloud_region` attribute equals the staging Cloud Run region.
- [ ] 8.4 Trigger a synthetic error (e.g., a 500-returning test endpoint protected by feature flag) AND a synthetic slow request (>500ms). Verify both traces appear in Tempo (force-keep promotion working).
- [ ] 8.5 Trigger a synthetic chat send failure (e.g., publish via a deliberately-broken Realtime endpoint URL OR a unit-test-driven Failure return) on staging. Verify in Tempo that the `chat.realtime.publish` span is captured with status ERROR + the `chat_realtime_publish_failed` event.
- [ ] 8.6 Document the staging soak results in the PR description before promoting to production.

## 9. Production rollout

- [ ] 9.1 Tag the merged commit with the next `v*` tag for production deploy per `openspec/project.md` § Environments.
- [ ] 9.2 Confirm production-stack token + endpoint secrets are populated before tag-deploy (avoid the no-op-exporter-in-production scenario from spec § "Token absent → no-op exporter + single INFO line"). Cloud Run revision logs SHOULD show one of: (a) the OTLP exporter wiring INFO line with the configured endpoint, OR (b) explicitly the `event="otel_exporter_disabled"` line during a known-misconfig deploy — anything else is a regression.
- [ ] 9.3 After the production rollout, sample a small batch of error traces and slow traces in Tempo to confirm force-keep is firing in production. Sample healthy fast traces to confirm ~10% base ratio.
- [ ] 9.4 Update `FOLLOW_UPS.md` if the Detekt rule for forbidden attributes should be tracked per design § D10 (probably yes — new follow-up entry titled `otel-attribute-detekt-rule`).

## 10. Spec deltas + reconciliation

- [ ] 10.1 Confirm the spec deltas under `openspec/changes/observability-otel-foundation/specs/` match the implementation: every spec scenario has a corresponding test case wired in step 4.x / 6.x / 7.x. Surface any spec-vs-code divergence to the user via `AskUserQuestion` rather than silently relaxing the spec.
- [ ] 10.2 Re-run `openspec validate observability-otel-foundation --strict` after any spec amendment. Validation MUST pass before archive.
- [ ] 10.3 At archive time, `/opsx:archive` syncs the spec deltas into `openspec/specs/` per the standard archive flow.
