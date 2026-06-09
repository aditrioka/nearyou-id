package id.nearyou.data.repository

import java.util.UUID

/**
 * Raw profile row backing `GET /api/v1/users/{user_id}` (the `user-profile-read` capability).
 *
 * The self-only field [isPrivate] is populated ONLY on the own-content (self) read and MUST be
 * `null` for any other-user read — a target's effective private-profile flag is private account
 * state (design D2). [followedByViewer] is always `false` on the self read (you do not follow
 * yourself; design D6).
 *
 * NOTE: there is deliberately NO `suspendedUntil` here. Suspension is a session-terminating state
 * (`docs/02-Product.md` § Suspension sets `is_banned = TRUE` + bumps `token_version`), so a
 * suspended user is rejected at the auth boundary and can never reach this read — the suspension
 * countdown belongs on the auth-rejection response, not on a protected resource (design D2).
 *
 * [followerCount] / [followingCount] are RAW totals from `follows`, deliberately NOT
 * viewer-block-filtered — a follower count is a public aggregate and per-viewer filtering would
 * leak block state via count deltas (design D1, asymmetric with the `/followers` `/following`
 * list endpoints which ARE filtered).
 */
data class UserProfileRow(
    val userId: UUID,
    val username: String,
    val displayName: String,
    val bio: String?,
    val followerCount: Long,
    val followingCount: Long,
    val followedByViewer: Boolean,
    val isPremium: Boolean,
    val isPrivate: Boolean?,
)

/**
 * Read-only projection of a single user's profile for the profile-read endpoint.
 *
 * Implementations MUST:
 *  - resolve a self read (`targetId == viewerId`) through the Repository own-content raw-`users`
 *    path so a shadow-banned viewer still sees their own profile (design D5);
 *  - resolve an other-user read through the `visible_users` view (shadow-ban-safe) AND fold in a
 *    bidirectional `user_blocks` exclusion, returning `null` when the target is unknown,
 *    soft-deleted, shadow-banned, or blocked in either direction (design D4 — the route maps the
 *    single `null` outcome to one constant, direction-less `404 user_not_found`).
 */
interface UserProfileReader {
    /**
     * Returns the [UserProfileRow] for [targetId] as seen by [viewerId], or `null` when the
     * target is unresolvable for any reason (unknown / soft-deleted / shadow-banned /
     * blocked-either-direction). The single `null` outcome is what the route collapses into the
     * leak-safe 404.
     */
    fun readProfile(
        viewerId: UUID,
        targetId: UUID,
    ): UserProfileRow?
}
