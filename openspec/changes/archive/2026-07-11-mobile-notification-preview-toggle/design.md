# Design: mobile-notification-preview-toggle

## Context

`NotificationContentPreference` (commonMain, Compose-free) + `PersistedNotificationContentPreference` over a per-platform `NotificationContentPreferenceStore` shipped in `mobile-push-message-handling`: Android actual = DataStore Preferences, iOS actual = the `group.id.nearyou.shared` App-Group `UserDefaults` suite (readable by the NSE). Both are Koin-bound in the platform modules. `SettingsScreen`/`SettingsViewModel` already carry two backed PRIVASI `Switch` rows (hide-distance, private-profile) using the docs/11 §2.2 event-as-state + optimistic-write patterns.

## Goals / Non-Goals

**Goals:**
- One new PRIVASI `Switch` row flipping the local preference; seeded from the store on screen open.
- Spec deltas flipping the deferred requirement in `mobile-push-message-handling` and the out-of-scope declaration in `mobile-settings`.

**Non-Goals:**
- No backend endpoint or server-side persistence (the preference is device-local by design — D4 of `mobile-push-message-handling`).
- No Premium gate (content privacy is a safety/privacy control, not a monetized feature; docs/03 §178 declares no tier).
- No change to the render/NSE read paths (they already honor the preference).

## Decisions

- **D1 — Wire the row through `SettingsViewModel`, not a new VM.** The existing VM already owns the two PRIVASI toggles; a third follows the registered pattern (docs/11 Pattern Registry: one Settings state-holder per NavEntry scope). A new VM for one boolean would be an undeclared second pattern. The dependency is `NotificationContentPreference?` — nullable with a fail-safe null default, matching the existing `hideDistance`/`privateProfile` constructor params, so existing tests and DI gaps degrade to an inert OFF row rather than failing resolution.
- **D2 — No upsell/premium/error event enum for this toggle.** Unlike the two PATCH-backed toggles there is no network write to fail and no Premium gate: the write is a local DataStore/UserDefaults put. The VM applies the value optimistically and issues the suspend write; no revert path is spec'd (a local KV write failing is not an actionable user error). This deliberately does NOT copy the `HideDistanceEvent` shape — copying it would add a dead upsell branch.
- **D3 — Row placement: PRIVASI, after "Sembunyikan jarak", before "Privasi & data".** Keeps the three switches contiguous (the two Premium toggles then this one), navigation rows after — matching the frame-16 grouped-list rhythm. The mockup board frame 16 predates this row (it was deferred); docs/03 § "User Toggle in Settings" is the canonical copy source: title "Tampilkan preview pesan chat di notifikasi", default OFF.
- **D4 — Icon `ic_nav_notifications`, title-only row.** The existing notifications glyph reads correctly; docs/03 specifies no subtitle. New string key `settings_row_chat_preview` in `:shared:resources` (values + values-in per the resource convention).
- **D5 — iOS App-Group mirroring needs no new code.** The Koin binding on iOS is `PersistedNotificationContentPreference(IosNotificationContentPreferenceStore())`; `setPreviewEnabled` therefore already writes the App-Group suite the NSE reads. The spec scenario pins the wiring (the VM MUST write via `NotificationContentPreference`, not a parallel store).

## Risks / Trade-offs

- [Seed read is async → the switch can render OFF for a frame before the seed lands] → same accepted behavior as the two existing seeded toggles; the store read is local and fast (no network).
- [No revert-on-write-failure] → local KV writes on both platforms are effectively infallible; if one throws, the in-memory state and the store disagree until next screen open. Accepted (D2) — an error affordance for an unreachable failure would be dead UI.
- [Frame-16 mockup does not show this row] → docs/03 §178 is the spec source; specs/docs win over mockups on behavior conflicts (CLAUDE.md). The row reuses the existing `SettingsRow` chrome, so no new visual vocabulary is introduced.

## Migration Plan

Pure additive mobile UI + spec delta; ships in one PR (`Closes #431`). No schema, no backend, no rollback concern beyond a normal revert.

## Open Questions

None.
