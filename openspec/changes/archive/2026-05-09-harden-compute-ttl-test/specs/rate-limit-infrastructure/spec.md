## MODIFIED Requirements

### Requirement: `computeTTLToNextReset(userId)` shared helper

A pure function `id.nearyou.app.core.domain.ratelimit.computeTTLToNextReset(userId: UUID, now: Instant = Instant.now()): Duration` SHALL exist in `:core:domain`. The function MUST implement exactly the WIB-stagger formula in [`docs/05-Implementation.md`](../../../docs/05-Implementation.md) §1751-1755:

1. `offset_seconds = abs(userId.hashCode()) % 3600` — per-user offset in `[0, 3600)`.
2. Compute `next_midnight_wib`: the next `00:00:00` in `Asia/Jakarta` (WIB, UTC+7) AT or AFTER `now`. If `now` is exactly midnight WIB, `next_midnight_wib = now + 24h`.
3. Effective reset `= next_midnight_wib + Duration.ofSeconds(offset_seconds)`.
4. Return `Duration.between(now, effective_reset)`.

The function MUST NOT depend on `:infra:redis`. The `now` parameter MUST be supplied by tests to verify behavior across midnight WIB.

The returned `Duration` MUST be strictly positive.

#### Scenario: Helper lives in :core:domain
- **WHEN** running `find core/domain/src/commonMain -name 'ComputeTtlToNextReset*' -o -name '*ratelimit*'`
- **THEN** at least one file is found AND its package is `id.nearyou.app.core.domain.ratelimit`

#### Scenario: Same user same offset across calls
- **WHEN** `computeTTLToNextReset(U, now1)` and `computeTTLToNextReset(U, now2)` are called for the same `U` AND `now2` is exactly 1 second after `now1`
- **THEN** the returned `Duration` for `now2` is exactly 1 second SHORTER than for `now1` (the offset is stable per user)

#### Scenario: Different users different offsets (with high probability)
- **WHEN** `computeTTLToNextReset(U1, now)` and `computeTTLToNextReset(U2, now)` are called for two distinct random `UUID` values at the same `now`, sampled across **100,000** independent random pairs
- **THEN** for at least **99,500** of those 100,000 pairs (≥ 99.5%), the returned `Duration` values differ by a non-zero amount (`abs(U1.hashCode().toLong()) % 3600L != abs(U2.hashCode().toLong()) % 3600L`). Threshold derivation: with per-pair collision probability `1/3600` (the offset bucket from `ComputeTtlToNextReset.kt:36`), the expected collision count in 100,000 pairs is `λ ≈ 27.78` (`σ ≈ 5.27`); the "≤ 500 collisions" floor sits ~89σ above the mean — failure probability is effectively zero on any reasonable hashCode distribution.

#### Scenario: Offset bounded to `[0, 3600)` seconds
- **WHEN** `computeTTLToNextReset(U, now)` is called for any 1000 random `U` AND a fixed `now = 2026-01-01T00:00:00Z` (exact UTC midnight = 07:00 WIB)
- **THEN** every returned `Duration` is in the range `[17h, 17h + 1h)` — i.e., the gap between 07:00 WIB now and the next 00:00–01:00 WIB window

#### Scenario: Crossing midnight WIB
- **WHEN** `now = 2026-01-01T16:59:59Z` (= 23:59:59 WIB on Jan 1) AND user U has offset 0 seconds AND `computeTTLToNextReset(U, now)` is called THEN immediately again at `now = 2026-01-01T17:00:01Z` (= 00:00:01 WIB on Jan 2)
- **THEN** the first call returns `Duration.ofSeconds(1)` AND the second call returns approximately `Duration.ofHours(24).minusSeconds(1)` — the window has rolled over

#### Scenario: Pure function, no I/O
- **WHEN** searching the `computeTTLToNextReset` source for any direct file I/O, network, or DB call
- **THEN** none is found — the function uses only `java.time` APIs and the `userId` argument
