## MODIFIED Requirements

### Requirement: Send message with client-side guard, optimistic append, and reconcile

The send path SHALL `POST /api/v1/chat/{conversation_id}/messages`. For a **plain text send from the message input bar** the body SHALL be `{ content }` only (no `embedded_*` fields). The **same send client method MAY additionally carry an `embedded_post_id`** when invoked by the share-to-chat flow (`mobile-chat-embedded-posts`); in that case `content` is optional and the client guard SHALL require at least one of a non-empty `content` or an `embedded_post_id`. For the text-bar path the client SHALL reject empty/whitespace-only content and content longer than 2000 characters BEFORE issuing the request. On a successful `201`, the optimistically-appended message SHALL be reconciled to the server-assigned `id` from the response.

The repository SHALL map a `429` (the Free 50/day cap — `chat-conversations` § daily send-rate cap; the Premium tiers skip the limiter, so a `429` is a Free cap hit) to `SendOutcome.RateLimited(retryAfterSeconds)`, carrying the parsed `Retry-After` seconds (absent/unparseable → `0`). A `429` MUST NOT fall into the retryable `Error` fallthrough. The `Retry-After` SHALL be parsed in `ChatMessagesApiClient` (on the `SendMessageApiResult.HttpError` it returns) and mapped once in the shared `ChatRepository`, so every send caller — the thread input bar AND the share-to-chat picker — receives the same outcome.

The send-state projection SHALL map `SendOutcome` to the input-bar states with no generic fallthrough:
- idle;
- sending;
- blocked;
- too-long;
- network-retry;
- a `RateLimited` outcome → **idle**. The cap is surfaced by the chat cap dialog (§ "A rate-limited send shows the chat cap upsell"), not by the input bar, so the bar stays usable.

#### Scenario: Plain text-bar send carries no embedded fields
- **WHEN** the user sends a message from the text input bar
- **THEN** the request body contains a `content` field and NONE of `embedded_post_id` / `embedded_post_snapshot` / `embedded_post_edit_id`

#### Scenario: Share-to-chat send carries embedded_post_id
- **WHEN** the share-to-chat flow sends a post embed
- **THEN** the request body carries `embedded_post_id` (and optionally `content`), and the client guard permits an absent/empty `content` because the embed is present

#### Scenario: Over-length content blocked client-side
- **WHEN** the user attempts to send content of 2001 characters
- **THEN** no request is issued AND the input bar shows the too-long state

#### Scenario: Empty or whitespace-only content is not sent
- **WHEN** the user attempts to send `""` or a whitespace-only string (e.g. `"   "`) from the text input bar with no embed
- **THEN** no request is issued AND the send action stays idle/disabled (no optimistic append)

#### Scenario: Optimistic append reconciles to the server id
- **GIVEN** an optimistic message appended on send
- **WHEN** the `201` returns the inserted row with its server `id`
- **THEN** the optimistic row is reconciled to that `id` (one row, not two) so a subsequent realtime echo or resync of the same `id` does not duplicate it

#### Scenario: A 429 send maps to RateLimited carrying Retry-After
- **GIVEN** a `MockEngine` returning `429 {"error":{"code":"rate_limited"}}` with `Retry-After: 3600` for the send
- **WHEN** the repository processes the response
- **THEN** the outcome is `SendOutcome.RateLimited(retryAfterSeconds = 3600)` AND NOT `SendOutcome.Error`; AND a `429` with no `Retry-After` yields `RateLimited(0)`

#### Scenario: The send-state projection maps RateLimited to idle
- **WHEN** the send-state projection is invoked with `SendOutcome.RateLimited(60)` and `inFlight = false`
- **THEN** it returns the idle state (the cap dialog, not the bar, surfaces the cap)

## ADDED Requirements

### Requirement: A rate-limited send shows the chat cap upsell

When a thread send returns `SendOutcome.RateLimited`, `ChatThreadScreen` SHALL:
- show the shared `DailyCapUpsellDialog` (`mobile-cap-upsell-dialog`, mockup frame 18), with the body `stringResource(Res.string.chat_cap_upsell)` formatted with the live countdown derived from `retryAfterSeconds`;
- drop the optimistic bubble (the message was not accepted);
- keep the typed text in the input field, so the user loses nothing.

Dialog state and CTAs:
- The dialog's visibility is the ViewModel's existing nullable `sendOutcome` one-shot state (docs/11 §2.2). It is not a new event stream.
- "Tutup" / scrim / back / the countdown auto-dismiss SHALL clear it via `clearSendOutcome()`.
- "Aktifkan Premium" SHALL clear it AND invoke the screen's hoisted `onActivatePremium`. `appEntryProvider` wires that to push `PaywallRoute(PaywallEntry.CHAT_CAP)` onto the root back stack.

The screen stays navigation-free.

#### Scenario: A 429 send shows the chat cap dialog and keeps the input
- **GIVEN** `ChatThreadScreen` over a `ChatFlow` whose `send(...)` returns `SendOutcome.RateLimited(retryAfterSeconds = 1140)`
- **WHEN** the user types "halo" and taps send
- **THEN** the cap dialog is shown with the `chat_cap_upsell` body formatted with "19 mnt" AND no message bubble for "halo" remains in the list AND the input field still contains "halo"

#### Scenario: Dismissing the chat cap dialog clears the one-shot
- **GIVEN** the chat cap dialog is shown after a rate-limited send
- **WHEN** "Tutup" is tapped
- **THEN** the dialog disappears AND the ViewModel's `sendOutcome` is `null` AND no `PaywallRoute` is pushed

#### Scenario: The chat cap CTA opens the paywall as CHAT_CAP
- **GIVEN** the chat cap dialog shown on a thread under the real `appEntryProvider`
- **WHEN** "Aktifkan Premium" is tapped
- **THEN** the dialog is dismissed AND the root back stack's top entry is `PaywallRoute(entry = PaywallEntry.CHAT_CAP)`
