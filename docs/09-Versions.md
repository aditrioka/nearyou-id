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
| lettuce-core | 6.5.0.RELEASE | 2026-04-25 | First Redis client on the JVM classpath; introduced by `like-rate-limit` change for the rate-limit infrastructure (PR [#37](https://github.com/aditrioka/nearyou-id/pull/37)). Lettuce is the mainstream Netty-based Redis client (vs Jedis); 6.5.x line is current stable. Sync API used initially; async/reactive can be revisited if a benchmark shows it matters. | 2026-Q3 |
| com.auth0:java-jwt | 4.5.0 | 2026-04-27 | Promoted from transitive (already pulled in by `io.ktor:ktor-server-auth-jwt-jvm`) to a direct, version-pinned dependency in the new `:infra:oidc` module introduced by `suspension-unban-worker` (PR [#57](https://github.com/aditrioka/nearyou-id/pull/57)). Used by `GoogleOidcTokenVerifier` to parse and verify Google-issued OIDC tokens for Cloud-Scheduler-invoked `/internal/*` endpoints. Pinned to the version Ktor 3.4.x already resolves so no surprise minor bump on a Ktor upgrade. | 2026-Q3 |
| com.auth0:jwks-rsa | 0.23.0 | 2026-04-27 | Same provenance as `java-jwt` above — promoted from transitive to direct in `:infra:oidc`. Provides the `JwkProvider` cache abstraction with rotation-aware refresh used by `GoogleOidcTokenVerifier` against `https://www.googleapis.com/oauth2/v3/certs`. Pinned to Ktor 3.4.x's resolved version. | 2026-Q3 |

(Backfill of wizard-defaulted entries is deliberately deferred — entries land here as we *change* a pin or *re-decide* one we want to actively justify.)
