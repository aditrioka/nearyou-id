package id.nearyou.app.user

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.sql.Date
import java.time.LocalDate
import java.util.UUID

/**
 * Covers `fcm-push-dispatch` spec § "Actor-username lookup SHALL use FROM
 * visible_users to mask shadow-banned and deleted actors". Task 7.10
 * (the shadow-ban MASK contract — at the JDBC layer rather than full e2e).
 *
 * Asserts the privacy contract directly:
 *   - Visible (non-shadow-banned, non-deleted) actor → username returned.
 *   - Shadow-banned actor (`is_shadow_banned = TRUE`) → null.
 *   - Hard-deleted actor (`deleted_at IS NOT NULL`) → null.
 *   - Null actorUserId (system-emitted notification) → null without a query.
 *
 * Why at JDBC layer, not full FCM e2e: the e2e would require seeding
 * users + posts + likes + a registered FCM token + a mocked
 * `FirebaseMessaging.send(...)` to capture the rendered push body. The
 * privacy contract here is `JdbcActorUsernameLookup → null on shadow-ban`;
 * the downstream rendering (`PushCopy.bodyFor(notification, null)`) is
 * unit-tested in `:infra:fcm/PushCopyTest`. The composition is verified by
 * `FcmDispatcherTest` task 6.4.11 (actor null → fallback path).
 */
@Tags("database")
class JdbcActorUsernameLookupTest : StringSpec(
    {
        val dataSource = hikari()
        val lookup = JdbcActorUsernameLookup(dataSource)

        fun seedUser(
            isShadowBanned: Boolean = false,
            deleted: Boolean = false,
        ): Pair<UUID, String> {
            val id = UUID.randomUUID()
            val short = id.toString().replace("-", "").take(8)
            val username = "vu_$short"
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO users (
                        id, username, display_name, date_of_birth, invite_code_prefix,
                        is_shadow_banned, deleted_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, id)
                    ps.setString(2, username)
                    ps.setString(3, "Visible-Users Test $short")
                    ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                    ps.setString(5, "v${short.take(7)}")
                    ps.setBoolean(6, isShadowBanned)
                    if (deleted) {
                        ps.setObject(7, java.sql.Timestamp.from(java.time.Instant.now()))
                    } else {
                        ps.setNull(7, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                    }
                    ps.executeUpdate()
                }
            }
            return id to username
        }

        "lookup returns username for a visible (non-shadow-banned, non-deleted) actor" {
            val (id, username) = seedUser(isShadowBanned = false, deleted = false)
            lookup.lookup(id) shouldBe username
        }

        "lookup returns null for a shadow-banned actor (privacy MASK)" {
            val (id, _) = seedUser(isShadowBanned = true, deleted = false)
            lookup.lookup(id) shouldBe null
        }

        "lookup returns null for a hard-deleted actor" {
            val (id, _) = seedUser(isShadowBanned = false, deleted = true)
            lookup.lookup(id) shouldBe null
        }

        "lookup returns null for a non-existent userId" {
            lookup.lookup(UUID.randomUUID()) shouldBe null
        }

        "lookup returns null without querying when actorUserId is null (system-emitted notification)" {
            lookup.lookup(null) shouldBe null
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
