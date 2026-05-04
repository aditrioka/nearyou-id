## MODIFIED Requirements

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

#### Scenario: Unknown / unwired type returns the fallback

- **WHEN** `bodyFor(subscription_billing_issue_notification, actorUsername = null)` is invoked (subscription-billing-issue emit-site has not shipped as of this change)
- **THEN** the result is `"Notifikasi baru dari NearYou"` AND no exception is thrown

#### Scenario: titleFor returns "NearYou" for every known type

- **WHEN** `titleFor("post_liked")`, `titleFor("post_replied")`, `titleFor("followed")`, `titleFor("post_auto_hidden")`, `titleFor("chat_message")` are each invoked
- **THEN** every call returns `"NearYou"`
