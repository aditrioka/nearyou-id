## 1. `IpHasher` helper in `:infra:otel`

- [ ] 1.1 Create `infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/IpHasher.kt` modeled on the sibling `UserIdHasher.kt` — `object IpHasher { fun hash(ip: String): String }` returning the first 16 hex characters of `SHA-256(ip.toByteArray(StandardCharsets.UTF_8))`. Use `java.security.MessageDigest.getInstance("SHA-256")` and `String.format("%02x", ...)` per byte for the hex encoding (mirror `UserIdHasher`'s exact style). The function MUST `require(ip.isNotBlank())` before hashing — defensive guard against a `ClientIpExtractor` regression (per `specs/observability-otel-foundation/spec.md` ADDED Requirement § "Blank input fails fast" scenario).
- [ ] 1.2 Create `infra/otel/src/test/kotlin/id/nearyou/app/infra/otel/IpHasherTest.kt` mirroring `UserIdHasherTest`. Test scenarios (8 total, one per spec scenario): deterministic, distinct-IPs-distinct-output, exact 16-hex shape, 1000-random-IPv4-shape sanity, IPv6 input (`2001:db8::1`), blank-input-fails-fast (`shouldThrow<IllegalArgumentException>` for `""`, `" "`, `"\t"`), whitespace-not-trimmed (`hash("1.2.3.4 ") != hash("1.2.3.4")`), IPv6 case sensitivity (`hash("2001:DB8::1") != hash("2001:db8::1")`). Test fixtures MUST NOT pin literal hex values — compute expectations dynamically via `IpHasher.hash(...)` so a future digest-function change is a single-source edit (consistent with `UserIdHasherTest` precedent). Add a file-level KDoc note: "no collision-rate test by design (see `design.md` D7)".
- [ ] 1.3 Add `LettuceSpanCaptureTest` to `infra/redis/src/test/kotlin/id/nearyou/app/infra/redis/LettuceSpanCaptureTest.kt` (Testcontainers `redis:7-alpine` + the existing `infra/otel/src/testFixtures/.../SpanRecorder.kt` fixture). Construct a Lettuce `RedisClient` configured with `OpenTelemetryTelemetry`, invoke a Lua-script `EVALSHA` call with key `"{scope:health}:{ip:${IpHasher.hash("1.2.3.4")}}"`, and assert the captured `EVALSHA` span's `db.statement` attribute contains the hashed form `{ip:[0-9a-f]{16}}` substring AND does NOT contain the literal `"1.2.3.4"`. Closes the BLOCKING coverage gap per `specs/observability-otel-foundation/spec.md` § "No raw client IP appears in Lua key on EVALSHA span" scenario (round-1 review B1).
- [ ] 1.4 Run `./gradlew :infra:otel:test :infra:redis:test` and confirm 8 new `IpHasherTest` scenarios + the new `LettuceSpanCaptureTest` pass alongside the 4 existing `UserIdHasherTest` scenarios.

## 2. Wire `IpHasher` into the `/health/*` rate-limit call site

- [ ] 2.1 Open `backend/ktor/src/main/kotlin/id/nearyou/app/health/HealthRoutes.kt:159` (verified call site — current form: `"{scope:health}:{ip:${call.clientIp}}"` with block interpolation).
- [ ] 2.2 Refactor the call site to **hoist `val hashedIp = IpHasher.hash(call.clientIp)` BEFORE the key literal** so the literal uses simple-name interpolation `"{scope:health}:{ip:$hashedIp}"`. Direct block interpolation `"{scope:health}:{ip:${IpHasher.hash(call.clientIp)}}"` triggers a `RedisHashTagRule` block-interpolation false-positive (per `lint/detekt-rules/.../RedisHashTagRule.kt` KDoc) — round-1 security review S2 catches this. Add `import id.nearyou.app.infra.otel.IpHasher` to the file. Verify the call site still passes `capacity = 60` and `ttl = Duration.ofSeconds(60)` unchanged.
- [ ] 2.3 Update the corresponding test in `:backend:ktor`. Existing `HealthRoutesScenariosTest.kt:355-365` uses a prefix-match because the test-client IP is platform-variable. Pick **approach (a) preferred**: inject `header("CF-Connecting-IP", "1.2.3.4")` on the test request so the hash is computable; assert the full key equals `"{scope:health}:{ip:${IpHasher.hash("1.2.3.4")}}"` (computed dynamically, not pinned). **Approach (b) fallback** if header injection proves brittle: keep the prefix-match shape but tighten the regex to `^\{scope:health\}:\{ip:[0-9a-f]{16}\}$` to lock the post-hashing shape. Round-1 test-coverage review S1.
- [ ] 2.4 Run `./gradlew :backend:ktor:test --tests '*Health*'` and confirm the suite passes.

## 3. Spec amendments — verify alignment

- [ ] 3.1 Re-read [`openspec/changes/rate-limit-ip-hashing/specs/rate-limit-infrastructure/spec.md`](specs/rate-limit-infrastructure/spec.md) and confirm the MODIFIED requirement copies the entire shipped requirement block from [`openspec/specs/rate-limit-infrastructure/spec.md`](../../specs/rate-limit-infrastructure/spec.md) lines 30-93, with the IP-axis sentence amended at line 58 and the new `IP-axis key shape uses hashed IP, never raw` scenario added.
- [ ] 3.2 Re-read [`openspec/changes/rate-limit-ip-hashing/specs/health-check/spec.md`](specs/health-check/spec.md) and confirm the MODIFIED requirement copies the entire shipped `/health/* rate-limit at 60 req/min per IP` requirement block from [`openspec/specs/health-check/spec.md`](../../specs/health-check/spec.md) lines 103-143, with the key-shape sentence amended at line 107, the assertion at line 131 updated, and two new scenarios added (`Same IP hashes to same key` + `Distinct IPs hash to distinct keys`).
- [ ] 3.3 Re-read [`openspec/changes/rate-limit-ip-hashing/specs/observability-otel-foundation/spec.md`](specs/observability-otel-foundation/spec.md) and confirm: (a) ADDED `IpHasher` requirement is new (no precedent in the shipped spec), (b) MODIFIED `Forbidden span attributes` requirement copies the shipped block from [`openspec/specs/observability-otel-foundation/spec.md`](../../specs/observability-otel-foundation/spec.md) lines 184-235 with the IP bullet at line 188 amended and the new `No raw client IP appears in Lua key on EVALSHA span` scenario added.
- [ ] 3.4 Run `openspec validate rate-limit-ip-hashing --strict` and confirm green.

## 4. Pre-commit verification

- [ ] 4.1 Run `./gradlew ktlintCheck detekt` and confirm both pass on the touched files (`IpHasher.kt`, `IpHasherTest.kt`, the call site, the call-site test).
- [ ] 4.2 Run `./gradlew :infra:otel:test :backend:ktor:test :lint:detekt-rules:test` (the pre-push verification suite per `CLAUDE.md` § Pre-push verification) and confirm all green.
- [ ] 4.3 Run `openspec validate rate-limit-ip-hashing --strict` one final time before committing.

## 5. Commit + push

- [ ] 5.1 Stage only the touched files (`IpHasher.kt`, `IpHasherTest.kt`, the call site `.kt`, the call-site test `.kt`, and any updated `tasks.md` checkbox state). Do NOT `git add -A`.
- [ ] 5.2 Commit with `feat(rate-limit): hash client IP in rate-limit Lua keys` and a body summarizing: (a) why (PII leak via `db.statement` Tempo span attribute + `key=` log field), (b) what (introduce `IpHasher` + consume at `/health/*` call site), (c) impact (one-time per-IP slot reset at deploy; 60-second TTL means ~1-minute transition).
- [ ] 5.3 Push to the existing change branch (do NOT open a new PR — the existing `rate-limit-ip-hashing` branch carries the full lifecycle per `openspec/project.md` § Change Delivery Workflow).
- [ ] 5.4 Update the PR title via `gh pr edit <pr> --title 'feat(rate-limit): rate-limit-ip-hashing'` (retitle from the proposal phase).
- [ ] 5.5 Update the PR body via `gh pr edit <pr> --body "$(cat <<'EOF' ... EOF)"` to reflect the in-progress implementation phase per the same project doc.

## 6. Pre-archive smoke (manual, against branch staging deploy)

- [ ] 6.1 Trigger the staging deploy on the change branch via `gh workflow run deploy-staging.yml --ref rate-limit-ip-hashing` and poll the deploy run until green.
- [ ] 6.2 Issue 5 sequential requests to `https://api-staging.nearyou.id/health/live` from a single test client. Capture the `traceId` of any one of them via the response header (or via Cloud Logging on the Cloud Run service).
- [ ] 6.3 In Grafana Tempo, look up the captured `traceId`. Drill into the inner `EVALSHA` Lettuce span on the `/health/live` request span tree.
- [ ] 6.4 Inspect the `db.statement` attribute on the `EVALSHA` span. **Assert**: the value contains `{ip:[0-9a-f]{16}}` (hashed form), NOT the raw IPv4 dotted-quad of the test client. Capture a screenshot or paste-text of the attribute value into the PR comment thread for the change.
- [ ] 6.5 In Cloud Logging, filter for the `key=` field on rate-limit log entries from the same time window. **Assert**: the `key=` value contains the same `{ip:[0-9a-f]{16}}` hashed form, no raw IP literal.
- [ ] 6.6 If 6.4 OR 6.5 fail (raw IP still present): roll back the call-site change on the branch, debug, and retry. Do NOT proceed to archive until both surfaces are clean.

## 7. Archive

- [ ] 7.1 Run `openspec archive rate-limit-ip-hashing` locally — verify the move from `openspec/changes/` → `openspec/changes/archive/` and the spec sync into `openspec/specs/`.
- [ ] 7.2 Run `openspec validate --specs rate-limit-infrastructure --strict` and `openspec validate --specs health-check --strict` and `openspec validate --specs observability-otel-foundation --strict` to confirm the post-archive specs are clean.
- [ ] 7.3 Stage the archive changes (move + spec sync) and commit on the SAME branch with `chore(openspec): archive rate-limit-ip-hashing`.
- [ ] 7.4 Push the archive commit + update the PR body via `gh pr edit <pr> --body "$(cat <<'EOF' ... EOF)"` to the merge-ready shape per `openspec/project.md` § Change Delivery Workflow.
- [ ] 7.5 Final squash-merge to `main` after CI green — produces ONE commit on `main` carrying proposal + feat + archive.

## 8. Post-merge verification (production gate)

- [ ] 8.1 After staging auto-deploy from `main` post-squash, repeat the smoke verification from Section 6 against `main`-deployed staging (`api-staging.nearyou.id`) — confirm Tempo + Cloud Logging surfaces remain clean.
- [ ] 8.2 Production deployment is OUT OF SCOPE for this change (no production deploy workflow exists yet). When production tag-deploy lands, this change MUST be already-merged AND the smoke from 8.1 MUST be green. Mark this task N/A in the archive commit body.
