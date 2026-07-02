## 1. Preflight / preconditions

- [x] 1.1 Re-confirm the shipped wire against `fcm-push-dispatch` + `in-app-notifications` specs: Android data-only keys (`type`, `actor_user_id`, `target_type`, `target_id`, `body_data`); iOS alert + `body_full` (= JSON-stringified `body_data`); `chat_message` `body_data = {conversation_id, preview≤80cp|null}`. Record exact keys the client + backend touch.
- [x] 1.2 Verify whether the shipped chat screen requests the Android `POST_NOTIFICATIONS` runtime permission (#257 closed). If it does NOT, file a `follow-up` (labels `follow-up` + `mobile`) for the runtime prompt — this change declares the manifest permission only.
- [x] 1.3 Confirm the App-Group identifier `group.id.nearyou.shared` against any existing iOS entitlements (`docs/04` §490); no divergent suite name.
- [x] 1.4 (Operator, tracked — not a code blocker) iOS NSE Xcode extension target + App-Group capability on both app + NSE targets + provisioning + `application-groups` entitlement (`docs/04` §494–502) — issue [#430](https://github.com/aditrioka/nearyou-id/issues/430); Firebase client config + APNs `.p8` for live verify — issue [#258](https://github.com/aditrioka/nearyou-id/issues/258). Code must build/test/assemble without these.

## 2. Backend MODIFY — `fcm-push-dispatch` payload fields (:infra:fcm)

- [x] 2.1 iOS payload builder: add `type`, `target_type`, `target_id` custom data fields (same string/empty-string semantics as Android) so the iOS tap can deep-link; keep `body_full` for the NSE; ensure the 4 KB clamp accounts for the added fields.
- [x] 2.2 Android payload builder: add `actor_username` data field resolved via the existing `ActorUsernameLookup` from `visible_users` (the same generic-fallback masking the iOS body uses); empty string when `actor_user_id == null`.
- [x] 2.3 Dispatcher tests: iOS payload carries the routing fields + stays ≤4 KB (incl. the oversized-`body_full` clamp case + the clamp-pathology multi-field case still holding with the extra fields); Android `actor_username` masking — resolved name; non-null-but-unresolvable (shadow-banned/deleted) → `"Seseorang"` (NOT `""`, NOT the real handle); `actor_user_id == null` → `""`.
- [x] 2.4 Apply the `fcm-push-dispatch` delta (MODIFIED Android + iOS payload requirements) at archive time; confirm no orphaned scenario.

## 3. Content-privacy preference store (commonMain seam + actuals)

- [x] 3.1 Add commonMain `interface NotificationContentPreference` (Compose-free): `suspend fun previewEnabled(): Boolean` (default `false`), `suspend fun setPreviewEnabled(v: Boolean)`.
- [x] 3.2 Android actual: DataStore-backed implementation; default OFF when unset.
- [x] 3.3 iOS actual: persist into `UserDefaults(suiteName="group.id.nearyou.shared")` so the out-of-process NSE can read it; default OFF when unset.
- [x] 3.4 Koin-bind the platform actuals in the respective `PlatformModule`s.
- [x] 3.5 commonTest: default-OFF-when-unset + write-round-trips-to-read.

## 4. Shared deep-link resolver + nav signal (commonMain)

- [x] 4.1 Ensure the pure `(type, target_type, target_id, actor_user_id, body_data) → destination` resolver from `mobile-notifications-list` is a shared commonMain function (lift it if currently private to the notifications screen); do NOT introduce a second resolver.
- [x] 4.2 Define / reuse a consumed-once nav-signal entry point (`docs/11` §2.2) the push tap feeds (no event bus).
- [x] 4.3 commonTest: resolver maps post → post-detail, `followed` → profile, `chat_message` → chat-thread by `body_data.conversation_id`, actor-less / reply-target → no destination (mirror the notifications-list resolver tests).

## 5. Android incoming-push display

- [x] 5.1 Extend `NearYouFirebaseMessagingService` with `onMessageReceived(RemoteMessage)`: parse `type`/`actor_user_id`/`actor_username`/`target_type`/`target_id`/`body_data`; keep all Firebase / `NotificationCompat` imports in `androidMain`.
- [x] 5.2 Create the app notification channel (idempotent) + a notification builder producing type-keyed Bahasa Indonesia copy from `:shared:resources` with `actor_username` substituted; chat default body = "Pesan baru dari {actor_username}"; preview-ON chat body = `body_data.preview` (null → "{actor_username} mengirim sebuah postingan" fallback).
- [x] 5.3 Attach a tap `PendingIntent` → `MainActivity` carrying `type`/`target_type`/`target_id`/`body_data` as extras; consume-once on launch/resume → emit the nav signal (task 4.2). Never log preview / ids / token.
- [x] 5.4 Declare `<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>` in the Android manifest (no runtime prompt — task 1.2).
- [x] 5.5 Per-conversation batching: persist a per-`conversation_id` last-push timestamp + count (DataStore); within 10 s replace the `conversation_id`-tagged notification with "{n} pesan baru dari {actor_username}" + suppress fresh sound; outside the window post fresh; non-chat uses a per-`(type,target_id)` tag.
- [x] 5.6 Robolectric/unit `onMessageReceived` tests: default-private chat body ("Pesan baru dari {username}"); masked actor (`actor_username="Seseorang"`) rendered verbatim; **blank `actor_username` degrades to a username-free form (no orphaned leading/trailing space)**; **malformed `body_data` (empty-string / non-JSON / missing keys) renders the private form and never throws out of the handler**; preview-ON surfaces `body_data.preview`; null-preview fallback; post-interaction type-keyed copy with `actor_username`; tap PendingIntent → correct resolver destination; batching merge within window + fresh outside window (timestamp persisted across cold start); no-destination tap navigates nowhere.

## 6. iOS incoming-push display (delegate + NSE)

- [x] 6.1 `UNUserNotificationCenterDelegate` tap handler in `iosMain`: read `type`/`target_type`/`target_id` from `userInfo` + `body_data.conversation_id` from `body_full`; route via the shared resolver → nav signal (symmetric with Android); inert without config.
- [x] 6.2 New Notification Service Extension target source: parse `body_full` (= `body_data`); read the App-Group preference (`group.id.nearyou.shared`); when ON and chat with non-null `preview`, rewrite the body to the preview; else leave the server-built private body. Keep `UserNotifications`/Firebase imports out of commonMain.
- [x] 6.3 iOS OS-grouping batching: set `thread-id` / collapse handling = `conversation_id` for OS-side merge (declare the best-effort count-merge limitation).
- [x] 6.4 iOS unit test (kotlin.test, `iosSimulatorArm64Test`) for the NSE body-rewrite ON/OFF/null-preview projection (pure logic extracted so it is testable without a device).

## 7. Vendor isolation + invariant guards

- [x] 7.1 Extend `FcmPushSourceGuardTest` (or successor) to assert no Firebase / `UserNotifications` / `NotificationCompat` import leaks into commonMain.
- [x] 7.2 Grep-guard: no hardcoded user-visible notification string literals in the display source (all via `:shared:resources` where commonMain-reachable; native-only copy documented).
- [x] 7.3 Log-sink guard test: assert no `actor_user_id` / `target_id` / `conversation_id` / message `preview` / raw FCM token reaches a log sink from the display + tap-routing paths (mirror the shipped `mobile-fcm-token-registration` token-log guard idiom; prose alone is insufficient).

## 8. Spec delta wiring (mobile-fcm-token-registration)

- [x] 8.1 Apply the RENAMED + MODIFIED delta to `mobile-fcm-token-registration` (flip "deferred" → "implemented by mobile-push-message-handling"); confirm no orphaned negative-guard scenario at archive time.

## 9. Strings + resources

- [x] 9.1 Add the type-keyed notification copy strings to `:shared:resources` (`docs/03` §163–176), incl. the private "Pesan baru dari {username}" form, the "{n} pesan baru dari {username}" batched form, and the "{username} mengirim sebuah postingan" null-preview fallback.

## 10. Verification + DoD

- [ ] 10.1 `./gradlew :mobile:app:testStagingDebugUnitTest` (+ `:iosSimulatorArm64Test` for the NSE logic) green; `assembleStagingDebug` green config-free.
- [ ] 10.2 Backend gate for the `:infra:fcm` MODIFY: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` (the dispatcher tests + lint) green.
- [ ] 10.3 Record manual-verification evidence per `docs/11` §5 DoD (config-free build + unit coverage; live device push verify recorded against #258 when provisioned).
- [ ] 10.4 Confirm the deferred-row follow-up [#431](https://github.com/aditrioka/nearyou-id/issues/431) (Settings preview toggle) + operator-setup [#430](https://github.com/aditrioka/nearyou-id/issues/430) are referenced in the PR body; file any runtime-prompt follow-up from task 1.2 if needed.
