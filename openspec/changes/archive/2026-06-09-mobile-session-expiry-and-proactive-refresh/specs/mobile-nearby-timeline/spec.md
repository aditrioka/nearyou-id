## MODIFIED Requirements

### Requirement: Fetch outcome mapping is HTTP-status-driven with no generic fallthrough

`NearbyTimelineRepository` SHALL map each fetch result to exactly one member of a sealed `NearbyTimelineOutcome`, keyed on the HTTP **status code** and transport-failure type (NOT on a parsed `error.code`), with no generic "load failed" fallthrough:
- **HTTP 200** → `Loaded(posts, nextCursor, upsell)`. Because the rate-limit hard cap is also a 200 (empty `posts` + `upsell.hard = true`), the hard/soft presentation is derived from the parsed `upsell` flags on the `Loaded` outcome, NOT from a distinct status.
- **HTTP 401** (terminal — survived the shipped Ktor `Auth` `refreshTokens` because the refresh itself failed) → a dedicated `SessionExpired` outcome. It MUST NOT map to `NetworkError` or `Error` (the prior `else`/wildcard branch that produced `NetworkError` for an unenumerated 401 is removed). The shipped `Auth` plugin still owns the refresh attempt, and `SessionInvalidator` still owns the re-route to `SignInScreen`; this mapping only guarantees the brief pre-re-route render is a neutral redirect placeholder, never the connectivity copy. The repository MUST NOT reimplement 401 refresh/retry.
- **HTTP 400** (`invalid_request` / `location_out_of_bounds` / `radius_out_of_bounds` / `invalid_cursor` — not expected from the stub's always-valid params) → a retryable `Error` outcome with a diagnostic emitted to logs (NOT a silent no-op, NOT a crash).
- **HTTP 5xx or network/IO failure** → `NetworkError` (retryable). A genuine transport failure (caught `IOException` / timeout / host-unreachable) keeps mapping here — `NetworkError` remains reserved for actual connectivity faults, distinct from the terminal-401 `SessionExpired` above.
- **Any other unenumerated non-2xx status** (e.g. an unexpected 403/404) → the defined `NetworkError` fallback (retryable). Because the mapping is over an `Int` status, a defined fallback MUST remain — the "no generic fallthrough" rule bans a generic "load failed" *copy*, NOT a `when` `else`/fallback branch. The fix for the bug this change addresses is to branch `401` explicitly to `SessionExpired` ahead of this fallback, never to delete the fallback.

#### Scenario: 200 maps to Loaded carrying posts, cursor, and upsell
- **GIVEN** a MockEngine returning 200 with 3 posts, top-level `nextCursor = "tok"` (shipped camelCase wire key), and `upsell.soft = true`
- **WHEN** the repository processes the response
- **THEN** the outcome is `Loaded` with 3 posts AND `nextCursor = "tok"` AND the parsed `upsell.soft = true`

#### Scenario: Hard-cap 200 (empty + upsell.hard) maps to Loaded, not Error
- **GIVEN** a MockEngine returning 200 with `{ posts: [], next_cursor: null, upsell: { hard: true } }`
- **WHEN** the repository processes the response
- **THEN** the outcome is `Loaded` with empty posts AND `upsell.hard = true` (the screen renders the hard-limit state; this is NOT mapped to `Error`/`NetworkError`)

#### Scenario: Terminal 401 maps to SessionExpired, never NetworkError
- **GIVEN** a MockEngine that responds 401 to the Nearby fetch AND responds 401 to the subsequent `POST /api/v1/auth/refresh` (a terminal 401 surfaced by the `Auth` plugin)
- **WHEN** the repository processes the result
- **THEN** the outcome is `SessionExpired` AND it is NOT `NetworkError` AND NOT the retryable `Error` AND the `signin_error_network` (connectivity) copy is not the selected state

#### Scenario: 5xx / network-IO maps to NetworkError
- **GIVEN** a MockEngine returning bare HTTP 500 (or throwing `IOException`)
- **WHEN** the repository processes the result
- **THEN** the outcome is `NetworkError` AND no crash occurs AND the outcome is NOT `SessionExpired`

#### Scenario: Unexpected 400 maps to retryable Error with a logged diagnostic
- **GIVEN** a MockEngine returning HTTP 400 `{"error":{"code":"invalid_request"}}`
- **WHEN** the repository processes the response
- **THEN** the outcome is the retryable `Error` AND a diagnostic is emitted to logs (NOT a silent no-op, NOT a crash)

#### Scenario: Every fetch result maps to exactly one outcome
- **WHEN** inspecting the repository result mapping and the `NearbyTimelineOutcome` sealed type
- **THEN** each of HTTP 200, terminal 401, 400, 5xx, and network/IO failure maps to exactly one `NearbyTimelineOutcome` member (`Loaded` / `SessionExpired` / `Error` / `NetworkError`); terminal 401 maps to `SessionExpired` (navigation remains delegated to the shipped `Auth` plugin) AND any other unenumerated non-2xx falls to the defined `NetworkError` fallback. There is NO branch emitting a generic "load failed" copy — but the `NetworkError` fallback itself is a DEFINED branch (required because the match is over an `Int`), not a generic-copy fallthrough

## ADDED Requirements

### Requirement: Terminal 401 renders a neutral session-expired redirect state, not the connectivity error

When the fetch outcome is `SessionExpired` (terminal 401), `NearbyTimelineScreen` SHALL render a neutral redirect placeholder — a short notice via `stringResource` (e.g. `timeline_session_redirect` "Mengalihkan ke halaman masuk…") with **no** retry control — and SHALL NOT render `stringResource(Res.string.signin_error_network)` nor any "Coba lagi" retry. The connectivity-error state (`signin_error_network` + `cta_retry`) remains reserved exclusively for the `NetworkError` outcome (genuine transport failure). This is the in-screen complement to the reliable `SignInScreen` re-route (`mobile-auth-signin`): it ensures the sub-second window before navigation shows a correct message.

#### Scenario: SessionExpired renders the redirect placeholder, not the connectivity error
- **WHEN** the outcome is `SessionExpired`
- **THEN** the rendered tree contains the neutral redirect notice AND does NOT contain `stringResource(Res.string.signin_error_network)` AND does NOT contain a `stringResource(Res.string.cta_retry)` control

#### Scenario: NetworkError still shows the connectivity copy and retry
- **WHEN** the outcome is `NetworkError`
- **THEN** the rendered tree contains `stringResource(Res.string.signin_error_network)` AND a retry control labelled `stringResource(Res.string.cta_retry)` (unchanged from the prior behavior)

### Requirement: Nearby repository diagnostic sink is wired to a real coordinate-free logger

The Koin binding for `NearbyTimelineRepository` SHALL pass a real `diagnosticLog` sink (not the no-op default) so non-user-facing diagnostics (`nearby_network_error`, the 400 `invalid_request` diagnostic) are observable. The sink SHALL remain coordinate-free and token-free by construction (it carries only pre-redacted status/message strings — no coordinate or token is passed to it), preserving the existing PII discipline and the HTTP-path `CoordinateMaskingLogger`.

#### Scenario: MobileModule wires a non-no-op diagnostic sink
- **WHEN** inspecting the `NearbyTimelineRepository` Koin registration in `MobileModule`
- **THEN** a real `diagnosticLog` argument is supplied (not omitted to the no-op default) AND the sink's call sites pass no coordinate and no token
