package id.nearyou.app.follow

import id.nearyou.app.block.BlockRateLimitedException
import id.nearyou.app.block.BlockRateLimiter
import id.nearyou.app.block.BlockService
import id.nearyou.app.infra.repo.UserBlockRepository
import id.nearyou.app.infra.repo.UserBlockRow
import id.nearyou.app.notifications.NotificationEmitter
import id.nearyou.data.repository.FollowListRow
import id.nearyou.data.repository.NotificationType
import id.nearyou.data.repository.UserFollowsRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Unit coverage for the docs/05 social-graph mutation caps added by the
 * 2026-06-10 holistic audit (finding 03-#3): follow/unfollow 50 per rolling
 * hour, block/unblock 30 per rolling hour — ONE shared bucket per feature, so
 * a follow↔unfollow (or block↔unblock) loop burns quota on both legs. The
 * route-layer 429 + Retry-After mapping mirrors the report-rate-limit pattern
 * already pinned by ReportEndpointsTest; this spec pins the limiter semantics
 * and the service-level enforcement point.
 */
class SocialGraphRateLimitTest : StringSpec({

    "FollowRateLimiter: docs/05 defaults (50 per rolling hour) and slot-stable key shape" {
        val limiter = FollowRateLimiter()
        limiter.cap shouldBe 50
        limiter.window shouldBe Duration.ofHours(1)
        val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
        FollowRateLimiter.keyFor(id) shouldBe "{scope:rate_follow}:{user:$id}"
    }

    "BlockRateLimiter: docs/05 defaults (30 per rolling hour) and slot-stable key shape" {
        val limiter = BlockRateLimiter()
        limiter.cap shouldBe 30
        limiter.window shouldBe Duration.ofHours(1)
        val id = UUID.fromString("00000000-0000-0000-0000-000000000002")
        BlockRateLimiter.keyFor(id) shouldBe "{scope:rate_block}:{user:$id}"
    }

    "follow and unfollow share one bucket: the second leg of a loop is rejected at cap" {
        val follower = UUID.randomUUID()
        val service =
            FollowService(
                dataSource = UnusedDataSource,
                follows = RecordingFollowsRepo(),
                notifications = NoopEmitter,
                dispatcher = { },
                rateLimiter = FollowRateLimiter(cap = 1),
                dbDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            )
        // Leg 1 (unfollow — no DataSource use) consumes the single slot.
        service.unfollow(follower, UUID.randomUUID())
        // Leg 2 (follow) is rejected BEFORE any transaction is opened —
        // UnusedDataSource would throw if the DB were touched.
        val ex = shouldThrow<FollowRateLimitedException> { service.follow(follower, UUID.randomUUID()) }
        (ex.retryAfterSeconds > 0) shouldBe true
    }

    "block and unblock share one bucket: the second leg of a loop is rejected at cap" {
        val blocker = UUID.randomUUID()
        val repo = RecordingBlockRepo()
        val service =
            BlockService(
                blocks = repo,
                rateLimiter = BlockRateLimiter(cap = 1),
                dbDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            )
        service.block(blocker, UUID.randomUUID())
        repo.created shouldBe 1
        val ex = shouldThrow<BlockRateLimitedException> { service.unblock(blocker, UUID.randomUUID()) }
        (ex.retryAfterSeconds > 0) shouldBe true
        repo.deleted shouldBe 0
    }

    "rate-limited follow never reaches the limiter-protected transaction" {
        val service =
            FollowService(
                dataSource = UnusedDataSource,
                follows = RecordingFollowsRepo(),
                notifications = NoopEmitter,
                dispatcher = { },
                rateLimiter = FollowRateLimiter(cap = 0),
                dbDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            )
        shouldThrow<FollowRateLimitedException> { service.follow(UUID.randomUUID(), UUID.randomUUID()) }
    }

    "429 takes precedence over the visibility 404 (gate runs AFTER checkRateLimit — design D5)" {
        // The fake gate would 404 every target; at cap=0 the limiter must still win,
        // pinning the FollowService ordering (self-check → limiter → gate → tx) so
        // 404-probes keep burning the 50/h bucket.
        val wouldAlways404 =
            object : UserFollowsRepository by RecordingFollowsRepo() {
                override fun ensureProfileVisible(
                    profileId: UUID,
                    viewerId: UUID,
                ): Unit = throw id.nearyou.data.repository.ProfileUserNotFoundException()
            }
        val service =
            FollowService(
                dataSource = UnusedDataSource,
                follows = wouldAlways404,
                notifications = NoopEmitter,
                dispatcher = { },
                rateLimiter = FollowRateLimiter(cap = 0),
                dbDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            )
        shouldThrow<FollowRateLimitedException> { service.follow(UUID.randomUUID(), UUID.randomUUID()) }
    }
})

private class RecordingFollowsRepo : UserFollowsRepository {
    var unfollows = 0

    override fun unfollow(
        follower: UUID,
        followee: UUID,
    ) {
        unfollows++
    }

    override fun listFollowers(
        profileId: UUID,
        viewerId: UUID,
        cursorCreatedAt: Instant?,
        cursorUserId: UUID?,
        limit: Int,
    ): List<FollowListRow> = emptyList()

    override fun listFollowing(
        profileId: UUID,
        viewerId: UUID,
        cursorCreatedAt: Instant?,
        cursorUserId: UUID?,
        limit: Int,
    ): List<FollowListRow> = emptyList()

    override fun ensureProfileVisible(
        profileId: UUID,
        viewerId: UUID,
    ): Unit = error("visibility gate must not run on the rate-limited path (429 precedes 404)")

    override fun followInTx(
        conn: Connection,
        follower: UUID,
        followee: UUID,
    ): Boolean = error("transaction path must not run in these tests")
}

private class RecordingBlockRepo : UserBlockRepository {
    var created = 0
    var deleted = 0

    override fun create(
        blockerId: UUID,
        blockedId: UUID,
    ): Boolean {
        created++
        return true
    }

    override fun delete(
        blockerId: UUID,
        blockedId: UUID,
    ): Int {
        deleted++
        return 1
    }

    override fun listOutbound(
        blockerId: UUID,
        cursorCreatedAt: Instant?,
        cursorBlockedId: UUID?,
        limit: Int,
    ): List<UserBlockRow> = emptyList()
}

private object NoopEmitter : NotificationEmitter {
    override fun emit(
        conn: Connection,
        recipientId: UUID,
        actorUserId: UUID?,
        type: NotificationType,
        targetType: String?,
        targetId: UUID?,
        bodyData: JsonObject,
    ): UUID? = null
}

/** The rate-limited paths must reject BEFORE any connection is opened. */
private object UnusedDataSource : DataSource {
    override fun getConnection(): Connection = error("DB must not be touched on the rate-limited path")

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = error("DB must not be touched on the rate-limited path")

    override fun getLogWriter(): java.io.PrintWriter = error("not used")

    override fun setLogWriter(out: java.io.PrintWriter?) = error("not used")

    override fun setLoginTimeout(seconds: Int) = error("not used")

    override fun getLoginTimeout(): Int = error("not used")

    override fun getParentLogger(): java.util.logging.Logger = error("not used")

    override fun <T : Any?> unwrap(iface: Class<T>?): T = error("not used")

    override fun isWrapperFor(iface: Class<*>?): Boolean = error("not used")
}
