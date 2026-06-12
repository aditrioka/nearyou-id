package id.nearyou.data.repository

import java.sql.Connection
import java.time.Instant
import java.util.UUID

/**
 * Row returned from `/users/{id}/followers` and `/users/{id}/following` listings — the
 * other side of the relationship (who-follows-profile for `/followers`, who-profile-follows
 * for `/following`) plus the `created_at` of the follow edge for cursor paging.
 *
 * Identity fields are the embedded profile summary (`social-list-profile-summaries`),
 * sourced via the shadow-ban-safe `visible_users` view: `username` / `displayName` are
 * NOT NULL since V2; `isPremium` uses the `user-profile-read` formula
 * (`subscription_status = 'premium_active'` only).
 */
data class FollowListRow(
    val userId: UUID,
    val username: String,
    val displayName: String,
    val isPremium: Boolean,
    val createdAt: Instant,
)

interface UserFollowsRepository {
    /**
     * `DELETE FROM follows WHERE follower_id = ? AND followee_id = ?`. Idempotent —
     * returns whether or not a row was removed; no exception on zero rows.
     */
    fun unfollow(
        follower: UUID,
        followee: UUID,
    )

    /**
     * Keyset page of users who follow [profileId], with each row's embedded profile
     * summary. Rows are sourced via INNER JOIN `visible_users` (shadow-banned and
     * soft-deleted followers do NOT appear — `docs/05-Implementation.md` §1272) and
     * filtered bidirectionally against [viewerId]'s `user_blocks` (exclude users the
     * viewer has blocked AND users who have blocked the viewer). Ordered
     * `(created_at DESC, follower_id DESC)`. Returns up to [limit] rows; callers
     * typically pass `pageSize + 1` to detect a next page.
     *
     * Resolves [profileId] through [ensureProfileVisible] first — throws
     * [ProfileUserNotFoundException] when the profile target is unresolvable for the
     * viewer (unknown / soft-deleted / shadow-banned / blocked-either-direction).
     */
    fun listFollowers(
        profileId: UUID,
        viewerId: UUID,
        cursorCreatedAt: Instant?,
        cursorUserId: UUID?,
        limit: Int,
    ): List<FollowListRow>

    /**
     * Keyset page of users [profileId] follows, with embedded profile summaries —
     * same sourcing, filtering, and resolution contract as [listFollowers]. Ordered
     * `(created_at DESC, followee_id DESC)`.
     */
    fun listFollowing(
        profileId: UUID,
        viewerId: UUID,
        cursorCreatedAt: Instant?,
        cursorUserId: UUID?,
        limit: Int,
    ): List<FollowListRow>

    /**
     * Profile-target resolution gate — the `user-profile-read` constant-404 contract:
     *  - Self ([profileId] == [viewerId]): raw-`users` existence (own-content path; a
     *    shadow-banned viewer keeps their own lists).
     *  - Other: the target must exist in `visible_users` AND have no `user_blocks` row
     *    in either direction relative to [viewerId].
     *
     * Throws [ProfileUserNotFoundException] when unresolvable — unknown, soft-deleted,
     * shadow-banned, or blocked-either-direction; HTTP callers map every cause to one
     * CONSTANT byte-identical 404 so the causes stay indistinguishable.
     */
    fun ensureProfileVisible(
        profileId: UUID,
        viewerId: UUID,
    )

    /**
     * Transactional follow-edge INSERT — runs the mutual-block guard + INSERT on the
     * caller-supplied [Connection] so a same-TX notification emit rides the primary
     * action. Semantics:
     *  - Takes the canonical user-pair advisory lock, then re-checks bidirectional
     *    `user_blocks` INSIDE the transaction; on a hit throws [FollowBlockedException]
     *    without writing (the TOCTOU backstop behind the service-level visibility gate —
     *    routes map it to the same constant 404 as an unresolvable target).
     *  - INSERT is `ON CONFLICT (follower_id, followee_id) DO NOTHING` — returns `true`
     *    when a row was inserted (new follow edge), `false` when the conflict absorbed
     *    the INSERT (re-follow is a no-op, so no notification should be emitted).
     *  - On FK violation (SQLState 23503, target user does not exist), throws
     *    [UserNotFoundException].
     *  - Self-follow is NOT checked here (the service layer rejects it before the call);
     *    the V6 CHECK constraint remains defense-in-depth.
     */
    fun followInTx(
        conn: Connection,
        follower: UUID,
        followee: UUID,
    ): Boolean
}

class FollowBlockedException : RuntimeException("follow blocked by user_blocks row in one or both directions")

class UserNotFoundException : RuntimeException("target user does not exist")

class ProfileUserNotFoundException : RuntimeException("profile user does not exist")
