# Tasks: mobile-settings-screen

## 1. Strings + navigation scaffold

- [x] 1.1 Add the `:shared:resources` strings (no hardcoded UI literals): `settings_title` ("Pengaturan"), the four section headers (`settings_section_account` "AKUN", `settings_section_premium` "PREMIUM", `settings_section_privacy` "PRIVASI", `settings_section_other` "LAINNYA"), every row title/subtitle per frame 16, `settings_coming_soon` ("Segera hadir"), `blocked_users_title` ("Pengguna diblokir"), `blocked_users_empty` ("Belum ada pengguna yang diblokir"), the block-list/consent error copy, the unblock affordance label, the consent settings title + per-toggle labels (reuse the `mobile-analytics-consent` strings where they already exist), the logout confirmation (title/body/confirm/cancel), the handle format string, and the settings-gear + back + row `contentDescription`s
- [x] 1.2 Add `SettingsRoute`, `BlockedUsersRoute`, `ConsentSettingsRoute` (`@Serializable data object`) to `screens/routing/NavKeys.kt` and register all three in the `AppNavSerialization` polymorphic `SerializersModule`
- [x] 1.3 commonTest: each new NavKey round-trips through `AppNavSerialization` (serialize → deserialize → equal); each carries no identity payload field

## 2. Block-list data seam (ApiClient → Repository → Outcome)

- [x] 2.1 Create `BlockListResponse { blocks: List<BlockListItem>, nextCursor: String? }` + `BlockListItem { userId: String, username: String, displayName: String, isPremium: Boolean, createdAt: String }` (bare camelCase, `@Serializable`, parsed with `ignoreUnknownKeys`) — matches the shipped `BlockRoutes.kt` wire field-for-field
- [x] 2.2 Create `data/block/BlockedUsersApiClient` (docs/11 § 2.1 target shape, matching the migrated `data/like/` peer — design D9) on the shared `Auth { bearer }` `HttpClient`: `GET /api/v1/blocks` (+ optional `cursor` param) and `DELETE /api/v1/blocks/{userId}`
- [x] 2.3 Create `data/block/BlockedUsersRepository` mapping DTO → domain `BlockedUser` (display name + handle + the `userId` held for the DELETE path only) and exposing a sealed `BlockedUsersOutcome` (success(page) / terminal-401 / retryable-error); the seam SHALL log no blocked-user `userId`/`username`/`displayName`
- [x] 2.4 commonTest: `BlockListResponse` parses the canonical camelCase JSON + tolerates an unknown extra key; a `500` maps to retryable-error; a `401` maps to terminal-401; the domain model carries no rendered UUID field (the `userId` is path-param-only)

## 3. Block-list screen

- [x] 3.1 Create `screens/settings/BlockedUsersScreen.kt` (own Scaffold + back bar) + `BlockedUsersViewModel` (`viewModel { }` scoped to `BlockedUsersRoute`): loads `GET /api/v1/blocks`; renders the loading / empty (`blocked_users_empty`) / error / list states per the `mobile-design-system` state contract; each row shows display name + @handle + an unblock affordance (NO UUID, NO coordinate); thread `nextCursor` for load-more (first-page-only shipped; pagination deferred → #265)
- [x] 3.2 Wire unblock: activating a row's unblock issues `DELETE /api/v1/blocks/{userId}` and, on success, removes the row from the list; on a `DELETE` failure (5xx / network) the row remains (or is restored) and a non-trapping error surfaces — NO silent optimistic drop; a `401` on read or unblock emits a sign-in redirect
- [x] 3.3 Robolectric `BlockedUsersScreenTest` (androidUnitTest; add to the Release-variant test-exclude list): list renders display name + handle and NO UUID node; unblock records exactly the right `DELETE` path and removes the row; **unblock `DELETE` `500` keeps the row + surfaces an error (no removal, no redirect)**; empty state shows `blocked_users_empty`; `401` routes to sign-in; `500` on the read renders the error state (no crash, no redirect); light + dark render
- [x] 3.4 androidUnitTest source-scan guard: no `data/block/**` nor `screens/settings/**` block source logs a blocked user's `userId`/`username`/`displayName`

## 4. Consent settings sub-screen (reuse the existing consent seam)

- [x] 4.1 Create `screens/settings/ConsentSettingsScreen.kt` + its `viewModel { }` (scoped to `ConsentSettingsRoute`) REUSING `consent/ConsentApiClient` + `ConsentFlow` + `ConsentOutcome` (mobile-analytics-consent) — three toggles, submit via `PATCH /api/v1/user/consent`; status-driven outcome mapping (200 → persist + confirm; 401 → sign-in; 5xx/IO/400 → retryable); double-tap-guard (exactly one PATCH)
- [x] 4.2 Add the device-local consent snapshot: persist the triple **echoed in the `PATCH` `200` body** (`ConsentResponse` — the server's authoritative acknowledgement, not a client guess) on each success, via the app's existing on-device key-value store (NO new library pin — reuse the storage family backing `SecureTokenStore`); initialize the toggles from the snapshot, falling back to the V2 defaults (analytics OFF, crash ON, ads OFF) when absent; injectable initial state for testability
- [x] 4.3 File the follow-up GitHub issue (label `follow-up` + `mobile`) for a dedicated server consent-READ endpoint (multi-device robustness) + durable cross-session persistence hardening (related to issue #198) — filed #266; the issue notes the `PATCH` `200` already round-trips the authoritative triple, so the GET is a robustness nicety, not a single-device correctness gap
- [x] 4.4 commonTest: no-snapshot → V2 default toggles + no consent-read request issued; snapshot present → toggles seed from it; a successful submit updates the snapshot from the `200` echo; **a snapshot written by one holder seeds a freshly reconstructed holder from the SAME store (re-instantiation, not the same in-memory instance)**; submit issues the canonical PATCH body `{"analytics":..,"crash":..,"ads_personalization":..}`; double-tap → exactly one PATCH; a `401` on submit routes to sign-in (terminal, not retryable)
- [x] 4.5 androidUnitTest source-scan guard: no `screens/settings/**` (consent) nor reused `consent/**` source logs the token, `Authorization`, JWT `sub`, or the PATCH request/response body
- [x] 4.6 Robolectric `ConsentSettingsScreenTest` (Release-excluded): three toggles render; toggling + save reflects the submitted values; retryable (`5xx`) error shows the in-screen error; `401` routes to sign-in (terminal); light + dark render

## 5. Settings screen (grouped frame-16 list)

- [x] 5.1 Create `screens/settings/SettingsScreen.kt` (own Scaffold + "Pengaturan" app bar + `arrow_back`) rendering the four section headers + all frame-16 rows (Material leading icon + title + optional subtitle + trailing chevron / M3 `Switch`), theme tokens only, all strings via `Res.string.*`
- [x] 5.2 Wire the backed rows: "Pengguna diblokir" → push `BlockedUsersRoute`; "Privasi & data" → push `ConsentSettingsRoute`; "Ketentuan & kebijakan privasi" → open the static policy URL (non-secret constant) via the platform external-link mechanism; "Keluar" → logout flow (task 5.4)
- [x] 5.3 Render the deferred rows (Edit profil, Ganti username, Perjalanan Premium, Kelola langganan, Profil privat, Sembunyikan jarak) with their mockup chrome; activation surfaces a non-trapping `settings_coming_soon` affordance and performs NO backend write and NO navigation — in particular the "Profil privat" / "Sembunyikan jarak" toggles issue NO `UPDATE users` / privacy-flag write (stay off the `@allow-privacy-write` invariant surface)
- [x] 5.4 Logout: confirmation dialog → wipe `SecureTokenStore` → emit `replaceAll` to sign-in (no server call); cancel leaves the store + back stack intact. Hold the logout/settings state in a `viewModel { }` scoped to `SettingsRoute`
- [x] 5.5 File the per-deferred-row follow-up GitHub issues (label `follow-up` + `mobile`): edit-profile, Premium username change, Premium tenure journey, manage-subscription, Premium private-profile toggle, Premium hide-distance toggle (grouped; cross-references Phase 4 / DESIGN status) — filed #267
- [x] 5.7 File a `follow-up` issue (label `follow-up` + `backend`) for **server-side bearer revoke on logout** (so a wiped-but-exfiltrated token can't be reused until natural expiry) — pre-launch hardening, out of this MVP UI change (design D6) — filed #268
- [x] 5.6 Robolectric `SettingsScreenTest` (Release-excluded): the four section headers + the app bar render; activating "Pengguna diblokir"/"Privasi & data" pushes the right routes; a deferred row shows `settings_coming_soon` and records NO outbound request (MockEngine asserts zero requests); confirming logout clears `SecureTokenStore` + emits the sign-in `replaceAll`, cancel does neither; NO "Hapus Akun"/"Unduh Data Saya"/suspension/chat-preview node is present; light + dark render

## 6. Entry gear on the profile surface (sequenced after PR #245)

- [ ] 6.1 **BLOCKED on PR #245** (profile surface not merged) — after `mobile-profile` (PR #245) merges, add a settings `gear` icon to the self-profile surface whose tap pushes `SettingsRoute` onto the root back stack (wired at the `AppEntryProvider` / profile call-site, consistent with the existing root-stack push wiring); `contentDescription` via `stringResource`
- [ ] 6.2 **BLOCKED on PR #245** — test the gear → `SettingsRoute` push (recording callback or test root back stack); assert the `contentDescription` is a non-empty `stringResource`

## 7. DI + iOS + verification gates

- [x] 7.1 Register the settings bindings in the Koin module (`BlockedUsersApiClient`/`Repository`, the consent-snapshot store, the three view models); add a Koin-resolution test asserting all settings view models resolve with dependencies satisfied
- [x] 7.2 iOS flow test under `mobile/app/src/iosTest/...` (mirroring `NearbyTimelineFlowIosTest`) exercising the settings surface (open settings → block list → consent → back) with Kotlin/Native-legal test function names
- [x] 7.3 Mobile gate: `:mobile:app:testDevDebugUnitTest` + `:mobile:app:testDevReleaseUnitTest` GREEN locally (new `*ScreenTest`s in the Release exclude). `:mobile:app:iosSimulatorArm64Test` runs in CI/macOS — not buildable on the Linux cloud sandbox (the iOS flow test compiles there)
- [x] 7.4 Lint gate: `ktlintCheck` + `detekt` GREEN locally (no `@allow-privacy-write` annotation needed — this change performs no privacy-flag write). `:lint:detekt-rules:test` + `:backend:ktor:test` N/A — no backend/rule change
- [ ] 7.5 **BLOCKED on PR #245** (settings is unreachable until the profile gear lands) — manual verification per docs/11 §5 DoD #3 (`verify-loop`): Android emulator AND iOS simulator — screenshot the Settings list (light + dark) against mockup frame 16, the block-list list+unblock round-trip, the consent submit, and the logout flow; attach evidence to the PR body; `mobile-ui-foundation` checklist pass
- [x] 7.6 Confirm no Flyway migration, no backend file, and no `gradle/libs.versions.toml` change landed (mobile-only invariant for this change)

## 8. Archive-time docs reconciliation

- [ ] 8.1 Trim `openspec/project.md` § Mobile-First to Full-Demo Priority live-menu **row #5** to the shipped settings scope: drop "account-deletion entry, suspension-countdown UI" (deferred as `follow-up` issues, not in this screen — design D8) so the live menu reflects what `mobile-settings` actually delivers
- [ ] 8.2 Mark the mobile critical-path menu as complete (all of #1–#5 shipped) and re-evaluate the flip trigger out of mobile-first priority per project.md § "Trigger to flip out of mobile-first priority" (settings is the last row; surface to the operator whether the authenticated core loop is now demoable end-to-end)
