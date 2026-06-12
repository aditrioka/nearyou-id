package id.nearyou.app.user

import id.nearyou.app.core.domain.lint.AllowMissingBlockJoin
import id.nearyou.data.repository.UserProfileReader
import id.nearyou.data.repository.UserProfileRow
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

/**
 * Production [UserProfileReader] for `GET /api/v1/users/{user_id}`.
 *
 * Two read paths (design D5):
 *  - **Self** (`targetId == viewerId`): reads raw `users` on the own-content path so a
 *    shadow-banned viewer still sees their own profile (`visible_users` would filter the row and
 *    404 the viewer's own profile). Projects the self-only effective-private `is_private`.
 *    `followed_by_viewer` is forced `false` (you cannot follow yourself), and no `user_blocks`
 *    predicate is applied (you cannot block yourself).
 *  - **Other** (`targetId != viewerId`): reads `visible_users` (shadow-ban + soft-delete safe) and
 *    folds in a bidirectional `user_blocks` exclusion (design D4). The self-only field is not
 *    selected → `null`. An absent row (unknown / soft-deleted / shadow-banned / blocked-either-
 *    direction) returns `null`, which the route maps to one constant `404 user_not_found`.
 *
 * `followerCount` / `followingCount` are RAW totals (design D1) — NOT viewer-block-filtered, unlike
 * the `/followers` `/following` list endpoints. `isPremium` is `subscription_status = 'premium_active'`
 * only; the effective-private premium-set additionally includes `premium_billing_retry` — the two
 * are deliberately different (design D2).
 */
class JdbcUserProfileReader(
    private val dataSource: DataSource,
) : UserProfileReader {
    override fun readProfile(
        viewerId: UUID,
        targetId: UUID,
    ): UserProfileRow? =
        if (targetId == viewerId) {
            readSelfProfile(viewerId)
        } else {
            readOtherProfile(viewerId = viewerId, targetId = targetId)
        }

    /**
     * Self read of raw `users` (own-content path). The raw-`users` allowlist annotation lives on the
     * [SQL_SELF] property where the SQL literal is declared (the `BlockExclusionJoinRule` walks up
     * from the string literal, so it must find the annotation on an enclosing declaration of that
     * literal — annotating this function would not cover the companion-object constant).
     */
    private fun readSelfProfile(viewerId: UUID): UserProfileRow? {
        dataSource.connection.use { conn ->
            conn.prepareStatement(SQL_SELF).use { ps ->
                ps.setObject(1, viewerId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return rs.toRow(isSelf = true)
                }
            }
        }
    }

    private fun readOtherProfile(
        viewerId: UUID,
        targetId: UUID,
    ): UserProfileRow? {
        dataSource.connection.use { conn ->
            conn.prepareStatement(SQL_OTHER).use { ps ->
                // followed_by_viewer EXISTS subquery
                ps.setObject(1, viewerId)
                // target id
                ps.setObject(2, targetId)
                // bidirectional user_blocks exclusion (viewer->target OR target->viewer)
                ps.setObject(3, viewerId)
                ps.setObject(4, targetId)
                ps.setObject(5, targetId)
                ps.setObject(6, viewerId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return rs.toRow(isSelf = false)
                }
            }
        }
    }

    /**
     * Map the current result-set row to a [UserProfileRow]. On the self read the self-only column
     * `is_private` is present and projected; on the other read it is absent and forced to `null`,
     * and `followed_by_viewer` is read from the EXISTS projection.
     */
    private fun ResultSet.toRow(isSelf: Boolean): UserProfileRow =
        UserProfileRow(
            userId = getObject("id", UUID::class.java),
            username = getString("username"),
            displayName = getString("display_name"),
            bio = getString("bio"),
            followerCount = getLong("follower_count"),
            followingCount = getLong("following_count"),
            followedByViewer = if (isSelf) false else getBoolean("followed_by_viewer"),
            isPremium = getBoolean("is_premium"),
            isPrivate = if (isSelf) getBoolean("is_private") else null,
        )

    private companion object {
        /**
         * Self read — raw `users` (own-content path, no shadow-ban filter, no block predicate).
         * Projects the canonical effective-private `is_private` (`docs/05-Implementation.md`
         * § Effective private): the `private_profile_opt_in` term is gated on the premium-status
         * conjunct (a Free user with a stale opt-in is NOT effectively private), OR the 72h
         * privacy-flip grace short-circuit (`privacy_flip_scheduled_at` in the future —
         * forward-looking plumbing; the populating worker is DESIGN-status).
         *
         * Allowlisted from `BlockExclusionJoinRule`: `users` is in the rule's `PROTECTED_TABLE_PATTERN`,
         * but a self read needs no `user_blocks` predicate (you cannot block yourself) and MUST NOT be
         * shadow-ban-filtered (a shadow-banned viewer must still see their own profile). Precedent:
         * `JdbcActorUsernameLookup`.
         */
        @AllowMissingBlockJoin(
            "own-profile-read: self read of raw users — a shadow-banned viewer must see their own " +
                "profile; no block predicate because you cannot block yourself",
        )
        val SQL_SELF =
            """
            SELECT u.id,
                   u.username,
                   u.display_name,
                   u.bio,
                   (SELECT COUNT(*) FROM follows WHERE followee_id = u.id) AS follower_count,
                   (SELECT COUNT(*) FROM follows WHERE follower_id = u.id) AS following_count,
                   (u.subscription_status = 'premium_active') AS is_premium,
                   (
                       (u.private_profile_opt_in AND u.subscription_status IN ('premium_active', 'premium_billing_retry'))
                       OR (u.privacy_flip_scheduled_at IS NOT NULL AND u.privacy_flip_scheduled_at > now())
                   ) AS is_private
              FROM users u
             WHERE u.id = ?
            """.trimIndent()

        /**
         * Other-user read — `visible_users` (shadow-ban + soft-delete safe) with the bidirectional
         * `user_blocks` exclusion folded in (design D4). The directional-fragment form matches
         * `ChatRepository.isBlockedBidirectional`. NOTE this predicate is a CORRECTNESS guardrail,
         * not a lint one — `visible_users` does not match `BlockExclusionJoinRule`'s pattern, so the
         * both-direction 404 tests are the real guardrail.
         */
        val SQL_OTHER =
            """
            SELECT vu.id,
                   vu.username,
                   vu.display_name,
                   vu.bio,
                   (SELECT COUNT(*) FROM follows WHERE followee_id = vu.id) AS follower_count,
                   (SELECT COUNT(*) FROM follows WHERE follower_id = vu.id) AS following_count,
                   EXISTS (SELECT 1 FROM follows WHERE follower_id = ? AND followee_id = vu.id) AS followed_by_viewer,
                   (vu.subscription_status = 'premium_active') AS is_premium
              FROM visible_users vu
             WHERE vu.id = ?
               AND NOT EXISTS (
                   SELECT 1 FROM user_blocks
                    WHERE (blocker_id = ? AND blocked_id = ?)
                       OR (blocker_id = ? AND blocked_id = ?)
               )
            """.trimIndent()
    }
}
