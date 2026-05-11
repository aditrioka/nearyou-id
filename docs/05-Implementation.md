# NearYouID - Technical Implementation

Database schemas, algorithms, auth/session implementation, rate limiting implementation, cache key formats, feature flags, and key implementation notes.

---

## Authentication Implementation

### JWT Strategy: Asymmetric Primary + HS256 WSS Companion

> Mirrors `backend/ktor/src/main/kotlin/id/nearyou/app/auth/jwt/` (verifier) + `auth/jwks/` (JWKS publisher). Update both when changing the canonical claim shape.

Two tokens, one primary key pair:

1. **Ktor REST JWT**: RS256 asymmetric, TTL 15 min, refresh-token 30 days. Signed with a private key in GCP Secret Manager; public key exposed via `https://api.nearyou.id/.well-known/jwks.json` with `kid` header. Supports scheduled rotation via multiple kids in JWKS (rolling deploy, no user re-auth). **Claims**: `sub` (user_id), `exp`, `iat`, `token_version` (compared against `users.token_version` on every request for instant revocation), `kid` (header).
2. **Supabase Realtime JWT**: HS256 (Supabase-compatible). Issued by `/api/v1/realtime/token` after verifying the Ktor JWT. TTL 1h, claims `sub`, `role: 'authenticated'`, `exp`, `iat`. Signed with the Supabase Project JWT Secret.

**Rationale**: Supabase Realtime Authorization without paid Third-Party Auth only supports HS256 natively, so we keep two tracks (REST future-proof RS256 + WSS-compatible HS256).

**Third-Party Auth migration path** (~2-3 days when MAU >10k makes it affordable): enable Third-Party Auth in Supabase Dashboard, point at the existing JWKS URL, swap WSS token generation from HS256 to RS256 (same key pair as REST). Zero REST refactor.

**Secrets** (GCP Secret Manager): `ktor-rsa-private-key` (kid rotation via versions), `supabase-jwt-secret`, `apns-key-p8`, `firebase-admin-sa`, `openai-api-key`, `invite-code-secret`, `revenuecat-webhook-secret`(+`-hmac-secret`), `resend-api-key`, `jitter-secret` (256-bit), `backup-age-private-key`, `admin-app-db-connection-string`, `admin-session-cookie-signing-key` (optional). DESIGN-reserved: `csam-archive-aes-key`, `cf-worker-csam-secret`, `content-moderation-fallback-list`. Staging mirrors the list with a `staging-*` prefix.

**HS256 incident rotation**: new Supabase JWT secret → Dashboard update (Realtime sessions kicked within ~20 min) → GCP slot update → rolling Ktor deploy → audit-log pre-rotation refresh tokens.

**RS256 scheduled kid rotation** (zero-disruption): new RSA pair → add kid to JWKS → roll Ktor signing 10% → 100% → retain old kid 30 days (covers refresh-token max TTL) → remove.

### Anomaly Detection Metrics

Alert (Sentry + Slack admin channel) if: one `sub` subscribes to >50 conversation channels in 5 min; same `sub` issued from >5 geographic locations in 1h; JWT verify fail rate >1% (possible secret compromise / Supabase cache sync lag); RevenueCat webhook signature fail rate non-zero.

### Client State Machine for Token Refresh

> Mirrors `backend/ktor/src/main/kotlin/id/nearyou/app/auth/routes/`.

Priority on 401: (1) Ktor JWT expired (REST) → `POST /api/v1/auth/refresh` returns new Ktor + Supabase JWT in one round-trip. (2) Supabase JWT only (WSS disconnect) → `GET /api/v1/realtime/token` with valid Ktor JWT. (3) Refresh token expired/invalid → force re-auth via Google/Apple + attestation. (4) Both expired → path (1) handles it.

Preemptive refresh: on app wake / cold start, if either JWT expires in <5 min, refresh async non-blocking. Mutex against refresh storms: client uses single-flight; server allows a 30-second overlap window for the same refresh token.

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

**Mandatory test cases**: non-participant deny; `left_at` set deny; shadow-banned deny; JWT `sub` not in `public.users` deny (defense); malformed topic (no delimiter) deny via regex; invalid UUID format deny via regex; SQL-injection attempt deny via regex; empty/null topic deny via regex.

### Apple Sign-In Specifics

**Private Relay email**: user picks "Hide My Email" and gets `xxx@privaterelay.appleid.com`. Store `users.email` = relay or real email (treat the same), with `users.apple_relay_email BOOLEAN`. Do not send marketing email to relay addresses (high bounce rate / blacklists). Batch safeguard: re-check `apple_relay_email` per batch for campaigns >1000 recipients.

**Apple S2S endpoint** (mandatory per Apple Dev Agreement): `POST /internal/apple/s2s-notifications`. Verify JWT signature via Apple JWKS, verify `aud` = bundle ID, dedup via `transaction_id`. Events: `email-disabled`/`email-enabled` → update flag; `consent-revoked` → treat as delete intent, kick sessions + 30-day tombstone grace; `account-delete` → hard tombstone immediately (no grace, Apple-required).

**Relay email change detection**: on every sign-in (not signup), compare `id_token.email` vs stored. On mismatch + `apple_relay_email = TRUE`, update email, log `apple.relay_email_changed`, send in-app notification + Resend email "Email bayangan Apple kamu sudah diperbarui".

---

## Session Management

> Mirrors `backend/ktor/src/main/kotlin/id/nearyou/app/auth/session/`.

Access token (Ktor RS256 JWT): TTL 15 min, stateless. Refresh token: TTL 30 days, stateful in Postgres, 1 row per device with `family_id`.

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
CREATE INDEX refresh_tokens_family_active_idx ON refresh_tokens (family_id) WHERE revoked_at IS NULL;
CREATE INDEX refresh_tokens_expires_idx ON refresh_tokens (expires_at) WHERE revoked_at IS NULL;
```

### Rotation Logic (`POST /api/v1/auth/refresh`)

- Valid token (`used_at IS NULL` or within 30s overlap): issue new token, mark old `used_at = NOW()`, update `last_used_at` on every use.
- Valid token but `used_at` set AND outside overlap = **reuse detected**: revoke the entire family (`UPDATE refresh_tokens SET revoked_at = NOW() WHERE family_id = ?`), increment `users.token_version` (kicks all active sessions), invalidate Redis cache, force re-auth via Google/Apple + attestation, log `security.token_reuse_detected` with user_id, family_id, IP, UA.
- Revoked token: 401, force re-auth.

### Cleanup + Global Revocation

Cleanup (Cloud Scheduler `/internal/cleanup`): daily `WHERE expires_at < NOW() - INTERVAL '1 day'`; weekly `WHERE last_used_at < NOW() - INTERVAL '90 days'`. Rotated token deleted immediately on successful rotation.

Instant global revocation: JWT carries `token_version`; every authenticated request compares JWT vs DB. Redis cache TTL 5 min, explicit invalidation on increment; Redis down → direct DB query + circuit breaker alert.

### Revocation Latency per Use Case

| Use Case | Action | Latency |
|----------|--------|---------|
| Logout single device | Delete refresh token row | ≤15 min |
| Log out of all devices | Delete all + increment token_version + invalidate cache | Instant (≤5 min cache) |
| Ban / suspend / delete account | Update flag + increment token_version + delete tokens | Instant |
| Refresh token reuse detected | Revoke family + increment + force re-auth | Instant |
| Normal token expire | Rotation | 0 (seamless) |

No conventional logout (WA/Telegram model). Re-auth required on: first registration, new device, idle >30 days, switching accounts, "log out of all devices", reuse detection.

---

## Users Schema (Canonical)

> Mirrors V2 (`backend/ktor/src/main/resources/db/migration/V2__auth_foundation.sql`) plus subsequent column-add migrations through V15.

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
- 18+ registration only; `CHECK` backstops the application-layer age gate.
- `suspended_until` set together with `is_banned = TRUE` for time-boxed suspension; daily `/internal/unban-worker` flips back.
- `subscription_status` CHECK enforces the 3-state machine (`01-Business.md`).
- `inviter_reward_claimed_at` (DESIGN): lifetime sentinel for future referral system, set on 5th-referral milestone.
- `username VARCHAR(60)`: ceiling sized for worst-case auto-generated `{adjective}_{noun}_{uuid8hex}`; Premium customization (DESIGN) tightens to a 30-char user-facing cap.
- `username_last_changed_at` / `privacy_flip_scheduled_at` are DESIGN-reserved columns.
- `invite_code_prefix`: stable 8-char base32 = `base32(HMAC-SHA256(invite-code-secret, user_id.bytes))[0..8]`, populated at signup, resolves O(1). Implemented in `InviteCodePrefixDeriver.kt`.

**Effective private** = `private_profile_opt_in = TRUE AND subscription_status IN ('premium_active', 'premium_billing_retry')`. The 72h privacy-flip-window short-circuit is DESIGN.

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
```

Triggers: auto-maintain `updated_at`; block DELETE/UPDATE on `source = 'seed_system'` rows. Flyway-seeded `seed_system` entries: `admin`, `support`, `moderator`, `system`, `nearyou`, `staff`, `official`, `akun_dihapus`, `deleted_user`, plus every 1-2 char lowercase string. All entries lowercase; signup checks `SELECT 1 FROM reserved_usernames WHERE username = LOWER(:candidate)` and on hit retries the next candidate.

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

**Privacy note**: only the hashed Google/Apple identifier is stored — not DOB, email, or name. Purpose: stop a user who declared DOB <18 from retrying signup with a different DOB on the same identifier.

**Signup check**: `NOT EXISTS (SELECT 1 FROM rejected_identifiers WHERE identifier_hash = :hash AND identifier_type = :type)` before users-row insert. On hit, return the same under-18 rejection message (avoid confirming state to the user).

Retention: indefinite. Purgeable via legitimate adult re-verification workflow (support form + manual admin clear).

---

## Suspension Unban Worker

Cloud Scheduler daily at 04:00 WIB calls `/internal/unban-worker`:

```sql
UPDATE users SET is_banned = FALSE, suspended_until = NULL
WHERE is_banned = TRUE AND suspended_until IS NOT NULL
  AND suspended_until <= NOW() AND deleted_at IS NULL;
```

Audit log inserted per unban. Permanent bans (`suspended_until IS NULL`) are untouched.

---

## Privacy Flip Worker — DESIGN

> **Status: DESIGN** (as of 2026-05-07). `/internal/privacy-flip-worker` not mounted; `users.privacy_flip_scheduled_at` reserved in V2. Future proposal will author the worker SQL (flip `private_profile_opt_in = FALSE` once the 72h grace expires) + the trigger SQL on the RevenueCat webhook handler.

---

## Username Generation & Customization

### DB Constraints

The `username VARCHAR(60) NOT NULL UNIQUE` constraint and `users_username_lower_idx` index are defined inline in the `users` table schema above. No additional ALTER needed.

### Atomic Generation Algorithm (signup-only)

> Mirrors `backend/ktor/src/main/kotlin/id/nearyou/app/auth/signup/UsernameGenerator.kt`. Update both when changing the candidate ladder. Produces ≤60 char username.

```
for attempt in 1..5:
    candidate = "{adj}_{noun}"                    if attempt <= 2  // ≤30 chars
              | "{adj}_{noun}_{modifier}"         if attempt <= 4  // ≤40 chars
              | "{adj}_{noun}_{random_5_digit}"   else             // ≤35 chars
    if EXISTS in reserved_usernames(LOWER(candidate)): continue
    if EXISTS in username_history(LOWER(candidate)) AND released_at > NOW(): continue
    try INSERT INTO users(..., username=candidate)  // success → return
    catch unique_violation → continue

# Worst-case fallback (≤60 chars, fits schema ceiling)
candidate = "{adj}_{noun}_{uuid8hex}"; INSERT
```

### Username History Schema (30-Day Release Hold)

Tracks historical usernames held during the post-change release hold window. Used for collision check + impersonation prevention.

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

- `released_at = changed_at + INTERVAL '30 days'`; a custom username cannot be claimed while `released_at > NOW()`.
- Rows kept indefinitely for audit. The `released_at` index is plain B-tree (PostgreSQL rejects partial-index predicates referencing `NOW()` because `NOW()` is `STABLE` not `IMMUTABLE`).

### Premium Customization Endpoint — DESIGN

> **Status: DESIGN** (as of 2026-05-07). `PATCH /api/v1/user/username` is not mounted; `users.username_last_changed_at` + `username_history` are reserved. Future proposal will define validation pipeline (3-30 chars, charset regex, no-consecutive-dots, reserved/history collision, profanity/UU ITE check), transactional flow with FOR UPDATE row lock, `premium_username_customization_enabled` kill switch, rate limits (1 successful change / 30 days, 10 failed attempts / h, 3 availability probes / day).

---

## Post System Implementation

### Coordinate Storage Policy + Posts Schema

Posts carry 2 geography columns: `display_location` (fuzzed ≤500m via HMAC-derived jitter; Nearby query + distance render) and `actual_location` (admin analytics + moderation + reverse geocoding only). Mirrors V4 + V11/V13 column adds.

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

> Mirrors `shared/distance/src/commonMain/kotlin/id/nearyou/distance/JitterEngine.kt`. Update both when changing the derivation.

```
JITTER_SECRET = secret from GCP Secret Manager (256-bit random, long-lived)
jitter_seed = HMAC-SHA256(key=JITTER_SECRET, message=post_id.bytes)

bearing_radians = (bytes_to_uint32(jitter_seed[0..4]) / 2^32) * 2π
distance_meters = (bytes_to_uint32(jitter_seed[4..8]) / 2^32) * 500

display_location = offset_by_bearing(actual_location, bearing_radians, distance_meters)
```

**Properties**: deterministic per `post_id` (same post always fuzzes to the same point — no averaging attack). Non-reversible without `JITTER_SECRET`. Uniform bearing 0-2π, uniform distance 0-500m (not fixed 500m).

**Secret rotation**: long-lived. Rotate only on compromise (requires re-fuzzing the entire posts table via Cloud Run Jobs). Not scheduled.

### Query Rules (CI-lint-audited)

- `ST_DWithin` Nearby filter + `ST_Distance` rendering use `display_location`.
- Admin moderation/analytics use `actual_location`.
- Reverse geocoding `city_name` uses `actual_location` (500m jitter could cross admin boundaries).
- 5km floor applied after fuzzing at render step (`:shared:distance` tests).
- GIST index on both columns; storage +2x geography is negligible.

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

Order matters: fuzz first (crypto jitter) → floor second (UX/privacy) → round to nearest 1km last (display). Examples: actual 4.5km → fuzz 4.8km → floor 5km → "5km" (fuzz doesn't leak). Actual 7.4km → fuzz 7.3km → floor unchanged → "7km". Actual 7.6km → fuzz 7.7km → floor unchanged → "8km".

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

Append-only: each edit inserts a new row with the **before-edit** snapshot, ordered by `edited_at DESC`. Retention until parent post hard-delete (cascade). Version label = `ROW_NUMBER() OVER (PARTITION BY post_id ORDER BY edited_at)` rendered as "Versi ke-N".

### Transactional Atomicity (mandatory)

> Mirrors `backend/ktor/src/main/kotlin/id/nearyou/app/post/CreatePostService.kt` and the future post-edit service. Update both when changing the lock+snapshot+update transaction shape.

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
FROM posts WHERE id = :post_id;

UPDATE posts SET content = :new_content, updated_at = NOW() WHERE id = :post_id;
COMMIT;
```

App-level retry on `unique_violation` edge case (sub-microsecond collision): rollback + 409 CONFLICT "Coba lagi sebentar."

---

## Follow Schema

> Mirrors V6 (`V6__follows.sql`) and `backend/ktor/src/main/kotlin/id/nearyou/app/follow/FollowService.kt`. Update both when changing the follow/unfollow shape.

```sql
CREATE TABLE follows (
    follower_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    followee_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (follower_id, followee_id),
    CHECK (follower_id != followee_id)
);

CREATE INDEX follows_follower_idx ON follows(follower_id, created_at DESC);
CREATE INDEX follows_followee_idx ON follows(followee_id, created_at DESC);
```

**Rules**: block applies bidirectional DELETE on both rows. Following a private-profile user is allowed (server creates the row); visibility of their posts is gated by `follows` EXISTS in the query. Follow/unfollow rate limit: 50/hour.

---

## Post Likes Schema

> Mirrors V7 (`V7__post_likes.sql`) and `backend/ktor/src/main/kotlin/id/nearyou/app/engagement/LikeService.kt`. V7 shipped in change `post-likes-v7`. Update both when changing the like shape.

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

> Mirrors V8 (`V8__post_replies.sql`) and `backend/ktor/src/main/kotlin/id/nearyou/app/engagement/ReplyService.kt`. V8 shipped in change `post-replies-v8` — schema + POST/GET/DELETE endpoints + `reply_count` on both timelines. The auto-hide trigger (via `reports.target_type = 'reply'`) and rate limiting under `reply-rate-limit` are tracked separately.

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

- Flat structure (no nested reply-to-reply threading in MVP). Max 280 chars (matches post length). Free 20/day, Premium unlimited.
- Soft delete only (tombstone label on the parent post's reply list).
- `is_auto_hidden` flag is set by the same 3-unique-reporters auto-hide trigger when `reports.target_type = 'reply'`.
- Block-aware read path: exclude replies from blocked users (both directions) AND replies where `is_auto_hidden = TRUE` unless the viewer is the author.

---

## Reports Schema

> Mirrors V9 (`V9__reports_moderation.sql`). Update both when changing the report shape.

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

CREATE INDEX reports_status_idx ON reports(status, created_at DESC);
CREATE INDEX reports_target_idx ON reports(target_type, target_id);
CREATE INDEX reports_reporter_idx ON reports(reporter_id, created_at DESC);
```

- `reporter_id` ON DELETE CASCADE (column is NOT NULL); on user hard-delete, submitted reports cascade — consistent with the Data Export scope matrix + Privacy retention policy (`06-Security-Privacy.md`).
- `reviewed_by` ON DELETE SET NULL so admin churn doesn't erase moderation history.

**Auto-hide trigger**: when 3 distinct reporters (accounts >7 days old) report the same `(target_type, target_id)`, the server sets `posts.is_auto_hidden = TRUE` (or `post_replies.is_auto_hidden`) and inserts a `moderation_queue` row. The unique `(reporter_id, target_type, target_id)` prevents the same user inflating the count via repeat reports.

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

> Mirrors V10 (`V10__notifications.sql`) + `backend/ktor/src/main/kotlin/id/nearyou/app/notifications/NotificationEmitter.kt`. V10 is the canonical authority for the type catalog.

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

`body_data` stores type-specific JSON (excerpts, billing grace end date, etc.). Retention: 90 days auto-purge via `/internal/cleanup`. Delivered via FCM push in parallel; the DB row is the source of truth for the in-app list.

**Event type catalog**: canonical addressing is `(target_type, target_id)` on the outer row — that column pair is what deep-links route to. `body_data` carries only what the outer pair can't supply (excerpts, secondary entity IDs, status strings, timestamps). Do NOT duplicate `target_id` inside `body_data`. See `V10__notifications.sql` + `NotificationWritePathTest.kt` for shipped shapes.

| Type | Trigger | Actor | `target_type` | Body data |
|------|---------|-------|---------------|-----------|
| `post_liked` | Another user likes a post | liker | `post` | `{post_excerpt}` |
| `post_replied` | Another user replies | replier | `post` (the replied-to post) | `{reply_id, reply_excerpt}` |
| `followed` | Another user follows | follower | NULL | `{}` |
| `chat_message` | New chat message | sender | `message` | `{conversation_id, preview}` |
| `subscription_billing_issue` | RevenueCat `BILLING_ISSUE` | NULL | NULL | `{grace_end_at}` |
| `subscription_expired` | Premium period lapsed | NULL | NULL | `{}` |
| `post_auto_hidden` | 3 reports auto-hid user's post or reply | NULL | `post` or `reply` (dynamic) | `{reason}` |
| `account_action_applied` | Admin action on user | NULL | NULL | `{action_type, reason, suspended_until}` |
| `data_export_ready` | Export worker finished | NULL | NULL | `{signed_url, expires_at}` |
| `chat_message_redacted` | Admin redacted a message the user participates in | NULL | `message` | `{conversation_id}` |
| `privacy_flip_warning` | Downgrade-to-Free with private profile, flip scheduled | NULL | NULL | `{privacy_flip_scheduled_at}` |
| `username_release_scheduled` | Custom username change confirmed; old handle releases at `released_at` | NULL | NULL | `{old_username, released_at}` |
| `apple_relay_email_changed` | Apple S2S `email-disabled`/`email-enabled` event | NULL | NULL | `{new_email_masked}` |

Rationale: outer `(target_type='post', target_id=<post_id>)` answers "which post"; body_data carries only what can't be inferred (excerpt, `reply_id` since target points at the parent, `reason` for auto-hide copy). V10 shipped this; table amended 2026-04-24 (PR [#24](https://github.com/aditrioka/nearyou-id/pull/24)). For `chat_message_redacted`, target_id is the redacted message; `conversation_id` lets the client route without a second fetch.

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
        'user', 'apple_s2s_consent_revoked', 'apple_s2s_account_delete', 'admin'
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

**Source semantics**: `user` / `admin` / `apple_s2s_consent_revoked` → `NOW() + 30 days`, 30-day cancellable grace. `apple_s2s_account_delete` → `NOW()`, no grace (Apple-required).

- Cancellation sets `cancelled_at`; `apple_s2s_account_delete` rows cannot be cancelled.
- **Immediate-execution for `apple_s2s_account_delete`**: the Apple S2S handler inserts the row AND synchronously enqueues a one-shot tombstone+cascade job before responding to Apple. If the sync job fails, the daily worker backstops via `deletion_requests_immediate_idx`.
- Daily hard-delete worker: scans `scheduled_hard_delete_at <= NOW() AND executed_at IS NULL AND cancelled_at IS NULL`, runs tombstone + cascade + deletion-log write, sets `executed_at = NOW()`.

---

## Admin Users Schema

```sql
CREATE TABLE admin_users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email TEXT NOT NULL UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    password_hash TEXT NOT NULL,                  -- Argon2id
    totp_secret_encrypted BYTEA,                  -- AES-256, key in GCP Secret Manager
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

Solo admin period: Oka's row with `webauthn_enrolled = FALSE` (TOTP required). Before the second admin onboards, Oka's row MUST have `webauthn_enrolled = TRUE` plus at least one `admin_webauthn_credentials` row. Session timeout 30 min idle (via `last_active_at`).

### Admin Session Cookie + WebAuthn

Classic server-side sessions (not JWT), since the Admin Panel is a stateful Ktor + HTMX app.

- **Cookie** `__Host-admin_session` with `Secure; HttpOnly; SameSite=Strict; Path=/; Domain=admin.nearyou.id`; value = opaque 256-bit random token, base64url encoded. SHA256 stored in `admin_sessions.session_token_hash` (lookup by hash, plain never persisted).
- **CSRF**: second 256-bit token per session, returned in login response body, SHA256 stored in `admin_sessions.csrf_token_hash`. State-changing requests must include `X-CSRF-Token`; mismatch → 403 + audit log `admin_csrf_violation`. Regenerated on every successful login.
- **Privilege-escalation rotation**: any change to `admin_users.role` rotates the affected admin's session cookies.

WebAuthn challenge: inserted with `expires_at = NOW() + INTERVAL '5 minutes'`; on verify, marked `consumed_at = NOW()`; re-use rejected via `consumed_at IS NOT NULL`. Weekly cleanup deletes rows where `expires_at < NOW() - INTERVAL '1 day' AND consumed_at IS NULL`.

---

## Admin Regions Schema

> Mirrors V11/V12 (`V11__admin_regions.sql`, `V12__admin_regions_seed.sql`).

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

V12 seed: 38 provinces + 514 kabupaten/kota (including all 6 DKI children), polygons simplified to ~5.5 m tolerance + 6-decimal `ST_AsText` precision (33 MB total). Coastal kabupaten (48 of 514) carry a 12 nautical mile (~22 km) maritime buffer. IDs = stable OSM relation IDs. Reproducible re-seed pipeline lives at `dev/scripts/import-admin-regions/`.

---

## Timeline Implementation

### Composite Indexes + Block-Aware Query Patterns

```sql
CREATE INDEX posts_timeline_cursor_idx ON posts (created_at DESC, id DESC) WHERE deleted_at IS NULL;
CREATE INDEX posts_nearby_cursor_idx ON posts USING GIST (display_location, created_at) WHERE deleted_at IS NULL;
```

**Nearby** — mirrors `infra/supabase/src/main/kotlin/id/nearyou/app/infra/repo/JdbcPostsTimelineRepository.kt`. Update both when changing the canonical query shape:
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

**Following** — mirrors `infra/supabase/src/main/kotlin/id/nearyou/app/infra/repo/JdbcPostsFollowingRepository.kt`. Update both when changing the canonical query shape:
```sql
SELECT p.* FROM visible_posts p
WHERE (p.created_at, p.id) < ($cursor_ts, $cursor_id)
  AND p.author_id IN (SELECT followee_id FROM follows WHERE follower_id = :viewer_id)
  AND p.is_auto_hidden = FALSE
  AND p.author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :viewer_id)
  AND p.author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :viewer_id)
ORDER BY p.created_at DESC, p.id DESC
LIMIT 20;
```

**Global** — verbatim from `infra/supabase/src/main/kotlin/id/nearyou/app/infra/repo/JdbcPostsGlobalRepository.kt`, shipped in `global-timeline-with-region-polygons` change. Update both when changing the canonical query shape:
```sql
SELECT p.id,
       p.author_id,
       p.content,
       ST_Y(p.display_location::geometry) AS lat,
       ST_X(p.display_location::geometry) AS lng,
       p.city_name,
       p.created_at,
       (pl.user_id IS NOT NULL) AS liked_by_viewer,
       c.n AS reply_count
  FROM visible_posts p
  LEFT JOIN post_likes pl ON pl.post_id = p.id AND pl.user_id = :viewer_id
  LEFT JOIN LATERAL (
      SELECT COUNT(*) AS n
        FROM post_replies pr
        JOIN visible_users vu ON vu.id = pr.author_id
       WHERE pr.post_id = p.id
         AND pr.deleted_at IS NULL
  ) c ON TRUE
 WHERE p.author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :viewer_id)
   AND p.author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :viewer_id)
   AND (p.created_at, p.id) < (:cursor_ts, :cursor_id)
 ORDER BY p.created_at DESC, p.id DESC
 LIMIT 31;  -- page-size 30 + one probe row for next_cursor
```

`p.city_name` is populated at INSERT by the `posts_set_city_tg` BEFORE INSERT trigger (V11) running the 4-step fallback ladder against the `admin_regions` polygon set (V12). NULL `city_name` (legacy V4 row or polygon-coverage gap) serializes as `""`. Guest Global timeline omits the `user_blocks` subqueries (no viewer identity); guest read-only access is deferred.

### V11 + V12 Migration History

V11 ships schema-only: table + 4 indexes + `posts_set_city_fn` PL/pgSQL + `posts_set_city_tg` BEFORE INSERT trigger + idempotent `visible_posts` view refresh. V12 ships 552 INSERTs (38 provinces + 514 kabupaten/kota); trailing validity sweep cleans 3 precision-truncation self-intersections (Karanganyar, Sukoharjo, Buton Selatan). The schema/seed split deviated from original "single migration" design (rationale in archived `design.md` Decision 3 amendment); the empty-table window was safe because trigger ladder + timeline DTOs were NULL-tolerant.

### Sliding Window Session Tracking (Server-Side via Redis)

- Client generates a `session_id` (UUID) when the app goes to foreground, sends it via the `X-Session-Id` header.
- `timeline_offset:{user:<user_id>}:<session_id>` TTL 1 hour (soft cap, UX nudge).
- `timeline_rolling:{user:<user_id>}` TTL 1 hour (hard cap, authoritative). Both caps reached: empty response + upsell flag.
- Guest rolling counter: `timeline_rolling_guest:{jti:<guest_jwt_jti>}` TTL 1 hour, cap 30 posts.

Key format uses hash tag `{scope:<value>}` for Upstash cluster co-location.

### Geometry-heavy migration conventions

Any seed migration shipping PostGIS geometry >5 MB (V12's 33 MB is the precedent) MUST:

1. **`ST_SimplifyPreserveTopology` with tolerance smaller than the consumer's tightest spatial threshold.** V12 uses 5.5 m, safely under the trigger's 10 m buffer (see `dev/scripts/import-admin-regions/generate-seed.py:255`).
2. **Cap `ST_AsText` precision at consumer's needs.** PostGIS defaults to 16 decimals; 6 decimals (~10 cm) halves WKT byte size and is plenty for any geo feature larger than a backyard.
3. **Idempotent validity sweep at end of migration:**
   ```sql
   UPDATE <seed_table>
      SET geom = ST_Multi(ST_CollectionExtract(ST_MakeValid(geom::geometry), 3))::geography
    WHERE NOT ST_IsValid(geom::geometry);
   ```
   `ST_MakeValid` returns a `GeometryCollection` for self-intersections; `ST_CollectionExtract(..., 3)` keeps polygon parts; `ST_Multi` rewraps. Idempotent.

Combined V12 effect: 72 MB → 33 MB, unchanged trigger semantics.

---

## Search Implementation (PostgreSQL FTS + pg_trgm)

> Mirrors V13 + `backend/ktor/src/main/kotlin/id/nearyou/app/search/SearchService.kt`.

V13 ships `CREATE EXTENSION IF NOT EXISTS pg_trgm;` plus GIN indexes (`posts_content_tsv_idx`, `posts_content_trgm_idx`) over the GENERATED `posts.content_tsv`. Uses `'simple'` tsvector — Indonesian stopwords aren't in Postgres core, acceptable for MVP. Month 6+: custom dictionary or XLM-R tokenizer service.

```sql
SELECT p.*,
       ts_rank(p.content_tsv, plainto_tsquery('simple', :query)) AS rank
FROM visible_posts p
JOIN visible_users u ON p.author_id = u.id
WHERE (p.content_tsv @@ plainto_tsquery('simple', :query)
     OR p.content % :query
     OR u.username % :query)
  AND p.is_auto_hidden = FALSE
  AND p.author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :viewer_id)
  AND p.author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :viewer_id)
  AND (u.private_profile_opt_in = FALSE
       OR u.subscription_status NOT IN ('premium_active', 'premium_billing_retry')
       OR EXISTS (SELECT 1 FROM follows f WHERE f.follower_id = :viewer_id AND f.followee_id = u.id))
ORDER BY rank DESC, p.created_at DESC
LIMIT 20 OFFSET :offset;
```

Rate: 60 queries/h per Premium user; Free rejects 403 + upsell paywall. On shadow-ban / unban / block / unblock the app invalidates any Redis search cache; the GIN index is auto-maintained.

---

## Direct Messaging Implementation

> Mirrors V15 + `backend/ktor/src/main/kotlin/id/nearyou/app/chat/ChatService.kt`.

### Chat Flow (Pre-Swap, Months 1-14)

```
A POSTs /api/v1/chat/:conversation_id/messages
  → Ktor validates quota / participant / not blocked
  → INSERT INTO chat_messages
  → Supabase Realtime broadcast on "realtime:conversation:<id>" (skipped if sender shadow-banned)
B receives broadcast (RLS-checked) → render
B fetches REST history to resync as needed
```

Pointers: sender shadow-ban skips publish (invisible-actor model — see [`openspec/specs/chat-realtime-broadcast/spec.md`](../openspec/specs/chat-realtime-broadcast/spec.md)). Chat-message INSERT + `notifications` emit run in the same transaction; post-commit FCM fan-out (see [`openspec/specs/in-app-notifications/spec.md`](../openspec/specs/in-app-notifications/spec.md)).

**Failure handling**: INSERT-OK + broadcast-fail → Ktor retries 3x exponential backoff, otherwise client fetches via REST. Offline client → on next open, fetch delta via `GET /api/v1/chat/:conversation_id/messages?after=:last_message_id`. Duplicate broadcast → client dedupes by `message_id` UUID.

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

CREATE UNIQUE INDEX conv_slot_unique ON conversation_participants(conversation_id, slot) WHERE left_at IS NULL;
CREATE INDEX conversation_participants_user_active_idx ON conversation_participants (user_id) WHERE left_at IS NULL;
CREATE INDEX conversation_participants_conversation_idx ON conversation_participants (conversation_id);
```

### Insert Flow (Ktor application layer)

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
             ELSE NULL END, ...);
COMMIT;
```

Partial unique index prevents a 3rd active slot (race-proof). Advisory lock serializes slot assignment per conversation.

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

First CHECK prevents fully empty messages (snapshot term keeps historical rows valid after embedded-post hard-delete — FK cascade nulls `embedded_post_id`). Second CHECK enforces redaction atomicity (all-null OR `redacted_at` + `redacted_by` both set). `embedded_post_edit_id` / `redacted_by` ON DELETE SET NULL preserve history. Redaction UX: client renders "Pesan ini telah dihapus oleh moderator." regardless of original content; recipient receives `chat_message_redacted` notification.

### Block Enforcement in Chat

```sql
SELECT 1 FROM user_blocks
WHERE (blocker_id = :sender AND blocked_id IN
       (SELECT user_id FROM conversation_participants WHERE conversation_id = :conv_id AND user_id != :sender))
   OR (blocked_id = :sender AND blocker_id IN
       (SELECT user_id FROM conversation_participants WHERE conversation_id = :conv_id AND user_id != :sender));
```

Hit → 403 "Tidak dapat mengirim pesan ke user ini". Existing history stays readable.

### Post-Swap Chat Persistence (Month 15+)

`04-Architecture.md` covers the Redis Streams approach: `XADD` per message, consumer groups + `XAUTOCLAIM` for failover, `XTRIM ... MAXLEN ~ 100`, REST fetch for older history.

---

## Block User Implementation

> Mirrors V5 (`V5__user_blocks.sql`) and `backend/ktor/src/main/kotlin/id/nearyou/app/block/BlockService.kt`. Update both when changing the block-action shape.

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
INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (:blocker, :blocked) ON CONFLICT DO NOTHING;
DELETE FROM follows
WHERE (follower_id = :blocker AND followee_id = :blocked)
   OR (follower_id = :blocked AND followee_id = :blocker);
COMMIT;
```

Repository-layer query rules: exclude `author_id` from timelines / replies / search if a block exists in either direction; profile view = 404; DM send = 403. Enforced by `BlockExclusionJoinRule` Detekt rule (allowlist: Repository own-content path + admin module). Rate limit 30 block/unblock per hour per user (anti-flip-flop).

---

## FCM Token Registration

> Mirrors V14 (`V14__user_fcm_tokens.sql`).

### Schema

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

Token / app_version length CHECKs are defense-in-depth against a malformed client payload bypassing the route-layer guard.

### Endpoint + Cleanup

`POST /api/v1/user/fcm-token` with `{ token, platform, app_version }`. Upsert on `(user_id, platform, token)` unique; **`last_seen_at = NOW()` updated on every call** (authoritative freshness signal). Expired (on send: FCM 404/410) → immediate row delete. Stale (weekly via `/internal/cleanup`) → delete `WHERE last_seen_at < NOW() - INTERVAL '30 days'`.

---

## Feature Flags (Firebase Remote Config)

Server-side fetch via Firebase Admin SDK, cached with TTL 5 minutes in Redis.

**Shipped** (Kotlin reference exists in a `*Service.kt`):

- `premium_like_cap_override` (integer, default 10) — read in `engagement/LikeService.kt`. Coerces non-positive values to default. See Decision 28 in `08-Roadmap-Risk.md`.
- `premium_reply_cap_override` (integer, default 20, oversized-flag clamp at 10,000) — read in `engagement/ReplyService.kt`.
- `premium_chat_send_cap_override` (integer, default 50, oversized-value fallback threshold at 10,000) — read in `chat/ChatService.kt`. Values above threshold fall back to default 50, not a clamp.
- `search_enabled` (boolean, default TRUE) — kill switch read in `search/SearchService.kt`.

**Reserved / DESIGN** (referenced in specs; consumer not yet shipped):

- `image_upload_enabled` (boolean, default FALSE): gate for Month 6 image-upload launch
- `attestation_mode` (enum `enforce` | `warn` | `off`, default `enforce`) + `attestation_bypass_google_ids_sha256` (list): QA bypass
- `force_update_min_version` (string): force-upgrade floor for the mobile app
- `perspective_api_enabled` (boolean, default TRUE): kill switch for Layer 3 toxicity classifier. Flag name retains historical "perspective" branding from the original spec; the underlying vendor is now OpenAI Moderation (`omni-moderation-latest`) after Perspective announced sunset end-of-2026.
- `perspective_api_high_score_threshold` (number, default 0.8): Layer 3 AutoHide threshold; clamped to `[0.0, 1.0]` on every read; out-of-range falls back to default with Sentry WARN
- `perspective_api_flag_threshold` (number, default 0.6): Layer 3 FlagOnly band lower bound; clamped to `[0.0, 1.0]`; cross-flag misconfig (`flag > high_score`) reverts to BOTH defaults with Sentry ERROR
- `premium_username_customization_enabled` (boolean, default TRUE): kill switch (see Premium Customization DESIGN stub)
- `moderation_profanity_list` / `moderation_uu_ite_list` / `moderation_match_threshold`: tied to the Content Moderation Lists DESIGN stub

Client-side: Firebase Remote Config SDK fetches on app launch + foreground. Server and client MUST fetch the same keys.

---

## Subscription Event-Level Tracking

```sql
CREATE TABLE subscription_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    event_type VARCHAR(32) NOT NULL
        CHECK (event_type IN ('initial_purchase', 'renewal', 'grant', 'cancellation', 'billing_issue', 'expiration')),
    source VARCHAR(32) NOT NULL CHECK (source IN ('paid', 'referral', 'manual_admin')),
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

### RevenueCat Webhook — DESIGN

> **Status: DESIGN** (as of 2026-05-07). `POST /internal/revenuecat-webhook` is not mounted; secret slots `revenuecat-webhook-secret` + `revenuecat-webhook-hmac-secret` are reserved. Future proposal will define auth layers (Bearer + HMAC + IP allowlist), idempotency via `subscription_events.revenuecat_event_id` UNIQUE, and the event-handler dispatch table.

---

## Referral System — DESIGN

> **Status: DESIGN** (as of 2026-05-07). `SignupService.kt` does NOT accept `invite_code`; `referral_tickets` / `granted_entitlements` tables not created; no `/internal/referral-activity-check` worker.
>
> Already-shipped support: `users.invite_code_prefix` (populated via `InviteCodePrefixDeriver.kt`, 10-char fallback on collision), `users.inviter_reward_claimed_at` (V2 lifetime sentinel), `subscription_events.source` accepts `'referral'`/`'manual_admin'` (V9), GCP secret slot `invite-code-secret`.
>
> Future proposal will define `referral_tickets` + `granted_entitlements` schema (with `granted_entitlements_inviter_once_idx` partial unique index for DB-level lifetime-cap enforcement), activity-gate worker, anti-fingerprint-collision checks at signup, and RevenueCat dispatch.

---

## Cache & Rate Limit (Upstash Redis)

Rate-limit counter: `INCR` + `EXPIRE`. Hot data cache: user profile, conversation metadata, `token_version`, timeline session counter. TTLs: attestation 1h, geocode 30d, Remote Config 5 min. Streams (post-swap): chat persistence + fan-out. Key format standard (cluster co-location):

```
rate:{user:<user_id>}:<action>
timeline_session:{user:<user_id>}:<session_id>
timeline_rolling:{user:<user_id>}
token_version:{user:<user_id>}
rate:area:{geohash:<geohash5>}
rate:guest_issue:{ip:<hashed-ip>}
rate:guest_issue:{fp:<fp_hash>}
rate:guest_issue_day:{ip:<hashed-ip>}
rate:guest_issue_global:{global:1}
stream:conv:{conv:<conversation_id>}
remote_config:{flag:<flag_name>}
geocode:{geocell:<lat2dp>_<lng2dp>}
```

Hash tag `{scope:<value>}` ensures same-scope keys land on the same Redis slot (multi-key ops safe). `RedisHashTagRule` Detekt rule rejects keys without a hash tag. Global circuit breaker uses `{global:1}` as a singleton scope so the shared counter still has a stable slot. IP-axis keys use `<hashed-ip>` (= `IpHasher.hash(clientIp)` from `:infra:otel`, first 16 hex of `SHA-256(ip.toByteArray(UTF-8))`) per the convention shipped with the `rate-limit-ip-hashing` change — raw IP literals MUST NOT appear in any rate-limit Lua key (they leak into Tempo `db.statement` span attributes and the `key=` field of structured logs).

---

## Rate Limiting Implementation (4-Layer, Hardened)

> **Layer 2 shipped** via change `like-rate-limit` (`rate-limit-infrastructure` capability) — see `openspec/specs/rate-limit-infrastructure/spec.md` for the canonical contract. Production binding: Redis-backed `RedisRateLimiter` in `:infra:redis` (single-Lua sliding-window script, hash-tag-formatted keys, `withContext(Dispatchers.IO)` wrap at the call site). `RateLimiter` interface lives in `:core:domain`. `computeTTLToNextReset(userId)` + hash-tag key format enforced by `RateLimitTtlRule` and `RedisHashTagRule` Detekt rules. V9's `ReportRateLimiter` (10/hour) is also ported.
>
> Shipped per-endpoint limits:
> - Like `POST /api/v1/posts/{post_id}/like`: 10/day Free + 500/h burst (both tiers), `premium_like_cap_override` flag, idempotent re-like releases the slot.
> - Reply `POST /api/v1/posts/{post_id}/replies`: 20/day Free, unlimited Premium, daily-only (no burst per `02-Product.md:224`), `premium_reply_cap_override` flag, no `releaseMostRecent`.
> - Chat send `POST /api/v1/chat/{conversation_id}/messages`: 50/day Free, unlimited Premium, daily-only, `premium_chat_send_cap_override` flag, no `releaseMostRecent`. GET endpoints + POST `/conversations` are not per-endpoint rate-limited.
>
> Layers 1 / 3 / 4 remain future work.

### Layer 1: Per IP + Attestation-Gated Guest Token + Pre-Issuance Rate Limits

**Guest flow**: client collects Play Integrity (Android) or App Attest (iOS) on first app open → `POST /api/v1/guest/session` with attestation payload + device fingerprint hash → server verifies → attestation passes + pre-issuance limits OK → issue short-lived guest JWT (TTL 24h, with `jti` claim) → client includes `Authorization: Guest <jwt>` on guest requests → post-issuance rate limit on compound key `rate:{jti:<guest_jwt_jti>}:<ip>`, 100 req/min. JWT expiry = re-attestation (no auto-refresh). Attestation failure = 403.

**Pre-issuance rate limits** (defense-in-depth for attestation-bypass + CGNAT flood):

| Layer | Key | Limit | TTL |
|-------|-----|-------|-----|
| 1a. IP hard | `rate:guest_issue:{ip:<hashed-ip>}` | 10 tokens/hour | 1 hour |
| 1b. IP daily cap | `rate:guest_issue_day:{ip:<hashed-ip>}` | 50 tokens/day | 24 hours |
| 1c. Device fingerprint | `rate:guest_issue:{fp:<fp_hash>}` | 5 tokens/hour | 1 hour |
| 1d. Global circuit breaker | `rate:guest_issue_global:{global:1}` | 10k tokens/min | 1 minute |

**Device fingerprint** (in request body, hashed SHA256 client-side):
- Android: `Settings.Secure.ANDROID_ID` + app signature hash
- iOS: `identifierForVendor` + bundle ID hash

**CGNAT mitigation**: IP limit hit but fingerprint not yet → allow 1 token + log `cgnat_suspected=true`. Both hit → block + user-facing "terlalu banyak permintaan dari jaringan ini, coba WiFi lain atau login". Global circuit breaker prevents DDoS amplification.

**Authenticated user IP baseline**: 1000 req/min/IP (measured off `CF-Connecting-IP` via the shipped `ClientIpExtractor` plugin).

**Guest permissions**: read Global timeline (latest 10 posts) only — no write, no profile view. **Upgrade path**: on Google/Apple sign-in + attestation, the guest token is invalidated and replaced with an authenticated JWT.

### Layer 2: Per User (with WIB Stagger)

| Action | Limit |
|--------|-------|
| Post | 10/day Free, unlimited Premium |
| Reply | 20/day Free, unlimited Premium |
| Chat | 50/day Free, unlimited Premium |
| Like | 10/day Free + 500/h burst (both tiers); Premium otherwise unlimited |
| Follow | 50/h |
| Report | 20/h |
| Block/Unblock | 30/h |
| Search (Premium) | 60/h |
| Image upload (Premium, Month 6+) | 50/day + 1/60 sec throttle |
| Timeline read Free | 150 posts/h rolling (hard); 50/session (soft) |
| Timeline read Guest | 30 posts/h rolling (hard); 10/session (soft) |

**Timezone reset stagger** (prevents thundering herd at 00:00 WIB): per-user reset offset = `hash(user_id) % 3600` seconds (effective reset window 00:00-01:00 WIB, linearly distributed). Redis key TTL = time until `next_00:00_WIB + offset`. Centralized in `computeTTLToNextReset(user_id)`; `RateLimitTtlRule` Detekt rule enforces usage at every daily-rate-limit endpoint (hourly limits skip the stagger).

**Rejected alternative**: rolling 24h window — UX confusing, Redis sliding window complex.

### Layer 3: Per Google/Apple ID

1 identifier = 1 active account. Sticky ban. Same human signing up with Google + Apple = 2 separate accounts (no linking).

### Layer 4: Per Area (anti local spam)

Max 50 new posts in a 1km radius / 1h (via `display_location` spatial query). Threshold hit → manual review. Redis INCR + EXPIRE counter.

---

## CSAM Detection Archive — DESIGN

> **Status: DESIGN** (as of 2026-05-07). No migration ships `csam_detection_archive`; no Kotlin consumes it. Secret slots `csam-archive-aes-key` + `cf-worker-csam-secret` provisioned. Future proposal will define schema (90-day preservation bypassing cascade-delete, `cf_match_id`/`image_hash` UNIQUE for idempotent dedup), AES-256-encrypted metadata column, trigger paths (admin manual / CF Worker / email poll). See `06-Security-Privacy.md` for Kominfo reporting SOP.

---

## Admin Actions Log Schema

Canonical (also referenced from `07-Operations.md`):

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

Immutable: UPDATE/DELETE revoked at the role level for `admin_app`. Retention 1 year minimum.

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

**Rules for code**: timeline / search use `FROM visible_posts`; user profile uses `FROM visible_users`; like/reply counters JOIN `visible_users`; notifications consume `visible_posts`; follower/following list JOINs `visible_users`; chat delivery filters at the application layer when consuming from Supabase Realtime.

**Own-content exception**: a banned user sees their own content normally. The own-content path bypasses the views with `WHERE author_id = current_user_id OR viewer_id = current_user_id`, centralized in the Repository layer.

`RawFromPostsRule` Detekt rule detects raw `FROM posts` / `FROM users` / `FROM post_replies` in business queries (allowed only in the Repository own-content path + admin module).

---

## Client IP Extraction

> Mirrors `backend/ktor/src/main/kotlin/id/nearyou/app/common/ClientIpExtractor.kt`. Update both when changing the precedence ladder.

All inbound HTTPS to `api.nearyou.id` / `admin.nearyou.id` transits Cloudflare; Cloud Run sees the CF edge IP in `X-Forwarded-For`. The real client IP is carried in `CF-Connecting-IP`.

The `ClientIpExtractorPlugin` (top of the request pipeline) resolves IP with this precedence:
1. `CF-Connecting-IP` header (Cloudflare origin-pull path; production + staging trustworthy).
2. First entry in `X-Forwarded-For` (fallback for local dev without Cloudflare).
3. `call.request.origin.remoteHost` (last-resort).

Stored under `ClientIpAttributeKey` (`AttributeKey<String>("ClientIp")`); consumers read via `call.clientIp`. Used by every rate-limit key, audit log entry, `admin_sessions.ip`, `admin_actions_log.ip`. Direct reads of `X-Forwarded-For` are forbidden by Detekt rule `RawXForwardedForRule`. Staging (`api-staging.nearyou.id`) sits behind Cloudflare; local dev falls through to remoteHost.

---

## Content Moderation Keyword Lists — DESIGN

> **Status: DESIGN** (as of 2026-05-07). No `ModerationListLoader`, no Aho-Corasick matcher; Remote Config keys `moderation_profanity_list` / `moderation_uu_ite_list` / `moderation_match_threshold` reserved (no Kotlin consumer). Fallback resource files not committed. Secret slot `content-moderation-fallback-list` reserved. Future proposal will define storage layers (Remote Config primary + repo fallback + Secret Manager last-resort), deterministic fallback ladder (Redis 5-min cache → Remote Config → file → Secret Manager), Aho-Corasick matching engine, threshold-based "high score", quarterly UU ITE legal-advisor review.

---

## Health Check Implementation

> Mirrors `backend/ktor/src/main/kotlin/id/nearyou/app/health/`.

`GET /health/live` returns plain `OK`. `GET /health/ready` runs three checks in parallel — Postgres `SELECT 1` (500ms timeout), Redis `PING` (200ms), Supabase Realtime `GET /rest/v1/` (500ms) — and returns 200 if all OK or 503 with the checks payload otherwise. Both endpoints rate-limited 60 req/min/IP (anti-scrape).

---

## Key Implementation Notes

### Spatial Queries

- PostGIS `ST_DWithin` for radius filtering (uses `display_location`); `ST_Contains` for reverse geocoding `city_name` (uses `actual_location`).
- GIST index on geometry columns (posts has dual: display + actual, plus `admin_regions`).
- Cursor-based pagination + composite `(created_at, id)` index avoids OFFSET scan.
- Exclude soft-deleted + shadow-banned + blocked + auto-hidden users/posts via `visible_posts` view + `is_auto_hidden` flag + block subquery.
- Target <100ms for standard radius query. Mitigations for dense Jakarta @ 100km / 50k MAU: composite `(created_at DESC, author_id)` index, partial index for high-density areas, Phase 2 benchmark, pre-materialized timeline (H3 hex) if needed.

### Security

- JWT validation middleware (RS256 primary + HS256 companion); `token_version` claim compared on every request.
- RLS regex-guarded `split_part` + `auth.jwt()->>'sub'` check, with malformed-topic test cases.
- Token version check via Redis 5-minute cache + DB fallback.
- Input sanitization + length enforcement per content type.
- 4-layer rate limiting (attestation-gated guest + pre-issuance, user + WIB stagger, identifier, area).
- HTTPS / WSS enforced. Google/Apple ID hashed in logs.
- Device attestation mandatory (Play Integrity + App Attest) + bypass whitelist via Remote Config; device fingerprint as correlation signal only.
- OIDC auth on `/internal/*`. Refresh-token reuse detection + family revocation.
- Anomaly detection metrics + Sentry/Slack alerts. Age gate 18+ (UU PDP). `rejected_identifiers` blocklist for under-18 bypass prevention.
- HMAC-based jitter for anti-triangulation. Backup `pg_dump` + `age` stream encryption before R2 upload.
- Block filter at repository layer + `BlockExclusionJoinRule`. Admin panel via scoped `admin_app` DB role.
- Environment separation at every layer (Cloud Run, Supabase, Upstash, R2, GCP Secret Manager).
- Client IP via `CF-Connecting-IP` + shipped `ClientIpExtractor` plugin.

### Performance

GIST indexes spatial (dual: display + actual); HikariCP max 20 connections per Ktor instance + Supabase PgBouncer; Upstash Redis caching + Streams (post-swap); CTE batching mandatory on timeline endpoint; GIN indexes (FTS + trgm) on `posts.content`; OTel tracing for latency root-cause.

### Observability

Structured JSON logging; metrics via GCP Cloud Monitoring + Supabase dashboard; Sentry KMP unified for backend + mobile errors (dSYM + ProGuard mappings via CI); OTel SDK to Grafana Cloud (100% head sampling dev Phase 2, 10% base + 100% error/slow prod); Amplitude funnels/cohorts/retention (consent-gated per UU PDP); DB size alert at 60/75/90% of cap from Month 3; security event logs cover attestation failures, reuse detection, rate limit hits, RLS denials, JWT verify spikes, age gate rejections, webhook signature fails, `rejected_identifiers` inserts. `/health/ready` monitored by Cloud Run native probe + external uptime check.
