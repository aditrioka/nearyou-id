package id.nearyou.app.user

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.sql.Date
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Covers `fcm-push-dispatch` spec § "UserFcmTokenReader interface SHALL
 * define the read + on-send-delete contract over `user_fcm_tokens` with a
 * re-registration race guard". Tasks 7.2 (race-guard predicate) + the
 * `activeTokens(...) → TokenSnapshot` DB-clock-domain contract.
 *
 * Asserts the SQL contract directly against a real Postgres:
 *   - `activeTokens` returns the snapshot list ordered platform-stable, AND
 *     captures `dispatchStartedAt` from `SELECT NOW()` on the same connection.
 *   - `deleteTokenIfStale` honours the `last_seen_at <= :dispatch_started_at`
 *     predicate: stale-row + matching predicate → 1 row deleted; fresh-row +
 *     non-matching predicate → 0 rows deleted (race guard preserves it).
 */
@Tags("database")
class JdbcUserFcmTokenReaderTest : StringSpec(
    {
        val dataSource = hikari()
        val reader = JdbcUserFcmTokenReader(dataSource)

        fun seedUser(): UUID {
            val id = UUID.randomUUID()
            val short = id.toString().replace("-", "").take(8)
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix)
                    VALUES (?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, id)
                    ps.setString(2, "ufr_$short")
                    ps.setString(3, "FCM Reader Test $short")
                    ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                    ps.setString(5, "u${short.take(7)}")
                    ps.executeUpdate()
                }
            }
            return id
        }

        fun seedToken(
            userId: UUID,
            platform: String,
            token: String,
            lastSeenAt: Instant = Instant.now(),
        ) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO user_fcm_tokens (user_id, platform, token, last_seen_at)
                    VALUES (?, ?, ?, ?)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, userId)
                    ps.setString(2, platform)
                    ps.setString(3, token)
                    ps.setTimestamp(4, java.sql.Timestamp.from(lastSeenAt))
                    ps.executeUpdate()
                }
            }
        }

        "activeTokens returns rows for the user only AND captures DB-clock dispatchStartedAt" {
            val userA = seedUser()
            val userB = seedUser()
            seedToken(userA, "android", "tok-a-1")
            seedToken(userA, "ios", "tok-a-2")
            seedToken(userB, "android", "tok-b-1")

            val snapshot = reader.activeTokens(userA)
            snapshot.tokens.size shouldBe 2
            snapshot.tokens.all { it.token in setOf("tok-a-1", "tok-a-2") } shouldBe true
            // dispatchStartedAt is recent (within 60 seconds) — DB clock is
            // very close to JVM clock under healthy conditions.
            val drift = java.time.Duration.between(snapshot.dispatchStartedAt, Instant.now()).abs()
            (drift.seconds < 60) shouldBe true
        }

        "activeTokens returns empty list for user with no tokens (no exception)" {
            val userZ = seedUser()
            reader.activeTokens(userZ).tokens shouldBe emptyList()
        }

        "deleteTokenIfStale returns 1 when row exists AND predicate matches (stale row)" {
            val userId = seedUser()
            val past = Instant.now().minusSeconds(3600)
            seedToken(userId, "android", "tok-stale", lastSeenAt = past)
            val deleted =
                reader.deleteTokenIfStale(
                    userId = userId,
                    platform = "android",
                    token = "tok-stale",
                    dispatchStartedAt = Instant.now(),
                )
            deleted shouldBe 1
            reader.activeTokens(userId).tokens.any { it.token == "tok-stale" } shouldBe false
        }

        "deleteTokenIfStale returns 0 when re-registration races (predicate doesn't match)" {
            val userId = seedUser()
            val now = Instant.now()
            // last_seen_at is AFTER dispatchStartedAt → predicate
            // `last_seen_at <= dispatchStartedAt` does not match.
            seedToken(userId, "android", "tok-fresh", lastSeenAt = now.plusSeconds(60))
            val deleted =
                reader.deleteTokenIfStale(
                    userId = userId,
                    platform = "android",
                    token = "tok-fresh",
                    dispatchStartedAt = now,
                )
            deleted shouldBe 0
            // Row preserved.
            reader.activeTokens(userId).tokens.any { it.token == "tok-fresh" } shouldBe true
        }

        "deleteTokenIfStale returns 0 when no matching row exists" {
            val userId = seedUser()
            val deleted =
                reader.deleteTokenIfStale(
                    userId = userId,
                    platform = "android",
                    token = "tok-missing",
                    dispatchStartedAt = Instant.now(),
                )
            deleted shouldBe 0
        }

        "deleteTokenIfStale does not affect peer tokens" {
            val userId = seedUser()
            val past = Instant.now().minusSeconds(3600)
            seedToken(userId, "android", "tok-1", lastSeenAt = past)
            seedToken(userId, "android", "tok-2", lastSeenAt = past)
            seedToken(userId, "ios", "tok-3", lastSeenAt = past)
            reader.deleteTokenIfStale(userId, "android", "tok-1", Instant.now()) shouldBe 1
            val remaining = reader.activeTokens(userId).tokens.map { it.token }.toSet()
            remaining shouldBe setOf("tok-2", "tok-3")
        }
    },
)

private fun hikari(): HikariDataSource {
    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val user = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"
    val config =
        HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            maximumPoolSize = 4
            initializationFailTimeout = -1
        }
    return HikariDataSource(config)
}
