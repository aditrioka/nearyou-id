-- V25: users.hide_distance_opt_in — the Premium "Hide Distance" toggle
-- (docs/01-Business.md § Hide Distance Mechanics; the `hide-distance` capability).
--
-- Additive, no backfill: the column default covers every existing row. Mirrors the
-- `private_profile_opt_in BOOLEAN NOT NULL DEFAULT FALSE` shape from V2__auth_foundation.sql.
--
-- Effectiveness is read-gated, NOT write-gated: the flag only suppresses the distance
-- number while the user is effectively Premium (subscription_status IN
-- ('premium_active','premium_billing_retry')) — a Free user with a stale TRUE reads as OFF.
-- It is symmetric on the Nearby feed: the distance number is hidden for a (post-author,
-- viewer) pair when EITHER side is effectively hiding.
--
-- `visible_users` (V7) was created as `SELECT u.*`, but a Postgres view freezes its column
-- list at CREATE time — `*` is expanded then and a later-added table column does NOT appear in
-- the view. The Nearby read joins the author via `visible_users` and projects
-- `u.hide_distance_opt_in`, so the view MUST be recreated to surface the new column.
-- `CREATE OR REPLACE VIEW` re-expands `*` and appends the new column at the end (the only kind of
-- column change CREATE OR REPLACE permits), preserving the existing column order + the view's
-- shadow-ban/soft-delete filter.

ALTER TABLE users
    ADD COLUMN hide_distance_opt_in BOOLEAN NOT NULL DEFAULT FALSE;

CREATE OR REPLACE VIEW visible_users AS
    SELECT u.*
    FROM users u
    WHERE u.deleted_at IS NULL
      AND u.is_shadow_banned = FALSE;
