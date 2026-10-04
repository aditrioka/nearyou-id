## MODIFIED Requirements

### Requirement: Backed rows are wired; deferred rows show a non-writing "Segera hadir" affordance and ship no dead control

Per the operator's mockup-faithful-shell scope decision, `SettingsScreen` SHALL render ALL frame-16 rows, partitioned into **backed** rows (wired to a real destination/action) and **deferred** rows (no backend yet). The backed rows are exactly: AKUN > "Ganti username" (→ `UsernameCustomizationRoute`, pushed **unconditionally** — the route-scoped screen owns the Free/Premium gate, so `SettingsScreen` holds no `isPremium` signal and adds no self-profile read), PRIVASI > "Pengguna diblokir" (→ `BlockedUsersRoute`), PRIVASI > "Privasi & data" (→ `ConsentSettingsRoute`), **PRIVASI > "Sembunyikan jarak" (a Premium-gated toggle wired to `PATCH /api/v1/user/hide-distance`, per the `hide-distance` capability)**, **PRIVASI > "Profil privat" (a Premium-gated toggle wired to `PATCH /api/v1/user/private-profile`, per the `private-profile` capability)**, **PRIVASI > "Tampilkan preview pesan chat di notifikasi" (a local, all-tiers toggle wired to `NotificationContentPreference`, per the `mobile-push-message-handling` capability — no backend call)**, LAINNYA > "Ketentuan & kebijakan privasi" (→ the static legal/privacy URL), and LAINNYA > "Keluar" (logout). The deferred rows are exactly: AKUN > "Edit profil", PREMIUM > "Perjalanan Premium", PREMIUM > "Kelola langganan". A deferred row SHALL render its mockup icon/title/subtitle but its activation SHALL surface a non-trapping "Segera hadir" affordance (a snackbar / inert state via `stringResource(Res.string.settings_coming_soon)`) and SHALL perform **no backend write and no navigation to a non-existent destination**. Each deferred row SHALL be tracked by a follow-up GitHub issue (label `follow-up` + `mobile`).

The "Sembunyikan jarak" row is a Material 3 `Switch` row reflecting the caller's current `hide_distance_opt_in` state, **seeded on screen open via `GET /api/v1/user/hide-distance`** (which also returns whether the caller is effectively Premium). For an **effectively-Premium** caller it is interactive: toggling it issues `PATCH /api/v1/user/hide-distance` with the new value and reflects the persisted result; on write failure (5xx / network) it reverts to the prior state and surfaces a non-trapping error (no optimistic stick). For a **Free** caller it is NOT interactive — it renders the Premium upsell / disabled affordance (mirroring the username-customization Premium-entry pattern) and issues no write. Its title/subtitle SHALL be sourced via `:shared:resources` Compose Multiplatform Resources (no hardcoded literals).

The "Profil privat" row is a Material 3 `Switch` row reflecting the caller's current `private_profile_opt_in` state, **seeded on screen open via `GET /api/v1/user/private-profile`** (which also returns whether the caller is effectively Premium). For an **effectively-Premium** caller it is interactive: toggling it issues `PATCH /api/v1/user/private-profile` with the new value and reflects the persisted result; on write failure (5xx / network) it reverts to the prior state and surfaces a non-trapping error (no optimistic stick). For a **Free** caller it is NOT interactive — it renders the Premium upsell / disabled affordance (mirroring the "Sembunyikan jarak" / username-customization Premium-entry pattern) and issues no write. Because `private_profile_opt_in` is on the `@allow-privacy-write` invariant surface, the row's interactive write SHALL go through the new `private-profile` endpoint ONLY (it issues no other `UPDATE users` path). Its title/subtitle SHALL be sourced via `:shared:resources` Compose Multiplatform Resources (no hardcoded literals).

During the post-purchase webhook-lag window (`mobile-premium-entitlement` § "Upsell surfaces show the activating notice during the webhook-lag window") the screen's seed read can still report a caller who just bought as Free, so "Sembunyikan jarak" / "Profil privat" stay non-interactive. Tapping either then SHALL surface `premium_activating_body` in place of its "Aktifkan Premium untuk…" upsell (`settings_hide_distance_premium_only` / `settings_private_profile_premium_only`), and still issue no write. The rows unlock on the next screen open once the server tier has flipped.

The "Tampilkan preview pesan chat di notifikasi" row (`docs/03` § "User Toggle in Settings") is a Material 3 `Switch` row over the **device-local** notification content-privacy preference — NOT Premium-gated, available to all tiers, and issuing **no backend request** in any state. It is seeded on screen open from `NotificationContentPreference.previewEnabled()` (default OFF/private when never written) and toggling it writes via `NotificationContentPreference.setPreviewEnabled(...)` ONLY — no parallel store — so on iOS the value lands in the `group.id.nearyou.shared` App-Group suite the NSE reads. Its title SHALL be sourced via `:shared:resources` Compose Multiplatform Resources (no hardcoded literals).

#### Scenario: A deferred row surfaces "Segera hadir" and writes nothing

- **GIVEN** `SettingsScreen` composed over a MockEngine that records all outbound requests
- **WHEN** a deferred row (e.g. "Kelola langganan") is activated
- **THEN** the "Segera hadir" affordance (`stringResource(Res.string.settings_coming_soon)`) is shown AND no outbound request is recorded (no write, no navigation to a missing destination)

#### Scenario: Backed rows navigate to their wired destinations

- **GIVEN** `SettingsScreen` composed over a test root back stack
- **WHEN** "Pengguna diblokir" and then "Privasi & data" are activated
- **THEN** `BlockedUsersRoute` and `ConsentSettingsRoute` respectively are appended onto the root back stack

#### Scenario: Premium caller toggling "Sembunyikan jarak" issues the PATCH

- **GIVEN** an effectively-Premium caller and `SettingsScreen` composed over a MockEngine recording requests, with the toggle initially off
- **WHEN** the "Sembunyikan jarak" switch is toggled on AND the server responds success
- **THEN** exactly one `PATCH /api/v1/user/hide-distance` with `{"hideDistance": true}` is recorded AND the switch reflects the on state

#### Scenario: Premium caller toggling "Profil privat" issues the PATCH

- **GIVEN** an effectively-Premium caller and `SettingsScreen` composed over a MockEngine recording requests, with the toggle initially off
- **WHEN** the "Profil privat" switch is toggled on AND the server responds success
- **THEN** exactly one `PATCH /api/v1/user/private-profile` with `{"privateProfile": true}` is recorded AND the switch reflects the on state

#### Scenario: Free caller sees the upsell on "Profil privat" and issues no write

- **GIVEN** a Free caller and `SettingsScreen` composed over a MockEngine recording requests
- **WHEN** the "Profil privat" row is activated
- **THEN** the Premium upsell / disabled affordance is shown AND no `PATCH /api/v1/user/private-profile` request is recorded

#### Scenario: A failed "Profil privat" toggle reverts and surfaces an error

- **GIVEN** an effectively-Premium caller with the "Profil privat" toggle off and a MockEngine returning `PATCH /api/v1/user/private-profile` → `500`
- **WHEN** the switch is toggled on
- **THEN** the switch returns to the off state AND a non-trapping error is surfaced (the toggle does not optimistically stick on a failed write)

#### Scenario: Free caller sees the upsell and issues no write

- **GIVEN** a Free caller and `SettingsScreen` composed over a MockEngine recording requests
- **WHEN** the "Sembunyikan jarak" row is activated
- **THEN** the Premium upsell / disabled affordance is shown AND no `PATCH /api/v1/user/hide-distance` request is recorded

#### Scenario: A failed toggle reverts and surfaces an error

- **GIVEN** an effectively-Premium caller with the toggle off and a MockEngine returning `PATCH /api/v1/user/hide-distance` → `500`
- **WHEN** the switch is toggled on
- **THEN** the switch returns to the off state AND a non-trapping error is surfaced (the toggle does not optimistically stick on a failed write)

#### Scenario: The "Ganti username" row pushes UsernameCustomizationRoute unconditionally

- **GIVEN** `SettingsScreen` composed over a test root back stack
- **WHEN** "Ganti username" is activated
- **THEN** `UsernameCustomizationRoute` is appended onto the root back stack (the route-scoped screen then resolves Premium status and renders the editor or the gate) AND the activation issues no "Segera hadir" affordance — the row is no longer deferred — and Settings reads no `isPremium` signal

#### Scenario: Toggling the chat-preview row persists locally and issues no network request

- **GIVEN** `SettingsScreen` composed over a MockEngine that records all outbound requests, with an in-memory `NotificationContentPreference` seeded unset
- **WHEN** the "Tampilkan preview pesan chat di notifikasi" switch (initially off — the private default) is toggled on
- **THEN** the switch reflects the on state AND `previewEnabled()` on the preference returns `true` AND no outbound request is recorded

#### Scenario: The chat-preview row seeds from the stored preference

- **GIVEN** an in-memory `NotificationContentPreference` whose store holds `true`
- **WHEN** `SettingsScreen` is composed
- **THEN** the "Tampilkan preview pesan chat di notifikasi" switch renders in the on state

#### Scenario: A buyer in the lag window gets the activating notice on the Premium toggles

- **GIVEN** `SettingsScreen` whose hide-distance and private-profile seed reads report `premium = false` AND a Koin graph binding a `PremiumEntitlementSession` on which `onPurchaseConfirmed()` has run
- **WHEN** the caller taps "Sembunyikan jarak", and separately "Profil privat"
- **THEN** each surfaces `premium_activating_body` AND not its `settings_*_premium_only` upsell AND no `PATCH` is recorded
