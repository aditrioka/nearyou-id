# mobile-push-message-handling — delta for mobile-notification-preview-toggle

## RENAMED Requirements

- FROM: `### Requirement: The Settings preview-toggle control row is deferred as an explicit requirement`
- TO: `### Requirement: The Settings preview-toggle control row ships in mobile-settings`

## MODIFIED Requirements

### Requirement: The Settings preview-toggle control row ships in mobile-settings

The user-facing Settings control row "Tampilkan preview pesan chat di notifikasi" (`docs/03` §178) that flips the content-privacy preference SHALL exist in `mobile-settings` — shipped by the `mobile-notification-preview-toggle` change (executing follow-up [#431](https://github.com/aditrioka/nearyou-id/issues/431)) once its sequencing blocker (`mobile-data-export-entry`, PR #424) merged. The original `mobile-push-message-handling` change shipped only the preference STORE and the render/NSE gate, functional at the private default (a docs/12 §3 explicit deferral that left no unsafe gap because the default behavior is the content-private form). The row SHALL write via `NotificationContentPreference.setPreviewEnabled(...)` ONLY — no parallel store — so on iOS the value lands in the `group.id.nearyou.shared` App-Group `UserDefaults` suite the NSE reads.

#### Scenario: The Settings row flips the preference the render and NSE gates read

- **WHEN** the "Tampilkan preview pesan chat di notifikasi" Settings row is toggled on
- **THEN** the value is persisted through `NotificationContentPreference` (on iOS, the App-Group store) AND a subsequent `previewEnabled()` read by the Android render path or the iOS NSE returns `true`
