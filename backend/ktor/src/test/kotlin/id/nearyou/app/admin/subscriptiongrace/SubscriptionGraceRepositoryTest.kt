package id.nearyou.app.admin.subscriptiongrace

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import id.nearyou.app.admin.ratelimit.GraceExpediteActionRateLimiter
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Instant
import java.util.UUID

/**
 * Repository-level integration tests for `SubscriptionGraceRepository` — the
 * keyset pagination, the current-streak `retry_since` derivation (design D6),
 * and the count summary, which the fixed-page-size route cannot exercise
 * directly. DB-backed; tagged `database`.
 */
@Tags("database")
class SubscriptionGraceRepositoryTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val repo = SubscriptionGraceRepository(dataSource, AdminAuditLogger(dataSource), GraceExpediteActionRateLimiter(dataSource))

    val seeded = mutableListOf<UUID>()

    fun seedUser(
        username: String,
        status: String = STATUS_BILLING_RETRY,
    ): UUID = GraceTestSupport.seedUser(dataSource, username, status).also { seeded.add(it) }
    afterEach {
        seeded.forEach { GraceTestSupport.deleteUser(dataSource, it) }
        seeded.clear()
    }

    "keyset pagination yields every row once across the page boundary (no drop / no dup)" {
        // Three billing-retry users with distinct latest-event times → distinct
        // sort_at; DESC order is u3, u2, u1.
        val u1 = seedUser("page_u1")
        GraceTestSupport.seedEvent(dataSource, u1, "billing_issue", createdAt = Instant.parse("2026-06-01T01:00:00Z"))
        val u2 = seedUser("page_u2")
        GraceTestSupport.seedEvent(dataSource, u2, "billing_issue", createdAt = Instant.parse("2026-06-02T01:00:00Z"))
        val u3 = seedUser("page_u3")
        GraceTestSupport.seedEvent(dataSource, u3, "billing_issue", createdAt = Instant.parse("2026-06-03T01:00:00Z"))

        // Page through ALL pages with pageSize=2 (robust to any sibling
        // billing-retry rows): collect every row id, then assert the no-drop /
        // no-dup contract over the THREE ids this test seeded.
        val firstPage = repo.query(GraceQuery(pageSize = 2))
        firstPage.rows.size shouldBe 2 // a full first page (≥3 rows exist)
        firstPage.nextCursor shouldNotBe null // a further page exists

        val allIds = mutableListOf<UUID>()
        var cursor = firstPage.nextCursor
        allIds += firstPage.rows.map { it.id }
        var guard = 0
        while (cursor != null && guard++ < 100) {
            val p = repo.query(GraceQuery(pageSize = 2, cursor = cursor))
            allIds += p.rows.map { it.id }
            cursor = p.nextCursor
        }
        val seededSet = setOf(u1, u2, u3)
        // Each seeded id appears EXACTLY once across the page boundaries.
        seededSet.forEach { id -> allIds.count { it == id } shouldBe 1 }
        // And no id at all is duplicated across pages (keyset stability).
        allIds.size shouldBe allIds.toSet().size
    }

    "retry_since is the earliest billing_issue in the CURRENT streak (after the last success)" {
        val u = seedUser("streak_user")
        GraceTestSupport.seedEvent(dataSource, u, "initial_purchase", createdAt = Instant.parse("2026-05-20T00:00:00Z"))
        GraceTestSupport.seedEvent(dataSource, u, "renewal", createdAt = Instant.parse("2026-06-01T00:00:00Z"))
        GraceTestSupport.seedEvent(dataSource, u, "billing_issue", createdAt = Instant.parse("2026-06-05T00:00:00Z"))
        GraceTestSupport.seedEvent(dataSource, u, "billing_issue", createdAt = Instant.parse("2026-06-06T00:00:00Z"))

        val row = repo.query(GraceQuery(qUserId = u)).rows.single()
        // retry-since = the streak start (first billing_issue after the renewal),
        // NOT the latest event.
        row.retrySince shouldBe Instant.parse("2026-06-05T00:00:00Z")
        row.lastEventAt shouldBe Instant.parse("2026-06-06T00:00:00Z")
        row.lastEventType shouldBe "billing_issue"
    }

    "summary counts the billing-retry population independent of the page limit" {
        // Delta-based (robust to any pre-existing billing-retry rows in a shared dev DB).
        val before = repo.summary(GraceQuery())
        seedUser("sum_a")
        seedUser("sum_b")
        seedUser("sum_c")
        seedUser("sum_active", status = "premium_active") // excluded

        // pageSize 1 → the page query returns 1 row, but the summary counts all
        // billing-retry rows: baseline + the 3 seeded (the active one excluded).
        repo.summary(GraceQuery(pageSize = 1)) shouldBe before + 3
    }
})
