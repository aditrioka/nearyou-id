package id.nearyou.app.account

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import kotlinx.coroutines.runBlocking
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private fun hikari(): HikariDataSource {
    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val user = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"
    val config =
        HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            maximumPoolSize = 2
            initializationFailTimeout = -1
        }
    return HikariDataSource(config)
}

@Tags("database")
class AccountHardDeleteWorkerTest : StringSpec({

    // autoClose releases the pool after the spec (CI connection-budget: don't leak a HikariPool).
    val dataSource = autoClose(hikari())
    val worker = AccountHardDeleteWorker(dataSource)

    fun seedUser(): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (
                    id, username, display_name, bio, email, google_id_hash,
                    device_fingerprint_hash, date_of_birth, apple_relay_email, invite_code_prefix
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "del_$short")
                ps.setString(3, "Real Name")
                ps.setString(4, "real bio")
                ps.setString(5, "real@example.com")
                ps.setString(6, "google_hash_$short")
                ps.setString(7, "device_$short")
                ps.setDate(8, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setBoolean(9, true)
                ps.setString(10, "d${short.take(7)}")
                ps.executeUpdate()
            }
        }
        return id
    }

    /** Insert a deletion_requests row; returns its id. */
    fun seedRequest(
        userId: UUID,
        scheduled: Instant,
        source: String = "user",
        cancelled: Boolean = false,
        executed: Boolean = false,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO deletion_requests (id, user_id, scheduled_hard_delete_at, source, cancelled_at, executed_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, userId)
                ps.setTimestamp(3, Timestamp.from(scheduled))
                ps.setString(4, source)
                if (cancelled) ps.setTimestamp(5, Timestamp.from(Instant.now())) else ps.setNull(5, java.sql.Types.TIMESTAMP)
                if (executed) ps.setTimestamp(6, Timestamp.from(Instant.now())) else ps.setNull(6, java.sql.Types.TIMESTAMP)
                ps.executeUpdate()
            }
        }
        return id
    }

    fun exec(
        sql: String,
        vararg args: Any,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
                ps.executeUpdate()
            }
        }
    }

    fun count(
        sql: String,
        vararg args: Any,
    ): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    data class Tomb(
        val deletedAt: Instant?,
        val displayName: String,
        val bio: String?,
        val email: String?,
        val google: String?,
        val apple: String?,
        val device: String?,
        val dob: LocalDate,
        val relay: Boolean,
        val username: String,
    )

    fun loadUser(id: UUID): Tomb? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT deleted_at, display_name, bio, email, google_id_hash, apple_id_hash,
                       device_fingerprint_hash, date_of_birth, apple_relay_email, username
                  FROM users WHERE id = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return@use null
                    Tomb(
                        deletedAt = rs.getTimestamp("deleted_at")?.toInstant(),
                        displayName = rs.getString("display_name"),
                        bio = rs.getString("bio"),
                        email = rs.getString("email"),
                        google = rs.getString("google_id_hash"),
                        apple = rs.getString("apple_id_hash"),
                        device = rs.getString("device_fingerprint_hash"),
                        dob = rs.getDate("date_of_birth").toLocalDate(),
                        relay = rs.getBoolean("apple_relay_email"),
                        username = rs.getString("username"),
                    )
                }
            }
        }

    fun cleanup(vararg ids: UUID) {
        dataSource.connection.use { conn ->
            ids.forEach { id ->
                conn.prepareStatement("DELETE FROM deletion_requests WHERE user_id = ?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM deletion_log WHERE user_id = ?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM follows WHERE follower_id = ? OR followee_id = ?").use {
                    it.setObject(1, id)
                    it.setObject(2, id)
                    it.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM user_blocks WHERE blocker_id = ? OR blocked_id = ?").use {
                    it.setObject(1, id)
                    it.setObject(2, id)
                    it.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM user_fcm_tokens WHERE user_id = ?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM notifications WHERE user_id = ?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM refresh_tokens WHERE user_id = ?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM login_events WHERE user_id = ?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM posts WHERE author_id = ?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM users WHERE id = ?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
            }
        }
    }

    "due request tombstones the user with the exact PII erasure" {
        val uid = seedUser()
        seedRequest(uid, Instant.now().minusSeconds(60))
        try {
            val result = runBlocking { worker.execute() }
            (result.deletedCount >= 1) shouldBe true
            val t = loadUser(uid)!!
            (t.deletedAt != null) shouldBe true
            t.displayName shouldBe "Akun Dihapus"
            t.bio shouldBe null
            t.email shouldBe null
            t.google shouldBe null
            t.apple shouldBe null
            t.device shouldBe null
            t.dob shouldBe LocalDate.of(1900, 1, 1)
            t.relay shouldBe false
            t.username shouldMatch Regex("^deleted_user_[0-9a-f]{8,}$")
        } finally {
            cleanup(uid)
        }
    }

    "cascade tables are emptied (both-direction follows + blocks, fcm, notifications, tokens, login events)" {
        val uid = seedUser()
        val other = seedUser()
        seedRequest(uid, Instant.now().minusSeconds(60))
        // both-direction follows + blocks
        exec("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", uid, other)
        exec("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", other, uid)
        exec("INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (?, ?)", uid, other)
        exec("INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (?, ?)", other, uid)
        exec("INSERT INTO user_fcm_tokens (user_id, platform, token) VALUES (?, 'android', ?)", uid, "tok_$uid")
        exec("INSERT INTO notifications (user_id, type) VALUES (?, 'followed')", uid)
        exec("INSERT INTO login_events (user_id, event_type) VALUES (?, 'signin')", uid)
        exec(
            "INSERT INTO refresh_tokens (id, family_id, user_id, token_hash, expires_at) VALUES (?, ?, ?, ?, ?)",
            UUID.randomUUID(),
            UUID.randomUUID(),
            uid,
            "hash_$uid",
            Timestamp.from(Instant.now().plusSeconds(86_400)),
        )
        try {
            runBlocking { worker.execute() }
            count("SELECT COUNT(*) FROM follows WHERE follower_id = ? OR followee_id = ?", uid, uid) shouldBe 0
            count("SELECT COUNT(*) FROM user_blocks WHERE blocker_id = ? OR blocked_id = ?", uid, uid) shouldBe 0
            count("SELECT COUNT(*) FROM user_fcm_tokens WHERE user_id = ?", uid) shouldBe 0
            count("SELECT COUNT(*) FROM notifications WHERE user_id = ?", uid) shouldBe 0
            count("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", uid) shouldBe 0
            // login_events deleted explicitly (the FK ON DELETE CASCADE does NOT fire on a tombstoned user)
            count("SELECT COUNT(*) FROM login_events WHERE user_id = ?", uid) shouldBe 0
        } finally {
            cleanup(uid, other)
        }
    }

    "authored content is retained (posts) + deletion_log written + executed_at set" {
        val uid = seedUser()
        val reqId = seedRequest(uid, Instant.now().minusSeconds(60))
        exec(
            """
            INSERT INTO posts (author_id, content, display_location, actual_location)
            VALUES (?, 'retained post', ST_SetSRID(ST_MakePoint(106.8, -6.2), 4326)::geography,
                    ST_SetSRID(ST_MakePoint(106.8, -6.2), 4326)::geography)
            """.trimIndent(),
            uid,
        )
        try {
            runBlocking { worker.execute() }
            count("SELECT COUNT(*) FROM posts WHERE author_id = ?", uid) shouldBe 1 // retained
            count("SELECT COUNT(*) FROM deletion_log WHERE user_id = ? AND source = 'user'", uid) shouldBe 1
            count("SELECT COUNT(*) FROM deletion_requests WHERE id = ? AND executed_at IS NOT NULL", reqId) shouldBe 1
        } finally {
            cleanup(uid)
        }
    }

    "cancelled request is skipped — user untouched" {
        val uid = seedUser()
        seedRequest(uid, Instant.now().minusSeconds(60), cancelled = true)
        try {
            runBlocking { worker.execute() }
            val t = loadUser(uid)!!
            (t.deletedAt == null) shouldBe true
            t.displayName shouldBe "Real Name"
        } finally {
            cleanup(uid)
        }
    }

    "not-yet-due request is skipped" {
        val uid = seedUser()
        seedRequest(uid, Instant.now().plusSeconds(86_400))
        try {
            runBlocking { worker.execute() }
            (loadUser(uid)!!.deletedAt == null) shouldBe true
        } finally {
            cleanup(uid)
        }
    }

    "idempotent — re-run does not re-process an executed request (one deletion_log row)" {
        val uid = seedUser()
        seedRequest(uid, Instant.now().minusSeconds(60))
        try {
            runBlocking { worker.execute() }
            runBlocking { worker.execute() } // second run: row now executed
            count("SELECT COUNT(*) FROM deletion_log WHERE user_id = ?", uid) shouldBe 1 // not duplicated
        } finally {
            cleanup(uid)
        }
    }
})
