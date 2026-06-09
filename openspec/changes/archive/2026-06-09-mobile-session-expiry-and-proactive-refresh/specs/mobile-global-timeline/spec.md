## MODIFIED Requirements

### Requirement: Fetch outcome mapping is HTTP-status-driven with no generic fallthrough

`GlobalTimelineRepository` SHALL map each fetch result to exactly one member of a sealed `GlobalTimelineOutcome`, keyed on the HTTP **status code** and transport-failure type (NOT on a parsed `error.code`), with no generic "load failed" fallthrough:
- **HTTP 200** → `Loaded(posts, nextCursor, upsell)`. Because the rate-limit hard cap is also a 200 (empty `posts` + `upsell.hard = true`), the hard/soft presentation is derived from the parsed `upsell` flags, NOT from a distinct status.
- **HTTP 401** (terminal — survived the shipped Ktor `Auth` `refreshTokens` because the refresh itself failed) → a dedicated `SessionExpired` outcome. It MUST NOT map to `NetworkError` or `Error` (the prior `else`/wildcard branch that produced `NetworkError` for an unenumerated 401 is removed). The shipped `Auth` plugin still owns the refresh attempt, and `SessionInvalidator` still owns the re-route to `SignInScreen`; this mapping only guarantees the brief pre-re-route render is a neutral redirect placeholder, never the connectivity copy. The repository MUST NOT reimplement 401 refresh/retry.
- **HTTP 400** (`invalid_cursor` — not expected on the always-valid first page) → a retryable `Error` outcome with a diagnostic emitted to logs (NOT a silent no-op, NOT a crash).
- **HTTP 5xx or network/IO failure** → `NetworkError` (retryable). A genuine transport failure (caught `IOException` / timeout / host-unreachable) keeps mapping here — `NetworkError` remains reserved for actual connectivity faults, distinct from the terminal-401 `SessionExpired` above.
- **Any other unenumerated non-2xx status** (e.g. an unexpected 403/404) → the defined `NetworkError` fallback (retryable). Because the mapping is over an `Int` status, a defined fallback MUST remain — the "no generic fallthrough" rule bans a generic "load failed" *copy*, NOT a `when` `else`/fallback branch. The fix for the bug this change addresses is to branch `401` explicitly to `SessionExpired` ahead of this fallback, never to delete the fallback.

#### Scenario: 200 maps to Loaded carrying posts, cursor, and upsell

- **GIVEN** a MockEngine returning 200 with 3 posts, top-level `nextCursor = "tok"`, and `upsell.soft = true`
- **WHEN** the repository processes the response
- **THEN** the outcome is `Loaded` with 3 posts AND `nextCursor = "tok"` AND the parsed `upsell.soft = true`

#### Scenario: Hard-cap 200 (empty + upsell.hard) maps to Loaded, not Error

- **GIVEN** a MockEngine returning 200 with `{ posts: [], nextCursor: null, upsell: { hard: true } }`
- **WHEN** the repository processes the response
- **THEN** the outcome is `Loaded` with empty posts AND `upsell.hard = true` (the screen renders the hard-limit state; this is NOT mapped to `Error`/`NetworkError`)

#### Scenario: Terminal 401 maps to SessionExpired, never NetworkError

- **GIVEN** a MockEngine that responds 401 to the Global fetch AND responds 401 to the subsequent `POST /api/v1/auth/refresh` (a terminal 401 surfaced by the `Auth` plugin)
- **WHEN** the repository processes the result
- **THEN** the outcome is `SessionExpired` AND it is NOT `NetworkError` AND NOT the retryable `Error` AND the `signin_error_network` (connectivity) copy is not the selected state

#### Scenario: 5xx / network-IO maps to NetworkError

- **GIVEN** a MockEngine returning bare HTTP 500 (or throwing `IOException`)
- **WHEN** the repository processes the result
- **THEN** the outcome is `NetworkError` AND no crash occurs AND the outcome is NOT `SessionExpired`

#### Scenario: Every fetch result maps to exactly one outcome

- **WHEN** inspecting the repository result mapping and the `GlobalTimelineOutcome` sealed type
- **THEN** each of HTTP 200, terminal 401, 400, 5xx, and network/IO failure maps to exactly one `GlobalTimelineOutcome` member (`Loaded` / `SessionExpired` / `Error` / `NetworkError`); terminal 401 maps to `SessionExpired` (navigation remains delegated to the shipped `Auth` plugin) AND any other unenumerated non-2xx falls to the defined `NetworkError` fallback. There is NO branch emitting a generic "load failed" copy — but the `NetworkError` fallback itself is a DEFINED branch (required because the match is over an `Int`), not a generic-copy fallthrough

## ADDED Requirements

### Requirement: Terminal 401 renders a neutral session-expired redirect state, not the connectivity error

When the fetch outcome is `SessionExpired` (terminal 401), `GlobalTimelineScreen` SHALL render a neutral redirect placeholder — a short notice via `stringResource` (e.g. `timeline_session_redirect` "Mengalihkan ke halaman masuk…") with **no** retry control — and SHALL NOT render `stringResource(Res.string.signin_error_network)` nor any "Coba lagi" retry. The connectivity-error state (`signin_error_network` + `cta_retry`) remains reserved exclusively for the `NetworkError` outcome (genuine transport failure). This is the in-screen complement to the reliable `SignInScreen` re-route (`mobile-auth-signin`).

#### Scenario: SessionExpired renders the redirect placeholder, not the connectivity error

- **WHEN** the outcome is `SessionExpired`
- **THEN** the rendered tree contains the neutral redirect notice AND does NOT contain `stringResource(Res.string.signin_error_network)` AND does NOT contain a `stringResource(Res.string.cta_retry)` control

#### Scenario: NetworkError still shows the connectivity copy and retry

- **WHEN** the outcome is `NetworkError`
- **THEN** the rendered tree contains `stringResource(Res.string.signin_error_network)` AND a retry control labelled `stringResource(Res.string.cta_retry)` (unchanged from the prior behavior)
