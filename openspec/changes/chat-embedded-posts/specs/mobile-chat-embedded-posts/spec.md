## ADDED Requirements

### Requirement: Post-detail offers a "Bagikan ke chat" affordance

The post-detail screen SHALL present a "Bagikan ke chat" affordance for a visible post. Activating it SHALL emit a Navigation 3 event to a conversation-picker destination carrying the post id to share. The affordance label SHALL come from Compose Multiplatform Resources (`Res.string.*`), never a hardcoded literal.

#### Scenario: Share action navigates to the picker with the post id
- **GIVEN** the post-detail screen for a visible post P is shown
- **WHEN** the user activates "Bagikan ke chat"
- **THEN** the app navigates to the conversation-picker destination with `postId = P`

### Requirement: The conversation picker selects-or-creates a 1:1 and sends the embed

The conversation-picker destination SHALL let the user choose a recipient, reuse the shipped `createOrReturnConversation(recipientUserId)` create-or-return path to obtain the conversation id, then send a message carrying `embedded_post_id = postId`. The picker SHALL expose its state through a single-`stateIn` `uiState` ViewModel (the shipped state-holder convention). A `403`/`400`/`404` from create-or-return SHALL map to distinct user-facing results (blocked / self / recipient-not-found), with no generic fallthrough; a successful send SHALL navigate to the conversation thread.

#### Scenario: Picking a recipient sends the embed and opens the thread
- **GIVEN** the picker is shown for `postId = P`
- **WHEN** the user picks recipient R and the create-or-return succeeds
- **THEN** a send is issued with `embedded_post_id = P` to that conversation AND the app navigates to the thread

#### Scenario: Blocked recipient maps to a blocked result
- **WHEN** create-or-return (or the send) returns `403` for recipient R
- **THEN** the picker surfaces a blocked result (not a crash, not a generic error) and no thread navigation occurs

### Requirement: An embed message renders as a context card from the snapshot

A chat message whose model carries an embedded-post snapshot SHALL render a **context card** built from `embedded_post_snapshot` — showing the author handle/display name, the post content, and the `cityName` — never a raw coordinate (the snapshot carries none). The card SHALL render from the snapshot alone (no live re-fetch to display). All card chrome strings SHALL come from Compose Multiplatform Resources.

#### Scenario: Embed message shows the context card
- **GIVEN** a chat message with a populated `embedded_post_snapshot`
- **WHEN** the thread renders it
- **THEN** a context card shows the snapshot's author, content, and city; no coordinate appears

### Requirement: Tapping the context card navigates to the live post

Tapping a context card whose `embedded_post_id` is non-null SHALL navigate to the post-detail screen for that post id, where the tapping viewer's live visibility rules apply (a blocked author yields the post-detail constant-404 state; a since-deleted post yields the not-found state). The card itself SHALL NOT bypass the viewer's live rules.

#### Scenario: Tapping a live-post card opens post-detail
- **GIVEN** a context card with a non-null `embedded_post_id = P`
- **WHEN** the user taps it
- **THEN** the app navigates to post-detail for P (which resolves under the viewer's own live rules)

### Requirement: The card shows an edited-since-shared banner when the post moved past the anchor

When the live post's most-recent edit differs from the message's `embedded_post_edit_id` anchor, the context card SHALL show a "diedit sejak dibagikan" banner indicating the post changed after it was shared. The comparison SHALL use the message's anchor versus the live post's current latest-edit signal; an unedited-then-still-unedited post SHALL show no banner.

#### Scenario: Post edited after sharing shows the banner
- **GIVEN** a context card whose `embedded_post_edit_id` anchor differs from the live post's current latest edit
- **WHEN** the card is shown with the live post's current edit state
- **THEN** the "diedit sejak dibagikan" banner is shown

#### Scenario: Unchanged post shows no banner
- **GIVEN** a context card whose anchor matches the live post's current latest edit (or both are unedited)
- **WHEN** the card is shown
- **THEN** no edited-since-shared banner is shown

### Requirement: A hard-deleted source post renders a deleted-state card with no navigation

A context card whose `embedded_post_snapshot` is present but whose `embedded_post_id` is null (the source post was hard-deleted, FK set to NULL) SHALL render the snapshot in a "post telah dihapus" state and SHALL NOT be tappable for navigation.

#### Scenario: Deleted source post renders the deleted state
- **GIVEN** a context card with a non-null `embedded_post_snapshot` and a null `embedded_post_id`
- **WHEN** the thread renders it
- **THEN** the card shows the snapshot in a "post telah dihapus" state and tapping does not navigate

### Requirement: Context-card strings come from Compose Multiplatform Resources

Every user-facing string introduced by this capability — the "Bagikan ke chat" action, the "diedit sejak dibagikan" banner, the "post telah dihapus" state, and the picker labels — SHALL be sourced from `Res.string.*` (the no-hardcoded-UI-strings invariant). No string literal SHALL be rendered directly in a composable.

#### Scenario: Embedded-post strings resolve from resources
- **WHEN** the embedded-post UI renders any of its labels
- **THEN** each label resolves from a `Res.string.*` key (no hardcoded literal in the composable)
