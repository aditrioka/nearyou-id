## ADDED Requirements

### Requirement: `user_fcm_tokens` table SHALL store device-scoped FCM push tokens with a UNIQUE `(user_id, platform, token)` constraint

The Ktor backend SHALL provision a Postgres table `user_fcm_tokens` via Flyway migration `V14__user_fcm_tokens.sql` matching the canonical schema at [`docs/05-Implementation.md:1376-1389`](docs/05-Implementation.md):

```sql
CREATE TABLE user_fcm_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    platform VARCHAR(8) NOT NULL CHECK (platform IN ('android', 'ios')),
    token TEXT NOT NULL CHECK (char_length(token) BETWEEN 1 AND 4096),
    app_version TEXT CHECK (app_version IS NULL OR char_length(app_version) <= 64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, platform, token)
);

CREATE INDEX user_fcm_tokens_user_idx ON user_fcm_tokens(user_id);
CREATE INDEX user_fcm_tokens_last_seen_idx ON user_fcm_tokens(last_seen_at);
```

**Schema deviation from canonical docs (additive, defense-in-depth):** the `token` and `app_version` CHECK constraints above are NOT in the canonical schema at [`docs/05-Implementation.md:1376-1389`](docs/05-Implementation.md). They mirror the application-side caps (4 KB body / 64-char `app_version` per the validation requirement below) at the DB layer so a non-route write path (operator `psql`, future migration backfill) cannot bypass the bound. Same posture as the existing `platform` CHECK. See `design.md` D9.

The UNIQUE constraint MUST be `(user_id, platform, token)` — NOT `(token)` alone. A single FCM token MAY appear in multiple rows when owned by different `user_id`s (family-shared device, account-switching). See `design.md` D1 for rationale.

The `ON DELETE CASCADE` from `users(id)` MUST cascade-delete all `user_fcm_tokens` rows when a user is hard-deleted.

The `last_seen_at` column SHALL be the authoritative freshness signal consumed by the deferred Phase 3.5 stale-cleanup worker (`/internal/cleanup` weekly DELETE WHERE `last_seen_at < NOW() - INTERVAL '30 days'`). Any code path that creates or updates a `user_fcm_tokens` row MUST set `last_seen_at = NOW()`.

#### Scenario: Migration creates the table with the canonical column set
- **WHEN** Flyway migration `V14__user_fcm_tokens.sql` is applied
- **THEN** the `user_fcm_tokens` table exists with columns `id`, `user_id`, `platform`, `token`, `app_version`, `created_at`, `last_seen_at` AND with the UNIQUE constraint on `(user_id, platform, token)` AND with both indexes (`user_fcm_tokens_user_idx`, `user_fcm_tokens_last_seen_idx`)

#### Scenario: Platform CHECK constraint rejects non-enum values at the DB layer
- **WHEN** an `INSERT INTO user_fcm_tokens (user_id, platform, token) VALUES (..., 'web', ...)` is attempted directly against the database
- **THEN** the insert fails with a Postgres CHECK constraint violation (the application-side validation per the next requirement is the first line of defense; the DB CHECK is the second)

#### Scenario: ON DELETE CASCADE removes tokens when their user is deleted
- **WHEN** a `DELETE FROM users WHERE id = :user_id` is executed AND that user has rows in `user_fcm_tokens`
- **THEN** the user's `user_fcm_tokens` rows are removed in the same transaction (no FK violation, no orphan rows)

#### Scenario: Same token registered by two different users produces two rows
- **WHEN** user A inserts `(user_a, 'android', 'shared-token-xyz')` AND user B inserts `(user_b, 'android', 'shared-token-xyz')`
- **THEN** both inserts succeed AND `SELECT COUNT(*) FROM user_fcm_tokens WHERE token = 'shared-token-xyz'` returns 2

#### Scenario: Same `(user_id, platform, token)` re-insert is rejected by the UNIQUE constraint at the DB layer
- **WHEN** `INSERT INTO user_fcm_tokens (user_id, platform, token) VALUES (:u, 'android', :t)` is executed twice without ON CONFLICT handling
- **THEN** the second insert fails with a Postgres unique-constraint violation on `user_fcm_tokens_user_id_platform_token_key`

#### Scenario: Token longer than 4096 chars is rejected by the DB CHECK
- **WHEN** an `INSERT INTO user_fcm_tokens (user_id, platform, token) VALUES (..., 'android', <4097-char string>)` is attempted directly against the database
- **THEN** the insert fails with a Postgres CHECK constraint violation on `token`

#### Scenario: app_version longer than 64 chars is rejected by the DB CHECK
- **WHEN** an `INSERT INTO user_fcm_tokens (user_id, platform, token, app_version) VALUES (..., 'android', 'tok', <65-char string>)` is attempted directly against the database
- **THEN** the insert fails with a Postgres CHECK constraint violation on `app_version`

### Requirement: `POST /api/v1/user/fcm-token` SHALL upsert idempotently and refresh `last_seen_at` on every call

The Ktor backend SHALL expose `POST /api/v1/user/fcm-token` as a JWT-authenticated endpoint accepting a JSON body with three fields: `token` (string, required, non-empty), `platform` (string, required, one of `"android"` or `"ios"`), and `app_version` (string, optional, max 64 chars).

The handler MUST execute a single atomic upsert via Postgres `INSERT ... ON CONFLICT (user_id, platform, token) DO UPDATE SET last_seen_at = NOW(), app_version = EXCLUDED.app_version` (see `design.md` D6). The `user_id` MUST come from the authenticated `UserPrincipal` (NOT from the request body).

On success, the handler MUST return `204 No Content` with no response body (see `design.md` D5).

The handler MUST log a single INFO-level structured event per successful registration with fields `event="fcm_token_registered"`, `user_id`, `platform`, `created` (boolean — `true` if the upsert produced a new row, `false` if it refreshed an existing one), AND `user_token_count` (Long — the per-user row count in `user_fcm_tokens` after the upsert applies; powers the `design.md` D3 tripwire). The `created` distinction MAY be derived via the `xmax = 0` Postgres trick on a `RETURNING xmax` clause OR via a separate `SELECT 1 FROM user_fcm_tokens WHERE ... ` pre-check; implementer's choice. The `user_token_count` is computed via `RETURNING (SELECT COUNT(*) FROM user_fcm_tokens WHERE user_id = $1) AS user_token_count` in the same upsert statement (see `design.md` D3 + tasks § 3.3).

The request body size MUST be capped at 4 KB at the **transport layer** — distinct from the field-level [`ContentLengthGuard`](backend/ktor/src/main/kotlin/id/nearyou/app/guard/ContentLengthGuard.kt) (which counts Unicode codepoints of authored-text fields like post / reply / chat content). The cap MUST be enforced via the Ktor `RequestBodyLimit` plugin OR a defensive top-of-handler `call.request.contentLength()?.let { if (it > 4096) return@post call.respond(HttpStatusCode.PayloadTooLarge) }` check — implementer's choice based on the Ktor version's plugin surface (see `design.md` D10). Larger bodies MUST be rejected with `413 Payload Too Large`. The cap is informational about transport size only; actual field validation (token / app_version length) lives in the validation requirement below AND in the DB CHECK constraints from the schema requirement above (defense-in-depth across three layers: handler, app-level DTO validation, DB CHECK).

#### Scenario: New token registration inserts a row and returns 204
- **WHEN** an authenticated user calls `POST /api/v1/user/fcm-token` with body `{"token": "abc123", "platform": "android", "app_version": "0.1.0"}` AND no row exists with that `(user_id, platform, token)` triple
- **THEN** the response status is `204 No Content` AND a new row exists in `user_fcm_tokens` with `last_seen_at` and `created_at` both ≈ `NOW()` AND the structured log event has `created=true`

#### Scenario: Re-registration of the same token refreshes `last_seen_at` and does NOT create a duplicate row
- **WHEN** an authenticated user calls `POST /api/v1/user/fcm-token` with body `{"token": "abc123", "platform": "android", "app_version": "0.1.1"}` AND a row already exists with the same `(user_id, platform, token)` triple from a prior call (with `app_version="0.1.0"`)
- **THEN** the response status is `204 No Content` AND `SELECT COUNT(*) FROM user_fcm_tokens WHERE user_id = :u AND platform = 'android' AND token = 'abc123'` returns exactly 1 AND that row's `last_seen_at` is later than its `created_at` AND that row's `app_version` is now `"0.1.1"` AND the structured log event has `created=false`

#### Scenario: Same user can register multiple tokens across platforms
- **WHEN** an authenticated user calls `POST /api/v1/user/fcm-token` with `{"token": "android-token", "platform": "android"}` AND then with `{"token": "ios-token", "platform": "ios"}`
- **THEN** both calls return `204 No Content` AND `SELECT COUNT(*) FROM user_fcm_tokens WHERE user_id = :u` returns 2

#### Scenario: `user_id` comes from JWT, not the request body
- **WHEN** an authenticated user (with `UserPrincipal.userId = U_AUTH`) calls `POST /api/v1/user/fcm-token` with a body that includes a `user_id` field set to a different UUID `U_OTHER`
- **THEN** the inserted row has `user_id = U_AUTH` (the JWT-derived value) AND any `user_id` field in the request body is ignored

#### Scenario: 4 KB body cap rejects oversized requests
- **WHEN** an authenticated user posts a body of 4097 bytes
- **THEN** the response status is `413 Payload Too Large` AND no row is inserted or updated

### Requirement: Validation errors MUST use a closed vocabulary

The request DTO MUST declare `token: String` and `platform: String` as **non-nullable** fields. A missing `token` or `platform` therefore fails Kotlinx-serialization at deserialization time and surfaces as `malformed_body` (NOT as `empty_token` / `invalid_platform`). The closed-vocabulary `empty_token` / `invalid_platform` codes apply only to deserialization-success-but-content-invalid cases (empty/whitespace token, or platform outside `{android, ios}`).

The handler SHALL validate request body fields and reject invalid input with HTTP `400 Bad Request` and a JSON body of shape `{"error": "<code>"}` where `<code>` is exactly one of:

- `"invalid_platform"` — `platform` field deserialized successfully but is empty after trimming OR is not one of `"android"` / `"ios"` (case-sensitive lowercase only)
- `"empty_token"` — `token` field deserialized successfully but is empty after trimming whitespace
- `"app_version_too_long"` — `app_version` field is present and exceeds 64 characters
- `"malformed_body"` — request body is not valid JSON, is missing the required content-type header `application/json`, fails to deserialize into the expected DTO shape, OR is missing a non-nullable required field (`token` or `platform`)

Other failure modes MAY use HTTP-standard responses without a body-level `error` field: `401 Unauthorized` (missing/invalid JWT), `413 Payload Too Large` (body > 4 KB), `415 Unsupported Media Type` (request body declared as a non-JSON content-type), `500 Internal Server Error` (database connection failure or other unexpected exception).

The original exception (when one exists) MUST be logged at WARN level with full context for operator debugging; the response body MUST NOT contain stack traces, exception messages, or any information beyond the closed-vocabulary `error` code.

**Token confidentiality**: the WARN log MUST NOT include the raw `token` field value in any form (plaintext, stringified DTO, exception `.toString()`, request-body capture). FCM tokens are device-addressed credentials — anyone holding a token plus the FCM project's server key can push to that device, so leaking a token to operator logs creates an exfiltration surface comparable to leaking a session-id. If token-presence telemetry is needed for debugging (e.g., "did the request body have a non-empty token?"), the log MAY include a derived signal such as `token_present=true` or `token_length=163` but NEVER the value itself. Same posture applies to the `Authorization: Bearer <jwt>` header — never logged. See `design.md` D11.

#### Scenario: `platform: "web"` is rejected with `invalid_platform`
- **WHEN** an authenticated user posts `{"token": "abc", "platform": "web"}`
- **THEN** the response status is `400 Bad Request` AND the response body is `{"error": "invalid_platform"}` AND no row is inserted

#### Scenario: `platform: "Android"` (mixed case) is rejected with `invalid_platform`
- **WHEN** an authenticated user posts `{"token": "abc", "platform": "Android"}`
- **THEN** the response status is `400 Bad Request` AND the response body is `{"error": "invalid_platform"}` AND no row is inserted

#### Scenario: Empty `token` is rejected with `empty_token`
- **WHEN** an authenticated user posts `{"token": "", "platform": "android"}` OR `{"token": "   ", "platform": "android"}` (whitespace-only)
- **THEN** the response status is `400 Bad Request` AND the response body is `{"error": "empty_token"}` AND no row is inserted

#### Scenario: Missing `token` field is rejected with `malformed_body`
- **WHEN** an authenticated user posts `{"platform": "android"}` (no `token` field)
- **THEN** the response status is `400 Bad Request` AND the response body is `{"error": "malformed_body"}` AND no row is inserted (token is non-nullable in the DTO; deserialization fails before validation runs)

#### Scenario: Missing `platform` field is rejected with `malformed_body`
- **WHEN** an authenticated user posts `{"token": "abc"}` (no `platform` field)
- **THEN** the response status is `400 Bad Request` AND the response body is `{"error": "malformed_body"}` AND no row is inserted (platform is non-nullable in the DTO; deserialization fails before validation runs)

#### Scenario: `app_version` longer than 64 chars is rejected with `app_version_too_long`
- **WHEN** an authenticated user posts a body whose `app_version` field is 65 chars or longer
- **THEN** the response status is `400 Bad Request` AND the response body is `{"error": "app_version_too_long"}` AND no row is inserted

#### Scenario: Malformed JSON is rejected with `malformed_body`
- **WHEN** an authenticated user posts a body that is not valid JSON (e.g., `{token: "abc", platform: "android"}` — unquoted keys)
- **THEN** the response status is `400 Bad Request` AND the response body is `{"error": "malformed_body"}` AND no row is inserted

#### Scenario: Validation errors do not leak exception messages
- **WHEN** any of the above 4xx scenarios fires
- **THEN** the response body field `error` is exactly one of the closed-vocabulary codes AND the response body has no other fields (no `message`, no `stack`, no `cause`)

#### Scenario: WARN logs do NOT include the raw token value
- **WHEN** any 4xx OR 5xx failure path fires AND the handler emits a WARN-level log line for operator debugging
- **THEN** the captured log output does NOT contain the raw `token` field value (verified by asserting the well-known test-input token string is absent from the captured log appender's output)

### Requirement: Endpoint MUST require a valid JWT

The endpoint MUST be installed inside the JWT-authenticated route block — same pattern as every other `/api/v1/user/*` endpoint per the [`auth-jwt`](openspec/specs/auth-jwt/spec.md) capability. Requests without a valid `Authorization: Bearer <jwt>` header MUST receive `401 Unauthorized` from the auth middleware before reaching the handler.

#### Scenario: Missing Authorization header is rejected with 401
- **WHEN** a request `POST /api/v1/user/fcm-token` is made with no `Authorization` header AND a valid JSON body
- **THEN** the response status is `401 Unauthorized` AND no row is inserted

#### Scenario: Invalid JWT is rejected with 401
- **WHEN** a request `POST /api/v1/user/fcm-token` is made with `Authorization: Bearer not-a-real-jwt` AND a valid JSON body
- **THEN** the response status is `401 Unauthorized` AND no row is inserted

#### Scenario: Expired JWT is rejected with 401
- **WHEN** a request `POST /api/v1/user/fcm-token` is made with an expired JWT AND a valid JSON body
- **THEN** the response status is `401 Unauthorized` AND no row is inserted

### Requirement: Schema MUST support the deferred Phase 2 on-send-failure delete and Phase 3.5 stale-cleanup contracts

The `user_fcm_tokens` schema introduced by this change MUST be designed such that two future deferred GC paths can be efficiently implemented in subsequent changes WITHOUT any further schema migration:

1. **Phase 2 on-send-failure delete** — when an FCM Admin SDK send returns `404 NOT_FOUND` or `410 GONE` for a given `(user_id, platform, token)` triple, the future Phase 2 send-path SHALL execute `DELETE FROM user_fcm_tokens WHERE user_id = :u AND platform = :p AND token = :t`. The UNIQUE index on `(user_id, platform, token)` introduced in this change MUST make this DELETE a single index-lookup operation. This is the on-send-failure GC path per [`docs/05-Implementation.md:1399`](docs/05-Implementation.md).

2. **Phase 3.5 stale-cleanup worker** — on a weekly schedule via `/internal/cleanup` (OIDC-authed Cloud Scheduler call), the future worker SHALL execute `DELETE FROM user_fcm_tokens WHERE last_seen_at < NOW() - INTERVAL '30 days'`. The `user_fcm_tokens_last_seen_idx` index on `last_seen_at` introduced in this change MUST make this DELETE an index-range scan (NOT a full-table scan). This is the long-tail backstop per [`docs/05-Implementation.md:1400`](docs/05-Implementation.md).

Neither GC path is implemented in this change. This requirement constrains the schema design so both can land additively in future changes.

#### Scenario: Schema supports the deferred on-send delete shape
- **WHEN** a future change implements the Phase 2 send-side `DELETE FROM user_fcm_tokens WHERE user_id = :u AND platform = :p AND token = :t`
- **THEN** the DELETE uses the UNIQUE index on `(user_id, platform, token)` (single index lookup, no full-table scan)

#### Scenario: Schema supports the deferred stale-cleanup shape
- **WHEN** a future change implements the Phase 3.5 weekly `DELETE FROM user_fcm_tokens WHERE last_seen_at < NOW() - INTERVAL '30 days'`
- **THEN** the DELETE uses the `user_fcm_tokens_last_seen_idx` index on `last_seen_at` (range scan, no full-table scan)
