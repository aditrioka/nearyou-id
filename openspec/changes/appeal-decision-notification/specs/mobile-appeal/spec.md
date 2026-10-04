## ADDED Requirements

### Requirement: Appeal screen opened from a notification reads status through the signed-in session

The appeal screen SHALL also be reachable from an `appeal_decided` notification (per `mobile-notifications-list`), i.e. by a signed-in, non-banned user who holds NO limited appeal token. When the in-memory `AppealSession` holds no appeal token, the on-entry own-appeal-status read SHALL go through the shared bearer-authenticated `HttpClient` (the Auth plugin attaches — and refreshes — the normal access token; the backend appeal realm accepts it) instead of short-circuiting to the re-sign-in state. When an appeal token IS held (the banned sign-in path), the read SHALL keep using it on the raw client exactly as before. A `401` on either path SHALL still map to the session-redirect (re-sign-in) state, so a restored back stack with neither an appeal token nor a usable session still ends at sign-in. Submission is unchanged: it requires the appeal token (a missing token still routes to re-sign-in).

An **approved** decision read through the signed-in session SHALL render the approved outcome with copy stating the account is active again (`appeal_approved_body_active`) and NO re-sign-in action (the user already holds a live session); an approved decision read through the appeal token keeps the shipped re-sign-in action. A rejected decision renders the shipped rejected surface (with the `decision_reason` when present) on both paths.

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

#### Scenario: No appeal token and no usable session routes to re-sign-in
- **GIVEN** `AppealSession` holds no appeal token AND the session read returns `401`
- **WHEN** the appeal screen opens
- **THEN** the session-redirect (re-sign-in) state is shown
