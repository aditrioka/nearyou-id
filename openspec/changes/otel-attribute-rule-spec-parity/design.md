## Context

`OtelForbiddenAttributeRule` shipped with `otel-attribute-lint-rule` ([PR #99](https://github.com/aditrioka/nearyou-id/pull/99)). It has three parts:

- **Mode A:** 21 exact-match Tier 1 keys plus 4 Tier 2 value regexes, applied to any string literal.
- **Mode B:** the `{ip:<value>}` value-shape check, also applied anywhere.
- **Bypasses:** a path allowlist and the `@AllowForbiddenSpanAttribute` annotation.

Its archived design (`openspec/changes/archive/2026-05-12-otel-attribute-lint-rule/design.md` § "Explicitly deferred follow-ups") left four gaps against the canonical spec. Each became a `follow-up` issue:

| Issue | Spec bullet left unenforced | Why it was deferred |
|---|---|---|
| #180 | `user_id` key (in `FORBIDDEN_KEYS`, carved out of Tier 1) | ~30 non-OTel literal uses: SQL column (`rs.getObject("user_id", …)`), `@SerialName`, `call.parameters["user_id"]`, `buildJsonObject { put("user_id", …) }` |
| #177 | raw user UUID under `principal` / `actor` / `subject` / `owner` | Key-name match alone blocks legitimate `setAttribute("principal", role)` |
| #178 | `*location*` / `*lat*` / `*lng*` / `*coord*` keys | Substring matches hit `display_location`, `latency`, `cloud.platform`, `allocation`, SQL text |
| #179 | OAuth client secrets, raw refresh / bearer tokens, plaintext passwords | Opaque values with no distinguishing marker |

**Root cause.** Three of the four gaps share one cause: the rule cannot tell an attribute-key literal from any other string literal.

**Lint environment.** The project's detekt is **syntactic only**. The `nearyou.ktor` and `nearyou.detekt` convention plugins, and `mobile/app`, all run plain `detekt` with no type resolution. The custom ruleset covers `:backend:ktor` plus 13 `:infra:*` and 2 `:core:*` modules via `config/detekt/invariants.yml`. `:mobile:app` activates only `TestLoginIsolationRule`.

**Attribute writers that must keep passing:**

- `AuthPlugin`: `setAttribute("user.id", UserIdHasher.hash(user.id))`
- `InternalEndpointAuth`: `setAttribute("service.account.id", ServiceAccountIdHasher.hash(claims.sub))`
- `ChatRoutes`:
  - `withSpan(…, mapOf("conversation_id" …, "message_id" …, "supabase.realtime.channel" …))`
  - fluent `Attributes.builder().put("event", …).put("error.type", …)` on the publish-failure event
- `FcmDispatcher`:
  - `withSpan(…, mapOf("messaging.system" …, "user.id" to UserIdHasher.hash(…)))`
  - `attrsBuilder.put("event", …)` / `put("error_code", …)`
- `:infra:otel` internals (path-allowlisted).

## Goals / Non-Goals

**Goals:**

- One PSI detector for "this literal is an OTel attribute key", shared by every new key-side check.
- Enforce `"user_id"` again, with zero false positives on its existing non-OTel uses.
- Value-check user-identity aliases (role/name strings pass, raw identifiers fire). Check raw JWT claim values under any key.
- Enforce location keys by token. Sanction exactly one key, `display_location`.
- Add credential keys by token, plus prefix-anchored opaque-secret value patterns for the vendor secret formats in this stack.
- Spec and rule agree, with zero carve-outs. Every forbidden category is either lint-enforced or explicitly named as defended by code review (plus layer-3 sentinels where they exist).

**Non-Goals:**

- Type-resolved detekt (`detektMain`). See Decision 1.
- Cross-file data flow; dynamic key construction (`"geo." + suffix`).
- Changes to the runtime `ForbiddenAttributeStripper`. It is unchanged, and the sync guard still pins it.
- Mobile. `:mobile:app` does not activate this rule and has no OTel writer.
- User-referencing keys outside the alias token set (`author_id`, `sender_id`, …). See Decision 4.

## Decisions

### Decision 1: One attribute-key-position (AKP) detector, PSI-only (no type resolution)

The new key-side checks (`user_id`, aliases, location, credentials, raw claims) fire only when the literal is in an AKP. Tier 1 Groups A/B/C, Tier 2 and Mode B keep firing anywhere. Tier 2 gains patterns in Decision 5 but keeps its anywhere scope.

**Alternatives considered:**

- **Substring regex anywhere, with a `(?!display_)` lookahead** (#178's candidate). Rejected. Firing anywhere produces unbounded false positives: `lat` matches latency, platform, translate, relation and template; `location` matches allocation; SQL column names fire too.
- **Detekt type resolution (`detektMain`)** — #177 option (b), and #180's "full type-resolution" option. Rejected. It means switching 16 modules to `detektMain`, pulling compile classpaths into the lint graph, and slowing every gate run, all for a writer surface of 4 sites. PSI plus the project's own naming conventions is enough, and it matches every sibling rule.

### Decision 2: AKP shapes, paired values, and hoisting (#180 sub-issues i + ii)

A key expression K is in an AKP in four shapes. Each shape also defines the **paired value** that the value-aware checks (Decision 4) inspect.

| Shape | K is | Paired value |
|---|---|---|
| **P1** `setAttribute` | the argument named `key`, else the unnamed argument at position 0 | the argument named `value`, else the unnamed argument at position 1 |
| **P2** `AttributeKey.stringKey/…Key` (unqualified or `…AttributeKey` receiver) | the factory's first argument | argument 1 of an enclosing `setAttribute(...)` / `put(...)` that has the factory call at argument 0; else the next argument of `Attributes.of(...)` at an even index; else **none** (value checks skipped) |
| **P3** `put(k, v)` on a *builder-evidenced* receiver | argument 0 | argument 1 |
| **P4** `mapOf` / `mutableMapOf` / `hashMapOf` / `linkedMapOf` entry (`to` / `Pair`) | the entry key | the entry value |

**Builder evidence (P3).** The receiver chain text contains `Attributes.builder()` or `.toBuilder()`. Alternatively, the receiver is a same-file name typed `AttributesBuilder`, or initialized from `Attributes.builder()`. This is the answer to #180 sub-issue (i): a generic `MutableMap.put`, an unqualified `put` inside `buildJsonObject { }` (in `DataExportArchiveService`, `DeletionQueueRepository` and `AppealReviewRepository`) and Ktor's `call.attributes.put` are not builder-evidenced.

**P4 reach** (#180 sub-issue ii). The map reaches `withSpan`'s `attributes` argument in one of two ways:
- directly, as the argument named `attributes` or the unnamed argument at position 1; or
- through a same-file `val` whose simple name is passed there.

**Hoisted key literals.** `const val K = "user_id"` followed by `span.setAttribute(K, v)` is the codebase's own idiom; `OtelBootstrap` uses `stringKey(SERVICE_NAME_KEY)`. A literal that initializes a property is therefore checked at any same-file AKP reference to that property, and takes that reference's paired value.

**Ceiling, recorded as a `ponytail:` note.** These shapes are not traced:
- maps built in another file, via `buildMap { }`, or composed with `+`;
- an unqualified `put` inside `Attributes.builder().apply { }`;
- key constants declared in another file;
- a standalone hoisted `AttributeKey` value (key-name checks only, no paired value).

The runtime stripper (which still strips `user_id`), the sentinel scenarios and code review are the backstop for these.

### Decision 3: Match keys by token, not substring; sanction exactly `display_location` (#178 + #179 key side)

**Tokenizing.** Keys are split on `.`, `_`, `-`, whitespace and lower→upper camelCase boundaries, then lowercased. For example, `geo.userLat` becomes `[geo, user, lat]`, and `latency_ms` becomes `[latency, ms]`.

**Location tokens.** `location(s)`, `geolocation`, `lat(s)`, `latitude(s)`, `lng`, `lon`, `longitude(s)`, `latlng`, `latlon`, `latlong`, `coord(s)`, `coordinate(s)`, `geohash`, `geom`, `geometry`, `geography`, `wkt`.
- `long` is excluded because it is too common as a non-geo word.
- `coordinator` is not a member.

**The sanction.** Only the exact key `display_location` is sanctioned: tokens exactly `[display, location]`, so `display.location` and `displayLocation` also pass.
- A family-wide `display_*` sanction was the first draft and was **rejected after security review**. It would pass `display_lat` (which could carry the viewer's raw, never-fuzzed Nearby request coordinate — docs/05) and `display_actual_location` (which `CoordinateJitterRule`'s `\bactual_location\b` misses, because `_` is a word character).
- The user's directive (do not flag `display_location`, the fuzzed legitimate read path) and #178's carve-out are both satisfied by the exact key.
- **Spec amendment, stated explicitly.** This reverses "Even `display_location`-derived numbers are not currently sanctioned" for the one exact key. The `display_location` value is the HMAC-fuzzed post coordinate that every non-admin post read already returns to API clients.
- **Privacy-disclosure consistency.** `docs/06-Security-Privacy.md` § Consent Flow tells users that Grafana Cloud receives "hashed user IDs, parameterized SQL, route patterns". Fuzzed location is not in that list. No writer emits `display_location` today. The spec therefore requires the change that adds the first such writer to amend that disclosure in the same PR. This is a forward guard; it is not a docs edit now, because the disclosure is accurate today.

**Credential tokens.**
- Single tokens: `password`, `passwd`, `secret(s)`, `bearer`, `authorization`, `apikey`, `cookie` (the admin session cookie via `http.request.header.cookie`).
- Adjacent pairs: `refresh token`, `access token`, `id token`, `api key`, `private key` (service-account JSON `private_key`), `service role`.
- Bare `token` and `credential` are excluded because semconv and benign keys use them (`gen_ai.usage.input_tokens`, `credential.type`).

### Decision 4: Value-aware user-identity aliases + raw-claim values, PSI-only (#177 option (a))

**Alias check.** An alias key is a key whose tokens include `user`, `enduser`, `principal`, `actor`, `subject`, `owner` or `account`. That set comes from:
- the issue's four aliases;
- `user.id` / OTel `enduser.id` (the spec's own code-review-blocker scenario);
- `service.account.id` (internal-endpoint-auth forbids the raw OIDC `sub` there).

An alias key fires only when its peeled paired value is raw-identifier-evidenced. Peeling removes parentheses, `!!`, a trailing no-argument `.toString()`, and a single-entry `"$x"` / `"${x}"` template. Evidence is:
- **V1:** a non-interpolated string literal fully matching `^[0-9a-fA-F]{8}-…-[0-9a-fA-F]{12}$`.
- **V2:** `UUID.randomUUID()` / `fromString(…)` / `nameUUIDFromBytes(…)`.
- **V3:** a simple name with a same-file parameter or property declaration that is typed `UUID` / `java.util.UUID` (nullable allowed), or initialized from V1 / V2 / V4. This is non-recursive; a bare-name initializer is not chased, so `val a = b; val b = a` cannot loop.
- **V4:** a name or property reference (not a call — `getUserId()` does not count) whose terminal identifier is `userId` or ends in `UserId`; or `<x>.id`, where `x`'s `!!`-peeled terminal identifier is `user` or ends in `User`.

V4 rests on this codebase's convention that every `*UserId` / `user.id` holds a raw `users.id` UUID. Hashed values (`UserIdHasher.hash(…)`, `ServiceAccountIdHasher.hash(…)`) match none of V1–V4, so the sanctioned shapes pass with no special case.

**Raw-claim check (any key).** The canonical spec forbids raw JWT claims under ANY attribute key, so claim evidence fires regardless of the key. Claim evidence is:
- a terminal `sub`; or
- `.subject` read off a receiver named `payload` / `decoded` / `claims` / `jwt`. Production reads the raw subject as `credential.payload.subject` and `decoded.subject`.

A bare `.subject` is not claim evidence, because `email.subject` and `template.subject` are ordinary text.

**Alternative considered:** checking every AKP value for UUID evidence regardless of key. Rejected, because `conversation_id` / `message_id` are spec-sanctioned UUID primary keys.

**Known gap.** A raw UUID under a user-referencing key outside the alias token set (`author_id`, `sender_id`, `recipient_id`) is not caught by lint. The spec names the enforced sets exactly, instead of an open-ended "etc.", and assigns the rest to code review.

### Decision 5: Tier 2 gains prefix-anchored vendor secrets; prefix-less opaque secrets are declared non-lint (#179)

#179 offered (a) known-prefix regexes or (b) amending the spec to rely on code review only. Inspecting the backend's secret inventory (`secretKey` / `secrets.resolve` slots) gives a split answer: **(a) where a stable vendor prefix exists, (b) where none does.**

| Pattern (fires anywhere, like existing Tier 2) | Covers |
|---|---|
| `GOCSPX-[A-Za-z0-9_\-]{20,}` | Google OAuth client secret. There is no backend slot today; it covers the stack's Google OAuth footprint. |
| `ya29\.[A-Za-z0-9_\-]{20,}` | Google OAuth / metadata-server access token |
| `(?<![A-Za-z0-9/])1//[A-Za-z0-9_\-]{20,}` | Google OAuth refresh token |
| `sb_secret_[A-Za-z0-9_\-]{20,}` | Supabase secret API key (`supabase-service-role-key` slot, new-format key) |
| `glc_[A-Za-z0-9+/=_\-]{20,}` | Grafana Cloud access-policy token, **raw form** (what operators handle) |
| `sk-(?:(?:proj\|svcacct\|admin)-…{20,}\|…{8,}T3BlbkFJ…{8,})` | OpenAI API key (`openai-api-key`). Matches a typed prefix, or the `T3BlbkFJ` (base64 "OpenAI") marker that legacy and project keys embed. |
| `(?<![A-Za-z0-9])sk_(?:live_\|test_)?[A-Za-z0-9]{24,}` | RevenueCat secret API key (`revenuecat-secret-api-key`; Stripe-style shape) |
| `rediss?://[^:/@\s]*:[^@/\s]+@` (widened) | `redis-url`: TLS `rediss://` (Upstash) and the user-less `redis://:pw@` form |

Already covered by existing patterns: the legacy JWT-format Supabase service role key (JWT pattern) and the Firebase service-account private key (PEM pattern, `[A-Z ]*` — the spec text is now aligned to the code).

**Declared prefix-less.** These are covered by key-side lint plus code review. The layer-3 bearer-token sentinel covers the `Authorization` header. Layer 1 never inspects values.
- project-issued refresh tokens (`RefreshTokenService`: `SecureRandom` base64url);
- Supabase GoTrue refresh tokens;
- RevenueCat webhook bearer / HMAC secrets;
- admin HMAC / AES keys;
- Cloudflare API tokens;
- the **stored** `otel-grafana-otlp-token`. `OtelBootstrap` holds it as base64(`<instance_id>:<glc_…>`), so its `glc_` prefix is not visible;
- plaintext password values.

**Rejected:** Resend `re_…`. A probe hit `re_threshold` / `re_registered`-style substrings in production literals.

**False-positive check.** Every new pattern needs a vendor prefix followed by a 20–24+ character token-alphabet body. The repo-wide run over the 16 scanned modules found hits only in KDoc lines inside the path-allowlisted `:infra:otel` (Decision 8).

### Decision 6: The sync guard ends with zero carve-outs

`FORBIDDEN_KEYS ⊆ TIER_1_GROUP_A ∪ CONTEXT_RESTRICTED_KEYS` (`{"user_id"}`); the two modes don't overlap. The test's `expectedCarveouts` set is deleted. A new `FORBIDDEN_KEYS` entry must go into one of the two modes, and the failure message names both.

### Decision 7: Allowlists, annotation and composition apply unchanged

**Bypasses.** The path allowlist and `@AllowForbiddenSpanAttribute("<non-blank>")` gate every mode, including the new one. The allowlisted paths are `/src/test/`, `/infra/otel/src/main/`, `/lint/detekt-rules/src/main/`, and the synthetic `id.nearyou.lint.detekt.*` package.

**Composition.** `setAttribute("actual_location", …)` produces exactly one finding from this rule (location key) and one from `CoordinateJitterRule`. One literal is reported at most once, however many checks match it.

### Decision 8: Verify with the repo-wide detekt run; never loosen

After the rule change, `./gradlew detekt` runs over every scanned module. Any new production finding gets one of two treatments:
- fixed at the source; or
- sanctioned with `@AllowForbiddenSpanAttribute("<reason>")`. That annotation class would be declared in `core/domain/.../lint/Annotations.kt` only when first needed.

The rule is never loosened.

**Result:** zero findings across all 17 detekt reports (the 16 custom-ruleset modules — `:backend:ktor`, 13 `:infra:*`, 2 `:core:*` — plus `:mobile:app`, whose config activates only `TestLoginIsolationRule`).
- None of the ~30 `"user_id"` literals is in an AKP.
- All 6 writer sites use sanctioned keys or helpers.

A temporary probe file (`setAttribute("user_id"/"geo.lat"/"principal"+userId)`) confirmed that the real `:backend:ktor:detekt` task flags all three. The probe was removed before commit.

### Decision 9: Final-review round 1 (four sub-agent lenses; qodo unavailable — subscription inactive)

The operator chose "should-fix + cheap fixes". Each change below refines Decisions 2–5; the spec delta carries the normative text.

- **Hoisted keys are checked at EVERY reference.** Each reference contributes its own paired value, and the literal fires when the checks match at any of them. The previous `firstNotNullOfOrNull` only checked the first use. All three code lenses flagged this.
- **An annotated use site is sanctioned there.** Annotating the constant itself sanctions every use.
- **New check 6: raw `clientIp` value under any key.** It mirrors the raw-claim check. `clientIp` is the canonical accessor named by the client-IP invariant. `IpHasher.hash(call.clientIp)` is a call, so it passes.
- **Evidence refinements:**
  - V4 ignores `hash`-named values (`hashedUserId`), so already-hashed locals stop false-positive firing.
  - V3 resolves parameters and properties only. A same-name function's return type no longer counts.
  - V2 adds the Kotlin `Uuid` factories and `UuidV7.next()`; production uses the latter.
  - Peeling adds the left side of `?:` and `.toJavaUuid()` / `.toKotlinUuid()`.
  - The claim receiver is peeled (`decoded!!.subject`).
  - Triple-quoted UUID literals now match.
  - `user_id` matching is case-insensitive.
- **Tokenizer gains an acronym boundary** (`IDToken` → `[id, token]`, `JWTSecret`, `userGPSLocation`).
- **Tier 2 changes:**
  - `ya29.` admits `.`, which covers the metadata-server `ya29.c.` form.
  - The credentialed-URI pattern covers `postgres(ql)://` (the admin DB connection-string slot).
  - Resend `re_…` is declared prefix-less in the spec rather than silently omitted.
- **Recorded as Known limits, not fixed:**
  - semconv constants (they carry no literal);
  - multi-entry templates (`"user:$userId"`);
  - all-lowercase compounds (`refreshtoken`);
  - the value of a hoisted `AttributeKey` val.

  These are the larger items the operator deferred. The rule-file split suggested by the general lens (~700 lines vs the ~500 soft cap) is likewise deferred; the file stays navigable as one rule.
- **Test-suite fixes:**
  - The `call.attributes.put` fixture now actually exercises the receiver check; it previously passed for the wrong reason.
  - New fixtures cover the `sk_` lookbehind, the `withSpan` location key, and the Tier-1-in-`withSpan` scenario.
  - Three duplicate loop entries are removed and stale labels corrected.
  - Result: 192 tests, 0 failed. Repo-wide detekt still reports 0 findings.

## Risks / Trade-offs

- **[Risk]** P1 matches any `setAttribute`, including non-OTel APIs. → **Mitigation:** there are none in the scanned backend today, and the annotation escape exists. Narrowing further would need type resolution.
- **[Risk]** V3, P3 and P4(b) resolve names across the whole file, not by lexical scope. V4 relies on naming, so an unconventionally named UUID value declared in another file is missed. → **Mitigation:** findings are bounded by the alias-key + AKP preconditions, so false positives stay small. False negatives are backstopped by code review and the JWT-claim sentinel.
- **[Risk]** P4 does not trace cross-file / `buildMap` / `+` maps, `apply { put }` builders, or cross-file key constants. → **Mitigation:** the runtime `FORBIDDEN_KEYS` stripping still drops `user_id`; review covers the rest. Documented as a `ponytail:` ceiling.
- **[Risk]** A new Tier 2 pattern fires on a legitimate literal. → **Mitigation:** vendor prefix + long body, the repo-wide run before merge, and the annotation escape.
- **[Risk]** The `secret` / `authorization` / `cookie` tokens over-match a benign key. → **Mitigation:** they are checked only in an AKP; annotate with a reason.
- **[Trade-off]** Sanctioning `display_location` reverses one spec sentence. → It is limited to the exact key, stated explicitly as an amendment, and guarded by the docs/06 disclosure requirement on the first writer.

## Migration Plan

Lint-only: there is no runtime, schema or deploy surface. Rollback means reverting the rule, the test and the spec delta. There is no staging smoke (archive N/A, as for the original `otel-attribute-lint-rule`).
