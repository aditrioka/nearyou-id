## ADDED Requirements

### Requirement: A new session drops the shared client's cached bearer tokens

A new session's requests SHALL never carry a previous session's access token. The Ktor `Auth { bearer }` plugin caches the `BearerTokens` returned by `loadTokens` until its own refresh path runs or the cache is cleared. Logout does not bump `token_version`, so a signed-out account's access token stays valid until it expires (≤ 15 min). Two consequences follow:

- `AuthRepository` SHALL clear every `BearerAuthProvider` cache on the shared `HttpClient` immediately after it persists a NEW session's `TokenPair`, on both sign-in success and sign-up success. It does this via `AuthApiClient.clearCachedBearerTokens()`, the same helper `ProactiveTokenRefreshTrigger` uses after a proactive refresh.
- The first authenticated request of the new session SHALL therefore re-run `loadTokens` and attach the new session's access token, never a previous session's cached one.

#### Scenario: After logout and a new sign-in, requests carry the new account's token

- **GIVEN** a production-configured shared `HttpClient` over a MockEngine and a token store holding account A's `TokenPair("at-A", …)`, AND one authenticated request already sent (so the plugin cached `at-A`)
- **WHEN** the store is cleared (logout), account B signs in successfully (the store now holds `TokenPair("at-B", …)`), and another authenticated request is sent
- **THEN** the second request's `Authorization` header is `Bearer at-B`, not `Bearer at-A`

#### Scenario: Sign-up also drops the cached tokens

- **GIVEN** a shared client that cached account A's access token
- **WHEN** a sign-up succeeds and persists account B's `TokenPair`
- **THEN** the next authenticated request carries B's access token
