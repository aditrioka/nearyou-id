## ADDED Requirements

### Requirement: Appeal screen opened from a notification reads status through the signed-in session

The appeal screen SHALL also be reachable from an `appeal_decided` notification (per `mobile-notifications-list`), i.e. by a signed-in, non-banned user who holds NO limited appeal token. When the in-memory `AppealSession` holds no appeal token, the on-entry own-appeal-status read SHALL go through the shared bearer-authenticated `HttpClient` (the Auth plugin attaches — and refreshes — the normal access token; the backend appeal realm accepts it) instead of short-circuiting to the re-sign-in state. When an appeal token IS held (the banned sign-in path), the read SHALL keep using it on the raw client exactly as before. A `401` on either path SHALL still map to the session-redirect (re-sign-in) state, so a restored back stack with neither an appeal token nor a usable session still ends at sign-in. Submission is unchanged: it requires the appeal token (a missing token still routes to re-sign-in).

Two guards keep "appeal token held" synonymous with "on the banned sign-in path": (1) a **successful sign-in SHALL drop any held appeal token** (`AppealSession.clear()`), so a stale token from an earlier banned attempt never routes a signed-in user's read through the raw appeal client; (2) when NO appeal token is held AND the token store holds NO session, the status read SHALL resolve to the session-redirect state **without issuing a network request** (no refresh attempt, no app-wide session-invalidation side effects — the same quiet redirect as before this change).

An **approved** decision read through the signed-in session SHALL render the approved outcome with copy stating the account is active again (`appeal_approved_body_active`) and NO re-sign-in action (the user already holds a live session); an approved decision read through the appeal token keeps the shipped re-sign-in action. A rejected decision renders the rejected surface (with the `decision_reason` when present) on both paths; its body copy SHALL be accurate whether or not the moderation action is still in effect (a rejected appellant reaching the screen through the session has, by construction, regained access).

#### Scenario: No appeal token reads status through the signed-in session
- **GIVEN** `AppealSession` holds no appeal token AND the signed-in session's `GET /api/v1/appeals` returns a `rejected` appeal with a `decision_reason`
- **WHEN** the appeal screen opens
- **THEN** the status read is issued without an appeal token (through the session client) AND the rejected status and the decision reason are displayed

#### Scenario: Approved decision via the signed-in session shows no re-sign-in action
- **GIVEN** `AppealSession` holds no appeal token AND the session read returns an `approved` appeal
- **WHEN** the appeal screen renders the decision
- **THEN** the approved title and the "account active again" copy are shown AND no re-sign-in action is rendered

#### Scenario: Approved decision via the appeal token keeps the re-sign-in action
- **GIVEN** `AppealSession` holds an appeal token AND the read returns an `approved` appeal
- **WHEN** the appeal screen renders the decision
- **THEN** the shipped approved surface with its re-sign-in action is shown

#### Scenario: No appeal token and an unusable session routes to re-sign-in
- **GIVEN** `AppealSession` holds no appeal token AND the session read returns `401`
- **WHEN** the appeal screen opens
- **THEN** the session-redirect (re-sign-in) state is shown

#### Scenario: No appeal token and no stored session redirects without a network read
- **GIVEN** `AppealSession` holds no appeal token AND the token store holds no session (e.g. a restored back stack after process death on the banned path)
- **WHEN** the appeal screen opens
- **THEN** the session-redirect (re-sign-in) state is shown AND no `GET /api/v1/appeals` request is issued

#### Scenario: A successful sign-in drops a held appeal token
- **GIVEN** an earlier banned sign-in stored an appeal token in `AppealSession`
- **WHEN** a later sign-in succeeds
- **THEN** `AppealSession` holds no appeal token (a subsequent appeal-screen read goes through the signed-in session)

#### Scenario: Rejected decision via the signed-in session shows the reason
- **GIVEN** `AppealSession` holds no appeal token AND the session read returns a `rejected` appeal with a `decision_reason`
- **WHEN** the appeal screen renders the decision
- **THEN** the rejected title, the rejected body, and the decision reason are shown AND no re-sign-in action is rendered
