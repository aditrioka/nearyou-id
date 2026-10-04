## MODIFIED Requirements

### Requirement: mobile-settings owns the SettingsRoute contract and push semantics

The `mobile-settings` capability SHALL own the `SettingsRoute` contract and its push semantics: `SettingsScreen` is a **root**-stack overlay reached by appending `SettingsRoute` onto the root back stack (above `HomeRoute`, overlaying the bottom `NavigationBar`), with the `entry<SettingsRoute>` → `SettingsScreen` mapping owned in `AppEntryProvider`. `SettingsRoute` SHALL be `@Serializable` and registered in the `navSavedStateConfiguration` polymorphic `SerializersModule` (the iOS-saveable back stack requirement). The in-app **entry affordance** that triggers this push SHALL be the settings gear on the **self**-profile section (`mobile-profile`'s `ProfileScreen`, which hoists it as `onSettings`): `AppShellScreen` forwards its `onOpenSettings` callback to the Profil section's `ProfileScreen.onSettings`, and the shell call site in `AppEntryProvider` wires `onOpenSettings` to `backStack.add(SettingsRoute)` — neither `ProfileScreen` nor `AppShellScreen` holds a back-stack reference. The gear SHALL render on the self-profile section only and SHALL NOT render on the other-user profile overlay. (The gear shipped in PR [#312](https://github.com/aditrioka/nearyou-id/pull/312), closing [#288](https://github.com/aditrioka/nearyou-id/issues/288).)

#### Scenario: SettingsRoute is a serializable root-stack route mapped to SettingsScreen

- **GIVEN** the app navigation graph
- **WHEN** `SettingsRoute` is appended onto the root back stack
- **THEN** `AppEntryProvider` maps it to `SettingsScreen` as a root-stack overlay AND `SettingsRoute` round-trips through the polymorphic `SerializersModule` (iOS-saveable)

#### Scenario: The self-profile settings gear pushes SettingsRoute

- **GIVEN** `ProfileScreen` rendered as the self-profile section with a recording `onSettings` callback
- **WHEN** the settings gear (test tag `PROFILE_SETTINGS_TAG`) is tapped
- **THEN** `onSettings` is invoked exactly once

#### Scenario: The shell forwards the gear to onOpenSettings

- **GIVEN** `AppShellScreen` composed with a recording `onOpenSettings` and the Profil section selected
- **WHEN** the self-profile settings gear (test tag `PROFILE_SETTINGS_TAG`) is tapped
- **THEN** `onOpenSettings` is invoked (the `AppEntryProvider` call site wires it to `backStack.add(SettingsRoute)`)

#### Scenario: The settings gear is absent on the other-user profile overlay

- **GIVEN** `ProfileScreen` rendered as an other-user profile overlay (a non-null `targetUserId`)
- **WHEN** the loaded profile renders
- **THEN** no settings-gear node (test tag `PROFILE_SETTINGS_TAG`) exists
