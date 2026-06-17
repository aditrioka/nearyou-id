## MODIFIED Requirements

### Requirement: Signup endpoint contract

`POST /api/v1/auth/signup` SHALL accept a JSON body `{ "provider": "google" | "apple", "id_token": string, "date_of_birth": string (ISO-8601 date, e.g. "1995-03-14"), "device_fingerprint_hash": string?, "invite_code": string? }`. The `invite_code` field is OPTIONAL (default absent); when present it engages the referral-ticket-creation capability as a best-effort, non-blocking side effect that MUST NOT change the signup outcome. On success the endpoint SHALL return `{ "access_token": string, "refresh_token": string, "expires_in": 900 }` with HTTP 201 — the success status and body are unchanged by the presence, absence, or referral outcome of `invite_code`. The endpoint MUST be unauthenticated (no Bearer token required).

#### Scenario: Body shape accepted
- **WHEN** an unauthenticated client calls the endpoint with the documented fields and a valid payload
- **THEN** the request reaches the handler (no 400 from shape validation) and proceeds through the signup pipeline

#### Scenario: Optional invite_code accepted
- **WHEN** the body includes an `invite_code` string alongside the other valid fields
- **THEN** the request is accepted (no 400 from shape validation) AND the signup pipeline proceeds exactly as it would without the field

#### Scenario: Malformed DOB
- **WHEN** the `date_of_birth` field is missing, empty, or does not parse as ISO-8601 YYYY-MM-DD
- **THEN** the response is HTTP 400 with code `invalid_request`

#### Scenario: Missing provider or id_token
- **WHEN** `provider` or `id_token` is missing
- **THEN** the response is HTTP 400 with code `invalid_request`

#### Scenario: Referral outcome does not change the signup result
- **WHEN** a signup carries an `invite_code` whose referral attempt is rejected for any reason (unresolvable, ineligible inviter, anti-abuse, or error)
- **THEN** the signup response is still HTTP 201 with the `{ access_token, refresh_token, expires_in: 900 }` body AND no new error code is introduced on the signup path
