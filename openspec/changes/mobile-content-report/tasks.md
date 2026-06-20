## 1. Extract the shared report seam (mechanical, no behavior change)

- [x] 1.1 Create `data/report/` and move `ReportReasonCategory` (+ `toWire`) and the sealed `ReportOutcome` out of `id.nearyou.app.profile` into the shared seam; keep the six user-facing categories + wire mappings identical (do NOT add `self_harm`/`csam_suspected`).
- [x] 1.2 Extract the report-submission call (ApiClient/Repository path that `POST`s `/api/v1/reports`) into the shared `data/report/` seam, parameterized over `target_type` + `target_id` (so `user`/`post`/`reply` all flow through one call); reuse the single shared `HttpClient`.
- [x] 1.3 Move the report `AlertDialog` composable (reason picker + optional 200-code-point note + "Jelaskan lebih detail jika perlu" placeholder) into `ui/components/`, target-agnostic; all strings via `:shared:resources` (reuse existing report strings; add only if a new one is genuinely needed).
- [x] 1.4 Update the profile surface (`ProfileScreen`, `ProfileViewModel`, `profile/ProfileRepository`/`ProfileFlow`) to import the relocated seam — package decl + imports only, zero logic edits; profile report UX + wire (`target_type = "user"`) unchanged.
- [x] 1.5 Update Koin DI (`MobileModule`) bindings for the relocated report ApiClient/Repository/dialog seam.
- [x] 1.6 Run the mobile test gate; the existing `ReportReasonCategoryTest` + profile report tests pass **unchanged** (regression oracle for the move).

## 2. Post-detail report entry points

- [x] 2.1 Add the post-header "Laporkan" affordance to `PostDetailScreen`, shown only when `!isAuthor` (mirror the existing Edit-affordance gate); opens the shared report dialog targeting `target_type = "post"`, `target_id = <post id>`.
- [x] 2.2 Add a per-reply "Laporkan" affordance to each reply row; opens the shared report dialog targeting `target_type = "reply"`, `target_id = <reply id>`; introduce NO author identity into the tree (only the reply `id` is used).
- [x] 2.3 Wire `PostDetailViewModel`: submit via the shared report seam; model the one-shot result (success / rate-limit / network-error) as nullable fields on the single `StateFlow<…UiState>`, cleared via an `onReportResultShown()` callback (no `Channel`/`SharedFlow` bus).
- [x] 2.4 Map outcomes in the screen: `Submitted` AND `Duplicate` → "Laporan terkirim. Tim moderasi akan meninjau."; `RateLimited` → rate-limit message; `NetworkError` → retryable; never surface a review outcome.
- [x] 2.5 Confirm no hardcoded UI strings (CMP Resources only) — run the no-hardcoded-strings grep / lint.

## 3. Tests

- [x] 3.1 `commonTest`: relocated `ReportReasonCategory.toWire` mapping + the post/reply `target_type` selection (post→`post`, reply→`reply`).
- [x] 3.2 `commonTest`: report submission outcome→UI-state mapping (`Submitted`/`Duplicate`→success message, `RateLimited`→message, `NetworkError`→retry, one-shot field cleared after `onReportResultShown()`). Assert the anti-enumeration contract explicitly: the `Duplicate`-path message string **equals** the `Submitted`-path string AND the `Duplicate` path fires no retry/second submission.
- [x] 3.3 Robolectric `PostDetailScreenTest` additions: report affordance present on a non-authored post; absent on the viewer's own post; reply-row report affordance present **on both a non-authored and a viewer-authored reply** (ungated by authorship — locks design D4); dialog submit → success-message path (via a fake report seam). Assert the reply-report request carries `target_id = <reply id>` only AND no rendered node / diagnostic log / request field contains the reply `author_id` UUID (PII negative-guard).
- [x] 3.4 Robolectric report-dialog component test: the six categories shown (no `self_harm`/`csam_suspected`), optional-note bound at 200 **code points** (include a multi-byte/non-BMP boundary case — e.g. 200 emoji ≠ 400 UTF-16 units, mirroring the reply composer's 280-code-point precedent), submit emits the selected wire `reason_category`.
- [x] 3.5 Add the new `*ScreenTest` class(es) to the Release-variant `*ScreenTest` exclude in `mobile/app/build.gradle.kts`; verify `:mobile:app:testDevReleaseUnitTest` passes.
- [x] 3.6 Re-run the full mobile unit gate (`:mobile:app:testDevDebugUnitTest` + `:mobile:app:testStagingDebugUnitTest`) — all green, profile tests included.
- [x] 3.7 Flip the now-stale negative-guard test `header_showsContentAndPostedFrom_andNoBlockReportAffordance` (`PostDetailScreenTest`, ~line 325): the `Blokir`-absent assertion stays (block remains deferred), but the `Laporkan`-absent assertion (line 333) is now incorrect (report ships) — remove it (report-affordance presence is covered by 3.3) and rename the test to a block-only guard, matching the renamed `mobile-post-detail` "Block kebab action is deferred" requirement.

## 4. Deferrals, follow-ups, and verification

- [x] 4.1 File a `follow-up` issue (labels `follow-up`,`mobile`) for the deferred timeline-card (`PostCard`) report kebab — the MODIFY hook once `image-attached-posts` (#354) lands; reference it in the `mobile-content-report` spec's deferral requirement.
- [x] 4.2 File a `follow-up` issue (labels `follow-up`,`mobile`) for chat-message reporting (`target_type = "chat_message"`).
- [x] 4.3 Narrow GitHub issue [#200](https://github.com/aditrioka/nearyou-id/issues/200) to the remaining **block** deferral (the report half is now shipped) — retitle/comment so the tracker matches the renamed `mobile-post-detail` requirement.
- [ ] 4.4 Manual verification (docs/11 §5 DoD): run the app on an emulator/device, open a post you do NOT author, report the post and a reply, confirm the "Laporan terkirim…" message and that your own post shows Edit (not Laporkan); capture evidence in the PR body.
- [x] 4.5 Pre-push gate: `./gradlew ktlintCheck detekt :lint:detekt-rules:test` (lint frameworks CI runs) + the mobile unit gate from 3.5/3.6; do not `--no-verify`.
