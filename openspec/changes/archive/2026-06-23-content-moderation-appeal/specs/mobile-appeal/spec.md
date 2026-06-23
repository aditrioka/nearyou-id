## ADDED Requirements

### Requirement: Sign-in banned-state appeal entry

The sign-in/banned screen SHALL show an "Ajukan banding" entry that navigates to the appeal screen, shown when a sign-in attempt returns the banned/suspended state (`SignInOutcome.Banned`, surfaced from the 403 `account_banned` response that carries the limited `appeal_token`). The entry MUST NOT be shown after a successful (un-actioned) sign-in. The entry lives at **sign-in, not Settings**: a banned OR suspended user is 403'd at the auth boundary (suspension is session-terminating — it bumps `token_version`, revoking existing tokens), so neither holds an in-app session from which to open Settings; the limited appeal token is captured from the sign-in 403 body into the in-memory appeal-session holder. All copy MUST come from `:shared:resources` CMP Resources (no hardcoded UI strings).

#### Scenario: Banned/suspended sign-in surfaces the appeal entry
- **GIVEN** a sign-in attempt returns 403 (`account_banned`) carrying an `appeal_token`
- **WHEN** the user is on the resulting banned sign-in screen
- **THEN** an "Ajukan banding" entry is shown and navigates to the appeal screen

#### Scenario: Successful (un-actioned) sign-in shows no appeal entry
- **GIVEN** the user is not under any moderation action
- **WHEN** sign-in succeeds
- **THEN** no appeal entry is shown

### Requirement: Appeal submission form

The appeal screen SHALL present a multiline `appeal_text` field with a 1000-character limit reflected in a visible counter, and a submit action. Submission goes through `AppealRepository` (over the shared `HttpClient`) which exposes a sealed `AppealOutcome`. The screen state is held by an androidx `ViewModel` (commonMain) exposing one `StateFlow<AppealUiState>`; the `AppealRoute` NavKey is `@Serializable` and registered in the polymorphic navigation `SerializersModule`.

#### Scenario: Successful submission shows the pending state
- **GIVEN** the user has typed a valid appeal reason
- **WHEN** the user submits and the backend returns 201
- **THEN** the screen transitions to the pending-appeal state showing the submission was received

#### Scenario: Character limit is enforced in the field
- **WHEN** the user's `appeal_text` reaches 1000 characters
- **THEN** further input is prevented or visibly flagged, and the counter reflects the limit

### Requirement: Appeal outcome state mapping

The screen SHALL map each `AppealOutcome` to a distinct, non-crashing UI state: success → pending view; already-pending → an "appeal already under review" state surfacing the existing status; rate-limited → a "try again later" state; not-eligible/other failure → a recoverable error state. A network/transport failure MUST degrade to a retryable error, never a crash.

#### Scenario: Already-pending appeal surfaces the existing status
- **GIVEN** the user already has a pending appeal
- **WHEN** the user opens the appeal screen (which fetches the own-appeal-status on entry, before showing the form)
- **THEN** the screen shows the existing pending status rather than an empty form or an error

#### Scenario: Rate-limited submission shows a try-later state
- **WHEN** submission returns the rate-limit outcome
- **THEN** the screen shows a non-error "try again later" message and does not crash

### Requirement: Appeal status display

The appeal screen SHALL display the latest appeal status from the own-appeal-status read: `pending`, `approved`, or `rejected` (with the `decision_reason` when present).

#### Scenario: Rejected status with reason is shown
- **GIVEN** the user's latest appeal is `rejected` with a `decision_reason`
- **WHEN** the user opens the appeal screen
- **THEN** the rejected status and the decision reason are displayed

### Requirement: Permanent-ban appellants are routed to support (in-app entry deferred)

A permanently-banned user (no in-app session) SHALL be shown the existing "Akun kamu telah dinonaktifkan. Hubungi support jika ini keliru." copy and is directed to the support path; an in-app appeal form for permanent bans is explicitly DEFERRED. The backend endpoint still accepts `action_type = 'permanent_ban'` appeals for a future support portal, but the mobile MVP does not surface an in-app permanent-ban form.

#### Scenario: Permanent ban shows the support copy, not the in-app form
- **GIVEN** the user is permanently banned (`is_banned = TRUE`, `suspended_until IS NULL`)
- **WHEN** the user reaches the login/ban screen
- **THEN** the "Hubungi support" copy is shown and no in-app appeal form is presented

#### Scenario: Deferred in-app permanent-ban entry is tracked
- **WHEN** the in-app permanent-ban appeal entry is considered
- **THEN** it is recorded as a deferred follow-up (not silently dropped), with the support-email path as the MVP recourse
