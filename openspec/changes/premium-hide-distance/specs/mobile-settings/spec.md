## MODIFIED Requirements

### Requirement: Backed rows are wired; deferred rows show a non-writing "Segera hadir" affordance and ship no dead control

Per the operator's mockup-faithful-shell scope decision, `SettingsScreen` SHALL render ALL frame-16 rows, partitioned into **backed** rows (wired to a real destination/action) and **deferred** rows (no backend yet). The backed rows are exactly: PRIVASI > "Pengguna diblokir" (→ `BlockedUsersRoute`), PRIVASI > "Privasi & data" (→ `ConsentSettingsRoute`), **PRIVASI > "Sembunyikan jarak" (a Premium-gated toggle wired to `PATCH /api/v1/user/hide-distance`, per the `hide-distance` capability)**, LAINNYA > "Ketentuan & kebijakan privasi" (→ the static legal/privacy URL), and LAINNYA > "Keluar" (logout). The deferred rows are exactly: AKUN > "Edit profil", AKUN > "Ganti username", PREMIUM > "Perjalanan Premium", PREMIUM > "Kelola langganan", PRIVASI > "Profil privat". A deferred row SHALL render its mockup icon/title/subtitle but its activation SHALL surface a non-trapping "Segera hadir" affordance (a snackbar / inert state via `stringResource(Res.string.settings_coming_soon)`) and SHALL perform **no backend write and no navigation to a non-existent destination** — in particular the deferred "Profil privat" toggle SHALL NOT issue any `UPDATE users` / privacy-flag write (the `@allow-privacy-write` invariant surface is deliberately not entered by this change). Each deferred row SHALL be tracked by a follow-up GitHub issue (label `follow-up` + `mobile`).

The "Sembunyikan jarak" row is a Material 3 `Switch` row reflecting the caller's current `hide_distance_opt_in` state, **seeded on screen open via `GET /api/v1/user/hide-distance`** (which also returns whether the caller is effectively Premium). For an **effectively-Premium** caller it is interactive: toggling it issues `PATCH /api/v1/user/hide-distance` with the new value and reflects the persisted result; on write failure (5xx / network) it reverts to the prior state and surfaces a non-trapping error (no optimistic stick). For a **Free** caller it is NOT interactive — it renders the Premium upsell / disabled affordance (mirroring the username-customization Premium-entry pattern) and issues no write. Its title/subtitle SHALL be sourced via `:shared:resources` Compose Multiplatform Resources (no hardcoded literals).

#### Scenario: A deferred row surfaces "Segera hadir" and writes nothing

- **GIVEN** `SettingsScreen` composed over a MockEngine that records all outbound requests
- **WHEN** a deferred row (e.g. "Profil privat") is activated
- **THEN** the "Segera hadir" affordance (`stringResource(Res.string.settings_coming_soon)`) is shown AND no outbound request is recorded (no privacy-flag write, no navigation to a missing destination)

#### Scenario: Backed rows navigate to their wired destinations

- **GIVEN** `SettingsScreen` composed over a test root back stack
- **WHEN** "Pengguna diblokir" and then "Privasi & data" are activated
- **THEN** `BlockedUsersRoute` and `ConsentSettingsRoute` respectively are appended onto the root back stack

#### Scenario: Premium caller toggling "Sembunyikan jarak" issues the PATCH

- **GIVEN** an effectively-Premium caller and `SettingsScreen` composed over a MockEngine recording requests, with the toggle initially off
- **WHEN** the "Sembunyikan jarak" switch is toggled on AND the server responds success
- **THEN** exactly one `PATCH /api/v1/user/hide-distance` with `{"hideDistance": true}` is recorded AND the switch reflects the on state

#### Scenario: Free caller sees the upsell and issues no write

- **GIVEN** a Free caller and `SettingsScreen` composed over a MockEngine recording requests
- **WHEN** the "Sembunyikan jarak" row is activated
- **THEN** the Premium upsell / disabled affordance is shown AND no `PATCH /api/v1/user/hide-distance` request is recorded

#### Scenario: A failed toggle reverts and surfaces an error

- **GIVEN** an effectively-Premium caller with the toggle off and a MockEngine returning `PATCH /api/v1/user/hide-distance` → `500`
- **WHEN** the switch is toggled on
- **THEN** the switch returns to the off state AND a non-trapping error is surfaced (the toggle does not optimistically stick on a failed write)
