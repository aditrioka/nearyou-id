package id.nearyou.app.referral

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.core.domain.ratelimit.InMemoryRateLimiter
import id.nearyou.app.infra.repo.JdbcUserRepository
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.sql.Date
import java.sql.Timestamp
import java.sql.Types
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live-DB tests for referral ticket creation at signup (the service layer).
 * Requires the dev Postgres (V23 applied). Tagged `database` so PR CI excludes
 * it by default. Signup-endpoint integration lives in `SignupFlowTest`.
 */
@Tags("database")
class ReferralServiceTest : StringSpec({

    val dataSource = hikari()
    // Fixed "now" for the service age-gate clock; inviters are seeded relative to it.
    val nowT: Instant = Instant.parse("2026-06-01T00:00:00Z")
    val serviceClock: Clock = Clock.fixed(nowT, ZoneId.of("UTC"))
    val seq = AtomicInteger(0)

    fun cleanSlate() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("DELETE FROM referral_tickets")
                st.executeUpdate("DELETE FROM refresh_tokens")
                st.executeUpdate("DELETE FROM chat_messages")
                st.executeUpdate("DELETE FROM conversation_participants")
                st.executeUpdate("DELETE FROM conversations")
                st.executeUpdate("DELETE FROM users")
            }
        }
    }

    fun uniquePrefix(): String = "p%07d".format(seq.incrementAndGet())

    /** Seed a `users` row with explicit control of the referral-relevant columns. */
    fun seedUser(
        id: UUID = UUID.randomUUID(),
        inviteCodePrefix: String = uniquePrefix(),
        createdAt: Instant = nowT.minus(Duration.ofDays(60)),
        isBanned: Boolean = false,
        deviceFingerprintHash: String? = null,
        deletedAt: Instant? = null,
    ): UUID {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix,
                                   is_banned, device_fingerprint_hash, created_at, deleted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "u_${seq.incrementAndGet()}_${id.toString().take(6)}")
                ps.setString(3, "Seed User")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, inviteCodePrefix)
                ps.setBoolean(6, isBanned)
                if (deviceFingerprintHash != null) ps.setString(7, deviceFingerprintHash) else ps.setNull(7, Types.VARCHAR)
                ps.setTimestamp(8, Timestamp.from(createdAt))
                if (deletedAt != null) ps.setTimestamp(9, Timestamp.from(deletedAt)) else ps.setNull(9, Types.TIMESTAMP)
                ps.executeUpdate()
            }
        }
        return id
    }

    fun build(
        limiter: InMemoryRateLimiter = InMemoryRateLimiter(),
        clock: Clock = serviceClock,
    ): ReferralService =
        ReferralService(
            users = JdbcUserRepository(dataSource),
            referrals = ReferralRepository(dataSource),
            rateLimiter = ReferralTicketRateLimiter(rateLimiter = limiter),
            dbDispatcher = kotlinx.coroutines.Dispatchers.IO,
            clock = clock,
        )

    fun ticketCount(invitee: UUID): Int {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM referral_tickets WHERE invitee_user_id = ?").use { ps ->
                ps.setObject(1, invitee)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
    }

    beforeEach { cleanSlate() }
    afterSpec { dataSource.close() }

    "valid invite code creates a pending_activity ticket with expires_at = created_at + 14 days" {
        val inviter = seedUser(inviteCodePrefix = "invtr001")
        val invitee = seedUser()
        build().createTicketIfInvited("invtr001", invitee, inviteeDeviceFingerprintHash = "fp-invitee")

        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT inviter_user_id, status, created_at, expires_at FROM referral_tickets WHERE invitee_user_id = ?",
            ).use { ps ->
                ps.setObject(1, invitee)
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    rs.getObject("inviter_user_id", UUID::class.java) shouldBe inviter
                    rs.getString("status") shouldBe "pending_activity"
                    val created = rs.getTimestamp("created_at").toInstant()
                    val expires = rs.getTimestamp("expires_at").toInstant()
                    Duration.between(created, expires) shouldBe Duration.ofDays(14)
                }
            }
        }
    }

    "absent invite code creates no ticket" {
        val invitee = seedUser()
        build().createTicketIfInvited(inviteCode = null, inviteeUserId = invitee, inviteeDeviceFingerprintHash = null)
        ticketCount(invitee) shouldBe 0
    }

    "blank invite code creates no ticket" {
        val invitee = seedUser()
        build().createTicketIfInvited(inviteCode = "   ", inviteeUserId = invitee, inviteeDeviceFingerprintHash = null)
        ticketCount(invitee) shouldBe 0
    }

    "unresolvable invite code creates no ticket" {
        val invitee = seedUser()
        build().createTicketIfInvited("nosuchcd", invitee, inviteeDeviceFingerprintHash = null)
        ticketCount(invitee) shouldBe 0
    }

    "soft-deleted inviter creates no ticket" {
        seedUser(inviteCodePrefix = "delinv01", deletedAt = nowT.minus(Duration.ofDays(1)))
        val invitee = seedUser()
        build().createTicketIfInvited("delinv01", invitee, inviteeDeviceFingerprintHash = null)
        ticketCount(invitee) shouldBe 0
    }

    "banned inviter creates no ticket" {
        seedUser(inviteCodePrefix = "baninv01", isBanned = true)
        val invitee = seedUser()
        build().createTicketIfInvited("baninv01", invitee, inviteeDeviceFingerprintHash = null)
        ticketCount(invitee) shouldBe 0
    }

    "inviter younger than 30 days creates no ticket" {
        seedUser(inviteCodePrefix = "newinv01", createdAt = nowT.minus(Duration.ofDays(10)))
        val invitee = seedUser()
        build().createTicketIfInvited("newinv01", invitee, inviteeDeviceFingerprintHash = null)
        ticketCount(invitee) shouldBe 0
    }

    "self-invite creates no ticket" {
        // The inviter resolves to the same id passed as the invitee.
        val self = seedUser(inviteCodePrefix = "selfinv1")
        build().createTicketIfInvited("selfinv1", inviteeUserId = self, inviteeDeviceFingerprintHash = null)
        ticketCount(self) shouldBe 0
    }

    "device fingerprint collision creates no ticket" {
        seedUser(inviteCodePrefix = "fpcol001", deviceFingerprintHash = "shared-fp")
        val invitee = seedUser()
        build().createTicketIfInvited("fpcol001", invitee, inviteeDeviceFingerprintHash = "shared-fp")
        ticketCount(invitee) shouldBe 0
    }

    "null invitee fingerprint is not treated as a collision" {
        seedUser(inviteCodePrefix = "fpnull01", deviceFingerprintHash = "inviter-fp")
        val invitee = seedUser()
        build().createTicketIfInvited("fpnull01", invitee, inviteeDeviceFingerprintHash = null)
        ticketCount(invitee) shouldBe 1
    }

    "burst boundary: 3rd ticket succeeds, 4th within the window is rejected" {
        val inviter = seedUser(inviteCodePrefix = "burst001")
        val svc = build() // shared InMemoryRateLimiter, cap 3
        val invitees = (1..4).map { seedUser() }
        invitees.forEach { svc.createTicketIfInvited("burst001", it, inviteeDeviceFingerprintHash = null) }

        // First three create tickets; the fourth is burst-rejected.
        ticketCount(invitees[0]) shouldBe 1
        ticketCount(invitees[1]) shouldBe 1
        ticketCount(invitees[2]) shouldBe 1
        ticketCount(invitees[3]) shouldBe 0
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM referral_tickets WHERE inviter_user_id = ?").use { ps ->
                ps.setObject(1, inviter)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) shouldBe 3
                }
            }
        }
    }

    "rolling window: after 7 days elapse a new ticket is allowed" {
        seedUser(inviteCodePrefix = "window01")
        var clockInstant = Instant.parse("2026-06-01T00:00:00Z")
        val limiter = InMemoryRateLimiter(clock = { clockInstant })
        val svc = build(limiter = limiter)

        val first3 = (1..3).map { seedUser() }
        first3.forEach { svc.createTicketIfInvited("window01", it, inviteeDeviceFingerprintHash = null) }
        val blocked = seedUser()
        svc.createTicketIfInvited("window01", blocked, inviteeDeviceFingerprintHash = null)
        ticketCount(blocked) shouldBe 0 // 4th within window blocked

        clockInstant = clockInstant.plus(Duration.ofDays(8)) // age the window out
        val afterWindow = seedUser()
        svc.createTicketIfInvited("window01", afterWindow, inviteeDeviceFingerprintHash = null)
        ticketCount(afterWindow) shouldBe 1
    }

    "duplicate invitee is swallowed (idempotent, no throw)" {
        seedUser(inviteCodePrefix = "dup00001")
        val invitee = seedUser()
        val svc = build()
        svc.createTicketIfInvited("dup00001", invitee, inviteeDeviceFingerprintHash = null)
        // A second attempt for the same invitee hits UNIQUE(invitee_user_id) and is swallowed.
        svc.createTicketIfInvited("dup00001", invitee, inviteeDeviceFingerprintHash = null)
        ticketCount(invitee) shouldBe 1
    }

    "hard-deleting the inviter cascades the ticket away" {
        val inviter = seedUser(inviteCodePrefix = "casc0001")
        val invitee = seedUser()
        build().createTicketIfInvited("casc0001", invitee, inviteeDeviceFingerprintHash = null)
        ticketCount(invitee) shouldBe 1
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, inviter)
                ps.executeUpdate()
            }
        }
        ticketCount(invitee) shouldBe 0
    }

    "hard-deleting the invitee cascades the ticket away" {
        seedUser(inviteCodePrefix = "casc0002")
        val invitee = seedUser()
        build().createTicketIfInvited("casc0002", invitee, inviteeDeviceFingerprintHash = null)
        ticketCount(invitee) shouldBe 1
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, invitee)
                ps.executeUpdate()
            }
        }
        ticketCount(invitee) shouldBe 0
    }

    "inviter resolution uses the invite_code_prefix index, not a seq scan" {
        // Postgres picks Seq Scan on tiny tables regardless of indexes, so disable
        // seqscan for this check — the point (docs/08 'Invite code prefix lookup
        // test') is that the query CAN use the unique index.
        seedUser(inviteCodePrefix = "explain1")
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.createStatement().use { it.execute("SET LOCAL enable_seqscan = off") }
                val plan = StringBuilder()
                conn.prepareStatement(
                    "EXPLAIN SELECT id, is_banned, created_at, device_fingerprint_hash " +
                        "FROM users WHERE invite_code_prefix = ? AND deleted_at IS NULL",
                ).use { ps ->
                    ps.setString(1, "explain1")
                    ps.executeQuery().use { rs -> while (rs.next()) plan.appendLine(rs.getString(1)) }
                }
                val planText = plan.toString()
                planText shouldContain "Index"
                planText shouldNotContain "Seq Scan"
            } finally {
                conn.rollback()
                conn.autoCommit = true
            }
        }
    }
})

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
