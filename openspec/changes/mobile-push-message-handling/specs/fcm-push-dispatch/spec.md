## MODIFIED Requirements

### Requirement: Android payload SHALL be data-only with `priority: "high"` and required data keys

For each row whose `platform = "android"`, `FcmDispatcher` SHALL build an FCM `Message` with the following shape:

- The `notification` block (alert payload) MUST be empty / unset. The Android payload is **data-only** per [`docs/04-Architecture.md`](../../../docs/04-Architecture.md) (the app handles rendering locally with the user's preview-toggle preference check).
- An `AndroidConfig` block with `priority = HIGH`.
- Data fields populated from the `NotificationDto`:
  - `type` — the notification type string (e.g., `"post_liked"`).
  - `actor_user_id` — the actor's UUID as string, or empty string if `dto.actorUserId == null`.
  - `actor_username` — the actor's display username for client-side copy. The dispatcher SHALL resolve it via `ActorUsernameLookup.lookup(actor_user_id)` from `visible_users` and map the result EXACTLY as follows (this masking happens in the dispatcher, before stringifying — the Android client does NOT re-resolve): a resolved name → that name; `lookup(...) == null` for a **non-null** `actor_user_id` (actor shadow-banned / deleted / not visibly resolvable) → the generic-fallback string `"Seseorang"` — the SAME masking `PushCopy.bodyFor(...)` applies to the iOS `notification.body`; the real handle MUST NOT be emitted, and `""` MUST NOT be emitted for a non-null-but-unresolvable actor (that is the Shadow-ban-safety / Block-enforcement seam); `dto.actorUserId == null` (system-emitted, actor-less type) → empty string `""` (the client renders actor-less copy). This lets the data-only Android client render faithful, masking-correct copy WITHOUT a render-time network call. The raw `actor_user_id` UUID remains for routing; `actor_username` is render-only.
  - `target_type` — `dto.targetType` (e.g., `"post"`), or empty string if null.
  - `target_id` — `dto.targetId` as string, or empty string if null.
  - `body_data` — `dto.bodyData` JSON-stringified (FCM data fields must be strings), or empty string if null. The client MUST parse this back into JSON for in-app rendering.
- The `token` set to the row's `token` value.

#### Scenario: Android payload has no notification block

- **WHEN** an Android push is constructed
- **THEN** the resulting `Message`'s `notification` field is null/unset (data-only mode confirmed)

#### Scenario: Android payload sets priority HIGH

- **WHEN** an Android push is constructed
- **THEN** the resulting `Message`'s `AndroidConfig.priority == HIGH`

#### Scenario: Android payload includes `type`, `target_type`, `target_id` data fields

- **WHEN** an Android push is constructed for a `post_liked` notification with `target_type="post"`, `target_id=<uuid>`
- **THEN** the data map contains `"type" -> "post_liked"` AND `"target_type" -> "post"` AND `"target_id" -> "<uuid-as-string>"`

#### Scenario: Android payload includes the masked actor_username

- **WHEN** an Android push is constructed for a `post_liked` notification whose actor resolves via `ActorUsernameLookup` to `"bobby"`
- **THEN** the data map contains `"actor_username" -> "bobby"`

#### Scenario: Android actor_username masks a shadow-banned non-null actor to "Seseorang", never the empty string or the real handle

- **WHEN** an Android push is constructed for a notification with a **non-null** `actor_user_id` whose `ActorUsernameLookup.lookup(...)` returns null (the actor is shadow-banned / deleted / not visible via `visible_users`)
- **THEN** the data map contains `"actor_username" -> "Seseorang"` (the generic-fallback, mirroring the iOS body masking) AND the value is NOT the empty string AND the value is NOT the actor's real handle

#### Scenario: Android actor_username is empty only for a system-emitted (null-actor) notification

- **WHEN** an Android push is constructed for a system-emitted notification (`actor_user_id == NULL`, e.g. `post_auto_hidden`)
- **THEN** the data map contains `"actor_username" -> ""` (the client renders actor-less copy; the empty string here signals "no actor", distinct from the masked-"Seseorang" case above)

#### Scenario: Android payload includes JSON-stringified `body_data`

- **WHEN** an Android push is constructed for a notification with `body_data = {"post_excerpt": "Hi from Jakarta"}`
- **THEN** the data map contains `"body_data"` whose value is the JSON string `{"post_excerpt":"Hi from Jakarta"}` (or equivalent canonical form parseable as JSON)

#### Scenario: Android payload tolerates null actor and target

- **WHEN** an Android push is constructed for a system-emitted notification (`actor_user_id = NULL`, `target_type = NULL`, `target_id = NULL` — e.g., `post_auto_hidden` for a reply target uses `target_type='reply'` so this is the `privacy_flip_warning` shape)
- **THEN** the data map contains `"actor_user_id" -> ""`, `"actor_username" -> ""`, `"target_type" -> ""`, `"target_id" -> ""` (no key omission; consumers can rely on key presence with empty-string semantics)

#### Scenario: Android payload tolerates null body_data

- **WHEN** an Android push is constructed for a notification whose `body_data IS NULL`
- **THEN** the data map contains `"body_data" -> ""` (no exception, no key omission; consumers can rely on key presence with empty-string semantics)

### Requirement: iOS payload SHALL be alert + mutable-content with `body_full` data field, clamped to APNs 4 KB limit

For each row whose `platform = "ios"`, `FcmDispatcher` SHALL build an FCM `Message` with the following shape:

- A `Notification` block with `title` (per-type via `PushCopy.titleFor(type)`) and `body` (per-type via `PushCopy.bodyFor(notification, actor_username)`).
- An `ApnsConfig` block with `aps.mutableContent = true`. This is the flag the iOS Notification Service Extension consumes to optionally rewrite the body based on the on-device preview-toggle preference per [`docs/04-Architecture.md`](../../../../../docs/04-Architecture.md).
- A custom data field `body_full` carrying the JSON-stringified `dto.bodyData`. The NSE rewrites the body based on this field if the preview-toggle is ON.
- **Routing data fields for tap deep-linking** — `type`, `target_type`, `target_id` (same string/empty-string semantics as the Android data block). These are delivered in the notification `userInfo` at tap time so the iOS `UNUserNotificationCenterDelegate` can resolve the deep-link destination via the shared `(type, target_type, target_id, actor_user_id, body_data) → destination` resolver. `body_data.conversation_id` (inside `body_full`) supplies the chat address; these outer fields supply the rest.
- The `token` set to the row's `token` value.

The iOS payload routing data fields (`type`/`target_type`/`target_id`) are present specifically so an iOS notification tap can deep-link (the prior "MUST NOT include the same data block as Android" restriction is relaxed to exactly these routing fields + `body_full`; the NSE still consumes `body_full` only for the body rewrite). All custom data MUST remain within the APNs 4 KB clamp below.

**APNs 4 KB clamp:** the assembled APNs payload (notification block + custom data including `body_full` AND the routing fields) MUST stay under the 4 KB APNs hard limit. Per `design.md` D6, the iOS payload builder SHALL pre-clamp `body_full` to a safe ceiling (typically 3 KB after JSON-stringification, leaving headroom for the notification block + the routing fields + APNs envelope overhead). Truncation MAY drop trailing characters from the longest-field — typically `post_excerpt` or `reply_excerpt` — preserving the surrounding JSON shape (the truncated string is still valid JSON; structurally `{"post_excerpt": "Hi from Jakarta...", "reply_id": "uuid"}` retains both keys, only the excerpt is shortened).

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

#### Scenario: iOS payload carries the tap-routing data fields

- **WHEN** an iOS push is constructed for a `post_liked` notification with `target_type="post"`, `target_id=<uuid>`
- **THEN** the APNs custom payload (delivered in `userInfo`) contains `"type" -> "post_liked"` AND `"target_type" -> "post"` AND `"target_id" -> "<uuid-as-string>"` (with the same empty-string-when-null semantics as Android) AND the assembled payload remains ≤ 4 KB

#### Scenario: iOS payload uses fallback copy for unwired notification types

- **WHEN** an iOS push is constructed for a notification of type `subscription_billing_issue` (not yet emitted as of this change but admitted by the V10 enum; replaces the prior `chat_message` example which is wired by `chat-message-notification`)
- **THEN** the `notification.body` is the fallback copy `"Notifikasi baru dari NearYou"` (per `PushCopy` fallback rule) AND no exception is thrown

#### Scenario: iOS payload uses chat_message copy when actor username is present

- **WHEN** an iOS push is constructed for a `chat_message` notification with actor `"bobby"` (added by `chat-message-notification`)
- **THEN** the `notification.body` is `"bobby mengirim pesan"` (per the `chat_message` template added by `chat-message-notification`); the `body_full` JSON-stringified `body_data` carries `conversation_id` and `preview` keys verbatim

#### Scenario: iOS payload clamps oversized body_full to stay under APNs 4 KB

- **WHEN** an iOS push is constructed for a `post_replied` notification whose `body_data.reply_excerpt` is a 5000-byte UTF-8 string (deliberately oversized; per the in-app-notifications spec it should be ≤ 80 code points, but the dispatcher MUST be defensive against an emit-site bug or a future spec change)
- **THEN** the assembled APNs payload size (notification block + custom data including `body_full` AND the routing fields) is ≤ 4 KB AND the resulting `body_full` is the JSON-stringified `body_data` with `reply_excerpt` truncated AND the structure is valid JSON parseable by the iOS NSE AND the `reply_id` field (if any) is preserved intact

#### Scenario: iOS payload below the clamp threshold is unmodified

- **WHEN** an iOS push is constructed for a typical `post_liked` notification with a 50-codepoint `post_excerpt`
- **THEN** the assembled APNs payload size is well under 4 KB AND `body_full` carries the original JSON-stringified `body_data` verbatim (no clamping applied)

#### Scenario: iOS clamp cuts on UTF-8 codepoint boundary, not mid-codepoint

- **WHEN** an iOS push is constructed for a `post_replied` notification whose `body_data.reply_excerpt` is a 5000-byte UTF-8 string with a 4-byte emoji `🎉` at byte position ~3000 (i.e., the naive byte-clamp ceiling would slice the emoji's surrogate pair / multi-byte sequence)
- **THEN** the truncated `body_full` is valid UTF-8 (no orphan surrogate or partial multi-byte sequence) AND parses back as valid JSON AND the truncation point falls cleanly before or after the emoji — never inside it

#### Scenario: iOS clamp pathology — body_data has no single field large enough to truncate

- **WHEN** an iOS push is constructed for a notification with an unusually large but uniform `body_data` (e.g., 20 small fields totaling >4 KB, with no single field dominating)
- **THEN** the implementation MUST either (a) drop the dispatch entirely with a structured WARN `event="fcm_dispatch_failed"` `error_code="payload_too_large"` (no FCM call made; recipient sees the in-app notification per the docs/04-Architecture.md fallback), OR (b) apply ordered-truncation across multiple fields per a documented strategy. Option (a) is the simpler default; option (b) requires explicit doc + scenario coverage
