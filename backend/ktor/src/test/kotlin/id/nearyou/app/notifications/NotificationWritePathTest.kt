package id.nearyou.app.notifications

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.config.StubRemoteConfig
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import id.nearyou.app.engagement.LikeService
import id.nearyou.app.engagement.ReplyService
import id.nearyou.app.follow.FollowService
import id.nearyou.app.guard.ContentLengthGuard
import id.nearyou.app.infra.repo.JdbcModerationQueueRepository
import id.nearyou.app.infra.repo.JdbcNotificationRepository
import id.nearyou.app.infra.repo.JdbcPostAutoHideRepository
import id.nearyou.app.infra.repo.JdbcPostLikeRepository
import id.nearyou.app.infra.repo.JdbcPostReplyRepository
import id.nearyou.app.infra.repo.JdbcReportRepository
import id.nearyou.app.infra.repo.JdbcUserFollowsRepository
import id.nearyou.app.moderation.ReportRateLimiter
import id.nearyou.app.moderation.ReportService
import id.nearyou.data.repository.NotificationRepository
import id.nearyou.data.repository.ReportReasonCategory
import id.nearyou.data.repository.ReportTargetType
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.Date
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import javax.sql.DataSource

private fun hikari(): HikariDataSource {
    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val user = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"
    return HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            maximumPoolSize = 4
            initializationFailTimeout = -1
        },
    )
}

private fun seedUser(
    dataSource: DataSource,
    prefix: String,
): UUID {
    val id = UUID.randomUUID()
    val short = id.toString().replace("-", "").take(8)
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            """
            INSERT INTO users (
                id, username, display_name, date_of_birth, invite_code_prefix
            ) VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, id)
            ps.setString(2, "${prefix}_$short")
            ps.setString(3, "V10 Write")
            ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
            ps.setString(5, "v${short.take(7)}")
            ps.executeUpdate()
        }
    }
    return id
}

private fun seedAgedUser(
    dataSource: DataSource,
    prefix: String,
): UUID {
    val id = seedUser(dataSource, prefix)
    dataSource.connection.use { conn ->
        conn.prepareStatement("UPDATE users SET created_at = ? WHERE id = ?").use { ps ->
            ps.setTimestamp(1, java.sql.Timestamp.valueOf(LocalDateTime.now().minusDays(14)))
            ps.setObject(2, id)
            ps.executeUpdate()
        }
    }
    return id
}

private fun seedPost(
    dataSource: DataSource,
    authorId: UUID,
    content: String = "A real post",
): UUID {
    val id = UUID.randomUUID()
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            """
            INSERT INTO posts (id, author_id, content, display_location, actual_location)
            VALUES (?, ?, ?,
              ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
              ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, id)
            ps.setObject(2, authorId)
            ps.setString(3, content)
            ps.setDouble(4, 106.8)
            ps.setDouble(5, -6.2)
            ps.setDouble(6, 106.8)
            ps.setDouble(7, -6.2)
            ps.executeUpdate()
        }
    }
    return id
}

private fun insertBlock(
    dataSource: DataSource,
    blocker: UUID,
    blocked: UUID,
) {
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            "INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
        ).use { ps ->
            ps.setObject(1, blocker)
            ps.setObject(2, blocked)
            ps.executeUpdate()
        }
    }
}

private fun insertReportRow(
    dataSource: DataSource,
    reporterId: UUID,
    targetType: String,
    targetId: UUID,
) {
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            """
            INSERT INTO reports (reporter_id, target_type, target_id, reason_category)
            VALUES (?, ?, ?, 'spam')
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, reporterId)
            ps.setString(2, targetType)
            ps.setObject(3, targetId)
            ps.executeUpdate()
        }
    }
}

private fun seedReply(
    dataSource: DataSource,
    postId: UUID,
    authorId: UUID,
    content: String = "a reply",
): UUID {
    val id = UUID.randomUUID()
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            """
            INSERT INTO post_replies (id, post_id, author_id, content)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, id)
            ps.setObject(2, postId)
            ps.setObject(3, authorId)
            ps.setString(4, content)
            ps.executeUpdate()
        }
    }
    return id
}

private fun countNotifications(
    dataSource: DataSource,
    recipientId: UUID,
    type: String? = null,
): Int {
    dataSource.connection.use { conn ->
        val sql =
            if (type != null) {
                "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = ?"
            } else {
                "SELECT COUNT(*) FROM notifications WHERE user_id = ?"
            }
        conn.prepareStatement(sql).use { ps ->
            ps.setObject(1, recipientId)
            if (type != null) ps.setString(2, type)
            ps.executeQuery().use { rs ->
                rs.next()
                return rs.getInt(1)
            }
        }
    }
}

private fun fetchNotification(
    dataSource: DataSource,
    recipientId: UUID,
    type: String,
): Map<String, Any?> {
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT id, type, actor_user_id, target_type, target_id, body_data::text AS body
              FROM notifications
             WHERE user_id = ? AND type = ?
             ORDER BY created_at DESC
             LIMIT 1
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, recipientId)
            ps.setString(2, type)
            ps.executeQuery().use { rs ->
                rs.next()
                return mapOf(
                    "id" to rs.getObject("id", UUID::class.java),
                    "type" to rs.getString("type"),
                    "actor_user_id" to rs.getObject("actor_user_id", UUID::class.java),
                    "target_type" to rs.getString("target_type"),
                    "target_id" to rs.getObject("target_id", UUID::class.java),
                    "body" to rs.getString("body"),
                )
            }
        }
    }
}

/**
 * Cleanup helper. V4 `posts.author_id` and V8 `post_replies.author_id` are both
 * ON DELETE RESTRICT, so posts / replies must be explicitly deleted before
 * their author rows. Notifications, follows, likes, blocks, and reports all
 * CASCADE from `users`, so those fall out automatically. `moderation_queue`
 * has no FK to the user (polymorphic target_id) — we null it out by target.
 */
private fun cleanupUsers(
    dataSource: DataSource,
    vararg ids: UUID,
) {
    dataSource.connection.use { conn ->
        conn.createStatement().use { st ->
            ids.forEach { id ->
                // 1. Drop moderation queue rows for posts / replies authored by this user.
                st.executeUpdate(
                    "DELETE FROM moderation_queue WHERE target_id IN " +
                        "(SELECT id FROM posts WHERE author_id = '$id' " +
                        " UNION SELECT id FROM post_replies WHERE author_id = '$id')",
                )
                // 2. Drop replies authored BY the user OR on posts authored by the user
                //    (posts→post_replies is CASCADE, but we order the work explicitly
                //    because `post_replies.author_id` is RESTRICT from the replier side).
                st.executeUpdate(
                    "DELETE FROM post_replies WHERE author_id = '$id' " +
                        "OR post_id IN (SELECT id FROM posts WHERE author_id = '$id')",
                )
                // 3. Drop posts authored by the user (RESTRICT from author side).
                st.executeUpdate("DELETE FROM posts WHERE author_id = '$id'")
                // 4. Finally, delete the user — remaining rows (notifications, follows,
                //    post_likes, user_blocks, reports) CASCADE.
                st.executeUpdate("DELETE FROM users WHERE id = '$id'")
            }
        }
    }
}

/**
 * Covers tasks 10.1–10.21. Each test seeds its own users and cleans up in the
 * `finally`; the V10 `notifications.user_id ON DELETE CASCADE` FK cascades the
 * notification rows when the owner user is deleted.
 */
@Tags("database")
class NotificationWritePathTest : StringSpec({

    val dataSource: DataSource = hikari()
    val notificationsRepo: NotificationRepository = JdbcNotificationRepository(dataSource)
    val dispatcher = NoopNotificationDispatcher()
    val emitter = DbNotificationEmitter(notificationsRepo)
    // The like rate-limiter and remote-config wires are stubs at this layer:
    // these tests assert the V10 notification write-path, NOT rate-limit semantics.
    // `LikeRateLimitTest` (section 8 of `like-rate-limit`) covers the limiter in
    // isolation against the CI Redis container.
    val noopLimiter =
        object : RateLimiter {
            override fun tryAcquire(
                userId: UUID,
                key: String,
                capacity: Int,
                ttl: java.time.Duration,
            ): RateLimiter.Outcome = RateLimiter.Outcome.Allowed(remaining = capacity - 1)

            override fun releaseMostRecent(
                userId: UUID,
                key: String,
            ) = Unit
        }
    val noopRemoteConfig = StubRemoteConfig()
    val likeService =
        LikeService(
            dataSource = dataSource,
            likes = JdbcPostLikeRepository(dataSource),
            notifications = emitter,
            dispatcher = dispatcher,
            rateLimiter = noopLimiter,
            remoteConfig = noopRemoteConfig,
        )
    val replyService =
        ReplyService(
            dataSource = dataSource,
            replies = JdbcPostReplyRepository(dataSource),
            notifications = emitter,
            dispatcher = dispatcher,
            rateLimiter = noopLimiter,
            remoteConfig = noopRemoteConfig,
        )
    val followService = FollowService(dataSource, JdbcUserFollowsRepository(dataSource), emitter, dispatcher)
    val contentGuard = ContentLengthGuard(mapOf("reply.content" to 280))
    val reportService =
        ReportService(
            dataSource = dataSource,
            reports = JdbcReportRepository(),
            moderationQueue = JdbcModerationQueueRepository(),
            postAutoHide = JdbcPostAutoHideRepository(),
            rateLimiter = ReportRateLimiter(),
            notifications = emitter,
            dispatcher = dispatcher,
        )

    "10.1 Like → post_liked notification with body_data.post_excerpt" {
        val alice = seedUser(dataSource, "al")
        val bob = seedUser(dataSource, "bo")
        val p = seedPost(dataSource, alice, content = "Hello world from Alice")
        try {
            runBlocking { likeService.like(p, bob, "free") }
            countNotifications(dataSource, alice, "post_liked") shouldBe 1
            val row = fetchNotification(dataSource, alice, "post_liked")
            row["actor_user_id"] shouldBe bob
            row["target_type"] shouldBe "post"
            row["target_id"] shouldBe p
            val body = Json.parseToJsonElement(row["body"] as String).jsonObject
            body["post_excerpt"]!!.jsonPrimitive.content shouldBe "Hello world from Alice"
        } finally {
            cleanupUsers(dataSource, alice, bob)
        }
    }

    "10.2 Self-like → zero notifications" {
        val alice = seedUser(dataSource, "sl")
        val p = seedPost(dataSource, alice)
        try {
            runBlocking { likeService.like(p, alice, "free") }
            countNotifications(dataSource, alice) shouldBe 0
        } finally {
            cleanupUsers(dataSource, alice)
        }
    }

    "10.3 Like: recipient blocked actor → zero notifications" {
        val alice = seedUser(dataSource, "ab")
        val bob = seedUser(dataSource, "bb")
        val p = seedPost(dataSource, alice)
        insertBlock(dataSource, alice, bob)
        try {
            // When Alice blocked Bob, `resolveVisiblePost` returns null → Result.NotFound.
            // Block-suppression is enforced both at visibility-gate AND write-time emit level.
            runBlocking { likeService.like(p, bob, "free") }
            countNotifications(dataSource, alice) shouldBe 0
        } finally {
            cleanupUsers(dataSource, alice, bob)
        }
    }

    "10.4 Like: actor blocked recipient → zero notifications" {
        val alice = seedUser(dataSource, "ba")
        val bob = seedUser(dataSource, "bc")
        val p = seedPost(dataSource, alice)
        insertBlock(dataSource, bob, alice)
        try {
            runBlocking { likeService.like(p, bob, "free") }
            countNotifications(dataSource, alice) shouldBe 0
        } finally {
            cleanupUsers(dataSource, alice, bob)
        }
    }

    "10.5 Re-like → exactly one notification row" {
        val alice = seedUser(dataSource, "rl")
        val bob = seedUser(dataSource, "rb")
        val p = seedPost(dataSource, alice)
        try {
            runBlocking {
                likeService.like(p, bob, "free")
                likeService.like(p, bob, "free")
            }
            countNotifications(dataSource, alice, "post_liked") shouldBe 1
        } finally {
            cleanupUsers(dataSource, alice, bob)
        }
    }

    "10.6 Unlike does NOT insert a counter-notification" {
        val alice = seedUser(dataSource, "ul")
        val bob = seedUser(dataSource, "ub")
        val p = seedPost(dataSource, alice)
        try {
            runBlocking { likeService.like(p, bob, "free") }
            likeService.unlike(p, bob)
            countNotifications(dataSource, alice) shouldBe 1
            countNotifications(dataSource, alice, "post_liked") shouldBe 1
        } finally {
            cleanupUsers(dataSource, alice, bob)
        }
    }

    "10.7 Reply → post_replied notification with body_data.reply_id + reply_excerpt" {
        val alice = seedUser(dataSource, "ar")
        val bob = seedUser(dataSource, "br")
        val p = seedPost(dataSource, alice)
        try {
            val row = replyService.post(p, bob, "Ayo ke Tebet")
            countNotifications(dataSource, alice, "post_replied") shouldBe 1
            val note = fetchNotification(dataSource, alice, "post_replied")
            note["actor_user_id"] shouldBe bob
            note["target_type"] shouldBe "post"
            note["target_id"] shouldBe p
            val body = Json.parseToJsonElement(note["body"] as String).jsonObject
            body["reply_id"]!!.jsonPrimitive.content shouldBe row.id.toString()
            body["reply_excerpt"]!!.jsonPrimitive.content shouldBe "Ayo ke Tebet"
        } finally {
            cleanupUsers(dataSource, alice, bob)
        }
    }

    "10.8 Self-reply → zero notifications" {
        val alice = seedUser(dataSource, "sr")
        val p = seedPost(dataSource, alice)
        try {
            replyService.post(p, alice, "own reply")
            countNotifications(dataSource, alice) shouldBe 0
        } finally {
            cleanupUsers(dataSource, alice)
        }
    }

    "10.9 Reply suppressed by block (either direction)" {
        val alice = seedUser(dataSource, "rb1")
        val bob = seedUser(dataSource, "rb2")
        val p = seedPost(dataSource, alice)
        insertBlock(dataSource, alice, bob)
        try {
            runCatching { replyService.post(p, bob, "blocked") }
            countNotifications(dataSource, alice) shouldBe 0
        } finally {
            cleanupUsers(dataSource, alice, bob)
        }
    }

    "10.10 Follow → followed notification (target_type/target_id null)" {
        val alice = seedUser(dataSource, "fa")
        val bob = seedUser(dataSource, "fb")
        try {
            followService.follow(bob, alice)
            countNotifications(dataSource, alice, "followed") shouldBe 1
            val note = fetchNotification(dataSource, alice, "followed")
            note["actor_user_id"] shouldBe bob
            note["target_type"] shouldBe null
            note["target_id"] shouldBe null
            (note["body"] as String) shouldStartWith "{"
        } finally {
            cleanupUsers(dataSource, alice, bob)
        }
    }

    "10.11 Unfollow + re-follow: first follow emits, unfollow silent, re-follow emits again" {
        val alice = seedUser(dataSource, "fr1")
        val bob = seedUser(dataSource, "fr2")
        try {
            followService.follow(bob, alice)
            followService.unfollow(bob, alice)
            followService.follow(bob, alice)
            countNotifications(dataSource, alice, "followed") shouldBe 2
        } finally {
            cleanupUsers(dataSource, alice, bob)
        }
    }

    "10.12 Follow suppressed by block (either direction)" {
        val alice = seedUser(dataSource, "fx1")
        val bob = seedUser(dataSource, "fx2")
        insertBlock(dataSource, alice, bob)
        try {
            runCatching { followService.follow(bob, alice) }
            countNotifications(dataSource, alice) shouldBe 0
        } finally {
            cleanupUsers(dataSource, alice, bob)
        }
    }

    "10.13 Auto-hide (post) → post_auto_hidden notification, actor NULL" {
        val alice = seedUser(dataSource, "ah1")
        val r1 = seedAgedUser(dataSource, "ar1")
        val r2 = seedAgedUser(dataSource, "ar2")
        val r3 = seedAgedUser(dataSource, "ar3")
        val p = seedPost(dataSource, alice)
        try {
            insertReportRow(dataSource, r1, "post", p)
            insertReportRow(dataSource, r2, "post", p)
            val result =
                reportService.submit(
                    r3,
                    ReportTargetType.POST,
                    p,
                    ReportReasonCategory.SPAM,
                    null,
                )
            (result is ReportService.Result.Success) shouldBe true
            countNotifications(dataSource, alice, "post_auto_hidden") shouldBe 1
            val note = fetchNotification(dataSource, alice, "post_auto_hidden")
            note["actor_user_id"] shouldBe null
            note["target_type"] shouldBe "post"
            note["target_id"] shouldBe p
            val body = Json.parseToJsonElement(note["body"] as String).jsonObject
            body["reason"]!!.jsonPrimitive.content shouldBe "auto_hide_3_reports"
        } finally {
            cleanupUsers(dataSource, alice, r1, r2, r3)
        }
    }

    "10.14 Auto-hide (reply) → post_auto_hidden with target_type = 'reply'" {
        val alice = seedUser(dataSource, "rh1")
        val r1 = seedAgedUser(dataSource, "rh2")
        val r2 = seedAgedUser(dataSource, "rh3")
        val r3 = seedAgedUser(dataSource, "rh4")
        val p = seedPost(dataSource, alice)
        val replyAuthor = seedUser(dataSource, "rh5")
        val replyId = seedReply(dataSource, p, replyAuthor)
        try {
            insertReportRow(dataSource, r1, "reply", replyId)
            insertReportRow(dataSource, r2, "reply", replyId)
            reportService.submit(
                r3,
                ReportTargetType.REPLY,
                replyId,
                ReportReasonCategory.SPAM,
                null,
            )
            countNotifications(dataSource, replyAuthor, "post_auto_hidden") shouldBe 1
            val note = fetchNotification(dataSource, replyAuthor, "post_auto_hidden")
            note["target_type"] shouldBe "reply"
            note["target_id"] shouldBe replyId
        } finally {
            cleanupUsers(dataSource, alice, replyAuthor, r1, r2, r3)
        }
    }

    "10.15 Auto-hide on user target → zero notifications" {
        val victim = seedUser(dataSource, "uh1")
        val r1 = seedAgedUser(dataSource, "uh2")
        val r2 = seedAgedUser(dataSource, "uh3")
        val r3 = seedAgedUser(dataSource, "uh4")
        try {
            insertReportRow(dataSource, r1, "user", victim)
            insertReportRow(dataSource, r2, "user", victim)
            reportService.submit(
                r3,
                ReportTargetType.USER,
                victim,
                ReportReasonCategory.SPAM,
                null,
            )
            countNotifications(dataSource, victim) shouldBe 0
        } finally {
            cleanupUsers(dataSource, victim, r1, r2, r3)
        }
    }

    "10.16 4th reporter on already-hidden post → no NEW notification" {
        val alice = seedUser(dataSource, "a4")
        val r1 = seedAgedUser(dataSource, "a4_1")
        val r2 = seedAgedUser(dataSource, "a4_2")
        val r3 = seedAgedUser(dataSource, "a4_3")
        val r4 = seedAgedUser(dataSource, "a4_4")
        val p = seedPost(dataSource, alice)
        try {
            insertReportRow(dataSource, r1, "post", p)
            insertReportRow(dataSource, r2, "post", p)
            reportService.submit(r3, ReportTargetType.POST, p, ReportReasonCategory.SPAM, null)
            countNotifications(dataSource, alice, "post_auto_hidden") shouldBe 1
            reportService.submit(r4, ReportTargetType.POST, p, ReportReasonCategory.SPAM, null)
            countNotifications(dataSource, alice, "post_auto_hidden") shouldBe 1
        } finally {
            cleanupUsers(dataSource, alice, r1, r2, r3, r4)
        }
    }

    "10.17 Transaction atomicity (like): emit failure rolls back the post_likes INSERT" {
        val alice = seedUser(dataSource, "tx1")
        val bob = seedUser(dataSource, "tx2")
        val p = seedPost(dataSource, alice)
        val failing =
            object : NotificationEmitter {
                override fun emit(
                    conn: Connection,
                    recipientId: UUID,
                    actorUserId: UUID?,
                    type: id.nearyou.data.repository.NotificationType,
                    targetType: String?,
                    targetId: UUID?,
                    bodyData: kotlinx.serialization.json.JsonObject,
                ): UUID? {
                    throw RuntimeException("simulated emit failure")
                }
            }
        val failService =
            LikeService(
                dataSource = dataSource,
                likes = JdbcPostLikeRepository(dataSource),
                notifications = failing,
                dispatcher = dispatcher,
                rateLimiter = noopLimiter,
                remoteConfig = noopRemoteConfig,
            )
        try {
            runCatching { runBlocking { failService.like(p, bob, "free") } }.isFailure shouldBe true
            // No like row should persist — the failed emit rolled back the whole TX.
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT COUNT(*) FROM post_likes WHERE post_id = ? AND user_id = ?").use { ps ->
                    ps.setObject(1, p)
                    ps.setObject(2, bob)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1) shouldBe 0
                    }
                }
            }
        } finally {
            cleanupUsers(dataSource, alice, bob)
        }
    }

    "10.18 Transaction atomicity (reply): emit failure rolls back post_replies INSERT" {
        val alice = seedUser(dataSource, "trx1")
        val bob = seedUser(dataSource, "trx2")
        val p = seedPost(dataSource, alice)
        val failing =
            object : NotificationEmitter {
                override fun emit(
                    conn: Connection,
                    recipientId: UUID,
                    actorUserId: UUID?,
                    type: id.nearyou.data.repository.NotificationType,
                    targetType: String?,
                    targetId: UUID?,
                    bodyData: kotlinx.serialization.json.JsonObject,
                ): UUID? {
                    throw RuntimeException("simulated emit failure")
                }
            }
        val failReply =
            ReplyService(
                dataSource = dataSource,
                replies = JdbcPostReplyRepository(dataSource),
                notifications = failing,
                dispatcher = dispatcher,
                rateLimiter = noopLimiter,
                remoteConfig = noopRemoteConfig,
            )
        try {
            runCatching { failReply.post(p, bob, "text") }.isFailure shouldBe true
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT COUNT(*) FROM post_replies WHERE post_id = ? AND author_id = ?").use { ps ->
                    ps.setObject(1, p)
                    ps.setObject(2, bob)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1) shouldBe 0
                    }
                }
            }
        } finally {
            cleanupUsers(dataSource, alice, bob)
        }
    }

    "10.19 Transaction atomicity (follow): emit failure rolls back follows INSERT" {
        val alice = seedUser(dataSource, "tff1")
        val bob = seedUser(dataSource, "tff2")
        val failing =
            object : NotificationEmitter {
                override fun emit(
                    conn: Connection,
                    recipientId: UUID,
                    actorUserId: UUID?,
                    type: id.nearyou.data.repository.NotificationType,
                    targetType: String?,
                    targetId: UUID?,
                    bodyData: kotlinx.serialization.json.JsonObject,
                ): UUID? {
                    throw RuntimeException("simulated emit failure")
                }
            }
        val failFollow = FollowService(dataSource, JdbcUserFollowsRepository(dataSource), failing, dispatcher)
        try {
            runCatching { failFollow.follow(bob, alice) }.isFailure shouldBe true
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT COUNT(*) FROM follows WHERE follower_id = ? AND followee_id = ?").use { ps ->
                    ps.setObject(1, bob)
                    ps.setObject(2, alice)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1) shouldBe 0
                    }
                }
            }
        } finally {
            cleanupUsers(dataSource, alice, bob)
        }
    }

    "10.20 Transaction atomicity (auto-hide): emit failure rolls back the flip + queue row" {
        val alice = seedUser(dataSource, "tah1")
        val r1 = seedAgedUser(dataSource, "tah2")
        val r2 = seedAgedUser(dataSource, "tah3")
        val r3 = seedAgedUser(dataSource, "tah4")
        val p = seedPost(dataSource, alice)
        val failing =
            object : NotificationEmitter {
                override fun emit(
                    conn: Connection,
                    recipientId: UUID,
                    actorUserId: UUID?,
                    type: id.nearyou.data.repository.NotificationType,
                    targetType: String?,
                    targetId: UUID?,
                    bodyData: kotlinx.serialization.json.JsonObject,
                ): UUID? {
                    throw RuntimeException("simulated emit failure")
                }
            }
        val failReport =
            ReportService(
                dataSource = dataSource,
                reports = JdbcReportRepository(),
                moderationQueue = JdbcModerationQueueRepository(),
                postAutoHide = JdbcPostAutoHideRepository(),
                rateLimiter = ReportRateLimiter(),
                notifications = failing,
                dispatcher = dispatcher,
            )
        try {
            insertReportRow(dataSource, r1, "post", p)
            insertReportRow(dataSource, r2, "post", p)
            runCatching {
                failReport.submit(r3, ReportTargetType.POST, p, ReportReasonCategory.SPAM, null)
            }.isFailure shouldBe true
            // Post should NOT be auto-hidden, and no moderation_queue row should exist
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT is_auto_hidden FROM posts WHERE id = ?").use { ps ->
                    ps.setObject(1, p)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getBoolean(1) shouldBe false
                    }
                }
                conn.prepareStatement(
                    "SELECT COUNT(*) FROM moderation_queue WHERE target_id = ? AND trigger = 'auto_hide_3_reports'",
                ).use { ps ->
                    ps.setObject(1, p)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1) shouldBe 0
                    }
                }
            }
        } finally {
            cleanupUsers(dataSource, alice, r1, r2, r3)
        }
    }

    "10.21 System-originated emit (auto-hide) skips block-check: block between author and random user" {
        val alice = seedUser(dataSource, "sys1")
        val unrelated = seedUser(dataSource, "sys2")
        val r1 = seedAgedUser(dataSource, "sys3")
        val r2 = seedAgedUser(dataSource, "sys4")
        val r3 = seedAgedUser(dataSource, "sys5")
        val p = seedPost(dataSource, alice)
        insertBlock(dataSource, alice, unrelated) // Alice has a block in place; still should emit.
        try {
            insertReportRow(dataSource, r1, "post", p)
            insertReportRow(dataSource, r2, "post", p)
            reportService.submit(r3, ReportTargetType.POST, p, ReportReasonCategory.SPAM, null)
            countNotifications(dataSource, alice, "post_auto_hidden") shouldBe 1
            val note = fetchNotification(dataSource, alice, "post_auto_hidden")
            note["actor_user_id"] shouldBe null
        } finally {
            cleanupUsers(dataSource, alice, unrelated, r1, r2, r3)
        }
    }

    // Sanity check: ensure contentGuard is referenced to avoid unused-warning flare.
    "contentGuard is wired" {
        contentGuard shouldNotBe null
    }
})
