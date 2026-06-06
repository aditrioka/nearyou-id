## MODIFIED Requirements

### Requirement: Signup call uses the canonical endpoint, snake_case body, no fingerprint

On create-account submission with a well-formed DOB, `AuthRepository.signUpWithGoogle(...)` SHALL issue `POST /api/v1/auth/signup` with a JSON body containing exactly `provider = "google"`, `id_token = <carried google id token>`, and `date_of_birth = "YYYY-MM-DD"` (ISO-8601 calendar date), serialized in snake_case (`id_token`, `date_of_birth`) per the `auth-signup` spec wire contract. The body MUST NOT include a `device_fingerprint_hash` key (attestation deferred, consistent with Mobile #3 Decision 9). On HTTP `201`, the returned `{access_token, refresh_token, expires_in}` SHALL be persisted via `SecureTokenStore.write(...)` and a navigation event routing to **`ConsentScreen`** (via `RootRouterScreen`) SHALL be emitted. `ConsentScreen` is the first-run analytics-consent step (per the `mobile-analytics-consent` capability) and routes onward to `HomeScreen` on consent submit; signup-success therefore terminates at `ConsentScreen`, not `HomeScreen` directly. (Prior to the `mobile-analytics-consent-screen` change the `201` terminus was `HomeScreen`; the returning-user sign-in terminus remains `HomeScreen` and is unaffected.)

#### Scenario: Valid 18+ DOB submits canonical signup request and routes to ConsentScreen on 201

- **GIVEN** a Ktor MockEngine capturing outbound requests that responds `201 {access_token:"at-X", refresh_token:"rt-Y", expires_in:900}`, AND `signUpWithGoogle` carrying `id_token = "g-id"`, AND a clean `SecureTokenStore`
- **WHEN** an 18+ DOB (e.g., `"1995-03-14"`) is submitted
- **THEN** the captured outbound request is `POST /api/v1/auth/signup` whose JSON body parses as `{provider:"google", id_token:"g-id", date_of_birth:"1995-03-14"}` with NO `device_fingerprint_hash` key AND `SecureTokenStore.write(TokenPair("at-X","rt-Y", <epoch-now + 900_000>))` is called exactly once AND a navigation event routing to `ConsentScreen` (NOT `HomeScreen`) is emitted

#### Scenario: Signup request body carries no device_fingerprint_hash

- **WHEN** `signUpWithGoogle(...)` makes the `/signup` API call
- **THEN** the captured outbound JSON body contains no `device_fingerprint_hash` key (verifiable via `body.toString().contains("device_fingerprint_hash") == false` OR a parsed-JSON assertion)
