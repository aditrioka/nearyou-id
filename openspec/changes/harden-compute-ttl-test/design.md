## Context

The `computeTTLToNextReset(userId)` helper at [`core/domain/.../ComputeTtlToNextReset.kt`](../../../core/domain/src/main/kotlin/id/nearyou/app/core/domain/ratelimit/ComputeTtlToNextReset.kt) computes the per-user offset as `userId.hashCode().toLong().absoluteValue % 3600L`, distributing daily-rate-limit resets across the 00:00–01:00 WIB window so traffic is not synchronized at midnight (canonical formula: [`docs/05-Implementation.md` § Rate Limiting Implementation §1751-1755](../../../docs/05-Implementation.md)). The "different users get different offsets with overwhelming probability" property is verified by a test at [`ComputeTtlToNextResetTest.kt:55-71`](../../../core/domain/src/test/kotlin/id/nearyou/app/core/domain/ratelimit/ComputeTtlToNextResetTest.kt) that takes 1000 random `UUID` pairs and asserts `differingPairs >= 999`.

That assertion has a baked-in flake. With per-pair collision probability `p = 1/3600` (the `% 3600L` bucket), the expected number of collisions in 1000 pairs is `λ = 1000/3600 ≈ 0.278` (Poisson). Therefore:

- `P(0 collisions) ≈ exp(-0.278) ≈ 75.7%`
- `P(1 collision) ≈ 0.278 × exp(-0.278) ≈ 21.0%`
- `P(≥ 2 collisions) ≈ 1 - 0.757 - 0.210 ≈ 3.2%`

The assertion `differingPairs >= 999` requires `≤ 1 collision`. The 3.2% probability mass below that threshold is the test's flake rate. Empirically observed flakes during the `chat-foundation` ship cycle alone: CI run 25132387140 attempts 1, 2, 3 all failed; attempt 4 passed. Per-flake cost: a manual GitHub UI retry click + ~5-7 min CI runtime per occurrence. Cumulative cost across the project lifetime to date is multiple wasted CI cycles, plus the cognitive load of "is this a real failure?" on every flake.

The test diagnoses the flake in its inline KDoc as "the hashCode distribution is the suspect, not the assertion" — that diagnosis is incorrect. The hashCode distribution is fine; the assertion threshold is mathematically too tight relative to the achievable randomness.

The matching scenario in [`openspec/specs/rate-limit-infrastructure/spec.md:163-165`](../../specs/rate-limit-infrastructure/spec.md) ("for at least 999 of 1000 random pairs, the returned `Duration` values differ") is the canonical source of the test's tight threshold. Without realigning the spec, future maintainers re-deriving the test from the spec would re-introduce the same flaky bound.

This change closes the flake. The `FOLLOW_UPS.md` entry [`compute-ttl-to-next-reset-test-flake`](../../../FOLLOW_UPS.md) sketches three approaches; the 2026-05-09 triage sweep promoted this entry as the single OpenSpec-shaped hand-off of the cycle (per [`FOLLOW_UPS.md:10`](../../../FOLLOW_UPS.md) intro: *"1 hand-off this sweep: `compute-ttl-to-next-reset-test-flake` → `harden-compute-ttl-test`"*).

## Goals / Non-Goals

**Goals:**
- Eliminate the recurring ~3-4% flake on `ComputeTtlToNextResetTest > Different users produce different offsets at high probability`.
- Realign the canonical spec scenario at [`openspec/specs/rate-limit-infrastructure/spec.md:163-165`](../../specs/rate-limit-infrastructure/spec.md) so the spec's threshold matches achievable randomness — preventing future test refactors from re-deriving the same flaky bound from the spec.
- Preserve the property under test (different users get different offsets with overwhelming probability) and the random-pair semantics (the test still samples random `UUID` values at runtime, not a fixed seed).
- Keep the test runtime cost trivial (under ~5s for the affected method).
- Touch as little surface as possible — single test method, single spec scenario.

**Non-Goals:**
- Refactor the `computeTTLToNextReset` formula itself. The `% 3600L` bucket is canonical (Phase 1 step 24, [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md); [`docs/05-Implementation.md`](../../../docs/05-Implementation.md) §1751-1755).
- Address the source-set discrepancy where the parent capability spec scenario "Helper lives in :core:domain" mentions `commonTest` but the test actually lives at `src/test/kotlin/...` per the JVM-only test source-set. That's pre-existing and orthogonal to this change.
- Adjust the `RateLimiter` interface, the `:infra:redis` `RedisRateLimiter` Lua script, or any other shipped behavior in the parent capability.
- Add new test coverage beyond the one scenario being amended (no new test classes; no new test methods).
- Change Detekt rules, the hash-tag key format, or the `RateLimitTtlRule` / `RedisHashTagRule` enforcement surface.
- Address the orthogonal `like-rate-limit-sliding-window-vs-fixed-window-semantic` follow-up entry, even though it lives adjacent in the same FOLLOW_UPS.md file.

## Decisions

### Decision 1: Approach — sample-size scaling (Approach 3) over seeded RNG (Approach 1) and threshold-loosening alone (Approach 2)

The FOLLOW_UPS entry sketches three approaches:

- **Approach 1 — Seeded RNG**: replace `UUID.randomUUID()` with `Random(seed).nextLong()`-derived UUIDs. Deterministic, zero flake.
- **Approach 2 — Loosen threshold to ">= 995/1000" + amend spec wording**. Preserves random-pair semantics; cheap.
- **Approach 3 — Increase sample to 100,000 with threshold 99,500**. Variance shrinks relative to threshold by `√100`, making P(failure) effectively zero. Preserves random-pair semantics + percentage-of-bucket-coverage.

**Decision: Approach 3 (FOLLOW_UPS recommendation)**. Rationale:

- **Preserves the property semantics.** The point of the test is "different users get different offsets *with overwhelming probability*" — not "this specific seed produces this specific outcome." Approach 1 weakens the property semantics: a single seeded sample is a degenerate case of the random-pair test, and a future maintainer reading the test would have to re-derive that the seeded sample is representative of the random distribution. Approach 3 keeps the random-pair test shape; the only thing that changes is the sample size and the proportional threshold.
- **Statistically bulletproof.** With `λ = 100,000/3600 ≈ 27.78` and `σ ≈ √27.78 ≈ 5.27`, the threshold "≥ 99,500 differing" = "≤ 500 collisions" sits `(500 − 27.78) / 5.27 ≈ 89.5σ` above the expected mean. Failure probability is effectively zero (Gaussian-tail approximation gives `< 10⁻¹⁰⁰`). Even if we tightened the threshold to `≥ 99,900` (= "≤ 100 collisions"), we'd still be at `~13.7σ` above the mean — overwhelming margin.
- **Runtime cost trivial.** Current test runtime ~50ms (1000 iterations of UUID gen + 2 × computeTTLToNextReset). 100× scale → ~5s upper bound. The actual runtime should be lower because UUID generation amortizes well across the JVM warmup. `:core:domain:test` is one of the fastest gradle tasks (typically <30s end-to-end); +5s on a single method is below the noise floor of the full `test` job.
- **No spec wording reduction.** Approach 2 requires loosening the spec wording from "for at least 999 of 1000" to something like "for the vast majority of random pairs" or "≥ 99.5%". The first is hand-wavy; the second is the same as Approach 3 expressed differently. Approach 3 keeps the spec wording crisp ("for at least 99,500 of 100,000") with a precise sample size + threshold.

**Alternatives considered and rejected:**

- **Approach 1 (seeded RNG)** — see above. Weakens random-pair semantics; loses the regression-detection power for "the hashCode distribution silently degraded" (which is a real if rare failure mode for `UUID` JVM versions).
- **Approach 2 (threshold loosen only)** — possible. Keeps sample size at 1000. With `≥ 995/1000` the probability of failure is `P(≥ 6 collisions in 1000 pairs)` with `λ = 0.278`, which is `≈ 5 × 10⁻⁷` (Poisson PMF). Statistically safe but only ~5σ-equivalent, vs Approach 3's ~89σ. Spec wording also becomes hand-wavy if not expressed as a precise percentage, and the percentage-of-bucket-coverage promise drops from 99.9% (implicit current) to 99.5% (explicit) — a real promise reduction the spec would have to absorb. Approach 3 expresses the same 99.5% but with 100× the sample size, so the underlying statistical confidence is much stronger.
- **Hybrid (seeded fallback + random primary)** — over-engineering. The test runs once per CI invocation; we don't need a fallback if the primary is statistically bulletproof.

### Decision 2: Threshold value — 99,500 of 100,000 (= ≥ 99.5%)

Three candidate values for the new threshold:

| Threshold | Maximum allowed collisions | σ above mean (`λ = 27.78`) | P(failure) ≈ |
|---|---|---|---|
| ≥ 99,500 (99.5%) | ≤ 500 | ~89.5σ | <<10⁻¹⁰⁰ |
| ≥ 99,900 (99.9%) | ≤ 100 | ~13.7σ | <10⁻⁴² |
| ≥ 99,950 (99.95%) | ≤ 50 | ~4.2σ | ~10⁻⁵ |

**Decision: 99,500 of 100,000.** Rationale:

- The 99.5% threshold has the largest statistical safety margin and is the FOLLOW_UPS-recommended value.
- Tighter thresholds (99.9% / 99.95%) trade safety margin for tighter contract — but the contract under test is "different users *with overwhelming probability*", not "different users with 5σ confidence." 99.5% IS overwhelming for any user-facing semantics.
- The 99.5% threshold leaves room for `% 3600L` to evolve into `% 7200L` (doubling the bucket space, halving collision probability) without the test breaking — a future "we want even-finer staggering" change wouldn't have to also amend the threshold.
- 99.5% is a round, memorable number that maps directly to "≥ 99,500 of 100,000" — easy to read in both spec and test.

**Alternative rejected: dynamic-threshold derivation in the test.** A version that computes `expectedMaxCollisions = ceil(lambda + 5σ)` at runtime would be self-adapting if the bucket size changes. Considered and rejected: the runtime computation adds complexity, and the static value is the spec contract. The contract should be a literal in the test, not a derived value.

### Decision 3: Spec amendment shape — RESTATE the entire requirement under MODIFIED Requirements

OpenSpec's MODIFIED-requirement workflow per [`openspec instructions specs`](../../changes/harden-compute-ttl-test/) requires copying the ENTIRE requirement block (from `### Requirement:` through all scenarios) and editing in place. Even though only one scenario inside `### Requirement: \`computeTTLToNextReset(userId)\` shared helper` changes, the entire requirement block (6 scenarios + body) must be RESTATED.

**Decision: copy the entire requirement block byte-for-byte from the parent capability spec, edit only the "Different users different offsets (with high probability)" scenario, leave everything else untouched.** Rationale: the openspec archive workflow concatenates MODIFIED-requirement blocks back into the parent spec verbatim — partial copies cause silent content loss at archive time (the spec's instruction document explicitly warns: *"Common pitfall: Using MODIFIED with partial content loses detail at archive time."*).

**Alternative rejected: ADDED Requirements with a new "Different users — strict" scenario name.** Would not consolidate the contract — the existing 999/1000 scenario would still ship with the loose contract while a new scenario asserts the stronger one. Two scenarios on the same property invites future drift. RESTATE is the correct mechanism.

### Decision 4: Inline KDoc comment correction in the test

The existing test KDoc at [`ComputeTtlToNextResetTest.kt:65-69`](../../../core/domain/src/test/kotlin/id/nearyou/app/core/domain/ratelimit/ComputeTtlToNextResetTest.kt) reads:

> *"If this ever flakes, the hashCode distribution is the suspect, not the assertion."*

That diagnosis is incorrect — the assertion was the suspect all along. The amendment in this change rewrites the comment to reflect the actual statistical model:

> *"Sample size 100,000 with threshold 99,500 (≥ 99.5%) puts the assertion ~89σ above the expected collision count of ~27.78 (Poisson, p = 1/3600). Failure probability is effectively zero on any reasonable hashCode distribution. If this ever flakes, the `hashCode().toLong().absoluteValue % 3600L` bucket — see `ComputeTtlToNextReset.kt:36` — has collapsed to a degenerate distribution; investigate JVM/UUID hashCode regressions before relaxing the assertion further."*

**Decision: rewrite the comment.** Rationale: leaving the misdiagnosis in place would mislead a future maintainer who hits a (theoretical) flake into looking at the wrong root cause.

## Risks / Trade-offs

- **[Risk] Test runtime grows from ~50ms to ~5s on the slowest CI runner.** → **Mitigation**: pre-archive smoke verifies actual runtime on the GitHub-hosted Ubuntu runner stays under 10s for the single test method (with 2× safety margin over the 5s expected). If the runtime on CI exceeds 10s, fall back to threshold approach 2 (sample 1000, threshold ≥ 995) — same statistical safety class, no runtime growth. Documented as the contingency in the apply-phase tasks.
- **[Risk] UUID.randomUUID() entropy starvation on Linux CI runners under load.** → **Mitigation**: GitHub-hosted Ubuntu runners use `/dev/urandom` (non-blocking) for UUID entropy via `SecureRandom.getInstanceStrong()` fallback chain — never `/dev/random`. 100,000 UUIDs is well within the budget where entropy starvation has been observed (`>10M UUIDs/sec is the regression range`). No change required.
- **[Risk] Future change to the offset bucket (`% 3600L` → some other modulus) would invalidate the threshold.** → **Mitigation**: the new scenario explicitly cites `% 3600L` as the bucket source. A future bucket change would need to also re-derive the threshold; the spec and test would both surface that explicitly. The 99.5% number is bucket-size-independent (it's the property under test, not the implementation), so the percentage stays stable across bucket changes — only the absolute count would shift.
- **[Trade-off] No regression-test for "the hashCode distribution silently degraded."** → A pathological hashCode that returns a constant for all UUIDs would still pass the new test (all 100,000 pairs would collide, but the threshold "≥ 99,500 differ" would fail loudly — actually catching the degenerate distribution). So this trade-off is null; the test still catches that failure mode.
- **[Trade-off] Approach 3 doubles the spec body length for one scenario by virtue of the RESTATE workflow.** → Acceptable: openspec's archive mechanism concatenates the MODIFIED block back into the parent spec, restoring the original structure. The `openspec/changes/harden-compute-ttl-test/specs/rate-limit-infrastructure/spec.md` file is a temporary artifact that lives only until archive-time.

## Migration Plan

No production deployment. This is a test-only + spec-only change.

Pre-archive sequence (mirrors the canonical [`openspec/project.md` § Change Delivery Workflow](../../../openspec/project.md)):

1. Spec amendment commit (this proposal phase).
2. Implementation phase via `/opsx:apply` — single test-file edit + run `:core:domain:test` to verify the new threshold passes locally + on CI.
3. Pre-archive runtime check: `time ./gradlew :core:domain:test --tests ComputeTtlToNextResetTest` on the local box, captured as evidence in the archive commit body. Goal: <10s wall clock for the affected single method (verifies the 5s expected upper bound with 2× safety margin).
4. Archive phase via `/opsx:archive` — moves the proposal under `archive/` and concatenates the MODIFIED requirement back into [`openspec/specs/rate-limit-infrastructure/spec.md`](../../specs/rate-limit-infrastructure/spec.md).
5. Squash-merge to `main` (one commit per the one-PR convention).

Rollback strategy: if a post-merge regression surfaces (e.g., new threshold flakes on a runner), revert the squash commit. The reverted state is the pre-change spec scenario + 1000-iteration test — the original known-flaky shape, but functionally correct.

## Open Questions

- **Q1: Should the test cite the threshold derivation in a code comment or only in the spec/design?** Current plan: the test KDoc cites the math inline (per Decision 4). The spec scenario carries the threshold value; the design.md carries the σ-margin derivation. This means the math lives in three places (test KDoc, spec scenario, this design doc). Acceptable — the test KDoc is the closest-to-the-code reader experience, the spec is the contract, the design is the rationale-of-record.
- **Q2: Should we add a regression-detection test that triggers if the collision rate drifts more than 5σ from the expected `λ = 27.78`?** Considered. Out of scope — the existing test already catches catastrophic regressions (a degenerate hashCode would fail the threshold), and a separate "collision rate within ±5σ" test would over-fit the implementation. If a real-world hashCode regression is observed, file a follow-up.
- **Q3: Does the source-set discrepancy on the parent capability spec scenario "Helper lives in :core:domain" need fixing in this change?** The parent capability spec at [`openspec/specs/rate-limit-infrastructure/spec.md:155-157`](../../specs/rate-limit-infrastructure/spec.md) says: *"WHEN running `find core/domain/src/commonMain -name 'ComputeTtlToNextReset*' -o -name '*ratelimit*'` THEN at least one file is found AND its package is `id.nearyou.app.core.domain.ratelimit`"*. The actual `:core:domain` module is JVM-only (`src/main/kotlin/...`, not `src/commonMain/kotlin/...`). The scenario is over-prescriptive — it would fail today if literally run. Out of scope for this change (the discrepancy predates this change and affects a different scenario), but flagged here so a future maintainer who notices doesn't conflate the two issues. If desired, file a follow-up `core-domain-source-set-spec-realignment` change.
