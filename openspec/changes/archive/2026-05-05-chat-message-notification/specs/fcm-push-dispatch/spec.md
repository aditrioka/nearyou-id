## MODIFIED Requirements

### Requirement: iOS payload SHALL be alert + mutable-content with `body_full` data field, clamped to APNs 4 KB limit

For each row whose `platform = "ios"`, `FcmDispatcher` SHALL build an FCM `Message` with the following shape:

- A `Notification` block with `title` (per-type via `PushCopy.titleFor(type)`) and `body` (per-type via `PushCopy.bodyFor(notification, actor_username)`).
- An `ApnsConfig` block with `aps.mutableContent = true`. This is the flag the future iOS Notification Service Extension consumes to optionally rewrite the body based on the on-device preview-toggle preference per [`docs/04-Architecture.md:535-540`](../../../../../docs/04-Architecture.md).
- A custom data field `body_full` carrying the JSON-stringified `dto.bodyData`. The NSE rewrites the body based on this field if the preview-toggle is ON.
- The `token` set to the row's `token` value.

The iOS payload MUST NOT include the same data block as Android (the iOS NSE consumes `body_full` only; other data routing is via `aps.category` etc., which is out of scope for this change).

**APNs 4 KB clamp:** the assembled APNs payload (notification block + custom data) MUST stay under the 4 KB APNs hard limit. Per `design.md` D6, the iOS payload builder SHALL pre-clamp `body_full` to a safe ceiling (typically 3 KB after JSON-stringification, leaving headroom for the notification block + APNs envelope overhead). Truncation MAY drop trailing characters from the longest-field — typically `post_excerpt` or `reply_excerpt` — preserving the surrounding JSON shape (the truncated string is still valid JSON; structurally `{"post_excerpt": "Hi from Jakarta...", "reply_id": "uuid"}` retains both keys, only the excerpt is shortened).

**UTF-8 codepoint-boundary requirement:** truncation MUST cut on a Unicode codepoint boundary, NEVER mid-codepoint. Multi-byte UTF-8 characters (Indonesian diacritics like "Müller", CJK characters like "中田", emoji like "🎉") occupy 2–4 bytes; a naive byte-clamp at byte position N may slice mid-codepoint and produce invalid UTF-8, which (a) corrupts the embedded JSON, (b) causes APNs to reject the payload as malformed, and (c) burns the dispatch with `MessagingErrorCode.INVALID_ARGUMENT` (which per D6 is transient — but the next emit will hit the same bug). Implementation: use Kotlin's `String.take(n)` (which operates on `Char` boundaries, not byte boundaries; for emojis represented as surrogate pairs, additionally guard against splitting a surrogate pair) OR an explicit UTF-8-aware truncator. Tests MUST cover a multi-byte boundary case (e.g., a 4-byte emoji at byte position ~3000 in an oversized excerpt — naive byte-clamp breaks here).

The reason this matters: FCM's underlying APNs response surfaces oversized-payload AND malformed-payload as `MessagingErrorCode.INVALID_ARGUMENT`, which per `design.md` D6 is a transient WARN — without clamping (or with broken clamping), every push for an excerpt-heavy notification would silently fail with no observable signal beyond a steady WARN-rate increase.

#### Scenario: iOS payload has alert title and body

- **WHEN** an iOS push is constructed for a `post_liked` notification by actor "bobby"
- **THEN** the resulting `Message`'s `notification.title` is `"NearYou"` AND `notification.body` matches the format `"bobby menyukai post-mu"` (per `PushCopy` rules)

#### Scenario: iOS payload sets aps.mutableContent = true

- **WHEN** an iOS push is constructed
- **THEN** the resulting `Message`'s `ApnsConfig.aps.mutableContent` equals true

#### Scenario: iOS payload carries body_full as JSON-stringified body_data

- **WHEN** an iOS push is constructed for a notification with `body_data = {"post_excerpt": "Hi from Jakarta"}`
- **THEN** the `ApnsConfig.payload.body_full` is the JSON string `{"post_excerpt":"Hi from Jakarta"}`

#### Scenario: iOS payload uses fallback copy for unwired notification types

- **WHEN** an iOS push is constructed for a notification of type `subscription_billing_issue` (not yet emitted as of this change but admitted by the V10 enum; replaces the prior `chat_message` example which is wired by `chat-message-notification`)
- **THEN** the `notification.body` is the fallback copy `"Notifikasi baru dari NearYou"` (per `PushCopy` fallback rule) AND no exception is thrown

#### Scenario: iOS payload uses chat_message copy when actor username is present

- **WHEN** an iOS push is constructed for a `chat_message` notification with actor `"bobby"` (added by `chat-message-notification`)
- **THEN** the `notification.body` is `"bobby mengirim pesan"` (per the `chat_message` template added by `chat-message-notification`); the `body_full` JSON-stringified `body_data` carries `conversation_id` and `preview` keys verbatim

#### Scenario: iOS payload clamps oversized body_full to stay under APNs 4 KB

- **WHEN** an iOS push is constructed for a `post_replied` notification whose `body_data.reply_excerpt` is a 5000-byte UTF-8 string (deliberately oversized; per the in-app-notifications spec it should be ≤ 80 code points, but the dispatcher MUST be defensive against an emit-site bug or a future spec change)
- **THEN** the assembled APNs payload size (notification block + custom data including `body_full`) is ≤ 4 KB AND the resulting `body_full` is the JSON-stringified `body_data` with `reply_excerpt` truncated AND the structure is valid JSON parseable by the iOS NSE AND the `reply_id` field (if any) is preserved intact

#### Scenario: iOS payload below the clamp threshold is unmodified

- **WHEN** an iOS push is constructed for a typical `post_liked` notification with a 50-codepoint `post_excerpt`
- **THEN** the assembled APNs payload size is well under 4 KB AND `body_full` carries the original JSON-stringified `body_data` verbatim (no clamping applied)

#### Scenario: iOS clamp cuts on UTF-8 codepoint boundary, not mid-codepoint

- **WHEN** an iOS push is constructed for a `post_replied` notification whose `body_data.reply_excerpt` is a 5000-byte UTF-8 string with a 4-byte emoji `🎉` at byte position ~3000 (i.e., the naive byte-clamp ceiling would slice the emoji's surrogate pair / multi-byte sequence)
- **THEN** the truncated `body_full` is valid UTF-8 (no orphan surrogate or partial multi-byte sequence) AND parses back as valid JSON AND the truncation point falls cleanly before or after the emoji — never inside it

#### Scenario: iOS clamp pathology — body_data has no single field large enough to truncate

- **WHEN** an iOS push is constructed for a notification with an unusually large but uniform `body_data` (e.g., 20 small fields totaling >4 KB, with no single field dominating)
- **THEN** the implementation MUST either (a) drop the dispatch entirely with a structured WARN `event="fcm_dispatch_failed"` `error_code="payload_too_large"` (no FCM call made; recipient sees the in-app notification per the docs/04-Architecture.md:558 fallback), OR (b) apply ordered-truncation across multiple fields per a documented strategy. Option (a) is the simpler default; option (b) requires explicit doc + scenario coverage

### Requirement: `PushCopy` SHALL provide Indonesian copy for the four V10-wired types and a fallback for others

A Kotlin object `PushCopy` SHALL be defined in `:infra:fcm` with at minimum:

- `fun titleFor(type: String): String` — returns the constant `"NearYou"` for all known types and the fallback (no per-type title differentiation in this change).
- `fun bodyFor(notification: NotificationDto, actorUsername: String?): String` — returns Indonesian copy parameterized by the actor username (when available) and notification type:
  - `post_liked`: `"<actor_username> menyukai post-mu"` (or `"Seseorang menyukai post-mu"` when `actorUsername == null`)
  - `post_replied`: `"<actor_username> membalas post-mu"` (or `"Seseorang membalas post-mu"` when `actorUsername == null`)
  - `followed`: `"<actor_username> mulai mengikuti kamu"` (or `"Seseorang mulai mengikuti kamu"` when `actorUsername == null`)
  - `post_auto_hidden`: `"Postinganmu disembunyikan otomatis karena beberapa laporan"` (no actor; system-emitted)
  - `chat_message`: `"<actor_username> mengirim pesan"` (or `"Seseorang mengirim pesan"` when `actorUsername == null`) — added by `chat-message-notification`. The body SHALL NOT inline `body_data.preview` content into the FCM body string; preview rendering is a mobile NSE / Android-side concern via the data payload `body_data.preview` key. The null-fallback path is structurally unreachable under the chat send-handler's sender-shadow-ban skip (a shadow-banned sender never triggers emit, so `ActorUsernameLookup.lookup(sender)` is invoked only for non-shadow-banned senders whose `visible_users` row exists and returns the username); the fallback is retained for defense-in-depth against rare races (e.g., a hard-deleted-mid-flight sender between emit and FCM dispatch).
  - Any other type (the 8 not yet emitted after `chat-message-notification`): `"Notifikasi baru dari NearYou"` (fallback)

(Note on the requirement title's "four" count: the title is preserved verbatim per OpenSpec's MODIFIED-header-stability convention — changing it would break the MODIFIED match against the existing canonical requirement. With `chat-message-notification` adding `chat_message` as the fifth wired type, the body above is the authoritative count; the title is a stable identifier with historical-original wording. A future change MAY use the OpenSpec RENAMED operation to update the title.)

`PushCopy` MUST NOT call any external service, MUST NOT read any database row, AND MUST NOT depend on Moko Resources (Moko Resources is a KMP client concern; backend strings are server-side i18n per `design.md` D4).

#### Scenario: post_liked body uses actor username when present

- **WHEN** `bodyFor(post_liked_notification, actorUsername = "bobby")` is invoked
- **THEN** the result is `"bobby menyukai post-mu"`

#### Scenario: post_liked body falls back when actor username is null

- **WHEN** `bodyFor(post_liked_notification, actorUsername = null)` is invoked
- **THEN** the result is `"Seseorang menyukai post-mu"`

#### Scenario: post_auto_hidden body is a constant system string

- **WHEN** `bodyFor(post_auto_hidden_notification, actorUsername = null)` is invoked
- **THEN** the result is `"Postinganmu disembunyikan otomatis karena beberapa laporan"`

#### Scenario: chat_message body uses actor username when present

- **WHEN** `bodyFor(chat_message_notification, actorUsername = "bobby")` is invoked
- **THEN** the result is `"bobby mengirim pesan"`

#### Scenario: chat_message body falls back when actor username is null

- **WHEN** `bodyFor(chat_message_notification, actorUsername = null)` is invoked
- **THEN** the result is `"Seseorang mengirim pesan"` AND no exception is thrown

#### Scenario: chat_message body does NOT inline body_data.preview

- **WHEN** `bodyFor(chat_message_notification_with_preview, actorUsername = "bobby")` is invoked, where the notification's `body_data.preview = "halo Alice"`
- **THEN** the result is exactly `"bobby mengirim pesan"` — the string `"halo Alice"` (or any substring of the preview) does NOT appear in the body. Preview rendering is delegated to the mobile NSE / Android side via the FCM data payload's `body_data.preview` key.

#### Scenario: chat_message body with empty-string actor username falls back to null-fallback

- **WHEN** `bodyFor(chat_message_notification, actorUsername = "")` is invoked (empty string, distinct from null — defensive against a bug in `ActorUsernameLookup` that returns `""` instead of `null`)
- **THEN** the result is `"Seseorang mengirim pesan"` (treated as null-fallback — the empty-string username MUST NOT render as `" mengirim pesan"` with a leading space, which would be a UX bug). Implementation hint: the template SHALL check `actorUsername.isNullOrBlank()` (not just `== null`) before substituting.

#### Scenario: chat_message body with actor username containing emoji renders verbatim

- **WHEN** `bodyFor(chat_message_notification, actorUsername = "bobby🎉")` is invoked (emoji in username — Indonesian usernames are lowercase ASCII per the `username-generation` rules, but defensive against future schema changes that might allow Unicode)
- **THEN** the result is `"bobby🎉 mengirim pesan"` — the emoji is preserved verbatim; no double-encoding, no HTML escaping. APNs / FCM payload stays valid UTF-8.

#### Scenario: chat_message body with actor username containing a quote character does not break JSON serialization

- **WHEN** an iOS push payload is assembled for a `chat_message` notification with `actor_username = "bo\"by"` (containing a JSON-special character — extremely unlikely in practice given username schema, but the dispatcher MUST be robust)
- **THEN** the assembled `ApnsConfig.payload` is valid JSON when parsed back; the `notification.body` field correctly escapes the embedded quote OR the dispatcher rejects the notification with a structured WARN `event="fcm_dispatch_failed"` `error_code="invalid_actor_username"`. Either outcome is acceptable; the test pins which outcome the implementation chose.

#### Scenario: Unknown / unwired type returns the fallback

- **WHEN** `bodyFor(subscription_billing_issue_notification, actorUsername = null)` is invoked (subscription-billing-issue emit-site has not shipped as of this change)
- **THEN** the result is `"Notifikasi baru dari NearYou"` AND no exception is thrown

#### Scenario: titleFor returns "NearYou" for every known type

- **WHEN** `titleFor("post_liked")`, `titleFor("post_replied")`, `titleFor("followed")`, `titleFor("post_auto_hidden")`, `titleFor("chat_message")` are each invoked
- **THEN** every call returns `"NearYou"`
