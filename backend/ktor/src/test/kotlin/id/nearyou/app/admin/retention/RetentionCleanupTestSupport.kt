package id.nearyou.app.admin.retention

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Date
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * Seeding + cleanup helpers for the `scheduled-retention-cleanup` DB-tagged
 * tests. Every row carries a unique `rc_<8hex>` prefix (username / token) so
 * per-test cleanup removes exactly the rows it created and never pollutes the
 * shared timeline/notification suites in the full multi-spec gate (MEMORY:
 * "New post-creating DB tests pollute timeline suites").
 *
 * Seed timestamps are bound as absolute [Instant]s via `Timestamp.from`; the
 * sweeps compare against the DB's `NOW()`, so the boundary cases use thresholds
 * comfortably inside / outside the window (e.g. 89d vs 91d) — never an exact
 * fencepost — to avoid the macOS-micros-vs-Linux-nanos clock false-fail
 * (MEMORY: "DB timestamp assertions false-fail in CI only").
 */
object RetentionCleanupTestSupport {
    fun hikari(): HikariDataSource {
        val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
        val user = System.getenv("DB_USER") ?: "postgres"
        val password = System.getenv("DB_PASSWORD") ?: "postgres"
        return HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = url
                username = user
                this.password = password
                maximumPoolSize = 2
                initializationFailTimeout = -1
            },
        )
    }

    /** A unique-per-row 8-hex tag used to prefix usernames + tokens for scoped cleanup. */
    fun tag(): String = UUID.randomUUID().toString().replace("-", "").take(8)

    /** Seed a minimal `users` row (the FK parent for tokens/notifications). */
    fun seedUser(
        dataSource: DataSource,
        tag: String,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "rc_$tag")
                ps.setString(3, "RC $tag")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "r${tag.take(7)}")
                ps.executeUpdate()
            }
        }
        return id
    }

    /**
     * Seed a `refresh_tokens` row with explicit `expires_at` / `last_used_at`
     * (nullable) / `revoked_at` (nullable) offsets relative to now. The
     * `token_hash` carries the `tag` so cleanup can target it.
     */
    fun seedRefreshToken(
        dataSource: DataSource,
        userId: UUID,
        tag: String,
        expiresAt: Instant,
        lastUsedAt: Instant?,
        revokedAt: Instant? = null,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO refresh_tokens
                    (id, family_id, user_id, token_hash, last_used_at, revoked_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, UUID.randomUUID())
                ps.setObject(3, userId)
                ps.setString(4, "rc_${tag}_${id.toString().take(8)}")
                if (lastUsedAt != null) ps.setTimestamp(5, Timestamp.from(lastUsedAt)) else ps.setNull(5, Types.TIMESTAMP)
                if (revokedAt != null) ps.setTimestamp(6, Timestamp.from(revokedAt)) else ps.setNull(6, Types.TIMESTAMP)
                ps.setTimestamp(7, Timestamp.from(expiresAt))
                ps.executeUpdate()
            }
        }
        return id
    }

    /** Seed a `notifications` row with an explicit `created_at` and `type`. */
    fun seedNotification(
        dataSource: DataSource,
        userId: UUID,
        createdAt: Instant,
        type: String = "post_liked",
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO notifications (id, user_id, type, created_at) VALUES (?, ?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, userId)
                ps.setString(3, type)
                ps.setTimestamp(4, Timestamp.from(createdAt))
                ps.executeUpdate()
            }
        }
        return id
    }

    /** Seed a `user_fcm_tokens` row with an explicit `last_seen_at`. */
    fun seedFcmToken(
        dataSource: DataSource,
        userId: UUID,
        tag: String,
        lastSeenAt: Instant,
        platform: String = "android",
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO user_fcm_tokens (id, user_id, platform, token, last_seen_at)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, userId)
                ps.setString(3, platform)
                ps.setString(4, "rc_${tag}_${id.toString().take(8)}")
                ps.setTimestamp(5, Timestamp.from(lastSeenAt))
                ps.executeUpdate()
            }
        }
        return id
    }

    /** Seed a `login_events` row with an explicit `occurred_at` (V34). */
    fun seedLoginEvent(
        dataSource: DataSource,
        userId: UUID,
        occurredAt: Instant,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO login_events (id, user_id, occurred_at, event_type) VALUES (?, ?, ?, 'signin')",
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, userId)
                ps.setTimestamp(3, Timestamp.from(occurredAt))
                ps.executeUpdate()
            }
        }
        return id
    }

    fun refreshTokenExists(
        dataSource: DataSource,
        id: UUID,
    ): Boolean = rowExists(dataSource, "SELECT 1 FROM refresh_tokens WHERE id = ?", id)

    fun loginEventExists(
        dataSource: DataSource,
        id: UUID,
    ): Boolean = rowExists(dataSource, "SELECT 1 FROM login_events WHERE id = ?", id)

    fun notificationExists(
        dataSource: DataSource,
        id: UUID,
    ): Boolean = rowExists(dataSource, "SELECT 1 FROM notifications WHERE id = ?", id)

    fun fcmTokenExists(
        dataSource: DataSource,
        id: UUID,
    ): Boolean = rowExists(dataSource, "SELECT 1 FROM user_fcm_tokens WHERE id = ?", id)

    private fun rowExists(
        dataSource: DataSource,
        sql: String,
        id: UUID,
    ): Boolean =
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use { rs -> rs.next() }
            }
        }

    /**
     * Per-test cleanup, scoped to the rows a test seeded. Deletes the tokens /
     * notifications first, then the parent users (FK order). The user delete by
     * id ANY(?) also clears any leftover child rows via their ON DELETE CASCADE,
     * but we delete children explicitly first so a test that seeds a token under
     * a pre-existing user (none do today) would still be tidy.
     */
    fun cleanup(
        dataSource: DataSource,
        userIds: List<UUID>,
    ) {
        if (userIds.isEmpty()) return
        dataSource.connection.use { conn ->
            val arr = conn.createArrayOf("uuid", userIds.toTypedArray())
            conn.prepareStatement("DELETE FROM refresh_tokens WHERE user_id = ANY(?)").use { ps ->
                ps.setArray(1, arr)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM notifications WHERE user_id = ANY(?)").use { ps ->
                ps.setArray(1, arr)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM user_fcm_tokens WHERE user_id = ANY(?)").use { ps ->
                ps.setArray(1, arr)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM login_events WHERE user_id = ANY(?)").use { ps ->
                ps.setArray(1, arr)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM users WHERE id = ANY(?)").use { ps ->
                ps.setArray(1, arr)
                ps.executeUpdate()
            }
        }
    }
}
