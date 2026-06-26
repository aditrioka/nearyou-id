-- V36: moderation_queue.trigger enum — add `area_spam`.
--
-- Backs the `post-area-density-cap` change (docs/05-Implementation.md § Anti-Spam
-- Layer 4 "Per Area (anti local spam)"). The per-area density gate flags the next
-- post created in an over-dense ~1.1 km cell (50 posts / rolling 1 h) to manual
-- review by writing a `moderation_queue` row with `trigger = 'area_spam'` — a soft
-- flag, NOT a hard reject (the post is still created with `is_auto_hidden = FALSE`).
--
-- The V9 `moderation_queue.trigger` CHECK reserved 7 forward-compat values; none
-- denotes per-area spam, so the gate needs its own value to keep admin triage
-- filterable. `area_spam` is DISTINCT from the reserved `anomaly_detection` value
-- (earmarked for the separate per-user 30-day-baseline mechanism, Phase 4 #17);
-- the two are deliberately NOT conflated.
--
-- The V9 enum is an INLINE column CHECK, auto-named `moderation_queue_trigger_check`
-- by Postgres. This migration drops + re-adds that constraint with the 8-value set.
-- Additive: no data rewrite, existing rows untouched, all 7 previously-valid values
-- continue to pass (V9 forward-compat scenarios stay valid). Forward-only — a
-- rollback would be a later migration reverting the constraint (safe pre-app-deploy
-- since no `area_spam` rows can exist until the gate code ships).
ALTER TABLE moderation_queue
    DROP CONSTRAINT moderation_queue_trigger_check,
    ADD CONSTRAINT moderation_queue_trigger_check CHECK (trigger IN (
        'auto_hide_3_reports',
        'perspective_api_high_score',
        'uu_ite_keyword_match',
        'admin_flag',
        'csam_detected',
        'anomaly_detection',
        'username_flagged',
        'area_spam'
    ));
