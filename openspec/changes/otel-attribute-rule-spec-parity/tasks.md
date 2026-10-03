# Tasks — `otel-attribute-rule-spec-parity`

## 1. Rule implementation (`OtelForbiddenAttributeRule.kt`)

- [ ] 1.1 AKP detector — P1–P4 per design Decision 2:
  - P1: key = named `key`, else the unnamed argument at position 0; value = named `value`, else the unnamed argument at position 1.
  - P2: factory argument; value = the sibling value from an enclosing `setAttribute` / `put` / `Attributes.of`.
  - P3: builder-evidenced 2-argument `put`.
  - P4: `mapOf` family entry passed to `withSpan`, directly or via a same-file `val`.
  - Hoisted same-file key constants.
  - `ponytail:` ceiling note for untraced shapes.
- [ ] 1.2 Key tokenizer: split on `.`, `_`, `-`, whitespace and the lower→upper camelCase boundary, then lowercase.
- [ ] 1.3 Check 1 (#180): `CONTEXT_RESTRICTED_KEYS = setOf("user_id")`. Remove the carve-out wording from the `TIER_1_GROUP_A` KDoc (Group A content unchanged).
- [ ] 1.4 Check 2 (#177): alias tokens `{user, enduser, principal, actor, subject, owner, account}` + V1–V4 evidence on the peeled value.
  - V3: same-file name, typed `UUID` or initialized from V1/V2/V4; non-recursive.
  - V4: name/property references only, no call selectors; `!!`-peeled receivers.
  - Add a `ponytail:` ceiling note.
- [ ] 1.5 Check 3 (#178): location token set incl. `latlong` / `geohash` / `geom` / `geometry` / `geography` / `wkt`. The exact key `display_location` (tokens `[display, location]`) is the one sanction.
- [ ] 1.6 Check 4 (#179 key side): credential single tokens + adjacent pairs, incl. `cookie`, `id token`, `private key`.
- [ ] 1.7 Check 5: raw JWT/OIDC claim value under any key. Matches `.sub`, or `.subject` on a receiver in `payload` / `decoded` / `claims` / `jwt`.
- [ ] 1.8 Tier 2 (#179 value side):
  - 7 vendor-prefix patterns, with OpenAI also matching the `T3BlbkFJ` marker;
  - Redis widened to `rediss?://[^:/@\s]*:[^@/\s]+@`;
  - PEM stays `[A-Z ]*` (spec text aligned).
- [ ] 1.9 Single `report` per literal. Existing path allowlist and `@AllowForbiddenSpanAttribute` gates run ahead of every check.
- [ ] 1.10 KDoc rewrite:
  - cover the three modes, the AKP shapes, token sets, exact-key sanction, value + claim evidence, and the full "out of reach" list;
  - delete the "user_id carve-out" and "value-aware … deferred" sections;
  - update the issue description string.

## 2. Rule tests (`OtelForbiddenAttributeLintTest.kt`)

- [ ] 2.1 Item 1: replace the carve-out test with the pair — bare/SQL literal passes; `setAttribute("user_id", v)` fires. Correct the stale "sanctioned UserIdHasher.hash" fixture so it actually calls `UserIdHasher.hash` (spec item 7).
- [ ] 2.2 Item 5: Tier 2 positive-fail for every new pattern, plus PEM label-less and the three Redis credential shapes.
  - Assemble secret-shaped values at test runtime and interpolate them into the fixture source as ONE complete literal.
  - Never concatenate inside the fixture: that yields two short literals and a silent false pass.
- [ ] 2.3 Item 6: Tier 2 near-misses, incl. a `/`-preceded `1//` + full-length body (exercises the lookbehind) and `sk_short`.
- [ ] 2.4 Item 11: sync guard → `TIER_1_GROUP_A ∪ CONTEXT_RESTRICTED_KEYS ⊇ FORBIDDEN_KEYS`, disjoint modes, zero carve-outs; the message names both modes.
- [ ] 2.5 Item 13: composition — `setAttribute("actual_location", v)` → 1 Otel finding + 1 `CoordinateJitterRule` finding.
- [ ] 2.6 Item 16: AKP shape matrix for `"user_id"`:
  - P1: positional; named `key =`; positional key + named `value =`.
  - P2: qualified + unqualified.
  - P3: fluent `builder()`; `.toBuilder()`; typed var; `builder()`-initialized var.
  - P4: positional; named; `Pair`; `mutableMapOf` / `hashMapOf` / `linkedMapOf`; hoisted `val`.
  - Hoisted key const used via `setAttribute` and via `AttributeKey`.
- [ ] 2.7 Item 17: non-AKP regressions — `rs.getObject`, `@SerialName`, `call.parameters[...]`, `buildJsonObject { put }`, `mutableMapOf().put`, `call.attributes.put`, `mapOf` not passed to `withSpan`, key const used only in SQL, production `AttributesBuilder.put` safe keys.
- [ ] 2.8 Item 18: alias value-awareness.
  - Fire: V1, V2 (`randomUUID` / `fromString` / `nameUUIDFromBytes`), V3 (typed param, nullable property via template, V2-initialized local, V4-initialized local), V4 (`*UserId`, `user.id`, `userId`, `currentUser!!.id`), P2 nested in `setAttribute` / `Attributes.of`.
  - Pass: `"system"`, `String` non-convention name, both hashers, `conversation_id` with a UUID, `user_agent.original`, `email.subject`, call selector, standalone hoisted `AttributeKey`.
- [ ] 2.9 Item 19: raw claim under any key — `claims.sub` (on `service.account.id`, in a `withSpan` map), `credential.payload.subject`, `decoded.subject`.
- [ ] 2.10 Item 20: location tokens.
  - Fire: `geo.lat`, `actual_location`, `userCoords`, `latitude`, `lng`, `coordinates`, `geohash`, `display_lat`, `display_actual_location`.
  - Pass: `display_location`, `displayLocation`, `latency_ms`, `cloud.platform`, `memory.allocation`, `coordinator`, and non-AKP SQL / `"latitude"` param / `"actual_lat"` export key.
- [ ] 2.11 Item 21: credential tokens.
  - Fire: `client_secret`, `…authorization`, `…cookie`, `refreshToken`, `db.password`, `supabase.service_role_key`, `api_key`, `id_token`, `private_key`.
  - Pass: `gen_ai.usage.input_tokens`, `credential.type`.
- [ ] 2.12 Items 8/9: all three allowlisted paths (`/src/test/`, `/infra/otel/src/main/`, `/lint/detekt-rules/src/main/`) and the annotation bypass also suppress an AKP finding.
- [ ] 2.13 Item 22: single report per literal (`setAttribute("secret_location", v)` → exactly 1).
- [ ] 2.14 `./gradlew :lint:detekt-rules:test :lint:detekt-rules:ktlintCheck` green.

## 3. Repo-wide detekt + production audit

- [ ] 3.1 `./gradlew detekt` across all scanned modules (`:backend:ktor` + the `nearyou.detekt` `:infra:*` / `:core:*` modules).
  - Record the result.
  - Fix any new `OtelForbiddenAttributeRule` finding at its source, or sanction it with `@AllowForbiddenSpanAttribute("<reason>")`. Only in the latter case, declare the annotation class in `core/domain/.../lint/Annotations.kt`.
  - NEVER loosen the rule.
  - Confirm with a temporary probe that the real detekt task fires; remove the probe before commit.
- [ ] 3.2 Audit and confirm each writer surface passes:
  - `setAttribute`: `AuthPlugin`, `InternalEndpointAuth`
  - `withSpan`: `ChatRoutes`, `FcmDispatcher`
  - `AttributesBuilder.put`: `ChatRoutes` publish-failure event, `FcmDispatcher` `attrsBuilder`
  - `tryAcquireByKey`: `HealthRoutes`
  - the ~33 `"user_id"` literals

## 4. Docs

- [ ] 4.1 `docs/04-Architecture.md` § Instrumentation priorities: change the mandatory hashed-user attribute key `user_id` → `user.id`. This matches the shipped `AuthPlugin` / `FcmDispatcher` key; `user_id` is runtime-stripped and now lint-forbidden as an attribute key.

## 5. Validation + gate

- [ ] 5.1 `openspec validate otel-attribute-rule-spec-parity --strict` green.
- [ ] 5.2 Pre-push gate `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test`. Use a throwaway Postgres on a free port if :5433 is dirty (docs/13 §5).

## 6. Pre-archive smoke (N/A for lint-only change)

- [ ] 6.1 N/A — no runtime code path and no staging deploy surface (lint + spec + docs only). Record `N/A` in the archive commit body.

## 7. Archive (same branch, same PR)

- [ ] 7.1 `openspec archive otel-attribute-rule-spec-parity --yes`, then:
  - `openspec validate --specs observability-otel-foundation --strict` green;
  - confirm no "TBD - created by archiving" Purpose.
- [ ] 7.2 PR body current, with four separate closing keywords (`Closes #177`, `Closes #178`, `Closes #179`, `Closes #180`).
