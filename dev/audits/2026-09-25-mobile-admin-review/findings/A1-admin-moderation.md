# A1 — admin moderation surfaces

### A. Completion matrix
| Capability | Reqs | Completion | Biggest gap |
|---|---|---|---|
| admin-report-queue | 23 | ~97% | listing driven by `FROM reports r` (`ReportQueueRepository.kt:103`) → report-less `moderation_queue` rows never surface (C2); #191 filter deferred by design |
| admin-appeal-review | 4 | ~65% | role gate wrong (C1): GET ungated, approve/reject use `requireWriteRole` (`AdminAppealReviewRoute.kt:48,64,86`) vs spec owner/admin; no route test for reject/role gate |
| admin-user-moderation | 26 | ~99% | — (`AdminUserModerationRouteTest`, `UserModerationRepositoryTest`) |
| admin-user-management | 7 | ~95% | history reads only `target_type='user'` (`UserProfileRepository.kt:74`) → queue-resolution bans + appeal unbans missing (C3) |
| admin-block-registry | 11 | 100% | #279 index (deferred); username links still `?q=` (`AdminBlockRegistryRouteTest.kt:89`) |
| admin-chat-message-redaction | 7 | 100% | #315 push (out of scope) |
| admin-csam-detection-log | 6 | 100% | — (`AdminCsamRouteTest.kt:109-842`) |
| admin-moderation-wordlist-editor | 12 | 100% | — |
| admin-post-edit-history | 10 | 100% | — |
| admin-rejected-identifiers-viewer | 19 | 100% | — |
| admin-actions-log-viewer | 10 | 100% | — (`action_type` free-text filter) |
| moderation-queue (admin-facing) | 6 | ~90% | "No reader endpoint in V9" stale; 3 trigger writers have no admin consumer (C2) |
| content-moderation-appeal (admin-facing) | 8 (1 admin) | ~85% | "no proactive notification on decision" implemented but no negative-guard test (C5) |
| csam-detection (admin-facing) | 7 (1 admin) | stale | § "Admin CSAM review surface … deferred" contradicts shipped `/admin/csam` (C6) |
| auth-login-anomaly-detection (admin-facing) | 7 (1 admin) | conforms | admin surface deferred by design → #438 |

Invariants: CSRF-first on every mutating route ✓ (reports/queue resolve, user actions, appeals, redact, wordlist, rejected-ids clear, CSAM); every applied write logs `admin_actions_log` ✓; admin FKs `ON DELETE SET NULL` on operational tables (`V16:124,135,151`, `V24:54`, `V34:31`), `admin_actions_log.admin_id` NO ACTION by documented design (`V16:33`) ✓; raw-table reads confined to `id.nearyou.app.admin.*` ✓.

### B. Follow-up validation
| # | Classification | Evidence | Scope |
|---|---|---|---|
| 191 | still-valid-openspec | no `has_edit_history`/`post_edits` predicate (`ReportQueueRepository.kt:44-85`); spec deferral in force; only per-row "Lihat riwayat edit" link (`reports-table.peb:81`) | parse `has_edit_history=true` in `ReportQueueRoute.kt`/`parseReportFilters`; `EXISTS (SELECT 1 FROM post_edits pe WHERE r.target_type='post' AND pe.post_id=r.target_id)` (served by `post_edits_post_id_idx`, `V22:29`); checkbox in `reports.peb`, echoed + carried through cursor + resolution round-trip. MODIFY admin-report-queue § "The post-edit-history prioritization filter is deferred". Repo + route tests (narrows, ANDs, pagination keeps filter) |
| 392 | still-valid-regular-pr | no appeal frame among 23 (`nearyou-admin-mockup.html:332-1556`); footer `:1573` "belum didesain"; design foundation landed (`archive/2026-06-12-admin-mockup-parity`), `appeals.peb` already uses `rcard`/`st`/`btn` | add frame "Appeal Review" (`GET /admin/appeals`): pending card, appellant link, action_type chip, inline text, Approve/Reject + reason; reconcile `appeals.peb`; update README frame count. Docs/mockup only |
| 393 | still-valid-defer | no redaction columns in `V34__appeals.sql`; text escaped only | Trigger: first observed PII/doxxing in `appeal_text`, or pre-launch UU PDP review. Then openspec (redacted_at/by/reason migration, owner/admin + CSRF redact POST under destructive cap + audit row) |
| 438 | still-valid-openspec | no admin reader of `anomaly_detection` rows; writer `LoginAnomalyRepository.kt:147` | build as generic **report-less moderation-queue viewer** (C2): `GET /admin/moderation-queue` keyset `(created_at,id)`, trigger/target_type/status filters, escaped `notes`; reuse `POST /admin/moderation-queue/{id}/resolve` (already accepts user targets). New capability + MODIFY auth-login-anomaly-detection § "The admin anomaly-review surface is explicitly deferred" |
| 279 | still-valid-defer | only `user_blocks_blocked_idx` (`V5__user_blocks.sql:27`) | Trigger: prod `user_blocks` cardinality or unfiltered `/admin/blocks` latency. Optional deep-link sibling doable now (`/admin/users/{id}` shipped) |
| 315 | still-valid-defer | spec: "no FCM push … out of scope" (admin-chat-message-redaction § "Redaction notifies active conversation participants"); `PushCopy.kt:59-64` no case | Trigger: operator product decision |

### C. New gaps
1. **[high] Moderators can approve appeals, and approval lifts permanent bans; read-only admins can view the appeal queue** — admin-appeal-review § "Admin appeals-review queue" (owner/admin) + admin-user-moderation § "State-changing actions are role-gated; lifting a permanent ban is restricted to owner/admin"; archived design/tasks 4.2 say owner/admin gate — `AdminAppealReviewRoute.kt:48` (GET no role gate), `:64,:86` `requireWriteRole` (includes moderator, `AdminRoleGate.kt:24`); `AppealReviewRepository.kt:119` unbans unconditionally; permanent-ban appeals accepted (`AppealEndpointsTest.kt:223`) incl. CSAM-banned uploaders; direct moderator path correctly blocked (`UserModerationRepositoryTest.kt:208`) — appeal path bypasses it; `AppealReviewTest.kt` has no role test — regular-pr (`requireOwnerOrAdmin` on GET + both POSTs; moderator/read_only 403 tests)
2. **[high] Report-less `moderation_queue` rows are invisible → nobody can review or resolve them** — writers: `uu_ite_keyword_match` post/reply/chat (`JdbcModerationQueueRepository.kt:49`), `perspective_api_high_score` + AutoHide (`JdbcLayer3ModerationWriter.kt:121`), `area_spam` (`:114`). Specs promise admin review: post-area-density-cap § "Area-density routing is silent to the poster" ("the only observable effect is the moderation_queue row visible to admins"), text-moderation-perspective-api-layer ("visible until an admin reviews it"). Queue view starts from `reports` (`ReportQueueRepository.kt:103`); `ReportQueueRepositoryTest.kt:275-285` must seed a report to make `area_spam` show. Consequence: auto-hidden false positives have no admin `keep` path — openspec (new viewer, also covers #438)
3. **[medium] User profile history drops enforcement applied via report queue + appeal approvals** — admin-user-management § "The action-history view merges admin_actions_log and username_history" — audit rows use `target_type` `moderation_queue`/`appeal` (`AdminAuditLogger.kt:407,1014`) vs profile filter `'user'` (`UserProfileRepository.kt:74`); `moderation_queue_resolved` after_state lacks enforced author id (`ReportResolutionRepository.kt:262-266`) — openspec (add `user_id` to after_state + widen predicate)
4. **[low]** Appeal queue uses LIMIT/OFFSET vs docs/11 §3 (line 139) cursor-no-OFFSET — `AppealReviewRepository.kt:64`, `AdminAppealReviewRoute.kt:49` — regular-pr (fold into C1)
5. **[low]** Appeal decision paths untested at route level: reject route, malformed id, over-long reason, "deciding fires no notifications/FCM" negative guard (content-moderation-appeal § "Decision outcome surfaced via status read, proactive notification deferred") — `AppealReviewTest.kt:145-301` — regular-pr
6. **[low]** Stale canonical spec deferrals: csam-detection § "Admin CSAM review surface and the report-filing read path are deferred" vs shipped `AdminCsamRoute.kt:75-160` (archive `2026-06-23-admin-csam-detection-log-viewer` had no csam-detection delta); moderation-queue § "No reader endpoint in V9" — openspec (spec hygiene)

### D. Unspecced product surface (moderation)
- Attestation Fallback Review (`GET /admin/attestation-review`) — `docs/07-Operations.md:53`, admin frame 11 (`nearyou-admin-mockup.html:1025`, "Usulan"); no spec, no route
- Admin "flag for review" (`admin_flag` trigger) — enum reserved (`docs/05:532`; moderation-queue lists "Phase 3.5 admin-flag"); no writer, no control
