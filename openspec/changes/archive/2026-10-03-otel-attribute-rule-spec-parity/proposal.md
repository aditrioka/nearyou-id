## Why

The canonical `observability-otel-foundation` spec § "Forbidden span attributes" forbids more than `OtelForbiddenAttributeRule` enforces. [PR #99](https://github.com/aditrioka/nearyou-id/pull/99) (`otel-attribute-lint-rule`) shipped the rule with four gaps deliberately deferred, each now tracked as a `follow-up` issue:

| Issue | Gap |
|---|---|
| [#177](https://github.com/aditrioka/nearyou-id/issues/177) | Generic user-id aliases (`principal`, `actor`, `subject`, `owner`) |
| [#178](https://github.com/aditrioka/nearyou-id/issues/178) | `*location*` / `*lat*` / `*lng*` / `*coord*` keys |
| [#179](https://github.com/aditrioka/nearyou-id/issues/179) | Opaque secrets (OAuth client secrets, refresh tokens) |
| [#180](https://github.com/aditrioka/nearyou-id/issues/180) | The `"user_id"` carve-out |

All four were deferred for the same reason. The rule matches string literals *anywhere*, so a key-name check on `user_id`, `lat` or `principal` would fire on SQL column names, `@SerialName` values, route parameters, and words like `latency` or `cloud.platform`.

One PSI "is this literal used as an OTel attribute key?" check removes that obstacle for all four. This change adds it, so the spec and the rule agree again: every forbidden category is either enforced by the lint, or explicitly named as defended by code review (plus a layer-3 sentinel where one exists).

## What Changes

### New check: attribute-key position (closes #180; also the base for the #177, #178 and #179 key checks)

A literal is in an *attribute-key position* when it is one of:

- (P1) the key argument of a `setAttribute(...)` call;
- (P2) the argument of an `AttributeKey.stringKey/longKey/booleanKey/doubleKey/*ArrayKey(...)` factory;
- (P3) the key argument of a two-argument `put(...)` whose receiver is evidenced as an OTel `AttributesBuilder`. A generic `Map.put`, `buildJsonObject { put(...) }`, or Ktor `call.attributes.put` does not count;
- (P4) a map-entry key inside a `mapOf(...)` passed as the `attributes` argument of `withSpan(...)`, either directly or through a `val` in the same file.

A key literal hoisted into a same-file `const val` / `val` is checked at EVERY place where that constant is used as a key; an `@AllowForbiddenSpanAttribute`-annotated use site is sanctioned there.

Each position also defines its paired value. In that position, the rule runs six checks:

- **`"user_id"` is enforced again.** It matches on the key alone, whatever the value. The Group A carve-out is removed. The ~30 existing SQL-column, `@SerialName`, route-param and `buildJsonObject` uses stay quiet because none of them is in an attribute-key position.
- **User-identity aliases fire only on a raw identifier (#177).**
  - Applies to keys whose tokens include `user`, `enduser`, `principal`, `actor`, `subject`, `owner` or `account`.
  - Fires only when the value is *raw-identifier-evidenced*: a UUID-shaped literal; a `UUID.randomUUID()` / `fromString()` / `nameUUIDFromBytes()` call; a name declared in the same file as `UUID`, or initialized from one of these; or a name following the domain convention (`userId`, `*UserId`, `user.id`).
  - `setAttribute("principal", "system")` and `setAttribute("user.id", UserIdHasher.hash(...))` do NOT fire.
- **Raw JWT / OIDC claim values fire under any key.** Covers `.sub`, and `.subject` read from a token `payload` / `decoded` / `claims` / `jwt` object. The spec forbids raw claims under every key; until now only the `jwt.*` key names were enforced.
- **A raw client IP value fires under any key.** `setAttribute("net.client", call.clientIp)` fires; `IpHasher.hash(call.clientIp)` passes. `clientIp` is the canonical accessor named by the client-IP invariant (added in final-review round 1).
- **Location keys, matched by token (#178).**
  - A key fires when any of its tokens is a location token, e.g. `location`, `lat`, `latitude`, `lng`, `lon`, `longitude`, `coord(s)`, `geohash`, `wkt`. The full set is enumerated in the spec.
  - The **exact key `display_location`** is the one sanctioned exception. It is the HMAC-fuzzed post coordinate that every non-admin read already returns. `display_lat` (which could carry a raw viewer coordinate) and `display_actual_location` still fire.
  - Because matching is by token, `latency_ms`, `cloud.platform` and `memory.allocation` don't fire. SQL column names never fire, because they are not attribute keys.
- **Credential keys, matched by token (#179, key side).**
  - Fires on single tokens such as `password`, `passwd`, `secret(s)`, `bearer`, `authorization`, `apikey`, `cookie`, and on token pairs such as `refresh token`, `access token`, `id token`, `api key`, `private key`, `service role`. The full set is in the spec.
  - Examples that fire: `client_secret`, `http.request.header.authorization`, `refreshToken`.

### Tier 2 value regexes extended for opaque secrets (#179, value side; still fire anywhere)

- **New prefix-anchored patterns** for the vendor secret formats in this stack:
  - Google OAuth client secret `GOCSPX-…`, access token `ya29.…` (incl. the metadata-server `ya29.c.…` form), refresh token `1//…`;
  - Supabase secret API key `sb_secret_…`;
  - raw Grafana Cloud token `glc_…`;
  - OpenAI key — `sk-proj-` / `sk-svcacct-` / `sk-admin-`, or the `T3BlbkFJ` marker;
  - RevenueCat / Stripe-style secret key `sk_…`.
- **Connection URI with password, widened** to `rediss://` (TLS, the Upstash shape), to the user-less `redis://:password@` form, and to `postgres(ql)://` (the admin DB connection-string slot).
- **Two spec-text corrections so the spec matches the shipped code** (no behavior change):
  - The PEM regex in the spec becomes `[A-Z ]*`, as in the code. The spec's `+` would miss the label-less PKCS#8 `BEGIN PRIVATE KEY` used by GCP service-account JSON.
  - Tier 1 matching is described as "exactly equals" (the code), not "contains".

### Spec changes to `observability-otel-foundation`

- **"Forbidden span attributes"**:
  - Names the exact alias, claim and location sets.
  - Fixes the defense-layer claims. Layer 1 strips only `FORBIDDEN_KEYS` key names, never values. So `author_id`-style raw UUIDs and prefix-less opaque secrets are defended by code review, plus the layer-3 sentinel where one exists, rather than by "layers 1 and 3".
  - Lists the prefix-less secrets: project-issued refresh tokens, Supabase GoTrue refresh tokens, webhook / HMAC / AES secrets, Cloudflare tokens, and the base64-wrapped stored OTLP credential.
- **"`OtelForbiddenAttributeRule` fences…"**: drops the `user_id` carve-out and adds the Tier 2 patterns.
- **"Detekt test coverage…"**: new scenario items; the sync guard has zero carve-outs.
- **"Detekt run … remains green"**:
  - The audit covers every scanned module.
  - The audit adds the two `AttributesBuilder.put` writers outside `:infra:otel` (`ChatRoutes`, `FcmDispatcher`).
  - Its remedy is narrowed to "fix at source or annotate" — never loosen the rule.
- **ADDED requirement**: specifies the attribute-key-position checks.

### Spec amendment, stated explicitly

The sentence "Even `display_location`-derived numbers are not currently sanctioned for span attributes" is **reversed for the one exact key `display_location`**. This is the sanction that the bullet's own "unless explicitly sanctioned" clause anticipates.

`docs/06-Security-Privacy.md` § Consent Flow discloses that Grafana Cloud receives only "hashed user IDs, parameterized SQL, route patterns". To keep that accurate, the spec requires the first change that adds a `display_location` span writer to amend that disclosure in the same PR. No such writer exists today. The spatial-fuzzing invariant itself is unchanged.

### Other changes

- **Docs:** `docs/04-Architecture.md` § Instrumentation names the mandatory hashed-user attribute `user_id`. The real key is `user.id`; `user_id` is stripped at runtime and is now lint-forbidden as an attribute key.
- **Verification:** `./gradlew detekt` runs across the repo after the rule change. Any new production finding is fixed at the source or sanctioned with `@AllowForbiddenSpanAttribute("<reason>")`. The rule is never loosened to make it pass.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `observability-otel-foundation`:
  - `OtelForbiddenAttributeRule` gains the attribute-key-position checks (ADDED requirement) and wider Tier 2 coverage.
  - The forbidden-attributes contract now matches the lint rule:
    - the `user_id` carve-out and the three "deferred to follow-up" statements are removed;
    - the exact key `display_location` is explicitly sanctioned;
    - the defense layer for each category is stated accurately.

## Impact

- **Code:**
  - `lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/OtelForbiddenAttributeRule.kt`: PSI helpers, key-token sets, value evidence, Tier 2 regexes, KDoc rewrite.
  - `lint/detekt-rules/src/test/kotlin/id/nearyou/lint/detekt/OtelForbiddenAttributeLintTest.kt`: new scenario matrix. The `user_id` carve-out test flips: SQL / JSON / route uses pass, the attribute-key use fires. One stale fixture is corrected to actually call `UserIdHasher.hash`.
  - No production code changes. The repo-wide detekt run reports zero findings.
- **Detekt scope:** `:backend:ktor` plus every `nearyou.detekt` module (`:infra:*`, `:core:*`). `:mobile:app`'s config activates only `TestLoginIsolationRule`, so it is unaffected.
- **Schema / APIs / dependencies / runtime:** none. `ForbiddenAttributeStripper.FORBIDDEN_KEYS` is unchanged.
- **Out of scope:**
  - type-resolved analysis (`detektMain`);
  - cross-file maps or constants, and dynamic key concatenation;
  - user-referencing keys outside the alias token set (`author_id`, …);
  - mobile telemetry;
  - backing tests for the pre-existing layer-3 sentinel scenarios — filed separately as [#522](https://github.com/aditrioka/nearyou-id/issues/522) by preflight.
- Closes #177, #178, #179, #180.
