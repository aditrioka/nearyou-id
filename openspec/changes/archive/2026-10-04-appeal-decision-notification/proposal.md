## Why

An appeal decision (approve / reject) is today surfaced ONLY when the appellant happens to re-open the appeal screen and the own-status read (`GET /api/v1/appeals`) runs — `content-moderation-appeal` § "Decision outcome surfaced via status read, proactive notification deferred" explicitly deferred proactive delivery. An approved appellant has no signal that their account is usable again, and a rejected one never learns the outcome unless they go looking. Follow-up [#390](https://github.com/aditrioka/nearyou-id/issues/390) closes that deferral.

## What Changes

- **Backend (Flyway V40)** — widen the `notifications.type` CHECK (inline V10 constraint `notifications_type_check`) with one new value `appeal_decided` (13 → 14 values; additive DROP + ADD, the V36 `moderation_queue.trigger` precedent).
- **Backend (admin decision tx)** — `AppealReviewRepository.approve` / `.reject` write exactly one `appeal_decided` `notifications` row for the appellant **inside the same transaction** as the appeal transition + audit row, using the shipped admin-notification pattern (raw in-tx `INSERT`, `actor_user_id = NULL`). Row shape: `target_type = 'appeal'`, `target_id = <appeal id>`, `body_data = {"decision": "approved" | "rejected"}` (one key — the free-text `decision_reason` is NOT copied; it stays on the status read). An idempotent re-decision / not-found writes no row; a failed insert rolls back the whole decision.
- **Backend (read path)** — add `APPEAL_DECIDED("appeal_decided")` to the `NotificationType` enum. The list endpoint filters `type IN (NotificationType.entries)`, so without this the new row would be invisible in `GET /api/v1/notifications` while still counting toward the unread badge.
- **In-app only — no FCM push.** Operator decision 2026-10-04: no `PushCopy` case and no `NotificationDispatcher` call for `appeal_decided` (consistent with the shipped admin `account_action_applied` / `chat_message_redacted` behavior). `Application.kt` / `admin()` wiring is untouched.
- **Mobile (notifications list)** — decision-keyed copy for `appeal_decided` (approved / rejected / decision-absent fallback) via `:shared:resources`; a tap resolves `target_type = "appeal"` to the existing `AppealRoute` (new `onOpenAppeal` hoisted callback, wired in the shell; no new `NavKey`). The shared resolver is also consumed by the push-tap effect, which gains the matching branch.
- **Mobile (appeal screen)** — when no limited appeal token is held (the user arrived signed-in from a notification, not from the banned sign-in 403), the own-status read goes through the shared bearer-authenticated `HttpClient` (the backend appeal realm accepts a normal access token). An approved decision seen this way renders without the "masuk lagi" re-sign-in CTA (the user already holds a live session). A successful sign-in now drops any held appeal token (today it is never cleared), and "no token + no stored session" redirects without a network read.
- **Docs** — `docs/05` § Notifications Schema (CHECK DDL + V40 note + catalog row), `docs/02` event-type list, `docs/03` notification copy, `docs/08` risk-register enum count.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `content-moderation-appeal`: the deferral requirement becomes "decision outcome surfaced via status read AND an in-app notification" (RENAMED + MODIFIED); FCM push stays out (positive negative-guard).
- `admin-appeal-review`: approve / reject insert one `appeal_decided` notification atomically within the decision transaction (ADDED).
- `in-app-notifications`: `notifications.type` enum extended with `appeal_decided` (V40) + the `appeal_decided` emit site and `body_data` shape (ADDED); the V10 table requirement's stale written/reserved counts point at V40 instead (MODIFIED).
- `mobile-notifications-list`: deep-link resolution gains the `appeal` target; the screen exposes `onOpenAppeal`; `appeal_decided` renders decision-keyed copy (MODIFIED ×2, ADDED ×1).
- `mobile-appeal`: the appeal screen reached from a notification reads status through the signed-in session (ADDED).

## Impact

- **Schema**: `V40__notifications_type_appeal_decided.sql` (constraint swap only; no data rewrite). Supabase-parity Flyway migrate in CI.
- **Backend code**: `admin/appealreview/AppealReviewRepository.kt` (+ insert), `core/data/.../NotificationRepository.kt` (enum value). No route, DTO, or `Application.kt` change.
- **Mobile code**: `screens/notifications/{NotificationNavigation,NotificationsScreen,NotificationsUiState,NotificationsViewModel}.kt`, `screens/routing/{AppEntryProvider,AppShellScreen,PushTapNavigationEffect}.kt`, `appeal/{AppealApiClient,AppealFlow,AppealRepository}.kt`, `auth/AuthRepository.kt`, `screens/appeal/{AppealViewModel,AppealUiState,AppealScreen}.kt`, `di/MobileModule.kt`, `:shared:resources` strings.
- **Admin UI**: none — the emit rides the existing approve / reject actions; no new operator surface (declared in design).
- **Wire**: no new endpoint or response field; the existing notifications wire carries a new `type` value + `target_type = "appeal"`.
- **Tests**: `MigrationV40SmokeTest` (DB), `AppealReviewTest` (DB; the "no notification" negative guard flips to positive), notifications read-path test, mobile commonTest + Robolectric + iOS flow test.
- Closes #390.
