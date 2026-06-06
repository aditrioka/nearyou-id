package id.nearyou.app.admin.rejectedidentifiers

import id.nearyou.app.admin.auth.AdminAuthTestSupport
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

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

    beforeEach { RejectedIdentifiersTestSupport.deleteAll(dataSource) }
    afterEach { RejectedIdentifiersTestSupport.deleteAll(dataSource) }

    val repo = AdminRejectedIdentifiersRepository(dataSource)
    val base = Instant.parse("2026-05-20T00:00:00Z")

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
})
