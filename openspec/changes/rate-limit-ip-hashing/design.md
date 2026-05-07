## Context

The IP-axis rate-limit convention shipped with the `health-check-endpoints` change ([`openspec/specs/rate-limit-infrastructure/spec.md:58`](../../specs/rate-limit-infrastructure/spec.md), [`health-check/spec.md:107`](../../specs/health-check/spec.md)) constructs Lua keys in the form `{scope:health}:{ip:<addr>}` from the `clientIp` request-context value (populated by `ClientIpExtractor` reading `CF-Connecting-IP`). The raw IP literal embedded in the key surfaces in two telemetry channels:

1. **Tempo span attributes**: every Lettuce `EVALSHA` call carries `db.statement = "EVALSHA <hash> 1 {scope:health}:{ip:1.2.3.4} ? ? ? ? ?"` (auto-instrumentation). Captured at staging during the `observability-otel-foundation` task 8.3 verification — see `FOLLOW_UPS.md` § rate-limit-key-includes-raw-client-ip.
2. **Structured logs**: per [`rate-limit-infrastructure/spec.md:84`](../../specs/rate-limit-infrastructure/spec.md), `tryAcquireByKey` MUST log `key=<key>` for telemetry. The full key (including raw IP) lands in Cloud Logging.

App-side IP handling is properly gated — `RawXForwardedForRule` Detekt rule forbids direct `X-Forwarded-For` reads, and the `clientIp` request-context value is the only sanctioned source. But the rate-limiter's Lua-key construction bypasses that boundary, leaking the value into `db.statement` and `key=` log fields regardless.

**Pre-launch dependency**: at staging the leaked IP is a link-local LB IP (synthetic), but production routes through Cloudflare and the rate-limiter reads `CF-Connecting-IP` — that's a real customer IP. The project's UU PDP posture per [`docs/06-Security-Privacy.md`](../../../docs/06-Security-Privacy.md) § Privacy Compliance treats trace data as pseudonymous-by-default (line 309 third-party processor disclosure: "Grafana Cloud (OpenTelemetry trace backend — receives pseudonymous trace data: hashed user IDs, parameterized SQL, route patterns)"); raw IPs in `db.statement` violate that envelope.

The `observability-otel-foundation` spec already anticipated this: line 188 of the forbidden-attributes contract reads *"The raw client IP read from `CF-Connecting-IP` or `X-Forwarded-For`. (No truncated form is currently sanctioned; if needed, file a follow-up that introduces an `ip.cidr` truncated attribute.)"* — this change is that follow-up.

The current call site is the only IP-axis rate-limit consumer (`/health/*` 60 req/min cap, capacity 60, TTL 60 seconds). Layer 1 pre-issuance limits ([`docs/05-Implementation.md:1131-1134`](../../../docs/05-Implementation.md)) prescribe additional IP-axis buckets (`rate:guest_issue:{ip:<ip>}`, `rate:guest_issue_day:{ip:<ip>}`) — those are deferred to a future change but will adopt the same hashed convention introduced here.

## Goals / Non-Goals

**Goals:**
- Eliminate raw client IPs from Tempo span attributes (`db.statement` on `EVALSHA` spans) and structured log fields (`key=`).
- Introduce a single sanctioned anonymization shape for IP-axis rate-limit keys, mirroring the existing `UserIdHasher` precedent so operator mental models stay unified.
- Update the three affected capability specs (`rate-limit-infrastructure`, `health-check`, `observability-otel-foundation`) in lockstep so the convention is enforced end-to-end.
- Preserve the rate-limit semantics — same IP hashes to same key, distinct IPs hash to distinct keys (with overwhelming probability over 64-bit truncated SHA-256 + IPv4 input space).

**Non-Goals:**
- **Layer 1 pre-issuance buckets** (guest token issuance, fingerprint, global circuit-breaker per [`docs/05-Implementation.md:1131-1134`](../../../docs/05-Implementation.md)) are NOT in scope. They will adopt the convention when those features ship via separate changes; this change establishes the helper and the convention.
- **CIDR truncation / network-prefix anonymization** (`ip.cidr` attribute as referenced in `observability-otel-foundation/spec.md:188`) is NOT shipped. The simpler "hash the full address" approach gets us out of the privacy hole faster; CIDR-style attributes can be a separate follow-up if operators need network-level aggregation in Tempo.
- **A new Detekt rule** to enforce hashing at every IP-axis call site is deferred. The current call site count is 1; with the convention encoded in the spec scenario, code review is sufficient. The deferred `OtelForbiddenAttributeRule` (per `FOLLOW_UPS.md`) would naturally cover this once it ships.
- **IPv6 normalization** (e.g., `::1` vs `0:0:0:0:0:0:0:1`) is NOT performed by `IpHasher`. The `clientIp` value is whatever `ClientIpExtractor` returns from `CF-Connecting-IP`; Cloudflare normalizes per its own conventions. The hash is over the literal string so two semantically-equivalent forms could hash differently — acceptable because Cloudflare's emission is deterministic per request-edge, not "1.2.3.4 vs IP-Pv4-mapped IPv6 of same."
- **Re-keying historical buckets** is NOT in scope. The 60-second TTL on `/health/*` makes pre-existing slots irrelevant within ~1 minute of deploy.

## Decisions

### D1. `IpHasher` lives in `:infra:otel` next to `UserIdHasher`

`:infra:otel` is the existing home of `UserIdHasher`. Rate-limit call sites in `:backend:ktor` already depend on `:infra:otel` (the dependency is non-vendor; OTel is a foundation library, not a vendor SDK boundary). Co-locating preserves the "one place for telemetry-anonymization helpers" mental model.

**Alternatives considered:**
- `:common` — closer to the call site but `:infra:otel` already pinned the precedent. Splitting `UserIdHasher` and `IpHasher` across modules would invite drift.
- New `:infra:hashing` module — overkill for two helpers totaling ~30 LOC each.
- `:core:domain` — pure-Kotlin module without `java.security.MessageDigest` access via stdlib reliably; would force a multiplatform Hash abstraction we don't need yet.

**Why this matters**: future `service.account.id` (per the `internal-endpoint-auth-otel-attributes` follow-up) and any other anonymization helper will land in the same module, keeping the surface coherent.

### D2. Truncation length: 16 hex characters (64-bit truncated SHA-256)

Matches the existing `UserIdHasher` ([`observability-otel-foundation/spec.md:127-129`](../../specs/observability-otel-foundation/spec.md)) and the token correlation-id pattern from [`internal-endpoint-auth/spec.md:18`](../../specs/internal-endpoint-auth/spec.md). Operator mental model unified — same anonymization shape for user/IP/token correlation IDs.

**Alternatives considered:**
- 8 hex (32-bit) — collision probability over IPv4 space (~4B addresses) becomes non-negligible (~2^-1.8 collisions over 2^32 inputs vs 2^32 output space — birthday-paradox territory). Rate-limit correctness depends on collision-resistance.
- 12 hex (48-bit) — splits the difference, but no precedent in the codebase. Diverging from `UserIdHasher` for no payoff is friction.
- 32 hex (full SHA-256) — verbose; reduces span-attribute readability without security gain.

**Why 16 hex is correct**: 64-bit collision probability over the IPv4 address space (~4×10^9) is ~2^-32 per pair — well below noise floor for rate-limit correctness, while keeping span attributes scannable.

**IPv6 pigeonhole acknowledgment**: the IPv6 input space is 2^128 against a 64-bit output, so collisions are guaranteed by pigeonhole. In rate-limit semantics this is graceful-degradation (two IPv6 clients share a single bucket), not a security regression — the cap they share is the same cap they'd individually have, and the worst-case rate is `cap * 2` for the colliding pair. Acceptable today; if IPv6 traffic at scale shows pathological collisions, a follow-up can re-examine truncation length without breaking the existing IPv4 surface.

### D3. Digest function: SHA-256 (not BLAKE3, MD5, or murmur)

Matches `UserIdHasher` precedent. SHA-256 is well-tested, present in JDK stdlib (`MessageDigest.getInstance("SHA-256")`), and the spec explicitly documents that "the truncation length and digest function are fixed (changing them is an explicit follow-up change requiring a separate proposal)." We honor that pin.

### D4. No salt / pepper (deterministic hash)

Matches `UserIdHasher` (no salt). Rate-limit correctness requires that `hash(IP_X)` always returns the same value across requests so the bucket lookup stays consistent. A per-instance salt would invalidate slots on every Cloud Run revision rollover.

A static project-wide pepper (e.g., from `secretKey(env, "ip-hasher-pepper")`) would defend against a hypothetical adversary who has full Tempo read access AND knows the SHA-256 algorithm AND wants to look up specific IPs in span data — but the threat model here is "don't leak raw PII into telemetry," not "make it cryptographically infeasible to reverse-engineer." A pepper adds operational complexity (key rotation, multi-env consistency) for marginal security gain. **Skipped.**

**Small-cohort correlation acknowledgment** (symmetric with `UserIdHasher`): an adversary with Tempo read access AND a candidate IP list (e.g., 10 known suspect IPs) can trivially compute the 16-hex hash for each and confirm presence/absence in span data — this is a *correlation* attack, not a *recovery* attack. The same property holds for `UserIdHasher` and is in-scope-acceptable for the project's threat model (telemetry confidentiality is enforced at the Tempo access-control layer; the hash is defense-in-depth, not the primary control).

### D5. Pre-existing rate-limit slots become invalid at deploy time (one-time reset)

The only existing IP-axis call site is `/health/*` with a 60-second window. At deploy time:
- Old keys `{scope:health}:{ip:1.2.3.4}` continue to age out within their 60-second TTL.
- New keys `{scope:health}:{ip:<hashed>}` start fresh.
- Net effect: per-IP `/health/*` counter resets at deploy time. A previously-rate-limited IP gets a fresh 60-bucket immediately.

**Acceptance**: this is a one-time invalidation, not a recurring concern. The 60-second TTL means within ~1 minute the system has fully transitioned. No user-facing impact (health endpoints aren't end-user-consumed).

**For Layer 1 pre-issuance buckets when they ship**: the 1-hour and 24-hour TTLs there mean a longer transition window — but those features haven't shipped yet, so this concern is forward-looking.

### D6. IPv6 input handled without normalization

`IpHasher.hash(ip: String)` accepts any string (caller-responsibility per `clientIp` convention). For IPv6 addresses, `hash("::1") != hash("0:0:0:0:0:0:0:1")` — acceptable because Cloudflare emits a canonical form per request, deterministic across that request's edge.

If a future audit shows IPv6-form drift causing rate-limit bypass, normalization can be added without breaking the existing IPv4 surface (just amend `IpHasher.hash` to call `InetAddress.getByName(ip).getHostAddress()` first; the spec scenarios remain unchanged because they assert the hash-shape, not the normalization).

### D7. Test coverage mirrors `UserIdHasherTest`

`IpHasherTest` ships in `:infra:otel/src/test/kotlin/` with parallel structure to `UserIdHasherTest`:
- `hash is deterministic` — same input twice returns same output
- `hash differs between distinct IPs` — `1.2.3.4` and `5.6.7.8` produce distinct hashes
- `hash output matches regex ^[0-9a-f]{16}$` — exact 16-hex shape
- `hash output is exactly 16 hex characters across many random IPs` — sample 1000 random IPv4 addresses, verify shape

**No collision-rate test**: the 64-bit collision probability over IPv4 space is below noise; a probabilistic test would add nothing.

## Risks / Trade-offs

- **Risk**: Tempo dashboards / saved queries that filter on raw IP will silently stop matching post-deploy.
  → **Mitigation**: there are no such dashboards today (the rate-limit IP key was leaking by accident, not consumed by any saved view). The change is invisible to operator workflows. If a future dashboard is built on the hashed form, it queries against the hashed value (deterministic per IP).

- **Risk**: An operator debugging a 429 incident loses the ability to correlate by raw IP across log lines.
  → **Mitigation**: IPs are also (correctly) absent from app-level logs per the `clientIp` boundary. Operators correlate via `traceId` and structured fields like `user.id` (already hashed). The IP→incident correlation today is via Cloudflare logs (not Cloud Logging) — that surface is unaffected.

- **Risk**: Slot reset at deploy means a misbehaving scraper gets a fresh 60-bucket the moment we deploy.
  → **Mitigation**: 60 requests/minute per IP is the cap regardless; the worst case is "scraper resumes 60/min after a 5-minute deploy window." Not a security regression — they had unrestricted 60/min before too.

- **Trade-off**: Deterministic hash means the same IP always hashes to the same value — an adversary with a Tempo dump and the SHA-256 algorithm CAN re-construct a "did IP X visit /health" oracle if they have a candidate IP list.
  → **Accepted trade-off**: the threat model is "don't put PII in telemetry," not "be unforgeable." Adding a pepper would defend against this scenario at the cost of operational complexity (D4). The hashed form is the standard project anonymization shape; same property applies to `UserIdHasher`.

- **Trade-off**: Single global helper means every IP-axis call site (current `/health/*`, future Layer 1) hashes the same way. If we later need per-scope salts (e.g., separate hash domain for guest-token IP buckets), we'd need a `IpHasher.hash(scope, ip)` overload.
  → **Accepted**: not needed today. Add the parameter if the threat model changes.

## Migration Plan

1. **Deploy** — staged via the standard Cloud Run staging-then-prod flow:
   - Cloud Run revision rollover replaces the rate-limit caller code (`HealthRoutes.kt` or wherever `tryAcquireByKey` is invoked).
   - Old raw-key slots in Redis age out within 60 seconds (TTL).
   - New hashed-key slots immediately start being created.
   - No DB migration, no Redis schema change, no data migration.

2. **Smoke verification** (manual staging step in `tasks.md` Section 8):
   - Issue 5 requests to `/health/live` from the same staging client IP.
   - Pull the corresponding `EVALSHA` Tempo span; assert `db.statement` carries `{ip:<16-hex>}` not `{ip:<raw-IP>}`.
   - Pull the corresponding structured log entries; assert `key=` carries the hashed form.
   - If staging green, proceed to production tag-deploy.

3. **Rollback strategy**:
   - If hashed-key behavior is buggy, revert the `HealthRoutes.kt` (or call-site) commit only — `IpHasher` itself is harmless (just an unused helper).
   - Cloud Run rollback to previous revision restores raw-key behavior; Redis slots will repopulate within 60 seconds of rollback.
   - No data corruption risk — rate-limit slots are ephemeral.

4. **Production deploy gate**: pre-archive smoke against staging, per `openspec/project.md` § Staging deploy timing pre-archive smoke convention (codified post-`reply-rate-limit`). No production deploy until staging Tempo span verification passes.

## Open Questions

1. ~~**Should the `key=<key>` log field also be hashed at the structured-log layer?**~~ **RESOLVED in this change** (per round-1 review feedback): `specs/rate-limit-infrastructure/spec.md` § "IP-axis key shape uses hashed IP, never raw" scenario amended to forbid raw-IP log aliases (e.g., a future `key.ip = <ip>` sibling field would have to carry the hashed form too). Codified now rather than deferred so the next IP-axis call site lands cleanly.

2. **Should `IpHasher` be marked internal to `:infra:otel` or exposed publicly?** `UserIdHasher` is `public` (consumed from `AuthPlugin.kt:115`). Following precedent, `IpHasher` should be public for the same reason — call sites in `:backend:ktor` need it.
   - **Decision**: public, matching `UserIdHasher`. No open question.

3. **Should we add a `OtelForbiddenAttributeRule` Detekt rule in this change?** The `FOLLOW_UPS.md` entry `observability-otel-attribute-detekt-rule` tracks this as a separate change.
   - **Decision**: no — that's a separately-scoped follow-up. This change establishes the convention; the rule enforces it later. Round-1 review noted the existing `FOLLOW_UPS.md` entry should explicitly mention IpHasher consumption enforcement; that addendum is applied in this cycle.
