# mobile-push-message-handling Specification

## Purpose

The `:mobile:app` device-side receipt, rendering, batching, content-privacy gating, and deep-link tap-through of incoming FCM pushes — the display half of the push loop whose dispatch (`fcm-push-dispatch`) and token registration (`mobile-fcm-token-registration`) already shipped. Android renders the data-only payload as a local notification (`onMessageReceived` on the shared `NearYouFirebaseMessagingService`) with type-keyed Bahasa Indonesia copy and per-conversation batching; iOS handles the alert-push tap via a `UNUserNotificationCenterDelegate` and optionally rewrites the body in a Notification Service Extension per the App-Group content-privacy preference. Both platforms route the tap through the `mobile-notifications-list` resolver via the consumed-once nav signal (no second navigation pattern). The content-privacy preference store ships here defaulting OFF (content-private); the Settings control row is deferred (#431), and live end-to-end delivery is gated on operator Firebase/APNs + NSE Xcode setup (#258 / #430) — the code builds, unit-tests, and assembles without that config.

## Requirements
### Requirement: Android renders an incoming data-only FCM push as a local notification

The `:mobile:app` Android target SHALL render an incoming FCM push by extending the shipped `NearYouFirebaseMessagingService` (`mobile/app/src/androidMain/kotlin/id/nearyou/app/push/`) with an `onMessageReceived(RemoteMessage)` override. It SHALL read the data-only payload keys the backend sends — `type`, `actor_user_id`, `actor_username`, `target_type`, `target_id`, and `body_data` (JSON-stringified; the client parses it) — and post a `NotificationCompat` notification on a dedicated, app-owned notification channel. The user-facing title/body SHALL be type-keyed Bahasa Indonesia copy per `docs/03-UX-Design.md` §163–176 (e.g. `post_liked` → "{actor_username} menyukai postingan kamu"; `chat_message` → the content-private form below), sourced via `:shared:resources` Compose Multiplatform Resources where the copy crosses commonMain, with `actor_username` substituted from the payload (no render-time network call). The Firebase SDK / platform notification APIs referenced by the handler SHALL remain confined to `androidMain` (the vendor-isolation invariant; no such import in commonMain). The raw FCM token and the message preview content SHALL NOT be logged.

The client SHALL NOT re-derive masking: `actor_username` arrives already masked by the dispatcher (a resolved name, the generic-fallback `"Seseorang"` for a non-null-but-unresolvable actor, or `""` for a system-emitted actor-less notification — per the `fcm-push-dispatch` `actor_username` masking, which preserves the `in-app-notifications` §71/§73 shadow-ban posture: a chat send by a shadow-banned actor is SUPPRESSED upstream so no `chat_message` push with a banned sender ever reaches this handler, and public-engagement actors are masked to `"Seseorang"`). The render SHALL `isNullOrBlank`-guard `actor_username`: when it is blank, the copy SHALL degrade to a username-free localized form (e.g. "Pesan baru" rather than an orphaned-space "Pesan baru dari "). The handler SHALL be defensive against a malformed payload: a `body_data` that is empty-string, not valid JSON, or missing expected keys (`conversation_id` / `preview`) SHALL render the non-content private form (or skip) and SHALL NEVER throw out of `onMessageReceived` (an uncaught throw in the high-priority FCM callback silently drops the push with no device-side fallback).

#### Scenario: A chat push renders a content-private local notification by default

- **GIVEN** the Android push handler and a content-privacy preference that is unset (default OFF)
- **WHEN** `onMessageReceived` is invoked with a `chat_message` data payload (`target_type="message"`, `actor_username="bobby"`, `body_data={"conversation_id":"<C>","preview":"halo apa kabar"}`)
- **THEN** a `NotificationCompat` notification is built on the app's channel whose body is the private form "Pesan baru dari bobby" (sender + non-content) AND the body does NOT contain the `preview` text AND the preview is not logged

#### Scenario: A post-interaction push renders its full type-keyed copy

- **WHEN** `onMessageReceived` is invoked with a `post_liked` data payload (`target_type="post"`, `target_id` set, `actor_username="bobby"`)
- **THEN** the notification body is the type-keyed copy "bobby menyukai postingan kamu" sourced from `:shared:resources` with `actor_username` substituted

#### Scenario: A masked actor renders the generic-fallback, not the real handle

- **WHEN** `onMessageReceived` is invoked with a `chat_message` payload whose `actor_username="Seseorang"` (the dispatcher masked a non-null-but-unresolvable actor)
- **THEN** the notification body is "Pesan baru dari Seseorang" (the masked token rendered verbatim; the client does not re-resolve)

#### Scenario: A blank actor_username degrades to a username-free form

- **WHEN** `onMessageReceived` is invoked with a notification whose `actor_username=""` (system-emitted / actor-less) for a type whose copy would otherwise interpolate the actor
- **THEN** the body renders a username-free localized form (no orphaned "Pesan baru dari " / " mengirim sebuah postingan" with a leading/trailing space) AND no crash

#### Scenario: A malformed body_data never crashes the handler

- **WHEN** `onMessageReceived` is invoked with a `chat_message` payload whose `body_data` is an empty string, OR non-JSON garbage, OR a JSON object missing `conversation_id`/`preview`
- **THEN** the handler renders the non-content private form (or skips) AND does NOT throw out of `onMessageReceived` (the high-priority callback completes without dropping the app)

### Requirement: The notification body honors the content-privacy preference

When the local content-privacy preference is **ON**, a `chat_message` notification body SHALL surface the message preview from `body_data.preview` (already ≤80 code points, server-truncated at emit time — `docs/03` §178 / `docs/04` §491). When `body_data.preview` is JSON `null` (an embedded-only message), the body SHALL be a localized "{actor_username} mengirim sebuah postingan" fallback (`in-app-notifications` §284) — never raw content. When the preference is **OFF or unset** (the default), the body SHALL be the private "Pesan baru dari {actor_username}" form and SHALL NOT include the preview. Non-chat notification types are unaffected by the preference (their copy is already non-sensitive per `docs/03` §155).

#### Scenario: Preview ON surfaces the chat preview

- **GIVEN** the content-privacy preference is ON
- **WHEN** a `chat_message` push arrives with `body_data.preview = "halo apa kabar"`
- **THEN** the notification body surfaces the preview text "halo apa kabar"

#### Scenario: Preview ON with a null preview falls back without raw content

- **GIVEN** the content-privacy preference is ON
- **WHEN** a `chat_message` push arrives with `body_data.preview = null` (embedded-only message)
- **THEN** the body is the localized "{actor_username} mengirim sebuah postingan" fallback AND no raw message content is surfaced

#### Scenario: Preview OFF keeps the private form

- **GIVEN** the content-privacy preference is OFF (or unset)
- **WHEN** a `chat_message` push arrives with a non-null `body_data.preview`
- **THEN** the notification body is "Pesan baru dari {actor_username}" AND contains no substring of `body_data.preview`

### Requirement: Tapping a push deep-links to the in-app destination via the shared resolver

The notification tap SHALL reopen the app and navigate to the same in-app destination the in-app notification list resolves to, by reusing the pure `(type, target_type, target_id, actor_user_id, body_data) → destination` resolver defined by `mobile-notifications-list` (no second navigation pattern; `docs/11` §2.2 consumed-once nav signal). Reuse means the resolver's existing behavior is inherited verbatim, including its chat path that fetches the partner display identity (`GET /api/v1/users/{actor_user_id}`) before opening the thread and its documented fetch-failure fallback (open the thread with a blank top-bar name) — this change SHALL NOT fork or reimplement that path. On Android the handler SHALL attach a `PendingIntent` to `MainActivity` carrying the routing fields as extras; on iOS the `UNUserNotificationCenterDelegate` SHALL read the routing fields from the notification `userInfo` (the `type`/`target_type`/`target_id` fields added to the iOS payload by the `fcm-push-dispatch` MODIFY) — both platforms feed the SAME resolver. On launch/resume the routing inputs SHALL be consumed exactly once and emit the nav signal. The mapping SHALL be: `target_type="post"` → post detail by `target_id`; `followed` → the actor's profile; `chat_message` (`target_type="message"`) → the chat thread addressed by `body_data.conversation_id`; actor-less / `reply`-target / no-destination types → no navigation. `actor_user_id`, `target_id`, and `conversation_id` SHALL be carried as opaque extras / route params only — never rendered in any UI node nor logged.

#### Scenario: Tapping a chat push routes to the addressed thread

- **WHEN** a `chat_message` notification (`target_type="message"`, `body_data.conversation_id="<C>"`) is tapped
- **THEN** the app resolves to the chat-thread destination addressed by `<C>` via the shared resolver AND the nav signal is consumed exactly once (it does not re-fire on recomposition / configuration change) AND no `conversation_id` / `actor_user_id` is logged

#### Scenario: Tapping a post push routes to post detail on both platforms

- **WHEN** a `post_liked` notification (`target_type="post"`, `target_id="<P>"`) is tapped — Android via the `PendingIntent` extras, iOS via the `userInfo` routing fields
- **THEN** each platform resolves to the post-detail destination for `<P>` through the SAME shared resolver

#### Scenario: Tapping a no-destination push navigates nowhere

- **WHEN** a notification whose `(type, target_type)` has no in-app destination (e.g. `chat_message_redacted` with a null actor, or a `reply`-target `post_auto_hidden`) is tapped
- **THEN** the resolver yields no destination AND no navigation occurs (the tap only opens the app)

### Requirement: Incoming chat pushes are batched per conversation on the display side

Chat notifications SHALL be batched per conversation: within 10 seconds of the last push for a given `conversation_id`, a new chat push SHALL REPLACE the existing conversation notification (reusing `conversation_id` as the Android notification tag) with a merged "{n} pesan baru dari {actor_username}" form (the count of messages in the window) and SHALL NOT raise a fresh alert sound; outside the window it SHALL post a fresh notification (`docs/04` §484–486). The batching state (a per-conversation last-push timestamp + count) SHALL survive a cold service start (persisted, not in-memory only). Non-chat types SHALL NOT batch by conversation and SHALL use a per-`(type, target_id)` tag so unrelated notifications never overwrite each other. On iOS the merge is best-effort via OS grouping (`thread-id` / collapse id = `conversation_id`); the durable count-merge is an Android guarantee only.

#### Scenario: Two chat pushes within the window merge into one notification

- **GIVEN** a chat push for `conversation_id="<C>"` was rendered <10 s ago
- **WHEN** a second chat push for `<C>` arrives
- **THEN** the existing `<C>`-tagged notification is replaced with "2 pesan baru dari {actor_username}" AND no fresh alert sound is raised AND only one notification for `<C>` is present

#### Scenario: Pushes outside the window post fresh notifications

- **GIVEN** the last chat push for `conversation_id="<C>"` was rendered >10 s ago (timestamp persisted across a cold start)
- **WHEN** a new chat push for `<C>` arrives
- **THEN** a fresh notification is posted (window + count reset)

### Requirement: iOS renders the alert push and rewrites the body in a Notification Service Extension per the App-Group preference

The `:mobile:app` iOS target SHALL handle the alert push: a `UNUserNotificationCenterDelegate` tap handler SHALL route to the in-app destination via the shared resolver using the `userInfo` routing fields (mirroring the Android path), AND a **Notification Service Extension (NSE)** target SHALL parse the `body_full` data field (= JSON-stringified `body_data`) and, **only when** the content-privacy preference read from App-Group shared `UserDefaults` (suite `group.id.nearyou.shared`) is ON AND the notification is a chat message with a non-null `preview`, rewrite the visible body to surface that preview (`docs/04` §488–502); when the preference is OFF/unset (or the preview is null) the server-built private alert body stands. All Firebase / `UserNotifications` references SHALL stay in `iosMain` / the NSE target — none in commonMain. The NSE and delegate SHALL be inert (no crash, no rewrite) when the operator App-Group / Firebase / APNs config is absent.

#### Scenario: NSE rewrites the body to the preview when preview is ON

- **GIVEN** the App-Group preference `group.id.nearyou.shared` has preview ON
- **WHEN** the NSE processes a `chat_message` alert push whose `body_full` parses to `body_data` with `preview="halo apa kabar"`
- **THEN** the delivered notification body surfaces "halo apa kabar"

#### Scenario: NSE leaves the private body when preview is OFF

- **GIVEN** the App-Group preference is OFF or unset
- **WHEN** the NSE processes an alert push whose `body_full` carries a non-null `preview`
- **THEN** the delivered body is the server-built private alert body AND no preview content is surfaced

### Requirement: A content-privacy preference store seam ships, defaulting to private, mirrored to the iOS App Group

The change SHALL ship a Compose-free commonMain seam `NotificationContentPreference` exposing the read and write of a single "show chat preview in notifications" boolean, defaulting to **OFF** (private) when unset (`docs/03` §178). The Android actual SHALL persist via DataStore; the iOS actual SHALL persist into the `group.id.nearyou.shared` App-Group `UserDefaults` suite so the out-of-process NSE can read it. A write SHALL be reflected by the next read on the same platform.

#### Scenario: Default is private when unset

- **WHEN** `previewEnabled()` is read with no prior write
- **THEN** it returns `false` (private)

#### Scenario: A write round-trips

- **WHEN** `setPreviewEnabled(true)` is called and then `previewEnabled()` is read
- **THEN** it returns `true`

### Requirement: The Settings preview-toggle control row is deferred as an explicit requirement

The user-facing Settings control row "Tampilkan preview pesan chat di notifikasi" (`docs/03` §178) that flips the content-privacy preference SHALL NOT be added to `mobile-settings` by this change — it is deferred (docs/12 §3 explicit requirement) to avoid a `SettingsScreen` merge conflict with the in-flight `mobile-data-export-entry` change (PR #424). This change ships only the preference STORE and the render/NSE gate (functional at the private default); the deferred row SHALL be tracked by a `follow-up` GitHub issue (labels `follow-up` + `mobile`) — [#431](https://github.com/aditrioka/nearyou-id/issues/431). The deferral leaves no unsafe gap because the default behavior is the content-private form.

#### Scenario: No Settings row ships in this change

- **WHEN** inspecting this change's diff
- **THEN** no row toggling the notification-content-preview preference is added to `SettingsScreen` / `mobile-settings` AND the deferral is tracked by the `follow-up` GitHub issue [#431](https://github.com/aditrioka/nearyou-id/issues/431)

### Requirement: Push-display code builds, unit-tests, and assembles without the operator Firebase / App-Group config

The push-display code SHALL build, unit-test, and `assembleStagingDebug` green **without** the operator Firebase client config (`google-services.json` / `GoogleService-Info.plist` / APNs `.p8`) and **without** the iOS App-Group / NSE Xcode setup (the PR #250 precedent). Absent the config, the Android `onMessageReceived` is never invoked by the SDK and the iOS NSE / delegate are inert; the handler logic is exercised directly by unit tests. The vendor-isolation guard test (`FcmPushSourceGuardTest` or its successor) SHALL be extended to assert no Firebase / platform-notification import leaks into commonMain. Android `POST_NOTIFICATIONS` SHALL be declared in the manifest, but the runtime-permission **prompt** SHALL NOT be added here (it is owned by the chat surface, #257).

#### Scenario: Build and unit tests are green config-free

- **WHEN** the module is built and unit-tested without any Firebase / App-Group config present
- **THEN** the build and unit tests pass AND the commonMain source contains no Firebase / `UserNotifications` / `NotificationCompat` import (vendor isolation holds)

#### Scenario: Manifest declares the permission but ships no runtime prompt

- **WHEN** inspecting this change's diff
- **THEN** the Android manifest declares `android.permission.POST_NOTIFICATIONS` AND no in-app `POST_NOTIFICATIONS` runtime-prompt flow is added (the prompt remains the chat surface's, #257)

#### Scenario: No PII / id / preview / token reaches a log sink

- **GIVEN** the push-display source under `androidMain` push + the iOS delegate/NSE
- **WHEN** the display + tap-routing paths are exercised
- **THEN** no log sink receives `actor_user_id`, `target_id`, `conversation_id`, the message `preview`/content, or the raw FCM token (a guard mirroring the shipped token-log guard in `mobile-fcm-token-registration` — prose alone is insufficient)
