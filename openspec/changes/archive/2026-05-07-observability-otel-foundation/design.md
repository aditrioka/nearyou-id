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

**Decision**: Create a fresh `infra/otel/` Gradle module with its own `build.gradle.kts` and namespace `id.nearyou.app.infra.otel` — matching the existing infra-module package convention (`infra/fcm/src/main/kotlin/id/nearyou/app/infra/fcm/`, `infra/redis/src/main/kotlin/id/nearyou/app/infra/redis/`, etc.).

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

**Decision**: Default exporter is Grafana Cloud Tempo via OTLP/HTTP (NOT OTLP/gRPC). Endpoint + HTTP Basic auth credential read from GCP Secret Manager via `secretKey(env, "otel-grafana-otlp-token")` and `secretKey(env, "otel-grafana-otlp-endpoint")`. The token slot value is the base64-encoded `<instance_id>:<api_token>` string generated by Grafana Cloud's OTLP wizard; `OtelBootstrap` prepends the `Basic ` scheme at request time. The Open Decision #12 vendor swap is encapsulated entirely within `:infra:otel` — `OtelBootstrap.kt` is the only file that knows the vendor name AND the auth scheme (Honeycomb / Cloud Trace use different auth shapes that would change in this single file).

**Rationale**:
- OTLP/HTTP works through HTTP-only Cloud Run egress without needing gRPC connection setup; cold-start friendly.
- Grafana Cloud Tempo speaks OTLP natively — zero protocol translation.
- Per [`docs/08-Roadmap-Risk.md:586`](../../../docs/08-Roadmap-Risk.md), Grafana Cloud is the **recommended default**. Honeycomb / Cloud Trace are also OTLP-compatible; the swap is a `OtelBootstrap` config-block change, not a code refactor.
- Cost projection at [`docs/01-Business.md:381,455`](../../../docs/01-Business.md): 0 IDR at Phase 2 (free tier covers it); 0.2% of revenue at scale.

**Alternatives considered**:
- OTLP/gRPC. Rejected: gRPC adds cold-start overhead and Cloud Run + outbound gRPC has known idiosyncrasies. HTTP is the simpler default.
- Cloud Trace direct. Rejected: vendor lock-in to GCP; harder to swap.
- Honeycomb default. Rejected: paid from message one (no free tier comparable to Grafana Cloud's). Re-evaluate at swap-time.

### D4: Sampling profile = `Sampler.alwaysOn()` for dev/staging, `ParentBased(TraceIdRatioBased(0.1))` for production — NO force-keep promotion in this change

**Decision**: Production sampler is `ParentBased(TraceIdRatioBased(0.1))` only. **No force-keep promotion of error / slow spans is implemented in this change.** Staging + dev: `Sampler.alwaysOn()` (head 100%) for the Phase 2 §14 benchmark surface.

The canonical sampling profile at [`docs/05-Implementation.md:2042`](../../../docs/05-Implementation.md) prescribes "10% base + 100% errors + 100% slow (>500ms) in production" — and the canonical full target IS the eventual production shape. But the round-3 review surfaced that the only correct way to implement force-keep at production scale is **OTel Collector tail sampling**: a Collector receives all spans, applies tail-sampling rules (`status=ERROR` OR `duration>500ms` → keep; everything else → 10% sample), and forwards the survivors to Tempo. Doing the equivalent at the SDK level (recording all spans on a secondary `BatchSpanProcessor` and re-emitting force-keep candidates) requires either (a) detaching the secondary span via `setNoParent()` — which loses trace_id linkage and breaks Tempo's trace-tree view — or (b) holding all spans in memory until end-of-trace, which the SDK doesn't natively support without Collector-style tail sampling.

This change ships the foundation correctly (10% base, no force-keep) and defers the force-keep work to a focused follow-up (`observability-otel-collector-tail-sampling`). MVP production accepts that 90% of healthy spans drop AND that 90% of error/slow traces also drop — structured JSON logging at 100% retention via Cloud Logging continues to be the authoritative surface for incident replay until the Collector lands.

**Rationale**:
- The naive SDK-level force-keep (secondary `BatchSpanProcessor`, force-keep `SpanProcessor` re-emitting via `setNoParent()`) silently breaks trace correlation. Round-3 review (codebase-grounded) caught this. Shipping it would create a real correctness bug in production telemetry the first time an operator clicks into an error trace and finds it isolated from the rest of the request flow.
- An OTel Collector deployment is meaningful infrastructure work (Cloud Run sidecar or separate Collector service, IAM grants, sampling-rule config). Forcing it into this change would expand scope beyond what the canonical foundation needs.
- Phase 2 §14 benchmark uses staging at head-100% sampling — force-keep is irrelevant for the benchmark workflow.
- Cloud Logging at 100% retention covers incident replay during the gap. Reduced trace fidelity in production is acceptable transitional behavior; data quality on the spans we DO export is preserved.

**Alternatives considered**:
- Always-on + tail sampling at OTel Collector inline in this change. Rejected: scope expansion. Tracked as `observability-otel-collector-tail-sampling` follow-up.
- Naive SDK-level force-keep (the round-1 design). **Rejected after round-3 codebase grounding** — `setNoParent()` loses trace_id linkage; force-kept traces would not join the original error trace tree in Tempo. Real correctness bug.
- Always-on in production at full volume. Rejected: cost overrun risk + cardinality at scale; the canonical doc explicitly mandates 10% base.

### D5: `user.id` attribute is SHA-256 hashed via the project-wide `UserIdHasher.hash(uuid)` helper, NEVER raw

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

### D7: Exporter secret absence is a clean no-op (no startup failure); secret resolution uses the existing `secretKey` + `SecretResolver.resolve` two-step pattern

**Decision**: `:infra:otel`'s `OtelBootstrap.start(env, secretResolver)` derives slot names via `secretKey(env, name)` (which simply prefixes `staging-` for the staging env per [`backend/ktor/.../config/Secrets.kt`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/config/Secrets.kt)) and resolves their VALUES via `SecretResolver.resolve(slotName)` — the same two-step pattern Application.kt uses for every other secret today. When EITHER `SecretResolver.resolve(...)` lookup returns null (local dev, unconfigured staging, or a misconfigured production), `OtelBootstrap` initializes a `LoggingSpanExporter` (DEBUG severity, dropped by default Logback config) and emits a single startup INFO log: `event="otel_exporter_disabled" reason="<endpoint_missing|token_missing>" sampling_profile="<profile>"`. Spans are still created in-memory but never reach a network endpoint.

The deterministic precedence for the EITHER-absent case is: check endpoint first; if endpoint is null, emit `reason="endpoint_missing"` and return. Only if endpoint is present is the token checked. This locks the reason field across runs (a dashboard or alerting query against `event="otel_exporter_disabled" reason="endpoint_missing"` is stable).

**Rationale**:
- The codebase already uses a two-step secret pattern (`secretKey(env, name)` builds the slot name; `SecretResolver.resolve(name)` returns the nullable value). Round-3 review caught that the round-1 spec assumed `secretKey` itself was nullable — wrong. This decision aligns the spec with codebase reality.
- Forcing an exporter token in dev is friction. Devs running `supabase start` + `./gradlew :backend:ktor:run` shouldn't need a Grafana Cloud account.
- Forcing an exporter token in CI is friction (CI tests run thousands of times; a missing token would block CI on something orthogonal).
- The single startup INFO line is observable enough that an operator deploying to production with missing secrets would immediately see "OTel exporter is disabled in production" in Cloud Logging — fail-loud-but-don't-crash.
- Deterministic precedence on EITHER-absent makes alerting queries stable.

**Alternatives considered**:
- Crash on missing token. Rejected: fragile in dev; over-restrictive.
- Always-export-to-stderr. Rejected: log-scraping noise.
- Treat both-absent as a single "secrets_missing" reason. Rejected: less precise for ops triage.

### D8: FCM dispatch gets the LOCAL `withSpan("fcm.dispatch", ...)` wrap only; cross-service `traceparent` injection is DEFERRED

**Decision**: The Firebase Admin SDK FCM send (`FirebaseMessaging.send(message)`) gets wrapped in `:infra:otel`'s `withSpan("fcm.dispatch", attributes) { ... }` so the LOCAL span captures FCM dispatch latency, status, and the WARN-log↔span-event pairing per the modified `fcm-push-dispatch` spec. **Cross-service `traceparent` propagation to Google's FCM endpoint is deferred** to a focused follow-up change (`observability-otel-fcm-traceparent`).

The deferral is shaped by the round-3 review finding that the current `:infra:fcm` API does NOT surface `FirebaseOptions.Builder.setHttpTransport(...)` to `:backend:ktor`. The `buildFcmComposite(...)` factory consumed by Application.kt:373 owns Firebase Admin SDK initialization internally; threading a `TracingHttpTransport` through requires either (a) modifying `:infra:fcm`'s public API to accept an HttpTransport parameter, or (b) defining a wiring contract where `:infra:otel` injects itself into `:infra:fcm`'s init flow. Both are real architectural decisions that deserve a focused proposal — not an inline patch on this change.

The local span alone is still useful: it captures dispatch latency for Phase 2 §14 benchmarking, exposes the WARN-log↔span-event pairing required by the modified `fcm-push-dispatch` spec, and queries cleanly in Tempo. What's missing is the cross-service hop into Google's own Cloud Trace surface — the user's chat send trace stops at FCM dispatch, rather than continuing into FCM's delivery infrastructure. That's a degraded-but-acceptable transitional shape until the follow-up ships.

**Rationale**:
- Round-3 implementation-realism review found that `:infra:fcm`'s current API doesn't expose the transport hook — the integration would force a `:infra:fcm` API change, which deserves its own focused proposal.
- The local span captures the most operationally relevant data (dispatch latency + failure-mode pairing) without the API expansion.
- Cloud Logging at 100% retention covers cross-service correlation today (Cloud Run logs + Cloud Trace logs are timestamp-correlatable) until the propagation follow-up lands.

**Alternatives considered**:
- Inline the `:infra:fcm` API change in this proposal. Rejected: scope expansion; deserves focused review per the round-3 finding.
- Skip FCM tracing entirely (no local span either). Rejected: the WARN-log↔span-event pairing requirement in the modified `fcm-push-dispatch` spec needs the local span to record events on.
- Forking the FCM Admin SDK. Rejected: maintenance overhead.

### D9: Spec-modification scope is `chat-realtime-broadcast` + `fcm-push-dispatch`; `internal-endpoint-auth` is left unchanged AND the `user.id` requirement does NOT extend to `/internal/*`

**Decision**: This change modifies two existing capability specs (`chat-realtime-broadcast` to drop the explicit deferral + add positive pairing; `fcm-push-dispatch` to add WARN-log↔span-event pairing). `internal-endpoint-auth` is NOT modified, AND **the new capability's `user.id` requirement explicitly applies only to `UserPrincipal`-backed authentication**. `/internal/*` requests authenticated via Cloud Scheduler service-account OIDC do NOT receive the `user.id` span attribute in this change — there is no `users` row to hash.

A sanctioned `service.account.id` shape (truncated SHA-256 of the OIDC `sub` claim) for `/internal/*` requests is **deferred** to a focused follow-up (`internal-endpoint-auth-otel-attributes`). That follow-up modifies the `internal-endpoint-auth` spec to require the attribute and ships the implementation. Until then, `/internal/*` server spans carry `http.route` and `http.status_code` (auto-instrumentation) but no principal-correlation attribute.

The auto-instrumented Ktor server span on `/internal/*` requests already carries `http.status_code = 401` on a verifier failure — the operator-queryable signal is already there. Extending the foundation now to bake in `service.account.id` would require either inventing the truncated-SHA-256 shape inline (round-3 caught that the spec requires `user.id` "when authenticated" but contradicts itself by forbidding raw JWT claims, leaving the OIDC sub case unaddressed) or defining the shape correctly in a focused proposal — the latter is right.

**Rationale**:
- Spec-modification scope discipline: only modify a spec when the underlying behavioral contract changes. The `user.id` requirement applies cleanly to `UserPrincipal` requests; the OIDC service-account case is structurally different (different principal type, different identifier shape) and deserves its own focused spec.
- `internal-endpoint-auth` is a security-critical spec; minimizing churn while the `service.account.id` shape is being designed is good hygiene.

**Alternatives considered**:
- Define `service.account.id` inline. Rejected: cross-spec contract that deserves focused review.
- Apply `user.id = UserIdHasher.hash(serviceAccountUuid)` to `/internal/*`. Rejected: the OIDC `sub` is an email-like string, not a UUID; the helper signature is wrong for it.
- Modify `internal-endpoint-auth` spec to add the cross-cutting attribute requirement. Rejected: same reason as the original D9 — `internal-endpoint-auth` is auth-only; observability attributes belong in the cross-cutting capability + a per-spec extension when there's something to add.

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
