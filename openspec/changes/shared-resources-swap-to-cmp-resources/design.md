## Context

Mobile #2 (`shared-resources-moko-bootstrap`, PR [#116](https://github.com/aditrioka/nearyou-id/pull/116), squash-merged 2026-05-27) bootstrapped the `:shared:resources` module with **Moko Resources** as the resource-accessor substrate. The proposal-phase comparison considered Compose Multiplatform's built-in `compose-components-resources` and rated the two roughly equivalent, choosing Moko on three soft rationales: (a) project canonical docs cited Moko by name; (b) Moko's Android-`R.class`-style conventions felt familiar; (c) Moko 0.x line stability since 0.20.x.

PR [#118](https://github.com/aditrioka/nearyou-id/pull/118) (squash-merged 2026-05-28) introduced a new workflow rule into [`openspec/project.md`](../../project.md) § Change Delivery Workflow — **"Pre-implementation library re-check (MUST for substrate-introducing changes)"** — mandating a fresh dated WebSearch before `/opsx:apply` lands its first feat commit. This change is the first test case for that rule. A re-check performed on 2026-05-28 surfaced material ecosystem shifts that materially change the original call:

- **CMP 1.10.0 (Jan 2026)** — production-stable, unified `@Preview` across platforms, Navigation 3 stable on non-Android targets, stable Compose Hot Reload bundled. Project already pins `composeMultiplatform = "1.10.3"`.
- **CMP 1.11.0 (May 2026)** — further matures iOS interop (native iOS text input, concurrent rendering default, Skia M144). No resources-subsystem-specific changes in 1.11; raises iOS minimum to 14.0.
- **JetBrains directional commitment** — `compose-components-resources` is the official CMP-builtin path; Moko Resources (still maintained as a 0.x line) is no longer the JetBrains-recommended substrate for new CMP projects.
- **Already-pinned-but-unused coordinate** — [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml) line 61 declares `compose-components-resources = { module = "org.jetbrains.compose.components:components-resources", version.ref = "composeMultiplatform" }`. Mobile #2 brought this coordinate into the catalog (as a future-compatible add) but did not wire it into any module. This change activates that pin — exactly matching the "previously-unused-but-pinned library" trigger condition for the re-check rule.

**Current state (as of branch creation 2026-05-28):**

- `:shared:resources` ships 10 strings (`commonMain/moko-resources/MR/base/strings.xml`), 2 SVG logos (`commonMain/moko-resources/images/`), 1 variable-weight Plus Jakarta Sans TTF (`commonMain/moko-resources/fonts/`).
- 4 non-resource Kotlin source files (`NearYouColorScheme.kt`, `NearYouTypography.kt`, `ColorSchemeExtensions.kt`, `NearYouColors.kt`) + 2 test files.
- `:mobile:app`'s [`HomeScreen.kt`](../../../mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt) is the sole consumer — 5 accessor call sites: 2 × `MR.images.logo_brand_{light,dark}` for the theme-aware logo, 1 × `MR.strings.app_name` (contentDescription), 1 × `MR.strings.home_placeholder_title`, 1 × `MR.strings.home_placeholder_version` (with positional format arg).
- 1 test-file comment line references `MR.strings.home_placeholder_version` in [`HomeScreenTest.kt:29`](../../../mobile/app/src/commonTest/kotlin/id/nearyou/app/HomeScreenTest.kt:29).
- Moko's dual-plugin workaround is in place: `mokoResources` plugin applied in BOTH `:shared:resources/build.gradle.kts` (for resource codegen) AND `:mobile:app/build.gradle.kts` (for the iOS framework copy task wiring, per the 7-line comment block at [`mobile/app/build.gradle.kts:8-14`](../../../mobile/app/build.gradle.kts:8)).

**Trigger to flip back to backend hardening:** N/A — this change is mobile-scaffolding work (Mobile #2.5 interstitial) and continues to honor the project's current mobile-scaffolding priority per [`openspec/project.md`](../../project.md) § Mobile + Admin Scaffolding Priority.

**Today's date:** 2026-05-28. Phase-balance check: scaffolding priority is active (0 of 2 trigger milestones — `mobile-nearby-timeline-screen` + `admin-actions-log-viewer` — shipped). This change is a small, focused interstitial between Mobile #2 (just shipped) and Mobile #3 (auth, upcoming).

## Goals / Non-Goals

**Goals:**

- Replace the Moko Resources substrate in `:shared:resources` with Compose Multiplatform's built-in `compose-components-resources`.
- Preserve every byte of resource content (10 strings, 2 SVGs, 1 TTF) bit-for-bit — only the accessor API and on-disk directory layout change.
- Preserve all non-resource surface of `:shared:resources` (brand color tokens, typography, extension properties via `LocalNearYouColors` CompositionLocal) untouched.
- Eliminate the dual-`mokoResources`-plugin workaround in `:mobile:app/build.gradle.kts` (the 7-line comment block + plugin block at lines 8-14 + the `multiplatformResources` block at lines 68-70).
- Drop `moko-resources` from `gradle/libs.versions.toml` entirely (version pin + 2 library entries + 1 plugin entry).
- Update the 5 documentation surfaces ([`CLAUDE.md`](../../../CLAUDE.md), [`openspec/project.md`](../../project.md), [`docs/04-Architecture.md`](../../../docs/04-Architecture.md), [`docs/09-Versions.md`](../../../docs/09-Versions.md), [`FOLLOW_UPS.md`](../../../FOLLOW_UPS.md)) that name Moko Resources to canonicalize on CMP Resources wording.
- Verify both Android (`./gradlew :mobile:app:assembleDebug`) and iOS (`./gradlew :mobile:app:linkDebugFrameworkIosSimulatorArm64`) build green through the swap.
- Serve as the first concrete proof that the pre-implementation library re-check rule (PR #118) catches the substrate-drift class of issues this project is exposed to.

**Non-Goals:**

- **Authoring the "no hardcoded UI strings" Detekt rule.** Still deferred per existing `FOLLOW_UPS.md` entry `mobile-negative-requirement-ci-grep` (FOLLOW_UPS.md:735), which proposes the future OpenSpec change `mobile-negative-requirement-detekt-rule`. A [`grep`-shaped verification](#decision-7) stays in place; this change just retargets the future rule's expected accessor pattern from `MR.strings` to `Res.string`.
- **moko-mvvm migration.** Not in use (project already wired `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose`, JetBrains' KMP fork of AndroidX Lifecycle, at Mobile #1). Skip.
- **moko-permissions / moko-media adoption.** Not in use yet. Defer permissions library choice to Mobile #5/#6 when `mobile-nearby-timeline-screen` actually needs location permission. Lean Calf ([mohamedrejeb/Calf](https://github.com/MohamedRejeb/Calf)) at decision time, but pre-picking here is premature.
- **Hand-tuned dark palette.** Still deferred per `shared-resources-moko-bootstrap` design.md Decision 3 (mechanically-derived dark stays).
- **Material 3 1.4-alpha + expressive components.** Still wait for a future change that actually needs `WavyProgressIndicator` / `MaterialExpressiveTheme` / expressive button APIs.
- **String / image / font CONTENT changes.** Bit-for-bit identical resources delivered; the swap is purely build-time accessor + layout.
- **Re-validating brand color tokens or typography decisions.** The `NearYouColorScheme` palette, dark derivation, outline override, and Plus Jakarta Sans selection from Mobile #2's design.md Decisions 2–9 all stand. Only the file-system location of the font and the access-path to color tokens / typography (which is unchanged — they're already `import`-based, not Moko-based) survive.
- **iOS launcher icon / Android adaptive icon / Bahasa Indonesia copy.** Mobile #2 shipped these; this change does NOT touch them.

## Decisions

### Decision 1: Activate `compose-components-resources` (already pinned, currently unused) — swap from Moko Resources

**Choice:** Wire `org.jetbrains.compose.components:components-resources` ([`gradle/libs.versions.toml:61`](../../../gradle/libs.versions.toml:61)) into `:shared:resources` as the resource-accessor substrate. Remove Moko Resources entirely (plugin, library, version pin).

**Rationale:**

- **Compose Multiplatform's built-in resources subsystem is production-stable since CMP 1.6 (Feb 2024); CMP 1.8 (May 2025) made iOS production-ready; CMP 1.10.3 (project's current pin) ships full support including unified `@Preview` previews, full Android-asset packing, and iOS framework auto-wiring** — none of which Moko Resources provides natively. The proposal-phase rationale "Moko Android-`R.class`-style conventions" no longer differentiates: CMP Resources' `Res.string.X` / `Res.drawable.X` accessor is structurally equivalent to Moko's `MR.strings.X` / `MR.images.X`, with the same type safety and the same generated-class-mirroring-Android-R semantics.
- **JetBrains-official path.** Moko Resources is a community library (IceRock Development); CMP Resources ships with the JetBrains-maintained Compose Multiplatform plugin. For long-term ecosystem alignment, the JetBrains-maintained path has lower bus-factor and migration risk.
- **No new external dependency.** `compose-components-resources` is already on the build classpath via the existing `composeMultiplatform = "1.10.3"` plugin — activating it adds zero net dependency surface area.
- **Eliminates Moko's iOS framework copy-task workaround.** Per Mobile #2's [`mobile/app/build.gradle.kts:8-14`](../../../mobile/app/build.gradle.kts:8) comment, Moko requires the `mokoResources` plugin to be applied in BOTH the resource module AND the consumer module to wire the iOS framework copy task. CMP Resources handles iOS framework integration natively through the CMP Gradle plugin — `:mobile:app` does NOT need to declare any resource-plugin block.

**Alternatives considered:**

- **Stay on Moko Resources.** Defensible (the library works, content delivery is unaffected) but: every subsequent mobile screen accumulates `MR.*` call sites that become migration debt. Mobile #3-#5 will add 80-150+ strings. Migration cost compounds linearly with screen count. Migrating now (10 strings, 5 call sites) is cheap; migrating in 4 weeks is materially more work. The "no immediate forcing function" stance loses to "no cheaper window will exist" math.
- **Author a thin adapter layer that aliases `MR.*` to `Res.*` for forward-compatibility.** Adds an extra abstraction layer for zero present-day benefit. The two accessor APIs are structurally identical — a direct swap is cleaner than an indirection. Rejected as gratuitous wrapping.
- **Wait for Moko to publish a 1.0 stable release before reconsidering.** Moko's 0.x line has been stable in practice (no breaking changes between 0.20.x and 0.26.4), but the 0.x version number signals "API may shift." Waiting forecloses the option to migrate cheaply now. Rejected as a deferred-cost trap.

**Trade-off accepted:** Project's existing memory of "Moko Resources is the Mobile substrate" (in onboarding docs, OpenSpec change archives, design.md decisions) must be canonicalized to "CMP Resources" via doc updates. Documented in this change's Impact + tasks.md.

### Decision 2: Resource layout migration follows CMP Resources convention exactly — no project-specific layering

**Choice:** Move resources to the CMP Resources canonical layout:

| Resource type | Moko location | CMP location |
|---|---|---|
| Strings | `commonMain/moko-resources/MR/base/strings.xml` | `commonMain/composeResources/values/strings.xml` |
| Drawables | `commonMain/moko-resources/images/` | `commonMain/composeResources/drawable/` |
| Fonts | `commonMain/moko-resources/fonts/` | `commonMain/composeResources/font/` |

No locale qualifier on `values/` (CMP uses `values/` for base + `values-{locale}/` for translations, matching Android resource convention exactly). Plus Jakarta Sans goes in `font/` (NOT `font-anydpi/` or similar — CMP's font dir is platform-agnostic).

**Rationale:** Adopting CMP's canonical layout means future contributors don't need to learn a project-specific layout. The single base-locale `strings.xml` retains the same shape Mobile #2 shipped (one root `<resources>` with `<string name="X">value</string>` entries). Drawable extensions are unchanged (`.svg` stays `.svg`). Font extensions are unchanged (`.ttf` stays `.ttf`). The move is `git mv`-grade: file bytes unchanged, only paths shift.

**Alternatives considered:**

- **Keep the Moko-style `moko-resources/MR/base/` directory structure under CMP Resources.** Doesn't work — CMP Resources' Gradle plugin scans the `composeResources/` directory specifically. Routing files outside that tree means they don't get codegen'd.
- **Use locale-qualified path (`composeResources/values-id/strings.xml`) for the Bahasa Indonesia strings.** Tempting because the content IS Indonesian, but CMP convention treats `values/` as base locale (matching Android). Since the app is single-locale-by-design (Indonesia-only MVP per CLAUDE.md), shipping the strings as the base locale + no `values-en/` fallback is correct. If the project ever ships an English variant, that lands as `values-en/strings.xml` alongside the existing `values/strings.xml`.

**Trade-off accepted:** None — this is a mechanical layout swap with no semantic implication.

### Decision 3: Drop `moko-resources` plugin + library + version pin from `libs.versions.toml`; preserve `compose-components-resources` entry

**Choice:** Remove from [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml):

- Line 35: `moko-resources = "0.26.4"`
- Lines 134-138 (preamble comment block) + 139-140 (library entries `moko-resources`, `moko-resources-compose`)
- Line 166: `mokoResources = { id = "dev.icerock.mobile.multiplatform-resources", version.ref = "moko-resources" }`

Keep:

- Line 61: `compose-components-resources = { module = "org.jetbrains.compose.components:components-resources", version.ref = "composeMultiplatform" }` — now actively wired.

**Rationale:** Moko isn't used anywhere else in the build after this change ships. Leaving stale entries in the catalog creates "is this used?" archaeology cost for future contributors and risks accidental re-wiring. Clean removal is the right hygiene. The version-pinning rationale comment block above the Moko library entries (the 6-line "Moko Resources: KMP resource codegen ..." preamble at lines 134-138) goes with them.

**Alternatives considered:**

- **Keep the `moko-resources` plugin entry but unreferenced** (as a "future option"). No — version-catalog entries are inventory, not options. Catalog hygiene says: if not used, remove.
- **Leave the version pin (`moko-resources = "0.26.4"`) "just in case."** Same anti-pattern. Catalog drift accumulates fast under solo-operator velocity.

**Trade-off accepted:** Re-adding Moko if a future change wants it back means re-adding 3 catalog entries + plugin wiring. Trivially cheap; doesn't justify carrying dead entries.

### Decision 4: `:mobile:app` drops the second `mokoResources` plugin block entirely — no replacement plugin in the consumer module

**Choice:** Remove from [`mobile/app/build.gradle.kts`](../../../mobile/app/build.gradle.kts):

- Lines 8-14: the 7-line comment block + `alias(libs.plugins.mokoResources)` plugin alias.
- Lines 68-70: the `multiplatformResources { resourcesPackage.set("id.nearyou.app.frameworkresources") }` block (the empty-MR-class workaround so the iOS framework gets the resource copy task). The 7-line justifying comment above it (lines 61-67) goes too.

CMP Resources does NOT require any plugin to be applied in consumer modules — the JetBrains Compose Multiplatform plugin handles iOS framework resource wiring automatically through its own iOS framework declaration logic. Resources from a depended-on KMP module with `compose-components-resources` configured are auto-bundled into the consumer's iOS framework.

**Rationale:** Mobile #2's design.md Decision … [implicit, since the dual-plugin workaround was a contractual requirement of Moko Resources' iOS pipeline]. With CMP Resources, the entire workaround disappears. This is one of the primary structural wins of the swap.

**Verification:** Build the iOS framework after the swap (`./gradlew :mobile:app:linkDebugFrameworkIosSimulatorArm64`) and inspect the produced `.framework` bundle — confirm that the 2 SVG drawables + Plus Jakarta Sans TTF are present in the bundle's `Resources/compose-resources/` directory (or wherever CMP Resources convention places them; verify against the JetBrains docs and the Mobile #1 / #2 iOS framework structure at apply time).

**Alternatives considered:**

- **Apply some other plugin in `:mobile:app` to "replace" the dropped `mokoResources` plugin.** Not needed; CMP Resources requires no consumer-side plugin.
- **Keep the empty-MR-class `multiplatformResources` block.** Doesn't apply with CMP Resources (different config block name entirely; the `multiplatformResources { ... }` DSL is Moko-specific). Removing is mandatory, not optional.

### Decision 5: Preserve `NearYouTheme` invariant — color tokens + typography surface from `:shared:resources` unchanged

**Choice:** The 4 non-resource Kotlin files in `:shared:resources/src/commonMain/kotlin/id/nearyou/resources/theme/` — `NearYouColorScheme.kt`, `NearYouTypography.kt`, `ColorSchemeExtensions.kt`, `NearYouColors.kt` — are preserved with at most a single import-line edit (if any current Moko import is referenced). Specifically:

- `NearYouColorScheme.kt` — pure Kotlin / Compose data; uses `androidx.compose.material3.lightColorScheme(...)` + hand-built `darkColorScheme()`. Does NOT import Moko. Untouched.
- `ColorSchemeExtensions.kt` — `CompositionLocal` declarations. No Moko import. Untouched.
- `NearYouColors.kt` — data class. No Moko import. Untouched.
- `NearYouTypography.kt` — **THIS IS THE ONLY FILE THAT IMPORTS FROM MOKO.** It calls `MR.fonts.plus_jakarta_sans.asFont()` to load the font resource. Change this import + accessor to CMP Resources' equivalent: import the generated `Res.font.plus_jakarta_sans` and use the CMP Resources `Font(Res.font.plus_jakarta_sans)` composable accessor (or the platform-equivalent eager-load shape — check against CMP Resources documentation at apply time).

**Rationale:** The brand contract (colors, typography, extension properties) is decoupled from the resource-substrate layer by design. The CompositionLocal + extension-property pattern is pure Compose semantics — no Moko vs CMP impact. Only the font-loading path touches the substrate, and the file move + accessor swap addresses it.

**Verification:** The existing `:shared:resources` tests (`NearYouColorSchemeTest`, `ColorSchemeExtensionsTest`) MUST continue to pass after the swap with no edits beyond Moko import removal (if any leaked in indirectly via the typography file).

**Alternatives considered:**

- **Re-derive the brand color tokens through CMP's theme system.** Not applicable — CMP Resources is about strings/drawables/fonts; theme tokens are pure Material 3 Compose code, independent of which resource library is in play.

### Decision 6: Drop the `export(libs.moko.resources)` line + `multiplatformResources` block; KEEP `isStatic = true` (it's the JetBrains-default, NOT Moko-coupled)

**Choice:** Remove from [`shared/resources/build.gradle.kts`](../../../shared/resources/build.gradle.kts):

- Line 25: `export(libs.moko.resources)` from the iOS framework block (lines 18-27). This is unambiguously Moko-specific.
- Lines 79-82: the `multiplatformResources { resourcesPackage.set("id.nearyou.resources"); resourcesClassName.set("MR") }` block — Moko-specific DSL, no equivalent needed under CMP Resources.

**PRESERVE `isStatic = true` (line 24).** This was originally planned as part of the drop but post-review (Round-1 substrate-rationale lens, 2026-05-28) the assumption that "isStatic was Moko-coupled" did not hold up to fresh evidence. `isStatic = true` is the **JetBrains KMP wizard default** for iOS frameworks in 2026, NOT a Moko-imposed requirement. Multiple 2026-era CMP samples + tutorials use `isStatic = true` as the canonical pattern, citing iOS app-startup-performance benefits ([Apple guidance](https://bpoplauschi.github.io/2021/10/25/Advanced-static-vs-dynamic-libraries-and-frameworks.html) recommends ≤6 dynamic modules for fast launch; static is the safer default for KMP shared frameworks). Dropping it speculatively would trade a stable, JetBrains-default configuration for a non-canonical one with potential app-startup-time regression — a behavior change unrelated to the substrate swap. Conservative call: leave `isStatic = true` untouched.

The iOS framework block becomes:
```kotlin
iosTarget.binaries.framework {
    baseName = "SharedResources"
    isStatic = true  // preserved — JetBrains KMP-wizard default, NOT Moko-coupled
}
```

**Rationale:** Each line dropped is unambiguously Moko-iOS-pipeline-specific. `export(libs.moko.resources)` references a library coordinate that disappears with the swap. The `multiplatformResources { ... }` DSL is a Moko-only Gradle configuration block (CMP Resources uses different conventions). `isStatic = true`, in contrast, is a Compose Multiplatform framework-shape decision orthogonal to the resources substrate — JetBrains' own KMP project wizard generates `isStatic = true` as default for iOS frameworks in 2026, and the Mobile #2 build that shipped with `isStatic = true` worked because static framework is correct for KMP shared modules, not because Moko demanded it.

**Alternatives considered:**

- **Drop `isStatic = true` along with the Moko-specific lines** (the original Round-1 proposal of this decision). Rejected post-review — Round-1 substrate-rationale lens found JetBrains KMP wizard's 2026 output ships `isStatic = true` as default for iOS frameworks; the "Moko-coupled" framing was incorrect. Dropping it would be a speculative behavior change (potential app-startup regression on iOS for no compensating benefit).
- **Keep everything in the iOS framework block as-is, only drop the `multiplatformResources` outer block.** Could work — `export(libs.moko.resources)` referencing a now-removed library would just be a compile error, alerting us to the issue at build time. But that's a slower feedback loop than removing the line proactively (the catalog removal happens in Decision 3; the iOS-framework `export(...)` referencing it must go with it).

### Decision 7: Grep-based "no hardcoded UI strings" verification retargets from `MR.strings` to `Res.string`

**Choice:** Update the grep heuristic shape (currently archived in `shared-resources-moko-bootstrap` tasks.md Section 8) to accept `Res.string` / `Res.drawable` accessors instead of `MR.strings` / `MR.images`. This change ships an UPDATED grep heuristic in its own tasks.md Section 8 (re-derived from Mobile #2's shape, not just diff-copied — since the grep is a verification step, not a deliverable, it lives in tasks.md not in spec scenarios). The existing `FOLLOW_UPS.md` entry `mobile-negative-requirement-ci-grep` (at FOLLOW_UPS.md:735 — proposes the future OpenSpec change `mobile-negative-requirement-detekt-rule`) is updated to reference the new accessor pattern.

**Grep shape (lives in this change's tasks.md Section 8):**

```bash
# Pass: zero hardcoded UI string literals inside :mobile:app composable call sites.
# Accept: stringResource(Res.string.X), Res.string.X (direct), or // hardcoded-string-allow: annotated.
grep -rEn 'Text\(\s*"[^"]+"' mobile/app/src/commonMain/ mobile/app/src/androidMain/ mobile/app/src/iosMain/ | \
    grep -vE '(stringResource\(Res\.string|Res\.string\.|//.*hardcoded-string-allow:)' && \
    { echo "FAIL: hardcoded UI string literals in mobile sources"; exit 1; } || \
    { echo "OK: no hardcoded UI string literals found"; exit 0; }
```

**Rationale:**

- Honest about current enforcement state (Detekt rule still doesn't exist).
- Lightest-touch fix matching Mobile #2's precedent: grep is the canonical backstop until the Detekt rule lands.
- Spec scenario "Grep verification reports zero hardcoded UI string literals" in `shared-resources/spec.md` (existing) updates to reference the new accessor pattern.

**Alternatives considered:**

- **Author the Detekt rule in this change.** Out of scope per the existing `FOLLOW_UPS.md` entry and per project precedent (one rule per change).
- **Drop the verification altogether.** No — the invariant "no hardcoded UI strings" is still real; the grep is still the backstop.

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| ~~iOS framework link fails after dropping `isStatic = true`~~ — **RESOLVED post-review:** Decision 6 amended to KEEP `isStatic = true` (it's the JetBrains KMP-wizard 2026 default, not Moko-coupled). Risk eliminated by not making the speculative change in the first place. | N/A — risk removed by amended Decision 6. |
| CMP Resources' `stringResource(Res.string.X, "1.0")` format-arg substitution behaves subtly differently from Moko's on iOS (e.g., `%1$s` vs `%@` normalization quirks per platform) | Mobile #2's `shared-resources/spec.md` § "home_placeholder_version format substitution renders correctly at runtime" requirement already exists. The corresponding scenario MUST be re-run against the CMP Resources implementation; the spec MODIFIED delta in this change updates the scenario to reference `Res.string.X` instead of `MR.strings.X`, but the runtime-substitution contract is preserved bit-for-bit. |
| Plus Jakarta Sans font loading via CMP Resources' `Font(Res.font.X)` accessor produces visibly different rendering vs Moko's `MR.fonts.X.asFont()` (e.g., different default weight resolution) | Both APIs ultimately load the same `.ttf` file via platform font-loading APIs. Visual diff during build verification step (compare APK + iOS framework rendering of `HomeScreen.kt`'s placeholder title). Defensive: `NearYouTypography`'s existing `if (brandFont == null) return Typography()` early-return guard catches any "font failed to load" case and falls back to platform sans-serif. |
| CMP Resources' generated `Res` class import path differs from Mobile #2's `MR` import path in non-obvious ways | The exact CMP-generated import path is determined at codegen time (typically `<project>.shared.resources.generated.resources.Res` or similar). Resolve at apply time when running the first `./gradlew :shared:resources:generateComposeResClass` or equivalent task; pin the import in `HomeScreen.kt` accordingly. tasks.md Section 4 verifies. |
| Removing Moko's `multiplatformResources` block in `:mobile:app/build.gradle.kts` (Decision 4) — the iOS framework copy task it wires might be necessary even with CMP Resources, just under a different mechanism | CMP Resources' Gradle plugin handles iOS framework integration through its own task wiring, NOT through a consumer-side resource plugin. Verify via `./gradlew :mobile:app:linkDebugFrameworkIosSimulatorArm64` succeeding AND inspecting the produced `.framework` bundle for the bundled resources. If they're missing, the workaround needs a CMP-specific equivalent; document the gap + restore as needed. |
| `gradle/libs.versions.toml` removals (Decision 3) cascade-break any other module that secretly references `moko-resources` (currently believed to be only `:shared:resources` + `:mobile:app`) | Pre-verify via `grep -rn "moko" --include="*.kts" .` across the repo before removing the catalog entries. The proposal already enumerated the consumer set (only the 2 modules). If anything else surfaces, abort + amend Decision 3. |
| Doc-wording updates (5 surfaces) accidentally drift between the change branch and pre-merge canonical docs — same `<file>:<line>` reference becomes stale due to a parallel update on `main` | Use stable anchor text matching (e.g., grep + sed) rather than line-number-pinned edits. Re-verify the 5 doc-update locations as the FIRST step of `/opsx:apply` (before any code edits) — if any anchor text has shifted, surface the drift before proceeding. |
| Pre-implementation re-check (per PR #118 rule) treats this change AS its first test case — risk of confirmation bias (we're swapping TO CMP Resources, of course the re-check confirms CMP Resources) | The re-check WAS done in the conversation that authored this proposal (2026-05-28). Outcome documented in `proposal.md` § Pre-implementation re-check status. The reasoning is auditable from the conversation transcript. If a reviewer believes the re-check was insufficient, they can request a fresh independent search before `/opsx:apply` runs. |

## Migration Plan

This is a **build-time-only substrate swap**. Zero runtime impact — the same final strings, drawables, and font reach the user; only the build-time accessor API and on-disk layout change.

1. **Pre-merge (apply-time gates):**
   - `./gradlew :mobile:app:assembleDebug` green (Android APK produced; resources bundled).
   - `./gradlew :mobile:app:linkDebugFrameworkIosSimulatorArm64` green (iOS framework produced; resources bundled inside `.framework`).
   - `./gradlew :shared:resources:test` green (existing tests pass; runtime substitution test for `home_placeholder_version` passes against `Res.string` accessor).
   - `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test :mobile:app:check` green.
   - Section 8 grep verification (per Decision 7) passes — zero hardcoded UI string literals reported.

2. **Squash-merge to `main`** — staging auto-deploys via `.github/workflows/deploy-staging.yml` but the deploy is a no-op since backend code is untouched. No staging smoke needed (Section 6 → N/A per `proposal.md`).

3. **Post-merge** — local dev verification: clone fresh, run `./gradlew :mobile:app:assembleDebug` + open in Android Studio + run on emulator → confirm `HomeScreen` still renders with brand colors + Plus Jakarta Sans + correct logo variant (light/dark) + correctly substituted version string. Toggle system dark mode + confirm same rendering with dark scheme + dark logo.

**Rollback plan:** Standard `git revert <squash-commit>` if the swap surfaces a runtime regression in mobile testing post-merge. Since there's no live user surface, rollback is a normal-priority recovery, not an incident.

## Open Questions

1. **CMP Resources generated `Res` class import path.** The exact import path the CMP Gradle plugin generates is configuration-dependent (`compose.resources { publicResClass = true / packageOfResClass = "..." }`). Resolve at apply time during the first codegen invocation; pin the import in `HomeScreen.kt` accordingly. The tasks.md Section 4 update step verifies the resolved import works on both Android + iOS targets.

2. **Should `:mobile:app/build.gradle.kts` retain ANY resource-related Gradle configuration after dropping the dual `mokoResources` plugin?** CMP Resources expects zero consumer-side configuration in the typical case. If any iOS-framework-specific wiring turns out to be needed (e.g., explicit `compose.resources { ... }` block at the consumer level), document + add in apply phase.

3. ~~**`isStatic = true` retention question (Decision 6).**~~ **RESOLVED post-Round-1-review.** Decision 6 amended to KEEP `isStatic = true` — it's the JetBrains KMP-wizard 2026 default, not Moko-coupled. No apply-time experiment needed; the framework declaration retains the existing flag.

4. **`docs/09-Versions.md` Moko row treatment.** Two options: (a) DELETE the Moko row entirely (since the pin is removed); (b) AMEND the Moko row with a "**SUPERSEDED 2026-05-28** by `shared-resources-swap-to-cmp-resources` per PR [#118](https://github.com/aditrioka/nearyou-id/pull/118) pre-implementation re-check rule" note + ADD a new row documenting the swap. **Lean: option (b)** (amend + add new row) — preserves the decision history for future-archaeology readers who wonder "why did this swap happen?" without forcing them to dig through OpenSpec archives. Confirm at apply time.

5. **Does `:shared:resources` need to update its Android `namespace`?** Mobile #2 used `id.nearyou.resources`. CMP Resources may or may not require namespace adjustment for its codegen output. Verify at apply time; the existing namespace likely works (it's an Android library namespace, independent of resource-codegen path).
