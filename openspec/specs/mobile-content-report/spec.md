# mobile-content-report Specification

## Purpose
The mobile surface for reporting an individual **post** or **reply** (`POST /api/v1/reports` with `target_type` of `post`/`reply`), closing the user-generated-content store-compliance + safety gap left when mobile shipped only user-level reporting (`docs/03` §Report UX, `docs/06` §Report System). It owns the shared report-submission seam — `data/report/` (`ReportReasonCategory`, `ReportOutcome`, `ReportTargetType`, `ReportApiClient`, `ReportSubmitter`) plus the `ui/components/ReportDialog` (the six user-facing reason categories + an optional 200-code-point note) — consumed by both the profile and post-detail surfaces, so there is exactly one report-submission implementation. On the post-detail surface both `Submitted` and `Duplicate` (409) render the same success message (anti-enumeration, `docs/03`:234) while `RateLimited` (429) and `NetworkError` are typed; the post entry point is gated on `!isAuthor` and the reply entry point sends only the reply id (never `author_id`, preserving PII discipline). The backend reports pipeline (10/h rate limit, duplicate handling, auto-hide-on-3) is unchanged; the timeline-card kebab (#363) and chat-message report (#364) are explicitly deferred.
## Requirements
### Requirement: Shared report dialog presents the six user-facing reason categories and an optional note

The mobile app SHALL present a single shared report dialog (an M3 `AlertDialog`) used to report any reportable content. The dialog SHALL offer exactly the six **user-facing** reason categories from `docs/03-UX-Design.md` §Report UX, each mapped to its SHIPPED `reason_category` wire value: "Spam" → `spam`, "Ujaran kebencian (SARA)" → `hate_speech_sara`, "Pelecehan" → `harassment`, "Konten dewasa" → `adult_content`, "Misinformasi" → `misinformation`, "Lainnya" → `other`. The internal/automated classifications `self_harm` and `csam_suspected` SHALL NOT be user-pickable. The dialog SHALL accept an optional free-text note of at most 200 code points with the placeholder "Jelaskan lebih detail jika perlu". All dialog strings SHALL come from Compose Multiplatform Resources (no hardcoded UI strings).

#### Scenario: Reason picker exposes exactly the six user-facing categories
- **WHEN** the report dialog is shown
- **THEN** the rendered tree offers exactly the six categories (Spam, Ujaran kebencian (SARA), Pelecehan, Konten dewasa, Misinformasi, Lainnya) AND offers no `self_harm` or `csam_suspected` option

#### Scenario: Each category maps to its shipped wire value
- **WHEN** a category is selected and the report is submitted
- **THEN** the submitted `reason_category` is the category's exact wire value (`spam` / `hate_speech_sara` / `harassment` / `adult_content` / `misinformation` / `other`)

#### Scenario: Optional note is bounded at 200 code points
- **WHEN** the user types a note longer than 200 code points
- **THEN** the dialog gates submission client-side so no note exceeding 200 code points is sent

### Requirement: Content report submission targets a post or reply via the shipped reports endpoint

A submitted content report SHALL `POST /api/v1/reports` (the shipped `reports` capability — no new or changed endpoint) with `target_type = "post"` and `target_id = <post id>` when reporting a post, or `target_type = "reply"` and `target_id = <reply id>` when reporting a reply, plus the selected `reason_category` and the optional `reason_note`. The submission SHALL reuse the single shared `HttpClient` (no per-feature client). No backend, rate-limit, auto-hide, or schema change is introduced by this capability.

#### Scenario: Reporting a post posts the post target
- **WHEN** the user submits a report for a post with id `P`
- **THEN** the request is `POST /api/v1/reports` with `target_type = "post"` AND `target_id = "P"`

#### Scenario: Reporting a reply posts the reply target
- **WHEN** the user submits a report for a reply with id `R`
- **THEN** the request is `POST /api/v1/reports` with `target_type = "reply"` AND `target_id = "R"`

### Requirement: Every report submission outcome maps to exactly one UI result with no review-outcome leak

The submission SHALL map each `ReportOutcome` member to exactly one user-visible result, with no generic fallthrough: `Submitted` (204) AND `Duplicate` (409 `duplicate_report`) SHALL both render the success message "Laporan terkirim. Tim moderasi akan meninjau." — the duplicate case is intentionally indistinguishable from a first report so a reporter learns nothing about prior reports (anti-enumeration / anti-retaliation, `docs/03`:234); `RateLimited` (429) SHALL render a rate-limit message and SHALL NOT claim the report was recorded; `NetworkError` (5xx / transport / any unenumerated status) SHALL be retryable. The reporter SHALL NOT be shown any moderation review outcome. The success/rate-limit/error result SHALL be modeled as one-shot UI state on the screen's single `StateFlow<…UiState>`, consumed and cleared via an `onXxxShown()` callback (not a `Channel`/`SharedFlow` event bus).

#### Scenario: Submitted shows the success toast
- **WHEN** the submission returns `204`
- **THEN** the success message "Laporan terkirim. Tim moderasi akan meninjau." is shown AND the dialog is dismissed

#### Scenario: Duplicate is indistinguishable from success
- **WHEN** the submission returns `409` with `error.code = "duplicate_report"`
- **THEN** the SAME success message is shown (no "already reported" wording) AND no second submission is attempted

#### Scenario: Rate limited does not claim success
- **WHEN** the submission returns `429`
- **THEN** a rate-limit message is shown AND the success message is NOT shown

#### Scenario: One-shot result is cleared after being shown
- **WHEN** the result has been shown and `onXxxShown()` is invoked
- **THEN** the result field is cleared so it does not re-fire on recomposition or configuration change

### Requirement: A single shared report-submission seam serves all report surfaces

The report reason enum, the sealed `ReportOutcome`, the report submission call, and the report dialog composable SHALL exist as ONE shared seam (`data/report/` + `ui/components/`) consumed by BOTH the profile (user report) and post-detail (post/reply report) surfaces. There SHALL NOT be a second or duplicated report-submission implementation. The pre-existing profile report behavior and its wire contract SHALL be unchanged by the relocation (mechanical move only).

#### Scenario: Exactly one report reason enum and outcome type exist
- **WHEN** inspecting the mobile source tree
- **THEN** there is exactly one `ReportReasonCategory` definition and one `ReportOutcome` type, located under the shared seam, referenced by both the profile and post-detail surfaces

#### Scenario: Profile report behavior is unchanged after the relocation
- **WHEN** running the existing profile report tests after the seam is relocated
- **THEN** they pass unchanged (same categories, same outcome mapping, same wire `target_type = "user"`)

### Requirement: Timeline-card report entry point is deferred

This capability SHALL NOT add a report affordance to the shared timeline post card (`PostCard`) or modify the `mobile-post-card` spec — the timeline-card report entry point is deferred so this change stays footprint-disjoint from the in-flight `image-attached-posts` change ([#354](https://github.com/aditrioka/nearyou-id/pull/354)), which currently owns `PostCard`. GitHub issue [#363](https://github.com/aditrioka/nearyou-id/issues/363) (label `follow-up`) tracks the deferred timeline-card report kebab as the MODIFY hook for a future change.

#### Scenario: No report affordance is added to the timeline card
- **WHEN** inspecting the shared `PostCard` composable and the `mobile-post-card` spec
- **THEN** neither is modified by this change AND no report affordance or report API call is present on the timeline card

#### Scenario: Follow-up issue tracks the timeline-card deferral
- **WHEN** inspecting the project's open GitHub issues (label `follow-up`)
- **THEN** GitHub issue [#363](https://github.com/aditrioka/nearyou-id/issues/363) tracks the deferred timeline-card report kebab

### Requirement: Chat-message report is deferred

This capability SHALL NOT add a report affordance for chat messages (`target_type = "chat_message"`); chat-message reporting is a separate chat-surface change. GitHub issue [#364](https://github.com/aditrioka/nearyou-id/issues/364) (label `follow-up`) tracks it.

#### Scenario: No chat-message report affordance is added
- **WHEN** inspecting the chat screens and this change's diff
- **THEN** no chat-message report affordance or `target_type = "chat_message"` submission is added

#### Scenario: Follow-up issue tracks the chat-message deferral
- **WHEN** inspecting the project's open GitHub issues (label `follow-up`)
- **THEN** GitHub issue [#364](https://github.com/aditrioka/nearyou-id/issues/364) tracks chat-message reporting

### Requirement: Reporting one's own reply is permitted without a client guard

Because reply rows never render `author_id` (the `mobile-post-detail` PII-discipline contract — the wire carries it but it is never surfaced), the client cannot determine reply authorship and SHALL NOT gate the reply report affordance by authorship. The backend `self_report_rejected` guard fires only for `target_type = "user"`, so a self-report of one's own reply is accepted as a (harmless, rare) report and is NOT specially handled in v1. The client SHALL NOT send `author_id` to enable such a gate (that would regress PII discipline). Abuse is bounded by the shipped 10/hour report rate limit and by auto-hide requiring 3 **distinct** reporters — a self-report can neither hide one's own content nor exceed the rate cap.

#### Scenario: Reply report affordance is present regardless of authorship
- **WHEN** the viewer's own reply and another user's reply are both rendered
- **THEN** both rows expose the report affordance AND neither row's report path sends or relies on `author_id`

### Requirement: Test coverage for the content-report capability

The change SHALL ship: (1) `commonTest` for the reason-category `toWire` mapping and the submission outcome→UI-state mapping (Submitted/Duplicate→success, RateLimited→message, NetworkError→retry, one-shot clear); (2) Robolectric screen tests exercising the dialog (category pick + optional note + submit → success message) — ADDED to the Release-variant `*ScreenTest` test-exclude list and passing under `:mobile:app:testDevReleaseUnitTest`.

#### Scenario: Mapping and dialog tests exist and are discoverable
- **WHEN** running `./gradlew :mobile:app:testDevDebugUnitTest`
- **THEN** the reason-category mapping test, the outcome→state mapping test, and the report-dialog screen test are discovered AND each documented mapping/state corresponds to at least one `@Test`

#### Scenario: New screen tests are excluded from the Release variant
- **WHEN** inspecting `mobile/app/build.gradle.kts`
- **THEN** the new report screen test(s) are listed in the Release-variant `*ScreenTest` exclude block AND `:mobile:app:testDevReleaseUnitTest` passes

