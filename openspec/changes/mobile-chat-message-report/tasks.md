## 1. Shared seam + strings

- [ ] 1.1 Add `CHAT_MESSAGE("chat_message")` member to `data/report/ReportTargetType.kt` (update its doc-comment that currently calls chat_message "a deferred chat-surface change" to reflect it is now wired). This is the only edit to the shared report seam. **Also update the existing `ReportTargetTypeTest`** — it currently asserts chat_message is ABSENT ("exactly the three surfaced target types exist - no chat_message"); flip it to expect the four members, or CI red-fails.
- [ ] 1.2 Add the new `:shared:resources` Compose Multiplatform string(s) for the long-press menu — the "Laporkan" action label + any content description — in the canonical strings resource (reuse existing report-success / rate-limit strings from `mobile-content-report`; do NOT duplicate them).

## 2. Chat-thread report affordance + wiring

- [ ] 2.1 Add a long-press affordance to `MessageBubble` in `screens/chat/ChatThreadScreen.kt` (e.g. `combinedClickable { onLongClick = … }`) that opens a small `DropdownMenu`/menu **anchored off the `ChatMessageRow`** (which carries `isOwn`/`isRedacted`, NOT `senderId`) exposing the "Laporkan" item — shown ONLY when `row.isReportable` (`!isOwn && !isRedacted`). Do NOT pass the raw `ChatMessage` (carries `senderId`) into the menu/affordance to recompute the gate — that would re-introduce a PII field onto the UI path. Confirm no pre-existing long-press gesture conflicts (none today).
- [ ] 2.2 On "Laporkan", show the shared `ui/components/ReportDialog` (six categories + optional ≤200-code-point note) for the selected `ChatMessageRow`.
- [ ] 2.3 Surface a per-row `isReportable = !isOwn && !isRedacted` on `ChatMessageRow` / the `chatMessageRows(...)` projection (derive `isOwn` from `senderId == viewerId` at projection — `senderId` stays out of the row) so the affordance gate is a pure, testable projection (not buried in composable conditionals).
- [ ] 2.4 In `ChatThreadViewModel`, inject the concrete `ReportSubmitter` (mirror `PostDetailViewModel`) and add a `reportMessage(row, category, note)` entry that calls `submit(target = ReportTargetType.CHAT_MESSAGE, targetId = row.id, category, note)`. Add the Koin binding (`viewModel { }` at the `ChatThreadRoute` scope) for the `ReportSubmitter` dependency if not already provided to the chat module.
- [ ] 2.5 Add a one-shot `reportResult` field to the chat thread's single `StateFlow<…UiState>` plus an `onReportResultShown()` consume/clear callback (NOT a `Channel`/`SharedFlow`). Render `Submitted`+`Duplicate` → the shared success message, `RateLimited` → rate-limit message (no success claim), `NetworkError` → retryable; never surface a moderation review outcome.

## 3. PII discipline

- [ ] 3.1 Ensure the chat report path sends only `row.id` as `target_id` and that no log call site in the chat report wiring passes the bearer token, `Authorization` header, JWT `sub`, `senderId`, or message body. Keep the Ktor `LogLevel` on this path body-free (reuse the shared seam's posture).

## 4. Tests

- [ ] 4.1 `commonTest`: assert `ReportTargetType.CHAT_MESSAGE.wire == "chat_message"`.
- [ ] 4.2 `commonTest`: chat report outcome→UI-state mapping (Submitted/Duplicate→success, RateLimited→message, NetworkError→retry, one-shot clear) using a `FakeReportSubmitter`.
- [ ] 4.3 `commonTest`: affordance-visibility projection — `isReportable == !isOwn && !isRedacted` (received non-redacted → true; own message → false; **optimistic-own send row → false**; redacted received → false).
- [ ] 4.4 `commonTest`: the chat report submits `POST /api/v1/reports` with `target_type="chat_message"` + `target_id=<message id>` (MockEngine recording requests).
- [ ] 4.5 Robolectric `ChatThreadScreenTest`: long-press a received bubble → "Laporkan" → `ReportDialog` → pick category + submit → success message; assert NO affordance on own/sent and on redacted messages. Add the new `*ScreenTest` to the Release-variant exclude list in `mobile/app/build.gradle.kts`.
- [ ] 4.6 `iosTest` flow test modeled on `PostDetailFlowIosTest` (there is NO pre-existing chat iOS flow test to copy): long-press received message → report → success, with Kotlin/Native-legal function names.
- [ ] 4.7 Confirm the existing **chat** (`ChatThreadScreenTest`, `ChatThreadViewModelTest`, `ChatThreadUiStateTest`, `ConversationListScreenTest`) **and** profile + post-detail report tests still pass unchanged (the `combinedClickable`/`isReportable` edits most plausibly regress the chat suites; the shared-seam change is additive-only).
- [ ] 4.8 `androidUnitTest` Koin-resolution test (`ChatReportKoinResolutionTest`, per the established `*KoinResolutionTest` pattern): `ChatThreadViewModel` resolves with its `ReportSubmitter` dependency satisfied.
- [ ] 4.9 PII source-scan verification for the chat report path (a structural test that no log call site passes `senderId` / message body / bearer token / JWT `sub`, OR an assertion that the Ktor `LogLevel` on this path is body-free) — the spec'd "logs no sender/body/token" scenario; `mobile-content-report` shipped no reusable template, so this is net-new.

## 5. Local verification gates (pre-push)

- [ ] 5.1 `./gradlew :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest` green (Release variant proves the new screen test is correctly excluded).
- [ ] 5.2 `./gradlew ktlintCheck detekt` green for the touched modules (no hardcoded-UI-string lint violation; no vendor-SDK-leak; no second-pattern).
- [ ] 5.3 (If iOS toolchain available locally) `:mobile:app:iosSimulatorArm64Test` green for the new chat report iOS flow test.

## 6. Manual verify-loop bring-up (UI-affecting DoD — docs/11 §5)

- [ ] 6.1 Bring up the app via `verify-loop` (context-routed: local emulator/device or Firebase Test Lab), open a chat thread, long-press a received message, submit a report, and capture screenshots of: the long-press menu, the report dialog, and the success message. Attach to the PR body before archive.
- [ ] 6.2 Consult chat mockup frames 2 + 5 (measurement annex) for the long-press menu styling and reconcile spacing/typography to tokens.

## 7. Docs reconciliation

- [ ] 7.1 Reconcile `docs/03-UX-Design.md` § Report UX (line ~230) — it lists the report kebab for "post, reply, profile page" only. Either amend it in this PR to add the chat long-press entry, or file a `follow-up` issue (docs/06 § Report System line 229 is canonical and already includes chat message). Record the chosen disposition in the PR body.
- [ ] 7.2 At archive, reconcile the `mobile-content-report` spec **Purpose** line (`openspec/specs/mobile-content-report/spec.md` line ~4: "...chat-message report (#364) are explicitly deferred") — it is prose outside a `### Requirement:`, so the RENAMED+MODIFIED delta does not rewrite it; hand-edit it in the archive commit to drop the chat-message "deferred" clause (now delivered by `mobile-chat-message-report`).

## 8. No-backend / no-migration guard

- [ ] 8.1 Confirm the final diff adds no `db/migration/*.sql` and edits no `:backend:ktor` source (mobile-only change; reuses the shipped `POST /api/v1/reports`).
