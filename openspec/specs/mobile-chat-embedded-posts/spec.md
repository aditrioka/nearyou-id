# mobile-chat-embedded-posts Specification

## Purpose
The mobile share-a-post-to-chat surface. A "Bagikan ke chat" affordance on post-detail opens a conversation picker (the shipped single-`stateIn` `uiState` state-holder convention) that lists the user's existing conversations and, on a pick, sends a message carrying `embedded_post_id` to the picked conversation — a `403` maps to a distinct blocked result, a successful send navigates to the thread. The chat thread renders an embed message as a self-contained **context card** built from `embedded_post_snapshot` (author handle / display name, content, `cityName` — never a coordinate); tapping a live card navigates to post-detail under the tapping viewer's own live rules; a hard-deleted source post (snapshot present, `embedded_post_id` null) renders a "post telah dihapus" state with no navigation; an admin redaction takes display precedence over the card. A "diedit sejak dibagikan" banner shows when the live post was edited after the share: the thread reads each shared post's `editedAt` once per visit through `single-post-read` and compares it with the snapshot's share-time `editedAt` (unknown live state shows no banner). The `:infra:supabase-realtime` inbound model surfaces the embed fields as vendor-SDK-free data; all card / picker strings come from Compose Multiplatform Resources.
## Requirements
### Requirement: Post-detail offers a "Bagikan ke chat" affordance

The post-detail screen SHALL present a "Bagikan ke chat" affordance for a visible post. Activating it SHALL emit a Navigation 3 event to a conversation-picker destination carrying the post id to share. The affordance label SHALL come from Compose Multiplatform Resources (`Res.string.*`), never a hardcoded literal.

#### Scenario: Share action navigates to the picker with the post id
- **GIVEN** the post-detail screen for a visible post P is shown
- **WHEN** the user activates "Bagikan ke chat"
- **THEN** the app navigates to the conversation-picker destination with `postId = P`

### Requirement: The conversation picker lists existing conversations and sends the embed (v1: existing-conversation surface)

The conversation-picker destination SHALL list the user's **existing** conversations (the shipped conversation-list read) and, on a pick, send a message carrying `embedded_post_id = postId` to the picked conversation. Because the picked conversation already exists, the create-or-return path collapses to its "return" arm: its id is the picked row's `conversationId`. The conversation-list rows are PII-stripped (no recipient UUID, per the conversation-list design), so the picker shares to the conversation id directly rather than re-deriving a recipient id. Starting a brand-new conversation with a never-messaged user from the share surface (the create arm of `createOrReturnConversation(recipientUserId)`) is **out of scope for v1**: it requires a recipient picker that exposes user ids, so the picker is the existing-conversation surface. The picker SHALL expose its state through a single-`stateIn` `uiState` ViewModel (the shipped state-holder convention).

The send outcome SHALL map to the share result with no generic fallthrough:
- a `403` → a distinct blocked result;
- a `SendOutcome.RateLimited` (the Free 50/day chat cap — the share is a chat send and consumes the same daily bucket) → a distinct rate-limited result carrying `retryAfterSeconds`;
- any other non-`Sent` outcome → a distinct failed result;
- a successful send → navigate to the conversation thread.

On the rate-limited result the picker SHALL show the shared `DailyCapUpsellDialog` (`mobile-cap-upsell-dialog`, frame 18) with the `chat_cap_upsell` body and the live countdown, NOT the generic failed snackbar, and SHALL NOT navigate to the thread. The dialog's visibility is the picker ViewModel's nullable `shareResult` one-shot, left set while the dialog shows and cleared via `clearShareResult()` on "Tutup" / scrim / back / auto-dismiss. "Aktifkan Premium" SHALL clear it AND invoke the picker's hoisted `onActivatePremium`, which `appEntryProvider` wires to push `PaywallRoute(PaywallEntry.CHAT_CAP)`.

#### Scenario: Picking a conversation sends the embed and opens the thread
- **GIVEN** the picker is shown for `postId = P` listing the user's existing conversations
- **WHEN** the user picks conversation C and the embed send succeeds
- **THEN** a send is issued with `embedded_post_id = P` to C AND the app navigates to the thread

#### Scenario: Blocked recipient maps to a blocked result
- **WHEN** the embed send returns `403` for the picked conversation (the recipient blocked the sender, or vice versa)
- **THEN** the picker surfaces a blocked result (not a crash, not a generic error) and no thread navigation occurs

#### Scenario: A rate-limited share maps to a rate-limited result
- **GIVEN** a `ChatFlow` whose `send(...)` returns `SendOutcome.RateLimited(retryAfterSeconds = 3600)`
- **WHEN** the user picks a conversation
- **THEN** the ViewModel's `shareResult` is the rate-limited result carrying `3600` (NOT the failed result) AND no thread navigation occurs

#### Scenario: A rate-limited share shows the chat cap dialog and its CTA opens the paywall
- **GIVEN** the picker under the real `appEntryProvider` whose share returns `SendOutcome.RateLimited`
- **WHEN** the user picks a conversation, the cap dialog appears with the `chat_cap_upsell` body, and "Aktifkan Premium" is tapped
- **THEN** no failed snackbar is shown AND the root back stack's top entry is `PaywallRoute(entry = PaywallEntry.CHAT_CAP)`

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

### Requirement: The card shows an edited-since-shared banner when the live post was edited after the share

The context card SHALL show a "diedit sejak dibagikan" banner when the live post's latest-edit timestamp (`editedAt` from `single-post-read`, `GET /api/v1/posts/{post_id}`) is known and differs, compared as an instant, from the snapshot's share-time `editedAt`. Both values are `MAX(post_edits.edited_at)` for the post, and `(post_id, edited_at)` is unique, so they differ exactly when an edit landed after the share. This comparison replaces the share-time `embedded_post_edit_id` anchor, which no read path exposes. In every other case the card SHALL show no banner:

- unedited at share time and still unedited (both absent);
- no edit since the share (equal instants);
- the live state is unknown (the read is in flight, failed, or returned `404` because the viewer can no longer see the post).

The gate SHALL never produce a false positive from missing or unparseable data.

The thread's route-scoped `ChatThreadViewModel` SHALL source the live `editedAt` through the `PostEditFlow.refreshPost` repository seam. It SHALL issue at most one read per distinct shared post id per thread visit, for every live card in the merged list: a non-redacted message whose `embedded_post_id` is non-null, whether it arrived via REST history, an older page, or realtime. A redacted message and a deleted-source card (`embedded_post_id` null) SHALL trigger no read and show no banner.

The gate result SHALL be projected onto the display row inside the ViewModel's single `uiState`, not computed in the composable. The card's displayed content SHALL still come from the immutable snapshot. The live read feeds only the banner.

#### Scenario: A post edited after the share shows the banner
- **GIVEN** an embed message whose snapshot `editedAt` is `2026-06-01T11:00:00Z`
- **AND** the live read of that post returns `editedAt = 2026-06-02T09:00:00Z`
- **WHEN** the thread renders the card
- **THEN** the "diedit sejak dibagikan" banner is shown

#### Scenario: A post unedited at share time but edited since shows the banner
- **GIVEN** an embed message whose snapshot `editedAt` is absent
- **AND** the live read returns a non-null `editedAt`
- **WHEN** the thread renders the card
- **THEN** the banner is shown

#### Scenario: An unchanged post shows no banner
- **GIVEN** an embed message whose snapshot `editedAt` equals the live `editedAt` as an instant, or is absent while the live `editedAt` is also absent
- **WHEN** the thread renders the card
- **THEN** no edited-since-shared banner is shown

#### Scenario: Unknown live state shows no banner
- **GIVEN** an embed message whose live read is still in flight, or returned `Unavailable` (a failure, or a `404` because the viewer can no longer see the post)
- **WHEN** the thread renders the card
- **THEN** no edited-since-shared banner is shown

#### Scenario: Each shared post is read once per thread visit
- **GIVEN** a thread whose merged list holds two live embed messages of the same post P and one of post Q
- **WHEN** the history loads and later rows arrive (an older page or a realtime message embedding P)
- **THEN** exactly one live read is issued for P and one for Q

#### Scenario: Deleted-source and redacted embeds are never read
- **GIVEN** a thread holding a deleted-source card (snapshot present, `embedded_post_id` null) and a redacted embed message
- **WHEN** the thread renders
- **THEN** no live read is issued for either, and neither shows the banner

