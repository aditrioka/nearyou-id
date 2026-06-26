## 1. Backend — surface `suspended_until` on the sign-in 403

- [x] 1.1 Add a nullable `suspended_until` (ISO-8601 timestamp) field to the banned 403 response DTO in `backend/ktor/src/main/kotlin/id/nearyou/app/auth/routes/AuthRoutes.kt` (the `account_banned` response). Populate it from the matched user's `users.suspended_until` (null for permanent, the timestamp for a suspension). Keep code `account_banned` and the `appeal_token` issuance unchanged.
- [x] 1.2 Confirm the matched-user query already selects `suspended_until` (it derives the appeal `action_type`); thread it into the 403 response without an extra query.
- [x] 1.3 No Flyway migration (column exists) — verify no schema change is introduced.

## 2. Backend — tests

- [x] 2.1 In `backend/ktor/src/test/kotlin/id/nearyou/app/auth/routes/SignInFlowTest.kt`, add: suspended user (`is_banned = TRUE, suspended_until > NOW()`) → 403 `account_banned` with `suspended_until` = the future timestamp + `appeal_token` present.
- [x] 2.2 Add: permanently-banned user (`is_banned = TRUE, suspended_until IS NULL`) → 403 `account_banned` with `suspended_until = null` + `appeal_token` present.
- [x] 2.3 Assert the existing invariants still hold for both: no `refresh_tokens` row inserted; `appeal_token` is a `scope="appeal"` RS256 JWT with current `token_version`.

## 3. Mobile — parse + outcome split

- [x] 3.1 Extend the 403 response parse in `mobile/app/src/commonMain/kotlin/id/nearyou/app/auth/AuthRepository.kt` to read `suspended_until` from the `account_banned` body. Capture the `appealToken` into `appealSession` **before** the suspension/permanent branch (capture is sub-state-independent). If `suspended_until` is absent/unparseable, default to the permanent sub-state (safe degrade).
- [x] 3.2 Split `SignInOutcome.Banned` to carry the suspension-vs-permanent distinction (single `Banned(suspendedUntil: Instant?)` variant or two variants — implementer's choice per design Decision 2). Discriminator is **null-ness**: non-null (including a past timestamp) → suspension sub-state, `null` → permanent sub-state.
- [x] 3.3 Update `SignInViewModel.kt` so `showAppealEntry` is true for the suspension sub-state only (false for permanent).

## 4. Mobile — copy + screen branch

- [x] 4.1 Add a net-new `signin_error_suspended` string to `shared/resources/src/commonMain/composeResources/values/strings.xml` (suspension-appropriate copy; appeal-entry copy already exists). Add the matching `Res.string` import at the use site.
- [x] 4.2 Branch `SignInScreen`: suspension → `signin_error_suspended` banner + "Ajukan banding" appeal entry; permanent → `signin_error_banned` "Hubungi support" copy + no appeal entry. CTA disabled (visual + tap-rejected) in both. No hardcoded UI strings.

## 5. Mobile — tests

- [x] 5.1 In the `AuthRepository` sign-in test suite, add: 403 with non-null `suspended_until` → suspension outcome, copy = `signin_error_suspended`, appeal entry surfaced, `ctaEnabled = false`, `appeal_token` captured.
- [x] 5.2 Add: 403 with null `suspended_until` → permanent outcome, copy = `signin_error_banned`, NO appeal entry, `ctaEnabled = false`, `appeal_token` captured.
- [x] 5.3 Verify the existing banned-CTA-tap-rejection and no-PII-in-error-state scenarios still pass for both sub-states.
- [x] 5.4 Update `SignInViewModelTest` and `SignInUiStateTest` (which currently assert the single `SignInOutcome.Banned` → `showAppealEntry = true` / banned banner) for the split: suspension sub-state → appeal entry + `signin_error_suspended` banner; permanent sub-state → no appeal entry + `signin_error_banned` banner. (These compile-break against the retired single `Banned` shape if not updated.)
- [x] 5.5 Edge tests: (a) a non-null **past** `suspended_until` → suspension sub-state (appeal entry shown — null-ness, not future-ness, is the discriminator); (b) an absent/unparseable `suspended_until` → permanent sub-state (safe degrade — no appeal entry).

## 6. Specs + delivery

- [x] 6.1 `openspec validate appeal-sign-in-ban-distinction --strict` passes (deltas for `auth-signin`, `mobile-auth-signin`, `mobile-appeal`).
- [x] 6.2 Run the pre-push gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test`.
- [x] 6.3 PR body closes #391 and #187 (repeat the closing keyword per issue). Verify post-merge with `gh issue view`.
- [x] 6.4 Manual verification (DoD): sign in as a suspended test user → suspension copy + appeal entry; as a permanently-banned user → support copy, no entry. Capture evidence per `docs/11` § 5. **Verified E2E on staging** (physical device, staging-debug APK, branch deployed via `deploy-staging.yml` run 28252990644): test account `smoketest_adi` flipped to permanent ban (`is_banned=TRUE, suspended_until=NULL`) → sign-in 403 rendered `signin_error_banned` "Hubungi support" copy, CTA disabled, **no** "Ajukan banding" entry; flipped to suspension (`suspended_until=NOW()+7d`) → 403 rendered `signin_error_suspended` "ditangguhkan sementara" copy + **"Ajukan banding"** entry, CTA disabled. Account restored to clean state post-verify. Screenshots captured (delivered to operator; `dev/device-runs/` is gitignored).
