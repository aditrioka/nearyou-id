# Tasks — `spec-hygiene-shipped-surfaces`

Spec-only change: no Kotlin / SQL / template / resource edits. The "implementation" re-verifies each delta against `origin/main`, amends docs/03, and runs the gate. Purpose fixes land in the archive commit, because delta files can't carry a Purpose.

## 1. Re-verify each delta against shipped code (apply)

- [x] 1.1 `mobile-settings`: gear wiring `AppEntryProvider` `onOpenSettings = { backStack.add(SettingsRoute) }` → `AppShellScreen.onOpenSettings` → `ProfileScreen.onSettings`. Self-only gear (`PROFILE_SETTINGS_TAG`) is asserted present + clickable and absent on the other-user overlay in `ProfileScreenTest`.
- [x] 1.2 `csam-detection`: `/admin/csam` routes (`AdminCsamRoute`: viewer, takedown via `handleDetection(ADMIN_MANUAL)`, kominfo-report, decrypt). `CsamRepository.fileKominfoReport` is the only `kominfo_reported_at` writer. `/internal/csam-webhook` returns `{status}` and `/internal/csam-archive-purge` returns `{purgedCount}`, with no archive content.
- [x] 1.3 `moderation-queue`: readers are only `ReportQueueRepository` / `ReportResolutionRepository` (`/admin/reports`, `/admin/moderation-queue/{id}/resolve`; `/admin/reports/{id}/resolve` updates `reports` only) and `UsernameOversightRepository` (`/admin/username-oversight`, `…/flags/{queue_id}/resolve`). No `/api/v1/*` reader exists.
- [x] 1.4 `admin-panel-scaffold`: `layout.peb` lists 16 `nitem`s in 6 groups with the enumerated paths and `data-icon`s. `AdminPanelScaffoldAuthTest` asserts 16.
- [x] 1.5 `admin-feature-flags`: `FeatureFlagCatalog.EDITABLE` holds `premium_image_upload_cap_override` as `IntRange(1, 10000)`, rendered as `<input type="number">` in `feature-flags-content.peb`. `FeatureFlagCatalogTest` covers `100` / `50` valid and `true` / `0` invalid.
- [x] 1.6 `mobile-post-detail`: `SinglePostApiClient.fetchPost` is the resume freshness read (via `PostEditRepository`); `fetchFullPost` is the deep-link resolution (`NotificationsRepository`).
- [x] 1.7 `mobile-premium-username`: the 409 body is the constant `{"error":"username_unavailable"}` and the client reads no reason. #334 is closed not-planned; #333 / #335 are open; #336 triage plan (dup of #252).
- [x] 1.8 `mobile-profile`: `ProfileViewModel.refresh()` rules (initial-load / in-flight-follow / running-refresh no-op; follow tap cancels; `NetworkError` keeps phase; `Loaded` / `NotFound` replace) and `LifecycleEventEffect(ON_RESUME)` in `ProfileScreen`. Covered by the `refresh …` cases in `ProfileViewModelTest` and the #498 cases in `ProfileScreenTest`.

## 2. docs amendment (apply)

- [x] 2.1 `docs/03-UX-Design.md` § Customization Screen:
  - the reconciliation note: the generic unavailable message is final (anti-probing; #334 closed as not planned), and the #336 autocomplete link is removed;
  - the Unavailable bullet's "distinct per-reason messages: #334" pointer becomes "final; not planned (#334)";
  - the #333 link is unchanged.
- [x] 2.2 `openspec/project.md` § Mobile-First phase-history row 5 (`mobile-settings-screen`): record that the gear shipped in #312 (Settings reachable in-app) and that the account-deletion entry later shipped into Settings. Keep the historical "deferred to #288" fact; drop the stale "not yet reachable in-app". Found during preflight doc reconciliation.

## 3. Validate + gate (apply)

- [x] 3.1 `openspec validate spec-hygiene-shipped-surfaces --strict` passes.
- [x] 3.2 Each RENAMED FROM header byte-matches the current canonical header (checked by the delta build script).
- [ ] 3.3 Pre-push gate (no code changed; run anyway per CLAUDE.md): `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test`.

## 4. Archive: sync + Purpose fixes

- [ ] 4.1 Sync the 8 deltas into `openspec/specs/` (RENAMED first, then MODIFIED by new header). Grep each synced spec: every new header is present, every old FROM header is gone.
- [ ] 4.2 Purpose fix — `mobile-notifications-list`: deep-link tap-through is wired; actor-username rendering and live unread-badge updates stay deferred.
- [ ] 4.3 Purpose fix — `mobile-settings`: describe the shipped backed rows, and say the self-profile gear is the entry (no "#288 deferred").
- [ ] 4.4 Purpose fix — `moderation-queue`: writers for the later triggers landed after V9; the `resolved_by` FK was backfilled by V16; readers are admin-only.
- [ ] 4.5 Purpose fix — `admin-panel-scaffold`: the authenticated frame-2 shell (auth by `admin-login`); drop the nav stub, footer and mount-guard text.
- [ ] 4.6 Purpose fix — `admin-feature-flags`: the full integer list, and the wordlist editor shipped (`admin-moderation-wordlist-editor`), not "deferred".
- [ ] 4.7 Purpose fix — `mobile-post-detail`: the notifications list deep-links here (not "future"); reply rows render author identity but never `author_id`.
- [ ] 4.8 Purpose fix — `mobile-premium-username`: #333 / #335 tracked; generic 409 message final; no autocomplete.
- [ ] 4.9 TBD-Purpose gate: `grep -rn "TBD - created by archiving" openspec/specs/ openspec/changes/` is empty.
- [ ] 4.10 Move the change to `openspec/changes/archive/<date>-spec-hygiene-shipped-surfaces/`, push the archive commit, and refresh the PR body to merge-ready (`Closes #499`; note #336 → duplicate of #252 after merge).
