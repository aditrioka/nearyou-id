package id.nearyou.app.screens.username

import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.profile.ProfileOutcome
import id.nearyou.app.profile.UserProfile

/**
 * Shared `commonTest` fixtures for the Ganti Username VM + screen + iOS-flow tests (commonTest is visible
 * to androidUnitTest + iosTest, so these live here once rather than being redeclared per source set — a
 * private redeclaration in each set clashes in the merged platform test compilation).
 */
class FakeSelfUserIdProvider(private val id: String? = "self-id") : SelfUserIdProvider {
    override suspend fun selfUserId(): String? = id
}

/** A self-profile read fixture driving the on-entry Premium resolution (Premium → editor; Free → gate). */
fun selfProfile(
    username: String = "oldname",
    isPremium: Boolean = true,
): ProfileOutcome =
    ProfileOutcome.Loaded(
        UserProfile(
            userId = "self-id",
            username = username,
            displayName = "Me",
            bio = null,
            followerCount = 0,
            followingCount = 0,
            isSelf = true,
            followedByViewer = false,
            isPremium = isPremium,
            isPrivate = false,
        ),
    )
