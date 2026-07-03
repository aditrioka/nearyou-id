package id.nearyou.app.referral

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.sql.Date
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
            minimumIdle = 0
            initializationFailTimeout = -1
        }
    return HikariDataSource(config)
}

/**
 * DB-tagged coverage of [JdbcReferralReadRepository.getReferralState] — the combined self-read of
 * `users.invite_code_prefix` + the `granted` referral-ticket count + the `inviter_reward_claimed_at`
 * sentinel. Seeds no posts (cannot pollute the timeline suites); `autoClose`s its frugal pool
 * (docs/11 §3.2). The `granted`-count query must count ONLY `status = 'granted'` tickets, and the
 * claimed flag must reflect `inviter_reward_claimed_at IS NOT NULL`.
 */
@Tags("database")
class ReferralReadRepositoryTest : StringSpec({

    val dataSource = autoClose(hikari())
    val repository = JdbcReferralReadRepository(dataSource)

    fun seedUser(): Pair<UUID, String> {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        val prefix = "p${short.take(7)}"
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "rrr_$short")
                ps.setString(3, "Repo Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, prefix)
                ps.executeUpdate()
            }
        }
        return id to prefix
    }

    /** Inserts one invitee user + a ticket with [status] for [inviterId]. Returns the invitee id. */
    fun seedTicket(
        inviterId: UUID,
        status: String,
    ): UUID {
        val inviteeId = UUID.randomUUID()
        val short = inviteeId.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, inviteeId)
                ps.setString(2, "ivr_$short")
                ps.setString(3, "Invitee")
                ps.setDate(4, Date.valueOf(LocalDate.of(1991, 2, 2)))
                ps.setString(5, "w${short.take(7)}")
                ps.executeUpdate()
            }
            conn.prepareStatement(
                """
                INSERT INTO referral_tickets (inviter_user_id, invitee_user_id, status, expires_at)
                VALUES (?, ?, ?, NOW() + INTERVAL '14 days')
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, inviterId)
                ps.setObject(2, inviteeId)
                ps.setString(3, status)
                ps.executeUpdate()
            }
        }
        return inviteeId
    }

    fun setRewardClaimed(userId: UUID) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE users SET inviter_reward_claimed_at = NOW() WHERE id = ?").use { ps ->
                ps.setObject(1, userId)
                ps.executeUpdate()
            }
        }
    }

    fun cleanup(vararg ids: UUID) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                ids.forEach { st.executeUpdate("DELETE FROM users WHERE id = '$it'") }
            }
        }
    }

    "getReferralState returns the verbatim invite code and a zero count for a fresh user" {
        val (uid, prefix) = seedUser()
        try {
            val state = runBlocking { repository.getReferralState(uid) }
            state.inviteCode shouldBe prefix
            state.grantedReferrals shouldBe 0
            state.inviterRewardClaimed shouldBe false
        } finally {
            cleanup(uid)
        }
    }

    "grantedReferrals counts only granted tickets, ignoring pending/expired" {
        val (uid, _) = seedUser()
        val granted = listOf(seedTicket(uid, "granted"), seedTicket(uid, "granted"))
        val pending = seedTicket(uid, "pending_activity")
        val expired = seedTicket(uid, "expired")
        try {
            val state = runBlocking { repository.getReferralState(uid) }
            state.grantedReferrals shouldBe 2
        } finally {
            cleanup(uid, *granted.toTypedArray(), pending, expired)
        }
    }

    "inviterRewardClaimed reflects the inviter_reward_claimed_at sentinel" {
        val (uid, _) = seedUser()
        setRewardClaimed(uid)
        try {
            runBlocking { repository.getReferralState(uid) }.inviterRewardClaimed shouldBe true
        } finally {
            cleanup(uid)
        }
    }
})
