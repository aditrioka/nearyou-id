-- V40: notifications.type enum — add `appeal_decided`.
--
-- Backs the `appeal-decision-notification` change (follow-up #390). When an admin
-- approves or rejects an appeal (`admin-appeal-review`), the decision transaction
-- writes one in-app `notifications` row for the appellant with
-- `type = 'appeal_decided'`, `target_type = 'appeal'`, `target_id = <appeal id>`,
-- `body_data = {"decision": "approved" | "rejected"}`. In-app feed only — no FCM
-- push (operator decision 2026-10-04).
--
-- The V10 enum is an INLINE column CHECK, auto-named `notifications_type_check`
-- by Postgres. This migration drops + re-adds that constraint with the 14-value
-- set in ONE atomic ALTER (the constraint is never absent to a concurrent
-- session) — the V36 `moderation_queue_trigger_check` precedent. Additive: no
-- data rewrite, existing rows untouched, all 13 previously-valid values continue
-- to pass. Re-runnable as-is (the re-add restores the same name).
--
-- Deliberately a plain DROP, not `DROP CONSTRAINT IF EXISTS`: if the constraint
-- were ever named differently on some database, IF EXISTS would silently keep
-- the old 13-value CHECK and every appeal decision would roll back on 23514 at
-- runtime. A plain DROP fails this migration loudly instead.
ALTER TABLE notifications
    DROP CONSTRAINT notifications_type_check,
    ADD CONSTRAINT notifications_type_check CHECK (type IN (
        'post_liked',
        'post_replied',
        'followed',
        'chat_message',
        'subscription_billing_issue',
        'subscription_expired',
        'post_auto_hidden',
        'account_action_applied',
        'data_export_ready',
        'chat_message_redacted',
        'privacy_flip_warning',
        'username_release_scheduled',
        'apple_relay_email_changed',
        'appeal_decided'
    ));
