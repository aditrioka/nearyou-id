## Context

`:backend:ktor` ships today with structured JSON logging (Logback) + Sentry Java for backend errors. Every shipped capability that needs failure-mode visibility currently relies on WARN logs as the canonical surface, with [`openspec/specs/chat-realtime-broadcast/spec.md:228`](../../specs/chat-realtime-broadcast/spec.md) explicitly naming `observability-otel-foundation` as the change that resolves the deferral. The `:infra:otel` module name is reserved in [`openspec/project.md:53`](../../project.md) and [`docs/04-Architecture.md:118`](../../../docs/04-Architecture.md) but the module directory does not exist on disk — `infra/` currently contains only `fcm`, `oidc`, `redis`, `supabase`. The instrumentation contract, mandatory attributes, and sampling profile are all canonical and unambiguous in [`docs/04-Architecture.md:386-399`](../../../docs/04-Architecture.md) + [`docs/05-Implementation.md:2042`](../../../docs/05-Implementation.md). Open Decision #12 ([`docs/08-Roadmap-Risk.md:586-588`](../../../docs/08-Roadmap-Risk.md)) is the one outstanding ambiguity — vendor final between Grafana Cloud / Honeycomb / Cloud Trace — and the canonical doc names Grafana Cloud as the recommended default. Cost projection at [`docs/01-Business.md:381,455`](../../../docs/01-Business.md) confirms Grafana Cloud free tier covers Phase 2 traffic with ~0.2% of revenue at scale.

Constraints carried into the design:
- **Vendor portability** is a guiding principle (`openspec/project.md` § Guiding Principles #4 — "Portable by design — every vendor integration sits behind a `:infra:*` abstraction so migrations are frictionless"). The Grafana Cloud choice MUST be reversible without a Ktor/business-module change.
- **PII safety**: existing CI lint rules forbid raw `X-Forwarded-For` reads, raw secret-name reads, raw username writes outside sanctioned paths, and `actual_location` reads outside admin paths. OTel attributes are a NEW surface that must obey the same posture — no spans MAY ever carry raw PII. The mitigation is a forbidden-attributes list (positive requirement so the spec encodes what NOT to attach) plus the JDBC instrumentation's `statementSanitizer` enabled by default.
- **Solo-operator ops budget** — no operator should need to remember to add manual spans to every new business path. The default MUST be auto-instrumentation does the heavy lifting; manual spans are only required for non-auto-instrumented paths (Realtime publish, rate-limit Lua call, FCM Admin SDK send).

## Goals / Non-Goals

**Goals:**
- Stand up `:infra:otel` as a standalone module owning every OTel-vendor concern, with a single startup entrypoint (`OtelBootstrap.start(env)`) and a single span helper (`withSpan(name, attributes) { ... }`) consumed by `:backend:ktor`.
- Auto-instrumentation covers every shipped HTTP/JDBC/Redis/Supabase/FCM call path. Manual spans only where auto-instrumentation gaps exist.
- Resolve the explicit OTel deferral in `chat-realtime-broadcast`, and harden the WARN logs in `fcm-push-dispatch` with span-event pairing, so failure-mode telemetry is queryable in Grafana Tempo by `event = ...`.
- Production sampling profile per [`docs/05-Implementation.md:2042`](../../../docs/05-Implementation.md): 10% base + 100% errors + 100% slow (>500ms root span). Staging + dev: 100% head sampling.
- Vendor swap (Open Decision #12) is a future within-module change. Business code MUST NOT import `io.grafana.*` or any vendor-specific package directly.

**Non-Goals:**
- Mobile-side OTel (KMP). Sentry KMP already covers crash + error reporting.
- OTel Logs SDK. Structured JSON logging stays as-is; this change covers traces only.
- Tail sampling via OTel Collector. Architecture mentions this as a future capacity step ([`docs/04-Architecture.md:394`](../../../docs/04-Architecture.md)) — Phase 2 has insufficient volume to need it.
- Metrics SDK (`io.opentelemetry.sdk.metrics`). GCP Cloud Monitoring + Supabase dashboards already cover metrics.
- Java agent (`-javaagent:opentelemetry-javaagent.jar`) auto-attach. The SDK-with-libraries approach is preferred (see Decision D2).
- Phase 2 §14 benchmark itself. This change unblocks it but does not execute it.
- Custom dashboards in Grafana Cloud. Operators consume the data via Grafana Tempo's standard trace search; dashboard authoring is a separate operator-facing chore.
- **Resend HTTP** and **CF Images call** instrumentation. Both are listed as mandatory span surfaces at [`docs/04-Architecture.md:397`](../../../docs/04-Architecture.md), but neither producing module exists today: `:infra:resend` is unscaffolded; `:infra:cloudflare-images` is gated behind `image_upload_enabled=FALSE` per Phase 4 §16. The cross-cutting `:infra:otel` surface this change introduces (auto-instrumented outbound HTTP, mandatory attributes, sampling profile, `withSpan` helper) covers them automatically when those modules ship — no future change re-introduces the foundation. The forthcoming Resend / CF Images proposals consume this foundation as a dependency, not as a re-implementation.

## Decisions

### D1: `:infra:otel` is a new standalone module, not a sub-package of an existing infra module

**Decision**: Create a fresh `infra/otel/` Gradle module with its own `build.gradle.kts` and namespace `id.nearyou.infra.otel`.

**Rationale**: Symmetric with the existing pattern — `:infra:fcm`, `:infra:oidc`, `:infra:redis`, `:infra:supabase` each own one vendor concern. Folding OTel into one of them (e.g., `:infra:redis` because it's the most-instrumented) couples vendor lifecycles. Module name is already reserved in [`openspec/project.md:53`](../../project.md) so there's no surprise.

**Alternatives considered**:
- Fold into `:backend:ktor` directly (no module). Rejected: violates the project's "no vendor SDK import outside `:infra:*`" CI rule.
- Use `:infra:observability` as a broader name. Rejected: the canonical doc names the module `:infra:otel` ([`docs/04-Architecture.md:118`](../../../docs/04-Architecture.md)) — drift from the canonical name is gratuitous.

### D2: SDK-with-instrumentation-libraries, NOT the OpenTelemetry Java Agent

**Decision**: Use the OpenTelemetry Java SDK (`io.opentelemetry:opentelemetry-sdk`) plus per-library instrumentation (`opentelemetry-instrumentation-jdbc`, `opentelemetry-instrumentation-lettuce-5_1`, `opentelemetry-instrumentation-ktor` if available for Ktor 3.4 — fall back to manual interceptor if not). Initialization runs in-process at `Application.module()` startup. NO `-javaagent:opentelemetry-javaagent.jar` attach.

**Rationale**:
- The Java agent is auto-magic but heavy: ~30MB attached at JVM start, applies bytecode rewriting to every loaded class, hard to debug version conflicts. Cloud Run cold-start budget targets <3s ([`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md) Phase 2 §14) — the agent's class-loader hit is non-trivial.
- The SDK approach is explicit: `OtelBootstrap.start(env)` is a single function call, version conflicts surface at compile time, and the operator can read the wiring instead of debugging bytecode.
- Library-specific instrumentation packages (`opentelemetry-instrumentation-lettuce-5_1` etc.) compose with the SDK without bytecode magic; the integration is in user-land Kotlin/Java.
- Vendor swap (Open Decision #12) is straightforward — replace the OTLP exporter pin and `OtelBootstrap.start()` configuration; no agent re-attach.

**Alternatives considered**:
- Java Agent (`-javaagent`). Rejected for cold-start + debug-difficulty reasons above.
- Hybrid (agent for auto-instrumentation + SDK for manual spans). Rejected: agent vs SDK API surface duplication; pick one mental model.

### D3: Grafana Cloud Tempo as the default exporter target via OTLP/HTTP

**Decision**: Default exporter is Grafana Cloud Tempo via OTLP/HTTP (NOT OTLP/gRPC). Endpoint + bearer token read from GCP Secret Manager via `secretKey(env, "otel-grafana-otlp-token")` and `secretKey(env, "otel-grafana-otlp-endpoint")`. The Open Decision #12 vendor swap is encapsulated entirely within `:infra:otel` — `OtelBootstrap.kt` is the only file that knows the vendor name.

**Rationale**:
- OTLP/HTTP works through HTTP-only Cloud Run egress without needing gRPC connection setup; cold-start friendly.
- Grafana Cloud Tempo speaks OTLP natively — zero protocol translation.
- Per [`docs/08-Roadmap-Risk.md:586`](../../../docs/08-Roadmap-Risk.md), Grafana Cloud is the **recommended default**. Honeycomb / Cloud Trace are also OTLP-compatible; the swap is a `OtelBootstrap` config-block change, not a code refactor.
- Cost projection at [`docs/01-Business.md:381,455`](../../../docs/01-Business.md): 0 IDR at Phase 2 (free tier covers it); 0.2% of revenue at scale.

**Alternatives considered**:
- OTLP/gRPC. Rejected: gRPC adds cold-start overhead and Cloud Run + outbound gRPC has known idiosyncrasies. HTTP is the simpler default.
- Cloud Trace direct. Rejected: vendor lock-in to GCP; harder to swap.
- Honeycomb default. Rejected: paid from message one (no free tier comparable to Grafana Cloud's). Re-evaluate at swap-time.

### D4: Sampling profile via `ParentBased(TraceIdRatioBased)` + a force-keep `SpanProcessor` for error/slow promotion

**Decision**: Production sampler is composed as:
```
Sampler.parentBased(
  TraceIdRatioBased(0.1)          // 10% base
)
```
combined with a custom `SpanProcessor` (`ErrorSlowForceKeepProcessor`) that runs in `onEnd(span)` and promotes any RecordOnly span where `http.status_code >= 500 || exception.escaped == true || (span.isRoot && duration_ms > 500)` to a force-export by re-emitting through a separate `BatchSpanProcessor` configured to export-all.

Staging + dev: `Sampler.alwaysOn()` (head 100%).

**Rationale**:
- The pure `ParentBased(TraceIdRatioBased(0.1))` shape is too loose — it would drop 90% of error traces, violating the "100% errors" requirement at [`docs/05-Implementation.md:2042`](../../../docs/05-Implementation.md).
- A `RuleBasedRoutingSampler` that hard-decides at `shouldSample` time would force `100% of errors` but you don't know if a span is an error until it ends — you have to record-then-decide-to-export.
- The `ErrorSlowForceKeepProcessor` shape is the standard OTel idiom for "head-sample but keep specific tail signals" — implemented via `Tracer.spanBuilder().setNoParent()` for the secondary export pipeline. Reference: OpenTelemetry SDK Sampling docs (canonical pattern, not a project invention).

**Alternatives considered**:
- Always-on + tail sampling at OTel Collector. Rejected: violates the Non-goal "no Collector deployment in this change". A Collector is a separate ops surface; the architecture doc defers it ([`docs/04-Architecture.md:394`](../../../docs/04-Architecture.md)).
- Always-on in production at full volume. Rejected: cost overrun risk + cardinality at scale; the canonical doc explicitly mandates 10% base.

### D5: `user.id` attribute is SHA-256 hashed via a project-wide `hashUserId(uuid)` helper, NEVER raw

**Decision**: `:infra:otel` exposes `UserIdHasher.hash(userId: UUID): String` which returns the first 16 hex characters of `SHA-256(user_id.bytes)`. Every code site setting `user.id` on a span MUST go through this helper — there is no overload accepting a raw UUID. The Detekt rule list grows by one entry: `OtelUserIdRawSetterRule` (forbid `Span.setAttribute("user.id", <UUID>)` outside the `UserIdHasher` invocation; allowlist the helper itself).

**Rationale**:
- Direct precedent: [`internal-endpoint-auth/spec.md:18`](../../specs/internal-endpoint-auth/spec.md) uses the same shape ("first 16 hex chars of `SHA-256(raw token bytes)`") for the WARN-log token correlation id. Using the same anonymization shape for OTel keeps the operator mental model unified ("16-hex truncated SHA-256 means 'this is a correlation id, not a primary key'").
- Truncated SHA-256 is sufficient for trace correlation while making rainbow-table reverse-lookup of small userbases impractical (16 hex = 64 bits of search space).
- A static helper + Detekt rule means the safety property is enforced at lint time, not at code-review time — consistent with the project's CI lint posture.

**Alternatives considered**:
- HMAC keyed by a secret. Rejected: solving a different problem (forgery resistance, which isn't a threat for tracing); adds key management overhead.
- Raw UUID. Rejected: every other PII surface in the project hashes/strips before logging or telemetry.
- Hash full 64 hex. Rejected: 16 hex is already 64 bits of entropy; storing 64 hex doubles attribute size with no observability benefit.

### D6: `db.statement` is parameterized-only via the JDBC instrumentation's `setStatementSanitizationEnabled(true)`

**Decision**: The HikariCP/JDBC OTel instrumentation is initialized with `JdbcTelemetryBuilder.setStatementSanitizationEnabled(true)`, which strips raw values from `db.statement` and replaces them with `?` placeholders. No additional sanitizer is needed for the project's queries because all parameterized queries use the JDBC `PreparedStatement` API (the project bans raw string concatenation in SQL via existing CI lint rules).

**Rationale**:
- Built-in OTel SDK feature with the exact sanitization shape the project needs.
- Zero custom code surface.
- Stripping is performed at the instrumentation layer (before the span exports), not at the application layer — defense in depth: even if a future code path accidentally ran a non-parameterized query, the value would still be stripped before reaching Grafana Tempo.

**Alternatives considered**:
- Custom span attribute interceptor. Rejected: redundant with the SDK feature.
- Disable `db.statement` entirely. Rejected: query shape is essential for performance investigation in Phase 2 §14 benchmark; only the values need stripping.

### D7: Exporter token absence is a clean no-op (no startup failure)

**Decision**: When `secretKey(env, "otel-grafana-otlp-token")` returns `null` (local dev environment without a token configured, or a misconfigured staging deployment), `OtelBootstrap.start(env)` initializes a `LoggingSpanExporter` (logs at DEBUG, dropped by default Logback config) and emits a single startup INFO log: `event="otel_exporter_disabled" reason="<reason>" sampling_profile="<profile>"`. Spans are still created in-memory but never reach a network endpoint.

**Rationale**:
- Forcing an exporter token in dev is friction. Devs running `supabase start` + `./gradlew :backend:ktor:run` shouldn't need a Grafana Cloud account.
- Forcing an exporter token in CI is friction (CI tests run thousands of times; a missing token would block CI on something orthogonal).
- The single startup INFO line is observable enough that an operator deploying to production with a missing token would immediately see "OTel exporter is disabled in production" in Cloud Logging — fail-loud-but-don't-crash.

**Alternatives considered**:
- Crash on missing token. Rejected: fragile in dev; over-restrictive.
- Always-export-to-stderr. Rejected: log-scraping noise.

### D8: Manual `traceparent` header injection on the FCM Admin SDK send call

**Decision**: The Firebase Admin SDK FCM send (`FirebaseMessaging.send(message)`) uses an internal HTTP client that the OTel auto-instrumentation does NOT cover. The fix: wrap the existing `FcmDispatcher.send(...)` body in `:infra:otel`'s `withSpan("fcm.dispatch", attributes) { ... }`, which (a) creates a span; (b) records the span's `traceparent` in MDC; (c) installs an `MDCInsertingServletFilter`-style propagator so the FCM Admin SDK's outbound HTTP request includes the `traceparent` header. If the FCM SDK's HTTP client doesn't expose a propagator hook, fall back to skipping the propagation and just recording the local span (the local span is still useful for latency attribution; cross-service propagation is a nice-to-have).

**Rationale**:
- FCM is a Google service that itself emits OTel/Cloud Trace spans — propagation gives operators a single trace from the user's chat send all the way through to FCM's delivery attempt. Worth the manual wrapping.
- The `withSpan` helper is the same shape used by other manual span sites (Realtime publish, rate-limit Lua call), so the FCM site doesn't introduce a new pattern.

**Alternatives considered**:
- Skip FCM tracing entirely. Rejected: leaves a visible gap in the chat-message-notification trace.
- Forking the FCM Admin SDK to add propagation. Rejected: massive maintenance overhead.

### D9: Spec-modification scope is `chat-realtime-broadcast` + `fcm-push-dispatch`; `internal-endpoint-auth` is left unchanged

**Decision**: This change modifies two existing capability specs (`chat-realtime-broadcast` to drop the explicit deferral + add positive pairing; `fcm-push-dispatch` to add WARN-log↔span-event pairing). `internal-endpoint-auth` is NOT modified because:
- The `internal-endpoint-auth` WARN log lines at [`spec.md:18,101`](../../specs/internal-endpoint-auth/spec.md) describe verifier-failure logging, not application-level events. The auto-instrumented Ktor server span on `/internal/*` requests already carries `http.status_code = 401` on a verifier failure — the operator-queryable signal is already there.
- Adding a "MUST emit a span with `internal.endpoint` = route pattern" requirement on top of the auto-instrumentation is redundant noise. The new `observability-otel-foundation` capability spec encodes the cross-cutting requirement that ALL Ktor server spans carry `endpoint` = route pattern; per-capability specs don't need to restate it.

**Rationale**:
- Spec-modification scope discipline: only modify a spec when the underlying behavioral contract changes. Adding a span attribute that's already covered by the cross-cutting capability is a redundant modification.
- `internal-endpoint-auth` is a security-critical spec; minimizing churn is good hygiene.

**Alternatives considered**:
- Modify all three specs symmetrically. Rejected: redundant for the reasons above.

### D10: The cross-cutting "forbidden attributes" list is a positive requirement in the new spec, not a Detekt rule (yet)

**Decision**: The `observability-otel-foundation` spec lists forbidden attribute keys (raw `user_id`, raw IPs, raw tokens, JWKS contents, raw post/chat/search content) as a positive requirement. A Detekt rule to enforce some of these is a Phase-2 hardening follow-up, not in scope here. The shape mirrors how the project handles other invariants — the rule shows up in the Detekt suite *after* the capability lands and the violation surface is concrete.

**Rationale**:
- A new capability with no shipped writers gives a Detekt rule no concrete violation surface to lint against; rules built ahead of writers tend to over-fit or under-fit.
- The forbidden-attributes list in the spec is the contract; the lint rule is the enforcement. Both can ship, but the lint rule benefits from one round of real-code-review feedback before it lands.

**Alternatives considered**:
- Ship a Detekt rule in this change. Rejected for the reason above; can be added in a follow-up `otel-attribute-lint` change once `:infra:otel` has writers.

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| **OTel Ktor instrumentation library not yet published for Ktor 3.4.x** — auto-instrumentation gap on the HTTP server side | Manual `Ktor` interceptor wrapping using OTel's `ServerHandlerInstrumenter` with W3C propagator — ~50 lines of code. Spec captures this as a "Ktor server span SHALL be emitted for every request" without prescribing the package source. |
| **OTLP token leaks into a span attribute or Cloud Logging line** | (a) The `secretKey(env, name)` helper already enforces no-direct-secret-name reads via Detekt (`SecretKeyHelperRule`). (b) The `OtelBootstrap` reads the token once, hands it to the SDK builder, and the reference goes out of scope. (c) The startup INFO log is parameterized to never log the token value. |
| **Cardinality blow-up via per-user span attributes** — Grafana Tempo storage cost grows with attribute cardinality | (a) `user.id` is a 64-bit truncated hash, not a raw UUID — bounded cardinality. (b) `endpoint` is a route pattern (`/api/v1/posts/{post_id}/like`), NOT a raw URL with the path-param value. (c) Sampling at 10% base reduces volume. |
| **Sampling profile under-counts a real production incident** — 10% base means 90% of normal traces are dropped, but slow/error promotion catches anomalies | The promotion logic catches `http.status_code >= 500`, `exception.escaped`, and `duration > 500ms`. Any incident WORTH investigating manifests as one of these — the 90% drop only loses healthy traces. Unit-test coverage on `ErrorSlowForceKeepProcessor` is mandatory in tasks.md. |
| **Vendor swap risk** — Grafana Cloud's pricing changes / Open Decision #12 reverses the default | OTLP is a vendor-neutral protocol; the swap is a within-`:infra:otel` config change. Endpoint + token are externalized to GCP Secret Manager, so the vendor swap doesn't touch business code. Document the swap shape as a section in `docs/07-Operations.md` (out-of-scope here; flagged as a follow-up if the vendor decision flips before this change archives). |
| **Cold-start regression** — adding the OTel SDK to the JVM startup path increases p99 cold-start | Mitigated by the SDK-not-agent choice (D2). Add a benchmark assertion in tasks.md: cold-start measured with vs without OTel must show <200ms regression on a representative `Application.module()` startup. If exceeded, follow-up: defer SDK init to a coroutine post-first-request (lazy init pattern). |
| **PII regression via a future writer** (e.g., a new module setting `query.text` = raw search query) | The forbidden-attributes list is a positive spec requirement. Code review remains the primary enforcement until the optional Detekt rule (D10) lands as a follow-up. |
| **Phase 2 §14 benchmark depends on this** — if OTel ships broken, the benchmark also blocks | Validation-by-staging-soak: after this change deploys to staging, the operator runs a representative load (5-min `wrk` against the timeline endpoint) and verifies traces appear in Grafana Tempo with the expected attributes. A staging-soak failure blocks production rollout. |

## Migration Plan

This is a backend-only change with zero schema migrations. Rollout:

1. **Local dev**: scaffold module, wire bootstrap + helpers, run `./gradlew :backend:ktor:run` locally → confirm `event="otel_exporter_disabled"` INFO line appears, no traces sent (no-op exporter).
2. **CI**: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` passes. Test suite verifies: bootstrap initializes idempotently; sampling profile decisions on synthetic spans; `UserIdHasher` round-trip stability; forbidden-attributes guards (the few sites we hand-instrument).
3. **Staging deploy**: PR merge → `main` → auto-deploy to `api-staging.nearyou.id`. After deploy, `gh pr comment` with a 5-min `wrk` soak result confirming traces appear in Grafana Tempo with expected attribute shape. Sampling = head 100% on staging, so all soak traces should appear.
4. **Production deploy**: gated behind the same git tag path (`v*` tag → manual approval → prod). The first prod deploy uses the 10%-base + force-keep production profile. Operator verifies a small sample of error traces and slow traces land in Tempo (force-keep working) and that base-ratio traffic is sampled to ~10% (cardinality math + Tempo's ingest counter).
5. **Rollback**: revert the `Application.module()` Koin wiring single-line change reverts the OTel SDK init. The `:infra:otel` module stays compiled but unused.

## Open Questions

- **Cold-start budget**: what's the actual cold-start p99 today before this change ships? Need a baseline measurement to assert against (see Risks). Tasks.md captures the pre-change measurement as a step.
- **`opentelemetry-instrumentation-ktor` package availability for Ktor 3.4.x**: confirm at task-execution time whether the official package supports our Ktor version. If not, the manual interceptor implementation is the fallback (D2 + first row in the Risks table).
- **Vendor decision (Open Decision #12) — flipping before archive**: if the vendor decision reverses to Honeycomb / Cloud Trace before this change archives, the migration plan reduces to a `OtelBootstrap` config-block edit + secret slot rename. No spec change needed (the spec is vendor-agnostic).
