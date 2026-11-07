# Tasks: username-cooldown-entry-and-downgrade-banner

## 1. Backend — self-only `usernameLastChangedAt` (user-profile-read)

- [ ] 1.1 `core/data/.../UserProfileReader.kt`: `UserProfileRow.usernameLastChangedAt: Instant?` (self-only, `null` on other reads); KDoc
- [ ] 1.2 `JdbcUserProfileReader`: `SQL_SELF` selects `u.username_last_changed_at`; `toRow` maps it on the self path only (`SQL_OTHER` never selects it); KDoc
- [ ] 1.3 `UserProfileService` + `UserProfileRoutes.UserProfileResponse`: `usernameLastChangedAt: String?` = `row.usernameLastChangedAt?.toString()`; KDoc
- [ ] 1.4 `UserProfileRoutesTest`: `seedUser(usernameLastChangedAt = …)`; self never-changed → key absent; self changed → ISO string parses back to the seeded instant (seed `truncatedTo(MICROS)`); other-user read of a changed target → key absent

## 2. Mobile data — DTO + domain (mobile-profile)

- [ ] 2.1 `ProfileApiClient.UserProfileResponse.usernameLastChangedAt: String? = null`; `ProfileFlow.UserProfile.usernameLastChangedAt: Instant? = null`; `ProfileRepository.toDomain` parses fail-soft (`runCatching { Instant.parse(it) }.getOrNull()`); KDoc
- [ ] 2.2 `ProfileRepositoryTest`: present → `Instant`, absent → `null`, malformed → `null` (still `Loaded`)

## 3. Mobile — cooldown projection + Settings entry (mobile-premium-username, mobile-settings)

- [ ] 3.1 `UsernameUiState.kt`: `USERNAME_CHANGE_COOLDOWN = 30.days` (mirrors `UsernameChangeService.COOLDOWN`) + pure `usernameCooldownDaysRemaining(lastChangedAt, now): Int?` over `cooldownDays`
- [ ] 3.2 New `screens/settings/SettingsUsernameEntryViewModel.kt`: `UsernameEntryState` (`Open` / `Cooldown(days)` / `Retained(username)`), pure `usernameEntryState(profile, now)`, the VM (nullable `ProfileFlow` / `SelfUserIdProvider`, injected `now`, init read + single-flight `refresh()`, failed read keeps state), ONE `StateFlow`
- [ ] 3.3 `SettingsComponents.SettingsRow`: `enabled: Boolean = true` → `clickable(enabled)`, muted title/icon/chevron (0.38), subtitle unmuted; fix the KDoc's phantom `iconTint`/`enabled` mention
- [ ] 3.4 `SettingsScreen`: resolve `ProfileFlow` / `SelfUserIdProvider` via `getOrNull`, host the VM, forward `ON_RESUME` to `refresh()`; `Retained` → `UsernameRetainedBanner` in the top banner slot (hidden while `premiumActivating`); `Cooldown` → row subtitle `username_cooldown_countdown` + `enabled = false`; refresh the AKUN comment + screen KDoc
- [ ] 3.5 `strings.xml`: `username_retained_banner` ("Username @%1$s tetap milikmu. Untuk mengubahnya lagi, aktifkan Premium."); refresh the `mobile-premium-username` comment block (the proactive countdown + banner now ship)

## 4. Mobile — username gate retained copy (mobile-premium-username)

- [ ] 4.1 `UsernameCustomizationViewModel`: record `customized = profile.usernameLastChangedAt != null` from the on-entry self read
- [ ] 4.2 `UsernameUiState` / `usernameUiState(...)`: carry `usernameCustomized`
- [ ] 4.3 `UsernameCustomizationScreen.PremiumGate`: body = activating ? `premium_activating_body` : customized ? `username_retained_banner(currentUsername)` : `username_premium_gate_body`

## 5. Tests (one per spec'd scenario)

- [ ] 5.1 commonTest `SettingsUsernameEntryViewModelTest`: projection matrix (Premium 16 days → `Cooldown(14)`; 29d 1h → `Cooldown(1)`; exactly 30 d → `Open`; null → `Open`; Free + changed → `Retained`; Free + null → `Open`); VM: `NetworkError` → `Open`, unbound deps → `Open` with no read, missing self id → `Open`, a failed re-read keeps the last state, a resume during the in-flight init read issues no second read, a later resume re-reads into `Cooldown`
- [ ] 5.2 commonTest `UsernameUiStateTest` + `UsernameCustomizationViewModelTest`: `usernameCustomized` carried through the projection; the VM records it from the self read (true / false / read failure → false)
- [ ] 5.3 Robolectric `SettingsScreenTest`: cooldown row disabled + countdown + no push; retained banner + row still pushes; Free never-customized → no banner; banner hidden with a confirmed purchase; `NetworkError` read → default row pushes; the existing unconditional-push test renamed to the outside-cooldown case
- [ ] 5.4 Robolectric `UsernameCustomizationScreenTest`: customized Free self read → retained copy, not the generic body, CTA fires `onActivatePremium`; `UsernameScreenTestFixtures.selfProfile(usernameLastChangedAt = …)`
- [ ] 5.5 iOS: the new commonTest (kotlin.test) suites run on Kotlin/Native via `:mobile:app:iosSimulatorArm64Test` (`Instant.parse` + the projection on Native)

## 6. Docs

- [ ] 6.1 docs/03 § Customization Screen note + § Cooldown Messaging: the proactive entry state ships (#333); "Username on downgrade": the banner renders at the top of Settings and as the username gate body (#335)
- [ ] 6.2 Mockup board frame-16 caption: name the disabled-cooldown row state and the retained banner (no new frame)

## 7. Verification + delivery

- [ ] 7.1 Gates: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` (backend on a throwaway PostGIS container) + `:mobile:app:ktlintCheck :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest :mobile:app:iosSimulatorArm64Test`
- [ ] 7.2 verify-loop: Android emulator + iOS simulator screenshots of the Settings cooldown row, the retained banner, and the retained gate copy (dark + light where cheap) → PR body
- [ ] 7.3 Staging branch deploy + smoke of the self read carrying `usernameLastChangedAt` (docs/11 § 5.4), or record why it is operator-gated
- [ ] 7.4 Sub-agent review (one general lens — no migration, no security-invariant path), apply findings, PR body `Review findings applied:` line
- [ ] 7.5 Archive: sync specs, hand-edit the `mobile-premium-username` Purpose (drop "tracked as follow-ups" for #333/#335), TBD-Purpose grep
