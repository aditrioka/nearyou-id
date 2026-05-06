## 1. Pre-implementation baseline

- [ ] 1.1 Verify Open Decision #12 vendor default — re-read `docs/08-Roadmap-Risk.md:586-588` to confirm Grafana Cloud Tempo remains the recommended default at task-execution time. If the user has flipped the decision since proposal review, reopen the design § D3 and amend the bootstrap config-block scope before implementation.
- [ ] 1.2 Capture baseline cold-start p99 — run `./gradlew :backend:ktor:run` (or the production-shape jar invocation) on a representative machine, measure `Application.module()` startup wall-clock time across 5 cold starts. Record the median + p99 in the PR description (without OTel SDK init) for later regression comparison.
- [ ] 1.3 Confirm `opentelemetry-instrumentation-ktor` library availability for Ktor 3.4.x — check Maven Central. If unavailable, plan to fall back to the manual `Ktor` interceptor implementation described in design § Risks (first row).
- [ ] 1.4 Re-read `docs/04-Architecture.md:386-399`, `docs/05-Implementation.md:2042`, and `docs/01-Business.md:380-381,454-455` to confirm the canonical instrumentation contract, sampling profile, and cost projection match the spec before scaffolding code.
- [ ] 1.5 Confirm `firebase-admin` 9.5.0 (current pin per `docs/09-Versions.md`) exposes `FirebaseOptions.Builder.setHttpTransport(HttpTransport)` for the `TracingHttpTransport` injection per design § D8. If the pinned version doesn't expose this hook, EITHER bump firebase-admin to a version that does (with a new `docs/09-Versions.md` row) OR escalate to the user before scaffolding the FCM `traceparent` injection task — the spec scenario "FCM Admin SDK send carries traceparent" is unconditional.

## 2. Provision secrets + Grafana Cloud project

- [ ] 2.1 Create a Grafana Cloud free-tier project for staging + production (one project, two stacks: `nearyou-staging`, `nearyou-prod`) per `docs/10-Setup-Checklist.md:220-229`.
- [ ] 2.2 Mint the OTLP/HTTP token for each stack (Read+Write trace permissions only — no metric/log scope at this change). Store via the existing GCP Secret Manager workflow:
    - `staging-otel-grafana-otlp-endpoint` (e.g., `https://tempo-prod-XX-us-central-0.grafana.net/tempo`).
    - `staging-otel-grafana-otlp-token` (bearer token).
    - `otel-grafana-otlp-endpoint` (production stack URL).
    - `otel-grafana-otlp-token` (production bearer token).
- [ ] 2.3 Confirm zero CI / dev access leakage — only the staging + production Cloud Run service accounts have IAM grants to read these secrets. Document the SA grant in the PR description.
- [ ] 2.4 Amend `docs/10-Setup-Checklist.md:220-229` to record the verbatim slot names this change introduces — replace the conceptual "Save OTel endpoint + API key" line with explicit checklist items for `staging-otel-grafana-otlp-endpoint`, `staging-otel-grafana-otlp-token`, `otel-grafana-otlp-endpoint`, `otel-grafana-otlp-token`. This brings the setup checklist into alignment with the spec's `secretKey(env, ...)` calls.

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
- [ ] 4.1.1 Add a one-line description for `:infra:otel` to `dev/module-descriptions.txt` (alongside the existing entries for `:infra:fcm`, `:infra:oidc`, `:infra:redis`, `:infra:supabase`). Then run `dev/scripts/sync-readme.sh --write` to regenerate the root README module list. Per CLAUDE.md "Critical invariants" — when a change adds a new module, the README sync is mandatory; CI runs `--check` and surfaces drift.
- [ ] 4.2 Create `infra/otel/build.gradle.kts` mirroring the convention of `infra/redis/build.gradle.kts` (Kotlin JVM target, Detekt + ktlint plugins, dependency on `:core:domain` for `KtorEnv`, dependency on the OTel BOM + SDK + exporter pins from step 3).
- [ ] 4.3 Create `infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/OtelConfig.kt` — value class wrapping `endpoint`, `token`, `samplingProfile`, `samplingRatio` (the production base ratio constant); read by `OtelBootstrap`. Package is `id.nearyou.app.infra.otel` — matches existing convention (`infra/fcm/src/main/kotlin/id/nearyou/app/infra/fcm/`, `infra/redis/src/main/kotlin/id/nearyou/app/infra/redis/`, etc.).
- [ ] 4.4 Create `infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/SamplingProfile.kt` — enum `Dev` / `Staging` / `Production`, each backed by a factory function returning the configured `Sampler` per spec § "Sampling profile per environment matches the canonical profile".
- [ ] 4.5 Create `infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/UserIdHasher.kt` — exposes `UserIdHasher.hash(userId: UUID): String` returning first 16 hex chars of `SHA-256(uuid.bytes)`, deterministic, on an `object UserIdHasher` declaration. Single-source-of-truth for the project's hashed `user.id` pattern.
- [ ] 4.6 Create `infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/WithSpan.kt` — `withSpan(name: String, attributes: Map<String, Any> = emptyMap(), block: () -> T): T` per spec § "`withSpan` helper is the canonical manual-span surface". Use `try/finally` so the span ends on `Throwable` (not just `Exception`); re-throw the original cause.
- [ ] 4.7 Create `infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/ErrorSlowForceKeepProcessor.kt` — `SpanProcessor` implementation that re-exports RecordOnly spans matching the force-keep predicate per design § D4. Configure via `BatchSpanProcessor` with separate exporter pipeline.
- [ ] 4.8 Create `infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/OtelBootstrap.kt` — the `start(env: KtorEnv)` entrypoint. Reads `OtelConfig` via `secretKey(env, ...)` (passing in the secret-helper as a constructor dependency since `:infra:otel` doesn't depend on the secret module directly; design choice — surface the dependency at the call site in `Application.module()`). Configures the `SdkTracerProvider` per the env's sampling profile, registers it as the global `OpenTelemetry`, returns a handle for graceful shutdown. Idempotent: tracks initialization in an `AtomicBoolean`. Implements deterministic precedence on EITHER-secret-absent (per spec § "Both secrets absent" scenario): check endpoint first, then token; emit exactly one INFO line.
- [ ] 4.9 Create `infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/TracingHttpTransport.kt` — wraps the Firebase Admin SDK's default `HttpTransport` and injects the active OTel context's `traceparent` header on outbound HTTPS requests per design § D8. Used by FCM dispatcher wiring in step 5.
- [ ] 4.10 Create `infra/otel/src/test/kotlin/id/nearyou/app/infra/otel/testing/SpanRecorder.kt` — a public test fixture: a `SpanProcessor` test-double that captures every `onEnd(span)` into an in-memory `MutableList<SpanData>`, exposes `recordedSpans(): List<SpanData>` and `clear()`. Provides the canonical spec-scenario test infrastructure consumed by both `chat-realtime-broadcast` and `fcm-push-dispatch` test surfaces (referenced by name in those spec deltas). Also exposes a `FailingSpanRecorder` variant whose `addEvent(...)` always throws — used by the `Span recording failure does not block dispatch` scenario.
- [ ] 4.11 Create `infra/otel/src/test/kotlin/id/nearyou/app/infra/otel/UserIdHasherTest.kt` — covers spec scenarios: deterministic, distinct-input-distinct-output, output-format-regex.
- [ ] 4.12 Create `infra/otel/src/test/kotlin/id/nearyou/app/infra/otel/SamplingProfileTest.kt` — covers spec scenarios: dev=100%, **staging=100% (parity with dev, NOT prod composed sampler)**, prod 1000-seed ratio between 5%–15%, prod force-keep on `http.status_code=500`, prod force-keep on slow span (800ms > 500ms threshold), **prod force-keep on `exception.escaped=true` at status 200**, prod drop on healthy fast span outside ratio.
- [ ] 4.13 Create `infra/otel/src/test/kotlin/id/nearyou/app/infra/otel/WithSpanTest.kt` — covers spec scenarios: block returns → OK, block throws Exception subclass → ERROR + recordException + re-throw, **block throws CancellationException → ERROR + re-throw (NOT swallowed)**, **block throws Throwable subclass (Error) → span ends via try/finally**, **nested withSpan produces parent-child relationship**, caller-provided attributes appear verbatim.
- [ ] 4.14 Create `infra/otel/src/test/kotlin/id/nearyou/app/infra/otel/OtelBootstrapTest.kt` — covers spec scenarios: first-call wires SDK, second-call no-op INFO line, exporter misconfiguration falls back to no-op without crashing, token-absent emits exporter_disabled INFO line + LoggingSpanExporter wired, endpoint-absent same, **both-secrets-absent emits exactly ONE INFO line with deterministic-precedence reason**, both-present wires live OTLP exporter, OTLP token VALUE never appears in startup logs, **OTLP token VALUE never appears in span attributes/events/names** (use SpanRecorder + sentinel-token substring scan).

## 5. Wire `:infra:otel` into `:backend:ktor`

- [ ] 5.1 Add `:infra:otel` as a project dependency in `backend/ktor/build.gradle.kts`.
- [ ] 5.2 In `backend/ktor/src/main/kotlin/.../Application.kt`, invoke `OtelBootstrap.start(env)` as the FIRST call inside `Application.module()` (before any other `install(...)` or DI wiring) so subsequent module inits get auto-instrumented.
- [ ] 5.3 Wire HikariCP JDBC instrumentation: replace the existing `HikariDataSource` construction site with the OTel-instrumented variant via `JdbcTelemetry.create(openTelemetry).wrap(dataSource)` (or equivalent). Set `setStatementSanitizationEnabled(true)`. Verify the wrapped data source is the one Koin binds.
- [ ] 5.4 Wire Lettuce Redis instrumentation: pass `LettuceTelemetry.create(openTelemetry).newTracing()` into `ClientResources.Builder.tracing(...)`. Verify the resulting `RedisClient` is the one Koin binds. **Configure the Lettuce telemetry to either drop the `db.connection_string` attribute OR sanitize the userinfo portion of the Redis URI** (the password lives in the userinfo and MUST NOT reach Tempo). Add a sentinel-password regression test per spec § "No raw Redis password appears in `db.connection_string`".
- [ ] 5.5 Wire outbound HTTP client instrumentation: configure the `HttpClient` (Apache or JDK) with the OTel client interceptor. Verify outbound requests from the Resend wrapper (when scaffolded), Supabase REST wrapper, and Supabase Realtime publish all carry `traceparent`.
- [ ] 5.6 If `opentelemetry-instrumentation-ktor` is unavailable for Ktor 3.4.x, install the manual server interceptor in `Application.module()` immediately after `OtelBootstrap.start(env)` per design § Risks (first row). Verify every inbound HTTP request produces a server span with `http.route` = Ktor route pattern.
- [ ] 5.7 Configure the Ktor server instrumentation to **suppress** the auto-attached attributes `client.address`, `net.peer.ip`, `net.sock.peer.addr`, `http.client_ip` per spec § "Forbidden span attributes" (project posture forbids exposing peer-IPs even when they're Cloudflare-edge IPs, not real client IPs). Add a regression test asserting these keys do not appear on a representative server span.
- [ ] 5.8 Add `cloud.region` as a per-resource attribute on the SDK builder (read from the `K_SERVICE_REGION` env var; default to `"unknown"` if absent). Use the OTel semconv name `cloud.region` (NOT `geo.cloud_region` — the canonical doc shorthand is amended via the FOLLOW_UPS.md entry). This applies the attribute to every span exported from the JVM without per-span code changes.
- [ ] 5.9 Add hashed `user.id` to the Ktor server span via the existing auth principal extraction site — when a request principal exists, set `Span.current().setAttribute("user.id", UserIdHasher.hash(principal.userId))`. Do not set `user.id` for unauthenticated requests. Add a regression test asserting raw JWT claims (`sub`, `aud`, `iss`) and the raw bearer token never appear in any span attribute (sentinel-string scan per spec § "No raw bearer token appears in any span" + "No raw JWT claim appears in any span").

## 6. Modify shipped capabilities to consume OTel surface

- [ ] 6.1 Update the chat send route handler at `POST /api/v1/chat/{conversation_id}/messages` — wrap the existing `ChatRealtimeClient.publish(...)` call in `withSpan("chat.realtime.publish", mapOf("conversation_id" to conversationId.toString(), "message_id" to messageId.toString())) { ... }`. The WARN log emission stays unchanged; ADD a `Span.current().addEvent("chat_realtime_publish_failed", attrs)` call inside the existing failure branches (both `PublishResult.Failure` and the catch block) with `error.type` = `error_class`. Verify status is set to ERROR via `Span.current().setStatus(StatusCode.ERROR)`.
- [ ] 6.2 Add test cases in `chat-realtime-broadcast` test surface (likely `:backend:ktor` test) per the modified spec scenarios. Each scenario maps 1:1 to a test:
    - [ ] 6.2.1 WARN log emitted on `PublishResult.Failure`.
    - [ ] 6.2.2 WARN log emitted on thrown exception caught post-commit.
    - [ ] 6.2.3 No WARN log on `PublishResult.Success`.
    - [ ] 6.2.4 OTel span captured on `PublishResult.Failure` with status ERROR + `chat_realtime_publish_failed` event + `error.type` attribute.
    - [ ] 6.2.5 OTel span captured on thrown exception with status ERROR + `recordException` capture.
    - [ ] 6.2.6 OTel span on Success has OK status + no failure event.
    - [ ] 6.2.7 Span carries no raw chat content (sentinel-string scan).
    - [ ] 6.2.8 Service role key slot name appears only at `secretKey(env, ...)` call site.
    - [ ] 6.2.9 Service role key VALUE never appears in logs (sentinel-string scan).
    - [ ] 6.2.10 Service role key VALUE never appears in spans (sentinel-string scan across attributes + event attributes + span names).
    - [ ] 6.2.11 Outbound Supabase Realtime publish HTTP request carries `traceparent` (mock transport boundary).
- [ ] 6.3 Update `FcmDispatcher` — wrap the FCM Admin SDK send call (per token) in `withSpan("fcm.dispatch", mapOf("messaging.system" to "fcm", "user.id" to UserIdHasher.hash(userId))) { ... }`. **Do NOT set `messaging.destination.kind`** — FCM dispatches to per-device tokens, neither `"topic"` nor `"queue"` accurately describes the destination, and emitting `"topic"` would mislead operators. ADD `Span.current().addEvent("fcm_dispatch_failed", attrs)` inside each WARN log emission site (INVALID_ARGUMENT, 5xx, network timeout, per-token partial-failure, dispatch-after-shutdown). Wrap each `addEvent` in a try/catch that swallows any exception per spec § "Span recording failure does not block dispatch". Verify the success path leaves the span at OK status with no failure event. Verify INFO-level skip paths (`fcm_skipped_no_tokens`, `fcm_token_prune_skipped_re_registered`) do NOT record a span event.
- [ ] 6.4 Add test cases in `fcm-push-dispatch` test surface per the new ADDED requirement. Each scenario maps 1:1 to a test:
    - [ ] 6.4.1 INVALID_ARGUMENT WARN log pairs with span event.
    - [ ] 6.4.2 5xx-class error WARN log pairs with span event (`INTERNAL`).
    - [ ] 6.4.3 Network timeout WARN log pairs with span event (`error_code="unknown"`).
    - [ ] 6.4.4 Per-token partial-failure pairs with one span event per failed token (no event for succeeding tokens).
    - [ ] 6.4.5 dispatch-after-shutdown pairs with span event.
    - [ ] 6.4.6 Successful dispatch records span with OK status, no failure event.
    - [ ] 6.4.7 INFO-level skip paths do NOT record a span event (locks the requirement scope to WARN-level paths only).
    - [ ] 6.4.8 Span recording failure does not block dispatch (uses `FailingSpanRecorder` from §4.10).
    - [ ] 6.4.9 Span carries no raw FCM token (sentinel-token scan).
    - [ ] 6.4.10 Span carries hashed `user.id`, not raw UUID.
- [ ] 6.5 Implement manual `traceparent` injection on the Firebase Admin SDK send. Configure the Firebase Admin SDK via `FirebaseOptions.Builder.setHttpTransport(TracingHttpTransport(...))` (per design § D8 + the `TracingHttpTransport` scaffold from §4.9) so the SDK's outbound HTTPS request carries the active context's `traceparent` header. Per the firebase-admin 9.5.0 hook check in §1.5, this is unconditional — if the pinned version doesn't support it, escalate to user before proceeding (do NOT silently downgrade to local-span-only).

## 7. Tests + lint pass

- [ ] 7.1 Run `./gradlew :infra:otel:test` — all `:infra:otel` unit tests pass.
- [ ] 7.2 Run `./gradlew :backend:ktor:test` — existing tests still pass; new chat + fcm span-pairing tests pass.
- [ ] 7.3 Run `./gradlew :lint:detekt-rules:test` — existing Detekt rule suite passes (no new rules in this change; scope deferred per design § D10).
- [ ] 7.4 Run `./gradlew ktlintCheck detekt` — both lint frameworks pass per `CLAUDE.md` § "Pre-push verification".
- [ ] 7.5 Add a JDBC sanitization regression test in `:backend:ktor` test surface — execute a query carrying a literal sentinel string in a parameter value via `PreparedStatement`, capture the resulting JDBC span via SpanRecorder, assert the span's `db.statement` attribute contains `?` placeholders and does NOT contain the sentinel value. Locks `setStatementSanitizationEnabled(true)` against a future SDK upgrade or refactor regression.
- [ ] 7.6 Verify cold-start regression budget — re-measure cold-start p99 with OTel SDK init (manual procedural check, NOT an automated test; the regression cap is documented in design § Risks but is not bound to a CI assertion in this change). If regression > 200ms, file follow-up: defer SDK init to a coroutine post-first-request (lazy init) per design § Risks. Record the measured deltas in the PR description.

## 8. Staging soak

- [ ] 8.1 Merge to `main` (squash-merge from this PR per the one-PR-per-change convention) → auto-deploy to `api-staging.nearyou.id`.
- [ ] 8.2 Run a 5-minute `wrk` load against the timeline endpoint (representative shape: `wrk -t4 -c20 -d5m -s scripts/timeline-soak.lua https://api-staging.nearyou.id/api/v1/timeline/nearby`). Capture `wrk` summary in PR comment.
- [ ] 8.3 In Grafana Cloud Tempo, search for traces from the soak window using the saved TraceQL query `{ resource.service.name = "nearyou-staging" && resource.cloud.region != "" } | select(http.route, db.statement, user.id, cloud.region)` (save it under the name `otel-foundation-staging-soak` in the Grafana stack so the verification is reproducible). Verify:
    - Server spans appear with `http.route` = `/api/v1/timeline/nearby` (route pattern, not raw URL).
    - JDBC spans appear with `db.statement` = parameterized form (no UUID values visible).
    - Lettuce spans appear for the rate-limit Lua script (with `db.connection_string` either absent OR sanitized — no Redis password substring).
    - `user.id` attributes are 16-char hex strings, not raw UUIDs.
    - `cloud.region` attribute equals the staging Cloud Run region (e.g., `asia-southeast1`).
    - No `client.address` / `net.peer.ip` / `http.client_ip` / `net.sock.peer.addr` attributes appear on server spans (suppression working).
- [ ] 8.4 Trigger a synthetic error (e.g., a 500-returning test endpoint protected by feature flag) AND a synthetic slow request (>500ms). Verify both traces appear in Tempo (force-keep promotion working).
- [ ] 8.5 Trigger a synthetic chat send failure (e.g., publish via a deliberately-broken Realtime endpoint URL OR a unit-test-driven Failure return) on staging. Verify in Tempo that the `chat.realtime.publish` span is captured with status ERROR + the `chat_realtime_publish_failed` event.
- [ ] 8.6 Document the staging soak results in the PR description before promoting to production.

## 9. Production rollout

- [ ] 9.1 Tag the merged commit with the next `v*` tag for production deploy per `openspec/project.md` § Environments.
- [ ] 9.2 Confirm production-stack token + endpoint secrets are populated before tag-deploy (avoid the no-op-exporter-in-production scenario from spec § "Token absent → no-op exporter + single INFO line"). Cloud Run revision logs SHOULD show one of: (a) the OTLP exporter wiring INFO line with the configured endpoint, OR (b) explicitly the `event="otel_exporter_disabled"` line during a known-misconfig deploy — anything else is a regression.
- [ ] 9.3 After the production rollout, sample a small batch of error traces and slow traces in Tempo to confirm force-keep is firing in production. Sample healthy fast traces to confirm ~10% base ratio.
- [ ] 9.4 Update `FOLLOW_UPS.md` with two entries: (a) `otel-attribute-detekt-rule` per design § D10 — Detekt rule enforcing the forbidden-attributes contract once the writer surface stabilizes; (b) confirm the existing `geo-cloud-region-canonical-doc-amendment` entry still applies (the canonical `docs/04-Architecture.md:398` shorthand is `geo.cloud_region`; this change ships the OTel semconv name `cloud.region` and the canonical doc gets amended in a future docs-only PR).

## 10. Spec deltas + reconciliation

- [ ] 10.1 Confirm the spec deltas under `openspec/changes/observability-otel-foundation/specs/` match the implementation: every spec scenario has a corresponding test case wired in step 4.x / 6.x / 7.x. Surface any spec-vs-code divergence to the user via `AskUserQuestion` rather than silently relaxing the spec.
- [ ] 10.2 Re-run `openspec validate observability-otel-foundation --strict` after any spec amendment. Validation MUST pass before archive.
- [ ] 10.3 At archive time, `/opsx:archive` syncs the spec deltas into `openspec/specs/` per the standard archive flow.
