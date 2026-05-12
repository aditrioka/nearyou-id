## Context

The `observability-otel-foundation` capability shipped at PR [#66](https://github.com/aditrioka/nearyou-id/pull/66) (2026-05-07) with a deliberately deferred carve-out: `/internal/*` server spans do NOT carry a principal-correlation attribute. The deferral was because (a) the canonical `user.id` attribute requires a `users` table row to hash, which Cloud Scheduler service-account OIDC requests do not have; (b) the obvious fallback — using the OIDC `sub` claim directly — is forbidden by the same capability's `OtelForbiddenAttributeRule` (Tier 1 Group C blocks raw `jwt.sub` / `jwt.aud` / `jwt.iss` attributes); and (c) the foundation change was already large, so a sanctioned alternative was punt-and-document.

Three independent correlation IDs already exist in the project, all using the same 16-hex truncated SHA-256 OUTPUT shape but differing by INPUT:

| Correlation ID | Input | Helper | Surface | Purpose |
|---|---|---|---|---|
| `UserIdHasher.hash(uuid)` | `users.id` UUID | [`infra/otel/.../UserIdHasher.kt`](../../../infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/UserIdHasher.kt) | `user.id` span attribute, structured logs | per-end-user principal correlation |
| `IpHasher.hash(ip)` | `clientIp` string | [`infra/otel/.../IpHasher.kt`](../../../infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/IpHasher.kt) | `{ip:<hashed>}` rate-limit-key segment | per-IP rate-limit bucketing + log/key anonymisation |
| WARN-log token correlation | raw token bytes | inlined in [`internal-endpoint-auth/spec.md:20`](../../../openspec/specs/internal-endpoint-auth/spec.md) | WARN log only | per-token failure correlation |

The new `ServiceAccountIdHasher.hash(sub)` slots into the same family — same 16-hex output shape, different input source (the OIDC `sub` claim). The attribute name on the span is `service.account.id`, the placeholder name reserved by the foundation spec at line 152.

## Goals / Non-Goals

**Goals:**

- Close the `observability-otel-foundation` carve-out by adding the sanctioned `service.account.id` attribute to every successful `/internal/*` server span.
- Maintain the architectural invariant that raw JWT claims (`sub`/`aud`/`iss`) NEVER appear on spans — the only sanctioned anonymisation shape for the `sub` claim is the new helper's 16-hex truncated SHA-256.
- Keep the helper / writer surface analogous to `UserIdHasher` + `IpHasher` so future contributors see the same shape.
- Make the attribute writer best-effort: if the OTel SDK is uninitialised (test contexts, local dev without OTLP secret), the attribute write SHALL fail silently — it MUST NOT block the auth gate or the handler dispatch.
- Resolve the OTel-semconv-name ambiguity flagged in the FOLLOW_UPS entry by picking `service.account.id` (the foundation-reserved name) with explicit alternatives considered.

**Non-Goals:**

- A span attribute for the WARN-log token correlation id (`SHA-256(raw token bytes)`). Different purpose, different surface, deliberate separation.
- Vendor-webhook route coverage (`/internal/revenuecat-webhook`, `/internal/csam-webhook`). Those don't use OIDC; their `sub`-equivalent doesn't exist; future change if needed.
- Cross-service `traceparent` propagation INTO `/internal/*` from Cloud Scheduler. Cloud Scheduler doesn't inject `traceparent` today; trace lineage starts at the server-span boundary.
- Adding `service.account.id` typo-defensive entries (`service_account_id`, etc.) to `OtelForbiddenAttributeRule`. Future change if regression risk surfaces.
- Mobile-side enforcement. Mobile is currently DESIGN per [`openspec/project.md`](../../../openspec/project.md) § Mobile + Admin Status; once mobile makes /internal/* equivalents, the same pattern extends.
- Modifying the WARN-log token correlation id pattern at [`internal-endpoint-auth/spec.md:20`](../../../openspec/specs/internal-endpoint-auth/spec.md). That serves a different purpose; both correlation IDs co-exist intentionally.

## Decisions

### Decision 1: Attribute key — `service.account.id`

**Choice:** Use `service.account.id` as the span attribute key. This is the name reserved by the foundation spec at [`observability-otel-foundation/spec.md:152`](../../../openspec/specs/observability-otel-foundation/spec.md).

**Alternatives considered:**

| Option | Pros | Cons |
|---|---|---|
| `user.id` (extend the existing key with a different value source) | reuses existing pipeline + lint allowlist | semantically wrong (a service account is NOT a user); breaks the foundation's "user.id is for `UserPrincipal`-backed identity" requirement |
| `service.account.id` (foundation-reserved, **chosen**) | matches reserved-name commitment; clear semantics; project-namespaced under standard `service.*` root | not in OTel semconv 1.30.0 stable (project-namespaced extension) |
| `enduser.id` (historical OTel semconv name) | exists in semconv | semantically about end-user, not service-account; deprecated in newer OTel semconv versions in favour of `user.id` |
| `cloud.account.id` (OTel semconv) | exists in semconv | semantically about the cloud account (e.g., GCP project number), not the per-service-account principal |
| `nearyou.principal.id` (project-namespaced) | unambiguously project-owned | breaks the foundation's reserved-name commitment; introduces a new namespace prefix for one attribute |

**Rationale for `service.account.id`:**

- The foundation spec at line 152 explicitly reserves the name. Honouring that reservation keeps the cross-spec contract tight.
- OTel semconv allows organisation-specific attributes under any namespace; `service.account.id` is a reasonable extension under the standard `service.*` root (siblings of `service.name`, `service.instance.id`, `service.namespace`, `service.version`).
- Operator-side query consistency: an operator looking at the foundation's `user.id` attribute and the new `service.account.id` attribute sees parallel naming, parallel hashing helpers (`UserIdHasher` / `ServiceAccountIdHasher`), parallel 16-hex shape. Mental model is unified.

**Trade-off accepted:** if OTel semconv ever adopts a different canonical name for this concept (e.g., `service.principal.id`), this change requires a one-line attribute-key rename + spec amendment. Mitigated by the fact that OTel semconv has not flagged this concept as in-progress for the 1.x stable line.

### Decision 2: Input source — OIDC `sub` claim (NOT raw token bytes)

**Choice:** The hasher input is the verified OIDC `sub` claim — concretely, the `String`-valued `sub` field of the `VerifiedClaims` data class returned by `OidcTokenVerifier.verify(...)` and stored at `InternalEndpointAuth.kt:79` into `OidcSubjectKey`. For Google-issued OIDC tokens, the `sub` claim is a 21-digit numeric string (the service account's stable numeric uniqueId per Google OIDC docs); the human-readable email lives in a separate `email` claim that the verifier does NOT extract. (An earlier draft of this design doc said `sub` could be either the email or the numeric UID — that was incorrect; per Google OIDC spec only the numeric UID appears in `sub` for SA tokens.) NOT the raw token bytes.

**Alternatives considered:**

- (α) `SHA-256(raw token bytes)` — matches the existing WARN-log token correlation id at [`internal-endpoint-auth/spec.md:20`](../../../openspec/specs/internal-endpoint-auth/spec.md).
- (β) `SHA-256(sub claim)` — **chosen**.
- (γ) `SHA-256(sub + aud)` — composite key.

**Rationale for `SHA-256(sub claim)`:**

- **Stability per principal.** A service account hitting the endpoint at two different times produces two different tokens (different `iat`, `exp`, `nbf`, `jti`) but the same `sub`. Hashing the token bytes would produce a NEW correlation id every minute (token TTL is ~1 hour); hashing the `sub` claim is stable per principal across the SA's lifetime. Stability is the operator's primary value: "show me all `/internal/unban-worker` invocations for service account X" is the canonical query.
- **Different purpose from WARN-log.** The WARN-log uses `SHA-256(raw token bytes)` because its purpose is per-token failure correlation: "this specific bad token was seen at this time, here's a stable id to correlate with the Cloud Scheduler invocation that produced it." That's a per-token id, not a per-principal id. The two correlation IDs are intentionally different shapes for different telemetry purposes; both co-exist on the verified-request hot path (WARN log fires on FAILED verification; span attribute fires on SUCCESSFUL verification).
- **Privacy-equivalent**: `sub` is the GCP service-account principal identifier — a non-PII value identifying a service account, not a human. Per [`CLAUDE.md`](../../../CLAUDE.md) § Critical invariants → "Public repository posture": *"Slot names + GCP project IDs + service-account emails are non-sensitive."* Hashing provides operational benefit (stable per-principal correlation; lower cardinality bucketing in Tempo's indexed-attribute store) without confidentiality intent — an attacker with read access to Tempo + knowledge of the GCP project name can dictionary-attack the 16-hex hash to recover the SA name in seconds (the SA inventory is small, ~5 SAs per project, and the naming convention is stable per `<sa-name>@<project>.iam.gserviceaccount.com`). This is acceptable because the SA name itself is non-sensitive; the hash is for cardinality bucketing, NOT confidentiality.

The composite `SHA-256(sub + aud)` (option γ) was considered to disambiguate "same SA hitting two different services" but rejected because (a) the foundation spec already requires `service.name` as a resource attribute (so the audience is implicit in the trace context), and (b) `aud` is configured to a single Cloud Run service URL per environment, so within a given trace `aud` is constant.

### Decision 3: Output shape — 16-hex truncated SHA-256 (mirror existing helpers)

**Choice:** `ServiceAccountIdHasher.hash(sub: String): String` returns the first 16 hex characters of `SHA-256(sub.toByteArray(StandardCharsets.UTF_8))`. Identical OUTPUT shape to `UserIdHasher.hash(uuid)` and `IpHasher.hash(ip)`.

**Rationale:**

- Operator mental model: every truncated-SHA-256 correlation id in the project is the same shape — 16 hex chars, lowercase, exactly 64 bits of entropy. A regex `^[0-9a-f]{16}$` validates all three. Adopting a different shape (e.g., 32 hex / 24 hex / Base64) would force operators to context-switch.
- 64-bit collision space is overkill for a small-cardinality identifier surface (the project will have on the order of ~5 service accounts, ~hundred-thousand users, ~millions of distinct IP addresses across the user base). 16 hex = 2^64 ≈ 1.8e19 collision-resistance; collision probability is negligible at any anticipated scale.
- The helper signature mirrors `IpHasher.hash(ip: String): String` (same input type — non-blank string). Mirror precedent.
- The truncation length and digest function are fixed (changing them is an explicit follow-up change requiring a separate proposal). Same fixed-by-spec posture as `UserIdHasher.hash` and `IpHasher.hash`.

### Decision 4: Defensive guard — `require(sub.isNotBlank())`

**Choice:** The helper requires `sub.isNotBlank()` defensively — blank input is treated as a fail-fast bug.

**Rationale:**

- A blank `sub` would produce a deterministic single hash for ALL service accounts that somehow trigger the path with a blank sub — silently collapsing disparate service accounts into one shared correlation id. That would invert the entire purpose of the helper.
- The verified `OidcTokenVerifier` flow does NOT produce blank `sub` claims under any documented Google OIDC contract (Google's IDP guarantees `sub` is the service-account principal identifier, non-empty). A blank `sub` reaching this helper is a verifier-flow regression, not a production-traffic edge case.
- Mirror precedent: [`IpHasher.kt`](../../../infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/IpHasher.kt) implements an identical `require(ip.isNotBlank())` guard with the same rationale (blank IP would collapse disparate requests to a shared bucket — invert the limiter intent).

### Decision 5: Writer integration point — inside `InternalEndpointAuth` plugin, post-verification, pre-handler-dispatch

**Choice:** The attribute writer call lives inside the `InternalEndpointAuth` Ktor plugin's success branch at [`InternalEndpointAuth.kt:79`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/internal/InternalEndpointAuth.kt) — immediately after `call.attributes.put(OidcSubjectKey, claims.sub)`. The call shape mirrors the precedent at [`AuthPlugin.kt:113-120`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/auth/AuthPlugin.kt) byte-for-byte:

```kotlin
try {
    io.opentelemetry.api.trace.Span.current()
        .setAttribute("service.account.id", id.nearyou.app.infra.otel.ServiceAccountIdHasher.hash(claims.sub))
} catch (_: Throwable) {
    // Span recording failure MUST NOT block auth — observability is a side-effect surface.
    // Per spec § "Best-effort write silently no-ops on helper throw" + § "FailingSpanProcessor regression".
}
```

**Rationale:**

- **After verification, never before**: setting the attribute before verification would attach an unverified-principal id to a span that may correspond to a 401-rejected request. Spans for 401-rejected requests should carry `http.status_code = 401` only, with no principal-correlation surface (an attacker's failed request shouldn't enrich the trace surface with their attempted principal). Concretely: the writer goes immediately AFTER `call.attributes.put(OidcSubjectKey, claims.sub)` at line 79, INSIDE the same `try { val claims = verifier.verify(token); ... }` block but AFTER the `OidcSubjectKey` put. The 401 paths short-circuit via `respondText` in the `catch` blocks at lines 80-95, so the writer never executes on 401.
- **Before handler dispatch**: setting the attribute before the handler runs ensures the attribute is present on the server span BEFORE any handler-level child spans are created, so child spans can be queried by the parent's `service.account.id` via Tempo's standard parent-child attribute join.
- **`Try { ... } catch (_: Throwable) { ... }` wrapper covers TWO failure modes**: (a) the OTel SDK is uninitialised — `Span.current()` returns the no-op span, whose `setAttribute` is a defensive no-op (no throw); (b) the helper throws `IllegalArgumentException` on a blank `sub` value — REACHABLE in production because `GoogleOidcTokenVerifier.kt:90` substitutes `decoded.subject ?: ""` for missing `sub`; the catch-all swallows the throw cleanly so the auth gate is unaffected. The `Throwable` (not `Exception`) catch matches the `AuthPlugin.kt:116` precedent — defends against any future-introduced telemetry failure mode (SpanProcessor throws, exporter throws, etc.) without case-by-case enumeration.
- **Verified precedent location**: the foundation's `user.id` writer is at [`AuthPlugin.kt:113-120`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/auth/AuthPlugin.kt) — inlined into the AuthPlugin's UserPrincipal-construction branch, NOT a shared `:infra:otel` helper. This change follows the same pattern: inline the writer at the verified-claims-produced point, no shared helper.

### Decision 6: Server-span-level mutual exclusion with `user.id` — STRUCTURAL via route-scoped plugin installation, NOT via principal-type discriminator

**Choice:** A `/internal/*` Ktor SERVER span SHALL carry `service.account.id` and SHALL NOT carry `user.id`. Conversely, a `/api/v1/*` UserPrincipal-authenticated SERVER span carries `user.id` and SHALL NOT carry `service.account.id`. The mutual exclusion is span-by-span (server span only — child spans created by handlers may independently carry whichever attribute their child-span business requires). The mechanism is STRUCTURAL: the two writers are inlined into two different route-scoped plugins (`InternalEndpointAuth` for `/internal/*`; `AuthPlugin` for `/api/v1/*`); the plugins are installed on disjoint route subtrees; therefore the two writers cannot both fire on a single Ktor server span.

**Rationale:**

- **Why structural, not principal-type discriminator**: there is NO `OidcServicePrincipal` Kotlin type in the codebase. `InternalEndpointAuth.kt:79` stores the verified `claims.sub` into `OidcSubjectKey: AttributeKey<String>` — an Attribute, not a `Principal`. There is no `principal is X` discriminator available. An earlier draft of this design doc referenced `principal is OidcServicePrincipal` as the gate; that was a fabricated type (caught in Phase D round-1 multi-lens review and corrected here). The actual gate is "this code lives inside the `InternalEndpointAuth` plugin's success branch", which is structurally inaccessible from `/api/v1/*` routes (the plugin is route-scoped via `createRouteScopedPlugin`).
- **Why span-by-span, not request-by-request**: a request can produce many spans (server span + child spans for downstream calls). The constraint applies to the SERVER span only. A future `/internal/*` worker that hashes a target user ID via `UserIdHasher.hash(...)` for per-target correlation on a CHILD span (e.g., `withSpan("user.suspension_check") { setAttribute("user.id", UserIdHasher.hash(targetUserId)); ... }`) is permitted under this requirement — child spans are owned by the handler, not by the auth plugin.
- **Avoids confusion in operator queries**: a single-attribute discriminator on the server span (`exists(user.id)` vs `exists(service.account.id)`) cleanly answers "is this a user-driven request or a system-driven request?" at the request-entry surface. Child spans MAY mix attributes for legitimate reasons (downstream-target correlation), but the server span's principal-attribute is unambiguous.
- **Trade-off accepted**: the structural mutual-exclusion is enforced by route-mounting discipline, not by an `OtelForbiddenAttributeRule` lint check. A future contributor who adds `setAttribute("service.account.id", ...)` to `AuthPlugin.kt` (or `setAttribute("user.id", ...)` to `InternalEndpointAuth.kt`) would silently break the mutual-exclusion invariant. Mitigation: the spec's converse-mutual-exclusion scenario (`/api/v1/*` server span never carries `service.account.id`) and the original mutual-exclusion scenario (`/internal/*` server span never carries `user.id`) are both covered by integration tests; either regression would surface there.

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| OTel semconv adopts a different canonical name later (e.g., `service.principal.id`) | One-line attribute-key rename + spec amendment. The foundation-reserved name commitment is honoured today; future renames are cheap. |
| Operator confusion about which correlation id to use (WARN-log token-id vs span attribute service-account-id) | Documented explicitly in `design.md` § Decision 2 and in the spec scenarios. Two different correlation IDs for two different purposes. |
| `service.account.id` accidentally collides with a future OTel semconv key of the same name with different semantics | OTel semconv promotes new keys via published proposals; collision risk is observable months in advance. The project-namespaced positioning (under standard `service.*` root) is consistent with semconv's organisation-extension allowance. |
| Best-effort attribute write silently fails in production (active span unavailable for some unforeseen reason) | The writer's silent-fail posture is by design (mirror of the foundation's `user.id` writer). Failure surfaces as missing-attribute on operator-side queries; structured-log fallback at the WARN-level on verification failure remains; no auth or handler-dispatch impact. |
| `OtelForbiddenAttributeRule` flags the new attribute writer as a violation | The rule fences forbidden keys (raw IP, `jwt.sub`, etc.). `service.account.id` is the SANCTIONED key — not in the forbidden list. The hashed-via-helper consumption pattern (analogous to `UserIdHasher.hash(uuid)` → `user.id` attribute) passes the rule. Verified by inspection of the rule's Tier 1 Group A/B/C entries. |
| Helper input validation diverges from the verifier's contract (e.g., the verifier silently allows empty `sub`) | Defensive `require(sub.isNotBlank())` in the helper is a fail-fast guard. If a blank `sub` ever reaches the helper, the application-level exception is the correct surface (it indicates a verifier-flow regression). |
| Tests struggle to reach the OTel-active-span surface (Ktor test framework does NOT initialise OTel by default) | Mirror the foundation's existing test pattern: use the project's `SpanRecorder` / equivalent test fixture from `:infra:otel` to capture spans during a `testApplication { ... }` block. The integration test asserts the captured server span carries the attribute. The foundation already ships this fixture. |

## Open Questions

- **None at proposal time.** The design replicates an established precedent (`UserIdHasher` + `IpHasher` shape), addresses a single deferred carve-out (foundation spec line 152), and resolves the FOLLOW_UPS entry's OTel-semconv-name ambiguity in Decision 1 with explicit alternatives.
