package id.nearyou.app.user

import id.nearyou.data.repository.ProfileUserNotFoundException
import id.nearyou.data.repository.UserProfileReader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Maps the [UserProfileReader] row to the wire [UserProfileResponse] for
 * `GET /api/v1/users/{user_id}`.
 *
 * An unresolvable target (the reader returns `null` for unknown / soft-deleted / shadow-banned /
 * blocked-either-direction) is mapped to [ProfileUserNotFoundException], which the route turns into
 * the single constant `404 user_not_found` (the leak-safe collapse — see [userProfileRoutes]).
 *
 * `isSelf` is computed here (`targetId == viewerId`); the reader carries the self-only field
 * (`isPrivate`) already nulled on the other-user path.
 */
class UserProfileService(
    private val reader: UserProfileReader,
    // Pool-bounded JDBC dispatcher (docs/11 §3.2); production passes DbDispatchers.db.
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun getProfile(
        viewerId: UUID,
        targetId: UUID,
    ): UserProfileResponse {
        val row =
            withContext(dbDispatcher) { reader.readProfile(viewerId = viewerId, targetId = targetId) }
                ?: throw ProfileUserNotFoundException()
        return UserProfileResponse(
            userId = row.userId.toString(),
            username = row.username,
            displayName = row.displayName,
            bio = row.bio,
            followerCount = row.followerCount,
            followingCount = row.followingCount,
            isSelf = targetId == viewerId,
            followedByViewer = row.followedByViewer,
            isPremium = row.isPremium,
            isPrivate = row.isPrivate,
        )
    }
}
