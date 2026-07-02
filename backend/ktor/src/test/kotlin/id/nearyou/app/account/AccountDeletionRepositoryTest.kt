package id.nearyou.app.account

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import java.sql.Date
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

private fun hikari(): HikariDataSource {
    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val config =
        HikariConfig().apply {
            jdbcUrl = url
            username = System.getenv("DB_USER") ?: "postgres"
            password = System.getenv("DB_PASSWORD") ?: "postgres"
            maximumPoolSize = 2
            initializationFailTimeout = -1
        }
    return HikariDataSource(config)
}

@Tags("database")
class AccountDeletionRepositoryTest : StringSpec({

    // autoClose releases the pool after the spec (CI connection-budget: don't leak a HikariPool).
    val dataSource = autoClose(hikari())
    val repo = AccountDeletionRepository(dataSource)

    fun seedUser(): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix) VALUES (?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "del_$short")
                ps.setString(3, "Del Test")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "d${short.take(7)}")
                ps.executeUpdate()
            }
        }
        return id
    }

    fun pendingCount(userId: UUID): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM deletion_requests WHERE user_id = ? AND cancelled_at IS NULL AND executed_at IS NULL",
            ).use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
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
                conn.prepareStatement("DELETE FROM users WHERE id = ?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
            }
        }
    }

    "request schedules deletion ~30 days out as source='user'" {
        val uid = seedUser()
        try {
            val scheduled = runBlocking { repo.requestDeletion(uid) }
            val expected = Instant.now().plus(30, ChronoUnit.DAYS)
            (Duration.between(scheduled, expected).abs().toHours() < 2) shouldBe true
            pendingCount(uid) shouldBe 1
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT source FROM deletion_requests WHERE user_id = ?").use { ps ->
                    ps.setObject(1, uid)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getString("source") shouldBe "user"
                    }
                }
            }
        } finally {
            cleanup(uid)
        }
    }

    "request is idempotent — re-request returns the same schedule, no second row" {
        val uid = seedUser()
        try {
            val first = runBlocking { repo.requestDeletion(uid) }
            val second = runBlocking { repo.requestDeletion(uid) }
            second shouldBe first
            pendingCount(uid) shouldBe 1
        } finally {
            cleanup(uid)
        }
    }

    "request after cancellation creates a new pending row" {
        val uid = seedUser()
        try {
            runBlocking { repo.requestDeletion(uid) }
            runBlocking { repo.cancelDeletion(uid) } shouldBe true
            pendingCount(uid) shouldBe 0
            runBlocking { repo.requestDeletion(uid) }
            pendingCount(uid) shouldBe 1 // a NEW pending row (the cancelled one is excluded)
        } finally {
            cleanup(uid)
        }
    }

    "cancel within grace sets cancelled_at and returns true" {
        val uid = seedUser()
        try {
            runBlocking { repo.requestDeletion(uid) }
            runBlocking { repo.cancelDeletion(uid) } shouldBe true
            pendingCount(uid) shouldBe 0
        } finally {
            cleanup(uid)
        }
    }

    "cancel returns false when nothing is pending" {
        val uid = seedUser()
        try {
            runBlocking { repo.cancelDeletion(uid) } shouldBe false
        } finally {
            cleanup(uid)
        }
    }

    "cancel does not touch an apple_s2s_account_delete row" {
        val uid = seedUser()
        try {
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "INSERT INTO deletion_requests (user_id, scheduled_hard_delete_at, source) " +
                        "VALUES (?, NOW(), 'apple_s2s_account_delete')",
                ).use {
                    it.setObject(1, uid)
                    it.executeUpdate()
                }
            }
            runBlocking { repo.cancelDeletion(uid) } shouldBe false // non-cancellable source
        } finally {
            cleanup(uid)
        }
    }

    // apple-s2s-deletion-flows: scheduleConsentRevoked (2.1) — 30-day grace, idempotent.
    "scheduleConsentRevoked schedules ~30 days out as apple_s2s_consent_revoked, idempotent against a pending row" {
        val uid = seedUser()
        try {
            runBlocking { repo.scheduleConsentRevoked(uid) } shouldBe true // fresh insert
            pendingCount(uid) shouldBe 1
            val scheduled =
                dataSource.connection.use { conn ->
                    conn.prepareStatement("SELECT scheduled_hard_delete_at, source FROM deletion_requests WHERE user_id = ?").use { ps ->
                        ps.setObject(1, uid)
                        ps.executeQuery().use { rs ->
                            rs.next()
                            rs.getString("source") shouldBe "apple_s2s_consent_revoked"
                            rs.getTimestamp("scheduled_hard_delete_at").toInstant()
                        }
                    }
                }
            val expected = Instant.now().plus(30, ChronoUnit.DAYS)
            (Duration.between(scheduled, expected).abs().toHours() < 2) shouldBe true
            // second receipt: a pending row exists → no second row (the insert no-ops).
            runBlocking { repo.scheduleConsentRevoked(uid) } shouldBe false
            pendingCount(uid) shouldBe 1
        } finally {
            cleanup(uid)
        }
    }

    // apple-s2s-deletion-flows: scheduleAppleAccountDelete (2.2 / D7) — immediate, NO pending-row guard.
    "scheduleAppleAccountDelete always inserts an immediate row even when a grace row is pending" {
        val uid = seedUser()
        try {
            runBlocking { repo.requestDeletion(uid) } // a pending 'user' grace row
            pendingCount(uid) shouldBe 1
            val rowId = runBlocking { repo.scheduleAppleAccountDelete(uid) }
            (rowId != null) shouldBe true // unconditional insert returns the new id
            pendingCount(uid) shouldBe 2 // the immediate row is ADDED, not suppressed
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "SELECT source, scheduled_hard_delete_at FROM deletion_requests WHERE id = ?",
                ).use { ps ->
                    ps.setObject(1, rowId)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getString("source") shouldBe "apple_s2s_account_delete"
                        val scheduledAt = rs.getTimestamp("scheduled_hard_delete_at").toInstant()
                        (Duration.between(scheduledAt, Instant.now()).abs().toMinutes() < 5) shouldBe true
                    }
                }
            }
        } finally {
            cleanup(uid)
        }
    }

    // apple-s2s-deletion-flows (2.3): consent-revoked is a CANCELLABLE source (cancel guard excludes only account-delete).
    "cancel restores an apple_s2s_consent_revoked row (cancellable source)" {
        val uid = seedUser()
        try {
            runBlocking { repo.scheduleConsentRevoked(uid) } shouldBe true
            runBlocking { repo.cancelDeletion(uid) } shouldBe true // consent-revoked is NOT excluded
            pendingCount(uid) shouldBe 0
        } finally {
            cleanup(uid)
        }
    }

    "status reflects pending then absent after cancel" {
        val uid = seedUser()
        try {
            runBlocking { repo.getStatus(uid) } shouldBe null
            val scheduled = runBlocking { repo.requestDeletion(uid) }
            runBlocking { repo.getStatus(uid) } shouldBe scheduled
            runBlocking { repo.cancelDeletion(uid) }
            runBlocking { repo.getStatus(uid) } shouldBe null
        } finally {
            cleanup(uid)
        }
    }

    "source CHECK rejects an unknown value but accepts the four canonical ones" {
        val uid = seedUser()
        try {
            shouldThrow<Exception> {
                dataSource.connection.use { conn ->
                    conn.prepareStatement(
                        "INSERT INTO deletion_requests (user_id, scheduled_hard_delete_at, source) VALUES (?, NOW(), 'gdpr_export')",
                    ).use {
                        it.setObject(1, uid)
                        it.executeUpdate()
                    }
                }
            }
            listOf("user", "apple_s2s_consent_revoked", "apple_s2s_account_delete", "admin").forEach { src ->
                dataSource.connection.use { conn ->
                    conn.prepareStatement(
                        "INSERT INTO deletion_requests (user_id, scheduled_hard_delete_at, source) VALUES (?, NOW(), ?)",
                    ).use {
                        it.setObject(1, uid)
                        it.setString(2, src)
                        it.executeUpdate()
                    }
                }
            }
        } finally {
            cleanup(uid)
        }
    }

    "schema: partial indexes exist and are NOW()-free; deletion_log has no FK on user_id" {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT indexdef FROM pg_indexes WHERE indexname IN ('deletion_requests_scheduled_idx','deletion_requests_immediate_idx')",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val defs = buildList { while (rs.next()) add(rs.getString("indexdef")) }
                    defs.size shouldBe 2
                    defs.none { it.contains("now()", ignoreCase = true) } shouldBe true
                }
            }
            // deletion_log.user_id must carry no foreign key (survives a future row-purge).
            conn.prepareStatement(
                """
                SELECT COUNT(*) FROM information_schema.table_constraints tc
                  JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name
                 WHERE tc.table_name = 'deletion_log' AND tc.constraint_type = 'FOREIGN KEY' AND kcu.column_name = 'user_id'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) shouldBe 0
                }
            }
        }
        // sanity: pendingCount helper used above resolves; keep a non-null assertion to anchor the spec.
        repo shouldNotBe null
    }
})
