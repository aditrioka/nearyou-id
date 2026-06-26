## 1. Backend — referral read endpoint (`referral-read`)

- [ ] 1.1 Add a `ReferralReadRepository` (JDBC) with: `findInviteCodePrefix(userId)` (reads `users.invite_code_prefix`), `grantedCountForInviter(userId)` (reuse the `SELECT COUNT(*) FROM referral_tickets WHERE inviter_user_id = ? AND status = 'granted'` shape from `ReferralGrantRepository`), and `inviterRewardClaimed(userId)` (`users.inviter_reward_claimed_at IS NOT NULL`) — or a single combined query returning all three.
- [ ] 1.2 Add the response DTO `ReferralStateResponse(inviteCode: String, grantedReferrals: Int, milestone: Int, inviterRewardClaimed: Boolean)` (bare camelCase, `@Serializable`). Source `milestone` from `ReferralActivityCheckWorker.INVITER_MILESTONE` (= 5) — do not hardcode a second literal.
- [ ] 1.3 Add `ReferralReadRoutes.kt` exposing `GET /api/v1/user/referral` under `authenticate(AUTH_PROVIDER_USER)`, principal-only (no `user_id` param → no IDOR), mirroring `HideDistanceRoutes` GET: `401` on missing principal, `200` with the DTO, `CancellationException` rethrow, fail-soft `500`, PII-safe logging (event name + exception class only).
- [ ] 1.4 Wire `referralReadRoutes(...)` into `Application.kt` (construct the repository from the shared `DataSource`, register the route).
- [ ] 1.5 Add `ReferralReadRoutesTest` (DB-tagged): authenticated `200` returns the caller's code + progress; unauthenticated `401`; the response reflects seeded granted-ticket count + claimed flag + the zero/unclaimed state; a forced repository failure fails soft as `500` with a PII-free log line. The test pool MUST `autoClose` (docs/11 §3.2 connection-budget rule). Seed must not pollute timeline suites (no extra posts).
- [ ] 1.6 Add a `ReferralReadRepository` unit/integration test for the granted-count + claimed-flag queries against seeded `referral_tickets` / `users` rows.

## 2. Mobile — referral data layer (`mobile-referral`)

- [ ] 2.1 Add `mobile/app/src/commonMain/kotlin/id/nearyou/app/referral/ReferralApiClient.kt`: thin shared-`HttpClient` wrapper over `GET /api/v1/user/referral`; `@Serializable ReferralStateDto(inviteCode, grantedReferrals, milestone, inviterRewardClaimed)`; sealed `ReferralStateResult` (`Success(...)` / `Failure`); `CancellationException` rethrow. Mirror `HideDistanceApiClient`.
- [ ] 2.2 Add `ReferralRepository` mapping the client result to a domain referral state (or a failure for the error UI state).
- [ ] 2.3 Register `ReferralApiClient` + `ReferralRepository` + `ReferralViewModel` in `MobileModule.kt` (Koin), mirroring the hide-distance wiring.
- [ ] 2.4 Add `ReferralApiClientTest` (Ktor MockEngine): `200` → `Success` with parsed fields; non-`200`/transport failure → `Failure`.

## 3. Mobile — referral screen + ViewModel (`mobile-referral`)

- [ ] 3.1 Add `ReferralViewModel` (commonMain androidx `ViewModel`) exposing ONE `StateFlow<ReferralUiState>` via `stateIn(WhileSubscribed)`; `ReferralUiState` models loading / loaded (`inviteCode`, `grantedReferrals`, `milestone`, `inviterRewardClaimed`) / error; fetch on init; retry re-fetches.
- [ ] 3.2 Add `ReferralScreen.kt`: invite-code display + copy-to-clipboard action (`LocalClipboardManager`); progress "X dari 5" + reward-unlocked state; explanatory copy; loading/error states; all strings via `:shared:resources`; reuse the Settings sub-surface visual idiom (`ConsentSettingsScreen` precedent), `NearYouTheme`.
- [ ] 3.3 Add `ReferralRoute` (parameterless `@Serializable data object`) to `NavKeys.kt`, map it to `ReferralScreen` in the `entryProvider`, and register it in the `navSavedStateConfiguration` polymorphic `SerializersModule`.
- [ ] 3.4 Add `ReferralViewModel` test (loading → loaded on success; error on failure; retry re-fetches) and a `ReferralScreen` Robolectric test (renders code + copy action; copy places code on clipboard; "X dari 5" progress; reward-unlocked state; Free user sees no paywall).

## 4. Mobile — Settings entry (`mobile-referral` + `mobile-settings`)

- [ ] 4.1 Add an "Undang teman" entry row to `SettingsScreen.kt` (string via `:shared:resources`) that emits a push-`ReferralRoute` navigation event; visible to all tiers (no paywall). Coordinate the row placement with in-flight #424 (`mobile-data-export-entry`) — different rows, rebase whichever lands second.
- [ ] 4.2 Wire the Settings → `ReferralRoute` push in the app entry/nav host (the `BlockedUsersRoute` / `ConsentSettingsRoute` push mechanism).
- [ ] 4.3 Add a Settings → referral navigation test (tapping the row emits the push event).

## 5. Mobile — invite-code redemption at signup (`mobile-age-gate`)

- [ ] 5.1 Add an optional `inviteCode` field to `AgeGateUiState` + `AgeGateViewModel` (held in UI state, NOT `PendingSignupIdentity`); add an on-change handler.
- [ ] 5.2 Render an optional "Kode undangan (opsional)" `TextField` on `AgeGateScreen` (label via `Res.string.age_gate_invite_code_label`); empty/blank is allowed and never blocks the CTA.
- [ ] 5.3 Add the optional `inviteCode` parameter to `AuthRepository.signUpWithGoogle(...)` and thread it to `AuthApiClient.signUp(...)`; add `@SerialName("invite_code") val inviteCode: String? = null` to `SignUpRequest`; trim and send only when non-blank (omit the key otherwise).
- [ ] 5.4 Add/extend `AuthApiClientSignUpTest`: signup with an entered code includes `invite_code`; blank input omits the key; existing no-fingerprint + canonical-body assertions still pass.
- [ ] 5.5 Extend the `AgeGateScreen` Compose render test to assert the optional invite-code field (`age_gate_invite_code_label`) is present alongside the title / DOB / create-account CTA, and that an empty field does not disable the create-account CTA.

## 6. Strings (`:shared:resources`)

- [ ] 6.1 Add Bahasa Indonesia CMP Resource strings: Settings entry ("Undang teman"), referral screen title + explainer + copy-action label + "X dari 5" formatted progress string + reward-unlocked copy, and `age_gate_invite_code_label` ("Kode undangan (opsional)").
- [ ] 6.2 Update `SharedStringsCatalogTest` with the new keys.

## 7. Docs, mockup-gap note, and verification

- [ ] 7.1 File a `follow-up` GitHub issue for the deferred native share-sheet (`docs/12` §3 deferred layer) and reference it in the `mobile-referral` deferred requirement.
- [ ] 7.2 Record the absent referral mockup frame: either add a referral frame to `dev/mockups/` (board + measurement annex per `docs/11` §3.6) OR file a `follow-up` issue documenting the intentional deferral; note that this surface is built from the `mobile-design-system` substrate.
- [ ] 7.3 Run the pre-push gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test`; build the mobile app (`:mobile:app:assembleStagingDebug`) and run the mobile unit tests (`:mobile:app:testStagingDebugUnitTest`).
- [ ] 7.4 Manual verification (docs/11 §5 DoD) — capture evidence in the PR body: referral screen renders the code + copy works + progress shows; Settings entry navigates; signup with a code creates a ticket (or, offline, the request carries `invite_code`). Use the device/emulator or the Nav3 seed harness per the verify-loop.
- [ ] 7.5 `openspec validate mobile-referral-invite-flow --strict` passes.
