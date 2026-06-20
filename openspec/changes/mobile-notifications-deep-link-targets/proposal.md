## Why

The shipped in-app Notifications surface marks a row read on tap but **navigates nowhere** — tapping "Seseorang menyukai postinganmu" or "Pesan baru" is a dead end, breaking the notification → content engagement loop in the demo. That was a deliberate deferral: `mobile-notifications-list` carries a negative-guard requirement ("Tapping a row marks it read; **deep-link navigation is deferred**") written expressly so this follow-up has a requirement to MODIFY. It was blocked on two things — the post-detail screen and a backend by-id post endpoint — that are **now both shipped** (`mobile-post-detail-screen`, and the `single-post-read` `GET /api/v1/posts/{post_id}` endpoint, whose own spec says it exists "notably [for] notification deep-links into a post/reply"). Promotes follow-up issue [#193](https://github.com/aditrioka/nearyou-id/issues/193).

## What Changes

- **Flip the deferred deep-link guard**: tapping a notification row now, IN ADDITION to the unchanged optimistic mark-read, navigates to the notification's target. Navigation routes off the canonical outer `(target_type, target_id)` addressing pair + the actor (per `docs/05-Implementation.md` § Notifications):
  - `target_type='post'` (`post_liked`; `post_replied` → the parent post; `post_auto_hidden` resolving to a post) → fetch the post by id, then push the post-detail surface. A post that no longer resolves (deleted / blocked / shadow-banned / auto-hidden — the endpoint collapses all to one `404`) shows a **non-blocking "tidak tersedia" affordance, no navigation** (never an error screen).
  - `followed` (no target, actor present) → push the actor's profile (no fetch).
  - `chat_message` / `chat_message_redacted` (`target_type='message'`, `body_data.conversation_id`) → push the chat thread (no fetch).
  - `target_type='reply'` (the dynamic reply case of `post_auto_hidden`) → **non-navigating** this change (no reply-by-id → parent-post endpoint exists to build the post-detail route); captured as an explicit scope line + a deferred follow-up so a later change can MODIFY it.
  - All no-target informational types (`subscription_*`, `account_action_applied`, `data_export_ready`, `privacy_flip_warning`, `username_release_scheduled`, `apple_relay_email_changed`) → **non-navigating**, mark-read only (unchanged — no in-app actionable destination).
- **Reuse the shipped navigation seams**: the navigation-free `NotificationsScreen` (today invoked bare by the shell) gains hoisted nav callbacks; `AppShellScreen` forwards its existing `onOpenPost` / `onOpenProfile` seams and adds a `conversationId → ChatThreadRoute` push. No new NavKeys (`PostDetailRoute` / `ProfileRoute` / `ChatThreadRoute` already exist).
- **Add a full-projection single-post fetch** to the existing `SinglePostApiClient` (today it decodes only a minimal `content`/`editedAt`/`isAuthor` projection for post-detail refresh). The new path decodes the full shipped `single-post-read` wire (`id`, `authorUsername`, `authorDisplayName`, `content`, `cityName`, `createdAt`, `likedByViewer`, `replyCount`) and maps it to the host's `PostDetailTarget` (with `distanceM = null` — the by-id projection omits coordinates). `200` → success; `404` / any non-200 / IO → the existing graceful `Unavailable`.
- **PII discipline unchanged**: `actor_user_id` / `target_id` / `conversation_id` are used ONLY as route payload or API path params — never rendered in any UI node nor logged (the shipped diagnostic-carries-only-status rule holds).
- **No backend change** (the `GET /api/v1/posts/{post_id}` endpoint is shipped). **No Flyway migration.** **No new library** (reuses the existing Ktor client, NavKeys, and `SinglePostApiClient`).

## Capabilities

### New Capabilities

<!-- None. The client-side full-projection fetch exists only to serve this deep-link and is folded into mobile-notifications-list rather than spun out as a standalone capability (no other consumer; YAGNI — see design.md Decision on capability partition). -->

### Modified Capabilities

- `mobile-notifications-list`: MODIFY the "Tapping a row marks it read; deep-link navigation is deferred" requirement (RENAMED + MODIFIED) → active per-type deep-link navigation alongside the unchanged mark-read, with the negative-guard scenario converted to positive per-type navigation scenarios + the post-unavailable non-blocking-no-nav scenario + the reply/informational non-navigating scenarios. ADD the full-projection single-post fetch seam (the post-target deep-link's by-id resolver → `PostDetailTarget`) and the screen/shell hoisted-callback wiring requirements.

## Impact

- **Spec**: `openspec/specs/mobile-notifications-list/spec.md` (delta — RENAMED + MODIFIED + ADDED requirements).
- **Mobile (`:mobile:app`, `commonMain`)**: `screens/notifications/NotificationsScreen.kt` (hoisted nav callbacks + collect one-shot nav events + per-tap resolving / unavailable surface), `screens/notifications/NotificationsViewModel.kt` (per-type nav resolution + post-target fetch + nav-event emission; mark-read unchanged), `post/SinglePostApiClient.kt` (full-projection fetch + DTO + result → `PostDetailTarget` mapping), `screens/shell/AppShellScreen.kt` (stop invoking `NotificationsScreen()` bare; forward `onOpenPost`/`onOpenProfile`, add `onOpenChatThread`), `screens/routing/AppEntryProvider.kt` (wire the notifications chat-thread callback to a `ChatThreadRoute` push), `di/MobileModule.kt` (any new binding for the fetch seam).
- **Tests**: `NotificationsScreenTest` (Robolectric, per-type tap → callback assertions + 404 unavailable + no-PII-in-tree), `NotificationsViewModel` nav-resolution commonTest (over `FakeNotificationsFlow` + a fake full-projection fetch), `SinglePostApiClient` full-projection MockEngine test (path / bare-camelCase parse / 200·404·IO → outcome), iOS notifications flow test (Kotlin/Native-legal names). Release-variant test-exclude for the `*ScreenTest`.
- **No** backend, DB/Flyway, `gradle/libs.versions.toml`, or `docs/11` Pattern-Registry impact (reuses the state-holder + Navigation-3 hoisted-callback + ApiClient→Repository→sealed-Outcome patterns; no new pattern, no deviation).
