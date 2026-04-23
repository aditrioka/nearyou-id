package id.nearyou.data.repository

import java.sql.Connection
import java.time.Instant
import java.util.UUID

/**
 * Row returned from `/users/{id}/followers` and `/users/{id}/following` listings — the
 * other side of the relationship (who-follows-profile for `/followers`, who-profile-follows
 * for `/following`) plus the `created_at` of the follow edge for cursor paging.
 */
data class FollowListRow(
    val userId: UUID,
    val createdAt: Instant,
)

interface UserFollowsRepository {
    /**
     * Create a follow edge `(follower, followee)`. Semantics:
     *  - Performs the mutual-block rejection read (`SELECT 1 FROM user_blocks WHERE
     *    (blocker, blocked) matches either direction`) BEFORE the INSERT; if a row is
     *    found, throws [FollowBlockedException] without writing.
     *  - INSERT is `ON CONFLICT (follower_id, followee_id) DO NOTHING` — re-follow is
     *    idempotent (no exception).
     *  - On FK violation (SQLState 23503, target user does not exist), throws
     *    [UserNotFoundException].
     *  - Self-follow is NOT checked here (the service layer rejects it before the call);
     *    the V6 CHECK constraint remains defense-in-depth.
     */
    fun follow(
        follower: UUID,
        followee: UUID,
    )

    /**
     * `DELETE FROM follows WHERE follower_id = ? AND followee_id = ?`. Idempotent —
     * returns whether or not a row was removed; no exception on zero rows.
     */
    fun unfollow(
        follower: UUID,
        followee: UUID,
    )

    /**
     * Keyset page of users who follow [profileId], filtered bidirectionally against
     * [viewerId]'s `user_blocks` (exclude users the viewer has blocked AND users who have
     * blocked the viewer). Ordered `(created_at DESC, follower_id DESC)`. Returns up to
     * [limit] rows; callers typically pass `pageSize + 1` to detect a next page.
     *
     * Throws [ProfileUserNotFoundException] if [profileId] does not exist in `users`.
     */
    fun listFollowers(
        profileId: UUID,
        viewerId: UUID,
        cursorCreatedAt: Instant?,
        cursorUserId: UUID?,
        limit: Int,
    ): List<FollowListRow>

    /**
     * Keyset page of users [profileId] follows, filtered bidirectionally against
     * [viewerId]'s `user_blocks`. Ordered `(created_at DESC, followee_id DESC)`.
     *
     * Throws [ProfileUserNotFoundException] if [profileId] does not exist in `users`.
     */
    fun listFollowing(
        profileId: UUID,
        viewerId: UUID,
        cursorCreatedAt: Instant?,
        cursorUserId: UUID?,
        limit: Int,
    ): List<FollowListRow>

    /**
     * Transactional variant of [follow] — runs block-check + INSERT on the
     * caller-supplied [Connection] so a same-TX notification emit rides the
     * primary action. Returns `true` when a row was inserted (new follow edge),
     * `false` when `ON CONFLICT DO NOTHING` absorbed the INSERT (re-follow is
     * a no-op, so no notification should be emitted).
     *
     * Same exception semantics as [follow]: [FollowBlockedException] for a
     * mutual-block row, [UserNotFoundException] on FK violation.
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
