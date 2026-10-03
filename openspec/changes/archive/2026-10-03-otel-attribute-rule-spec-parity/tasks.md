# Tasks — `otel-attribute-rule-spec-parity`

## 1. Rule implementation (`OtelForbiddenAttributeRule.kt`)

- [x] 1.1 AKP detector — P1–P4 per design Decision 2:
  - P1: key = named `key`, else the unnamed argument at position 0; value = named `value`, else the unnamed argument at position 1.
  - P2: factory argument; value = the sibling value from an enclosing `setAttribute` / `put` / `Attributes.of`.
  - P3: builder-evidenced 2-argument `put`.
  - P4: `mapOf` family entry passed to `withSpan`, directly or via a same-file `val`.
  - Hoisted same-file key constants.
  - `ponytail:` ceiling note for untraced shapes.
- [x] 1.2 Key tokenizer: split on `.`, `_`, `-`, whitespace and the lower→upper camelCase boundary, then lowercase.
- [x] 1.3 Check 1 (#180): `CONTEXT_RESTRICTED_KEYS = setOf("user_id")`. Remove the carve-out wording from the `TIER_1_GROUP_A` KDoc (Group A content unchanged).
- [x] 1.4 Check 2 (#177): alias tokens `{user, enduser, principal, actor, subject, owner, account}` + V1–V4 evidence on the peeled value.
  - V3: same-file name, typed `UUID` or initialized from V1/V2/V4; non-recursive.
  - V4: name/property references only, no call selectors; `!!`-peeled receivers.
  - Add a `ponytail:` ceiling note.
- [x] 1.5 Check 3 (#178): location token set incl. `latlong` / `geohash` / `geom` / `geometry` / `geography` / `wkt`. The exact key `display_location` (tokens `[display, location]`) is the one sanction.
- [x] 1.6 Check 4 (#179 key side): credential single tokens + adjacent pairs, incl. `cookie`, `id token`, `private key`.
- [x] 1.7 Check 5: raw JWT/OIDC claim value under any key. Matches `.sub`, or `.subject` on a receiver in `payload` / `decoded` / `claims` / `jwt`.
- [x] 1.8 Tier 2 (#179 value side):
  - 7 vendor-prefix patterns, with OpenAI also matching the `T3BlbkFJ` marker;
  - Redis widened to `rediss?://[^:/@\s]*:[^@/\s]+@`;
  - PEM stays `[A-Z ]*` (spec text aligned).
- [x] 1.9 Single `report` per literal. Existing path allowlist and `@AllowForbiddenSpanAttribute` gates run ahead of every check.
- [x] 1.10 KDoc rewrite:
  - cover the three modes, the AKP shapes, token sets, exact-key sanction, value + claim evidence, and the full "out of reach" list;
  - delete the "user_id carve-out" and "value-aware … deferred" sections;
  - update the issue description string.

## 2. Rule tests (`OtelForbiddenAttributeLintTest.kt`)

- [x] 2.1 Item 1: replace the carve-out test with the pair — bare/SQL literal passes; `setAttribute("user_id", v)` fires. Correct the stale "sanctioned UserIdHasher.hash" fixture so it actually calls `UserIdHasher.hash` (spec item 7).
- [x] 2.2 Item 5: Tier 2 positive-fail for every new pattern, plus PEM label-less and the three Redis credential shapes.
  - Assemble secret-shaped values at test runtime and interpolate them into the fixture source as ONE complete literal.
  - Never concatenate inside the fixture: that yields two short literals and a silent false pass.
- [x] 2.3 Item 6: Tier 2 near-misses, incl. a `/`-preceded `1//` + full-length body (exercises the lookbehind) and `sk_short`.
- [x] 2.4 Item 11: sync guard → `TIER_1_GROUP_A ∪ CONTEXT_RESTRICTED_KEYS ⊇ FORBIDDEN_KEYS`, disjoint modes, zero carve-outs; the message names both modes.
- [x] 2.5 Item 13: composition — `setAttribute("actual_location", v)` → 1 Otel finding + 1 `CoordinateJitterRule` finding.
- [x] 2.6 Item 16: AKP shape matrix for `"user_id"`:
  - P1: positional; named `key =`; positional key + named `value =`.
  - P2: qualified + unqualified.
  - P3: fluent `builder()`; `.toBuilder()`; typed var; `builder()`-initialized var.
  - P4: positional; named; `Pair`; `mutableMapOf` / `hashMapOf` / `linkedMapOf`; hoisted `val`.
  - Hoisted key const used via `setAttribute` and via `AttributeKey`.
- [x] 2.7 Item 17: non-AKP regressions — `rs.getObject`, `@SerialName`, `call.parameters[...]`, `buildJsonObject { put }`, `mutableMapOf().put`, `call.attributes.put`, `mapOf` not passed to `withSpan`, key const used only in SQL, production `AttributesBuilder.put` safe keys.
- [x] 2.8 Item 18: alias value-awareness.
  - Fire: V1, V2 (`randomUUID` / `fromString` / `nameUUIDFromBytes`), V3 (typed param, nullable property via template, V2-initialized local, V4-initialized local), V4 (`*UserId`, `user.id`, `userId`, `currentUser!!.id`), P2 nested in `setAttribute` / `Attributes.of`.
  - Pass: `"system"`, `String` non-convention name, both hashers, `conversation_id` with a UUID, `user_agent.original`, `email.subject`, call selector, standalone hoisted `AttributeKey`.
- [x] 2.9 Item 19: raw claim under any key — `claims.sub` (on `service.account.id`, in a `withSpan` map), `credential.payload.subject`, `decoded.subject`.
- [x] 2.10 Item 20: location tokens.
  - Fire: `geo.lat`, `actual_location`, `userCoords`, `latitude`, `lng`, `coordinates`, `geohash`, `display_lat`, `display_actual_location`.
  - Pass: `display_location`, `displayLocation`, `latency_ms`, `cloud.platform`, `memory.allocation`, `coordinator`, and non-AKP SQL / `"latitude"` param / `"actual_lat"` export key.
- [x] 2.11 Item 21: credential tokens.
  - Fire: `client_secret`, `…authorization`, `…cookie`, `refreshToken`, `db.password`, `supabase.service_role_key`, `api_key`, `id_token`, `private_key`.
  - Pass: `gen_ai.usage.input_tokens`, `credential.type`.
- [x] 2.12 Items 8/9: all three allowlisted paths (`/src/test/`, `/infra/otel/src/main/`, `/lint/detekt-rules/src/main/`) and the annotation bypass also suppress an AKP finding.
- [x] 2.13 Item 22: single report per literal (`setAttribute("secret_location", v)` → exactly 1).
- [x] 2.14 `./gradlew :lint:detekt-rules:test :lint:detekt-rules:ktlintCheck` green. _`OtelForbiddenAttributeLintTest`: 168 tests (was 58), 0 failed; module total 317, 0 failed._

## 3. Repo-wide detekt + production audit

- [x] 3.1 `./gradlew detekt` across all scanned modules (`:backend:ktor` + the `nearyou.detekt` `:infra:*` / `:core:*` modules). _Result: 17 detekt tasks re-run (`--rerun-tasks`), all reports fresh with **0 findings**. No source fix and no annotation needed, so `AllowForbiddenSpanAttribute` was NOT added to production (YAGNI). A temporary `:backend:ktor` probe (`setAttribute("user_id"|"geo.lat"|"principal"+userId)`) produced 3 real-pipeline findings and was removed before commit._
  - Record the result.
  - Fix any new `OtelForbiddenAttributeRule` finding at its source, or sanction it with `@AllowForbiddenSpanAttribute("<reason>")`. Only in the latter case, declare the annotation class in `core/domain/.../lint/Annotations.kt`.
  - NEVER loosen the rule.
  - Confirm with a temporary probe that the real detekt task fires; remove the probe before commit.
- [x] 3.2 Audit and confirm each writer surface passes:
  - `setAttribute`: `AuthPlugin`, `InternalEndpointAuth`
  - `withSpan`: `ChatRoutes`, `FcmDispatcher`
  - `AttributesBuilder.put`: `ChatRoutes` publish-failure event, `FcmDispatcher` `attrsBuilder`
  - `tryAcquireByKey`: `HealthRoutes`
  - the ~30 `"user_id"` literals

## 4. Docs

- [x] 4.1 `docs/04-Architecture.md` § Instrumentation priorities: change the mandatory hashed-user attribute key `user_id` → `user.id`. This matches the shipped `AuthPlugin` / `FcmDispatcher` key; `user_id` is runtime-stripped and now lint-forbidden as an attribute key.

## 5. Validation + gate

- [x] 5.1 `openspec validate otel-attribute-rule-spec-parity --strict` green.
- [x] 5.2 Pre-push gate `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test`. Use a throwaway Postgres on a free port if :5433 is dirty (docs/13 §5). _Green 2026-10-03 against a throwaway `postgis/postgis:16-3.4` on :5438 (`--no-daemon`, `:backend:ktor:test --rerun`, `:lint:detekt-rules:test --rerun`): backend 2632 tests / 0 failed, lint 317 / 0 failed, ktlint + detekt clean._

## 6. Pre-archive smoke (N/A for lint-only change)

- [x] 6.1 N/A — no runtime code path and no staging deploy surface (lint + spec + docs only). Record `N/A` in the archive commit body.

## 7. Archive (same branch, same PR)

- [x] 7.1 `openspec archive otel-attribute-rule-spec-parity --yes`, then:
  - `openspec validate --specs observability-otel-foundation --strict` green;
  - confirm no "TBD - created by archiving" Purpose.
- [x] 7.2 PR body current, with four separate closing keywords (`Closes #177`, `Closes #178`, `Closes #179`, `Closes #180`).

## 8. Final-review round 1 (four sub-agent lenses; qodo unavailable — subscription inactive)

- [x] 8.1 Operator chose "should-fix + cheap fixes". Rule changes:
  - hoisted keys are checked at EVERY reference, and an annotated use site is sanctioned there;
  - new check 6: raw `clientIp` value under any key;
  - V4 ignores `hash`-named values;
  - V3 resolves parameters and properties only;
  - V2 adds Kotlin `Uuid` and `UuidV7.next()`;
  - peeling covers `?:` and `toJavaUuid` / `toKotlinUuid`;
  - the claim receiver is peeled;
  - triple-quoted UUIDs match;
  - `user_id` matching is case-insensitive;
  - the tokenizer gains an acronym boundary;
  - Tier 2 `ya29.c.` and `postgres(ql)://` are covered.
- [x] 8.2 Spec, design (Decision 9), proposal and KDoc updated. Known limits now include semconv constants, multi-entry templates, lowercase compounds and hoisted `AttributeKey` values. Resend `re_` and Cloudflare are listed as prefix-less. `display_location` value provenance = code review. Counts reconciled (~30 `user_id` literals; 17 reports = 16 custom-ruleset modules + `:mobile:app`).
- [x] 8.3 Tests:
  - the `call.attributes.put` fixture is fixed;
  - new fixtures for every fix above, plus the `sk_` lookbehind, the `withSpan` location key and the Tier-1-in-`withSpan` scenario;
  - 3 duplicates removed and stale labels corrected.
  - Result: `OtelForbiddenAttributeLintTest` 192 tests / 0 failed (module 341 / 0); ktlint clean; repo-wide detekt re-run with 17 fresh reports, **0 findings**.
- [x] 8.4 Deferred and recorded as non-blocking in the PR body: splitting the rule file (~700 lines vs the ~500 soft cap), semconv-constant tokenization, multi-entry template scanning, value pairing for hoisted `AttributeKey` vals.
- [x] 8.5 Round 2 (the cap), a regression scan of the round-1 commit. No blocking findings; no production false positives from the new checks. Applied:
  - spec "checks 2 and 5" → "checks 2, 5 and 6" in 4 places;
  - tests for the Kotlin `Uuid`-typed V3 and `toKotlinUuid` peeling, plus `Uuid.parse`;
  - stale KDoc link and Tier 2 KDoc text; `unquoted()` reused in `visitStringTemplateExpression`;
  - old "Task 2.N" test headers relabeled to this change's spec items;
  - proposal hoisted-key wording.

  Result: 195 tests / 0 failed (module 344 / 0); ktlint clean; repo-wide detekt 0 findings. Review closed at 2 rounds.
