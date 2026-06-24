-- V35: login_events — the durable, append-only, per-user security/anti-abuse
-- record of authenticated sign-in and refresh events.
--
-- Backs the `login-history-tracking` change. It is the durable login-history
-- store the referral activity-gate's anti-abuse legs depend on (the dependency
-- deferred from `referral-grant-worker` #353): the activity-check worker reads it
-- for the invitee's engagement legs (≥3 distinct login-days, ≥5 app sessions) and
-- the device-fingerprint anti-collision voids (the invitee's fingerprint in the
-- inviter's 90-day history; the invitee's identifier seen on the inviter's
-- device). See docs/01-Business.md §212-213 + docs/05-Implementation.md.
--
-- Written best-effort from the sign-in and refresh ROUTES (where call.clientIp +
-- the request body live) via LoginEventRecorder; RefreshTokenService is untouched.
-- The IP comes from the call.clientIp request-context accessor only (never a raw
-- X-Forwarded-For — the client-IP invariant). identifier_hash is the one-way
-- SHA-256 of the provider subject (never the raw Google/Apple identifier).
--
-- PII lifecycle (UU-PDP): login_events is essential security / anti-abuse data
-- under a legitimate-interest basis — collected for ALL authenticated users,
-- exempt from the opt-in analytics_consent toggle (it is not product analytics).
-- It is fully integrated into the data lifecycle:
--   * retention   — 90-day auto-purge ("Session trail", docs/06 § Retention),
--                   added to the /internal/cleanup worker (scheduled-retention-cleanup).
--   * export      — the "Session history (fingerprint, IP)" export category
--                   (account-data-export), now IP-bearing.
--   * deletion    — explicit DELETE in the hard-delete worker
--                   (account-hard-delete-worker): the FK ON DELETE CASCADE below
--                   does NOT fire there because that worker TOMBSTONES (does not
--                   row-delete) the user, so the cascade is a backstop only.
--
-- ip_subnet_24 is a STORED generated /24 of `ip` (network(set_masklen(ip,24))).
-- set_masklen + network are IMMUTABLE, so the generated column is legal. It is
-- RECORDED ONLY (export + forensics) — per the operator decision (design D8a) the
-- /24 is NEVER a voiding signal, because Indonesian carrier-grade NAT makes a
-- shared /24 common among unrelated users and subnet-alone voiding would
-- false-void legitimate same-carrier referrals.
--
-- The (user_id, occurred_at DESC) btree serves every windowed read (the invitee's
-- own engagement counts + the inviter's history scans). No index predicate uses
-- NOW() or any volatile expression (partial-index immutability invariant) — this
-- is a plain non-partial btree, so the invariant is trivially satisfied.
--
-- No inline GRANT: like granted_entitlements / subscription_events, this is a
-- regular application-role table (the admin_app GRANTs in V16/V24 are a separate
-- role for the admin tables only).

CREATE TABLE login_events (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    occurred_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    event_type             VARCHAR(16) NOT NULL
        CHECK (event_type IN ('signin', 'refresh')),
    ip                     INET,
    ip_subnet_24           INET GENERATED ALWAYS AS (network(set_masklen(ip, 24))) STORED,
    device_fingerprint_hash TEXT,
    identifier_hash        TEXT
);

-- Workhorse index: the invitee's own windowed counts (login-days, sessions) and
-- the inviter's windowed device-fingerprint history scans are all
-- `WHERE user_id = ? AND occurred_at >= ?`. Non-partial → no volatile predicate.
CREATE INDEX login_events_user_occurred_idx
    ON login_events (user_id, occurred_at DESC);
