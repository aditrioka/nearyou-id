## Why

IP-axis rate-limit Lua keys today embed raw client IP (per [`health-check/spec.md:107`](../../specs/health-check/spec.md) shape `{scope:health}:{ip:1.2.3.4}`), which leaks into Tempo span attributes via `db.statement` on the Lettuce `EVALSHA` span AND into structured logs via the `key=` field mandated by [`rate-limit-infrastructure/spec.md:84`](../../specs/rate-limit-infrastructure/spec.md). This contradicts the project's `CF-Connecting-IP` privacy posture (already enforced in app code via the `RawXForwardedForRule` Detekt rule) and exposes real customer IPs at production tag-deploy + Cloudflare-fronted traffic — IP-with-timestamp surfaces are pseudonymous-by-default in the project's UU PDP posture per [`docs/06-Security-Privacy.md`](../../../docs/06-Security-Privacy.md) § Privacy Compliance (the third-party processor disclosure at line 309 treats trace data as "pseudonymous": hashed user IDs, parameterized SQL, route patterns — raw IPs in `db.statement` violate that envelope). The leak surface is staging-only today (link-local LB IPs), but production must land before tag-deploy.

The `observability-otel-foundation` spec already anticipates this exact follow-up: line 188 explicitly defers a sanctioned truncated-IP attribute form (*"if needed, file a follow-up that introduces an `ip.cidr` truncated attribute"*). This change is that follow-up.

## What Changes

- **ADD** `IpHasher.hash(ip: String): String` helper in `:infra:otel`, mirroring the shipped `UserIdHasher.hash(uuid)` pattern at [`infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/UserIdHasher.kt`](../../../infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/UserIdHasher.kt). Returns first 16 hex of `SHA-256(ip.toByteArray(StandardCharsets.UTF_8))`. Deterministic, no salt. Truncation length matches the existing `UserIdHasher` precedent ([`observability-otel-foundation/spec.md:127`](../../specs/observability-otel-foundation/spec.md)) — operator mental model unified.
- **MODIFY** `rate-limit-infrastructure` capability — the IP-axis convention at [`spec.md:58`](../../specs/rate-limit-infrastructure/spec.md) changes from `{scope:<role>}:{ip:<addr>}` to `{scope:<role>}:{ip:<hashed>}`, where `<hashed>` is the result of `IpHasher.hash(clientIp)` consumed by the caller. Add a scenario asserting the convention.
- **MODIFY** `health-check` capability — Redis key shape at [`spec.md:107`](../../specs/health-check/spec.md) becomes `{scope:health}:{ip:<hashed-ip>}`. Update the assertion at line 131. The call site MUST consume `IpHasher.hash(call.clientIp)` before constructing the key.
- **MODIFY** `observability-otel-foundation` capability — flip the explicit defer-pointer at [`spec.md:188`](../../specs/observability-otel-foundation/spec.md) ("if needed, file a follow-up that introduces an `ip.cidr` truncated attribute") to admit the new `{ip:<hashed>}` axis-value form as the sanctioned anonymization. The forbidden list still rejects raw `CF-Connecting-IP` / `X-Forwarded-For` values; only the truncated form is admitted.
- **BREAKING (slot-reset only)**: pre-existing per-IP rate-limit slots become invalid at deploy time (they were keyed by raw IP, new keys are hashed). For the only existing call site (`/health/*` 60-second window), this means the per-IP counter resets at deploy time. Acceptable for a one-time slot reset; documented in `design.md`. No user-facing impact (health endpoints aren't consumed by end-users).

## Capabilities

### New Capabilities
<!-- None. IpHasher is an implementation detail of :infra:otel, not a new capability. -->

### Modified Capabilities
- `rate-limit-infrastructure`: IP-axis key convention requires the caller to hash `clientIp` before constructing the key. Add scenario asserting the new shape.
- `health-check`: Redis key consumed by `tryAcquireByKey` for `/health/*` rate-limiting changes from `{scope:health}:{ip:<addr>}` to `{scope:health}:{ip:<hashed-ip>}`.
- `observability-otel-foundation`: forbidden-attributes list at the IP entry is amended to admit the new sanctioned hashed form; raw IP values remain forbidden.

## Impact

- **Code**:
  - New: `infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/IpHasher.kt` + `IpHasherTest.kt` (mirrors `UserIdHasher` precedent)
  - Modified: `backend/ktor/src/main/kotlin/id/nearyou/app/health/HealthRoutes.kt` (or wherever the rate-limit key is constructed) — consume `IpHasher.hash(call.clientIp)` before passing to `tryAcquireByKey`
- **APIs**: none — no public REST contract change
- **Dependencies**: none — `:infra:otel` already on `:backend:ktor`'s classpath
- **Systems**:
  - `/health/*` per-IP rate-limit counter resets at deploy time (one-time slot invalidation; acceptable)
  - Tempo span attribute `db.statement` on the `EVALSHA` span carries hashed IP post-deploy (sanctioned form)
  - Structured logs `key=` field carries hashed IP post-deploy
- **Detekt**: existing `RawXForwardedForRule` already enforces `clientIp` request-context use at the request layer; no new rule needed. The new helper is consumed at call sites that already use `clientIp`.
- **Specs**: 3 capability spec files modified (deltas under `specs/` per the OpenSpec convention).
