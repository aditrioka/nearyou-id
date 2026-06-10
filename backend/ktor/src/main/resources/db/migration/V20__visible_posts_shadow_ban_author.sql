-- V20: visible_posts gains the author-side shadow-ban + soft-delete filters that
-- docs/05-Implementation.md § "Shadow Ban Implementation" has always prescribed
-- (JOIN users; p.deleted_at IS NULL; u.deleted_at IS NULL; u.is_shadow_banned = FALSE)
-- merged with the V4-era is_auto_hidden filter the reports-v9 auto-hide capability
-- depends on. 2026-06-10 holistic audit, finding 02-C1: the shipped view filtered
-- ONLY is_auto_hidden, so a shadow-banned author's posts kept appearing in all three
-- timelines + search even though the shadow_ban_author admin action (PR #160) is live.
--
-- Self-visibility note: the own-content exception (a shadow-banned user sees their
-- own content normally) is carried by the Repository own-content paths that read raw
-- `posts` (docs/05 § Own-content exception) — NOT by this view. Feed surfaces are
-- deliberately viewer-agnostic.
--
-- Also ships posts_author_idx (docs/05 § Posts Schema documented it; no migration
-- ever created it — finding 02-M1). Partial on deleted_at IS NULL: the view now
-- emits that predicate, so the planner can use this index AND the V4 partial cursor
-- indexes for view-backed queries (finding 02-H1).

CREATE OR REPLACE VIEW visible_posts AS
SELECT p.*
FROM posts p
JOIN users u ON u.id = p.author_id
WHERE p.is_auto_hidden = FALSE
  AND p.deleted_at IS NULL
  AND u.deleted_at IS NULL
  AND u.is_shadow_banned = FALSE;

CREATE INDEX IF NOT EXISTS posts_author_idx
    ON posts (author_id, created_at DESC)
    WHERE deleted_at IS NULL;
