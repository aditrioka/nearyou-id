## MODIFIED Requirements

### Requirement: Backed rows are wired; deferred rows show a non-writing "Segera hadir" affordance and ship no dead control

Per the operator's mockup-faithful-shell scope decision, `SettingsScreen` SHALL render ALL frame-16 rows, partitioned into **backed** rows (wired to a real destination/action) and **deferred** rows (no backend yet). The backed rows are exactly: AKUN > "Ganti username" (Premium → `UsernameCustomizationRoute`; Free → the hoisted paywall callback, per the row-routing requirement below), PRIVASI > "Pengguna diblokir" (→ `BlockedUsersRoute`), PRIVASI > "Privasi & data" (→ `ConsentSettingsRoute`), LAINNYA > "Ketentuan & kebijakan privasi" (→ the static legal/privacy URL), and LAINNYA > "Keluar" (logout). The deferred rows are exactly: AKUN > "Edit profil", PREMIUM > "Perjalanan Premium", PREMIUM > "Kelola langganan", PRIVASI > "Profil privat", PRIVASI > "Sembunyikan jarak". A deferred row SHALL render its mockup icon/title/subtitle but its activation SHALL surface a non-trapping "Segera hadir" affordance (a snackbar / inert state via `stringResource(Res.string.settings_coming_soon)`) and SHALL perform **no backend write and no navigation to a non-existent destination** — in particular the deferred "Profil privat" and "Sembunyikan jarak" toggles SHALL NOT issue any `UPDATE users` / privacy-flag write (the `@allow-privacy-write` invariant surface is deliberately not entered by this change). Each deferred row SHALL be tracked by a follow-up GitHub issue (label `follow-up` + `mobile`).

#### Scenario: A deferred row surfaces "Segera hadir" and writes nothing

- **GIVEN** `SettingsScreen` composed over a MockEngine that records all outbound requests
- **WHEN** a deferred row (e.g. "Profil privat") is activated
- **THEN** the "Segera hadir" affordance (`stringResource(Res.string.settings_coming_soon)`) is shown AND no outbound request is recorded (no privacy-flag write, no navigation to a missing destination)

#### Scenario: Backed rows navigate to their wired destinations

- **GIVEN** `SettingsScreen` composed over a test root back stack
- **WHEN** "Pengguna diblokir" and then "Privasi & data" are activated
- **THEN** `BlockedUsersRoute` and `ConsentSettingsRoute` respectively are appended onto the root back stack

#### Scenario: The "Ganti username" row routes by Premium status

- **GIVEN** `SettingsScreen` composed over a test root back stack with a recording `onActivatePremium` callback, and the self-Premium hint resolvable
- **WHEN** "Ganti username" is activated by a Premium-hinted user
- **THEN** `UsernameCustomizationRoute` is appended onto the root back stack (and the activation issues no "Segera hadir" affordance — the row is no longer deferred)
- **WHEN** instead "Ganti username" is activated by a Free-hinted user
- **THEN** the hoisted `onActivatePremium` callback fires (the call site pushes `PaywallRoute`) AND no `UsernameCustomizationRoute` is appended
