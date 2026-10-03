## MODIFIED Requirements

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
