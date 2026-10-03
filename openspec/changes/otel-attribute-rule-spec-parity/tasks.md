# Tasks — `otel-attribute-rule-spec-parity`

## 1. Rule implementation (`OtelForbiddenAttributeRule.kt`)

- [ ] 1.1 Attribute-key-position (AKP) detector. Implement P1–P4 from design Decision 2:
  - P1: `setAttribute` key arg (named `key` wins, else positional 0);
  - P2: `AttributeKey.*Key(...)` factories;
  - P3: builder-evidenced 2-arg `put(...)`;
  - P4: `mapOf`/`mutableMapOf`/`hashMapOf`/`linkedMapOf` entry key (`to` / `Pair`) passed as `withSpan`'s `attributes`, either directly (named or positional 1) or via a same-file `val` name.

  Add a `ponytail:` ceiling note for the untraced shapes: cross-file maps, `buildMap`, `+`.
- [ ] 1.2 Key tokenizer: split on `.` `_` `-` whitespace + lower→upper camelCase boundary, then lowercase.
- [ ] 1.3 Check 1 (#180): `CONTEXT_RESTRICTED_KEYS = setOf("user_id")` fires in AKP regardless of value. Remove the `user_id` carve-out wording from `TIER_1_GROUP_A` KDoc (Group A content unchanged).
- [ ] 1.4 Check 2 (#177): alias tokens `{user, enduser, principal, actor, subject, owner, account}` + raw-identifier evidence V1–V4 on the peeled value (parens, `!!`, trailing `.toString()`, single-entry template). V3 is same-file name resolution — add a `ponytail:` ceiling note.
- [ ] 1.5 Check 3 (#178): location token set, with the `display`-first-token sanction.
- [ ] 1.6 Check 4 (#179 key side): credential single tokens `{password, passwd, secret, secrets, bearer, authorization, apikey}` + adjacent pairs `{refresh token, access token, api key, service role}`.
- [ ] 1.7 Tier 2 (#179 value side):
  - add the 7 prefix-anchored patterns (`GOCSPX-`, `ya29.`, `1//`, `sb_secret_`, `glc_`, `sk-(proj|svcacct|admin)-`, `sk_`);
  - widen Redis to `rediss?://[^:/@\s]*:[^@/\s]+@`;
  - keep PEM `[A-Z ]*` (spec text now aligned to the code's label-less-PKCS#8-matching form).
- [ ] 1.8 Single `report` per literal (any mode). Reuse the existing path allowlist + `@AllowForbiddenSpanAttribute` gates ahead of every check.
- [ ] 1.9 KDoc rewrite:
  - modes (Mode A anywhere, Mode A attribute-key-position, Mode B), AKP shapes, token sets + `display_*` sanction, value evidence;
  - delete the "user_id carve-out" and "value-aware … deferred" sections;
  - update the issue description string to mention the attribute-key checks + new secret shapes.

## 2. Rule tests (`OtelForbiddenAttributeLintTest.kt`)

- [ ] 2.1 Replace the `user_id` carve-out test with the item-1 pair: bare/SQL literal passes; `setAttribute("user_id", v)` fires.
- [ ] 2.2 Item 5: Tier 2 positive-fail for every new pattern + PEM label-less + the three Redis credential shapes. Build secret-shaped fixture values at runtime (`"GOCSPX-" + "a".repeat(28)` style), so the committed test source never contains a scanner-matching token (GitHub push protection).
- [ ] 2.3 Item 6: Tier 2 near-miss negatives (bare/short prefixes, `https://host/1//`, `risk_assessment_threshold_value_x`, `re_threshold`, `sk-learn`, `rediss://host:6380/0`).
- [ ] 2.4 Item 11: sync guard → `TIER_1_GROUP_A ∪ CONTEXT_RESTRICTED_KEYS ⊇ FORBIDDEN_KEYS` snapshot, zero carve-outs, failure message names both modes.
- [ ] 2.5 Item 13: composition — `setAttribute("actual_location", v)` → 1 Otel finding + 1 `CoordinateJitterRule` finding.
- [ ] 2.6 Item 16: AKP shape matrix for `"user_id"` (P1 positional + named `key =`; P2; P3 fluent + typed var + `Attributes.builder()`-initialized var; P4 positional, named `attributes =`, `Pair(...)`, hoisted same-file `val`).
- [ ] 2.7 Item 17: non-AKP production-shape regressions (`rs.getObject`, `@SerialName`, `call.parameters[...]`, `buildJsonObject { put(...) }`, `mutableMapOf().put(...)`, `mapOf` not passed to `withSpan`).
- [ ] 2.8 Item 18: alias value-awareness.
  - Fire: V1 UUID literal, V2 `UUID.randomUUID()` / `fromString`, V3 same-file `UUID`-typed param, V4 `userId` / `*UserId` / `user.id` / `.sub`.
  - Pass: `"system"`, `UserIdHasher.hash`, `ServiceAccountIdHasher.hash`, `String`-typed non-convention name, `conversation_id` with UUID.
  - Keys covered: `principal`, `actor`, `subject`, `owner`, `user.id`, `enduser.id`, `service.account.id`.
- [ ] 2.9 Item 19: location tokens.
  - Fire: `geo.lat`, `actual_location`, `userCoords`, `latitude`, `lng`, `coordinates`.
  - Pass: `display_location`, `display_lat`, `latency_ms`, `cloud.platform`, `memory.allocation`, `coordinator`, non-AKP SQL text / `"latitude"` param literal.
- [ ] 2.10 Item 20: credential tokens.
  - Fire: `client_secret`, `http.request.header.authorization`, `refreshToken`, `db.password`, `supabase.service_role_key`, `api_key`.
  - Pass: `gen_ai.usage.input_tokens`, `credential.type`.
- [ ] 2.11 Items 8/9: path allowlist + annotation bypass also suppress an AKP finding (`setAttribute("user_id", v)` under `/src/test/` and under `@AllowForbiddenSpanAttribute("reason")`).
- [ ] 2.12 Item 21: single report per literal (`setAttribute("secret_location", v)` → exactly 1).
- [ ] 2.13 `./gradlew :lint:detekt-rules:test` green.

## 3. Repo-wide detekt + production audit

- [ ] 3.1 `./gradlew detekt` across all scanned modules (`:backend:ktor` + `nearyou.detekt` `:infra:*` / `:core:*`).
  - Record the result. Any new `OtelForbiddenAttributeRule` finding is fixed at source or sanctioned with `@AllowForbiddenSpanAttribute("<reason>")`; only in that case, declare the annotation class in `core/domain/.../lint/Annotations.kt`.
  - NEVER loosen the rule.
- [ ] 3.2 Audit the writer surfaces named in the "Detekt run … remains green" requirement and confirm each passes:
  - `setAttribute`: `AuthPlugin`, `InternalEndpointAuth`;
  - `withSpan`: `ChatRoutes`, `FcmDispatcher`;
  - `tryAcquireByKey`: `HealthRoutes`;
  - the ~30 `"user_id"` literals.

## 4. Docs

- [ ] 4.1 `docs/04-Architecture.md` § Instrumentation priorities: mandatory hashed-user attribute key `user_id` → `user.id`. This matches the shipped `AuthPlugin`/`FcmDispatcher` key; `user_id` is runtime-stripped and now lint-forbidden as an attribute key.

## 5. Validation + gate

- [ ] 5.1 `openspec validate otel-attribute-rule-spec-parity --strict` green.
- [ ] 5.2 Pre-push gate `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test`. Use a throwaway Postgres on :5434 if :5433 is dirty — docs/13 §5.

## 6. Pre-archive smoke (N/A for lint-only change)

- [ ] 6.1 N/A — no runtime code path, no staging deploy surface (lint + spec + docs only). Record `N/A` in the archive commit body.

## 7. Archive (same branch, same PR)

- [ ] 7.1 `openspec archive otel-attribute-rule-spec-parity --yes`; `openspec validate --specs observability-otel-foundation --strict` green; confirm no "TBD - created by archiving" Purpose.
- [ ] 7.2 PR body current, with four separate closing keywords (`Closes #177`, `Closes #178`, `Closes #179`, `Closes #180`).
