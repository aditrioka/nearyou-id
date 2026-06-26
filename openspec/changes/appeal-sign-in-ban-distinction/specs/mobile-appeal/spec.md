## MODIFIED Requirements

### Requirement: Sign-in banned-state appeal entry

The sign-in/banned screen SHALL show an "Ajukan banding" entry that navigates to the appeal screen **only when the 403 indicates a suspension** (`is_banned = TRUE` with a non-null `suspended_until` in the future) — surfaced to the client as the suspension sub-state of the banned sign-in outcome (per `mobile-auth-signin`). A **permanent** ban (`suspended_until` null in the 403 body) MUST NOT show the appeal entry; it is routed to the support path (see the permanent-ban requirement below). The entry MUST NOT be shown after a successful (un-actioned) sign-in. The entry lives at **sign-in, not Settings**: a banned OR suspended user is 403'd at the auth boundary (suspension is session-terminating — it bumps `token_version`, revoking existing tokens), so neither holds an in-app session from which to open Settings; the limited appeal token is captured from the sign-in 403 body into the in-memory appeal-session holder regardless of sub-state. All copy MUST come from `:shared:resources` CMP Resources (no hardcoded UI strings).

#### Scenario: Suspended sign-in surfaces the appeal entry
- **GIVEN** a sign-in attempt returns 403 (`account_banned`) carrying an `appeal_token` AND a non-null future `suspended_until`
- **WHEN** the user is on the resulting banned sign-in screen
- **THEN** an "Ajukan banding" entry is shown and navigates to the appeal screen

#### Scenario: Permanent ban does not surface the appeal entry
- **GIVEN** a sign-in attempt returns 403 (`account_banned`) carrying an `appeal_token` AND a null `suspended_until`
- **WHEN** the user is on the resulting banned sign-in screen
- **THEN** no "Ajukan banding" entry is shown (the support path applies)

#### Scenario: Successful (un-actioned) sign-in shows no appeal entry
- **GIVEN** the user is not under any moderation action
- **WHEN** sign-in succeeds
- **THEN** no appeal entry is shown

### Requirement: Permanent-ban appellants are routed to support (in-app entry deferred)

A permanently-banned user (`is_banned = TRUE`, `suspended_until IS NULL`, surfaced via the 403's null `suspended_until`) SHALL be shown the existing "Akun kamu telah dinonaktifkan. Hubungi support jika ini keliru." copy (`signin_error_banned`) and is directed to the support path; **no in-app appeal entry is shown**. An in-app appeal form for permanent bans is explicitly DEFERRED. The backend endpoint still accepts `action_type = 'permanent_ban'` appeals for a future support portal, but the mobile MVP does not surface an in-app permanent-ban form. The permanent-vs-suspension distinction is now wire-backed by the sign-in 403's `suspended_until` field (per `auth-signin`), so this routing is implemented rather than approximated by a uniform banned path.

#### Scenario: Permanent ban shows the support copy, not the in-app form
- **GIVEN** the user is permanently banned (`is_banned = TRUE`, `suspended_until IS NULL`) and the sign-in 403 carries `suspended_until = null`
- **WHEN** the user reaches the login/ban screen
- **THEN** the `signin_error_banned` "Hubungi support" copy is shown and no in-app appeal form and no appeal entry are presented

#### Scenario: Deferred in-app permanent-ban form is tracked
- **WHEN** the in-app permanent-ban appeal form is considered
- **THEN** it is recorded as a deferred follow-up (not silently dropped), with the support-email path as the MVP recourse
