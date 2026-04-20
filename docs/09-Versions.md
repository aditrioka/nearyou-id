# NearYouID — Version Pinning Decisions Log

Single source of truth for the *why* behind each library version pin in `gradle/libs.versions.toml`.

## Pinning Policy

- All third-party library and plugin versions are declared in `gradle/libs.versions.toml`. Modules reference them via the typesafe `libs.*` accessor; raw `group:artifact:version` strings are forbidden (CI lint will enforce when added).
- Versions are **frozen** at Pre-Phase 1 baseline. Changes happen via deliberate bumps recorded in the table below — not via "latest stable" auto-resolution.
- Patch-level bumps (X.Y.**Z**) follow the auto-update flow (Dependabot/Renovate, separate change). Minor (X.**Y**) and major (**X**.Y) bumps require a row in the table justifying the change.

## Update Cadence

- **Patch**: monthly batch via Dependabot/Renovate PR (auto-merged after CI green).
- **Minor**: quarterly review; bump if a new minor unblocks a desired feature or fixes a CVE.
- **Major**: case-by-case; treated as a real change (proposal + design + tasks).
- **Security CVE**: immediate, out-of-band.

## Version Decisions

| Library | Version | Pinned on | Rationale | Next review |
|---------|---------|-----------|-----------|-------------|
<!-- example row: | kotlin | 2.3.20 | 2026-04-20 | KMP wizard scaffold default; aligns with Compose Multiplatform 1.10.x | 2026-Q3 | -->

(Backfill of wizard-defaulted entries is deliberately deferred — entries land here as we *change* a pin or *re-decide* one we want to actively justify.)
