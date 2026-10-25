## Why

The admin per-user profile (`GET /admin/users/{id}`) is where an operator answers "what has been done to this account?". Its history view reads only `admin_actions_log` rows with `target_type = 'user' AND target_id = {id}`. Two enforcement paths write their audit row against a different target:

- **Report-queue resolution.** `moderation_queue_resolved` is keyed to the queue row (`target_type = 'moderation_queue'`), and its `after_state` doesn't record the enforced author at all.
- **Appeal decisions.** `appeal_approved` / `appeal_rejected` are keyed to the appeal (`target_type = 'appeal'`). Approve records the appellant's `user_id` in `after_state`. Reject records the appellant nowhere, even though `admin-appeal-review` says the row references "the target user".

So a user banned, suspended, or shadow-banned from the report queue, or unbanned through an appeal, shows none of it in their profile history. The operator sees `is_banned = true` with no matching history row. Follow-up [#496](https://github.com/aditrioka/nearyou-id/issues/496) (2026-09-25 mobile + admin review, finding A1-C3).

## What Changes

- **Backend (audit writers).** Each of these rows now records the affected user in `after_state.user_id`, joining the convention `appeal_approved` and `deletion_request_expedited` already follow:
  - `ReportResolutionRepository.resolveQueueItem`: the three author resolutions (`suspend_author_7d`, `ban_author`, `shadow_ban_author`) add the resolved author's `user_id`. The content resolutions (`keep` / `hide`) don't, because they act on a post or reply, not on an account.
  - `AppealReviewRepository.reject`: adds the appellant's `user_id` to its `after_state`, as approve already does.
- **Backend (history read).** `UserProfileRepository.loadAdminActionHistory` widens its predicate to `(target_type = 'user' AND target_id = {id}) OR (after_state ->> 'user_id') = {id}`. It stays parameterized, newest-first, read-only, and joined to `admin_users`.
- **Schema (Flyway V41).** A partial expression index `admin_actions_after_user_idx ON admin_actions_log ((after_state ->> 'user_id')) WHERE (after_state ->> 'user_id') IS NOT NULL`. With it, the widened `OR` is served by a BitmapOr of this index and the V16 `admin_actions_target_idx`, not a sequential scan of the append-only, 1-year-retention log. Same reasoning as the V17 viewer index. The migration is additive, has no `NOW()` in its predicate, and uses `IF NOT EXISTS`.
- **Admin UI (`user-profile.peb`).** History chip tones extend to the new rows:
  - `moderation_queue_resolved` gets the punitive (`err`) tone. Only author resolutions reach a profile, and all of them are punitive.
  - `appeal_approved` gets the restorative (`ok`) tone.
  - Everything else keeps the existing `info` default.

  No new column or control (board frame 6 History card).
- **No backfill.** `admin_actions_log` is immutable (UPDATE/DELETE revoked for `admin_app`). Queue resolutions logged before this change keep their author-less `after_state`. They stay visible in the audit-log viewer but not on the profile. All existing data is pre-launch synthetic (design D4).
- **Docs.** `docs/05-Implementation.md` § Admin Actions Log Schema lists the shipped V17 + V41 indexes and the `after_state.user_id` convention.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `admin-user-management`: the history-view requirement adds rows whose `after_state.user_id` names the user, and must be index-served. It gains scenarios for queue enforcement, appeal approve and reject, a hand-off row, and two negative guards: content resolutions are excluded and other users' rows don't leak (MODIFIED).
- `admin-report-queue`: the moderation-queue resolution requirement's `after_state` records the resolved author's `user_id` for author resolutions only (MODIFIED).
- `admin-appeal-review`: the audit-row requirement pins the shape: `target_type = 'appeal'`, `target_id` = the appeal id, and `after_state.user_id` = the appellant for both approve and reject (MODIFIED).

## Impact

- **Schema**: `V41__admin_actions_log_after_user_idx.sql`: one `CREATE INDEX IF NOT EXISTS`, no data rewrite. CI runs the Supabase-parity Flyway migrate.
- **Backend code**: `admin/usermanagement/UserProfileRepository.kt` (predicate), `admin/reportqueue/ReportResolutionRepository.kt` (author `user_id`), `admin/appealreview/AppealReviewRepository.kt` (reject `user_id`). KDoc only in `admin/auth/AdminAuditLogger.kt`, `admin/routes/AdminUserModerationRoute.kt`, and `templates/admin/user-profile.peb`. No route, DTO, `AdminModule.kt`, or `Application.kt` change.
- **Wire / mobile**: none. This is an admin-only read surface (docs/12: the mobile and public-API layers are not applicable; see design D6).
- **Destructive-action rate limit**: unaffected. Its ledger filters on `action_type` + `after_state ->> 'resolution'`, and the extra `user_id` key changes neither.
- **Tests**:
  - `MigrationV41SmokeTest` (DB).
  - `ReportResolutionRepositoryTest` + `AppealReviewTest`: `after_state.user_id` present or absent per resolution.
  - `AdminUserModerationRouteTest`: profile history via the real queue and appeal write paths, the hand-off row, the negative guards, and an EXPLAIN index-served assertion.
- Closes #496.
