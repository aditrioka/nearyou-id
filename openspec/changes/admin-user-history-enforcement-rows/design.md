## Context

`admin-user-management` shipped the per-user profile page (#251). Its history view is `UserProfileRepository.loadAdminActionHistory`: `admin_actions_log JOIN admin_users WHERE target_type = 'user' AND target_id = ?`, newest-first, merged with `username_history` in `AdminUserModerationRoute.buildHistory`.

Later capabilities added account enforcement that isn't keyed to the user:

| Writer | `action_type` | `target_type` / `target_id` | `after_state` today |
|---|---|---|---|
| `ReportResolutionRepository.resolveQueueItem` (`admin-report-queue`) | `moderation_queue_resolved` | `moderation_queue` / queue id | `{resolution, <effect>}`, author **absent** |
| `AppealReviewRepository.approve` (`admin-appeal-review`) | `appeal_approved` | `appeal` / appeal id | `{status, lifted_ban, user_id}` |
| `AppealReviewRepository.reject` | `appeal_rejected` | `appeal` / appeal id | `{status}`, appellant **absent** |
| `DeletionQueueRepository.expedite` (`admin-hard-delete-queue`) | `deletion_request_expedited` | `deletion_request` / request id | `{scheduled_hard_delete_at, user_id}` |

Every other user-affecting writer (`user_*`, `system_unban_applied`, `system_privacy_flip_applied`, username-oversight, CSAM, subscription-grace) already targets `('user', <id>)`.

`admin_actions_log` is append-only. `docs/05` says UPDATE/DELETE are revoked for `admin_app`, and the destructive-action rate limiter reads this table as its ledger. The table's indexes are V16 `(admin_id, created_at)`, `(target_type, target_id)`, `(action_type, created_at)`, and V17 `(created_at DESC, id DESC)`. None covers a JSONB key.

## Goals / Non-Goals

**Goals:**
- A ban, suspension, or shadow ban applied from the report queue appears in the enforced author's profile history.
- An appeal approve (the unban) or reject appears in the appellant's profile history.
- The widened history read stays index-served as the log grows.

**Non-Goals:**
- **Backfilling pre-change rows.** See D4.
- **Content resolutions in a profile.** `keep` / `hide` act on a post or reply, not on an account. They stay in the audit-log viewer and the report queue, not on the author's profile. See D2.
- **Changing `target_type` of the queue or appeal rows.** Those rows stay keyed to the object the admin acted on, because the audit-log viewer, the report-queue trail, and the rate-limit ledger depend on that shape.
- **Report bookkeeping.** `report_resolved` (`actioned` / `dismissed`) applies no enforcement and names no user.
- **A rendered "via report queue" label or KPI strip.** The KPI strip is frame 6's "Usulan" element, out of scope. The source is already visible: the chip shows the action type, and the state disclosure shows `resolution`.

## Decisions

### D1 — Name the affected user in `after_state.user_id`; don't re-key the row
The rows keep their object target (`moderation_queue` / `appeal`) and add the affected account as `after_state.user_id`. That is the convention `appeal_approved` and `deletion_request_expedited` (whose spec already says "carry the affected `user_id`") already follow.
- The value is the canonical lowercase UUID string (`UUID.toString()`), the same encoding `target_id` uses.
- It goes in `after_state`, not `before_state`: the after-snapshot describes the result, and a single key location keeps the read predicate and the index to one expression.

*Alternatives rejected:*
- (a) A second audit row with `target_type = 'user'` per enforcement. It doubles the rate-limit ledger rows (the cap counts `moderation_queue_resolved` by `resolution`, so the extra row would be counted again, or would need a new exclusion arm). It also breaks "exactly one audit row" in both specs.
- (b) A new `subject_user_id` column. That is a schema change on an immutable table for one key, plus a second way to name the affected user next to the `user_id` key that two writers already use.
- (c) Resolving the author at read time by joining `moderation_queue` → posts / replies / chat. That is wrong once the content is hard-deleted, and it is a four-way join on a page view.

### D2 — Only author resolutions carry `user_id`
`resolveQueueItem` writes `user_id` = the resolved author for `suspend_author_7d`, `ban_author`, and `shadow_ban_author`. For `user` targets the author is the user itself; for `chat_message` targets it is the sender. `keep` and `hide` carry no `user_id`.
- The profile history is "admin actions against this account". A hide is an action against a post.
- Every `moderation_queue_resolved` row that reaches a profile is therefore punitive, which is what makes the single `err` chip tone in D5 correct.
- `appeal_rejected` does get `user_id`. It changes no state, but it is a decision about the account's enforcement, and the existing spec already requires the row to reference "the target user", which reject currently doesn't.

### D3 — Predicate + V41 partial expression index
The read becomes:

```sql
WHERE (l.target_type = 'user' AND l.target_id = ?) OR (l.after_state ->> 'user_id') = ?
```

Both parameters are the profile id as a string. A row can't appear twice, because the `OR` is evaluated per row.

V41 adds:

```sql
CREATE INDEX IF NOT EXISTS admin_actions_after_user_idx
    ON admin_actions_log ((after_state ->> 'user_id'))
    WHERE (after_state ->> 'user_id') IS NOT NULL;
```

- **Partial:** only the few rows that carry the key are indexed (queue author resolutions, appeals, deletion expedites), not the whole log, most of which is logins, CSRF events, and `user`-targeted rows.
- **The planner can use it with a bind parameter.** `(after_state ->> 'user_id') = $1` uses a strict operator, so Postgres proves it implies `IS NOT NULL`. A partial predicate of `after_state ? 'user_id'` would not work: the planner can't prove it from the equality, and a bare `?` collides with JDBC placeholders.
- **Invariants:** no `NOW()` in the predicate (partial-index invariant). `IF NOT EXISTS` keeps it re-runnable (V17 precedent; staging branch-deploys can re-apply).
- **No `CONCURRENTLY`:** Flyway runs each migration in a transaction, and the table is small pre-launch (V17 precedent).
- **Plan:** the two `OR` arms become a BitmapOr over `admin_actions_after_user_idx` and V16 `admin_actions_target_idx`. A DB test pins this by asserting no Seq Scan on `admin_actions_log` with `enable_seqscan = off` (the `SuspensionUnbanWorkerTest` 9.8 precedent).
- **Version:** V41 is the next free version. `origin/main` tops out at V40, and no open PR or sibling worktree holds a V41 at proposal time. Re-check before every push and `git mv` to the next free number on a collision.

*Alternative rejected:* a JSONB GIN index on `after_state`. It is larger, indexes every key, and the planner would only use it with `@>` containment, which reads worse than the scalar `->>` predicate.

### D4 — No backfill
Rewriting old `after_state` values would UPDATE an immutable ledger that `admin_app` can't write. A one-off owner-run backfill would still re-derive authors from content that may since have been hard-deleted. All current data is pre-launch synthetic (staging only), so the gap only affects rows written before this change.

The audit-log viewer still shows those rows in full. The profile shows enforcement from this change onward, and the profile's current-state block (`is_banned`, `suspended_until`, `is_shadow_banned`) stays authoritative regardless.

### D5 — Chip tones (look only)
`user-profile.peb` adds `moderation_queue_resolved` to the `err` list and `appeal_approved` to the `ok` list. `appeal_rejected` and `deletion_request_expedited` fall through to `info`. This mirrors the board's frame-6 History card: punitive actions get `err`, restorative ones get `ok`.

No layout or spacing changes. The row reuses the existing columns: time, chip, admin, reason, and the state disclosure. Behavior is spec-bound; the tone is styling and isn't a spec scenario.

### D6 — docs/12 vertical slice
| Layer | Status |
|---|---|
| backend | Writers + read + V41 |
| admin | `/admin/users/{id}` history; tone tweak only |
| mobile / public API | N/A: the profile history is an operator-only surface, no user-facing wire changes, and `user_id` stays inside `admin_actions_log` |

### D7 — Found-while
`docs/05` § Admin Actions Log Schema lists only the V16 indexes, but V17 `admin_actions_created_idx` shipped too. The schema block gains V17 + V41 and a one-line note on the `after_state.user_id` convention. This is a docs-only fix in the same file cluster.

## Risks / Trade-offs

- **[Old rows absent from the profile]** → Accepted (D4). Pre-launch synthetic data only. The audit-log viewer remains complete.
- **[A future writer puts a different meaning under `user_id`]** → That row would show on that user's profile. Mitigations:
  - The convention is now documented in `docs/05`, the `AdminAuditLogger` KDoc, and the `admin-user-management` requirement.
  - The key has meant "the affected account" in all four writers.
- **[Index write amplification]** → Negligible. The index is partial, so only the few `user_id`-bearing rows add an entry.
- **[Parallel Flyway collision]** → Re-check the latest V before each push (D3).
- **[`AdminAuditLogger.kt` is contested with #531/#532 and #277]** → This change touches it with KDoc only. Whoever merges second rebases.

## Migration Plan

1. V41 applies automatically on deploy (Flyway at boot). It is additive and re-runnable.
2. Run the staging branch-deploy smoke:
   - V41 is in `flyway_schema_history`.
   - `admin_actions_after_user_idx` exists with the expected predicate.
   - A queue `ban_author` on a synthetic user shows in `/admin/users/{id}`.
3. Rollback: revert the code. The index can stay; it's harmless, and dropping it would need a V42.
