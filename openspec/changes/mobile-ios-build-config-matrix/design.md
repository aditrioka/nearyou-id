## Context

`mobile-env-launcher-icons` (#155, archived `2026-06-06`) shipped a **safe subset** on iOS: a single committed `Staging` build configuration (resolving `AppIcon-Staging` + `id.nearyou.app.staging` + staging API) plus a shared scheme, verified headlessly via `xcodebuild -showBuildSettings`. It deliberately did NOT remove the `Debug`/`Release` `ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon` hardcodes, because a global removal would have regressed production (every config would resolve `AppIcon-Staging` since `Config.xcconfig` is the staging-flavored base, with no config left resolving cobalt).

Current iOS build-config state (on `origin/main` 46f6337):
- Configs: `Debug`, `Staging`, `Release` (project + target level each).
  - `Debug` (target) → `Pods-iosApp.debug.xcconfig`; APPICON hardcoded `AppIcon`.
  - `Release` (target) → `Pods-iosApp.release.xcconfig`; APPICON hardcoded `AppIcon`.
  - `Staging` (target) → `Staging.xcconfig` (anchor) → `Config.xcconfig` → `Pods-iosApp.debug.xcconfig`; no APPICON hardcode → resolves `AppIcon-Staging`.
- `Production.xcconfig` exists but is **orphaned** (no config bases off it).
- `iosApp/Podfile` is `pod 'app', :path => '../mobile/app'` with **no per-config mapping**.
- `Config.xcconfig` is the staging-flavored base (`PRODUCT_BUNDLE_IDENTIFIER = id.nearyou.app.staging`, staging API) and `#include`s `Pods-iosApp.debug.xcconfig`.

Canonical doc state: [`docs/04-Architecture.md`](../../../docs/04-Architecture.md):332-334 specifies iOS env separation as env-level `Staging` + `Production` xcconfig schemes — NOT an env×build-type matrix, NO iOS `dev` scheme. **The user explicitly chose the richer env×build-type matrix ("ala Android") AND to amend `docs/04` to match** (over the simpler env-level model), so this change diverges deliberately and updates the doc.

## Goals / Non-Goals

**Goals:**
- An env × build-type iOS build-configuration matrix (Android-parity), each config resolving the correct bundle id + API base URL + launcher icon, reproducible from a fresh checkout via committed shared schemes.
- A real `.nearyou.app` production build configuration resolving cobalt `AppIcon` + the placeholder production API.
- Per-config CocoaPods wiring that **links on a real build** (the part #155 could not verify) — verified via `pod install` + `xcodebuild build`.
- Remove the `Debug`/`Release` APPICON hardcodes so the launcher icon resolves from each config's xcconfig.
- Keep all icon assets byte-identical (this is build-config plumbing, not an icon change).

**Non-Goals:**
- No icon-asset change (`AppIcon-Staging.appiconset`, `AppIcon.appiconset`, Android flavor `colors.xml` untouched).
- No backend/schema/API/security surface; no `gradle/libs.versions.toml` change.
- No real production deploy (the production API stays a fail-fast placeholder until prod infra is provisioned).
- Not adding an iOS `dev` launcher icon by default (Decision 3 open question — dev reuses the staging tint unless the user opts in).

## Decisions

### Decision 1 — The build-configuration matrix (CENTRAL; confirm with user before `/opsx:apply`)

The user chose env×build-type "ala Android." Android in practice uses `devDebug` (local), `stagingDebug`/`stagingRelease` (on-device + TestFlight), `productionRelease` (store) — `devRelease` + `productionDebug` exist but are rarely used (dev is never distributed; the prod API is a placeholder today).

**Options:**
| Option | Configs | Notes |
|---|---|---|
| (a) user's pragmatic 4 | Dev Debug, Staging Debug, Prod Debug, Prod Release | as proposed; omits Staging Release (TestFlight staging should be release-optimized) |
| (b) **recommended 4** | Dev Debug, Staging Debug, Staging Release, Prod Release | covers local dev + staging on-device + staging TestFlight + prod store; drops the low-value Prod Debug |
| (c) practical 5 | (b) + Prod Debug | adds prod-debugging (low value while prod API is a placeholder) |
| (d) full symmetric 6 | Dev/Staging/Prod × Debug/Release | true Android parity; most maintenance + Podfile mappings |

**Recommendation: (b)** — 4 configs that cover every real workflow without the rarely-used `Dev Release` / `Prod Debug`. **Naming:** `"<Env> <Type>"` (`Dev Debug`, `Staging Debug`, `Staging Release`, `Prod Release`) so CocoaPods generates `Pods-iosApp.<config>.xcconfig` per name. **Alternatives considered:** env-level 3-config (rejected by user — wanted Android parity); full-6 (more surface than needed).

**✅ CONFIRMED (user, at apply 2026-06-06): option (a)** — `Dev Debug` / `Staging Debug` / `Prod Debug` / `Prod Release` (4 configs; staging is debug-only, prod has both debug + release). **iOS dev = `id.nearyou.app.dev` bundle id + a real `AppIcon-Dev` (forest green `#15803D`)** — full 3-icon iOS parity. Per-config resolution: `Dev Debug`→`.dev`+`AppIcon-Dev`+iOS-dev API; `Staging Debug`→`.staging`+`AppIcon-Staging`+staging API; `Prod Debug`/`Prod Release`→`.nearyou.app`+`AppIcon`+placeholder API.

> **Note on iOS `dev` bundle id.** Android dev uses `applicationIdSuffix = ".dev"`. For iOS, decide in the matrix whether `Dev Debug` uses `id.nearyou.app.dev` (true side-by-side) or reuses `.staging` (current base). Recommend `.dev` for parity if a `Dev.xcconfig` is added.

### Decision 2 — Per-configuration CocoaPods wiring (requires `pod install`)

Custom build configurations need CocoaPods to know about them, or `pod install` warns and the Pods base is wrong. Add to `iosApp/Podfile`:

```ruby
project 'iosApp/iosApp.xcodeproj',
  'Dev Debug' => :debug, 'Staging Debug' => :debug,
  'Staging Release' => :release, 'Prod Release' => :release
```

`pod install` then generates `Pods-iosApp.<config>.xcconfig` per config (debug- vs release-typed), and each **env xcconfig `#include`s its matching Pods xcconfig** (so env settings layer on top of the right Pods base).

**Apply-time refinement — per-config xcconfig files (verified canonical 2026-06-06).** The "env xcconfig `#include`s its matching Pods xcconfig" framing BREAKS for an env with two build-types: `Prod Debug` + `Prod Release` both carry `Production.xcconfig` env values but need DIFFERENT Pods xcconfigs (debug vs release), and one `Production.xcconfig` can't `#include` both. The canonical CocoaPods multi-config + multi-env pattern (per [Felgines](https://felginep.github.io/2021-01-21/xcode-configuration-multiple-environments) + [thoughtbot](https://thoughtbot.com/blog/let-s-setup-your-ios-environments) + CocoaPods issue #8459) is **per-CONFIGURATION xcconfig files**: each build configuration's `baseConfigurationReference` is its own `iosApp/Configuration/<Config>.xcconfig` (e.g. `Dev Debug.xcconfig`) which `#include`s (a) its env xcconfig (`Dev.xcconfig` — env values only) AND (b) its `Pods-iosApp.<config>.xcconfig` (the build-type-correct Pods base). The env xcconfigs (`Config`/`Dev`/`Staging`/`Production.xcconfig`) hold env values only and carry NO Pods include. This is the structure shipped at apply.

**Transitive-include caveat (must handle at apply — review F1).** `Staging.xcconfig` and `Production.xcconfig` both `#include "Config.xcconfig"`, and `Config.xcconfig` today unconditionally `#include`s `Pods-iosApp.debug.xcconfig`. xcconfig has **no "un-include"** — so a release-typed config (e.g. `Prod Release`) basing off `Production.xcconfig` would inherit the **debug** Pods include transitively, with last-include-wins layering that is easy to get backwards. The fix therefore REQUIRES **removing the hardcoded `#include "…Pods-iosApp.debug.xcconfig"` from `Config.xcconfig`** and moving the Pods include to the leaf (each env xcconfig `#include`s its own per-config Pods xcconfig), so the right (debug- or release-typed) Pods base is the only one in the chain. Verify per config that the resolved `PODS_*`/`OTHER_LDFLAGS` come from the matching Pods variant (Decision 2 ⇒ the §4.1 real build is the ultimate proof). `pod install` runs via `dev/scripts/ios-pod-install.sh` and self-bootstraps the Gradle compose-resources (per the `Podfile` comment) — it is heavy. **Why:** this is the canonical CocoaPods multi-config pattern; without it a release config silently links debug pods (or vice-versa). **Alternatives considered:** hand-editing the Pods xcconfigs (rejected — `pod install` overwrites them).

### Decision 3 — APPICON resolves from xcconfig per config (remove the hardcodes)

Remove the `Debug`/`Release` `ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon` hardcodes from `project.pbxproj` (`:307` / `:336`). This is now **safe** because a dedicated production config resolves cobalt via `Production.xcconfig` (`= AppIcon`). Resolution per config: `Staging *` → `AppIcon-Staging`, `Prod *` → `AppIcon`, `Dev *` → `AppIcon-Staging` (the **iOS dev icon stays deferred** per the FOLLOW_UP). Verified per config via `xcodebuild -showBuildSettings | grep APPICON`. **Open question:** the user MAY opt to add a real iOS dev icon (`AppIcon-Dev`, forest green `#15803D`) here instead of reusing staging — surface in the matrix confirmation.

### Decision 4 — pbxproj edit technique (reuse #155)

Reuse the #155 approach: a scripted Python edit (regex with **captured indentation** to avoid tab-matching errors), verified with `plutil -lint` + `xcodebuild -list` + `xcodebuild -showBuildSettings`, with `git checkout` as the revert safety net. **Recurring false-positive warning:** the "swapped Pods xcconfig" finding was misread by **3 separate review agents** across #155 — `Release` → `Pods-iosApp.release.xcconfig` and `Debug` → `Pods-iosApp.debug.xcconfig` is **CORRECT** (no swap). The exact opener→base→name line evidence is in `openspec/changes/archive/2026-06-06-mobile-env-launcher-icons/design.md` Decision 4. Do not "fix" it.

### Decision 5 — Amend `docs/04-Architecture.md` (sanctioned divergence)

`docs/04:333` ("iOS xcconfig schemes: `Staging`, `Production`") is updated to document the env×build-type matrix. The user explicitly authorized this amendment; `proposal.md` § What Changes states it; `tasks.md` includes the edit. This is the sanctioned "amend docs" path, not silent divergence.

## Risks / Trade-offs

- **`pod install` + real build is the heavy, less-reversible step** → Mitigation: do all pbxproj/xcconfig edits + headless `xcodebuild -showBuildSettings` verification FIRST (fully revertible); run `pod install` + `xcodebuild build` only after the static verification passes. Keep the Android side untouched (already shipped).
- **More configs = more Podfile mappings + Pods xcconfigs to maintain** → Mitigation: pick the leanest set that covers real workflows (Decision 1 option b); document the matrix in `docs/04`.
- **`pod install` regenerates `Pods/` + the `.xcworkspace`** (large diff, possibly gitignored) → Mitigation: confirm what's tracked vs gitignored before committing; commit only the intended project/xcconfig/Podfile + Podfile.lock changes.
- **Renaming/removing the default `Release` config could break tooling** that assumes `Debug`/`Release` → Mitigation: option (b)/(d) has no plain `Release` config, so the apply MUST affirmatively repoint both `XCConfigurationList`s' `defaultConfigurationName` to a present release-typed config (e.g. `Prod Release`) AND verify `xcodebuild` with no `-configuration` still resolves (and CI/`gh` workflows that assume `Release` are checked). Keep a `Debug`-typed config present for local runs.
- **Spec coupling:** removing the hardcode makes the `shared-resources` "iOS staging build selects a staging-tinted AppIcon" scenario's "Release resolves AppIcon via retained hardcode" clause stale → this change MODIFIES that scenario to be matrix-agnostic (cobalt via the production config; no hardcode remains).

## Migration Plan

No runtime migration. Production end-user experience unchanged (production assets byte-identical; the production API stays a fail-fast placeholder). Rollback = revert the pbxproj/xcconfig/Podfile edits + `git checkout` the Pods regeneration; nothing is deployed or user-visible. Staging deploy unaffected (build-config is a client concern, not a Cloud Run config).

## Open Questions

- **The matrix (Decision 1)** — confirm the config set (recommend option b: Dev Debug / Staging Debug / Staging Release / Prod Release) + the iOS `dev` bundle id (`.dev` vs reuse `.staging`) with the user before the heavy apply.
- **iOS dev icon (Decision 3)** — add a real `AppIcon-Dev` (`#15803D`) now, or keep dev reusing the staging tint (current default)?
