## Why

`mobile-premium-username` (PR [#322](https://github.com/aditrioka/nearyou-id/pull/322)) deferred two docs/03 states. Both lack the same signal: the client cannot see when the caller last changed their username.

- **[#333](https://github.com/aditrioka/nearyou-id/issues/333): proactive cooldown at the entry point.** docs/03 § Cooldown Messaging specifies a disabled "Ganti username" entry with "Ganti username berikutnya tersedia dalam {n} hari." shown before any attempt. Today a Premium user in the 30-day cooldown taps the row, types, can spend some of the 3/day probe budget, confirms, and only then meets the reactive `429 cooldown_active`.
- **[#335](https://github.com/aditrioka/nearyou-id/issues/335): the downgrade banner.** docs/03 § Paywall & Premium Disclosure ("Username on downgrade") specifies an in-app banner, "Username @{username} tetap milikmu. Untuk mengubahnya lagi, aktifkan Premium." A Premium→Free user who customized their handle instead sees the never-had-it pitch, "Ganti username adalah fitur Premium."

`users.username_last_changed_at` already holds the signal. Only the `PATCH /api/v1/user/username` transaction writes it (`JdbcUserRepository`, `NOW()`). Signup never sets it (`V2__auth_foundation.sql`, nullable), so a non-null value means "this user customized their handle". The self-profile read just does not expose it.

## What Changes

- **Backend (`user-profile-read`).** `GET /api/v1/users/{user_id}` gains the self-only `usernameLastChangedAt` (ISO-8601 instant string). It is set only when `isSelf = true` and the user has changed their handle. It is omitted (`explicitNulls = false`) on every other-user read and for a never-changed user, the same way `isPrivate` is self-only. The self SQL reads `u.username_last_changed_at`. This is a read; the username write allowlist is untouched.
- **Mobile data (`mobile-profile`).** `UserProfileResponse.usernameLastChangedAt: String? = null`. `UserProfile.usernameLastChangedAt: Instant?` is parsed fail-soft (an unparseable value reads as `null`).
- **Settings entry (#333 + #335, `mobile-settings` + `mobile-premium-username`).** A new NavEntry-scoped `SettingsUsernameEntryViewModel` does a self-profile read. It does so on entry and again on every `ON_RESUME`, the docs/11 § 2.3 re-read idiom, so a return from a username change or a purchase shows fresh state. It projects one `UsernameEntryState`:
  - `Cooldown(days)`: Premium, with less than 30 days since the last change. The "Ganti username" row's subtitle becomes `username_cooldown_countdown`, and the row is **disabled**. It pushes nothing.
  - `Retained(username)`: not Premium, and the user has customized before. A non-blocking banner at the top of Settings shows `username_retained_banner`. It is hidden during the post-purchase webhook-lag window. The row still pushes the route.
  - `Open`: anything else, including a failed or unbound read. The row is unchanged and pushes the route.
- **Username screen gate (#335, `mobile-premium-username`).** The route's on-entry self read also records whether the user has customized. The `PremiumGate` panel then shows `username_retained_banner` with the current handle in place of `username_premium_gate_body`. The activating notice still wins during the lag window.
- **The deferral requirement is retired.** Items (a) and (c) ship. The requirement keeps only the two "not planned" items (b) and (d).
- **`SettingsRow` gains `enabled`.** A disabled row is not clickable, and its title, icon and chevron are muted. Its subtitle stays at full contrast, because it carries the countdown. This also fixes the KDoc, which named `iconTint`/`enabled` parameters that did not exist.
- **One new string:** `username_retained_banner`. The countdown reuses `username_cooldown_countdown`.
- **Docs:**
  - docs/03 § Cooldown Messaging and § Customization Screen note: the proactive state ships.
  - docs/03 "Username on downgrade": where the banner renders.
  - The mockup board frame-16 caption names the two states.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `user-profile-read`: the response shape gains the self-only `usernameLastChangedAt`. ADD a requirement that it is self-only and reflects `username_last_changed_at`.
- `mobile-profile`: the `UserProfileResponse` DTO field list gains `usernameLastChangedAt: String? = null`.
- `mobile-premium-username`:
  - the Premium gate shows the retained copy for a customized user;
  - the "Settings row pushes the route unconditionally" requirement becomes "unless the proactive cooldown disables it";
  - ADD the Settings entry-state requirement;
  - the deferral requirement keeps only the not-planned items.
- `mobile-settings`: the "Ganti username" backed row is no longer unconditional. Settings now does a self-profile read for the username entry state only.

## Impact

- **Layers:** backend + mobile (`:mobile:app` + `:shared:resources`).
  - **Admin: none.** The field is the caller's own state. The operator already sees username changes on the Premium Username Change Oversight surface (admin board frame 22), so there is no operator dimension to add.
  - No migration, no new route, no new dependency.
- **Code:**
  - Backend: `core/data/.../UserProfileReader.kt` (`UserProfileRow`), `backend/ktor/.../user/{JdbcUserProfileReader,UserProfileService,UserProfileRoutes}.kt`, and `UserProfileRoutesTest.kt`.
  - Mobile: `profile/{ProfileApiClient,ProfileFlow,ProfileRepository}.kt`, new `screens/settings/SettingsUsernameEntryViewModel.kt`, `screens/settings/{SettingsScreen,SettingsComponents}.kt`, `screens/username/{UsernameUiState,UsernameCustomizationViewModel,UsernameCustomizationScreen}.kt`, and `strings.xml`.
  - Tests: `ProfileRepositoryTest`, new `SettingsUsernameEntryViewModelTest`, `UsernameUiStateTest`, `UsernameCustomizationViewModelTest`, `UsernameCustomizationScreenTest`, `SettingsScreenTest`, and the shared username fixtures.
- **Wire:** additive and optional. An older client ignores the key (`ignoreUnknownKeys`), and a newer client tolerates its absence. Staging deploy + smoke are needed before archive (runtime-impacting backend change, docs/11 § 5.4).
- **Coordination:** no open PR touches these files (surveyed 2026-10-19). Wave 4 siblings add strings to `strings.xml`. Whoever merges second rebases.
