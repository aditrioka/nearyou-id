# mobile-home-tab-host — Delta Specification

## ADDED Requirements

### Requirement: The Home brand app bar exposes a Pesan action that opens the conversation list

The Home section's centered brand-logo app bar SHALL carry a single trailing action — a "Pesan" (messages) icon button (a Material icon, `contentDescription` via `stringResource(Res.string.chat_open_action)`) — whose tap pushes `ConversationListRoute` onto the ROOT back stack (overlaying the section shell, the same root-stack mechanism the composer FAB and `PostDetailRoute` use). The callback SHALL be hoisted by the tab host and wired at the `AppShellScreen` / host call site (mirroring the existing `onOpenPost` hoisting), so the host composable itself takes no navigation dependency. This requirement is **additive**: it does NOT alter the existing "Home section shows the centered brand-logo app bar" requirement (the logo + theme behavior is unchanged), the section shell (Home / Notifikasi / Profil), or any feed-tab requirement. The action SHALL be present only on the Home section's app bar (the Notifikasi and Profil sections render no shell top app bar, unchanged).

#### Scenario: Pesan action is present on the Home app bar
- **WHEN** the Home section renders its centered brand-logo app bar
- **THEN** the app bar contains a trailing "Pesan" icon action whose `contentDescription` matches `stringResource(Res.string.chat_open_action)`

#### Scenario: Tapping Pesan pushes the conversation-list route onto the root stack
- **WHEN** the Pesan action is tapped
- **THEN** the hoisted callback is invoked AND `ConversationListRoute` is pushed onto the root back stack (overlaying the bottom-nav section shell), not pushed inside a per-section back stack

#### Scenario: Pesan action is absent on non-Home sections
- **WHEN** the Notifikasi or Profil section is selected
- **THEN** no shell top app bar (and therefore no Pesan action) is rendered for that section (unchanged from the existing non-Home no-app-bar behavior)

#### Scenario: The logo app-bar requirement is untouched
- **WHEN** comparing the Home app bar's brand-logo behavior before and after this change
- **THEN** the centered brand-logo + active-scheme logo behavior is unchanged (this delta only ADDS the trailing Pesan action)
