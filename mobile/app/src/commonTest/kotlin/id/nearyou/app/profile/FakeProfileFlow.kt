package id.nearyou.app.profile

import id.nearyou.app.data.block.BlockOutcome
import id.nearyou.app.data.report.ReportOutcome
import id.nearyou.app.data.report.ReportReasonCategory
import kotlinx.coroutines.CompletableDeferred

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
    var loadCalls = 0
    var blockCalls = 0
    var reportCalls = 0

    /** When set, [loadProfile] suspends until it completes — lets a test land the read AFTER other work. */
    var loadGate: CompletableDeferred<Unit>? = null

    /** When set, [follow] / [block] / [report] suspend until it completes — lets a test move the state
     *  mid-request. */
    var actionGate: CompletableDeferred<Unit>? = null

    override suspend fun loadProfile(userId: String): ProfileOutcome {
        loadCalls++
        loadedUserId = userId
        loadGate?.await()
        return profileOutcome
    }

    override suspend fun follow(userId: String): FollowToggleOutcome {
        followedUserId = userId
        actionGate?.await()
        return followOutcome
    }

    override suspend fun unfollow(userId: String): FollowToggleOutcome {
        unfollowedUserId = userId
        return unfollowOutcome
    }

    override suspend fun block(userId: String): BlockOutcome {
        blockCalls++
        blockedUserId = userId
        actionGate?.await()
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
        reportCalls++
        actionGate?.await()
        return reportOutcome
    }

    companion object {
        fun sampleProfile(
            userId: String = "u1",
            isSelf: Boolean = false,
            followedByViewer: Boolean = false,
            isPremium: Boolean = false,
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
                isPremium = isPremium,
                isPrivate = if (isSelf) false else null,
            )
    }
}
