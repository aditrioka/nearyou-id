# Proposal: mobile-notification-preview-toggle

## Why

`mobile-push-message-handling` (PR #428) shipped the notification content-privacy preference store (default OFF/private) plus the Android render gate and iOS NSE gate that READ it — but deliberately deferred the user-facing Settings control row that flips it, as a `docs/12` §3 explicit deferred requirement, to avoid a `SettingsScreen` squash-merge conflict with the then-in-flight `mobile-data-export-entry` (PR #424). Both PRs have merged; the sequencing blocker is gone. Today a user has no way to opt IN to chat-message previews in notifications (`docs/03-UX-Design.md` § "User Toggle in Settings"), leaving shipped, tested render/NSE behavior permanently stuck at the private default. This change executes follow-up issue [#431](https://github.com/aditrioka/nearyou-id/issues/431).

## What Changes

- Add a Material 3 `Switch` row **"Tampilkan preview pesan chat di notifikasi"** (default OFF) to `SettingsScreen` under the **PRIVASI** group, wired to the existing `NotificationContentPreference.setPreviewEnabled(...)` seam.
- The row is a **local-preference toggle**: no backend endpoint, no Premium gate, available to all tiers. Seeded from `previewEnabled()` on screen open.
- On iOS the write lands in the `group.id.nearyou.shared` App-Group `UserDefaults` suite automatically — the Koin-bound `IosNotificationContentPreferenceStore` already writes there; no additional platform work.
- Flip the `mobile-push-message-handling` deferred-row requirement to reflect the row now shipping, and update the two `mobile-settings` requirements that declare the chat-preview toggle out of scope / enumerate the backed rows.

## Capabilities

### New Capabilities

(none — this is a control surface for an already-specced preference)

### Modified Capabilities

- `mobile-push-message-handling`: the requirement "The Settings preview-toggle control row is deferred as an explicit requirement" flips from deferred to shipped — the row now exists in `mobile-settings`, wired to the preference store this capability owns.
- `mobile-settings`: (a) the "Suspension countdown and notification chat-preview are explicitly out of scope" requirement narrows to suspension-countdown only — the chat-preview toggle is no longer out of scope; (b) the "Backed rows are wired…" requirement gains the new backed PRIVASI row (a non-Premium, local-write `Switch` row with no backend call).

## Impact

- `mobile/app/src/commonMain/.../screens/settings/SettingsScreen.kt` — new PRIVASI `Switch` row.
- `mobile/app/src/commonMain/.../screens/settings/SettingsViewModel.kt` — new seeded `StateFlow` + toggle handler over `NotificationContentPreference` (nullable, fail-safe null pattern like the existing repos).
- `shared/resources` — new string key(s) for the row title (Compose Multiplatform Resources; no hardcoded literals).
- `openspec/specs/mobile-push-message-handling/spec.md` + `openspec/specs/mobile-settings/spec.md` — requirement deltas above.
- Tests: `commonTest` VM coverage (seed + write round-trip), `androidUnitTest` screen coverage (row renders, switch flips, no network write). No backend, no schema, no CI change.
- Closes [#431](https://github.com/aditrioka/nearyou-id/issues/431).
