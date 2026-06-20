## 1. Shared seam + strings

- [ ] 1.1 Add `CHAT_MESSAGE("chat_message")` member to `data/report/ReportTargetType.kt` (update its doc-comment that currently calls chat_message "a deferred chat-surface change" to reflect it is now wired). This is the only edit to the shared report seam.
- [ ] 1.2 Add the new `:shared:resources` Compose Multiplatform string(s) for the long-press menu — the "Laporkan" action label + any content description — in the canonical strings resource (reuse existing report-success / rate-limit strings from `mobile-content-report`; do NOT duplicate them).

## 2. Chat-thread report affordance + wiring

- [ ] 2.1 Add a long-press affordance to `MessageBubble` in `screens/chat/ChatThreadScreen.kt` (e.g. `combinedClickable { onLongClick = … }`) that opens a small `DropdownMenu`/menu anchored to the bubble exposing the "Laporkan" item — shown ONLY when `senderId != viewerId` AND the row is not redacted. Confirm no pre-existing long-press gesture conflicts (none today).
- [ ] 2.2 On "Laporkan", show the shared `ui/components/ReportDialog` (six categories + optional ≤200-code-point note) for the selected `ChatMessageRow`.
- [ ] 2.3 Surface a per-row `isReportable` (sender-not-self AND not redacted) on `ChatMessageRow` / the `chatMessageRows(...)` projection so the affordance gate is testable as a pure projection (not buried in composable conditionals).
- [ ] 2.4 In `ChatThreadViewModel`, inject the concrete `ReportSubmitter` (mirror `PostDetailViewModel`) and add a `reportMessage(row, category, note)` entry that calls `submit(target = ReportTargetType.CHAT_MESSAGE, targetId = row.id, category, note)`. Add the Koin binding (`viewModel { }` at the `ChatThreadRoute` scope) for the `ReportSubmitter` dependency if not already provided to the chat module.
- [ ] 2.5 Add a one-shot `reportResult` field to the chat thread's single `StateFlow<…UiState>` plus an `onReportResultShown()` consume/clear callback (NOT a `Channel`/`SharedFlow`). Render `Submitted`+`Duplicate` → the shared success message, `RateLimited` → rate-limit message (no success claim), `NetworkError` → retryable; never surface a moderation review outcome.

## 3. PII discipline

- [ ] 3.1 Ensure the chat report path sends only `row.id` as `target_id` and that no log call site in the chat report wiring passes the bearer token, `Authorization` header, JWT `sub`, `senderId`, or message body. Keep the Ktor `LogLevel` on this path body-free (reuse the shared seam's posture).

## 4. Tests

- [ ] 4.1 `commonTest`: assert `ReportTargetType.CHAT_MESSAGE.wire == "chat_message"`.
- [ ] 4.2 `commonTest`: chat report outcome→UI-state mapping (Submitted/Duplicate→success, RateLimited→message, NetworkError→retry, one-shot clear) using a `FakeReportSubmitter`.
- [ ] 4.3 `commonTest`: affordance-visibility projection — `isReportable` true only when `senderId != viewerId` AND `redactedAt == null` (own message → false; redacted → false; received non-redacted → true).
- [ ] 4.4 `commonTest`: the chat report submits `POST /api/v1/reports` with `target_type="chat_message"` + `target_id=<message id>` (MockEngine recording requests).
- [ ] 4.5 Robolectric `ChatThreadScreenTest`: long-press a received bubble → "Laporkan" → `ReportDialog` → pick category + submit → success message; assert NO affordance on own/sent and on redacted messages. Add the new `*ScreenTest` to the Release-variant exclude list in `mobile/app/build.gradle.kts`.
- [ ] 4.6 `iosTest` flow test mirroring the existing chat iOS flow test (long-press received message → report → success) with Kotlin/Native-legal function names.
- [ ] 4.7 Confirm the existing profile + post-detail report tests still pass unchanged (the shared-seam additive-only invariant).

## 5. Local verification gates (pre-push)

- [ ] 5.1 `./gradlew :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest` green (Release variant proves the new screen test is correctly excluded).
- [ ] 5.2 `./gradlew ktlintCheck detekt` green for the touched modules (no hardcoded-UI-string lint violation; no vendor-SDK-leak; no second-pattern).
- [ ] 5.3 (If iOS toolchain available locally) `:mobile:app:iosSimulatorArm64Test` green for the new chat report iOS flow test.

## 6. Manual verify-loop bring-up (UI-affecting DoD — docs/11 §5)

- [ ] 6.1 Bring up the app via `verify-loop` (context-routed: local emulator/device or Firebase Test Lab), open a chat thread, long-press a received message, submit a report, and capture screenshots of: the long-press menu, the report dialog, and the success message. Attach to the PR body before archive.
- [ ] 6.2 Consult chat mockup frames 2 + 5 (measurement annex) for the long-press menu styling and reconcile spacing/typography to tokens.

## 7. Docs reconciliation

- [ ] 7.1 Reconcile `docs/03-UX-Design.md` § Report UX (line ~230) — it lists the report kebab for "post, reply, profile page" only. Either amend it in this PR to add the chat long-press entry, or file a `follow-up` issue (docs/06 § Report System line 229 is canonical and already includes chat message). Record the chosen disposition in the PR body.

## 8. No-backend / no-migration guard

- [ ] 8.1 Confirm the final diff adds no `db/migration/*.sql` and edits no `:backend:ktor` source (mobile-only change; reuses the shipped `POST /api/v1/reports`).
