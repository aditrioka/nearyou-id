-- Notifications: DB-persisted per-user in-app notification feed. Canonical DDL
-- verbatim from docs/05-Implementation.md §820–844 — no renames.
--
-- Dependency shape (sixth distinct shape in the migration pipeline):
--   * Two outgoing FK edges on V3 `users`:
--       - `user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE` —
--         the recipient's feed is PII about them; a hard-delete wipes the feed
--         alongside the tombstone worker's treatment of per-user ephemeral data.
--       - `actor_user_id UUID REFERENCES users(id) ON DELETE SET NULL` — actor
--         churn preserves the recipient's historical feed with the actor nulled
--         out ("a deleted user liked your post 3 weeks ago" remains a valid UX).
--         Distinct from CASCADE to match docs/05-Implementation.md:833.
--   * First migration to introduce a partial-index on a `read_at IS NULL`
--     predicate. The predicate is IMMUTABLE (distinct from `> NOW()` which the
--     partial-index lint rule flags), so this is valid.
--
-- V10-era writers:
--   * LikeService (V7)        → post_liked
--   * ReplyService (V8)       → post_replied
--   * FollowService (V6)      → followed
--   * ReportService (V9)      → post_auto_hidden (resolves the V9 auto-hide TODO)
--
-- Reserved-for-future enum values (no V10 writers — each ships with its feature):
--   * chat_message, chat_message_redacted (chat feature)
--   * subscription_purchased, subscription_expiring, subscription_expired
--     (subscription / billing feature)
--   * account_action_applied (admin moderation action)
--   * data_export_ready (privacy / data-export worker)
--   * privacy_flip_warning (username / profile privacy toggle)
--   * username_release_scheduled (username release worker)
--   * apple_relay_email_changed (Apple S2S email-relay change notification)
--
-- Full-enum-at-V10 convention continues V9's `moderation_queue.trigger` 7-value
-- precedent — ships the full vocabulary up-front to avoid a chain of
-- enum-widening micro-migrations as feature writers land.
--
-- Reference: docs/05-Implementation.md §820–844 is the canonical source for every
-- column, CHECK enum value, and index declared below.

CREATE TABLE notifications (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type           VARCHAR(48) NOT NULL CHECK (type IN (
        'post_liked',
        'post_replied',
        'followed',
        'post_auto_hidden',
        'chat_message',
        'chat_message_redacted',
        'subscription_purchased',
        'subscription_expiring',
        'subscription_expired',
        'account_action_applied',
        'data_export_ready',
        'privacy_flip_warning',
        'username_release_scheduled',
        'apple_relay_email_changed'
    )),
    actor_user_id  UUID REFERENCES users(id) ON DELETE SET NULL,
    target_type    VARCHAR(32),
    target_id      UUID,
    body_data      JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    read_at        TIMESTAMPTZ
);

-- Partial index: the hot "unread badge count" path. Predicate `read_at IS NULL`
-- is immutable (no `NOW()`-style non-immutability — Postgres accepts it), so it
-- sidesteps the partial-index pitfall documented at docs/08-Roadmap-Risk.md:486.
-- Typical selectivity is 1–10% of rows for an engaged user, making unread-count
-- queries an index-only scan with no heap fetches.
CREATE INDEX notifications_user_unread_idx
    ON notifications (user_id, created_at DESC)
    WHERE read_at IS NULL;

-- Full index: the paginated list path (`GET /api/v1/notifications` default).
CREATE INDEX notifications_user_all_idx
    ON notifications (user_id, created_at DESC);
