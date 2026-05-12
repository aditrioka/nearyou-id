## 1. Rule scaffolding

- [ ] 1.1 Create `lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/IpAxisMustUseTryAcquireByKeyRule.kt` extending Detekt's `Rule` base class. Mirror structural shape of [`OtelForbiddenAttributeRule.kt`](../../../lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/OtelForbiddenAttributeRule.kt) — same package, same `Issue` declaration shape, same `visitCallExpression` override pattern.
- [ ] 1.2 Implement the `key`-argument extraction helper: given a `KtCallExpression`, prefer the named argument `key = ...` (via `valueArguments.firstOrNull { it.getArgumentName()?.asName?.asString() == "key" }`); fall back to positional index 1.
- [ ] 1.3 Implement the `KtStringTemplateExpression` IP-axis match: extract the flat textual content (concatenate all `entries`'s rendered text, treating template entries as their literal source `${...}` form so the regex sees `{ip:$identifier}` rather than the runtime value), then test against the regex `\{[^}]*ip:` (Kotlin: `Regex("\\{[^}]*ip:")`).
- [ ] 1.4 Implement the path-based test-source allowlist: short-circuit `visitCallExpression` if `containingKtFile.virtualFilePath?.contains("/src/test/") == true`. Mirror precedent from `OtelForbiddenAttributeRule`.
- [ ] 1.5 Implement the callee disambiguation: only fire when `KtCallExpression.calleeExpression` is a `KtSimpleNameExpression` with text `tryAcquire` AND the call has at least 2 value arguments (defensive — prevents IndexOutOfBoundsException on malformed call shapes).
- [ ] 1.6 Compose the finding via `report(CodeSmell(issue, Entity.from(keyArgument), message))` where `keyArgument` is the `KtValueArgument` PSI element of the `key` argument and `message` is the recommended-fix text per `specs/rate-limit-infrastructure/spec.md`.
- [ ] 1.7 Add KDoc covering: rule purpose, regex shape, allowlist mechanism, scope-narrow-to-`ip:` rationale (link to `design.md` § Decision 3 + the future-extension TODO), reference back to `openspec/specs/rate-limit-infrastructure/spec.md`.

## 2. Provider registration

- [ ] 2.1 Register `IpAxisMustUseTryAcquireByKeyRule` in [`NearYouRuleSetProvider.kt`](../../../lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/NearYouRuleSetProvider.kt) — add it to the `RuleSet` returned by `instance(config)`. Mirror the existing 8-rule registration shape; place the new entry alphabetically with the other rules.
- [ ] 2.2 Verify the project Detekt config (canonical location: `config/detekt/detekt.yml` — verify path during apply) includes `nearyou-rules.IpAxisMustUseTryAcquireByKey` under `active: true` (or equivalent rule-enable mechanism per the existing 8-rule precedent).

## 3. Lint test fixtures

- [ ] 3.1 Create `lint/detekt-rules/src/test/kotlin/id/nearyou/lint/detekt/IpAxisMustUseTryAcquireByKeyLintTest.kt` extending the project's existing Detekt-rule test base (mirror `OtelForbiddenAttributeLintTest`).
- [ ] 3.2 Implement positive-case fixtures (1–5 per spec scenario list): literal IP-axis key, IP-derived UUID, literal sentinel UUID, template-string IP value, named-argument syntax. Each fixture asserts exactly one finding.
- [ ] 3.3 Implement negative-case fixtures (6–9 per spec scenario list): tryAcquireByKey with IP key, user-axis key, non-IP non-user axis (geocell), Semaphore short-name collision. Each fixture asserts zero findings.
- [ ] 3.4 Implement the test-source allowlist case (10 per spec scenario list): fixture (1) under simulated `/src/test/` virtual path → zero findings.
- [ ] 3.5 Implement the composition case (11 per spec scenario list): fixture `RateLimiter.tryAcquire(userId, "{scope:foo}:{ip:1.2.3.4}", 10, ttl)` run against BOTH `IpAxisMustUseTryAcquireByKeyRule` AND `OtelForbiddenAttributeRule` — assert both finding-IDs appear in the captured findings list.

## 4. Provider registration test

- [ ] 4.1 Extend `NearYouRuleSetProviderTest` (existing class shipped under `otel-attribute-lint-rule`) with a new method `providerExposesIpAxisRule()` asserting `provider.instance(Config.empty()).rules.any { it is IpAxisMustUseTryAcquireByKeyRule }`. Mirror the existing test method shape for the OTel rule's registration assertion.

## 5. Project Detekt run validation

- [ ] 5.1 Run `./gradlew :lint:detekt-rules:test` locally — assert all new tests pass.
- [ ] 5.2 Run `./gradlew detekt` locally — assert the rule is active AND no production source file produces a finding (per spec scenario "Project Detekt run includes the rule"). If a production violation is found, FIX the call site by switching to `tryAcquireByKey` BEFORE proceeding to step 5.3 — do NOT add `@Suppress` or amend the rule's allowlist to mask a real violation.
- [ ] 5.3 Run the full pre-push verification per [`CLAUDE.md`](../../../CLAUDE.md) § "Pre-push verification": `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` — all four targets MUST pass before pushing.

## 6. Spec + docs sync

- [ ] 6.1 Run `openspec validate rate-limit-ip-axis-method-lint-rule --strict` — ensure validation passes.
- [ ] 6.2 At `/opsx:archive` time: delete the [`FOLLOW_UPS.md`](../../../FOLLOW_UPS.md) entry `tryacquirebykey-ip-derived-uuid-detekt-rule` (lines ~246–263) — its scope is fully fulfilled by this change. If `FOLLOW_UPS.md` becomes empty after this deletion, also delete the file itself per the convention in its intro blurb.
- [ ] 6.3 Verify no other `docs/**` or `openspec/specs/**` cross-reference points at the deleted `FOLLOW_UPS.md` entry by grepping for `tryacquirebykey-ip-derived-uuid` and `IpAxisMustUseTryAcquireByKey` after archive — fix any orphan references.

## 7. Pre-archive smoke

- [ ] 7.1 N/A — this is a docs + lint-rule change with no runtime impact (per the Pre-archive smoke convention in [`openspec/project.md`](../../../openspec/project.md) § Staging deploy timing: "For docs-only / refactor-only changes, skip step 2-3 and go straight to archive"). Mark Section 7 N/A in the archive commit body.
