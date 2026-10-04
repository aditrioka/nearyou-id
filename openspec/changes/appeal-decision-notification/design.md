## Context

`content-moderation-appeal` shipped the appeal ledger (V34), the ban-exempt submit/status endpoints, and — via `admin-appeal-review` — the admin approve/reject actions in `AppealReviewRepository` (one transaction: `SELECT … FOR UPDATE` → guarded `UPDATE appeals` → [unban] → `admin_actions_log`). Its § "Decision outcome surfaced via status read, proactive notification deferred" pinned the decision as pull-only and `AppealReviewTest` carries a negative guard asserting no `notifications` row is written. Follow-up #390 lifts that deferral.

Relevant shipped facts this design builds on:

- **Admin-originated notifications** (`account_action_applied` in `UserModerationRepository` / `BanPrimitives`, `chat_message_redacted` in `ChatRedactionRepository`) are a raw `INSERT INTO notifications (…) ?::jsonb` on the action's own transaction connection, `actor_user_id = NULL`, in-app feed only. `NotificationEmitter` (block / self-action suppression) is the social emit path and is NOT used for system rows.
- **FCM dispatch is call-site driven**, not row-driven: social writers call `NotificationDispatcher.dispatch(id)` after commit. A raw admin insert never dispatches.
- **The feed read path** (`JdbcNotificationRepository.listByUser`) filters `type IN (NotificationType.entries)` in SQL (audit 03-#8); the unread-count query does not filter by type. A type the enum does not know is therefore invisible in the list but counted in the badge.
- **`notifications.type`** is an inline V10 CHECK, Postgres-named `notifications_type_check` (verified on the dev DB). `target_type VARCHAR(16)` has no CHECK.
- **Mobile**: one shared deep-link resolver (`resolveNotificationNavIntent` + `NotificationNavTargetResolver`) feeds both the in-app list tap and `PushTapNavigationEffect`. `AppealRoute` (a `data object` NavKey, already registered) hosts `AppealScreen`, whose `AppealViewModel` reads the one-shot limited appeal token from the in-memory `AppealSession`; no token ⇒ `SessionRedirect` today. `AppealApiClient` uses a raw client (no Auth plugin) so it can attach that token explicitly. The backend appeal realm (`AUTH_PROVIDER_APPEAL`) accepts any token scope, so a normal access token also reads `GET /api/v1/appeals`.

## Goals / Non-Goals

**Goals:**
- An admin approve/reject atomically writes one in-app `appeal_decided` row for the appellant.
- The row is visible in `GET /api/v1/notifications`, renders decision-keyed copy on mobile, and tapping it opens the appeal screen showing the decision (incl. `decision_reason`).
- The appeal screen works when opened by a signed-in (non-banned) user who holds no appeal token.

**Non-Goals:**
- FCM push for `appeal_decided` (operator decision 2026-10-04 — in-app only; also applies to #315). No `PushCopy` case, no dispatcher wiring into `admin()` / `Application.kt` (a parallel session owns `Application.kt`).
- Notifying a user who is still banned at decision time through any channel other than the status read — see Risks.
- Addressing a specific historical appeal on the appeal screen (it shows the caller's latest appeal; see Risks).
- The actor-less / reply-target deep-link deferrals (#379) — untouched.

## Decisions

### D1 — Emit with the shipped admin raw in-tx INSERT, inside the decision transaction
`approve` / `reject` insert the row on the same `conn` after the guarded `UPDATE appeals` and before `commit()`, alongside the audit row. A private `insertDecisionNotification(conn, userId, appealId, decision)` mirrors `UserModerationRepository.insertSuspendNotification` (Kotlin-built `buildJsonObject` → `?::jsonb`, the repository's existing `kotlinx.serialization` JSON). The no-op paths (`NotFound`, `NoOpAlreadyResolved`) return before it, so a re-decision writes no row; any insert failure throws into the existing `catch → rollback`, so the appeal transition, unban, audit row, and notification commit or roll back together.
*Alternative rejected:* `NotificationEmitter` — it is the social path (block/self suppression keyed on an actor), would be a second admin-emit pattern, and drags FCM plumbing toward `admin()`.

### D2 — Row shape: `target_type='appeal'`, `target_id=<appeal id>`, `body_data={"decision": …}`
Per the docs/05 catalog rule, the outer `(target_type, target_id)` is the deep-link address and `body_data` carries only what the pair cannot supply. The appeal id addresses the decided appeal; `decision` (`"approved"` / `"rejected"`) is not derivable from the pair without a fetch and drives the row copy. `actor_user_id = NULL` (the actor is an admin, not a `public.users` row).
`body_data` deliberately carries NO `decision_reason` (free-text up to 1000 chars; the `account_action_applied` precedent keeps admin free text out of the feed — the status read already returns it to the appellant), NO `action_type`, NO admin identity, and does not duplicate the appeal id.
*Alternatives rejected:* `target_type = NULL` with the appeal id in `body_data` (violates the "don't duplicate target into body_data" rule's intent and loses the addressing pair); two types `appeal_approved` / `appeal_rejected` (doubles the CHECK widening and the client mapping for one bit of data).

### D3 — V40: additive constraint swap, fail-loud on a name mismatch
`ALTER TABLE notifications DROP CONSTRAINT notifications_type_check, ADD CONSTRAINT notifications_type_check CHECK (type IN (…14 values…))` — one atomic ALTER (the constraint is never absent to a concurrent session), exactly the V36 precedent. It is already re-runnable (the re-add restores the same name, so a `repair()`-driven re-apply finds it again). `IF EXISTS` is deliberately NOT used: if the constraint were ever named differently on some database, `IF EXISTS` would silently leave the old 13-value CHECK in place and every approve/reject would then roll back on 23514 at runtime; a plain `DROP` fails the migration instead. The smoke test + staging smoke additionally assert exactly ONE CHECK on `notifications` references `type`. The re-add validates existing rows; all 13 prior values stay valid, so no data rewrite. V40 is the next free version (`origin/main` tops out at V39; no remote branch or sibling worktree holds a V40).

### D4 — `NotificationType.APPEAL_DECIDED` is part of the slice (read-path threading)
Without the enum value the list endpoint's `type IN (…)` filter hides the row while the unread badge counts it — a phantom unread. Adding the value makes the list, unread count, mark-read, and data-export paths all consistent. No `PushCopy` branch is added (no push is ever dispatched for this type; if one ever were, `PushCopy`'s existing `FALLBACK_BODY` applies).

### D5 — Mobile deep link: new `appeal` target → existing `AppealRoute`
`resolveNotificationNavIntent` maps `target_type = "appeal"` → `NotificationNavIntent.OpenAppeal` (no fetch, no payload — the screen reads the caller's own status) → `NotificationNavTarget.Appeal`. `NotificationsScreen` gains a hoisted `onOpenAppeal: () -> Unit = {}`; `AppShellScreen` forwards it and `appEntryProvider` wires it to `backStack.add(AppealRoute)`. `PushTapNavigationEffect.push` gains the matching branch (exhaustive `when`; unreachable today because no push is sent, and harmless if one ever is). No new `NavKey`.

### D6 — Appeal screen token source: appeal token if held, else the signed-in session
`AppealFlow.status(appealToken: String?)`: a non-null token keeps today's raw-client path; `null` reads through the shared bearer-authed `HttpClient` (docs/11 §2.6 — the one shared client; its Auth plugin attaches/refreshes the normal access token, which the appeal realm accepts). `AppealApiClient` takes that shared client as a second constructor arg. A `401` on either path stays `SessionExpired → SessionRedirect` (re-sign-in), so the restored-back-stack-after-process-death case still ends at sign-in. Submit is unchanged (appeal token only; a signed-in, non-banned user has nothing to appeal).
`AppealStatus.Decided` gains `viaSession: Boolean`; an approved decision read via the session renders the approved title + a new "akunmu sudah aktif kembali" body with NO re-sign-in CTA (the user already holds a live session). Approved via the appeal token (banned sign-in path) keeps the shipped re-sign-in CTA.

Two guards make "token held ⇔ banned sign-in flow" true (round-1 review):
- **The appeal token is dropped on a successful sign-in** (`AuthRepository`'s `SignInApiResult.Success` branch calls `appealSession.clear()`). Today nothing ever clears it (1 h TTL), so a suspended user who appealed, was approved, and signed in again in the same process would otherwise have the notification tap read through the stale token → the approved surface's "masuk lagi" CTA for a signed-in user, or a 401 → sign-in after the hour. It also stops holding a credential past its use.
- **No appeal token AND no stored session → `SessionExpired` with no network call** (`AppealRepository` checks `TokenStore.read()` first). Routing that case through the shared client would 401 → `TokenRefresher` finds no refresh token → `SessionInvalidator.invalidate()` → the involuntary "session expired" notice + a saved return destination — not today's quiet redirect. The short-circuit keeps the restored-back-stack behavior identical to today.

The rejected body (`appeal_rejected_body`) is reworded from "Tindakan pada akun tetap berlaku" (false once a suspension has lapsed, which is the only time a rejected appellant can reach the feed) to "Keputusan moderasi atas akunmu tidak diubah" — accurate on both paths, so no rejected-path branching.
*Alternatives rejected:* reading the stored access token and attaching it to the raw client (no refresh → an expired access token would bounce a signed-in user to sign-in); a separate `AppealStatusRoute` NavKey (a second screen for the same status UI).

### D7 — Decision-keyed row copy
`NotificationRow` gains `appealDecision: String?` (projected from `body_data.decision` for `appeal_decided` rows only, like `flipDeadlineDate`). `notificationCopy` maps `"approved"` → `notif_appeal_approved`, `"rejected"` → `notif_appeal_rejected`, absent/unknown → `notif_appeal_decided` (neutral). All via `:shared:resources`.

### Standards conformance
- **Backend layering (docs/11 §3.1/§3.2)** — the write stays in the existing admin repository transaction (admin repositories own their tx, the `ReportResolutionRepository` / `UserModerationRepository` precedent); one transaction per decision; no new route/service.
- **Admin notification emit** — reuses the shipped raw in-tx INSERT pattern (no second pattern).
- **Mobile state (§2.2)** — `AppealViewModel` keeps its single `stateIn` `uiState`; the nav signal stays the consumed-once nullable state field in `NotificationsViewModel`.
- **Navigation (§2.3)** — reuses the registered `AppealRoute`; hoisted callback through the shell; no new NavKey.
- **Data layer (§2.6)** — `ApiClient` + `Repository` + sealed outcome unchanged in shape; the session path uses the ONE shared `HttpClient` (no ad-hoc client). No Pattern-Registry deviation → no docs/11 amendment.

### Cross-layer scope (docs/12)
| Layer | Shipped in this change |
|---|---|
| Backend | V40 CHECK widening, in-tx emit, `NotificationType` enum (feed read path), docs/05 catalog (+ V40 note, in-app-only marker) / docs/02 / docs/03 / docs/08 enum count |
| Admin | **No surface change** — the emit rides the existing approve/reject actions; the operator observes nothing new (the audit row is unchanged). Not a deferred layer: there is no admin-facing behavior to add. |
| Mobile (Android + iOS, commonMain) | row copy, `appeal` deep link → `AppealRoute`, signed-in status read on the appeal screen, appeal token dropped on sign-in success |
| Push (FCM) | **Out of scope by operator decision**, pinned as a positive negative-guard in `content-moderation-appeal` + `in-app-notifications` (not a §3 deferral — no follow-up is planned). |

No layer is deferred, so no §3 deferred requirement is needed.

## Risks / Trade-offs

- **[A rejected appellant is still banned and cannot read the feed]** → The row is still written; a suspended user sees it once the suspension lapses (unban worker) and they sign in. While banned, the appeal screen reached from the sign-in 403 already shows the decision via the ban-exempt status read (unchanged). A permanently-banned rejected appellant never reaches the feed, so the row sits unread until the type-agnostic 90-day retention purge (likewise for a suspension longer than 90 days) — acceptable; the status read is their surface.
- **[The appeal screen shows the LATEST appeal, not necessarily the one the notification names]** → Only reachable if the user had a newer appeal, which requires being banned again (feed unreachable while banned). The `target_id` keeps the precise address for a future by-id read; not built now (YAGNI).
- **[No appeal token and no session would hit the shared client's 401→refresh→invalidate path]** → Short-circuited in `AppealRepository` (D6); a signed-in user whose refresh token is rejected still gets the app-wide session-expiry handling, which is correct for a live-but-dead session.
- **[V40 on staging via branch deploy, later main deploy without V40]** → Flyway's default `*:future` ignore keeps `main` booting; the squash-merge deploy sees the same checksum. `IF EXISTS` keeps a `repair()` re-apply safe.

## Migration Plan

1. CI: Flyway V40 against the service-container Postgres (DB-tagged tests) + the supabase-parity migrate lane.
2. Pre-archive staging branch deploy (`gh workflow run deploy-staging.yml --ref appeal-decision-notification`); smoke = V40 history row `success` + `pg_constraint` contains `appeal_decided` (read-only Supabase MCP) + `/health/ready` 200. The admin decide → notification E2E is verified locally (verify-loop: local Ktor + admin panel + mobile), since a staging admin decision needs a temp admin whose audit rows only the dashboard can delete.
3. Rollback: forward-only — a later migration deletes the `appeal_decided` rows, then re-narrows the CHECK. A code-only revert that also removed the enum value would hide existing rows from the list while the unread badge still counts them, so delete the rows first (or keep the enum value when reverting only the writer).

## Open Questions

_None._ (FCM scope settled by the operator 2026-10-04.)
