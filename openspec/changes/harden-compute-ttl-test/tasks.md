## 1. Spec amendment (proposal phase)

- [ ] 1.1 Author `proposal.md` with the Why / What Changes / Capabilities / Impact sections aligned to the FOLLOW_UPS recommendation (approach 3, sample 1000→100,000, threshold 999→99,500).
- [ ] 1.2 Author `design.md` with Decisions covering approach choice, threshold value, spec amendment shape (RESTATE), and inline KDoc correction.
- [ ] 1.3 Author `specs/rate-limit-infrastructure/spec.md` MODIFIED Requirements block — copy the entire `### Requirement: \`computeTTLToNextReset(userId)\` shared helper` block from [`openspec/specs/rate-limit-infrastructure/spec.md:142-177`](../../specs/rate-limit-infrastructure/spec.md) byte-for-byte, edit only the "Different users different offsets (with high probability)" scenario.
- [ ] 1.4 Run `openspec validate harden-compute-ttl-test --strict` and resolve any reported issues. Re-run until clean.
- [ ] 1.5 Reconciliation pass: re-read [`openspec/specs/rate-limit-infrastructure/spec.md:142-177`](../../specs/rate-limit-infrastructure/spec.md) and verify the spec delta matches the parent requirement byte-for-byte except for the one amended scenario. Confirm no other scenarios were inadvertently rewritten or dropped.
- [ ] 1.6 Reconciliation pass: confirm [`docs/05-Implementation.md` § Rate Limiting Implementation §1751-1755](../../../docs/05-Implementation.md) does NOT prescribe a specific test threshold (it describes the WIB-stagger formula only). Documented in `proposal.md` Impact section as "no docs amendment required."

## 2. Implementation phase (`/opsx:apply`)

- [ ] 2.1 In [`core/domain/src/test/kotlin/id/nearyou/app/core/domain/ratelimit/ComputeTtlToNextResetTest.kt`](../../../core/domain/src/test/kotlin/id/nearyou/app/core/domain/ratelimit/ComputeTtlToNextResetTest.kt), in the test method `"Different users produce different offsets at high probability"`: change `repeat(1000)` to `repeat(100_000)` and `(differingPairs >= 999) shouldBe true` to `(differingPairs >= 99_500) shouldBe true`.
- [ ] 2.2 Rewrite the inline KDoc-style comment block at lines 65-69 of the same test method per `design.md` Decision 4. Replace the misdiagnosis ("the hashCode distribution is the suspect") with the correct statistical model — cite the σ-margin (~89σ) and the bucket source (`hashCode().toLong().absoluteValue % 3600L`).
- [ ] 2.3 Run `./gradlew :core:domain:test --tests "id.nearyou.app.core.domain.ratelimit.ComputeTtlToNextResetTest"` locally and verify the test passes. Repeat 3× to verify no flake at the new threshold.
- [ ] 2.4 Capture single-method runtime for evidence: `time ./gradlew :core:domain:test --tests "id.nearyou.app.core.domain.ratelimit.ComputeTtlToNextResetTest.Different users produce different offsets at high probability" --rerun-tasks`. Record the wall-clock time in the apply commit body. Goal: <10s wall clock (with 2× safety over the 5s expected).
- [ ] 2.5 Contingency: if the wall-clock time on CI exceeds 10s for the affected method, fall back to threshold approach 2 — sample 1000 with `(differingPairs >= 995) shouldBe true`. Update `specs/.../spec.md` to match. Re-run `openspec validate --strict`. Document the contingency in the apply commit body.
- [ ] 2.6 Run the full local lint + test gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test :core:domain:test`. All four MUST pass. Per [`CLAUDE.md` § Pre-push verification](../../../CLAUDE.md), `ktlintCheck` is required alongside `detekt` — running `detekt` alone is insufficient for CI parity.
- [ ] 2.7 Verify CI green on the change branch via `gh pr checks <pr> --watch`. Resolve any test or lint failures (test classes other than `ComputeTtlToNextResetTest` should not be affected by this change; if they fail it's a CI flake or unrelated regression).

## 3. Pre-archive verification

- [ ] 3.1 Re-run `openspec validate harden-compute-ttl-test --strict` after any post-review-feedback amendments. MUST be clean before archive.
- [ ] 3.2 Verify no production code was touched: `git diff main..<change-branch> -- ':!*.md' ':!openspec/' ':!core/domain/src/test/'` should output nothing. (Test-only + spec-only change.)
- [ ] 3.3 No staging deploy required (test-only change, no runtime impact, no smoke script needed). Mark Section 4 N/A in the archive commit body per [`openspec/project.md` § Archive timing under the one-PR convention](../../../openspec/project.md).

## 4. Staging smoke (N/A for this change)

- [ ] 4.1 N/A — test-only + spec-only change with no runtime impact. Per [`openspec/project.md`](../../../openspec/project.md) § Archive timing, "for docs-only / refactor-only changes, skip step 2-3 and go straight to archive (mark Section 6 N/A in the archive commit body)" — applies here equivalently. The archive commit body MUST cite this Section 4 N/A explicitly.

## 5. Archive (`/opsx:archive`)

- [ ] 5.1 Run `openspec archive harden-compute-ttl-test` locally. Confirm the MODIFIED requirement is concatenated back into [`openspec/specs/rate-limit-infrastructure/spec.md`](../../specs/rate-limit-infrastructure/spec.md) verbatim — the only diff in that file vs `main` should be the one amended scenario inside `### Requirement: \`computeTTLToNextReset(userId)\` shared helper`.
- [ ] 5.2 Confirm `openspec/changes/harden-compute-ttl-test/` is moved under `openspec/changes/archive/<YYYY-MM-DD>-harden-compute-ttl-test/`.
- [ ] 5.3 Run `openspec validate --specs rate-limit-infrastructure --strict` and confirm clean.
- [ ] 5.4 Update [`FOLLOW_UPS.md`](../../../FOLLOW_UPS.md): delete the `compute-ttl-to-next-reset-test-flake` entry per the entry's own action item ("Update `FOLLOW_UPS.md` to delete this entry once the change merges"). Update the entry-count audit in the file's intro blurb.
- [ ] 5.5 Push the archive commit + final body refresh per [`openspec/project.md` § PR title and body MUST stay current at every phase boundary](../../../openspec/project.md). Squash-merge to `main` once archive CI is green.
