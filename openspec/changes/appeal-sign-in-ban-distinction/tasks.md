## 1. Backend — surface `suspended_until` on the sign-in 403

- [ ] 1.1 Add a nullable `suspended_until` (ISO-8601 timestamp) field to the banned 403 response DTO in `backend/ktor/src/main/kotlin/id/nearyou/app/auth/routes/AuthRoutes.kt` (the `account_banned` response). Populate it from the matched user's `users.suspended_until` (null for permanent, the timestamp for a suspension). Keep code `account_banned` and the `appeal_token` issuance unchanged.
- [ ] 1.2 Confirm the matched-user query already selects `suspended_until` (it derives the appeal `action_type`); thread it into the 403 response without an extra query.
- [ ] 1.3 No Flyway migration (column exists) — verify no schema change is introduced.

## 2. Backend — tests

- [ ] 2.1 In `backend/ktor/src/test/kotlin/id/nearyou/app/auth/routes/SignInFlowTest.kt`, add: suspended user (`is_banned = TRUE, suspended_until > NOW()`) → 403 `account_banned` with `suspended_until` = the future timestamp + `appeal_token` present.
- [ ] 2.2 Add: permanently-banned user (`is_banned = TRUE, suspended_until IS NULL`) → 403 `account_banned` with `suspended_until = null` + `appeal_token` present.
- [ ] 2.3 Assert the existing invariants still hold for both: no `refresh_tokens` row inserted; `appeal_token` is a `scope="appeal"` RS256 JWT with current `token_version`.

## 3. Mobile — parse + outcome split

- [ ] 3.1 Extend the 403 response parse in `mobile/app/src/commonMain/kotlin/id/nearyou/app/auth/AuthRepository.kt` to read `suspended_until` from the `account_banned` body (alongside the existing `appealToken` stash into `appealSession`).
- [ ] 3.2 Split `SignInOutcome.Banned` to carry the suspension-vs-permanent distinction (single `Banned(suspendedUntil: Instant?)` variant or two variants — implementer's choice per design Decision 2); map non-null → suspension sub-state, null → permanent sub-state.
- [ ] 3.3 Update `SignInViewModel.kt` so `showAppealEntry` is true for the suspension sub-state only (false for permanent).

## 4. Mobile — copy + screen branch

- [ ] 4.1 Add a net-new `signin_error_suspended` string to `shared/resources/src/commonMain/composeResources/values/strings.xml` (suspension-appropriate copy; appeal-entry copy already exists). Add the matching `Res.string` import at the use site.
- [ ] 4.2 Branch `SignInScreen`: suspension → `signin_error_suspended` banner + "Ajukan banding" appeal entry; permanent → `signin_error_banned` "Hubungi support" copy + no appeal entry. CTA disabled (visual + tap-rejected) in both. No hardcoded UI strings.

## 5. Mobile — tests

- [ ] 5.1 In the `AuthRepository` sign-in test suite, add: 403 with non-null `suspended_until` → suspension outcome, copy = `signin_error_suspended`, appeal entry surfaced, `ctaEnabled = false`, `appeal_token` captured.
- [ ] 5.2 Add: 403 with null `suspended_until` → permanent outcome, copy = `signin_error_banned`, NO appeal entry, `ctaEnabled = false`, `appeal_token` captured.
- [ ] 5.3 Verify the existing banned-CTA-tap-rejection and no-PII-in-error-state scenarios still pass for both sub-states.

## 6. Specs + delivery

- [ ] 6.1 `openspec validate appeal-sign-in-ban-distinction --strict` passes (deltas for `auth-signin`, `mobile-auth-signin`, `mobile-appeal`).
- [ ] 6.2 Run the pre-push gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test`.
- [ ] 6.3 PR body closes #391 and #187 (repeat the closing keyword per issue). Verify post-merge with `gh issue view`.
- [ ] 6.4 Manual verification (DoD): sign in as a suspended test user → suspension copy + appeal entry; as a permanently-banned user → support copy, no entry. Capture evidence per `docs/11` § 5.
