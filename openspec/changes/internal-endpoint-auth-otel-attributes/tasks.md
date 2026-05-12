## 1. ServiceAccountIdHasher helper

- [ ] 1.1 Create `infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/ServiceAccountIdHasher.kt` mirroring the structural shape of [`UserIdHasher.kt`](../../../infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/UserIdHasher.kt) and [`IpHasher.kt`](../../../infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/IpHasher.kt). Single `object ServiceAccountIdHasher` with one function `fun hash(sub: String): String`.
- [ ] 1.2 Implement `hash(sub)` as: `require(sub.isNotBlank())` defensive guard, then return the first 16 hex characters of `SHA-256(sub.toByteArray(StandardCharsets.UTF_8))` (use `MessageDigest.getInstance("SHA-256")`, format via `joinToString("") { "%02x".format(it) }.substring(0, 16)` or equivalent). Mirror `IpHasher.hash` byte-for-byte except for the input-validation message (no IP-specific wording).
- [ ] 1.3 Add KDoc covering: helper purpose (per-principal correlation for `/internal/*` OIDC-auth requests), output shape (16-hex truncated SHA-256, mirror sibling of `UserIdHasher` and `IpHasher`), input source (the verified OIDC `sub` claim, NOT raw token bytes — link to the WARN-log token-correlation distinction at [`internal-endpoint-auth/spec.md:20`](../../../openspec/specs/internal-endpoint-auth/spec.md)), defensive-guard rationale (blank `sub` → `IllegalArgumentException` to avoid silent shared-bucket collapse), reference back to the spec at `openspec/specs/internal-endpoint-auth/spec.md` § "Requirement: `/internal/*` server spans carry `service.account.id`".

## 2. Helper unit tests

- [ ] 2.1 Create `infra/otel/src/test/kotlin/id/nearyou/app/infra/otel/ServiceAccountIdHasherTest.kt` mirroring [`UserIdHasherTest.kt`](../../../infra/otel/src/test/kotlin/id/nearyou/app/infra/otel/UserIdHasherTest.kt) and `IpHasherTest.kt` shape (Kotest `StringSpec` or whichever shape the existing tests use — verify during apply).
- [ ] 2.2 Implement Scenario "Hash is deterministic": call `hash(S)` twice with the same input, assert identical 16-hex output.
- [ ] 2.3 Implement Scenario "Hash differs between distinct inputs": call `hash(S1)` and `hash(S2)` with two distinct inputs, assert outputs differ.
- [ ] 2.4 Implement Scenario "Output shape is exactly 16 lowercase hex chars": call `hash(s)` on a representative input, assert `result.matches(Regex("^[0-9a-f]{16}$"))`.
- [ ] 2.5 Implement Scenario "Rejects blank input fail-fast": invoke `hash("")`, `hash(" ")`, `hash("\t\n")`, assert each throws `IllegalArgumentException`.
- [ ] 2.6 Implement parity test: assert `ServiceAccountIdHasher.hash("test")` matches an externally-computed reference value (e.g., the first 16 hex chars of `SHA-256("test".toByteArray(UTF-8))` = `9f86d081884c7d65...`); guards against accidental algorithm/encoding drift.

## 3. Attribute writer integration

- [ ] 3.1 Locate the existing `InternalEndpointAuth` Ktor plugin (or its companion handler) wiring in `:backend:ktor` `internal` package. Identify the precise code path that establishes the verified-principal context AFTER OIDC verification AND BEFORE handler dispatch — that is the canonical insertion point per `design.md` § Decision 5.
- [ ] 3.2 Add the attribute-writer call: `Span.current().setAttribute("service.account.id", ServiceAccountIdHasher.hash(verifiedToken.subject))` (or the project's wrapper helper if one exists — verify against the foundation's `user.id` writer for the canonical pattern). The call MUST be wrapped in a try/catch (or equivalent silent-fail mechanism) so OTel-SDK-uninitialised contexts (tests, dev) do NOT propagate exceptions to the auth gate or handler dispatch.
- [ ] 3.3 Verify the principal-type discriminator: the `user.id` writer (already shipped in `observability-otel-foundation`) is gated on `principal is UserPrincipal`; the new `service.account.id` writer MUST be gated on `principal is OidcServicePrincipal` (or whatever the post-verification principal type is in the existing internal-endpoint-auth implementation). The two writers MUST NOT both fire on a single request.
- [ ] 3.4 Wire the necessary `:infra:otel` dependency exposure on `:backend:ktor`'s `internal` package — verify via `backend/ktor/build.gradle.kts` that `:infra:otel` is already on the classpath (it should be from `observability-otel-foundation`); no new module dependencies are added by this change.

## 4. Plugin integration test

- [ ] 4.1 Create `backend/ktor/src/test/kotlin/id/nearyou/app/internal/InternalEndpointSpanAttributeTest.kt` (or extend the existing `InternalEndpointAuth*Test` if one exists — verify during apply) using `testApplication { ... }` + the project's existing `SpanRecorder` test fixture from `:infra:otel` (mirror the foundation's existing integration tests for `user.id` attribute capture).
- [ ] 4.2 Implement Scenario "Successful request produces span with hashed service.account.id": construct a valid OIDC token (test JWKS-signed) with `sub = "nearyou-cloud-scheduler@example.iam.gserviceaccount.com"`, send `POST /internal/unban-worker` with the token, capture the resulting server span, assert `service.account.id` attribute equals the expected hash AND the literal `sub` string does NOT appear anywhere on the span.
- [ ] 4.3 Implement Scenario "401-rejected request span carries no principal-correlation attribute": construct a token with an invalid signature (or expired `exp`), send the request, capture the resulting server span (the auth plugin should still produce a server span for the 401 response), assert NO `service.account.id` attribute AND NO `user.id` attribute.
- [ ] 4.4 Implement Scenario "service.account.id mutually exclusive with user.id": same as 4.2 but additionally assert `user.id` attribute is absent (the request never reached UserPrincipal-backed auth).
- [ ] 4.5 Implement Scenario "Best-effort write silently no-ops when OTel SDK uninitialised": construct a `testApplication { ... }` block that mounts `/internal/*` routes WITHOUT calling `OtelBootstrap.start(...)`, send a valid OIDC request, assert the handler dispatches normally AND no exception propagates AND the response is the handler's success body. This locks the silent-fail posture.
- [ ] 4.6 Implement Scenario "Vendor-webhook routes opt out and produce no service.account.id": send a request to a vendor-webhook route (e.g., `POST /internal/revenuecat-webhook` if it exists; use a stand-in if not), capture the server span, assert NO `service.account.id` attribute (the OIDC plugin never ran).

## 5. Lint + Detekt verification

- [ ] 5.1 Run `./gradlew detekt` locally — assert the `OtelForbiddenAttributeRule` does NOT fire on the new attribute writer call (`service.account.id` is the SANCTIONED key, not in the rule's forbidden list; the hashed-via-helper consumption pattern mirrors `UserIdHasher.hash(uuid)` → `user.id` and is the canonical sanctioned path).
- [ ] 5.2 Run the full pre-push verification per [`CLAUDE.md`](../../../CLAUDE.md) § "Pre-push verification": `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` — all four targets MUST pass before pushing.
- [ ] 5.3 Verify by inspection that the new helper is NOT added to `ForbiddenAttributeStripper.FORBIDDEN_KEYS` — the runtime stripper defends against accidentally-emitted forbidden keys; the new sanctioned attribute MUST flow through unstripped.

## 6. Spec + docs sync

- [ ] 6.1 Run `openspec validate internal-endpoint-auth-otel-attributes --strict` — ensure validation passes.
- [ ] 6.2 At `/opsx:archive` time: delete the [`FOLLOW_UPS.md`](../../../FOLLOW_UPS.md) entry `internal-endpoint-auth-otel-attributes` (lines ~79–96) — its scope is fully fulfilled by this change. If `FOLLOW_UPS.md` becomes empty after this deletion (unlikely given the other open entries), also delete the file itself per its intro-blurb convention.
- [ ] 6.3 Verify by grep that no other `docs/**` or `openspec/specs/**` cross-reference points at the deleted FOLLOW_UPS entry: `grep -rn "internal-endpoint-auth-otel-attributes" docs/ openspec/specs/` post-archive.
- [ ] 6.4 If `docs/04-Architecture.md` or `docs/06-Security-Privacy.md` § Observability sections enumerate "mandatory span attributes" or similar lists, verify they include `service.account.id` for `/internal/*` requests post-this-change. If they do not, either (a) add the attribute to the doc's enumeration in the same change, or (b) log a `FOLLOW_UPS.md` entry for the docs-amend if the doc enumeration is the canonical surface.

## 7. Pre-archive smoke

- [ ] 7.1 Manual staging deploy on the change branch via `gh workflow run deploy-staging.yml --ref internal-endpoint-auth-otel-attributes` (only after Section 5 + 6.1 pass on the branch).
- [ ] 7.2 Smoke verification: trigger a `/internal/*` invocation against staging (the existing `nearyou-staging-unban-worker` Cloud Scheduler job is the canonical test path, OR manually invoke via `gcloud run services call ...` with a valid OIDC token); fetch the resulting server span from the staging Grafana Tempo via the canonical `gcx` CLI; assert the span carries `service.account.id` AND the value matches the expected `ServiceAccountIdHasher.hash` of the staging Cloud Scheduler service-account `sub`. Document the verification command + result in this task entry at apply time.
- [ ] 7.3 Negative smoke: send a deliberately-bad OIDC token to a `/internal/*` route (or trigger an audience-mismatch); fetch the resulting 401-response server span; assert NO `service.account.id` attribute on the span (confirms the post-verification gate per Decision 5).
