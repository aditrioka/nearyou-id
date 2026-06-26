## Context

The push stack is shipped end-to-end **except the device-side render**. A reconciliation pass against the shipped `fcm-push-dispatch` spec established the exact wire (this is load-bearing — the earlier draft of this change mis-described it):

- **Android payload** (`fcm-push-dispatch` § "Android payload SHALL be data-only…") — data-only, `priority:"high"`, no `notification` block. Data keys: `type`, `actor_user_id` (UUID, empty-string when null), `target_type`, `target_id`, `body_data` (JSON-stringified, empty-string when null). **No actor username. No `body_full`.** The client builds the entire notification locally from these.
- **iOS payload** (`fcm-push-dispatch` § "iOS payload SHALL be alert + mutable-content…") — a server-built `notification` block (`title="NearYou"`, `body` per-type with the actor username already resolved via `ActorUsernameLookup`, e.g. `"bobby mengirim pesan"`), `aps.mutableContent=true`, and **one** custom data field `body_full` = JSON-stringified `body_data` (4 KB-clamped, codepoint-safe). The spec explicitly states the iOS payload "MUST NOT include the same data block as Android … other data routing is via `aps.category` etc., which is out of scope for this change" — i.e. **no `type`/`target_type`/`target_id` on iOS**.
- **`chat_message` `body_data`** (`in-app-notifications` § "body_data shape per emitted type") = `{conversation_id: <uuid>, preview: <string ≤80 cp OR null>}`. `preview` is the first 80 code points of the message content at emit time; `null` ⇒ embedded-only message ⇒ the client renders a localized "mengirim sebuah postingan" fallback. The deep-link address for chat is `body_data.conversation_id`.
- `mobile-notifications-list` already defines the canonical pure resolver `(type, target_type, target_id, actor_user_id, body_data) → destination` + the consumed-once nav-signal pattern (`docs/11` §2.2). The push tap must land on the **same** destinations.

Consequences that drove the decisions below: Android has everything it needs to **route**, but **no username** to render faithful copy; iOS has the username but **nothing to route a tap**. Closing both faithfully requires a small additive **backend MODIFY** to `fcm-push-dispatch` (operator confirmed the full cross-platform slice over deferring iOS routing). Constraints unchanged: vendor-SDK isolation (Firebase / platform notification APIs confined to platform source sets — `FcmPushSourceGuardTest`), no hardcoded UI strings, single-nav-pattern, and the PR #250 precedent that the code builds + unit-tests + assembles **without** operator config.

## Goals / Non-Goals

**Goals:**
- Render an incoming FCM push on Android (local notification) and iOS (alert + NSE body rewrite), default content-private.
- Route the notification tap to the same in-app destination the in-app list resolves to, on **both** platforms, via the existing resolver + consumed-once nav signal.
- Ship the additive backend payload fields that make a faithful, tappable cross-platform render possible (Android `actor_username`; iOS `type`/`target_type`/`target_id`), within the APNs 4 KB clamp.
- Ship the content-privacy preference store (default OFF) read by the Android render path and the iOS NSE.
- Per-conversation batching on the display side.
- Build + unit-test + assemble green without operator Firebase / App-Group config.

**Non-Goals:**
- The Settings **control row** that flips the preview preference — deferred (docs/12 §3 explicit requirement) to avoid a `SettingsScreen` conflict with in-flight PR #424. Store + safe default ship here.
- The Android `POST_NOTIFICATIONS` **runtime-prompt UX** — owned by the chat screen (#257, closed). This change declares the manifest permission only.
- Any schema / Flyway / API-endpoint change, or changes to the per-type **copy** the backend already builds for the iOS alert body (the NSE only optionally augments it).
- Live end-to-end device verification — gated on operator config (#258); covered by unit/Robolectric tests here.
- Reconciling the slight iOS-push vs in-app-list chat-copy wording difference ("{username} mengirim pesan" vs "Pesan baru dari {username}") — out of scope; the NSE does not rebuild the base copy.

## Decisions

### D1 — Extend the shipped `NearYouFirebaseMessagingService`, don't add a second service
An Android app registers exactly one `FirebaseMessagingService`. The shipped service bridges `onNewToken`; we add `onMessageReceived(RemoteMessage)`. A second service would fight over the same manifest intent-filter. Firebase imports stay in `androidMain` (invariant preserved).

### D2 — Android renders from `body_data` + `actor_username`; there is no `body_full` on Android
The Android handler reads `type`, `actor_username`, `target_type`, `target_id`, and parses `body_data`. Chat default body (preference OFF) = "Pesan baru dari {username}" (sender + non-content); preference ON = `body_data.preview` (already ≤80 cp server-side; `null` ⇒ localized "mengirim sebuah postingan" fallback). Non-chat copy is the type-keyed `docs/03` §163–176 form built from `actor_username` (already non-sensitive). **Alternative** (resolve the username via a render-time `GET /users/{id}` network call) rejected — fragile/slow inside a high-priority handler and offline-brittle; the backend already computes the masked username for iOS, so providing it to Android (D6) is cheaper and robust.

### D3 — iOS: server builds the base body; the NSE only augments with the preview when ON
The server already sends a complete username-bearing private alert body. The NSE parses `body_full` (= `body_data` JSON) and, **only when** the App-Group preference is ON and the notification is a chat message with a non-null `preview`, rewrites the visible body to surface the preview; otherwise the server's private body stands. **Alternative** (silent push + app-built notification) rejected — iOS throttles silent push (`docs/04` §469); (NSE rebuilds the whole body) rejected — the server copy is already correct and username-bearing, the NSE's only job is the preview augmentation `docs/04` §491 describes.

### D4 — Content-privacy preference: commonMain seam, DataStore (Android) + App-Group UserDefaults (iOS), default OFF
A Compose-free commonMain `interface NotificationContentPreference { suspend fun previewEnabled(): Boolean; suspend fun setPreviewEnabled(v: Boolean) }`. Android actual = DataStore (pinned by `mobile-auth-google-signin-flow`); the Android render reads it directly. iOS actual = `UserDefaults(suiteName:"group.id.nearyou.shared")` so the **NSE (a separate process) can read it** — the NSE cannot reach the app sandbox/DataStore, only the App Group. Default **OFF** (private) when unset. **Alternative** (one cross-platform store) rejected — the iOS NSE process boundary forces the App-Group suite.

### D5 — Per-conversation batching: persisted last-push timestamp keyed by `conversation_id`
Android `onMessageReceived` may cold-start per message, so an in-memory window is unreliable. Persist a per-conversation `last_push_at` (DataStore): within 10 s of the last push for a `conversation_id`, **replace** the existing notification (reuse `conversation_id` as the notification **tag**) with "{n} pesan baru dari {username}" and suppress a fresh alert sound; outside the window post fresh. Non-chat uses a per-`(type, target_id)` tag so unrelated notifications never overwrite. iOS uses APNs `thread-id`/collapse = `conversation_id` for OS-side grouping; cross-invocation count-merge in the NSE is **best-effort** (no durable counter) and declared a known iOS limitation. **Alternative** (no batching) rejected — `docs/04` §484–486 requires it; (server-side batching) rejected — batching is a display concern and the dispatch is per-row.

### D6 — Backend MODIFY to `fcm-push-dispatch`: add iOS routing fields + Android `actor_username`
Two additive payload fields, both reusing existing seams, both within the 4 KB clamp:
- **iOS**: add `type`, `target_type`, `target_id` as custom APNs data fields (delivered in `userInfo` at tap time) so the iOS `UNUserNotificationCenterDelegate` can feed the shared resolver. This relaxes the spec's "MUST NOT include the same data block as Android" line — the now-shared routing fields are exactly what tap deep-linking needs; `body_full` remains the NSE's body source.
- **Android**: add `actor_username` (via the existing `ActorUsernameLookup` from `visible_users`, with the generic-fallback masking — "Seseorang …" — already used for the iOS body) so Android renders faithful copy without a network call. For chat (shadow-ban suppressed entirely at the emit site) and public-engagement (generic-fallback) the masking already applies; this field just surfaces it to the Android client.

**Alternative** (defer iOS tap routing entirely, declare it a docs/12 §3 deferred layer) was the other option offered; operator chose the full slice. **Alternative** (encode routing in `aps.category` only) rejected — explicit `type`/`target_type`/`target_id` data fields are symmetric with Android, reuse the same resolver inputs, and avoid a category-string vocabulary. Both additions keep the APNs payload well under 4 KB (a UUID-pair + a short type string).

### D7 — Tap routing reuses the `mobile-notifications-list` resolver on both platforms; no second nav pattern
Android attaches a tap `PendingIntent`→`MainActivity` carrying the routing fields as extras; iOS reads them from `userInfo` in the delegate. Both feed the **same** pure resolver + consumed-once nav signal (`docs/11` §2.2). **Alternative** (a push-specific nav path) rejected as the patchwork second pattern docs/11 forbids. Ids travel as opaque extras/route params — never rendered or logged.

### D8 — Declare `POST_NOTIFICATIONS`; runtime prompt stays the chat screen's
Android 13+ drops notifications silently without the granted runtime permission. This change adds the manifest `<uses-permission>` so display works once granted. The runtime **prompt** is #257 (closed, owned by the chat screen) — preflight verifies it actually ships; if not, a thin follow-up is filed. The render code is correct regardless.

### D9 — Graceful no-op without operator config (PR #250 precedent)
Without `google-services.json` / the `google-services` plugin, the Android SDK never invokes `onMessageReceived`; unit tests exercise the handler directly. The iOS NSE / delegate are inert without `GoogleService-Info.plist` + APNs key + the App-Group entitlement. Build, unit tests, and `assembleStagingDebug` stay green. iOS NSE Xcode target + App Group + provisioning are operator setup (preflight).

## Risks / Trade-offs

- **iOS NSE process can't read app DataStore** → preference invisible to the rewrite. *Mitigation:* D4 mirrors the preference into the `group.id.nearyou.shared` App-Group suite; the app writes on every toggle.
- **`POST_NOTIFICATIONS` not granted (Android 13+)** → pushes silently dropped. *Mitigation:* manifest declared here; runtime prompt is the chat screen's (#257); preflight-verified.
- **iOS NSE count-merge batching is best-effort.** *Mitigation:* OS grouping via `thread-id`/collapse = `conversation_id`; declare the limitation rather than over-claim. Android batching is durable.
- **Backend MODIFY re-touches a shipped capability** (`fcm-push-dispatch`) and crosses into the backend lane. *Mitigation:* additive-only fields (no removed/renamed keys), within the 4 KB clamp; new dispatcher tests assert the clamp still holds + the new fields present; the change is the iOS capability's dependency, not opportunistic backend work.
- **Operator config absent in CI / this environment** → no live push verify. *Mitigation:* PR #250 precedent — unit/Robolectric coverage; live verify recorded against #258 once provisioned.
- **`body_data` shape extended by in-flight `chat-embedded-posts` (#423).** *Mitigation:* the client reads only `conversation_id` + `preview` and tolerates added keys (forward-compat per `in-app-notifications` §286); no conflict.
- **Deferring the Settings row** → users can't enable preview until the follow-up lands. *Trade-off accepted:* the private default is the safe, spec-correct behavior; the row is additive and conflict-free once #424 merges.

## Migration Plan

Additive on both layers; no data migration. The backend payload fields are additive — older clients ignore unknown data keys, so the dispatcher MODIFY can deploy independently of the client. Rollback = revert the PR (no schema/state change; the preference store is new and unread elsewhere). The iOS NSE target + App-Group entitlement are operator Xcode steps sequenced before the first iOS store build that needs push (tracked in preflight, not blocking merge).

## Open Questions

- Does the shipped chat screen already request `POST_NOTIFICATIONS` at runtime (#257 closed)? Preflight resolves; if not, a thin runtime-prompt follow-up is filed (does not block this change's render code).
- App-Group identifier — `group.id.nearyou.shared` is the `docs/04` §490 canonical; confirmed against existing iOS entitlements at apply time.
