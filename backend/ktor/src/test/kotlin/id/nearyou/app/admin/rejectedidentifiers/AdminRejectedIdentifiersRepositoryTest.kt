package id.nearyou.app.admin.rejectedidentifiers

import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import id.nearyou.app.admin.ratelimit.RejectedIdentifierClearRateLimiter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Integration tests for [AdminRejectedIdentifiersRepository] — the
 * read/filter/paginate/summarize core of the `admin-rejected-identifiers-
 * viewer` capability (tasks.md Section 6).
 *
 * DB-backed; tagged `database` (runs against the CI service-container Postgres
 * / local dev compose). `rejected_identifiers` has no admin scoping column, so
 * each test starts from a [RejectedIdentifiersTestSupport.deleteAll] clean
 * slate (the table is test-owned in the integration DB). Asserts ordering,
 * keyset pagination (incl. the boundary + tiebreaker edge cases), filtering,
 * the UTC date-boundary convention, the count summary, injection safety, and
 * the empty-result path.
 *
 * No index-existence test (cf. the actions-log repo's V17 keyset-index check):
 * design.md D2 ships ZERO migrations — the read path is served by the existing
 * PK + `rejected_identifiers_hash_idx` over a low-cardinality table.
 */
@Tags("database")
class AdminRejectedIdentifiersRepositoryTest : StringSpec({

    val dataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    // Clear tests seed an admin + write admin_actions_log rows (audit + ledger);
    // those rows carry admin_id and are the real cross-spec pollution vector, so
    // clean them per-test by acting-admin id. NEVER autoClose the shared pool the
    // afterEach cleanup uses (it closes only in afterSpec).
    val seededAdmins = mutableListOf<UUID>()
    beforeEach { RejectedIdentifiersTestSupport.deleteAll(dataSource) }
    afterEach {
        RejectedIdentifiersTestSupport.deleteAll(dataSource)
        seededAdmins.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seededAdmins.clear()
    }

    val repo =
        AdminRejectedIdentifiersRepository(
            dataSource,
            AdminAuditLogger(dataSource),
            RejectedIdentifierClearRateLimiter(dataSource),
        )
    val base = Instant.parse("2026-05-20T00:00:00Z")

    fun newAdmin(): UUID = AdminAuthTestSupport.seedAdmin(dataSource).also { seededAdmins += it.id }.id

    fun seed(
        hash: String,
        type: String = "google",
        reason: String = "age_under_18",
        at: Instant = base,
    ) = RejectedIdentifiersTestSupport.seedRejectedIdentifier(dataSource, hash, type, reason, at)

    "6.2 — rows are returned newest-first (rejected_at DESC)" {
        seed("h-old", at = base)
        seed("h-mid", at = base.plusSeconds(60))
        seed("h-new", at = base.plusSeconds(120))

        val rows = repo.query(RejectedIdentifiersQuery()).rows

        rows.map { it.identifierHash } shouldContainExactly listOf("h-new", "h-mid", "h-old")
    }

    "6.3 — keyset: first page full + has-next; cursor returns strictly-older, non-overlapping rows" {
        repeat(5) { i -> seed("h$i", at = base.plusSeconds(i.toLong())) }

        val page1 = repo.query(RejectedIdentifiersQuery(pageSize = 2))
        page1.rows.size shouldBe 2
        page1.nextCursor.shouldNotBeNull()

        val page2 = repo.query(RejectedIdentifiersQuery(pageSize = 2, cursor = page1.nextCursor))
        // strictly older than the last row of page 1
        val p1LastAt = page1.rows.last().rejectedAt
        page2.rows.forEach { (it.rejectedAt <= p1LastAt) shouldBe true }
        // no overlap
        val p1Ids = page1.rows.map { it.id }.toSet()
        page2.rows.forEach { p1Ids.contains(it.id) shouldBe false }
    }

    "6.3a — exact page-size boundary: 50 rows => no next cursor; 51 rows => has next" {
        repeat(AdminRejectedIdentifiersRepository.PAGE_SIZE) { i ->
            seed("boundary-%04d".format(i), at = base.plusSeconds(i.toLong()))
        }
        val exact = repo.query(RejectedIdentifiersQuery())
        exact.rows.size shouldBe AdminRejectedIdentifiersRepository.PAGE_SIZE
        exact.nextCursor.shouldBeNull()

        // one more → 51 total
        seed("boundary-overflow", at = base.plusSeconds(1000))
        val overflow = repo.query(RejectedIdentifiersQuery())
        overflow.rows.size shouldBe AdminRejectedIdentifiersRepository.PAGE_SIZE
        overflow.nextCursor.shouldNotBeNull()
    }

    "6.3b — id tiebreaker on identical rejected_at: no row skipped or duplicated across the cursor" {
        val ts = base.plusSeconds(500)
        // three rows sharing an IDENTICAL rejected_at, distinct hashes (→ distinct ids)
        repeat(3) { i -> seed("collide-$i", at = ts) }

        val page1 = repo.query(RejectedIdentifiersQuery(pageSize = 2))
        page1.rows.size shouldBe 2
        page1.nextCursor.shouldNotBeNull()
        val page2 = repo.query(RejectedIdentifiersQuery(pageSize = 2, cursor = page1.nextCursor))
        page2.rows.size shouldBe 1

        val all = (page1.rows + page2.rows).map { it.id }
        all.toSet().size shouldBe 3 // no duplicates
    }

    "6.4 — filters narrow + compose with AND; ordering preserved within a filter" {
        seed("a-goog", type = "google", reason = "age_under_18", at = base.plusSeconds(1))
        seed("a-appl", type = "apple", reason = "age_under_18", at = base.plusSeconds(2))
        seed("att-goog", type = "google", reason = "attestation_persistent_fail", at = base.plusSeconds(3))

        // reason narrows
        repo.query(RejectedIdentifiersQuery(reason = "age_under_18")).rows
            .all { it.reason == "age_under_18" } shouldBe true

        // identifier_type narrows
        repo.query(RejectedIdentifiersQuery(identifierType = "apple")).rows
            .all { it.identifierType == "apple" } shouldBe true

        // AND composition: reason=age_under_18 AND identifier_type=google
        val anded = repo.query(RejectedIdentifiersQuery(reason = "age_under_18", identifierType = "google")).rows
        anded.size shouldBe 1
        anded.first().identifierHash shouldBe "a-goog"

        // ordering preserved within a filtered set (newest-first)
        val ageRows = repo.query(RejectedIdentifiersQuery(reason = "age_under_18")).rows
        ageRows.map { it.identifierHash } shouldContainExactly listOf("a-appl", "a-goog")
    }

    "6.5 — date range bounds rejected_at in UTC with inclusive whole-day upper bound (near-boundary row included)" {
        seed("before", at = Instant.parse("2026-05-20T12:00:00Z"))
        // Near the UTC day boundary: 23:30Z on the 25th IS within the UTC day
        // 2026-05-25 (a +07:00 WIB reading would wrongly push it to the 26th).
        seed("near-boundary", at = Instant.parse("2026-05-25T23:30:00Z"))
        seed("after", at = Instant.parse("2026-05-30T12:00:00Z"))

        val day = LocalDate.parse("2026-05-25")
        val q =
            RejectedIdentifiersQuery(
                fromInclusive = day.atStartOfDay(ZoneOffset.UTC).toInstant(),
                toExclusive = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
            )
        repo.query(q).rows.map { it.identifierHash } shouldContainExactly listOf("near-boundary")
    }

    "6.6 — summary: both reasons + both types non-zero when unfiltered" {
        seed("u1", type = "google", reason = "age_under_18", at = base.plusSeconds(1))
        seed("u2", type = "apple", reason = "attestation_persistent_fail", at = base.plusSeconds(2))

        val summary = repo.summary(RejectedIdentifiersQuery())
        summary.total shouldBe 2L
        (summary.byReason["age_under_18"] ?: 0L) shouldBe 1L
        (summary.byReason["attestation_persistent_fail"] ?: 0L) shouldBe 1L
        (summary.byType["google"] ?: 0L) shouldBe 1L
        (summary.byType["apple"] ?: 0L) shouldBe 1L
    }

    "6.6 — summary narrows to the filtered reason scope (excluded bucket is zero)" {
        seed("age1", reason = "age_under_18", at = base.plusSeconds(1))
        seed("age2", reason = "age_under_18", at = base.plusSeconds(2))
        seed("att1", reason = "attestation_persistent_fail", at = base.plusSeconds(3))

        val summary = repo.summary(RejectedIdentifiersQuery(reason = "age_under_18"))
        summary.total shouldBe 2L
        (summary.byReason["age_under_18"] ?: 0L) shouldBe 2L
        // attestation_persistent_fail excluded by the filter → absent / zero
        (summary.byReason["attestation_persistent_fail"] ?: 0L) shouldBe 0L
    }

    "6.6 — summary total counts the WHOLE filtered set, not just one page" {
        repeat(AdminRejectedIdentifiersRepository.PAGE_SIZE + 1) { i ->
            seed("whole-%04d".format(i), reason = "age_under_18", at = base.plusSeconds(i.toLong()))
        }
        val q = RejectedIdentifiersQuery(reason = "age_under_18")
        // page query caps at PAGE_SIZE …
        repo.query(q).rows.size shouldBe AdminRejectedIdentifiersRepository.PAGE_SIZE
        // … but the summary counts all 51 matching rows.
        repo.summary(q).total shouldBe (AdminRejectedIdentifiersRepository.PAGE_SIZE + 1).toLong()
    }

    "6.5 — SQL-metacharacter reason is bound as a literal (0 rows, no error, table survives)" {
        seed("h1", reason = "age_under_18", at = base)

        val injection =
            repo.query(RejectedIdentifiersQuery(reason = "'; DROP TABLE rejected_identifiers;--"))
        injection.rows.size shouldBe 0

        // table still exists + queryable
        repo.query(RejectedIdentifiersQuery()).rows.size shouldBe 1
    }

    "6.12 — empty result returns an empty page, not an error" {
        val page = repo.query(RejectedIdentifiersQuery(reason = "attestation_persistent_fail"))
        page.rows.size shouldBe 0
        page.nextCursor.shouldBeNull()
        repo.summary(RejectedIdentifiersQuery(reason = "attestation_persistent_fail")).total shouldBe 0L
    }

    // ---- Clear (admin-rejected-identifiers-clear-action) --------------------

    "clear — removes the row + writes one audit row with full before_state + null after_state" {
        val admin = newAdmin()
        val id = seed("clear-hash", type = "google", reason = "age_under_18", at = base)

        repo.clear(id, admin, "legitimate adult re-verify", "127.0.0.1", null) shouldBe ClearOutcome.Cleared

        RejectedIdentifiersTestSupport.existsById(dataSource, id) shouldBe false
        val rows = AdminAuthTestSupport.latestAuditRows(dataSource, admin)
        rows shouldHaveSize 1
        rows[0].actionType shouldBe "rejected_identifier_cleared"
        rows[0].targetType shouldBe "rejected_identifier"
        rows[0].reason shouldBe "legitimate adult re-verify"
        // before_state captures all four cleared-row fields
        rows[0].beforeState!! shouldContain "clear-hash"
        rows[0].beforeState!! shouldContain "google"
        rows[0].beforeState!! shouldContain "age_under_18"
        rows[0].beforeState!! shouldContain "rejected_at"
        // after_state is null — the row is gone; the audit trail is the record
        rows[0].afterState.shouldBeNull()
    }

    "clear — nonexistent id is a graceful no-op: no delete, no audit row, no quota burn" {
        val admin = newAdmin()
        seed("survivor", at = base)

        repo.clear(UUID.randomUUID(), admin, "x", "127.0.0.1", null) shouldBe ClearOutcome.NotFound

        RejectedIdentifiersTestSupport.count(dataSource) shouldBe 1
        AdminAuthTestSupport.latestAuditRows(dataSource, admin) shouldHaveSize 0
    }

    "clear — at the 10/hr cap → RateLimited: the row remains, no new audit row" {
        val admin = newAdmin()
        repeat(10) { RejectedIdentifiersTestSupport.seedClearAudit(dataSource, admin) }
        val id = seed("capped-hash", at = base)

        repo.clear(id, admin, "x", "127.0.0.1", null) shouldBe ClearOutcome.RateLimited

        RejectedIdentifiersTestSupport.existsById(dataSource, id) shouldBe true
        AdminAuthTestSupport.latestAuditRows(dataSource, admin) shouldHaveSize 10 // the 10 seeded; no new one
    }

    "clear — atomic: an audit-write failure (FK on a nonexistent admin) rolls back the delete" {
        // A random acting-admin id is absent from admin_users, so the audit INSERT
        // (admin_id NOT NULL REFERENCES admin_users) fails → the whole tx rolls
        // back → the DELETE is undone and the row survives.
        val id = seed("rollback-hash", at = base)

        shouldThrow<Exception> {
            repo.clear(id, UUID.randomUUID(), "x", "127.0.0.1", null)
        }

        RejectedIdentifiersTestSupport.existsById(dataSource, id) shouldBe true
    }

    "clear — a cleared identifier can be re-rejected (UNIQUE allows re-insert of the same hash+type)" {
        val admin = newAdmin()
        val id = seed("reusable-hash", type = "google", reason = "age_under_18", at = base)

        repo.clear(id, admin, "x", "127.0.0.1", null) shouldBe ClearOutcome.Cleared

        // Re-insert the SAME (hash, type) — succeeds because the prior row is gone
        // (the UNIQUE(identifier_hash, identifier_type) no longer conflicts).
        val reId =
            RejectedIdentifiersTestSupport.seedRejectedIdentifier(
                dataSource,
                "reusable-hash",
                "google",
                "age_under_18",
                base.plusSeconds(60),
            )
        RejectedIdentifiersTestSupport.existsById(dataSource, reId) shouldBe true
    }
})
