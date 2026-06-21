package id.nearyou.app.profile

import id.nearyou.app.data.report.ReportOutcome
import id.nearyou.app.data.report.ReportReasonCategory

/**
 * commonTest double for [ProfileFlow] — drives the `ProfileViewModel` + screen tests with configurable
 * per-operation outcomes and records the last call arguments (mirroring `FakePostDetailFlow`).
 */
class FakeProfileFlow(
    var profileOutcome: ProfileOutcome = ProfileOutcome.Loaded(sampleProfile()),
    var followOutcome: FollowToggleOutcome = FollowToggleOutcome.Followed,
    var unfollowOutcome: FollowToggleOutcome = FollowToggleOutcome.Unfollowed,
    var blockOutcome: BlockOutcome = BlockOutcome.Blocked,
    var reportOutcome: ReportOutcome = ReportOutcome.Submitted,
) : ProfileFlow {
    var loadedUserId: String? = null
    var followedUserId: String? = null
    var unfollowedUserId: String? = null
    var blockedUserId: String? = null
    var reportedUserId: String? = null
    var lastReportCategory: ReportReasonCategory? = null
    var lastReportNote: String? = null

    override suspend fun loadProfile(userId: String): ProfileOutcome {
        loadedUserId = userId
        return profileOutcome
    }

    override suspend fun follow(userId: String): FollowToggleOutcome {
        followedUserId = userId
        return followOutcome
    }

    override suspend fun unfollow(userId: String): FollowToggleOutcome {
        unfollowedUserId = userId
        return unfollowOutcome
    }

    override suspend fun block(userId: String): BlockOutcome {
        blockedUserId = userId
        return blockOutcome
    }

    override suspend fun report(
        userId: String,
        reasonCategory: ReportReasonCategory,
        note: String?,
    ): ReportOutcome {
        reportedUserId = userId
        lastReportCategory = reasonCategory
        lastReportNote = note
        return reportOutcome
    }

    companion object {
        fun sampleProfile(
            userId: String = "u1",
            isSelf: Boolean = false,
            followedByViewer: Boolean = false,
        ): UserProfile =
            UserProfile(
                userId = userId,
                username = "raka.jkt",
                displayName = "Raka Pratama",
                bio = "halo",
                followerCount = 3,
                followingCount = 5,
                isSelf = isSelf,
                followedByViewer = followedByViewer,
                isPremium = false,
                isPrivate = if (isSelf) false else null,
            )
    }
}
