# NearYouID - Technical Implementation

Database schemas, algorithms, auth/session implementation, rate limiting implementation, cache key formats, feature flags, and key implementation notes.

---

## Authentication Implementation

### JWT Strategy: Asymmetric Primary + HS256 WSS Companion

Two tokens, two purposes, one primary key pair:

**1. Ktor REST JWT**: **RS256 asymmetric**, short-lived (15 minutes), refresh token 30 days.
- Backend signs with a private key in GCP Secret Manager
- Exposes public key via `https://api.nearyou.id/.well-known/jwks.json` with `kid` header
- Supports scheduled rotation via multiple kids in JWKS (new kid rolling deploy, old kid retained during TTL overlap, no user re-auth)
- **Claims**: `sub` (user_id), `exp`, `iat`, `token_version` (integer, compared against `users.token_version` on every request for instant revocation), `kid` (header)

**2. Supabase Realtime JWT**: **HS256** (Supabase-compatible).
- Issued by the Ktor endpoint `/api/v1/realtime/token` after verifying the Ktor JWT
- TTL 1 hour, claims `sub` (user_id), `role: 'authenticated'`, `exp`, `iat`
- Signed with the Supabase Project JWT Secret (shared via GCP Secret Manager)

**Rationale**: Supabase Realtime Authorization without paid Third-Party Auth only supports HS256 natively. Maintaining two tracks:
- REST future-proof: RS256 with proper JWKS + kid rotation
- WSS compatible: HS256 with Supabase-managed secret

**Migration path to Third-Party Auth (trivial, ~2-3 days of work)**: when MAU >10k and Third-Party Auth becomes affordable, enable it in the Supabase Dashboard, point it at the existing `https://api.nearyou.id/.well-known/jwks.json`, swap WSS token generation from HS256 to RS256 (using the same key pair already used for REST). Zero REST refactor.

**Secret storage** (all in GCP Secret Manager):
- `ktor-rsa-private-key` (single slot, kid rotation via versions)
- `supabase-jwt-secret`
- `backup-age-private-key` (for restore; encryption uses the public key baked into the backup image)
- `jitter-secret` (256-bit, long-lived)
- `revenuecat-webhook-secret`
- `revenuecat-webhook-hmac-secret` (optional)
- `resend-api-key`
- `firebase-admin-sa` (for Remote Config server access)
- `apns-key-p8`
- `admin-app-db-connection-string` (scoped role for the admin panel)
- `admin-session-cookie-signing-key` (optional; reserved for future signed-cookie mode if the opaque-token scheme is ever swapped)
- `csam-archive-aes-key` (AES-256 for `csam_detection_archive.encrypted_metadata`)
- `cf-worker-csam-secret` (HMAC-SHA256 key shared between the Cloudflare Worker forwarding path and the Ktor CSAM webhook handler; used only once the Phase 2+ Worker path is enabled)
- `invite-code-secret` (256-bit random, HMAC key for deterministic invite-code derivation from `user_id`)
- `content-moderation-fallback-list` (Firebase Remote Config secret-string slot; see "Content Moderation Keyword Lists" below)

Staging uses the same slot list with a `staging-*` prefix (see `04-Architecture.md` Deployment > Secret Manager namespace).

**Incident-only rotation procedure** (HS256 compromise scenario):
1. Generate new Supabase JWT secret, update Dashboard (active Realtime sessions kicked within ~20 min cache bust)
2. Update GCP Secret Manager Supabase slot
3. Rolling deploy Ktor
4. Audit log all pre-rotation refresh tokens

**Scheduled rotation (Ktor RS256 kid)**: zero-disruption.
1. Generate new RSA key pair, add as a new kid in JWKS
2. Configure Ktor to sign with the new kid (gradual rollout 10% then 100%)
3. Retain old kid in JWKS for 30 days (to cover the refresh token max TTL)
4. Remove old kid after the retention period

### Anomaly Detection Metrics

- Subscribe rate per `sub`: alert if one user subscribes to >50 conversation channels within 5 minutes
- JWT with the same `sub` issued from >5 geographic locations within 1 hour
- JWT verify fail rate spike >1% of total (possible secret compromise or Supabase cache sync lag)
- RevenueCat webhook signature fail rate: any non-zero should be investigated
- Alert destinations: Sentry + Slack webhook to admin channel

### Client State Machine for Token Refresh

Priority order on 401:
1. **Ktor JWT expired** (REST 401): use `refresh_token` to call `POST /api/v1/auth/refresh`, returning a new Ktor JWT + new Supabase JWT (single round-trip)
2. **Supabase JWT expired only** (WSS disconnect): use the valid Ktor JWT to call `GET /api/v1/realtime/token`
3. **Refresh token expired/invalid**: force re-auth via Google/Apple + attestation
4. **Both expired together**: path (1) handles it

**Preemptive refresh**: when the app wakes / cold starts, check the expiry of both JWTs. If they expire in <5 minutes, refresh asynchronously and non-blocking.

**Mutex to avoid refresh storms**: client uses single-flight pattern (concurrent 401s queue behind a single refresh). Server allows a 30-second overlap window for the same refresh token.

### RLS Policy with Regex Guard

```sql
CREATE POLICY "participants_can_subscribe" ON realtime.messages
FOR SELECT USING (
    realtime.topic() ~ '^conversation:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    AND EXISTS (
        SELECT 1 FROM public.conversation_participants cp
        WHERE cp.conversation_id = (split_part(realtime.topic(), ':', 2))::uuid
            AND cp.user_id = ((auth.jwt()->>'sub')::uuid)
            AND cp.left_at IS NULL
            AND NOT EXISTS (
                SELECT 1 FROM public.users u
                WHERE u.id = cp.user_id AND u.is_shadow_banned = TRUE
            )
    )
);
```

Regex-first gate ensures short-circuit AND evaluates regex before cast. Malformed topic, missing delimiter, or invalid UUID format fails the regex and denies silently, without raising.

**Topic format**: `conversation:<uuid>` (string delimiter + canonical UUID format).

**Mandatory test cases in Phase 1**:
- User who is NOT a participant then deny
- User whose `left_at` is set then deny
- Shadow-banned user then deny
- JWT with `sub` not present in `public.users` then deny (defense)
- Malformed topic (`conversation:`, `conversation`, no delimiter) then deny via regex
- Topic with invalid UUID format (`conversation:not-a-uuid`) then deny via regex
- SQL injection attempt via topic then deny via regex
- Empty/null topic then deny via regex

### Apple Sign-In Specifics

**Apple Private Relay email**:
- User selects "Hide My Email" and gets back `xxx@privaterelay.appleid.com`
- Store `users.email` = relay or real email (treat the same)
- Column `users.apple_relay_email BOOLEAN`
- Do not send marketing email to relay addresses (high bounce rate, leads to blacklist)

**Apple S2S Notification endpoint** (mandatory per Apple Dev Agreement):
- Endpoint: `POST /internal/apple/s2s-notifications`
- Verify Apple JWT signature via Apple JWKS, verify `aud` claim = bundle ID
- Dedup via `transaction_id` (idempotent)
- Events handled:
  - `email-disabled` / `email-enabled`: update the `apple_relay_email` flag
  - `consent-revoked`: user revoked Sign in with Apple, treated as account delete intent, kick sessions + 30-day tombstone grace
  - `account-delete`: user deleted Apple ID, hard tombstone immediately (Apple-required, no grace)
- Register the notification endpoint in the Apple Developer Console
- Batch send safeguard: re-check the `apple_relay_email` flag per batch for campaigns with >1000 recipients

**Relay email change detection**: On every sign-in (not signup), the server compares `id_token.email` vs stored `users.email`. On mismatch + `apple_relay_email = TRUE`, update email, log the `apple.relay_email_changed` event, and send an in-app notification + Resend email (user-facing): "Email bayangan Apple kamu sudah diperbarui".

---

## Session Management

**Access token (Ktor RS256 JWT)**: TTL 15 minutes, stateless.

**Refresh token**: TTL 30 days, stateful in Postgres, 1 row per device with `family_id`.

### Schema

```sql
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY,
    family_id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id),
    device_fingerprint_hash TEXT,
    token_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    used_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX refresh_tokens_token_hash_idx ON refresh_tokens (token_hash);
CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);
CREATE INDEX refresh_tokens_family_active_idx 
    ON refresh_tokens (family_id) WHERE revoked_at IS NULL;
CREATE INDEX refresh_tokens_expires_idx 
    ON refresh_tokens (expires_at) WHERE revoked_at IS NULL;
```

### Rotation Logic (`POST /api/v1/auth/refresh`)

- Valid token (used_at IS NULL or within 30s overlap): issue a new token + mark old `used_at = NOW()`, update `last_used_at` on all uses
- Valid token but `used_at` is set AND outside the overlap: **reuse detected**:
  - Revoke the ENTIRE family: `UPDATE refresh_tokens SET revoked_at = NOW() WHERE family_id = ?`
  - Increment `users.token_version` (kicks all active sessions)
  - Invalidate Redis cache for that user
  - Force re-auth via Google/Apple + attestation
  - Log event: `security.token_reuse_detected` with user_id, family_id, IP, user agent
- Revoked token: 401, force re-auth

### Cleanup Jobs (Cloud Scheduler calls `/internal/cleanup`)

- Daily: delete `WHERE expires_at < NOW() - INTERVAL '1 day'`
- Weekly: delete `WHERE last_used_at < NOW() - INTERVAL '90 days'`
- Rotated token: deleted immediately on successful rotation (part of the flow)

### Instant Global Revocation via `users.token_version`

- JWT payload includes `token_version`
- On every authenticated request: check JWT `token_version` == DB `token_version`
- Cached in Redis with TTL 5 minutes, explicit invalidation when incremented
- Fallback if Redis is down: fall through to direct DB query + circuit breaker alert

### Revocation Latency per Use Case

| Use Case | Action | Latency |
|----------|--------|---------|
| Logout single device | Delete refresh token row | Max 15 minutes |
| Log out of all devices | Delete all + increment token_version + invalidate cache | Instant (max 5 min cache) |
| Ban user | Set is_banned + increment token_version + delete tokens | Instant |
| Suspend user 7 days | Set is_banned + suspended_until + increment token_version + delete tokens | Instant |
| Delete account | Mark deleted_at + increment + delete tokens | Instant |
| Refresh token reuse detected | Revoke family + increment + force re-auth | Instant |
| Normal token expire | Rotation | 0 (seamless) |

- No conventional logout (WA/Telegram model)
- Re-auth is required when: first registration, new device, idle >30 days, switching accounts, "log out of all devices", reuse detection

---

## Users Schema (Canonical)

```sql
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(60) NOT NULL UNIQUE,
    display_name VARCHAR(50) NOT NULL,
    bio VARCHAR(160),
    email TEXT,
    google_id_hash TEXT UNIQUE,
    apple_id_hash TEXT UNIQUE,
    apple_relay_email BOOLEAN DEFAULT FALSE,
    date_of_birth DATE NOT NULL,
    private_profile_opt_in BOOLEAN NOT NULL DEFAULT FALSE,
    privacy_flip_scheduled_at TIMESTAMPTZ,
    is_shadow_banned BOOLEAN NOT NULL DEFAULT FALSE,
    is_banned BOOLEAN NOT NULL DEFAULT FALSE,
    suspended_until TIMESTAMPTZ,
    device_fingerprint_hash TEXT,
    token_version INT NOT NULL DEFAULT 0,
    username_last_changed_at TIMESTAMPTZ,
    invite_code_prefix VARCHAR(8) NOT NULL UNIQUE,
    analytics_consent JSONB NOT NULL DEFAULT '{"analytics": false, "crash": true, "ads_personalization": false}',
    subscription_status VARCHAR(32) NOT NULL DEFAULT 'free'
        CHECK (subscription_status IN ('free', 'premium_active', 'premium_billing_retry')),
    inviter_reward_claimed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    deleted_at TIMESTAMPTZ,
    CHECK (date_of_birth <= (CURRENT_DATE - INTERVAL '18 years'))
);

CREATE INDEX users_username_lower_idx ON users (LOWER(username));
CREATE INDEX users_email_idx ON users(email) WHERE deleted_at IS NULL;
CREATE INDEX users_suspended_idx ON users(suspended_until) WHERE suspended_until IS NOT NULL;
CREATE INDEX users_subscription_idx ON users(subscription_status) WHERE deleted_at IS NULL;
CREATE INDEX users_privacy_flip_idx ON users(privacy_flip_scheduled_at) WHERE privacy_flip_scheduled_at IS NOT NULL;
```

**Notes**:
- Registration is 18+ only. The `CHECK (date_of_birth <= CURRENT_DATE - INTERVAL '18 years')` constraint is a defense-in-depth backstop behind the application-layer age gate; any insert with a DOB that makes the user younger than 18 is rejected at the DB.
- `suspended_until`: set together with `is_banned = TRUE` for a time-boxed suspension. A daily worker (`/internal/unban-worker`) flips `is_banned = FALSE` and nulls `suspended_until` once it elapses.
- `subscription_status` CHECK enforces the 3-state machine documented in `01-Business.md`.
- `inviter_reward_claimed_at`: lifetime sentinel for the referral system. Set exactly once, when the user's 5th successful referral triggers the inviter grant. Non-null means the inviter has already claimed their lifetime referral reward and can never receive another, regardless of subsequent successful referrals.
- `username VARCHAR(60)`: the schema ceiling is 60 to accommodate the worst-case auto-generated fallback handle `{adjective}_{noun}_{uuid8hex}`. When a Premium user customizes their username via `PATCH /api/v1/user/username`, the application layer enforces a tighter 30-character cap. The 60-char ceiling is not exposed to end users; they only see auto-generated (≤60) or Premium-custom (≤30).
- `username_last_changed_at`: enforces the 1-change-per-30-days cooldown for Premium username customization. Set only when a Premium user successfully changes their username; NULL for users still on the auto-generated handle.
- `privacy_flip_scheduled_at`: set to `NOW() + 72 hours` when a private-profile user downgrades to Free. During the 72h window the user is still `effective private = TRUE`; a daily worker (`/internal/privacy-flip-worker`) flips `private_profile_opt_in = FALSE` and nulls this column once the deadline elapses. Re-subscribing to Premium before the deadline cancels the flip (the worker clears `privacy_flip_scheduled_at` on any `premium_active` transition). See the Privacy Flip Worker section below.
- `invite_code_prefix VARCHAR(8) NOT NULL UNIQUE`: stable 8-character base32 prefix for the user's invite code. Populated at signup via `base32(HMAC-SHA256(invite-code-secret, user_id.bytes))[0..8]`. The column is indexed UNIQUE so that invite-code → inviter resolution at a referring signup is O(1) instead of scanning all users. In the rare event of an 8-char base32 collision across two new signups in the same transaction batch, the UNIQUE constraint forces a retry with a longer prefix (falling back to a 10-character prefix); the collision rate at 100k users is astronomically low but the retry path is implemented for completeness.

**Effective private state** = `private_profile_opt_in = TRUE AND subscription_status IN ('premium_active', 'premium_billing_retry')`. Note: during the 72h privacy-flip window (user downgraded to Free with private profile still on), `private_profile_opt_in` remains TRUE and `subscription_status` is `free`, so effective private resolves to FALSE for the purposes of the formula. The warning-then-flip flow uses `privacy_flip_scheduled_at` to track the worker deadline; the app client short-circuits to "still private" in the UX layer during the 72h grace by checking `privacy_flip_scheduled_at IS NOT NULL AND NOW() < privacy_flip_scheduled_at`. See the Privacy Flip Worker section.

---

## Reserved Usernames Schema

```sql
CREATE TABLE reserved_usernames (
    username TEXT PRIMARY KEY,
    reason VARCHAR(64) NOT NULL,
    source VARCHAR(16) NOT NULL DEFAULT 'admin_added'
        CHECK (source IN ('seed_system', 'admin_added')),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX reserved_usernames_source_idx ON reserved_usernames(source);

-- Auto-maintain updated_at on UPDATE
CREATE OR REPLACE FUNCTION reserved_usernames_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER reserved_usernames_updated_at_trigger
BEFORE UPDATE ON reserved_usernames
FOR EACH ROW EXECUTE FUNCTION reserved_usernames_set_updated_at();

-- Block deletion/modification of seed_system rows (belt-and-suspenders; also enforced at admin UI)
CREATE OR REPLACE FUNCTION reserved_usernames_protect_seed()
RETURNS TRIGGER AS $$
BEGIN
    IF (TG_OP = 'DELETE' AND OLD.source = 'seed_system') THEN
        RAISE EXCEPTION 'Cannot delete seed_system reserved username: %', OLD.username;
    END IF;
    IF (TG_OP = 'UPDATE' AND OLD.source = 'seed_system' AND NEW.source != 'seed_system') THEN
        RAISE EXCEPTION 'Cannot change source of seed_system reserved username: %', OLD.username;
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER reserved_usernames_protect_seed_trigger
BEFORE UPDATE OR DELETE ON reserved_usernames
FOR EACH ROW EXECUTE FUNCTION reserved_usernames_protect_seed();
```

Seeded via Flyway with `source = 'seed_system'` entries: `admin`, `support`, `moderator`, `system`, `nearyou`, `staff`, `official`, `akun_dihapus`, `deleted_user`, and every 1-2 character lowercase string. All entries stored in lowercase; the signup flow normalizes the candidate before the check.

**Immutability**: rows with `source = 'seed_system'` cannot be removed via the Admin Panel (UI guard + DB trigger). Admins can only add or remove rows where `source = 'admin_added'`. This prevents accidental deletion of critical system reservations like `admin` or `akun_dihapus`.

**Premium username customization check**: before accepting a user-supplied custom username, the server runs:

```sql
SELECT 1 FROM reserved_usernames WHERE username = LOWER(:candidate);
```

Signup pre-check (for auto-generated candidates):
```sql
SELECT 1 FROM reserved_usernames WHERE username = LOWER(:candidate);
```

On hit, the flow retries with the next candidate in the generation algorithm (signup) or returns a user-facing error "Username ini tidak tersedia" (Premium customization).

---

## Rejected Identifiers Schema (Anti-Bypass for Under-18 Signup)

```sql
CREATE TABLE rejected_identifiers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    identifier_hash TEXT NOT NULL,
    identifier_type VARCHAR(8) NOT NULL CHECK (identifier_type IN ('google', 'apple')),
    reason VARCHAR(32) NOT NULL CHECK (reason IN ('age_under_18', 'attestation_persistent_fail')),
    rejected_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (identifier_hash, identifier_type)
);

CREATE INDEX rejected_identifiers_hash_idx ON rejected_identifiers(identifier_hash);
```

**Privacy note**: only the hashed Google/Apple identifier is stored, not DOB, not email, not name. The purpose is narrow: prevent a user who declared DOB <18 from retrying signup with a different DOB using the same identifier.

**Signup check**: before creating a users row, verify `NOT EXISTS (SELECT 1 FROM rejected_identifiers WHERE identifier_hash = :hash AND identifier_type = :type)`. On hit, return the same user-facing under-18 rejection message to avoid confirming the state to the user.

**Retention**: indefinite (anti-abuse). Can be purged on legitimate adult re-verification workflow (support form + manual admin clear).

---

## Suspension Unban Worker

Cloud Scheduler daily at 04:00 WIB calls `/internal/unban-worker`:

```sql
UPDATE users
SET is_banned = FALSE,
    suspended_until = NULL
WHERE is_banned = TRUE
  AND suspended_until IS NOT NULL
  AND suspended_until <= NOW()
  AND deleted_at IS NULL;
```

Audit log inserted per unban. Permanent bans (`suspended_until IS NULL`) are untouched.

---

## Privacy Flip Worker

Handles the 72-hour grace window where a user who had `private_profile_opt_in = TRUE` as a Premium subscriber is downgraded to Free. Per the UX spec, private profile is NOT immediately flipped; the user gets 72 hours to re-subscribe or accept the public switch.

### Trigger (RevenueCat webhook handler)

On a webhook transition that sets `subscription_status = 'free'` (either `EXPIRATION` or a grace-period elapse), the handler runs:

```sql
UPDATE users
SET privacy_flip_scheduled_at = COALESCE(privacy_flip_scheduled_at, NOW() + INTERVAL '72 hours')
WHERE id = :user_id
  AND private_profile_opt_in = TRUE
  AND privacy_flip_scheduled_at IS NULL;
```

The `COALESCE` + `privacy_flip_scheduled_at IS NULL` guard makes the update idempotent (re-processing the same webhook does not push the deadline back).

The handler then inserts a `notifications` row with `type = 'privacy_flip_warning'` and `body_data = {"privacy_flip_scheduled_at": ...}` and sends an FCM push + in-app banner.

### Cancellation (re-subscribe before deadline)

When a webhook transitions `subscription_status` back to `premium_active` before the deadline:

```sql
UPDATE users
SET privacy_flip_scheduled_at = NULL
WHERE id = :user_id
  AND privacy_flip_scheduled_at IS NOT NULL
  AND privacy_flip_scheduled_at > NOW();
```

The in-app banner clears on the next client poll.

### Worker (hourly Cloud Scheduler calls `/internal/privacy-flip-worker`)

```sql
UPDATE users
SET private_profile_opt_in = FALSE,
    privacy_flip_scheduled_at = NULL
WHERE privacy_flip_scheduled_at IS NOT NULL
  AND privacy_flip_scheduled_at <= NOW()
  AND subscription_status = 'free'
  AND deleted_at IS NULL
RETURNING id;
```

The worker runs **hourly** rather than daily because the UX promises "72 hours" not "3 sleeps"; an hourly granularity keeps the user's experience aligned with the banner countdown. Affected user IDs are returned to the worker for cache invalidation (Redis `user:<id>` profile cache busted).

Audit log entry written per flip with `action_type = 'privacy_flip_applied'`, `before_state = {private: true}`, `after_state = {private: false}`, `reason = 'premium_downgraded_grace_expired'`.

---

## Username Generation & Customization

### DB Constraints

The `username VARCHAR(60) NOT NULL UNIQUE` constraint and `users_username_lower_idx` index are defined inline in the `users` table schema above. No additional ALTER needed.

### Atomic Generation Algorithm (server-side, register endpoint)

Used only at signup. Produces an auto-generated username ≤60 chars.

```
for attempt in 1..5:
    if attempt <= 2:
        candidate = "{adj}_{noun}"                    # typically ≤30 chars
    elif attempt <= 4:
        candidate = "{adj}_{noun}_{modifier}"         # typically ≤40 chars
    else:
        candidate = "{adj}_{noun}_{random_5_digit}"   # typically ≤35 chars

    if EXISTS in reserved_usernames(LOWER(candidate)): continue
    if EXISTS in username_history(LOWER(candidate)) AND released_at > NOW(): continue
    try INSERT INTO users(..., username=candidate) → success, return
    catch unique_violation → continue

# Worst-case fallback (≤60 chars, fits the schema ceiling)
candidate = "{adj}_{noun}_{uuid8hex}"
INSERT
```

### Username History Schema (30-Day Release Hold)

Tracks historical usernames held during the post-change release hold window. Used both for collision check and for impersonation prevention.

```sql
CREATE TABLE username_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    old_username VARCHAR(60) NOT NULL,
    new_username VARCHAR(60) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    released_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX username_history_old_lower_idx ON username_history(LOWER(old_username));
CREATE INDEX username_history_released_idx ON username_history(released_at);
CREATE INDEX username_history_user_idx ON username_history(user_id, changed_at DESC);
```

- `released_at = changed_at + INTERVAL '30 days'`
- A custom username cannot be claimed if it appears in `username_history` with `released_at > NOW()`
- Row retention: kept indefinitely for audit; only the `released_at > NOW()` guard matters for claim-blocking
- **Partial index note**: the index on `released_at` is NOT partial (no `WHERE released_at > NOW()` predicate). PostgreSQL rejects partial-index predicates that reference `NOW()` because `NOW()` is `STABLE`, not `IMMUTABLE`. The plain B-tree index is sufficient; queries do the `released_at > NOW()` filter at runtime and the planner uses the index range-scan regardless.

### Premium Customization Endpoint (`PATCH /api/v1/user/username`)

Entry preconditions:
- `subscription_status IN ('premium_active', 'premium_billing_retry')`
- Feature flag `premium_username_customization_enabled = TRUE` in Firebase Remote Config
- `username_last_changed_at IS NULL OR username_last_changed_at <= NOW() - INTERVAL '30 days'`
- Account not banned / shadow banned

Request body: `{ "new_username": "..." }`

Validation pipeline (server-side, rejection stops on first fail):
1. Length 3-30 characters
2. Charset regex `^[a-z0-9][a-z0-9_.]*[a-z0-9_]$` (must start with a letter or digit, may contain letters/digits/underscore/dot in the middle, must end with a letter, digit, or underscore — dots are allowed only in the middle, never at the start or end)
3. No consecutive dots: application-layer check `!candidate.contains("..")`. The Postgres regex above cannot cleanly forbid consecutive dots in a single anchored pattern; a second check is mandatory (enforced in the validation helper + covered by unit tests).
4. NOT in `reserved_usernames`
5. NOT in `username_history` with `released_at > NOW()`
6. NOT equal to current `users.username` (no-op guard)
7. NOT matching profanity blocklist or UU ITE keyword list

If step 7 trips the moderation hit threshold, the change is REJECTED upfront with user-facing error, AND a `moderation_queue` row is inserted with `trigger = 'username_flagged'` for admin awareness. Admin can explicitly allow the candidate via an override action if appropriate (resolution `accept_flagged_username`) or confirm the rejection (resolution `reject_flagged_username`).

Transactional flow:

```sql
BEGIN;

-- Re-check cooldown with lock to prevent double-change race
SELECT username, username_last_changed_at
FROM users
WHERE id = :user_id AND deleted_at IS NULL
FOR UPDATE;

-- If username_last_changed_at is too recent: ROLLBACK, return 429

-- Insert into username_history (30-day hold starts now)
INSERT INTO username_history (user_id, old_username, new_username, changed_at, released_at)
VALUES (:user_id, :current_username, :new_username, NOW(), NOW() + INTERVAL '30 days');

-- Apply the change
UPDATE users
SET username = :new_username,
    username_last_changed_at = NOW()
WHERE id = :user_id;

COMMIT;
```

On `unique_violation` (sub-microsecond race with another concurrent change or signup): rollback + return 409 CONFLICT with user-facing message "Username ini sudah dipakai, coba lagi sebentar."

### Availability Probe (`GET /api/v1/username/check?candidate=...`)

Non-authoritative helper for UX live-validation:
- Rate limit 3/day per user (anti-brute-force on probing the reserved list)
- Returns `{ available: true/false, reason?: "reserved"|"taken"|"on_release_hold"|"invalid_format" }`
- Result is advisory; the authoritative check happens at PATCH time inside the transaction

### Rate Limit

- Change attempts: 1 successful change per 30 days per user (DB-enforced via `username_last_changed_at`)
- Failed attempts: 10/hour per user (prevents probing via PATCH)
- Availability probe: 3/day per user

### Feature Flag

`premium_username_customization_enabled` (Firebase Remote Config, default TRUE) is a kill switch. If flipped to FALSE, the PATCH endpoint returns 503 with user-facing "Fitur ganti username sedang dinonaktifkan sementara. Coba lagi nanti."

---

## Post System Implementation

### Coordinate Storage Policy (Anti-Triangulation, Crypto-Safe)

Posts have 2 geography columns:

- `posts.display_location GEOGRAPHY NOT NULL` (fuzzed up to ~500m via HMAC-derived jitter) for Nearby query + distance render
- `posts.actual_location GEOGRAPHY NOT NULL` retained for admin analytics + moderation + reverse geocoding

### Posts Schema

```sql
CREATE TABLE posts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    author_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    content VARCHAR(280) NOT NULL,
    display_location GEOGRAPHY(POINT, 4326) NOT NULL,
    actual_location GEOGRAPHY(POINT, 4326) NOT NULL,
    city_name TEXT,
    city_match_type VARCHAR(16),
    image_id TEXT,
    content_tsv TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED,
    is_auto_hidden BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ
);

CREATE INDEX posts_display_location_idx ON posts USING GIST(display_location);
CREATE INDEX posts_actual_location_idx ON posts USING GIST(actual_location);
CREATE INDEX posts_content_tsv_idx ON posts USING GIN(content_tsv);
CREATE INDEX posts_content_trgm_idx ON posts USING GIN(content gin_trgm_ops);
CREATE INDEX posts_author_idx ON posts(author_id, created_at DESC) WHERE deleted_at IS NULL;
```

### Jitter Generation (HMAC-based, non-reversible)

```
JITTER_SECRET = secret from GCP Secret Manager (256-bit random, long-lived)
jitter_seed = HMAC-SHA256(key=JITTER_SECRET, message=post_id.bytes)

bearing_radians = (bytes_to_uint32(jitter_seed[0..4]) / 2^32) * 2π
distance_meters = (bytes_to_uint32(jitter_seed[4..8]) / 2^32) * 500

display_location = offset_by_bearing(actual_location, bearing_radians, distance_meters)
```

**Properties**:
- Deterministic per `post_id`: same post always renders the same fuzzed location (no averaging attack)
- Non-reversible without `JITTER_SECRET`: attacker cannot compute the offset from the public `post_id`
- Distribution: uniform bearing 0-2π, uniform distance 0-500m (not fixed 500m)

**Secret rotation**: long-lived secret. Rotate only if compromise is detected (requires re-fuzzing the entire posts table, expensive but runnable via Cloud Run Jobs). Not scheduled.

### Query Rules (audit every spatial query path via CI lint)

- `ST_DWithin` for timeline Nearby filter uses `display_location`
- `ST_Distance` for distance rendering uses `display_location`
- Admin spatial queries for moderation/analytics use `actual_location`
- Reverse geocoding `city_name` uses `actual_location` (accuracy matters; 500m jitter could cross admin boundaries)

**Interaction with the 5km floor**: the floor is applied after fuzzing at the render step; documented in the `:shared:distance` module tests.

**Performance**: GIST index on `display_location` (second index, alongside the existing `actual_location` index), storage +2x geography. Negligible.

### Distance Floor + Rounding + Fuzz Order (`renderDistance`)

```kotlin
fun renderDistance(viewer: Location, post: Post, hideDistance: Boolean): String {
    if (hideDistance) return ""
    val fuzzedMeters = haversine(viewer, post.displayLocation)
    val flooredMeters = max(fuzzedMeters, 5_000.0)
    val roundedKm = (flooredMeters / 1_000.0).roundToInt()
    return "${roundedKm}km"
}
```

Order matters: fuzz first (crypto-derived jitter), floor second (UX/privacy), round to nearest 1km last (display). Actual 4.5km then fuzz to 4.8km then floor to 5km then round to 5km: user sees "5km", fuzz does not leak. Actual 7.4km fuzz to 7.3km floor unchanged round to "7km". Actual 7.6km fuzz to 7.7km floor unchanged round to "8km".

### Post Edit History (Race-Safe Temporal Versioning)

```sql
CREATE TABLE post_edits (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    edited_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    content_snapshot VARCHAR(280) NOT NULL,
    location_snapshot GEOGRAPHY NOT NULL,
    edited_by UUID NOT NULL REFERENCES users(id)
);

CREATE UNIQUE INDEX post_edits_temporal_idx ON post_edits(post_id, edited_at);
CREATE INDEX post_edits_post_id_idx ON post_edits(post_id, edited_at DESC);
```

Append-only: each edit is an insert of a new row with a snapshot of the state **before** the edit. Natural ordering via `edited_at DESC`.

**Retention**: kept as long as the parent post exists. Edits are purged only when the parent post is hard-deleted (the `ON DELETE CASCADE` handles it). Rationale: the user-facing "Diedit [relative time]" label + "Riwayat edit" modal must remain coherent for the full lifetime of the post; fixed-time purge would produce empty history modals on old posts.

**Content version display**: computed at query time with `ROW_NUMBER() OVER (PARTITION BY post_id ORDER BY edited_at)`, rendered as user-facing "Versi ke-N".

### Transactional Atomicity (mandatory)

```sql
BEGIN;

SELECT id, content, actual_location, author_id
FROM posts
WHERE id = :post_id AND author_id = :user_id
    AND created_at > NOW() - INTERVAL '30 minutes'
    AND deleted_at IS NULL
FOR UPDATE;

INSERT INTO post_edits (post_id, content_snapshot, location_snapshot, edited_at, edited_by)
SELECT id, content, actual_location, clock_timestamp(), author_id
FROM posts
WHERE id = :post_id;

UPDATE posts SET content = :new_content, updated_at = NOW()
WHERE id = :post_id;

COMMIT;
```

Application-level retry on `unique_violation` edge case (sub-microsecond collision): rollback + return 409 CONFLICT with user-facing message "Coba lagi sebentar."

---

## Follow Schema

```sql
CREATE TABLE follows (
    follower_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    followed_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (follower_id, followed_id),
    CHECK (follower_id != followed_id)
);

CREATE INDEX follows_follower_idx ON follows(follower_id, created_at DESC);
CREATE INDEX follows_followed_idx ON follows(followed_id, created_at DESC);
```

**Rules**:
- Block applies bidirectional DELETE on both rows (see Block User Implementation)
- Following a private-profile user is allowed (server creates the row); visibility of their posts is gated by `follows` EXISTS in the query
- Follow/unfollow rate limit: 50/hour (see Rate Limiting)

---

## Post Likes Schema

```sql
CREATE TABLE post_likes (
    post_id UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (post_id, user_id)
);

CREATE INDEX post_likes_user_idx ON post_likes(user_id, created_at DESC);
CREATE INDEX post_likes_post_idx ON post_likes(post_id, created_at DESC);
```

Like counter aggregation reads via `JOIN visible_users` to exclude shadow-banned contributors from the public counter.

Rate limit: Free 10/day + 500/hour burst, Premium unlimited + 500/hour burst (see Layer 2 table below).

---

## Post Replies Schema

```sql
CREATE TABLE post_replies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    author_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    content VARCHAR(280) NOT NULL,
    is_auto_hidden BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ
);

CREATE INDEX post_replies_post_idx ON post_replies(post_id, created_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX post_replies_author_idx ON post_replies(author_id, created_at DESC) WHERE deleted_at IS NULL;
```

- Flat structure (no nested reply-to-reply threading in MVP)
- Free 20/day, Premium unlimited
- Max 280 chars (matches post length)
- Soft delete only (tombstone label on the parent post's reply list)
- `is_auto_hidden` flag mirrors the post-level flag and is set by the same 3-unique-reporters auto-hide trigger when `reports.target_type = 'reply'`
- Block-aware read path: exclude replies from blocked users (both directions) AND exclude replies where `is_auto_hidden = TRUE` unless the viewer is the author

---

## Reports Schema

```sql
CREATE TABLE reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target_type VARCHAR(16) NOT NULL CHECK (target_type IN ('post', 'reply', 'user', 'chat_message')),
    target_id UUID NOT NULL,
    reason_category VARCHAR(32) NOT NULL CHECK (reason_category IN (
        'spam', 'hate_speech_sara', 'harassment', 'adult_content',
        'misinformation', 'self_harm', 'csam_suspected', 'other'
    )),
    reason_note VARCHAR(200),
    status VARCHAR(16) NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'actioned', 'dismissed')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    reviewed_at TIMESTAMPTZ,
    reviewed_by UUID REFERENCES admin_users(id) ON DELETE SET NULL,
    UNIQUE (reporter_id, target_type, target_id)
);

-- Note: reporter_id is ON DELETE CASCADE because the column is NOT NULL.
-- When a user hard-deletes their account, their submitted reports are
-- cascade-deleted. This is consistent with the Data Export scope matrix
-- ("Reports submitted by the user" is included in the user's export, so
-- they have a copy pre-deletion) and with the Privacy retention policy
-- (see `06-Security-Privacy.md`).
-- reviewed_by is ON DELETE SET NULL so admin churn does not erase
-- moderation history; the action persists without the admin attribution.

CREATE INDEX reports_status_idx ON reports(status, created_at DESC);
CREATE INDEX reports_target_idx ON reports(target_type, target_id);
CREATE INDEX reports_reporter_idx ON reports(reporter_id, created_at DESC);
```

**Auto-hide trigger**: when 3 distinct reporters (accounts >7 days old) report the same `(target_type, target_id)`, the server sets:
- `posts.is_auto_hidden = TRUE` (for target_type = 'post')
- `post_replies.is_auto_hidden = TRUE` (for target_type = 'reply')
- inserts a `moderation_queue` row for admin review

The unique `(reporter_id, target_type, target_id)` prevents the same user from inflating the count via repeat reports.

---

## Moderation Queue Schema

```sql
CREATE TABLE moderation_queue (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    target_type VARCHAR(16) NOT NULL CHECK (target_type IN ('post', 'reply', 'user', 'chat_message')),
    target_id UUID NOT NULL,
    trigger VARCHAR(32) NOT NULL CHECK (trigger IN (
        'auto_hide_3_reports', 'perspective_api_high_score', 'uu_ite_keyword_match',
        'admin_flag', 'csam_detected', 'anomaly_detection', 'username_flagged'
    )),
    status VARCHAR(16) NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'resolved')),
    resolution VARCHAR(32)
        CHECK (resolution IS NULL OR resolution IN (
            'keep', 'hide', 'delete', 'shadow_ban_author', 'suspend_author_7d', 'ban_author',
            'accept_flagged_username', 'reject_flagged_username'
        )),
    priority SMALLINT NOT NULL DEFAULT 5,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at TIMESTAMPTZ,
    resolved_by UUID REFERENCES admin_users(id),
    notes TEXT,
    UNIQUE (target_type, target_id, trigger)
);

CREATE INDEX moderation_queue_status_idx ON moderation_queue(status, priority, created_at);
CREATE INDEX moderation_queue_target_idx ON moderation_queue(target_type, target_id);
```

The UNIQUE constraint prevents duplicate queue entries for the same item + trigger combo. Multi-trigger items (e.g., both auto-hide and Perspective API) produce one row per trigger and surface together in the admin UI grouped by `(target_type, target_id)`.

---

## Notifications Schema (DB-Persisted)

```sql
CREATE TABLE notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type VARCHAR(48) NOT NULL CHECK (type IN (
        'post_liked', 'post_replied', 'followed', 'chat_message',
        'subscription_billing_issue', 'subscription_expired',
        'post_auto_hidden', 'account_action_applied', 'data_export_ready',
        'chat_message_redacted', 'privacy_flip_warning', 'username_release_scheduled',
        'apple_relay_email_changed'
    )),
    actor_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    target_type VARCHAR(16),
    target_id UUID,
    body_data JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    read_at TIMESTAMPTZ
);

CREATE INDEX notifications_user_unread_idx 
    ON notifications(user_id, created_at DESC) WHERE read_at IS NULL;
CREATE INDEX notifications_user_all_idx 
    ON notifications(user_id, created_at DESC);
```

- `body_data` stores type-specific JSON (e.g., liked post title, reply excerpt, billing grace end date, privacy flip deadline, redaction reason summary)
- Retention: 90 days auto-purge via `/internal/cleanup`
- Delivered via FCM push in parallel; the DB row is the source of truth for the in-app list

**Event type catalog**:

| Type | Trigger | Actor | Body data |
|------|---------|-------|-----------|
| `post_liked` | Another user likes a post | liker | `{post_id, post_excerpt}` |
| `post_replied` | Another user replies | replier | `{post_id, reply_excerpt}` |
| `followed` | Another user follows | follower | `{}` |
| `chat_message` | New chat message | sender | `{conversation_id, preview}` |
| `subscription_billing_issue` | RevenueCat `BILLING_ISSUE` | NULL | `{grace_end_at}` |
| `subscription_expired` | Premium period lapsed | NULL | `{}` |
| `post_auto_hidden` | 3 reports auto-hid user's post | NULL | `{post_id, reason}` |
| `account_action_applied` | Admin action on user | NULL | `{action_type, reason, suspended_until}` |
| `data_export_ready` | Export worker finished | NULL | `{signed_url, expires_at}` |
| `chat_message_redacted` | Admin redacted a message in a conversation the user participates in | NULL | `{conversation_id, message_id}` |
| `privacy_flip_warning` | Downgrade-to-Free with private profile, flip scheduled | NULL | `{privacy_flip_scheduled_at}` |
| `username_release_scheduled` | User's custom username change confirmed; old handle releases at `released_at` | NULL | `{old_username, released_at}` |
| `apple_relay_email_changed` | Apple S2S `email-disabled`/`email-enabled` event | NULL | `{new_email_masked}` |

---

## Deletion Requests Schema

```sql
CREATE TABLE deletion_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    scheduled_hard_delete_at TIMESTAMPTZ NOT NULL,
    cancelled_at TIMESTAMPTZ,
    executed_at TIMESTAMPTZ,
    source VARCHAR(24) NOT NULL CHECK (source IN (
        'user',
        'apple_s2s_consent_revoked',
        'apple_s2s_account_delete',
        'admin'
    ))
);

CREATE INDEX deletion_requests_scheduled_idx
    ON deletion_requests(scheduled_hard_delete_at)
    WHERE executed_at IS NULL AND cancelled_at IS NULL;

CREATE INDEX deletion_requests_immediate_idx
    ON deletion_requests(requested_at)
    WHERE source = 'apple_s2s_account_delete'
      AND executed_at IS NULL
      AND cancelled_at IS NULL;
```

**Source semantics**:

| Source | `scheduled_hard_delete_at` | Grace | Trigger |
|--------|---------------------------|-------|---------|
| `user` | `NOW() + 30 days` | 30 days (user can cancel) | User-initiated "Hapus Akun" |
| `admin` | `NOW() + 30 days` | 30 days | Admin-initiated delete |
| `apple_s2s_consent_revoked` | `NOW() + 30 days` | 30 days | Apple S2S `consent-revoked` (user revoked Sign in with Apple); treated as a standard deletion intent |
| `apple_s2s_account_delete` | `NOW()` | None (Apple-required) | Apple S2S `account-delete` (user deleted their Apple ID); must be executed promptly |

- On request: insert a row with the scheduled timestamp per the source semantics above.
- User can cancel during the grace period (only sources with non-zero grace): sets `cancelled_at`. `apple_s2s_account_delete` rows cannot be cancelled.
- **Immediate-execution path for `apple_s2s_account_delete`**: the Apple S2S handler both inserts the row AND synchronously enqueues a one-shot tombstone+cascade job before responding to Apple. This avoids up-to-24h latency from the daily worker. If the synchronous job fails, the daily worker picks it up as a backstop via `deletion_requests_immediate_idx`.
- The daily hard-delete worker scans `scheduled_hard_delete_at <= NOW() AND executed_at IS NULL AND cancelled_at IS NULL`, runs tombstone + cascade + deletion log write, sets `executed_at = NOW()`.

---

## Admin Users Schema

```sql
CREATE TABLE admin_users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email TEXT NOT NULL UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    password_hash TEXT NOT NULL,
    totp_secret_encrypted BYTEA,
    webauthn_enrolled BOOLEAN NOT NULL DEFAULT FALSE,
    role VARCHAR(16) NOT NULL CHECK (role IN ('owner', 'admin', 'moderator', 'read_only')),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_login_at TIMESTAMPTZ
);

CREATE TABLE admin_webauthn_credentials (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id UUID NOT NULL REFERENCES admin_users(id) ON DELETE CASCADE,
    credential_id BYTEA NOT NULL UNIQUE,
    public_key BYTEA NOT NULL,
    sign_count BIGINT NOT NULL DEFAULT 0,
    device_label TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ
);

CREATE TABLE admin_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id UUID NOT NULL REFERENCES admin_users(id) ON DELETE CASCADE,
    session_token_hash TEXT NOT NULL UNIQUE,
    csrf_token_hash TEXT NOT NULL,
    ip INET NOT NULL,
    user_agent TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_active_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);

CREATE INDEX admin_sessions_admin_idx ON admin_sessions(admin_id, created_at DESC);
CREATE INDEX admin_sessions_active_idx ON admin_sessions(expires_at) WHERE revoked_at IS NULL;

-- WebAuthn ceremony state (challenge storage for registration + authentication flows)
CREATE TABLE admin_webauthn_challenges (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id UUID REFERENCES admin_users(id) ON DELETE CASCADE,
    challenge BYTEA NOT NULL,
    ceremony VARCHAR(16) NOT NULL CHECK (ceremony IN ('registration', 'authentication')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ
);

CREATE INDEX admin_webauthn_challenges_admin_idx ON admin_webauthn_challenges(admin_id, created_at DESC);
CREATE INDEX admin_webauthn_challenges_cleanup_idx ON admin_webauthn_challenges(expires_at) WHERE consumed_at IS NULL;
```

- `password_hash`: Argon2id (mandatory)
- `totp_secret_encrypted`: AES-256 encrypted at rest, key in GCP Secret Manager
- Solo admin period: row for Oka with `webauthn_enrolled = FALSE` (TOTP required)
- Before the second admin is onboarded: Oka's row MUST have `webauthn_enrolled = TRUE` and at least one `admin_webauthn_credentials` row
- Session timeout: 30 minutes idle (enforced via `last_active_at`)

### Admin Session Cookie Mechanism

The Admin Panel uses classic server-side sessions (not JWT) because it is a stateful Ktor + HTMX app, not an SPA. Cookie mechanics:

- **Cookie name**: `__Host-admin_session`
- **Attributes**: `Secure; HttpOnly; SameSite=Strict; Path=/; Domain=admin.nearyou.id`
- **Value**: opaque 256-bit random token, base64url encoded (never a user-readable format)
- **Server storage**: SHA256 of the cookie value is stored in `admin_sessions.session_token_hash` (lookup by hash, plain value never persisted)
- **CSRF token**: a second 256-bit random token is issued per session, returned in the login response body, and stored in `admin_sessions.csrf_token_hash` (also SHA256 at rest)
  - Every state-changing request (POST/PATCH/PUT/DELETE) MUST include the CSRF token in a `X-CSRF-Token` header
  - The Ktor middleware verifies `SHA256(header_value) == admin_sessions.csrf_token_hash`
  - Missing or mismatched token then 403 + audit log `admin_csrf_violation`
- **CSRF token rotation**: on every successful login (new session), the CSRF token is regenerated. Within a session the token is stable.
- **Admin-triggered CSAM handler invocation** (`POST /internal/csam-webhook` from Admin Panel): uses the same `X-CSRF-Token` mechanism, plus an additional session-scope check that the calling session has `role IN ('owner', 'admin')`. Read-only admins cannot invoke.
- **Rotation on privilege escalation**: any admin action changing `admin_users.role` also rotates the affected admin's session cookies (old session revoked; user re-authenticates).

### WebAuthn Challenge Lifecycle

- Challenge is generated at the start of a registration or authentication ceremony, inserted with `expires_at = NOW() + INTERVAL '5 minutes'`
- On ceremony completion (verify attestation/assertion), the challenge is marked `consumed_at = NOW()`; re-use is rejected via the `consumed_at IS NOT NULL` guard
- The `admin_webauthn_challenges_cleanup_idx` supports a weekly cleanup job that deletes rows where `expires_at < NOW() - INTERVAL '1 day' AND consumed_at IS NULL` (stale unused challenges)

---

## Admin Regions Schema

```sql
CREATE TABLE admin_regions (
    id SERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    parent_id INT REFERENCES admin_regions(id),
    level VARCHAR(32) NOT NULL CHECK (level IN ('province', 'kabupaten_kota', 'kotamadya')),
    source VARCHAR(8) NOT NULL CHECK (source IN ('BPS', 'OSM')),
    geom GEOGRAPHY(MULTIPOLYGON, 4326) NOT NULL,
    geom_centroid GEOGRAPHY(POINT, 4326) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX admin_regions_geom_idx ON admin_regions USING GIST(geom);
CREATE INDEX admin_regions_centroid_idx ON admin_regions USING GIST(geom_centroid);
CREATE INDEX admin_regions_level_idx ON admin_regions(level);
CREATE INDEX admin_regions_parent_idx ON admin_regions(parent_id);
```

Import script Pre-Phase 1:
1. Download BPS GeoJSON or OSM extract
2. Filter to kabupaten_kota level
3. Add 12-mile maritime buffer for coastal kabupaten (`ST_Buffer` on EPSG:4326 geography, ~22km)
4. Compute the centroid column
5. Insert via COPY
6. Visual spot-check 10 complex kabupaten

---

## Timeline Implementation

### Composite Indexes (mandatory)

```sql
CREATE INDEX posts_timeline_cursor_idx ON posts (created_at DESC, id DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX posts_nearby_cursor_idx ON posts USING GIST 
    (display_location, created_at)
    WHERE deleted_at IS NULL;
```

### Query Patterns (block-aware)

**Nearby**:
```sql
SELECT p.* FROM visible_posts p
WHERE (p.created_at, p.id) < ($cursor_ts, $cursor_id)
  AND ST_DWithin(p.display_location, $viewer_loc, $radius_m)
  AND p.is_auto_hidden = FALSE
  AND p.author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :viewer_id)
  AND p.author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :viewer_id)
ORDER BY p.created_at DESC, p.id DESC
LIMIT 20;
```

**Following**:
```sql
SELECT p.* FROM visible_posts p
WHERE (p.created_at, p.id) < ($cursor_ts, $cursor_id)
  AND p.author_id IN (SELECT followed_id FROM follows WHERE follower_id = :viewer_id)
  AND p.is_auto_hidden = FALSE
  AND p.author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :viewer_id)
  AND p.author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :viewer_id)
ORDER BY p.created_at DESC, p.id DESC
LIMIT 20;
```

**Global**:
```sql
SELECT p.* FROM visible_posts p
WHERE (p.created_at, p.id) < ($cursor_ts, $cursor_id)
  AND p.is_auto_hidden = FALSE
  AND p.author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :viewer_id)
  AND p.author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :viewer_id)
ORDER BY p.created_at DESC, p.id DESC
LIMIT 20;
```

Guest Global timeline omits the `user_blocks` subqueries (no viewer identity).

### Sliding Window Session Tracking (Server-Side via Redis)

- Client generates a `session_id` (UUID) when the app goes to foreground, sends it via the `X-Session-Id` header
- Redis key `timeline_offset:{user:<user_id>}:<session_id>` TTL 1 hour (soft cap)
- Redis key `timeline_rolling:{user:<user_id>}` TTL 1 hour (hard cap, authoritative)
- When soft cap is reached: empty response + upsell flag
- When hard cap is reached: empty response + upsell flag (regardless of session_id rotation)
- Guest rolling counter: `timeline_rolling_guest:{jti:<guest_jwt_jti>}` TTL 1 hour, cap 30 posts.

Key format uses hash tag `{scope:<value>}` for Upstash cluster co-location (multi-key ops are safe).

---

## Search Implementation (PostgreSQL FTS + pg_trgm)

### Setup

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;
-- posts.content_tsv GENERATED as defined in Posts Schema above
-- posts_content_tsv_idx GIN + posts_content_trgm_idx GIN
```

Note: `'simple'` tsvector config because Indonesian stopwords are not available by default in Postgres. Acceptable for MVP. Upgrade path in Month 6+: custom Indonesian dictionary or Meta XLM-R tokenizer service.

### Query Pattern

```sql
SELECT p.*,
       ts_rank(p.content_tsv, plainto_tsquery('simple', :query)) AS rank
FROM visible_posts p
JOIN visible_users u ON p.author_id = u.id
WHERE (
        p.content_tsv @@ plainto_tsquery('simple', :query)
     OR p.content % :query
     OR u.username % :query
      )
  AND p.is_auto_hidden = FALSE
  AND p.author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :viewer_id)
  AND p.author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :viewer_id)
  AND (u.private_profile_opt_in = FALSE
       OR u.subscription_status NOT IN ('premium_active', 'premium_billing_retry')
       OR EXISTS (SELECT 1 FROM follows f WHERE f.follower_id = :viewer_id AND f.followed_id = u.id))
ORDER BY rank DESC, p.created_at DESC
LIMIT 20 OFFSET :offset;
```

### Rate Limit

60 queries/hour per Premium user. Free tier rejects with 403 + upsell paywall.

### Re-index Trigger

On shadow-ban / unban / block / unblock: application code invalidates any Redis search-result cache (if added at scale). The underlying GIN index is auto-maintained.

---

## Direct Messaging Implementation

### Chat Flow (Pre-Swap Period, Months 1-14)

```
Client A: POST /api/v1/chat/:conversation_id/messages { content }
  ↓
Ktor: validates quota, sender is participant, not shadow-banned, not blocked (either direction)
  ↓
Ktor: INSERT INTO chat_messages (persistence)
  ↓
Ktor: Supabase Realtime broadcasts to channel "conversation:<id>"
  ↓
Client B (subscribed, passed RLS): receives broadcast
  ↓
Client B: ACK, render, update UI
  ↓
Client B later: fetches history via REST to resync if needed
```

**Failure handling**:
- INSERT success + broadcast fail: Ktor retries broadcast 3x with exponential backoff. If still failing, log a warning. Client eventually fetches via REST.
- Client offline during broadcast: miss. On next app open, fetch delta via REST `GET /api/v1/chat/:conversation_id/messages?after=:last_message_id`.
- Duplicate broadcast (retry + original both succeed): client dedupes via `message_id` UUID.

### Conversation Participant Schema (Race-Safe 1:1 Enforcement)

```sql
CREATE TABLE conversations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    created_by UUID NOT NULL REFERENCES users(id),
    last_message_at TIMESTAMPTZ
);

CREATE TABLE conversation_participants (
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    slot SMALLINT NOT NULL CHECK (slot IN (1, 2)),
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    left_at TIMESTAMPTZ,
    last_read_at TIMESTAMPTZ,
    PRIMARY KEY (conversation_id, user_id)
);

CREATE UNIQUE INDEX conv_slot_unique 
    ON conversation_participants(conversation_id, slot)
    WHERE left_at IS NULL;

CREATE INDEX conversation_participants_user_active_idx 
    ON conversation_participants (user_id) WHERE left_at IS NULL;
CREATE INDEX conversation_participants_conversation_idx 
    ON conversation_participants (conversation_id);
```

### Insert Flow (at the Ktor application layer)

```sql
BEGIN;
SELECT pg_advisory_xact_lock(hashtext(:conversation_id::text));

WITH existing AS (
    SELECT slot FROM conversation_participants
    WHERE conversation_id = :conversation_id AND left_at IS NULL
)
INSERT INTO conversation_participants (conversation_id, user_id, slot, ...)
VALUES (:conversation_id, :user_id, 
        CASE WHEN NOT EXISTS (SELECT 1 FROM existing WHERE slot = 1) THEN 1
             WHEN NOT EXISTS (SELECT 1 FROM existing WHERE slot = 2) THEN 2
             ELSE NULL END,
        ...);

COMMIT;
```

Partial unique index prevents a 3rd active slot (race-proof). Advisory lock ensures slot assignment is serialized per conversation (no wasted CHECK violation retries).

### Chat Message Schema (Embedded Post)

```sql
CREATE TABLE chat_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    sender_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    content VARCHAR(2000),
    embedded_post_id UUID REFERENCES posts(id) ON DELETE SET NULL,
    embedded_post_snapshot JSONB,
    embedded_post_edit_id UUID REFERENCES post_edits(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    redacted_at TIMESTAMPTZ,
    redacted_by UUID REFERENCES admin_users(id) ON DELETE SET NULL,
    redaction_reason TEXT,
    CHECK (content IS NOT NULL OR embedded_post_id IS NOT NULL OR embedded_post_snapshot IS NOT NULL),
    CHECK ((redacted_at IS NULL AND redacted_by IS NULL AND redaction_reason IS NULL)
        OR (redacted_at IS NOT NULL AND redacted_by IS NOT NULL))
);

CREATE INDEX chat_messages_conv_idx ON chat_messages(conversation_id, created_at DESC);
CREATE INDEX chat_messages_sender_idx ON chat_messages(sender_id, created_at DESC);
CREATE INDEX chat_messages_redacted_idx ON chat_messages(redacted_by, redacted_at DESC) WHERE redacted_at IS NOT NULL;
```

- `CHECK` prevents fully empty messages (both text and embed null simultaneously). The third term `embedded_post_snapshot IS NOT NULL` keeps historical rows valid even after the embedded post is hard-deleted and `embedded_post_id` is set to NULL by the FK cascade.
- Second `CHECK` enforces redaction atomicity: either all three redaction fields are null (normal message) or `redacted_at` + `redacted_by` are both set (admin-redacted). `redaction_reason` is optional free-text.
- `embedded_post_edit_id ON DELETE SET NULL` handles the case where a post_edits row is cascaded on parent-post hard-delete; the chat row survives with a `NULL` pointer and the snapshot still renders.
- `redacted_by ON DELETE SET NULL` so admin churn does not erase redaction history; the redaction persists without the admin attribution.
- **Redaction UX**: when `redacted_at IS NOT NULL`, the client renders the message body as user-facing "Pesan ini telah dihapus oleh moderator." regardless of the original content. The original `content` field stays in the DB for audit; it is not returned to the conversation feed endpoint once redacted. The recipient also receives a `chat_message_redacted` notification (see Notifications Schema below) so their chat list reflects the change on next fetch.

### Block Enforcement in Chat

Send flow check:
```sql
SELECT 1 FROM user_blocks
WHERE (blocker_id = :sender AND blocked_id IN (SELECT user_id FROM conversation_participants WHERE conversation_id = :conv_id AND user_id != :sender))
   OR (blocked_id = :sender AND blocker_id IN (SELECT user_id FROM conversation_participants WHERE conversation_id = :conv_id AND user_id != :sender));
```

Hit then reject 403 with user-facing "Tidak dapat mengirim pesan ke user ini". Existing history remains readable (both parties); only new sends are blocked.

### Post-Swap Chat Persistence (Month 15+)

See `04-Architecture.md` for the diagram and the Redis Streams approach. Summary:

- `XADD stream:conv:<id> * message_id <uuid> ...` for persistence
- Consumer groups per conversation, `XAUTOCLAIM` for failover
- `XTRIM ... MAXLEN ~ 100` to bound memory
- Client fetches history via REST for older messages

---

## Block User Implementation

### Schema

```sql
CREATE TABLE user_blocks (
    blocker_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (blocker_id, blocked_id),
    CHECK (blocker_id != blocked_id)
);

CREATE INDEX user_blocks_blocker_idx ON user_blocks(blocker_id);
CREATE INDEX user_blocks_blocked_idx ON user_blocks(blocked_id);
```

### Block Action Flow

```sql
BEGIN;
INSERT INTO user_blocks (blocker_id, blocked_id)
VALUES (:blocker, :blocked)
ON CONFLICT DO NOTHING;

-- Cascade remove follow relationships bidirectionally
DELETE FROM follows
WHERE (follower_id = :blocker AND followed_id = :blocked)
   OR (follower_id = :blocked AND followed_id = :blocker);

COMMIT;
```

### Query Rules (MUST be at repository layer)

- Timeline filter: exclude `author_id` if a block relationship exists in either direction
- Profile view: 404 if a block relationship exists
- DM send: reject 403 if a block exists (either direction)
- Reply visibility: same
- Search results: same

CI lint rule: detect business queries to `posts`/`users`/`chat_messages`/`post_replies` without a block-exclusion join (except Repository `own-content` path + admin module).

### Rate Limit

30 block/unblock per hour per user (anti-flip-flop).

---

## FCM Token Registration

### Schema

```sql
CREATE TABLE user_fcm_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    platform VARCHAR(8) NOT NULL CHECK (platform IN ('android', 'ios')),
    token TEXT NOT NULL,
    app_version TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, platform, token)
);

CREATE INDEX user_fcm_tokens_user_idx ON user_fcm_tokens(user_id);
CREATE INDEX user_fcm_tokens_last_seen_idx ON user_fcm_tokens(last_seen_at);
```

### Endpoint

`POST /api/v1/user/fcm-token` with body `{ token, platform, app_version }`. Upsert on `(user_id, platform, token)` unique, **update `last_seen_at = NOW()` on every call** (this is the authoritative freshness signal). Expired token (FCM returns 404/410 on send) then delete the row on the spot.

### Cleanup

Two complementary paths:
- **Expired (on send)**: server-side send result 404/410 triggers immediate `DELETE FROM user_fcm_tokens WHERE user_id = ? AND platform = ? AND token = ?`
- **Stale (weekly)**: `/internal/cleanup` deletes `WHERE last_seen_at < NOW() - INTERVAL '30 days'`

---

## Feature Flags (Firebase Remote Config)

Server-side fetch via Firebase Admin SDK, cached with TTL 5 minutes in Redis.

Flags:
- `image_upload_enabled` (boolean, default FALSE): gate for Month 6 launch
- `attestation_mode` (enum `enforce` | `warn` | `off`, default `enforce`): QA bypass
- `attestation_bypass_google_ids_sha256` (list<string>): QA whitelist
- `force_update_min_version` (string): force upgrade floor for the mobile app
- `search_enabled` (boolean, default TRUE): kill switch if FTS load becomes too heavy
- `perspective_api_enabled` (boolean, default TRUE): kill switch stopgap
- `premium_username_customization_enabled` (boolean, default TRUE): kill switch for the Premium username change endpoint if abuse pattern emerges
- `premium_like_cap_override` (integer, default 10): server-side override of the Free-tier daily like cap. Raise to 20 or 30 without a mobile release if D7/D30 retention data shows the cap is too restrictive (see Decision 28 in `08-Roadmap-Risk.md`).
- `moderation_profanity_list` (string-array): profanity blocklist; hot-reloaded via the 5-minute Remote Config cache
- `moderation_uu_ite_list` (string-array): UU ITE keyword list; hot-reloaded via the 5-minute Remote Config cache
- `moderation_match_threshold` (integer, default 3): number of distinct keyword hits required for a "high score" flag

Client-side: Firebase Remote Config SDK fetches on app launch + foreground. Server and client MUST fetch the same keys.

---

## Subscription Event-Level Tracking

```sql
CREATE TABLE subscription_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    event_type VARCHAR(32) NOT NULL
        CHECK (event_type IN ('initial_purchase', 'renewal', 'grant', 'cancellation', 'billing_issue', 'expiration')),
    source VARCHAR(32) NOT NULL
        CHECK (source IN ('paid', 'referral', 'manual_admin')),
    revenuecat_event_id TEXT UNIQUE,
    entitlement_start TIMESTAMPTZ,
    entitlement_end TIMESTAMPTZ,
    amount_rupiah BIGINT,
    platform VARCHAR(16),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX subscription_events_user_idx ON subscription_events(user_id, created_at DESC);
CREATE INDEX subscription_events_source_idx ON subscription_events(source, event_type, created_at);
```

MRR/ARR queries MUST filter `WHERE source = 'paid' AND event_type IN ('initial_purchase', 'renewal')`.

### RevenueCat Webhook Authentication

Endpoint: `POST /internal/revenuecat-webhook` (NOT behind OIDC because RevenueCat doesn't support OIDC; custom auth is used instead).

Auth layers:
1. **Authorization Bearer**: header `Authorization: Bearer <shared_secret>` from GCP Secret Manager `revenuecat-webhook-secret`. Reject 401 on mismatch.
2. **HMAC signature (optional but enabled)**: `X-RevenueCat-Signature` = HMAC-SHA256(body, `revenuecat-webhook-hmac-secret`). Reject 401 on mismatch.
3. **IP allowlist** (optional layer): restrict to RevenueCat's published IP ranges via Cloud Armor or Ktor middleware.
4. **Idempotency**: `revenuecat_event_id` UNIQUE constraint prevents double-processing of replays.

Failed signature = audit log + Sentry alert + 401 response (no body).

---

## Referral System Implementation

### Schema

```sql
CREATE TABLE referral_tickets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    inviter_user_id UUID NOT NULL REFERENCES users(id),
    invitee_user_id UUID NOT NULL REFERENCES users(id),
    status VARCHAR(32) NOT NULL DEFAULT 'pending_activity'
        CHECK (status IN ('pending_activity', 'granted', 'expired', 'voided')),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    activity_checked_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '14 days'),
    UNIQUE (invitee_user_id)
);

CREATE INDEX referral_tickets_inviter_idx ON referral_tickets(inviter_user_id, created_at DESC);
CREATE INDEX referral_tickets_pending_idx 
    ON referral_tickets(status, expires_at) WHERE status = 'pending_activity';
CREATE INDEX referral_tickets_inviter_granted_idx
    ON referral_tickets(inviter_user_id) WHERE status = 'granted';

CREATE TABLE granted_entitlements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    referral_ticket_id UUID REFERENCES referral_tickets(id),
    granted_at TIMESTAMPTZ DEFAULT NOW(),
    duration_days INT NOT NULL,
    source VARCHAR(32) NOT NULL
        CHECK (source IN ('referral', 'manual_admin')),
    grant_role VARCHAR(16) NOT NULL DEFAULT 'invitee'
        CHECK (grant_role IN ('invitee', 'inviter', 'manual_admin')),
    revenuecat_grant_id TEXT,
    UNIQUE (referral_ticket_id, user_id)
);

CREATE INDEX granted_entitlements_user_idx ON granted_entitlements(user_id, granted_at DESC);
CREATE UNIQUE INDEX granted_entitlements_inviter_once_idx
    ON granted_entitlements(user_id)
    WHERE grant_role = 'inviter';
```

**Defaults and constraints**:
- `expires_at` defaults to `created_at + 14 days`, matching the activity gate window.
- `grant_role` distinguishes invitee-registration rewards from the inviter's lifetime milestone reward. `granted_entitlements_inviter_once_idx` is a partial unique index guaranteeing that at most one row with `grant_role = 'inviter'` can ever exist per user, providing DB-level enforcement of the lifetime cap.
- The inviter lifetime cap is also tracked by `users.inviter_reward_claimed_at` (sentinel timestamp; non-null means claimed).

### Reward Rules (Canonical)

- **Invitee**: receives 1 grant of 7 days when their ticket reaches `status = 'granted'` (activity gate passed, anti-fingerprint checks passed). One invitee, one grant, tied to their own registration ticket.
- **Inviter**: receives 1 grant of 7 days in their lifetime, triggered when the inviter's 5th `status = 'granted'` ticket is confirmed. The grant is attached to that 5th ticket.
- Inviters whose `inviter_reward_claimed_at IS NOT NULL` never receive another grant, even if they continue to refer more users.
- Invitees continue to receive their 1-week reward regardless of where the inviter is on the 0-5 counter.

### Ticket Creation Flow

Tickets are created at signup, optionally, if the new user supplies an invite code.

**Endpoint**: `POST /api/v1/auth/signup` (the standard signup endpoint) accepts an optional `invite_code` field in the request body.

**Server-side flow**:

1. Standard signup validation passes (attestation, age gate, `rejected_identifiers` pre-check, identifier uniqueness).
2. `users` row is inserted and the auto-generated username is assigned.
3. If `invite_code` is present and non-empty:
   a. Resolve `invite_code` to `inviter_user_id` via the inviter's stable invite-code hash (invite codes are derived deterministically from `user_id` via HMAC + base32 truncation; see Feature Flags note below for the code format).
   b. Validate the inviter:
      - inviter exists, `deleted_at IS NULL`, `is_banned = FALSE`, `is_shadow_banned = FALSE`
      - inviter account age > 30 days (anti new-account farming, matches `01-Business.md` bonus release criteria)
      - inviter is NOT the just-created invitee (self-invite rejected)
   c. Validate the invitee vs inviter anti-fingerprint-collision checks:
      - invitee `device_fingerprint_hash` does NOT match the inviter's in the last 90 days
      - invitee IP subnet (/24) does NOT match any of the inviter's last 10 login subnets
      - inviter has not hit the "3 tickets/week" burst rate limit
   d. On all checks passing, `INSERT INTO referral_tickets` with `status = 'pending_activity'`, `expires_at = NOW() + INTERVAL '14 days'`.
   e. On any check failing, signup still succeeds but no ticket is created; the invitee is NOT informed of the specific failure reason (privacy + anti-probing). A generic log entry is written.
4. Invite-code collision (rare; UNIQUE `(invitee_user_id)` on `referral_tickets`): if the just-created user already has a ticket row somehow (replay attack or retry), ON CONFLICT DO NOTHING keeps the first ticket authoritative.

**Worker triggering**: A Cloud Scheduler daily job (`/internal/referral-activity-check`) scans `referral_tickets WHERE status = 'pending_activity' AND expires_at > NOW()` and runs the activity gate check documented below. Expired tickets are flipped to `status = 'expired'` by the same worker.

**Invite-code format**: `INVITE-<8-char-base32>` where the 8 chars are derived from `base32(HMAC-SHA256(INVITE_CODE_SECRET, user_id.bytes))[0..8]`. Stable per user, non-enumerable without `INVITE_CODE_SECRET` (stored in GCP Secret Manager as `invite-code-secret`, generated in Pre-Phase 1). The user sees their own code in Settings and can share via any channel.

**Resolution at signup** (O(1), no full-table scan): the 8-char prefix is also stored in `users.invite_code_prefix VARCHAR(8) NOT NULL UNIQUE`, populated at user-row insert time. Inviter lookup is:

```sql
SELECT id FROM users
WHERE invite_code_prefix = :candidate_prefix
  AND deleted_at IS NULL
LIMIT 1;
```

In the extremely rare case of an 8-char base32 collision during concurrent signups (probability ~1e-9 at 100k users), the UNIQUE constraint fails the second insert and the signup flow retries with a 10-character prefix (extended-base32) for the losing row. Retry is capped at 3 attempts before falling back to random UUID suffix.

### Worker Job (Idempotent)

```kotlin
fun processReferralActivityGate(ticket: ReferralTicket) {
    if (ticket.expiresAt <= Instant.now()) {
        markTicketExpired(ticket.id)
        return
    }
    if (!checkActivityGate(ticket.inviteeUserId)) return

    var unlockedInviterReward = false

    database.withTransaction {
        // 1. Invitee grant (one per ticket; every successful referral)
        val inviteeGrant = insertGrantedEntitlementOrNull(
            userId = ticket.inviteeUserId,
            ticketId = ticket.id,
            durationDays = 7,
            source = "referral",
            grantRole = "invitee"
        ) ?: return@withTransaction

        markTicketGranted(ticket.id)

        // 2. Inviter milestone check: 5th successful referral, lifetime-once
        val inviterSuccessfulCount = countGrantedTicketsForInviter(ticket.inviterUserId)
        val inviterRewardAlreadyClaimed = isInviterRewardClaimed(ticket.inviterUserId)

        if (inviterSuccessfulCount == 5 && !inviterRewardAlreadyClaimed) {
            val inviterGrant = insertGrantedEntitlementOrNull(
                userId = ticket.inviterUserId,
                ticketId = ticket.id,
                durationDays = 7,
                source = "referral",
                grantRole = "inviter"
            )
            if (inviterGrant != null) {
                markInviterRewardClaimed(ticket.inviterUserId) // sets users.inviter_reward_claimed_at = NOW()
                unlockedInviterReward = true
            }
        }
        // inviterSuccessfulCount < 5: count only, no grant
        // inviterSuccessfulCount > 5: no grant (cap already consumed or structurally ignored)
    }

    // RevenueCat dispatch + event logging (outside the DB transaction)
    revenueCatClient.grantEntitlement(
        userId = ticket.inviteeUserId,
        entitlementId = "premium",
        durationDays = 7,
        dedupKey = "referral-${ticket.id}-invitee"
    )
    insertSubscriptionEvent(
        userId = ticket.inviteeUserId,
        eventType = "grant",
        source = "referral",
        amountRupiah = 0
    )

    if (unlockedInviterReward) {
        revenueCatClient.grantEntitlement(
            userId = ticket.inviterUserId,
            entitlementId = "premium",
            durationDays = 7,
            dedupKey = "referral-inviter-lifetime-${ticket.inviterUserId}"
        )
        insertSubscriptionEvent(
            userId = ticket.inviterUserId,
            eventType = "grant",
            source = "referral",
            amountRupiah = 0
        )
    }
}
```

**Helper queries**:

```sql
-- countGrantedTicketsForInviter
SELECT COUNT(*) FROM referral_tickets
WHERE inviter_user_id = :inviter_id AND status = 'granted';

-- isInviterRewardClaimed
SELECT inviter_reward_claimed_at IS NOT NULL
FROM users WHERE id = :inviter_id;

-- markInviterRewardClaimed (idempotent: COALESCE preserves first-claim timestamp)
UPDATE users
SET inviter_reward_claimed_at = COALESCE(inviter_reward_claimed_at, NOW())
WHERE id = :inviter_id;
```

**Idempotency layers**:
1. `granted_entitlements UNIQUE (referral_ticket_id, user_id)` prevents duplicate grants per ticket/user.
2. `granted_entitlements_inviter_once_idx` partial unique index prevents more than one `grant_role = 'inviter'` row per user, ever.
3. `users.inviter_reward_claimed_at` sentinel short-circuits the milestone check before any insert attempt.
4. RevenueCat `dedupKey` makes the external grant call idempotent on retry.

---

## Cache & Rate Limit (Upstash Redis)

### Patterns

- Rate limit counter: `INCR` + `EXPIRE`
- Hot data cache: user profile, conversation metadata, `token_version`, timeline session counter
- Attestation cache: recent verdicts TTL 1 hour
- Geocode cache: TTL 30 days
- Remote Config cache: TTL 5 minutes
- Streams (post-swap): chat message persistence + fan-out

### Key Format Standard (for Upstash cluster co-location)

```
rate:{user:<user_id>}:<action>
timeline_session:{user:<user_id>}:<session_id>
timeline_rolling:{user:<user_id>}
token_version:{user:<user_id>}
rate:area:{geohash:<geohash5>}
rate:guest_issue:{ip:<ip>}
rate:guest_issue:{fp:<fp_hash>}
rate:guest_issue_day:{ip:<ip>}
rate:guest_issue_global:{global:1}
stream:conv:{conv:<conversation_id>}
remote_config:{flag:<flag_name>}
geocode:{geocell:<lat2dp>_<lng2dp>}
```

Hash tag `{scope:<value>}` ensures same-scope keys map to the same Redis slot. Multi-key ops (MULTI/EXEC, Lua) are safe. CI lint rule rejects raw keys that don't include a hash tag. Note: the global circuit breaker uses `{global:1}` as a singleton scope so the single shared counter still has a stable slot.

---

## Rate Limiting Implementation (4-Layer, Hardened)

### Layer 1: Per IP + Attestation-Gated Guest Token + Pre-Issuance Rate Limits

**Guest flow**:
1. First app open: client collects Play Integrity (Android) or App Attest (iOS)
2. `POST /api/v1/guest/session` with attestation payload + device fingerprint hash
3. Server verifies attestation (Google/Apple public key)
4. Attestation passes then check pre-issuance rate limits (below) then issue short-lived guest JWT (TTL 24 hours, signed server-side, with `jti` claim)
5. Attestation fails then return 403, block guest flow
6. Client stores guest JWT in secure storage
7. Client includes header `Authorization: Guest <jwt>` on guest requests
8. Rate limit post-issuance: compound key `rate:{jti:<guest_jwt_jti>}:<ip>`, 100 req/min
9. Re-attestation: when JWT expires, client redoes the flow (no auto-refresh)

**Pre-issuance rate limits (defense in depth for attestation-bypass + CGNAT flood)**:

| Layer | Key | Limit | TTL |
|-------|-----|-------|-----|
| 1a. IP hard | `rate:guest_issue:{ip:<ip>}` | 10 tokens/hour | 1 hour |
| 1b. IP daily cap | `rate:guest_issue_day:{ip:<ip>}` | 50 tokens/day | 24 hours |
| 1c. Device fingerprint | `rate:guest_issue:{fp:<fp_hash>}` | 5 tokens/hour | 1 hour |
| 1d. Global circuit breaker | `rate:guest_issue_global:{global:1}` | 10k tokens/min | 1 minute |

**Device fingerprint in request body**:
- Android: `Settings.Secure.ANDROID_ID` + app signature hash
- iOS: `identifierForVendor` + bundle ID hash
- Hash SHA256 client-side before sending (optional privacy layer)

**CGNAT mitigation**:
- IP limit hit but fingerprint not yet: allow 1 token + log flag `cgnat_suspected=true`
- IP limit AND fingerprint limit both hit: block + user-facing error "terlalu banyak permintaan dari jaringan ini, coba WiFi lain atau login"
- Global circuit breaker: prevents DDoS amplification

**Authenticated user IP baseline**: 1000 req/min/IP (measured off the `CF-Connecting-IP` header when Cloudflare is in front; see "Cloudflare-Fronted IP Extraction" below). This is a Layer 1 baseline that applies to all authenticated traffic in addition to the Layer 2 per-user quotas.

**Guest permissions**:
- Read Global timeline (latest 10 posts)
- No write access (post, like, reply, chat, follow)
- No profile view

**Upgrade path**: when the user logs in with Google/Apple + attestation, the guest session token is invalidated and replaced with an authenticated JWT.

### Layer 2: Per User (with WIB Stagger)

| Action | Limit |
|--------|-------|
| Post | 10/day Free, unlimited Premium |
| Reply | 20/day Free, unlimited Premium |
| Chat | 50/day Free, unlimited Premium |
| Like | 10/day Free + 500/hour burst, unlimited Premium + 500/hour burst |
| Follow | 50/hour |
| Report | 20/hour |
| Block/Unblock | 30/hour |
| Search query (Premium) | 60/hour |
| Image upload (Premium, Month 6+) | 50/day + 1/60 sec throttle |
| Timeline read Free | 150 posts/hour rolling (hard, authoritative), 50/session (soft UX nudge) |
| Timeline read Guest | 30 posts/hour rolling (hard), 10/session (soft UX nudge) |
| Username change (Premium) | 1 successful change per 30 days (DB-enforced), 10 failed attempts/hour (anti-probe) |
| Username availability probe | 3/day per user |

**Timezone reset stagger** (prevents thundering herd at 00:00 WIB):
- Reset offset per user = `hash(user_id) % 3600` seconds
- Effective reset window: 00:00 to 01:00 WIB, linearly distributed
- Redis key TTL = time until `next_00:00_WIB + offset`
- **Centralized in the shared function** `computeTTLToNextReset(user_id)`, with lint rule enforcing usage at every daily rate limit endpoint (hourly limits do not use this stagger)

**Rejected alternative**: rolling 24h window. UX hard to understand, Redis sliding window complex.

### Layer 3: Per Google/Apple ID

1 identifier = 1 active account. Sticky ban. The same human signing up with Google + Apple becomes 2 separate accounts (no linking).

### Layer 4: Per Area (anti local spam)

Max 50 new posts within a 1km radius within 1 hour (via `display_location` spatial query). When the threshold is hit: manual review.

Implementation: Redis counter (INCR + EXPIRE) in Upstash.

---

## CSAM Detection Archive Schema

Schema for 90-day preservation (bypasses cascade delete):

```sql
CREATE TABLE csam_detection_archive (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    detected_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    cf_match_id TEXT,
    image_hash TEXT NOT NULL,
    original_post_id UUID,
    user_id_at_detection UUID,
    cf_report_url TEXT,
    ncmec_report_id TEXT,
    kominfo_report_id TEXT,
    kominfo_reported_at TIMESTAMPTZ,
    source VARCHAR(16) NOT NULL DEFAULT 'admin_manual'
        CHECK (source IN ('admin_manual', 'cf_worker', 'email_poll')),
    expires_at TIMESTAMPTZ NOT NULL,
    encrypted_metadata BYTEA
);

-- Deduplication across handler invocations: same CF match should never archive twice.
-- cf_match_id is UNIQUE when present; image_hash-based fallback covers the legacy
-- case where CF email did not include a match id.
CREATE UNIQUE INDEX csam_archive_cf_match_unique ON csam_detection_archive(cf_match_id) WHERE cf_match_id IS NOT NULL;
CREATE UNIQUE INDEX csam_archive_image_hash_unique ON csam_detection_archive(image_hash);
CREATE INDEX csam_archive_expires_idx ON csam_detection_archive(expires_at);
CREATE INDEX csam_archive_kominfo_idx ON csam_detection_archive(kominfo_reported_at) WHERE kominfo_reported_at IS NULL;
CREATE INDEX csam_archive_source_idx ON csam_detection_archive(source, detected_at DESC);
```

**Deduplication semantics**: handler invocations across the three trigger paths (admin manual, CF Worker, email poll) MUST be idempotent. When inserting:

```sql
INSERT INTO csam_detection_archive (...)
VALUES (...)
ON CONFLICT (image_hash) DO UPDATE
SET source = CASE
        WHEN csam_detection_archive.source = 'admin_manual' THEN csam_detection_archive.source
        ELSE EXCLUDED.source
    END,
    cf_match_id = COALESCE(csam_detection_archive.cf_match_id, EXCLUDED.cf_match_id),
    cf_report_url = COALESCE(csam_detection_archive.cf_report_url, EXCLUDED.cf_report_url)
RETURNING id;
```

The `ON CONFLICT` guard ensures the first successful archive (admin-triggered in MVP) wins as the canonical record; later invocations by the Worker path enrich the row but do not reset it.

**Partial index note**: the `expires_at` index is a plain B-tree (no `WHERE expires_at > NOW()` predicate). PostgreSQL rejects partial-index predicates that reference `NOW()` because it is `STABLE`, not `IMMUTABLE`. The purge worker's query filters at runtime; the index covers both the forward-range lookup and the purge-worker's `WHERE expires_at < NOW()` scan.

> See `06-Security-Privacy.md` for Kominfo reporting SOP, encryption strategy (AES-256 with `csam-archive-aes-key` from GCP Secret Manager), and purge worker.

---

## Admin Actions Log Schema

Canonical version (also referenced from `07-Operations.md`):

```sql
CREATE TABLE admin_actions_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id UUID NOT NULL REFERENCES admin_users(id),
    action_type VARCHAR(64) NOT NULL,
    target_type VARCHAR(32),
    target_id TEXT,
    reason TEXT,
    before_state JSONB,
    after_state JSONB,
    ip INET,
    user_agent TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX admin_actions_admin_idx ON admin_actions_log(admin_id, created_at DESC);
CREATE INDEX admin_actions_target_idx ON admin_actions_log(target_type, target_id);
CREATE INDEX admin_actions_type_idx ON admin_actions_log(action_type, created_at DESC);
```

Immutable: revoked UPDATE/DELETE at the role level for `admin_app`. Retention 1 year minimum.

---

## Shadow Ban Implementation

### Server-Side Consistency via Database Views

```sql
CREATE VIEW visible_posts AS
SELECT p.*
FROM posts p
JOIN users u ON p.author_id = u.id
WHERE p.deleted_at IS NULL
    AND u.deleted_at IS NULL
    AND u.is_shadow_banned = FALSE;

CREATE VIEW visible_users AS
SELECT u.*
FROM users u
WHERE u.deleted_at IS NULL
    AND u.is_shadow_banned = FALSE;
```

**Rules for code**:
- Timeline query: `FROM visible_posts` (not `posts`)
- User profile query: `FROM visible_users`
- Counter aggregation (likes, replies): JOIN `visible_users` to exclude banned contributions
- Chat delivery: filter when consuming from Supabase Realtime broadcast (application-level)
- Search: `FROM visible_posts` JOIN `visible_users`
- Notification trigger: consume from `visible_posts`
- Follower/following list: JOIN `visible_users`

**Exception for own content**: a banned user sees their own content normally. The own-content query path bypasses the view with `WHERE author_id = current_user_id OR viewer_id = current_user_id`. Centralized in the Repository layer.

**CI check (mandatory)**: lint rule / pre-commit hook detects raw `FROM posts` / `FROM users` / `FROM post_replies` in business queries (allowed only in the Repository own-content path + admin module). Prevents leaks in future commits.

---

## Cloudflare-Fronted IP Extraction

All inbound HTTPS traffic to `api.nearyou.id` and `admin.nearyou.id` transits Cloudflare. Cloud Run sees the Cloudflare edge IP in `X-Forwarded-For`, NOT the real client IP. The real client IP is carried in the `CF-Connecting-IP` header.

### Middleware

A Ktor intercept at the top of the request pipeline extracts the client IP with the following precedence:

1. `CF-Connecting-IP` header if present (Cloudflare origin-pull path)
2. First entry in `X-Forwarded-For` if no `CF-Connecting-IP` (local dev + staging where Cloudflare may be absent)
3. `call.request.origin.remoteHost` as a last-resort fallback

The extracted IP is attached to the request context as `ClientIp` and used by every rate-limit key construction, audit log entry, and `admin_sessions.ip` / `admin_actions_log.ip` write.

### Spoof Protection

Cloudflare sets `CF-Connecting-IP` only on traffic that actually flows through its edge. Direct hits to the Cloud Run URL (`*.run.app`) would bypass Cloudflare entirely, so:

- Cloud Run ingress is set to "Internal and Cloud Load Balancing" + a Cloud Armor policy in front allowing only Cloudflare's published IP ranges. This forces all production traffic through the CF edge and makes `CF-Connecting-IP` trustworthy.
- Alternatively (simpler, accepted for Phase 1 MVP until Cloud Armor is wired): a Ktor middleware check that `X-Forwarded-For`'s last entry belongs to a known Cloudflare range; reject requests without a CF edge as the last hop.

### Staging Note

`api-staging.nearyou.id` also sits behind Cloudflare. Same extraction logic applies. Local dev (`localhost`) has no Cloudflare and falls through to the remoteHost fallback.

---

## Content Moderation Keyword Lists (Storage + Management)

Two categories of keyword/pattern lists drive text and username moderation:

- **Profanity blocklist**: ~200-500 entries (Indonesian + common English profanity). Drafted in Pre-Phase 1 via AI + manual review (0.5 day budget).
- **UU ITE keyword list**: SARA, defamation, incitement patterns. Drafted in Pre-Phase 1 (1 day budget), quarterly review cadence with legal advisor.

### Storage

- **Primary**: Firebase Remote Config string-array parameters `moderation_profanity_list` and `moderation_uu_ite_list`. Version-controlled via the Firebase Remote Config history feature. Editable by admins with audit-logged changes.
- **Fallback (repo-committed)**: `/backend/src/main/resources/moderation/profanity.default.txt` and `uu_ite.default.txt`. Used when Remote Config fetch fails or the server is cold-starting without network.
- **Hot-reload**: the same Remote Config 5-minute Redis cache used for feature flags. Key format: `remote_config:{flag:moderation_profanity_list}` etc.
- **Secret slot**: the signed/integrity-checked version of the list (optional, for defense against Remote Config write compromise) is stored in GCP Secret Manager as `content-moderation-fallback-list` (comma-separated, version-pinned). Used only if a boot-time integrity check against Remote Config fails.

### Loader Mechanism

A single `ModerationListLoader` service wraps list resolution with deterministic fallback order:

1. **Hot path (default)**: read from Redis cache at `remote_config:{flag:moderation_profanity_list}` (TTL 5 min), populated by the Remote Config Redis refresher worker.
2. **Cache miss**: fetch from Firebase Remote Config directly via the Admin SDK, populate the Redis cache, and return.
3. **Remote Config unreachable** (network error, quota exceeded): log a WARN-level `moderation.remote_config_unavailable` event to Sentry, increment a circuit breaker counter, and return the repo-committed fallback file contents (read once at boot, held in a `LazyHolder` object for the process lifetime).
4. **Integrity check**: when the circuit breaker counter exceeds 10 consecutive failures, the loader switches to the `content-moderation-fallback-list` Secret Manager slot as the canonical source; returns to Remote Config after a successful fetch.

**Reload cadence**: the Redis cache TTL (5 minutes) is the effective reload interval. No long-polling or pubsub needed. The Aho-Corasick automaton is rebuilt on cache miss inside the loader (amortized: one build per 5 minutes per service instance).

**Testing**: unit tests cover (a) Remote Config happy path, (b) fallback file path when Remote Config throws, (c) Secret Manager switchover after N failures, (d) automaton rebuild correctness. Integration test verifies that flipping a Remote Config value propagates to the matcher within the cache TTL + 1 minute jitter.

### Matching Engine

- Case-insensitive substring match with word-boundary regex: `\b<keyword>\b` (Unicode aware to handle Indonesian + leetspeak variants)
- Aho-Corasick automaton built at service start + rebuilt when the cache invalidates
- Match score = count of distinct keywords hit; threshold for "high score" = 3+ distinct matches (tunable via Remote Config key `moderation_match_threshold`)

### Review Cadence

- UU ITE list: quarterly review with legal advisor (documented as Open Decision 10 in `08-Roadmap-Risk.md`). Changes written via the Admin Panel Feature Flag Admin surface with audit entry.
- Profanity list: ad-hoc update; admins can add/remove entries via the Admin Panel with audit log.

---

## Health Check Implementation

```kotlin
route("/health") {
    get("/live") {
        call.respondText("OK", ContentType.Text.Plain, HttpStatusCode.OK)
    }
    get("/ready") {
        val checks = coroutineScope {
            listOf(
                async { checkPostgres() },   // SELECT 1, timeout 500ms
                async { checkRedis() },      // PING, timeout 200ms
                async { checkSupabaseRT() }  // GET /rest/v1/, timeout 500ms
            ).awaitAll()
        }
        if (checks.all { it.ok }) {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ready", "checks" to checks))
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("status" to "degraded", "checks" to checks))
        }
    }
}
```

Both are rate-limited: 60 req/min/IP (anti-scrape).

---

## Key Implementation Notes

### Spatial Queries

- PostGIS `ST_DWithin` for radius filtering (uses `display_location`)
- PostGIS `ST_Contains` for reverse geocoding `city_name` (uses `actual_location`)
- GIST index on geometry columns (posts has dual: display + actual, plus `admin_regions`)
- Cursor-based pagination avoids OFFSET scan + composite `(created_at, id)` index
- Exclude soft-deleted + shadow-banned + blocked + auto-hidden users/posts in all queries (via `visible_posts` view + `is_auto_hidden` flag + block subquery)

Performance target: <100ms standard radius query. Dense Jakarta at 100km with 50k MAU + view filter can be slow. Mitigation:
- Composite index `(created_at DESC, author_id)` on posts
- Partial index for high-density areas
- Benchmark mandatory in Phase 2; evaluate pre-materialized timeline (H3 hex) if needed

### Security

- JWT validation middleware, RS256 primary + HS256 companion; `token_version` claim compared on every request
- RLS regex-guarded `split_part` pattern + `auth.jwt()->>'sub'` check + test cases for malformed topics
- Token version check with Redis cache TTL 5 minutes + DB fallback
- Input sanitization on every endpoint, length enforcement per content type
- 4-layer rate limiting (attestation-gated guest + pre-issuance limits, user + WIB stagger, identifier, area)
- HTTPS enforced; WSS enforced for Supabase Realtime
- Google/Apple ID hashed in logs
- Device attestation mandatory (Play Integrity + App Attest) + bypass whitelist via Remote Config
- Device fingerprinting (correlation signal only)
- OIDC auth on `/internal/*`
- RevenueCat webhook: Bearer + HMAC signature
- Refresh token reuse detection + family revocation
- CSAM detection via CF Scanning Tool + webhook auto-action + archive + Kominfo report
- Anomaly detection metrics + Sentry/Slack alerts
- Age gate 18+ only (UU PDP compliance)
- `rejected_identifiers` blocklist for under-18 signup bypass prevention
- HMAC-based jitter for anti-triangulation coordinate fuzzing
- Backup pg_dump + `age` stream encryption before R2 upload
- Block filter enforced at repository layer + CI lint
- Admin panel via scoped `admin_app` DB role (separate credential from main API)
- Premium username customization: 30-day cooldown + 30-day release hold via `username_history` + profanity/UU ITE moderation check + feature flag kill switch
- Environment separation: staging and production isolated at every layer (Cloud Run service, Supabase project, Upstash DB, R2 bucket, subdomain, GCP Secret Manager namespace, Firebase project, RevenueCat sandbox)
- Client IP extracted from `CF-Connecting-IP` with Cloudflare-edge enforcement (Cloud Armor ingress allowlist or XFF last-hop check)
- Content moderation keyword lists: Firebase Remote Config primary + repo-committed fallback + Aho-Corasick matcher, quarterly UU ITE review with legal advisor

### Performance

- GIST indexes spatial (dual: display + actual)
- HikariCP max 20 connections per Ktor instance + Supabase PgBouncer
- Upstash Redis caching + Streams (post-swap)
- CTE batching mandatory on timeline endpoint
- GIN indexes (FTS + trgm) on `posts.content`
- OTel tracing for latency root-cause analysis

### Observability

- Structured JSON logging
- Metrics: GCP Cloud Monitoring + Supabase dashboard
- Backend + mobile errors: Sentry KMP unified, dSYM + ProGuard mappings uploaded via CI
- Traces: OTel SDK to Grafana Cloud (100% head sampling in dev Phase 2, 10% base + 100% error/slow in production)
- Product analytics: Amplitude funnels + cohorts + retention (consent-gated per UU PDP)
- DB size alert at 60/75/90% of cap (start Month 3)
- Security event logs: attestation failures, reuse detection triggers, rate limit hits, RLS denials, JWT verify fail spikes, CSAM detection events, age gate rejections, webhook signature fails, `rejected_identifiers` inserts
- Health check `/health/ready` monitored by Cloud Run native probe + external uptime check
