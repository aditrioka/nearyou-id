# Phase 3 — staging-flavor test-login (auth bypass) on the internet-facing staging build

Status: **DESIGNED + IMPLEMENTED (regular infra PR).** Sibling of [Phase 2](PHASE-2-dev-test-login.md)
(dev-only test-login), extended to reach the **authenticated staging Home on a real device** (via `adb`,
and drivable in Maestro by pasting the printed URL into an `openLink` step) without interactive Google
sign-in against `api-staging.nearyou.id`. A dedicated, pre-wired staging Maestro flow is a follow-up (see
§ Follow-ups). This is **not** an OpenSpec change — per CLAUDE.md § "When NOT to use OpenSpec" (tooling /
Detekt rules / build wiring → regular PR); this note is the short design record the change warrants.

## Why this is needed

Same as Phase 2: `auth-gated` flows stop at Sign-In because Google/Apple social login can't be driven by
automation, and on a real device the operator wants to land on the authenticated Home for a demo without
the OAuth round-trip. Phase 2 solved this for **dev** (localhost backend). This phase solves it for
**staging** (the internet-facing Cloud Run + Supabase environment).

## The crux: staging is different from dev

**Dev accepts any dummy JWT** because the dev flavor talks to a local backend (`10.0.2.2:8080`) you
control. **Staging validates the bearer JWT's RSA signature on every request** (`auth/AuthPlugin.kt`:
`JWT.require(Algorithm.RSA256(keys.publicKey, keys.privateKey))`). So an injected token is only accepted
if it is **signed by the staging RSA key** with the exact claims the backend checks.

What the staging backend actually verifies per request (and nothing more):

| Check | Source | Implication for the forged token |
|---|---|---|
| RS256 signature | `auth/jwt/JwtIssuer.kt` (`Algorithm.RSA256`) | must be signed by `staging-ktor-rsa-private-key` |
| `exp` in the future | `JWT.require(...).build()` | TTL 900s (`ACCESS_TOKEN_TTL_SECONDS`) |
| `sub` ∈ `public.users` | `AuthPlugin.kt` → `users.findById(UUID(sub))` | a seeded staging user must exist |
| `token_version` matches the row | `AuthPlugin.kt` validate block | claim must equal `users.token_version` |
| not banned / not suspended | `AuthPlugin.kt` validate block | user row must be active |

The minted token's header `kid` reads `dev-1` (the `RsaKeyLoader` default) but `kid` is never validated
(`AuthPlugin` builds a single-key RS256 verifier, not a JWKS/`kid` lookup), so the value is cosmetic.
There is **no `aud` and no `iss`** on the user access token, and the validator checks neither. The
refresh token is an **opaque server-side value (not a JWT)**; the injected `refresh` is inert until the
client calls `/api/v1/auth/refresh` (where it 401s and the session self-clears) — so a single mint is a
~15-minute window, by design.

## The key security fact (why this is cheap + safe)

The deep-link activity **only WRITES a token** into `SecureTokenStore`. The backend signature check is the
real gate. Therefore:

- A **remote** attacker can't reach a useful state: they'd need a token signed by the staging private key,
  which they don't have. Triggering the deep-link with a junk token just writes a token the backend
  rejects on the first call → `SessionInvalidator` clears it → back to Sign-In.
- A **local** attacker who can `adb shell` your device already owns the device; the exported activity adds
  nothing they couldn't otherwise do.

⇒ **The backend is NOT modified. The marginal attack surface is one exported, signature-gated entry point
on the staging *debug* build only.** (A `/internal-dev/test-login` backend endpoint was rejected in Phase 2
for adding real production-shipped surface; that decision stands — we mint offline with the existing
`JwtIssuer`.)

### Operator sign-off (2026-06-08)

Adding an exported entry point to the internet-facing staging build was surfaced for explicit approval
before implementation. **Approved — staging-DEBUG only.** Rationale captured below.

## The guard (defense in depth)

1. **Flavor + build-type source-set isolation (PRIMARY).** All new Kotlin + the manifest entry live under
   `mobile/app/src/stagingDebug/` (staging flavor **× debug build type**). AGP merges `src/stagingDebug/**`
   **only** into the `stagingDebug` variant — it is physically absent from `stagingRelease` and from every
   production APK. This is stronger than a runtime `BuildConfig.DEBUG` check (nothing to bypass / reverse).
   - **Why debug-only here, but flavor-wide (`src/dev`) for dev?** Dev is localhost-only, so build-type
     scoping is irrelevant. Staging is **internet-facing**; scoping to `stagingDebug` keeps the bypass out
     of any staging *release* artifact that might get wider distribution (Play internal track / Firebase
     App Distribution). It costs nothing: both real use cases — on-device manual testing and Maestro E2E —
     build debug variants anyway.
2. **`TestLoginIsolationRule` Detekt rule (DEFENSE-IN-DEPTH).** A custom rule
   (`lint/detekt-rules/.../TestLoginIsolationRule.kt`, mirroring `RawFromPostsRule` + its path allowlist)
   fails the build if any class/object/named-function declaration whose name contains the CamelCase
   `TestLogin` token is declared **outside** `src/dev/` or `src/stagingDebug/`. Note `src/staging/`
   (flavor-wide) is deliberately **not** allowlisted, so the rule also mechanically enforces the
   staging-debug-only decision. There is no annotation/package escape hatch (unlike `RawFromPostsRule`) —
   there is no legitimate reason to declare a test-login symbol in a shipping source set. (The pattern is
   the capital-`T` `Test[Ll]ogin` token, so routine names like `latestLogin` do not false-positive.)
   - The mobile module did not previously run Detekt (only `backend/ktor` did). This change wires Detekt
     into `:mobile:app` (`alias(libs.plugins.detekt)` + `detektPlugins(projects.lint.detektRules)` +
     `mobile/app/config/detekt/detekt.yml`, all builtin rulesets disabled — mirrors backend/ktor), and
     points `source.setFrom` at the **whole `src` tree** (not a hand-enumerated source-set list, which
     would drift and could silently omit a shipping set such as flavor-wide `src/staging/`); the rule's
     own path allowlist then passes only `src/dev/` + `src/stagingDebug/`. `./gradlew detekt` (unqualified)
     now covers both modules. The same rule is also enabled in `backend/ktor/config/detekt/detekt.yml` to
     guard against re-introducing a backend test-login symbol.

## Token lifetime — best-practice note

The minted access token is **short-lived (900s)** — the existing `JwtIssuer` TTL, reused as-is. This is the
OWASP / industry standard (5–15 min access tokens): JWTs can't be cheaply revoked, so the TTL **is** the
exposure window, and a minted bypass token tends to land in shell history / Maestro logs / screenshots, so a
long TTL is the anti-pattern. Convenience mitigation: re-run `mint-staging-jwt.sh` (one command) to refresh.
No backend signing code was changed.

## Design (chosen)

```
mint-staging-jwt.sh ── gcloud secrets access staging-ktor-rsa-private-key ──► KTOR_RSA_PRIVATE_KEY
        │  KTOR_RSA_PRIVATE_KEY=… mint-dev-jwt.sh <uuid> <ver>  →  :backend:ktor:mintDevJwt
        │                                                          → JwtIssuer.issueAccessToken (real claims)
        │  prints:  adb shell am start -d "nearyou-staging://test-login?access=<jwt>&refresh=<jwt>&exp=<ms>"
        ▼                                                                                id.nearyou.app.staging
[stagingDebug APK only] StagingTestLoginActivity ── tokenStore.write(TokenPair(...)) ──► SecureTokenStore
        │  start MainActivity
        ▼
RootRouterScreen: AuthFlow.isAuthenticated() == true  ──►  staging Home  (every request: RSA-signed JWT ✔)
```

## File-by-file

1. **`mobile/app/src/stagingDebug/kotlin/id/nearyou/app/staging/StagingTestLoginActivity.kt`** (NEW) —
   mirror of `DevTestLoginActivity`; reads `access`/`refresh`/`exp`, `tokenStore.write(TokenPair(...))`,
   launches `MainActivity`. Package `id.nearyou.app.staging`.
2. **`mobile/app/src/stagingDebug/AndroidManifest.xml`** (NEW) — exported `StagingTestLoginActivity` with an
   intent-filter for scheme `nearyou-staging`, host `test-login`. Distinct scheme from dev (`nearyou-dev`)
   so both can be installed side-by-side without a chooser.
3. **`lint/detekt-rules/.../TestLoginIsolationRule.kt`** (NEW) + registration in `NearYouRuleSetProvider` +
   **`.../TestLoginIsolationRuleTest.kt`** (NEW).
4. **`mobile/app/build.gradle.kts`** — apply Detekt, `detektPlugins(projects.lint.detektRules)`, `detekt {}`
   block (disable builtin rulesets, `source.setFrom(files("src"))` — scan the whole tree; the rule's
   allowlist passes only dev/stagingDebug).
   **`mobile/app/config/detekt/detekt.yml`** (NEW) — enable `nearyou` ruleset + `TestLoginIsolationRule`.
5. **`backend/ktor/config/detekt/detekt.yml`** — also enable `TestLoginIsolationRule`.
6. **`dev/scripts/mint-staging-jwt.sh`** (NEW) — pulls the staging key from GCP Secret Manager, reuses
   `mint-dev-jwt.sh` (so the claim set never drifts from the backend), prints the ready-to-paste deep-link.

## Seeding / confirming the staging test user

The forged token's `sub` must be a real, active staging `users` row. A canonical one already exists:
`smoketest_adi` (id `986142e3-0f12-43fd-92a5-cf40e1b70bd4`, `token_version 0`) — the script's default.

Confirm it (read-only) via the Supabase MCP (`mcp__supabase__execute_sql`, allowlisted for staging):

```sql
SELECT id, username, token_version, is_banned, suspended_until, is_shadow_banned, deleted_at
FROM users WHERE id = '986142e3-0f12-43fd-92a5-cf40e1b70bd4';
```

`is_banned` + `suspended_until` are the AuthPlugin auth gates (a row failing either is rejected at
validation). `is_shadow_banned` + `deleted_at` are **not** auth gates — they govern downstream content
visibility (the `visible_*` views), so for a useful demo the user should also be neither.

To seed a NEW user, generate a UUID and INSERT the minimal column set (note destructive staging writes are
classifier-gated — run the INSERT via the Supabase dashboard SQL editor or the allowlisted MCP):

```sql
INSERT INTO users (id, username, display_name, date_of_birth, google_id_hash, invite_code_prefix)
VALUES ('<uuid>', '<unique-username>', 'Staging Test', DATE '1990-01-01',
        '<sha256hex>', '<UNIQUE8>');
```

(Pooler psql fallback — host `aws-1-ap-southeast-1.pooler.supabase.com:6543`, user
`postgres.hvlbfbuuorhackrlbouo`, password via `gcloud secrets versions access latest
--secret=staging-db-password --project=nearyou-staging` — is documented in the repo ops notes.)

## Verification (DO NOT skip — it's an auth bypass)

1. Lint gate: `./gradlew ktlintCheck detekt :lint:detekt-rules:test` — `:lint:detekt-rules:test` exercises
   `TestLoginIsolationRule` (fires in shipping source sets, passes under `src/dev` / `src/stagingDebug`,
   still fires under flavor-wide `src/staging`); `detekt` now also runs over `:mobile:app`.
2. Compile the staging variant: `./gradlew :mobile:app:assembleStagingDebug` (the dev-flavor unit-test
   gate `testDevDebugUnitTest` does NOT compile `src/stagingDebug`).
3. **Production-absence check (mandatory):** `./gradlew :mobile:app:assembleProductionDebug` then confirm
   the merged manifest has no `StagingTestLogin`. Use `find` — a `**` glob does NOT recurse in a default
   shell and would falsely print nothing (a false "absent = pass" on the most security-critical step):
   `find mobile/app/build/intermediates -path '*productionDebug*' -name AndroidManifest.xml -exec grep -l StagingTestLogin {} +`
   → **no output** (absent), while the same `find` with `*stagingDebug*` **prints a match** (present).
4. On-device: install a `stagingDebug` build, run `dev/scripts/mint-staging-jwt.sh`, paste the printed
   `adb shell am start …` → app routes to the authenticated staging Home (timelines load against
   `api-staging.nearyou.id`, proving the injected token passes the real signature check).

## iOS gap

Flavor source sets are Android-only (AGP). iOS has no flavor concept; an iOS staging test-login would need
a scheme/xcconfig-gated `iosMain` path. **Scoped to Android** (matches Phase 2 + the current Maestro app
IDs). Tracked under [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md) § Phase 3 iOS (where the
other iOS-launch-gated mobile work lives), conditioned on Maestro needing to run on the iOS sim against
staging.

## Follow-ups

- **Wired staging Maestro flow.** The deep-link is drivable in Maestro today by pasting the URL the script
  prints into an `openLink` step, but the only committed auth sub-flow (`mobile/app/maestro/flows/_auth/test-login.yaml`)
  hardcodes the `nearyou-dev://` scheme + dev app id. A pre-wired staging flow (parameterize the scheme/app-id
  via `-e` from `maestro-run.sh`, or add a staging variant flow) is deferred — not required for the on-device
  demo path this change targets.
- **iOS staging test-login** — see § iOS gap (tracked in `docs/08-Roadmap-Risk.md` § Phase 3 iOS).
- **shellcheck** — the repo has no shellcheck CI lane; `mint-staging-jwt.sh` passed `sh -n` but was not
  run through shellcheck (not installed locally).
