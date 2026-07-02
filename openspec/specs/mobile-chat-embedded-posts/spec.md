# mobile-chat-embedded-posts Specification

## Purpose
The mobile share-a-post-to-chat surface. A "Bagikan ke chat" affordance on post-detail opens a conversation picker (the shipped single-`stateIn` `uiState` state-holder convention) that lists the user's existing conversations and, on a pick, sends a message carrying `embedded_post_id` to the picked conversation — a `403` maps to a distinct blocked result, a successful send navigates to the thread. The chat thread renders an embed message as a self-contained **context card** built from `embedded_post_snapshot` (author handle / display name, content, `cityName` — never a coordinate); tapping a live card navigates to post-detail under the tapping viewer's own live rules; a hard-deleted source post (snapshot present, `embedded_post_id` null) renders a "post telah dihapus" state with no navigation; an admin redaction takes display precedence over the card. An edited-since-shared banner GATE ships (its per-card live-edit source explicitly deferred to a follow-up, so the banner stays dormant until wired). The `:infra:supabase-realtime` inbound model surfaces the embed fields as vendor-SDK-free data; all card / picker strings come from Compose Multiplatform Resources.

## Requirements
### Requirement: Post-detail offers a "Bagikan ke chat" affordance

The post-detail screen SHALL present a "Bagikan ke chat" affordance for a visible post. Activating it SHALL emit a Navigation 3 event to a conversation-picker destination carrying the post id to share. The affordance label SHALL come from Compose Multiplatform Resources (`Res.string.*`), never a hardcoded literal.

#### Scenario: Share action navigates to the picker with the post id
- **GIVEN** the post-detail screen for a visible post P is shown
- **WHEN** the user activates "Bagikan ke chat"
- **THEN** the app navigates to the conversation-picker destination with `postId = P`

### Requirement: The conversation picker lists existing conversations and sends the embed (v1: existing-conversation surface)

The conversation-picker destination SHALL list the user's **existing** conversations (the shipped conversation-list read) and, on a pick, send a message carrying `embedded_post_id = postId` to the picked conversation. Because the picked conversation already exists, the create-or-return path collapses to its "return" arm — its id is the picked row's `conversationId` — and the conversation-list rows are PII-stripped (no recipient UUID, per the conversation-list design), so the picker shares to the conversation id directly rather than re-deriving a recipient id. Starting a brand-new conversation with a never-messaged user from the share surface (the create arm of `createOrReturnConversation(recipientUserId)`) is **out of scope for v1** (it requires a recipient picker that exposes user ids); the picker is the existing-conversation surface. The picker SHALL expose its state through a single-`stateIn` `uiState` ViewModel (the shipped state-holder convention). A `403` on the send SHALL map to a distinct blocked result and any other non-`Sent` outcome to a distinct failed result, with no generic fallthrough; a successful send SHALL navigate to the conversation thread.

#### Scenario: Picking a conversation sends the embed and opens the thread
- **GIVEN** the picker is shown for `postId = P` listing the user's existing conversations
- **WHEN** the user picks conversation C and the embed send succeeds
- **THEN** a send is issued with `embedded_post_id = P` to C AND the app navigates to the thread

#### Scenario: Blocked recipient maps to a blocked result
- **WHEN** the embed send returns `403` for the picked conversation (the recipient blocked the sender, or vice versa)
- **THEN** the picker surfaces a blocked result (not a crash, not a generic error) and no thread navigation occurs

#### Scenario: Empty conversation list
- **GIVEN** the user has no existing conversations
- **WHEN** the picker is shown
- **THEN** it renders the empty state ("Belum ada percakapan untuk dibagikan."), not a recipient picker (the new-conversation surface is deferred)

### Requirement: An embed message renders as a context card from the snapshot

A chat message whose model carries an embedded-post snapshot SHALL render a **context card** built from `embedded_post_snapshot` — showing the author handle/display name, the post content, and the `cityName` — never a raw coordinate (the snapshot carries none). The card SHALL render from the snapshot alone (no live re-fetch to display). All card chrome strings SHALL come from Compose Multiplatform Resources.

#### Scenario: Embed message shows the context card
- **GIVEN** a chat message with a populated `embedded_post_snapshot`
- **WHEN** the thread renders it
- **THEN** a context card shows the snapshot's author, content, and city; no coordinate appears

### Requirement: Tapping the context card navigates to the live post

Tapping a context card whose `embedded_post_id` is non-null SHALL navigate to the post-detail screen for that post id, where the tapping viewer's live visibility rules apply (a blocked author yields the post-detail constant-404 state; a since-deleted post yields the not-found state). The card itself SHALL NOT bypass the viewer's live rules. The recipient-side card is NOT redacted by the recipient's own block of the author — the snapshot is static relayed content the sender chose to send (the same model as pasting quoted text); only live navigation re-applies the tapper's rules, so the card grants no live access to a blocked author's content.

#### Scenario: Tapping a live-post card opens post-detail
- **GIVEN** a context card with a non-null `embedded_post_id = P`
- **WHEN** the user taps it
- **THEN** the app navigates to post-detail for P (which resolves under the viewer's own live rules)

### Requirement: The card shows an edited-since-shared banner when the post moved past the anchor (gate live; live-edit source deferred)

The context card SHALL gate a "diedit sejak dibagikan" banner on a pure comparison of the message's `embedded_post_edit_id` anchor against a live latest-edit signal: when they differ the banner SHALL show; an unedited-then-still-unedited post (both signals absent) SHALL show no banner; and an absent (unknown) live signal SHALL show no banner (never a false positive from missing data). This change SHALL ship and unit-test that gate.

The **per-card live-edit fetch that feeds the gate is explicitly DEFERRED** (a follow-up, [#440](https://github.com/aditrioka/nearyou-id/issues/440)): the card renders from the immutable snapshot (no live re-fetch — see the context-card requirement) and the thread fetches no live post-edit state, and the shipped `GET /api/v1/posts/{id}/edits` exposes `editedAt` timestamps + version indices, not `post_edits.id`, so a faithful anchor-vs-live-edit-id comparison is not yet wireable client-side. Until the follow-up wires a live-edit source, the runtime SHALL pass the message's own anchor as the live signal, so the banner stays dormant (never a false positive). A future change MODIFIES this requirement to wire the live source rather than rediscovering the gap (the project's "capture deferred behaviors as explicit spec requirements" rule; the same treatment as the author-tombstone deferral below).

#### Scenario: Edited-since-shared gate shows the banner when anchor differs from the live signal
- **GIVEN** a context card whose `embedded_post_edit_id` anchor differs from a supplied live latest-edit signal
- **WHEN** the gate is evaluated
- **THEN** the "diedit sejak dibagikan" banner is shown

#### Scenario: Unchanged post (or unknown live signal) shows no banner
- **GIVEN** a context card whose anchor matches the live latest-edit signal (or both are absent, or the live signal is unknown/absent)
- **WHEN** the gate is evaluated
- **THEN** no edited-since-shared banner is shown

#### Scenario: The live-edit source is not wired in this change (deferred negative-guard)
- **GIVEN** the shipped thread renders an embed message whose source post was edited after share time
- **WHEN** the card is shown in the running app (no per-card live-edit fetch is performed)
- **THEN** the runtime passes the message's own anchor as the live signal so the banner stays dormant (no false-positive banner); the live-edit fetch is tracked by follow-up [#440](https://github.com/aditrioka/nearyou-id/issues/440)

### Requirement: A redacted message suppresses the embedded card

When a message has `redacted_at` set, the thread SHALL render the neutral redaction placeholder (the shipped `mobile-chat` "Redacted messages render a neutral placeholder" behavior) and SHALL NOT render the embedded context card, even when an `embedded_post_snapshot` is present. The redaction render SHALL take precedence over the embed render so an admin redaction visually suppresses the shared post, not just accompanying text. (The stored snapshot is not separately scrubbed by this change — display suppression is the moderation contract here; deeper at-rest scrub, if ever needed, is out of scope.)

#### Scenario: Redacted embed message shows the placeholder, not the card
- **GIVEN** an embed message with a populated `embedded_post_snapshot` AND `redacted_at` set
- **WHEN** the thread renders it
- **THEN** it shows the redaction placeholder ("Pesan ini telah dihapus…") AND does NOT render the embedded context card

#### Scenario: Embed-only message (not redacted) still shows the card
- **GIVEN** an embed message with `content = null`, a populated snapshot, AND `redacted_at` null
- **WHEN** the thread renders it
- **THEN** it shows the context card (the redaction-precedence rule keys on `redacted_at`, not on a null `content`)

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

