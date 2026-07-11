# Tasks: mobile-notification-preview-toggle

## 1. Strings

- [x] 1.1 Add `settings_row_chat_preview` = "Tampilkan preview pesan chat di notifikasi" to `:shared:resources` (the base `values/` locale is Indonesian — no `values-in/` exists; matches the existing settings-row key convention)

## 2. ViewModel

- [x] 2.1 Add nullable `NotificationContentPreference` constructor param to `SettingsViewModel` (fail-safe null default, matching the existing repo params), a `chatPreviewChecked: StateFlow<Boolean>` seeded from `previewEnabled()` in `init`, and `onChatPreviewToggle(requested: Boolean)` that sets the state and writes `setPreviewEnabled(requested)` (no event enum, no revert — design D2)
- [x] 2.2 commonTest coverage in the existing Settings VM test style: seed reflects a store holding `true`; toggle updates the flow AND round-trips through the preference; a null preference leaves the row inert OFF

## 3. Screen

- [x] 3.1 Wire `NotificationContentPreference` into the `SettingsScreen` VM construction via the fail-safe `getKoin().getOrNull()` idiom; render the new PRIVASI `Switch` row (icon `ic_nav_notifications`, title via `Res.string.settings_row_chat_preview`) after "Sembunyikan jarak", before "Privasi & data" (design D3/D4)
- [x] 3.2 androidUnitTest screen coverage: the row renders in PRIVASI; toggling flips the switch, persists via an in-memory preference, and records no outbound request; seeded-true renders on (the two delta-spec scenarios)

## 4. Spec sync + verification

- [x] 4.1 Update the `SettingsScreen`/`NotificationContentPreference` KDoc blocks that still describe the row as deferred (#431)
- [x] 4.2 Run the pre-push gate (`ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test`) + `:mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest`; UI-affecting → manual verify (verify-loop §B) with screenshot evidence in the PR body
