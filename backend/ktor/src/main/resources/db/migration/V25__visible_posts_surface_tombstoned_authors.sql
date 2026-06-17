-- V24: visible_posts surfaces tombstoned (account-deleted) authors' posts
-- (account-deletion-tombstone, design D1; operator decision Q1).
--
-- docs/06 § Account Deletion mandates that a hard-deleted user's posts REMAIN in
-- feeds, anonymized as "Akun Dihapus" (the tombstoned `users` row's NULL
-- display_name + `deleted_user_` handle IS the anonymized identity). The V20 view
-- excluded `u.deleted_at IS NULL`, which would make them vanish — so V24 drops
-- ONLY that author-side predicate.
--
-- PRESERVED byte-identical from V20 (the shadow-ban safety + post-soft-delete
-- contract is unchanged):
--   * p.is_auto_hidden = FALSE   (auto-hide)
--   * p.deleted_at IS NULL       (post soft-delete — keeps the V4 partial cursor
--                                 indexes eligible for view-backed queries)
--   * u.is_shadow_banned = FALSE (shadow-ban — a shadow-banned-THEN-deleted author
--                                 stays hidden; shadow-ban dominates)
-- REMOVED: u.deleted_at IS NULL  (author tombstone no longer hides their posts).
--
-- The `JOIN users` is retained (a tombstoned author's row persists — the tombstone
-- is an UPDATE, not a row-delete), so the join still matches and the nulled
-- identity flows to consumers. `visible_users` is intentionally NOT changed here —
-- profile read stays 404 for a tombstoned target, and search/metrics keep
-- excluding them.

CREATE OR REPLACE VIEW visible_posts AS
SELECT p.*
FROM posts p
JOIN users u ON u.id = p.author_id
WHERE p.is_auto_hidden = FALSE
  AND p.deleted_at IS NULL
  AND u.is_shadow_banned = FALSE;
