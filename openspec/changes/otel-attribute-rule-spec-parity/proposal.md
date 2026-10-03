## Why

The canonical `observability-otel-foundation` spec § "Forbidden span attributes" forbids more than `OtelForbiddenAttributeRule` enforces. The rule shipped in [PR #99](https://github.com/aditrioka/nearyou-id/pull/99) (`otel-attribute-lint-rule`) deliberately deferred four gaps, each now tracked as a `follow-up` issue: generic user-id aliases (`principal` / `actor` / `subject` / `owner`, [#177](https://github.com/aditrioka/nearyou-id/issues/177)), `*location*` / `*lat*` / `*lng*` / `*coord*` keys ([#178](https://github.com/aditrioka/nearyou-id/issues/178)), opaque secrets such as OAuth client secrets and refresh tokens ([#179](https://github.com/aditrioka/nearyou-id/issues/179)), and the `"user_id"` carve-out ([#180](https://github.com/aditrioka/nearyou-id/issues/180)). All four were deferred for the same reason: the rule matches string literals *anywhere*, so a key-name check on `user_id`, `lat` or `principal` would fire on SQL column names, `@SerialName` keys, route parameters and ordinary words like `latency` or `cloud.platform`. A single PSI check for whether a literal is used as an OTel attribute key fixes all four deferrals. This change adds that check, so the spec and the rule agree again: each forbidden category is either enforced by the lint rule or named in the spec as covered only by runtime stripping, sentinel tests and code review.

## What Changes

- **New PSI-context-restricted mode in `OtelForbiddenAttributeRule` (closes #180 and is the base for #177/#178/#179 key checks).** A literal is in an *attribute-key position* when it is (P1) the key argument of a `setAttribute(...)` call; (P2) the argument of an `AttributeKey.stringKey/longKey/booleanKey/doubleKey/*ArrayKey(...)` factory; (P3) the key argument of a 2-argument `put(...)` whose receiver is evidenced as an OTel `AttributesBuilder` (so a generic `Map.put` or `buildJsonObject { put(...) }` does not count); or (P4) a map-entry key inside `mapOf(...)` passed as `withSpan(...)`'s `attributes`, either directly or through a `val` defined in the same file. Four checks run only in that position:
  - **`"user_id"` is enforced again** (key match, any value). The Group A carve-out is removed. The ~30 existing SQL-column / `@SerialName` / route-param / `buildJsonObject` uses stay quiet because none of them is an attribute-key position.
  - **User-identity aliases checked by value (#177).** A key whose tokens include `user`, `enduser`, `principal`, `actor`, `subject`, `owner` or `account` fires only when its value is *raw-identifier-evidenced*. That means: a UUID-shaped literal, a `UUID.randomUUID()` / `fromString()` / `nameUUIDFromBytes()` call, a name declared in the same file as `UUID`, or a name following the `*UserId` / `user.id` / `.sub` convention. `setAttribute("principal", "system")` and `setAttribute("user.id", UserIdHasher.hash(...))` do NOT fire.
  - **Location key tokens (#178).** A key whose tokens include `location`, `lat`, `latitude`, `lng`, `lon`, `longitude`, `coord(s)` or `coordinate(s)` fires (e.g. `geo.lat`, `actual_location`, `userCoords`), unless its first token is `display`. `display_location` (the HMAC-fuzzed coordinate every non-admin read already returns) and values derived from it are the explicitly sanctioned keys. Because keys are split into tokens, `latency_ms`, `cloud.platform` and `memory.allocation` do not fire, and SQL column names never do because they are not attribute keys.
  - **Credential key tokens (#179, key side).** A key whose tokens include `password` / `passwd` / `secret` / `bearer` / `authorization` / `apikey`, or the token pairs `refresh token` / `access token` / `api key` / `service role`, fires (e.g. `client_secret`, `http.request.header.authorization`, `refreshToken`).
- **Tier 2 value regexes extended for opaque secrets (#179, value side; still fires anywhere).** New prefix-anchored patterns, each tied to a vendor secret the backend actually holds: Google OAuth client secret `GOCSPX-…`, Google OAuth access token `ya29.…`, Google OAuth refresh token `1//…`, Supabase secret API key `sb_secret_…`, Grafana Cloud token `glc_…`, OpenAI key `sk-proj-` / `sk-svcacct-` / `sk-admin-…`, and RevenueCat/Stripe-style secret key `sk_…`. The Redis-URI-with-password pattern is widened to `rediss://` (TLS, the Upstash shape) and to the user-less `redis://:password@` form. The spec's PEM regex text is corrected to match the shipped code (`[A-Z ]*`). The spec currently says `+`, but the code also catches the label-less PKCS#8 `BEGIN PRIVATE KEY` used by GCP service-account JSON.
- **MODIFY `observability-otel-foundation`:**
  - "Forbidden span attributes": names the exact alias and location sets, sanctions `display_*` keys explicitly, and states that **project-issued refresh tokens and other prefix-less opaque secrets are covered by runtime stripping, sentinel tests and review only** (no regex can recognise them).
  - "`OtelForbiddenAttributeRule` fences…": drops the `user_id` carve-out and adds the Tier 2 patterns.
  - "Detekt test coverage…": new scenario items; the sync guard now has zero carve-outs.
  - "Detekt run … remains green": audit scope covers every module the custom ruleset scans.
  - One ADDED requirement specifies the attribute-key-position mode.
- **Spec amendment, stated explicitly:** the current bullet "Even `display_location`-derived numbers are not currently sanctioned for span attributes" is **reversed**. `display_location` and its derived `display_*` keys become the sanctioned exception the bullet's own "unless explicitly sanctioned" clause anticipates. Rationale: the value is already HMAC-fuzzed and already returned to every API client, so emitting it in telemetry exposes nothing new. The spatial-fuzzing invariant (non-admin paths use `display_location`) is unchanged.
- **Docs:** `docs/04-Architecture.md` § Instrumentation lists the mandatory hashed-user attribute as `user_id`. The real key is `user.id`, and `user_id` is stripped at runtime and now lint-forbidden as an attribute key, so the wording is corrected.
- Run `./gradlew detekt` across the repo after the rule changes. Any new production finding is fixed at the source or sanctioned with `@AllowForbiddenSpanAttribute("<reason>")`; the rule is never loosened to make it pass.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `observability-otel-foundation`: `OtelForbiddenAttributeRule` gains the attribute-key-position mode (ADDED requirement) and wider Tier 2 coverage. The forbidden-attributes contract now matches the lint rule: the `user_id` carve-out and the three "deferred to follow-up" statements are removed, and the `display_*` location keys are explicitly sanctioned.

## Impact

- **Code:** `lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/OtelForbiddenAttributeRule.kt` (new PSI helpers + key-token sets + Tier 2 regexes + KDoc rewrite) and `lint/detekt-rules/src/test/kotlin/id/nearyou/lint/detekt/OtelForbiddenAttributeLintTest.kt` (new scenarios; the `user_id` carve-out test flips to "SQL / JSON / route uses pass, attribute-key use fires"). No production behavior change is expected, and the detekt run verifies that.
- **Detekt scope:** `:backend:ktor` + every `nearyou.detekt` module (`:infra:*`, `:core:*`). `:mobile:app`'s config enables only `TestLoginIsolationRule`, so it is unaffected.
- **Schema / APIs / dependencies / runtime:** none. `ForbiddenAttributeStripper.FORBIDDEN_KEYS` is unchanged.
- **Out of scope:**
  - Type-resolved analysis (`detektMain`). The project runs plain syntactic `detekt`, so value evidence uses PSI plus naming conventions.
  - Key names built dynamically by string concatenation (already documented).
  - Generic user-referencing keys outside the alias token set (`author_id`, `sender_id`, …). These stay covered by sentinel tests and review.
  - Mobile telemetry.
- Closes #177, #178, #179, #180.
