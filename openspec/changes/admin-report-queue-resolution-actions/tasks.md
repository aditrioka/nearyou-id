> **Scope note (design OQ1):** these tasks implement the recommended Scope A (bookkeeping write-back + intrinsic `keep`/`hide` visibility toggle; author/delete/shadow-ban **recorded-only** via the existing `/admin/users` deep-link). If proposal review selects the fuller in-row-enforcement scope (`docs/07-Operations.md:36`), revise §1–§4 before `/opsx:apply` lands the first feat commit.

## 1. Repository — atomic resolution write-back (NO Flyway migration)

- [ ] 1.1 Confirm NO migration is needed and add NONE: `reports.status`/`reviewed_by`/`reviewed_at` + `moderation_queue.status`/`resolution`/`resolved_by`/`resolved_at` (V9), the `admin_users` FKs (V16), and the free-form `admin_actions_log.action_type VARCHAR(64)` (V16) all already exist. No file SHALL be added under `backend/ktor/src/main/resources/db/migration/`.
- [ ] 1.2 Add a `ReportResolutionRepository` (or extend `ReportQueueRepository`) in `backend/ktor/.../admin/reportqueue/` with `resolveReport(reportId, decision, adminId, ip, userAgent)`: ONE transaction — conditional `UPDATE reports SET status=?, reviewed_by=?, reviewed_at=NOW() WHERE id=? AND status='pending'`; on 1 row affected write one `admin_actions_log` (`report_resolved`, before/after JSONB) in the SAME transaction; return a sealed outcome (`Applied` / `NoOpAlreadyResolved` / `NotFound`).
- [ ] 1.3 Add `resolveQueueItem(queueId, resolution, adminId, ip, userAgent)`: ONE transaction — conditional `UPDATE moderation_queue SET status='resolved', resolution=?, resolved_by=?, resolved_at=NOW() WHERE id=? AND status='pending'`; when the row's `trigger='auto_hide_3_reports'` AND `resolution IN ('keep','hide')`, apply the visibility toggle in the same transaction (`keep` → `UPDATE posts|post_replies SET is_auto_hidden=FALSE`; `hide` → leave TRUE; `user`/`chat_message` targets → no-op); write one `admin_actions_log` (`moderation_queue_resolved`); return the sealed outcome. The author/delete enforcement (`suspend_author_7d`/`ban_author`/`shadow_ban_author`/`delete`) records the enum only — NO `users`/`deleted_at` mutation (design D2 / spec § record-not-enforce).
- [ ] 1.4 Add `AdminAuditLogger.reportResolved(...)` + `moderationQueueResolved(...)` mirroring the existing `user_suspended`/`user_unbanned` writers (same `before_state`/`after_state` JSONB shape, `ip`/`user_agent`).

## 2. Routes — CSRF + write-role-gated POST endpoints (mirror AdminUserModerationRoute)

- [ ] 2.1 Add `AdminReportResolutionRoute.kt` in `backend/ktor/.../admin/routes/` — `POST /admin/reports/{id}/resolve`: `AdminCsrfGate.validateCsrf` FIRST → `AdminRoleGate.requireWriteRole` → parse `{id}` UUID (malformed → 400, no writes) → read `decision` via `AdminCsrfGate.formParametersAfterValidation` (reject out-of-enum without a write) → `principal<AdminPrincipal>()` → `repo.resolveReport` → outcome: success re-renders the re-queried fragment (HTMX) or 303 back to `/admin/reports` (no-JS); no-op/not-found re-renders with a message.
- [ ] 2.2 Add `POST /admin/moderation-queue/{id}/resolve` to the same route file — identical gate order; read + validate `resolution` against the 6 content/author values (`keep`/`hide`/`delete`/`shadow_ban_author`/`suspend_author_7d`/`ban_author`); reject out-of-set values incl. the out-of-scope `accept_flagged_username`/`reject_flagged_username` (owned by the future username-oversight feature) → no partial write, no audit, no 5xx; `repo.resolveQueueItem` → outcome handling as 2.1.
- [ ] 2.3 Wire both routes in `AdminModule.kt` INSIDE `authenticate(ADMIN_AUTH_NAME)` alongside `adminReportQueue` (so the session gate applies). Keep the bare `GET /admin/reports` collection unchanged (POST to the bare path stays 405).

## 3. Templates — in-row resolution controls (HTMX partial + no-JS fallback)

- [ ] 3.1 `templates/admin/reports-table.peb`: add a per-row resolution form — a report `decision` select (`actioned`/`dismissed`) and, when `hasQueue`, a queue `resolution` select (8-enum); each with the `_csrf` hidden field; `hx-post` + `hx-target` the table fragment for HTMX, plain `POST` action for no-JS. Include a short label clarifying that `suspend`/`ban`/`shadow_ban`/`delete` RECORD the decision — enforcement is via the per-row `/admin/users` deep-link (record-not-enforce).
- [ ] 3.2 `templates/admin/reports.peb`: ensure the full-page model carries the derived `csrfToken` into the controls (mirror `users.peb` / the existing `ReportQueueRoute` full-page path).
- [ ] 3.3 Verify Pebble autoescape covers every control value (no `| raw`); the read-viewer escaping pattern is reused.

## 4. Tests (Kotest — one per spec scenario; do NOT skip any)

- [ ] 4.1 Report status: a pending report → `actioned` and → `dismissed` each set `status`/`reviewed_by`/`reviewed_at` + write exactly one `report_resolved` audit row.
- [ ] 4.2 Queue resolution: a pending queue item resolved (e.g. `resolution=hide`) → `status=resolved` + `resolution` + `resolved_by`/`resolved_at` + exactly one `moderation_queue_resolved` audit row.
- [ ] 4.3 Visibility toggle: `keep` on an `auto_hide_3_reports` post → `is_auto_hidden=FALSE`; `hide` → stays `TRUE`; `keep` on a `user`-target row → no-op, resolution still succeeds.
- [ ] 4.4 Gating: unauthenticated `POST` → 302 `/admin/login` + no write; missing/invalid CSRF → 403 + `admin_csrf_violation` audit + no write; non-write-role admin → gated + no write; CSRF validated before the role gate.
- [ ] 4.5 Record-not-enforce negative guards: `resolution=ban_author` records the enum but leaves the author's `users` row unchanged (`is_banned` still FALSE, `token_version` unchanged); `resolution=delete` leaves the post's `deleted_at` NULL.
- [ ] 4.6 Malformed + idempotent + out-of-scope: malformed `{id}` → 400 + no write; out-of-enum `decision`/`resolution` → rejected, no partial write, no audit, not a 5xx; out-of-scope username resolution (`accept_flagged_username`/`reject_flagged_username`) → rejected with no write; re-resolving an already-`resolved` row → zero rows affected, no second audit row, existing `resolution`/`resolved_by` unchanged, no error.
- [ ] 4.7 In-row controls: a resolvable row renders the resolution form with a CSRF hidden field (inverts the read-viewer "no resolution control is rendered"); `reason_note` containing `<script>` is escaped; a successful no-JS `POST` → 303 back to `/admin/reports` preserving active filters; an `HX-Request` returns only the table fragment.
- [ ] 4.8 GET listing unchanged: `GET /admin/reports` writes no audit row + mutates nothing; bare `POST /admin/reports` (collection path) → 405.
- [ ] 4.9 Deferred edit-history filter unchanged: `GET /admin/reports?has_edit_history=true` → 200, parameter ignored.

## 5. Spec sync, docs, follow-ups

- [ ] 5.1 `openspec validate admin-report-queue-resolution-actions --strict` green (RENAMED + MODIFIED + ADDED deltas all resolve against `openspec/specs/admin-report-queue/spec.md`).
- [ ] 5.2 (archive-time) `openspec archive admin-report-queue-resolution-actions` → confirm `openspec/specs/admin-report-queue/spec.md` reflects the renamed/modified requirements + the new resolution requirements; `openspec validate --specs admin-report-queue --strict` green.
- [ ] 5.3 (archive-time) `docs/07-Operations.md` § Core Features "Report Queue": flip the in-row resolution actions from "Still DESIGN" to shipped (resolution write-back) — keep the "post has edit history" filter marked DESIGN (still deferred to `admin-report-queue-has-edit-history-filter`). Update the top-of-file Status (2026-…) line's "What remains DESIGN" to drop the resolution write-back.
- [ ] 5.4 (archive-time) `FOLLOW_UPS.md`: delete the `admin-report-queue-resolution-actions` entry (its action items are merged); leave `admin-report-queue-has-edit-history-filter` + `admin-destructive-action-rate-limit` intact.

## 6. Gate + pre-archive staging smoke

- [ ] 6.1 Pre-push gate (CLAUDE.md): `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` green locally before pushing feat commits (both lint frameworks, not just detekt).
- [ ] 6.2 (pre-archive) Manual staging deploy on the branch (`gh workflow run deploy-staging.yml --ref admin-report-queue-resolution-actions`) → smoke: log in to `api-staging.nearyou.id/admin`, open `/admin/reports`, resolve a seeded report + an `auto_hide_3_reports` queue item (verify `keep` un-hides the target), confirm exactly one `admin_actions_log` row per action and that a repeat resolve is a no-op; tick the Section 6 deploy tasks per `openspec/project.md` § Staging deploy timing.
