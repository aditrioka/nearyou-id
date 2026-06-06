## Context

The read-only Report Queue (`admin-report-queue`, [PR #154](https://github.com/aditrioka/nearyou-id/pull/154)) renders `reports` ⟕ `moderation_queue` newest-first with filters + a per-row deep-link to `/admin/users`, but writes nothing. Its spec positively defers the write-back: *"Report resolution write-back … SHALL ship as the separate change `admin-report-queue-resolution-actions`."* `docs/07-Operations.md` § Core Features "Report Queue" describes the deferred in-row resolution actions as **"Hide, Dismiss, Suspend, Ban, Shadow ban — today reachable only via the deep-link."** This change ships those **in-row actions that PERFORM the moderation enforcement** (not merely record a decision), closing the moderation loop entirely in-panel. The read→write fast-follow mirrors `admin-actions-log-viewer` (read) → `admin-user-moderation` (write).

The schema is fully shipped — **no Flyway migration**:
- `reports.status` ∈ {`pending`,`actioned`,`dismissed`}; `reviewed_at`, `reviewed_by` (FK→`admin_users` SET NULL, V16). [`V9:45-73`]
- `moderation_queue.status` ∈ {`pending`,`resolved`}; `resolution` ∈ {`keep`,`hide`,`delete`,`shadow_ban_author`,`suspend_author_7d`,`ban_author`,`accept_flagged_username`,`reject_flagged_username`}; `resolved_at`, `resolved_by` (FK→`admin_users` SET NULL, V16). [`V9:75-111`]
- `users.is_banned` / `suspended_until` / `is_shadow_banned` / `deleted_at` (V2); `posts.is_auto_hidden` (V4) + `post_replies.is_auto_hidden` (V8).
- `admin_actions_log.action_type` `VARCHAR(64)`, **no CHECK** → new free-form action types need no migration. [`V16:102-114`]

The precedents to reuse/mirror: `UserModerationRepository.suspend/unban` (`admin-user-moderation`) — single-connection `autoCommit=false` transaction, `SELECT … FOR UPDATE` guards (soft-deleted → reject, already-permanently-banned → reject the suspend), the audit row + the sanitized `account_action_applied` notification joining the same connection, role tiering (lifting a *permanent* ban is owner/admin only; `moderator` is rejected). `AdminUserModerationRoute` for the route shape (`AdminCsrfGate.validateCsrf` first → `AdminRoleGate.requireWriteRole` → parse UUID → `formParametersAfterValidation` → repo txn → outcome). The Report Queue's existing offending-user resolution (`post`→author, `reply`→author, `user`→self, `chat_message`→sender, via `LEFT JOIN`) is reused to find the enforcement target.

> **IMPORTANT correction (from review):** the V9 auto-hide is an **application-level conditional `UPDATE posts/post_replies SET is_auto_hidden = TRUE` inside `ReportService`** (run in the same transaction as the report INSERT — see `V9` header lines 32-42), **not** a DB trigger. The `keep` un-hide is the inverse of that application-level writer.

## Goals / Non-Goals

**Goals:**
- Let an authenticated, suitably-roled admin resolve a `moderation_queue` item *and perform the enforcement it names*, in one transaction with exactly one `admin_actions_log` row: `keep` (un-hide), `hide` (hide), `suspend_author_7d` (suspend the author — reusing the shipped path), `ban_author` (permanently ban the author), `shadow_ban_author` (shadow-ban the author).
- Let an admin transition a report's bookkeeping status (`pending → actioned | dismissed`) with `reviewed_by`/`reviewed_at` + one audit row.
- Render in-row controls in the report-queue table (HTMX-partial + no-JS fallback, HTML-escaped) that make clear these are **immediate destructive actions**.
- Be atomic (enforcement + resolution + audit commit-or-rollback together) and idempotent (re-resolving a non-`pending` row is a safe no-op).

**Non-Goals:**
- **`delete` (content soft-delete)** — NOT in the `docs/07-Operations.md:36` in-row action set ("Hide, Dismiss, Suspend, Ban, Shadow ban"); `hide` is the canonical in-row content removal. `resolution=delete` is rejected by this endpoint (a content-soft-delete admin surface, if ever wanted, is a separate change).
- **Username-moderation resolutions** `accept_flagged_username` / `reject_flagged_username` (+ the `username_flagged` trigger) — owned by the future Premium Username Change Oversight feature (`docs/07-Operations.md` § Core Features) with its own 10/hour limit + override-on-resubmit + manual-handle-release semantics. Rejected by this endpoint.
- **The "post has edit history" prioritization filter** (`admin-report-queue-has-edit-history-filter`).
- **The per-admin destructive-action rate limit** (`admin-destructive-action-rate-limit`) — see Risks; full enforcement RAISES the urgency of that follow-up, but the limiter is its own substrate decision and ships separately (consistent with suspend/unban having shipped without it).
- **`token_version` bump on ban/suspend** — the shipped `suspend`/`unban` do NOT touch `token_version`; this change mirrors that exactly (no new session-revocation behavior; if session-kick-on-ban is wanted, add it uniformly in a separate change).
- Any new Flyway migration, new capability spec, or new library.

## Decisions

### D1 — Extend the `admin-report-queue` capability (no new capability)
Same route subtree, templates, and CSRF/role machinery; the read-viewer's deferred requirement names this change as the write-back lander. The delta RENAMEs+MODIFIEs the two now-stale requirements and ADDs the resolution requirements. (Shadow-ban as a *primitive* lives in `docs/06`; this change is the first admin surface that sets `is_shadow_banned`, but it does so as one bounded write under the report-queue capability rather than introducing a separate shadow-ban capability — flagged in Risks as a new-surface to scrutinize.)

### D2 — Resolution PERFORMS enforcement (the scope chosen at review — OQ1 = full in-row enforcement)
The `moderation-queue` resolution endpoint applies the enforcement named by the `resolution` value, atomically with the bookkeeping write + audit row. Enforcement matrix (the accepted set is exactly the `docs/07-Operations.md:36` list):

| `resolution` | docs/07 label | enforcement effect | user notified? | role tier |
|---|---|---|---|---|
| `keep` | Dismiss | content un-hide: target `is_auto_hidden = FALSE` (`post`/`reply`) | no | write |
| `hide` | Hide | content hide: target `is_auto_hidden = TRUE` (`post`/`reply`) | no | write |
| `suspend_author_7d` | Suspend | **reuse** `UserModerationRepository.suspend` on the resolved author (7-day, `account_action_applied` notification, soft-deleted/already-permabanned guards) | yes | write |
| `ban_author` | Ban | permanent ban the resolved author: `is_banned = TRUE`, `suspended_until = NULL` + `account_action_applied` notification | yes | **owner/admin only** |
| `shadow_ban_author` | Shadow ban | shadow-ban the resolved author: `is_shadow_banned = TRUE` | **NO — stealthy (critical)** | write |
| `delete` / `accept_flagged_username` / `reject_flagged_username` | — | **rejected** (Non-Goals) | — | — |

Author resolution for the three author actions: `post`→author, `reply`→author, `user`→self, `chat_message`→sender (the same resolution the read viewer uses for the deep-link). A hard-deleted target whose author can't be resolved → the action is rejected (no write).

**Content resolutions (`keep`/`hide`) require `target_type ∈ {post, reply}`** (the tables with `is_auto_hidden`); for `user`/`chat_message` targets they are not applicable → rejected with a message (no write).

**`ban_author` is owner/admin-only**, mirroring the existing rule that lifting a *permanent* ban is owner/admin (`UnbanOutcome.ForbiddenPermanentBan` rejects `moderator`): if a moderator cannot *lift* a permanent ban, they cannot *issue* one. A `moderator` selecting `ban_author` → rejected, no write.

### D3 — Two endpoints, matching the two-table / two-cardinality reality
- `POST /admin/reports/{reportId}/resolve` with `decision ∈ {actioned, dismissed}` → `reports.status`/`reviewed_by`/`reviewed_at` for that one report (bookkeeping; performs no user/content enforcement).
- `POST /admin/moderation-queue/{queueId}/resolve` with `resolution ∈ {keep, hide, suspend_author_7d, ban_author, shadow_ban_author}` → `moderation_queue.status='resolved'` + `resolution` + `resolved_by`/`resolved_at` **and** the D2 enforcement.

Both wired inside `authenticate(ADMIN_AUTH_NAME)` alongside `adminReportQueue`. *Alternative considered:* a single report-centric endpoint with an optional resolution — rejected: `moderation_queue` rows are keyed `(target_type, target_id, trigger)` and *many reports map to one queue row*; resolving the shared queue item through an arbitrary report id conflates the cardinalities. **OQ2 (cascade) → no for v1:** resolving a queue item does NOT auto-transition its same-target `pending` reports (captured as a negative-guard spec scenario so the deferral is explicit and a follow-up has something to MODIFY).

### D4 — Atomicity: enforcement + resolution + audit in one transaction
Each resolution opens one `autoCommit=false` connection: `SELECT … FOR UPDATE` the target/author, evaluate guards, perform the enforcement UPDATE, the queue-status UPDATE, the audit INSERT (and, for suspend/ban, the notification INSERT) — then commit. Any failure rolls back everything (no enforcement without its audit row; no queue marked resolved without the enforcement). For `suspend_author_7d` the existing `UserModerationRepository.suspend` already encapsulates this transaction shape; the queue-status UPDATE + the queue-side audit join that same transaction (or the resolution wraps suspend's connection). The suspend reuse means the suspend-guard outcomes (`RejectedSoftDeleted`, `RejectedPermanentBan`) surface as resolution rejections: **if enforcement is rejected by a guard, the queue is NOT marked resolved and no audit row is written** — the moderator sees the rejection and picks another resolution.

### D5 — Idempotency via conditional UPDATE on the pending precondition
`UPDATE … WHERE id=? AND status='pending'`; zero rows affected → already resolved (or gone) → benign no-op, **no enforcement performed**, no audit row, re-render with a message, never a 5xx. This serializes the two-admins-resolve-the-same-row race (the loser is a no-op) and makes re-submission safe. The enforcement is gated behind the successful `pending→resolved` transition so a double-submit cannot double-ban.

### D6 — Gate order mirrors `admin-user-moderation`: CSRF first, then role
`AdminCsrfGate.validateCsrf(call, auditLogger)` (403 + `admin_csrf_violation` on miss) → `AdminRoleGate.requireWriteRole` → parse path UUID → read `decision`/`resolution` via `AdminCsrfGate.formParametersAfterValidation` (the body the gate already consumed) → `principal<AdminPrincipal>()` for `adminId`/`role`. The **`ban_author` owner/admin tier** is enforced INSIDE the repository transaction (like `unban`'s permanent-ban tier) so the rejection writes nothing. **CSRF is validated before the role gate** (a non-write-role request with a bad CSRF token gets the CSRF rejection, not a role rejection).

### D7 — Server-side enum allowlist (never a DB-CHECK 5xx)
The route validates `decision`/`resolution` against an in-code allowlist (`decision ∈ {actioned,dismissed}`; `resolution ∈ {keep,hide,suspend_author_7d,ban_author,shadow_ban_author}`) and rejects anything else (out-of-enum garbage, the out-of-scope `delete`/`accept_flagged_username`/`reject_flagged_username`) BEFORE the repository call — re-rendering with a message, never letting a value reach the DB `CHECK` and surface as a 500 (the "never 5xx" contract).

### D8 — Dual-mode render + destructive-action controls, fully escaped
In-row controls in `reports-table.peb` (per-row `POST` form: `_csrf` hidden field + a `decision`/`resolution` selector). The full-page render derives `csrfToken` via `HashUtil.deriveCsrfFromSessionToken` (mirroring `ReportQueueRoute`/`AdminUserModerationRoute`). Controls render under HTMX (fragment swap) and plain `POST` (303 redirect back to the filtered queue). Because these are **immediate destructive enforcement actions** (not "record only"), the controls carry explicit affordance copy (e.g. a confirm step / clear labels that Suspend/Ban/Shadow-ban take effect immediately). Pebble autoescape covers every value (incl. `reason_note`).

### D9 — New free-form audit `action_type` values + the enforcement in `after_state`
`report_resolved`, `moderation_queue_resolved` (free-form, no migration). The `after_state` JSONB records both the resolution value AND the enforcement effect (e.g. `{"resolution":"ban_author","is_banned":true,"suspended_until":null}`) so the audit trail is self-describing — an auditor can see *what was enforced*, not just *what was decided* (review NB-1). `shadow_ban_author` writes its audit row but **inserts no user-facing notification** — the absence is the stealth invariant, asserted by a negative-guard scenario.

## Risks / Trade-offs

- **Full enforcement = real, higher-stakes destructive power with NO rate limit yet.** A phished admin session (the project's own `docs/07` threat model) can now mass-ban/shadow-ban in-panel at unbounded rate. → The `admin-destructive-action-rate-limit` follow-up should be sequenced **immediately after** this change (its urgency rises materially vs the reversible suspend/unban that shipped without it). Interim mitigations: CSRF, 30-min idle + absolute session caps, IAP network gate, immutable audit trail, and reversibility (suspend→unban; shadow-ban→clear flag; ban→unban via the owner/admin tier). **Recommend** the rate-limit be the very next admin pick.
- **Shadow-ban is a NEW admin primitive** (first surface that sets `is_shadow_banned`). Its one hard invariant — **no user-facing notification** — is the difference between a shadow-ban and a visible ban; a regression that notifies would silently defeat it. Guarded by a negative-guard scenario (no `notifications` row written) + the audit row records the action for accountability.
- **Scope is larger than a minimal write-back** (it spans content-visibility + three author-enforcement actions, two of them new). Mitigated by reusing `UserModerationRepository.suspend` and keeping ban/shadow-ban to single bounded `users` UPDATEs; `delete` + username resolutions held out. *If the reviewer prefers a smaller PR, a clean split is: ship `keep`/`hide` + `suspend_author_7d` (reuse) here, fast-follow `ban_author` + `shadow_ban_author`.* (Offered to the user; default is to ship all five.)
- **Many reports → one queue row.** Resolving the queue item enforces once; sibling `pending` reports stay pending (OQ2 no-cascade). D5 idempotency makes re-resolution safe; v1 requires explicit per-report dismissal.
- **Overlap with `admin-user-moderation`.** Suspend is *reused* (not reimplemented); ban mirrors its transaction shape + role tier. No divergent second suspend path.

## Migration Plan

No Flyway migration (all columns/enums/FKs at V9/V16; `action_type` free-form). Code-only (resolution route + repository [reusing `UserModerationRepository.suspend`, adding `banAuthor`/`shadowBanAuthor`/content-toggle] + `AdminAuditLogger` methods + template edits + `AdminModule` wiring). Rollback = revert. Pre-archive staging smoke: log in, resolve a seeded `auto_hide_3_reports` post with `keep` (verify un-hide) and `hide`; suspend/ban/shadow-ban a seeded author (verify `users` state + that shadow-ban writes NO notification while suspend/ban DO); confirm one audit row per action + idempotent re-resolution + the owner/admin gate on `ban_author`.

## Open Questions

- **OQ2 (cascade):** when a queue item is resolved, auto-transition its same-target `pending` reports? Recommended **no** for v1 (explicit, no surprising bulk writes); captured as a negative-guard scenario.
- **Session revocation on ban:** the shipped suspend/unban don't bump `token_version`; this change mirrors that (no session kick). If immediate session revocation on permanent ban is desired, add it uniformly across suspend+ban in a separate change (flagged, not assumed).
