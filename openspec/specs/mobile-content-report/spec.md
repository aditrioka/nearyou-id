# mobile-content-report Specification

## Purpose
The mobile surface for reporting an individual **post** or **reply** (`POST /api/v1/reports` with `target_type` of `post`/`reply`), closing the user-generated-content store-compliance + safety gap left when mobile shipped only user-level reporting (`docs/03` §Report UX, `docs/06` §Report System). It owns the shared report-submission seam — `data/report/` (`ReportReasonCategory`, `ReportOutcome`, `ReportTargetType`, `ReportApiClient`, `ReportSubmitter`) plus the `ui/components/ReportDialog` (the six user-facing reason categories + an optional 200-code-point note) — consumed by both the profile and post-detail surfaces, so there is exactly one report-submission implementation. On the post-detail surface both `Submitted` and `Duplicate` (409) render the same success message (anti-enumeration, `docs/03`:234) while `RateLimited` (429) and `NetworkError` are typed; the post entry point is gated on `!isAuthor` and the reply entry point sends only the reply id (never `author_id`, preserving PII discipline). The backend reports pipeline (10/h rate limit, duplicate handling, auto-hide-on-3) is unchanged; the timeline-card kebab (#363) is explicitly deferred, and chat-message report (#364) is delivered by the `mobile-chat-message-report` capability (which consumes this shared seam via `ReportTargetType.CHAT_MESSAGE`).
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

The report reason enum, the sealed `ReportOutcome`, the report submission call, and the report dialog composable SHALL exist as ONE shared seam (`data/report/` + `ui/components/`) consumed by the profile (user report), post-detail (post/reply report), AND chat-thread (chat-message report) surfaces. `ReportTargetType` SHALL enumerate exactly the four members the shipped `reports` endpoint validates — `USER("user")`, `POST("post")`, `REPLY("reply")`, `CHAT_MESSAGE("chat_message")`. There SHALL NOT be a second or duplicated report-submission implementation. The pre-existing profile and post-detail report behavior and their wire contracts SHALL be unchanged by the addition of the chat-thread consumer (additive only).

#### Scenario: Exactly one report reason enum and outcome type exist

- **WHEN** inspecting the mobile source tree
- **THEN** there is exactly one `ReportReasonCategory` definition, one `ReportOutcome` type, and one `ReportSubmitter`, located under the shared seam, referenced by the profile, post-detail, AND chat-thread surfaces

#### Scenario: Profile and post-detail report behavior is unchanged after the chat consumer is added

- **WHEN** running the existing profile and post-detail report tests after the chat-thread surface is added as a consumer
- **THEN** they pass unchanged (same categories, same outcome mapping, same wire `target_type` of `user` / `post` / `reply`)

#### Scenario: ReportTargetType enumerates the four shipped wire values

- **WHEN** inspecting `ReportTargetType`
- **THEN** it has exactly the members `USER`, `POST`, `REPLY`, `CHAT_MESSAGE` with wire strings `"user"`, `"post"`, `"reply"`, `"chat_message"` (matching the backend `reports` `target_type` CHECK)

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

### Requirement: Chat-message report is delivered by the mobile-chat-message-report capability

The `mobile-content-report` capability itself SHALL NOT add a chat-message report affordance to its own surfaces (post-detail, profile) — chat-message reporting (`target_type = "chat_message"`) is delivered by the separate `mobile-chat-message-report` capability, which adds the long-press → "Laporkan" entry point on the 1:1 chat thread. The shared seam (`ReportTargetType.CHAT_MESSAGE`, `ReportSubmitter`, `ReportDialog`) is the integration point both capabilities share. GitHub issue [#364](https://github.com/aditrioka/nearyou-id/issues/364) is resolved by `mobile-chat-message-report`.

#### Scenario: Content-report's own surfaces add no chat-message affordance

- **WHEN** inspecting the post-detail and profile report surfaces owned by `mobile-content-report`
- **THEN** neither surface submits `target_type = "chat_message"` (chat reporting is not a content-report surface)

#### Scenario: Chat-message reporting is delivered by the sibling capability

- **WHEN** locating where `target_type = "chat_message"` is submitted in the mobile source
- **THEN** it is the chat-thread surface owned by the `mobile-chat-message-report` capability, consuming the shared seam (not a duplicated report path)

### Requirement: Timeline card exposes a report entry point

The shared timeline post card (`PostCard`, `mobile-post-card`) SHALL expose a report entry point on all three feed surfaces (Nearby / Global / Following): an overflow kebab whose menu carries a "Laporkan" item (resource `profile_report_action`), supplied by the feed host ONLY for **non-authored** posts. Authorship SHALL be resolved in the feed ViewModel by comparing the raw timeline DTO's `authorUserId` against the viewer's `SelfUserIdProvider` id — never on the PII-free `PostCardModel`, which stays UUID-free. Selecting "Laporkan" SHALL open the shared `ReportDialog` (title `report_title_post`); submission SHALL go through the shared `ReportSubmitter` with `target_type = "post"` and the tapped post's id as `target_id`. The outcome→message mapping SHALL keep the anti-enumeration posture (design D3 / `docs/03`:234): `Submitted` AND `Duplicate` (409) map to the SAME success copy (`profile_report_success_toast`); `RateLimited` (429) and `NetworkError` map to their typed copy. The result SHALL surface as a one-shot snackbar message held as nullable state cleared via a shown-callback (docs/11 § 2.2 — no `Channel`/`SharedFlow` bus). The report-flow state (dialog target + one-shot message) SHALL exist exactly once, in a shared controller instantiated per feed ViewModel (the `InlineLikeController` precedent) — not three per-feed copies. This supersedes the prior "Timeline-card report entry point is deferred" posture ([#354](https://github.com/aditrioka/nearyou-id/pull/354) has merged; issue [#363](https://github.com/aditrioka/nearyou-id/issues/363) is closed by this change).

#### Scenario: Another user's post is reportable from the feed

- **WHEN** a feed renders a post NOT authored by the viewer and the viewer opens the card kebab, picks "Laporkan", selects a reason category, and submits
- **THEN** the shared `ReportDialog` is shown AND exactly one report is submitted through the shared `ReportSubmitter` with `target_type = "post"` and the post's id as `target_id`

#### Scenario: The viewer's own post exposes no report entry point

- **WHEN** a feed renders a post whose `authorUserId` equals the viewer's `SelfUserIdProvider` id
- **THEN** that card renders no report kebab AND no report affordance is reachable for it from the timeline

#### Scenario: Submitted and duplicate reports render the same success message

- **WHEN** a timeline-card report submission resolves as `Submitted`, and separately as `Duplicate` (409)
- **THEN** both render the identical success snackbar copy (`profile_report_success_toast`) — the reporter cannot distinguish a prior report (anti-enumeration)

#### Scenario: Rate-limited and network-failed submissions render typed messages

- **WHEN** a timeline-card report submission resolves as `RateLimited` (429), and separately as `NetworkError`
- **THEN** the rate-limit copy renders for the former and the retryable failure copy for the latter, each as a one-shot snackbar cleared after showing (no re-fire on recomposition)

#### Scenario: One shared report-flow implementation across the three feeds

- **WHEN** inspecting the mobile source tree
- **THEN** the timeline report-flow state machine (dialog target + one-shot message + submission) is defined exactly once (a shared controller under `ui/timeline/`), instantiated by the Nearby, Global, AND Following feed ViewModels, consuming the existing shared `ReportSubmitter`/`ReportDialog` seam (no duplicated report path)

### Requirement: Test coverage for the timeline-card report entry point

The change SHALL ship: (1) `commonTest` (or JVM unit) coverage for the shared timeline report controller — dialog open/dismiss one-shot, submission outcome→message mapping (Submitted/Duplicate→the same success value, RateLimited, NetworkError), and message clear; (2) Robolectric coverage for the card-level affordance — kebab present with a report action and absent without one — and for the feed-level entry point (kebab → the "Laporkan" menu item). The feed-level screen test SHALL NOT open the dialog body: the shared `ReportDialog` (an `OutlinedTextField` inside an `AlertDialog`) over the feed's `LazyColumn` triggers the documented Robolectric-only never-settling measure pass (the `mobile-content-report` PostDetailScreenTest precedent), so the dialog-open one-shot, the `target_type=post` submission, and the outcome mapping are locked at the controller level instead (item 1), and the dialog body renders under its own non-`LazyColumn` host test. New `*ScreenTest`-shaped tests SHALL be added to the Release-variant test-exclude list, keeping `:mobile:app:testDevReleaseUnitTest` green.

#### Scenario: Controller and affordance tests exist and are discoverable

- **WHEN** running `./gradlew :mobile:app:testDevDebugUnitTest`
- **THEN** the timeline report controller tests and the kebab presence/absence tests are discovered AND each documented outcome mapping corresponds to at least one `@Test`

#### Scenario: Release variant stays green

- **WHEN** running `./gradlew :mobile:app:testDevReleaseUnitTest`
- **THEN** the task passes, with any new screen-shaped tests listed in the Release-variant exclude block of `mobile/app/build.gradle.kts`

