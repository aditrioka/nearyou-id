## Context

`OtelForbiddenAttributeRule` was shipped by `otel-attribute-lint-rule` ([PR #99](https://github.com/aditrioka/nearyou-id/pull/99)). It has three parts:

- **Mode A** — 21 exact-match Tier 1 keys plus 4 Tier 2 value regexes, applied to any string literal.
- **Mode B** — the `{ip:<value>}` value-shape check, also applied anywhere.
- **Allowlists** — a path allowlist plus the `@AllowForbiddenSpanAttribute` annotation.

Its archived design (`openspec/changes/archive/2026-05-12-otel-attribute-lint-rule/design.md` § "Explicitly deferred follow-ups") left four gaps against the canonical spec. Each became a `follow-up` issue:

| Issue | Spec bullet left unenforced | Why it was deferred |
|---|---|---|
| #180 | `user_id` key (in `FORBIDDEN_KEYS`, carved out of Tier 1) | ~30 non-OTel literal uses: SQL column (`rs.getObject("user_id", …)`), `@SerialName`, `call.parameters["user_id"]`, `buildJsonObject { put("user_id", …) }` |
| #177 | raw user UUID under `principal` / `actor` / `subject` / `owner` | Matching the key name alone blocks legitimate `setAttribute("principal", role)` |
| #178 | `*location*` / `*lat*` / `*lng*` / `*coord*` keys | Substring matches hit `display_location`, `latency`, `cloud.platform`, `allocation`, and SQL text |
| #179 | OAuth client secrets, raw refresh / bearer tokens, plaintext passwords | Opaque values with no distinguishing marker |

Three of the four gaps have the same root cause: the rule cannot tell a literal used as an attribute key from any other string literal.

The project's detekt is **syntactic only**:

- The `nearyou.ktor` and `nearyou.detekt` convention plugins, and `mobile/app`, all run the plain `detekt` task with no type resolution.
- The custom ruleset covers `:backend:ktor` plus 13 `:infra:*` and 2 `:core:*` modules, through `config/detekt/invariants.yml`.
- `:mobile:app` turns on only `TestLoginIsolationRule`.

Today's attribute-writer surface:

- `AuthPlugin`: `setAttribute("user.id", UserIdHasher.hash(user.id))`
- `InternalEndpointAuth`: `setAttribute("service.account.id", ServiceAccountIdHasher.hash(claims.sub))`
- `ChatRoutes`: `withSpan(..., mapOf("conversation_id" …, "message_id" …, "supabase.realtime.channel" …))`
- `FcmDispatcher`: `withSpan(..., mapOf("messaging.system" …, "user.id" to UserIdHasher.hash(…)))`
- `:infra:otel` internals (path-allowlisted)

All of these must keep passing.

## Goals / Non-Goals

**Goals:**

- One PSI detector for "this literal is an OTel attribute key", shared by all the new key-side checks.
- Enforce `"user_id"` again with zero false positives on the existing non-OTel uses.
- Check user-identity aliases by their value: role and name strings pass, raw identifiers fire.
- Enforce location keys by token, with `display_*` keys sanctioned.
- Add credential keys (by token) and opaque-secret value patterns. Each value pattern is anchored on a vendor prefix of a secret the backend actually holds.
- Spec and rule agree, with zero carve-outs left. Every forbidden category is either lint-enforced, or explicitly named as covered only by runtime stripping, sentinel tests and code review.

**Non-Goals:**

- Type-resolved detekt (`detektMain`). See Decision 1.
- Data flow across files, or keys built dynamically (`"geo." + suffix`).
- Changes to the runtime `ForbiddenAttributeStripper`. It stays as is, and the sync guard still pins it.
- Mobile. `:mobile:app` does not activate this rule, and it has no OTel writer.
- User-referencing keys outside the alias token set (`author_id`, `sender_id`, …). See Decision 4.

## Decisions

### Decision 1: One attribute-key-position (AKP) detector, PSI-only (no type resolution)

The new key-side checks (`user_id`, aliases, location, credentials) fire only when the literal is in an attribute-key position (AKP). The checks that already fire anywhere — Tier 1 Groups A/B/C, Tier 2 and Mode B — do not change.

**Alternatives considered:**

- **Substring regex anywhere, with a `(?!display_)` lookahead** (the candidate in #178). Rejected. Firing anywhere produces unbounded false positives: `lat` matches latency, platform, translate, relation and template; `location` matches allocation; and SQL column names would fire too.
- **Detekt type resolution (`detektMain`)** — option (b) in #177 and the "full type-resolution" option in #180. Rejected. It means moving 16 modules from `detekt` to `detektMain`, pulling compile classpaths into the lint graph, and slowing every gate run. That build-graph cost is too high for a writer surface of 4 call sites. PSI plus the project's own naming conventions is enough, and it matches how every sibling rule works.

### Decision 2: The four AKP shapes (and how #180's `put` and hoisting sub-issues are solved)

A `KtStringTemplateExpression` L is in an AKP when any of the following holds.

**P1 — `setAttribute`.** L is the key argument of a call whose callee short name is `setAttribute`. The key argument is the one named `key`; if none is named, the first positional argument.
- This covers OTel `Span`, `SpanBuilder` and `LogRecordBuilder`.
- A non-OTel `setAttribute` (servlet, say) would also match. That is accepted: the annotation escape exists, and the backend has no such call today.

**P2 — `AttributeKey` factory.** L is the first argument of one of `stringKey`, `booleanKey`, `longKey`, `doubleKey`, `stringArrayKey`, `booleanArrayKey`, `longArrayKey`, `doubleArrayKey`. The call is either unqualified (static import) or has a receiver whose text ends in `AttributeKey`.
- This also covers the keys of `Attributes.of(...)` and `addEvent(name, Attributes)`, because those keys are typed `AttributeKey`.
- The event *name* passed to `addEvent` is not a key and is not checked.

**P3 — `AttributesBuilder.put`** (#180 sub-issue i). L is the first argument of a 2-argument `put(...)` whose receiver is *builder-evidenced*:
- the receiver chain's text contains `Attributes.builder()` or `.toBuilder()`, OR
- the receiver is a simple name declared in the same file, either with type `AttributesBuilder` or with an initializer that contains `Attributes.builder()`.

These are NOT builder-evidenced and do not fire:
- a generic `MutableMap.put("user_id", …)`;
- an unqualified `put` inside `buildJsonObject { }` (the shape used in `DataExportArchiveService`, `DeletionQueueRepository` and `AppealReviewRepository`);
- Ktor's `call.attributes.put(AttributeKey<T>, …)`.

**P4 — `withSpan` attribute map** (#180 sub-issue ii). L is the key of a map entry — the left operand of infix `to`, or the first argument of `Pair(...)` — that is passed directly to a map builder call M (`mapOf`, `mutableMapOf`, `hashMapOf` or `linkedMapOf`). In addition, one of these holds:
- (a) M is the `attributes` argument of a `withSpan(...)` call, either named (`attributes =`) or at positional index 1 when no argument is named; OR
- (b) M is the initializer of a property or variable `V`, and a `withSpan(...)` call in the same file passes the simple name `V` as its `attributes` argument.

Together these handle the two-step "literal → mapOf → withSpan" chain, and the hoisted-`val` shape that broke call-context walks in the original Decision 5.

**Ceiling.** Maps built in another file, built with `buildMap { put(...) }`, or composed with `+` are not traced. The backstop for those stays the runtime stripper (which removes `user_id`) and the sentinel scenarios. This is recorded as a `ponytail:` note in the rule.

### Decision 3: Match keys by token, not substring (#178, and #179 key side)

Before matching, each key is split into tokens:

1. Split on `.`, `_`, `-` and whitespace, and at lowercase→uppercase camelCase boundaries.
2. Lowercase every token.

For example, `geo.userLat` becomes `[geo, user, lat]`, and `latency_ms` becomes `[latency, ms]`.

**Location tokens.** A key fires when any token is one of: `location`, `locations`, `geolocation`, `lat`, `lats`, `latitude`, `latitudes`, `lng`, `lon`, `longitude`, `longitudes`, `latlng`, `latlon`, `coord`, `coords`, `coordinate`, `coordinates`.
- `long` is left out because it is too common as a non-geo word.
- `coordinator` is not in the set, so it passes.

**The `display` sanction.** A location-matching key whose **first token is `display`** passes: `display_location`, `display.location`, `display_lat`, `display_lng`. This is the `(?!display_)` carve-out from the issue, made exact at the token level.
- **Spec amendment.** This reverses the current sentence "Even `display_location`-derived numbers are not currently sanctioned for span attributes".
- **Why it is safe.** The value is the HMAC-fuzzed coordinate. Every non-admin read path already serialises it to clients (coordinate-jitter capability), so a span attribute exposes nothing that an API reader cannot already see. The bullet's own "unless explicitly sanctioned" clause is where this sanction belongs.
- The raw `actual_location` stays forbidden by two independent checks: this one, plus `CoordinateJitterRule` for any `actual_location` literal. They fire independently (Decision 7).

**Credential tokens.** A key fires when it contains:
- any single token from: `password`, `passwd`, `secret`, `secrets`, `bearer`, `authorization`, `apikey`; or
- any adjacent token pair from: `refresh,token`, `access,token`, `api,key`, `service,role`.

This catches `client_secret`, `http.request.header.authorization`, `refreshToken` and `supabase.service_role_key`. Bare `token` and `credential` are left out because semconv and other benign keys use them (`gen_ai.usage.input_tokens`, `credential.type`).

### Decision 4: User-identity aliases are checked by value, PSI-only (#177 option (a))

**Alias keys.** An alias key is a key in an AKP with any token in: `user`, `enduser`, `principal`, `actor`, `subject`, `owner`, `account`. That set is:
- the four aliases from the issue;
- the canonical `user.id` and OTel `enduser.id` — the spec's own scenario is `setAttribute("user.id", userId.toString())`;
- `service.account.id`, because `openspec/specs/internal-endpoint-auth/spec.md` forbids that key — and `principal` / `actor` — from carrying the raw OIDC `sub`.

An alias key fires only when its value is *raw-identifier-evidenced*. So benign keys such as `user_agent.original`, `user.name`, `email.subject`, and `principal = "system"` all pass.

**Peeling.** Before the evidence check, the value expression is unwrapped: parentheses, `!!`, a trailing no-argument `.toString()`, and a single-entry string template (`"$x"` / `"${x}"`) are removed.

**Evidence.** The peeled expression counts as a raw identifier when it is:

- **V1** — a string literal with no interpolation that matches the canonical UUID regex `[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}`.
- **V2** — a call to `UUID.randomUUID()`, `UUID.fromString(…)` or `UUID.nameUUIDFromBytes(…)`, with receiver `UUID` or `java.util.UUID`.
- **V3** — a simple name where some parameter or property with that name, declared in the same file, has type `UUID` / `java.util.UUID` (nullable allowed), or an initializer that satisfies V1 or V2.
  - *Ceiling:* the name is matched across the whole file, not by lexical scope. A same-named declaration of another type elsewhere in the file can cause an over-match. That is very unlikely, because the key must also be an alias key and in an AKP.
- **V4** — the naming convention, in either of two forms:
  - the terminal identifier (a simple name, or the last selector of a dot or safe-call chain) is `userId`, ends in `UserId`, or is `sub` (the raw JWT/OIDC subject); or
  - the expression is `<x>.id`, where `x`'s terminal identifier is `user` or ends in `User`.

V4 rests on this codebase's domain convention: every `*UserId` and `user.id` holds a raw `users.id` UUID, and every `.sub` holds a raw claim.

Calls to `UserIdHasher.hash(…)` and `ServiceAccountIdHasher.hash(…)` satisfy none of V1–V4. The sanctioned shapes therefore pass without any special case.

**Alternative considered.** Checking every AKP value for UUID evidence, whatever the key. Rejected: `conversation_id` and `message_id` are UUID primary keys that the spec explicitly allows on spans.

**Known gap.** User-referencing keys outside the alias token set (`author_id`, `sender_id`, `recipient_id`) are not caught by lint when they carry a raw UUID. Instead of an open-ended "etc.", the spec now names exactly which sets the lint enforces, and leaves the rest to the runtime, sentinel and review layers.

### Decision 5: Extend Tier 2 with prefix-anchored vendor secrets, and declare prefix-less opaque secrets as non-lint (#179)

#179 offered two options: (a) regexes on known prefixes, or (b) amending the spec to rely on code review only. Inspecting the backend's actual secret inventory (the `secretKey` / `secrets.resolve` slots) leads to a split answer: **(a) where a stable vendor prefix exists, (b) where none does.**

New patterns. Like the existing Tier 2 patterns, they fire anywhere.

| Pattern | Secret it covers |
|---|---|
| `GOCSPX-[A-Za-z0-9_\-]{20,}` | Google OAuth client secret (spec: "raw OAuth client secrets") |
| `ya29\.[A-Za-z0-9_\-]{20,}` | Google OAuth / metadata-server access token ("raw API bearer tokens") |
| `(?<![A-Za-z0-9/])1//[A-Za-z0-9_\-]{20,}` | Google OAuth refresh token ("raw refresh tokens") |
| `sb_secret_[A-Za-z0-9_\-]{20,}` | Supabase secret API key (`supabase-service-role-key` slot, new-format key) |
| `glc_[A-Za-z0-9+/=_\-]{20,}` | Grafana Cloud access-policy token (`otel-grafana-otlp-token`) |
| `sk-(?:proj\|svcacct\|admin)-[A-Za-z0-9_\-]{20,}` | OpenAI API key (`openai-api-key`) |
| `(?<![A-Za-z0-9])sk_(?:live_\|test_)?[A-Za-z0-9]{24,}` | RevenueCat secret API key (`revenuecat-secret-api-key`; Stripe-style shape) |
| `rediss?://[^:/@\s]*:[^@/\s]+@` (widened from `redis://[^:]+:[^@/]+@`) | `redis-url` in its TLS `rediss://` (Upstash) and user-less `redis://:pw@` forms |

Already covered by existing patterns: the legacy JWT-format Supabase service role key (JWT pattern) and the Firebase service-account private key (PEM pattern).

**Covered only by the non-lint layers.** The spec is amended to say so explicitly for:
- project-issued refresh tokens (`RefreshTokenService`: `SecureRandom` bytes, base64url, no prefix);
- Supabase GoTrue refresh tokens;
- the RevenueCat webhook bearer and HMAC secrets, and the admin HMAC and AES keys (operator-generated and opaque);
- Cloudflare API tokens;
- plaintext password *values*.

The *keys* that would carry these values are still caught by the credential tokens in Decision 3.

**Rejected: Resend `re_…`.** A probe found `re_threshold` / `re_registered`-style substrings in production string literals, so the prefix is not distinctive enough.

**False-positive posture.** Every new pattern requires a vendor prefix followed by a body of 20–24+ characters from a token alphabet, so prose and identifiers do not match. Before merge, the patterns are checked against the whole detekt-scanned production surface (Decision 8).

### Decision 6: The sync guard ends with zero carve-outs

The guard asserts `FORBIDDEN_KEYS` ⊆ `TIER_1_GROUP_A` ∪ `CONTEXT_RESTRICTED_KEYS`, where `CONTEXT_RESTRICTED_KEYS` is `{"user_id"}`.

- The test's `expectedCarveouts` set is deleted.
- A new `FORBIDDEN_KEYS` entry must be enforced by one of the two modes. There is no third "carve-out" bucket.
- The failure message names both modes.

### Decision 7: Allowlists, annotation and composition apply unchanged

The path allowlist and `@AllowForbiddenSpanAttribute("<non-blank>")` gate every mode, the new one included. The allowlisted paths are `/src/test/`, `/infra/otel/src/main/`, `/lint/detekt-rules/src/main/`, and the synthetic `id.nearyou.lint.detekt.*` package.

The new checks are independent of the sibling rules. For example, `setAttribute("actual_location", …)` produces two findings with no cross-suppression:
- one from `OtelForbiddenAttributeRule` (location key);
- one from `CoordinateJitterRule` (the literal, anywhere).

The existing composition test is extended to cover this case. A single literal still reports at most once, even when several checks match it (the existing single-`report` shape).

### Decision 8: Verify with the repo-wide detekt run, not a guess

After the rule changes, `./gradlew detekt` runs over every scanned module. Each new production finding is handled in one of two ways:

- **Fixed at the source**, e.g. switched to the sanctioned key or helper.
- **Sanctioned** with `@AllowForbiddenSpanAttribute("<reason>")`. That annotation class does not yet exist in production code. It is added next to its siblings in `core/domain/.../lint/Annotations.kt` only if a sanction is actually needed (YAGNI otherwise).

The rule is never loosened to make the run pass. The expected result is zero findings:
- the ~30 `"user_id"` sites are all outside any AKP;
- the 4 writer sites use sanctioned keys and helpers.

## Risks / Trade-offs

- **[Risk]** P1 matches any `setAttribute`, including non-OTel APIs. → **Mitigation:** there is none in the scanned backend today, and the annotation escape exists. Narrowing further would need type resolution (Decision 1).
- **[Risk]** The V3 and V4 heuristics. V3 resolves names across the whole file. V4 relies on naming, so a UUID-typed value with an unconventional name, declared in another file, is missed. → **Mitigation:** both apply only when the key is an alias key *and* in an AKP, which keeps false positives small. False negatives are backstopped by the sentinel scenario "No raw JWT claim / user_id appears in any span".
- **[Risk]** P4 does not trace maps built in another file, with `buildMap { }`, or with `+`. → **Mitigation:** the runtime `FORBIDDEN_KEYS` stripping still drops `user_id`, and code review covers the rest. Documented as a `ponytail:` ceiling.
- **[Risk]** A new Tier 2 pattern fires on a legitimate literal. → **Mitigation:** every pattern needs a vendor prefix plus a long body, the repo-wide detekt run verifies them before merge, and the annotation escape exists.
- **[Risk]** The `secret` and `authorization` tokens over-match a benign key (e.g. `secret_manager.slot`). → **Mitigation:** they are checked only in an AKP. Benign uses are rare; annotate them with a reason.
- **[Trade-off]** Sanctioning `display_*` loosens the spec by reversing one sentence. → It is stated explicitly in both the proposal and the spec as an amendment with its rationale (Decision 3), instead of diverging silently.

## Migration Plan

This change is lint-only. There is no runtime, schema or deploy surface.

- **Rollback:** revert the rule, the test and the spec delta.
- **Staging smoke:** none. Archive marks it N/A, as the original `otel-attribute-lint-rule` did.
