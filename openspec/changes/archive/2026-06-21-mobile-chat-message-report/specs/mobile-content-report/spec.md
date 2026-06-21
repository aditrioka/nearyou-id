## RENAMED Requirements

- FROM: `### Requirement: Chat-message report is deferred`
- TO: `### Requirement: Chat-message report is delivered by the mobile-chat-message-report capability`

## MODIFIED Requirements

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

### Requirement: Chat-message report is delivered by the mobile-chat-message-report capability

The `mobile-content-report` capability itself SHALL NOT add a chat-message report affordance to its own surfaces (post-detail, profile) — chat-message reporting (`target_type = "chat_message"`) is delivered by the separate `mobile-chat-message-report` capability, which adds the long-press → "Laporkan" entry point on the 1:1 chat thread. The shared seam (`ReportTargetType.CHAT_MESSAGE`, `ReportSubmitter`, `ReportDialog`) is the integration point both capabilities share. GitHub issue [#364](https://github.com/aditrioka/nearyou-id/issues/364) is resolved by `mobile-chat-message-report`.

#### Scenario: Content-report's own surfaces add no chat-message affordance

- **WHEN** inspecting the post-detail and profile report surfaces owned by `mobile-content-report`
- **THEN** neither surface submits `target_type = "chat_message"` (chat reporting is not a content-report surface)

#### Scenario: Chat-message reporting is delivered by the sibling capability

- **WHEN** locating where `target_type = "chat_message"` is submitted in the mobile source
- **THEN** it is the chat-thread surface owned by the `mobile-chat-message-report` capability, consuming the shared seam (not a duplicated report path)
