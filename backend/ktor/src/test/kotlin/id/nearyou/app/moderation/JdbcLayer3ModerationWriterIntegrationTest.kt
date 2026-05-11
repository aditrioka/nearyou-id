package id.nearyou.app.moderation

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.infra.repo.JdbcLayer3ModerationWriter
import id.nearyou.data.repository.Layer3WriteResult
import id.nearyou.data.repository.ReportTargetType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.Date
import java.sql.PreparedStatement
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Integration tests for [JdbcLayer3ModerationWriter] per
 * `text-moderation-perspective-api-layer/spec.md`
 * `### Requirement: AutoHide outcome flips is_auto_hidden and writes queue row in
 * one SQL transaction (with soft-delete guard)`.
 *
 * Covers tasks 7.4 (idempotency + true concurrency), 7.5 (atomicity rollback when
 * queue INSERT fails mid-transaction — uses a JDBC `Connection` proxy that throws
 * on the queue INSERT step), and 7.6 (soft-delete-target race).
 *
 * Requires a reachable Postgres (the project's `KotestProjectConfig` probes for it
 * and runs Flyway). On unit-only runs without Postgres, these tests are skipped at
 * the project level — the test class itself does not gate.
 */
class JdbcLayer3ModerationWriterIntegrationTest : StringSpec({

    val dbUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val dbUser = System.getenv("DB_USER") ?: "postgres"
    val dbPassword = System.getenv("DB_PASSWORD") ?: "postgres"
    val dataSource: HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = dbUrl
                username = dbUser
                password = dbPassword
                // Enough connections for the concurrent-INSERT test (2 callers + introspection).
                maximumPoolSize = 8
                initializationFailTimeout = -1
            },
        )
    val writer = JdbcLayer3ModerationWriter(dataSource)

    afterSpec { dataSource.close() }

    "applyAutoHideAndQueue is idempotent on sequential retries — exactly one queue row" {
        val author = seedUser(dataSource, "pa")
        val postId = seedPost(dataSource, author)
        try {
            writer.applyAutoHideAndQueue(ReportTargetType.POST, postId, score = 0.92) shouldBe
                Layer3WriteResult.Applied
            writer.applyAutoHideAndQueue(ReportTargetType.POST, postId, score = 0.92) shouldBe
                Layer3WriteResult.QueueConflictNoOp

            countQueueRows(dataSource, postId) shouldBe 1
            isAutoHidden(dataSource, postId) shouldBe true
        } finally {
            cleanupPost(dataSource, postId)
            cleanupUser(dataSource, author)
        }
    }

    "Concurrent applyAutoHideAndQueue collapses to one queue row via UNIQUE constraint" {
        val author = seedUser(dataSource, "pc")
        val postId = seedPost(dataSource, author)
        try {
            val gate = CountDownLatch(1)
            val ready = CountDownLatch(2)
            val executor = Executors.newFixedThreadPool(2)
            val results: MutableList<Layer3WriteResult> =
                java.util.Collections.synchronizedList(mutableListOf())
            try {
                repeat(2) {
                    executor.submit {
                        ready.countDown()
                        gate.await()
                        val result = writer.applyAutoHideAndQueue(ReportTargetType.POST, postId, score = 0.92)
                        results.add(result)
                    }
                }
                ready.await(5, TimeUnit.SECONDS)
                gate.countDown()
                executor.shutdown()
                executor.awaitTermination(10, TimeUnit.SECONDS) shouldBe true
            } finally {
                executor.shutdownNow()
            }

            // Both callers complete without surfacing a unique-violation error to the orchestrator.
            results.size shouldBe 2
            // Exactly one queue row exists; the other caller observed ON CONFLICT DO NOTHING.
            countQueueRows(dataSource, postId) shouldBe 1
            isAutoHidden(dataSource, postId) shouldBe true
            // Each caller saw either Applied or QueueConflictNoOp — neither saw an exception.
            results.all { it is Layer3WriteResult.Applied || it is Layer3WriteResult.QueueConflictNoOp } shouldBe true
        } finally {
            cleanupPost(dataSource, postId)
            cleanupUser(dataSource, author)
        }
    }

    "Atomicity: queue INSERT failure rolls back the UPDATE (is_auto_hidden remains FALSE)" {
        val author = seedUser(dataSource, "atom")
        val postId = seedPost(dataSource, author)
        try {
            // Wrap the data source so EVERY connection it returns wraps the queue INSERT
            // PreparedStatement to throw on `executeUpdate()`. Mirrors the spec scenario:
            // "simulate the queue INSERT failing mid-transaction via a JDBC interceptor".
            val interceptingDataSource = QueueInsertFailingDataSource(dataSource)
            val interceptingWriter = JdbcLayer3ModerationWriter(interceptingDataSource)
            shouldThrow<RuntimeException> {
                interceptingWriter.applyAutoHideAndQueue(ReportTargetType.POST, postId, score = 0.92)
            }

            // Re-SELECT from a FRESH connection. Both invariants:
            // (a) is_auto_hidden = FALSE — UPDATE was rolled back.
            // (b) zero queue rows — INSERT never committed.
            isAutoHidden(dataSource, postId) shouldBe false
            countQueueRows(dataSource, postId) shouldBe 0
        } finally {
            cleanupPost(dataSource, postId)
            cleanupUser(dataSource, author)
        }
    }

    "Soft-deleted target races to no-op — UPDATE returns 0 rows, INSERT skipped" {
        val author = seedUser(dataSource, "sd")
        val postId = seedPost(dataSource, author)
        // Soft-delete the post BEFORE invoking the writer (simulates the race
        // where the user deletes their content between INSERT and async dispatch).
        softDeletePost(dataSource, postId)
        try {
            // No exception thrown.
            writer.applyAutoHideAndQueue(ReportTargetType.POST, postId, score = 0.92) shouldBe
                Layer3WriteResult.SoftDeletedTarget

            // Zero queue rows — the INSERT was skipped because the UPDATE returned 0.
            countQueueRows(dataSource, postId) shouldBe 0
            // is_auto_hidden remains FALSE on the soft-deleted row (UPDATE didn't match).
            isAutoHidden(dataSource, postId) shouldBe false
        } finally {
            cleanupPost(dataSource, postId)
            cleanupUser(dataSource, author)
        }
    }

    "applyFlagQueue writes queue row WITHOUT flipping is_auto_hidden" {
        val author = seedUser(dataSource, "flag")
        val postId = seedPost(dataSource, author)
        try {
            writer.applyFlagQueue(ReportTargetType.POST, postId, score = 0.70) shouldBe
                Layer3WriteResult.Applied

            countQueueRows(dataSource, postId) shouldBe 1
            isAutoHidden(dataSource, postId) shouldBe false
        } finally {
            cleanupPost(dataSource, postId)
            cleanupUser(dataSource, author)
        }
    }

    "applyFlagQueue idempotent retry collapses to one row" {
        val author = seedUser(dataSource, "fr")
        val postId = seedPost(dataSource, author)
        try {
            writer.applyFlagQueue(ReportTargetType.POST, postId, score = 0.70) shouldBe
                Layer3WriteResult.Applied
            writer.applyFlagQueue(ReportTargetType.POST, postId, score = 0.70) shouldBe
                Layer3WriteResult.QueueConflictNoOp

            countQueueRows(dataSource, postId) shouldBe 1
        } finally {
            cleanupPost(dataSource, postId)
            cleanupUser(dataSource, author)
        }
    }

    "Reply target updates post_replies, not posts" {
        val author = seedUser(dataSource, "rauth")
        val parentPost = seedPost(dataSource, author)
        val replyId = seedReply(dataSource, parentPost, author)
        try {
            writer.applyAutoHideAndQueue(ReportTargetType.REPLY, replyId, score = 0.92) shouldBe
                Layer3WriteResult.Applied

            isReplyAutoHidden(dataSource, replyId) shouldBe true
            countQueueRowsForReply(dataSource, replyId) shouldBe 1
            // The parent post was NOT touched.
            isAutoHidden(dataSource, parentPost) shouldBe false
        } finally {
            cleanupReply(dataSource, replyId)
            cleanupPost(dataSource, parentPost)
            cleanupUser(dataSource, author)
        }
    }
})

// ---- DB seed / inspect / cleanup helpers ------------------------------------

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
            ps.setString(3, "Perspective Test")
            ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
            ps.setString(5, "p${short.take(7)}")
            ps.executeUpdate()
        }
    }
    return id
}

private fun seedPost(
    dataSource: DataSource,
    authorId: UUID,
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
            ps.setString(3, "Perspective seed post")
            ps.setDouble(4, 106.8)
            ps.setDouble(5, -6.2)
            ps.setDouble(6, 106.8)
            ps.setDouble(7, -6.2)
            ps.executeUpdate()
        }
    }
    return id
}

private fun seedReply(
    dataSource: DataSource,
    postId: UUID,
    authorId: UUID,
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
            ps.setString(4, "Perspective seed reply")
            ps.executeUpdate()
        }
    }
    return id
}

private fun softDeletePost(
    dataSource: DataSource,
    postId: UUID,
) {
    dataSource.connection.use { conn ->
        conn.prepareStatement("UPDATE posts SET deleted_at = NOW() WHERE id = ?").use { ps ->
            ps.setObject(1, postId)
            ps.executeUpdate()
        }
    }
}

private fun isAutoHidden(
    dataSource: DataSource,
    postId: UUID,
): Boolean {
    dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT is_auto_hidden FROM posts WHERE id = ?").use { ps ->
            ps.setObject(1, postId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return false
                return rs.getBoolean("is_auto_hidden")
            }
        }
    }
}

private fun isReplyAutoHidden(
    dataSource: DataSource,
    replyId: UUID,
): Boolean {
    dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT is_auto_hidden FROM post_replies WHERE id = ?").use { ps ->
            ps.setObject(1, replyId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return false
                return rs.getBoolean("is_auto_hidden")
            }
        }
    }
}

private fun countQueueRows(
    dataSource: DataSource,
    targetId: UUID,
): Int {
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            "SELECT count(*) FROM moderation_queue WHERE target_type = 'post' AND target_id = ? AND trigger = 'perspective_api_high_score'",
        ).use { ps ->
            ps.setObject(1, targetId)
            ps.executeQuery().use { rs ->
                rs.next()
                return rs.getInt(1)
            }
        }
    }
}

private fun countQueueRowsForReply(
    dataSource: DataSource,
    targetId: UUID,
): Int {
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            "SELECT count(*) FROM moderation_queue " +
                "WHERE target_type = 'reply' AND target_id = ? AND trigger = 'perspective_api_high_score'",
        ).use { ps ->
            ps.setObject(1, targetId)
            ps.executeQuery().use { rs ->
                rs.next()
                return rs.getInt(1)
            }
        }
    }
}

private fun cleanupPost(
    dataSource: DataSource,
    postId: UUID,
) {
    dataSource.connection.use { conn ->
        conn.prepareStatement("DELETE FROM moderation_queue WHERE target_id = ?").use { ps ->
            ps.setObject(1, postId)
            ps.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM posts WHERE id = ?").use { ps ->
            ps.setObject(1, postId)
            ps.executeUpdate()
        }
    }
}

private fun cleanupReply(
    dataSource: DataSource,
    replyId: UUID,
) {
    dataSource.connection.use { conn ->
        conn.prepareStatement("DELETE FROM moderation_queue WHERE target_id = ?").use { ps ->
            ps.setObject(1, replyId)
            ps.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM post_replies WHERE id = ?").use { ps ->
            ps.setObject(1, replyId)
            ps.executeUpdate()
        }
    }
}

private fun cleanupUser(
    dataSource: DataSource,
    userId: UUID,
) {
    dataSource.connection.use { conn ->
        conn.prepareStatement("DELETE FROM users WHERE id = ?").use { ps ->
            ps.setObject(1, userId)
            ps.executeUpdate()
        }
    }
}

// ---- JDBC interceptor: throws on the queue INSERT step ---------------------

/**
 * Wraps a [DataSource] so every connection it returns intercepts `prepareStatement`
 * calls; if the SQL contains the moderation_queue INSERT, the returned
 * PreparedStatement throws on `executeUpdate()`. Used by the atomicity test (7.5):
 * the writer's `applyAutoHideAndQueue` runs UPDATE then INSERT; if the INSERT step
 * throws, the transaction MUST roll back the UPDATE.
 */
private class QueueInsertFailingDataSource(
    private val delegate: DataSource,
) : DataSource by delegate {
    override fun getConnection(): Connection = wrap(delegate.connection)

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = wrap(delegate.getConnection(username, password))

    private fun wrap(conn: Connection): Connection =
        Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(Connection::class.java),
            ConnectionInvocationHandler(conn),
        ) as Connection
}

private class ConnectionInvocationHandler(private val target: Connection) : InvocationHandler {
    override fun invoke(
        proxy: Any,
        method: Method,
        args: Array<out Any?>?,
    ): Any? {
        if (method.name == "prepareStatement" && args != null && args.isNotEmpty()) {
            val sql = args[0] as? String
            if (sql != null && sql.contains("INSERT INTO moderation_queue")) {
                val realPs = target.prepareStatement(sql)
                return Proxy.newProxyInstance(
                    javaClass.classLoader,
                    arrayOf(PreparedStatement::class.java),
                    ThrowingPreparedStatementHandler(realPs),
                )
            }
        }
        return invokeOrUnwrap(method, args)
    }

    private fun invokeOrUnwrap(
        method: Method,
        args: Array<out Any?>?,
    ): Any? =
        try {
            if (args == null) method.invoke(target) else method.invoke(target, *args)
        } catch (t: java.lang.reflect.InvocationTargetException) {
            throw t.targetException
        }
}

private class ThrowingPreparedStatementHandler(private val target: PreparedStatement) : InvocationHandler {
    override fun invoke(
        proxy: Any,
        method: Method,
        args: Array<out Any?>?,
    ): Any? {
        if (method.name == "executeUpdate") {
            throw RuntimeException("simulated INSERT failure")
        }
        return try {
            if (args == null) method.invoke(target) else method.invoke(target, *args)
        } catch (t: java.lang.reflect.InvocationTargetException) {
            throw t.targetException
        }
    }
}
