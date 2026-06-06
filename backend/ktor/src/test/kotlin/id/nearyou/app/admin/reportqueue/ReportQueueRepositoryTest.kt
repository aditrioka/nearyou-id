package id.nearyou.app.admin.reportqueue

import id.nearyou.app.admin.auth.AdminAuthTestSupport
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Integration tests for [ReportQueueRepository] — the read/filter/paginate/
 * join core of the `admin-report-queue` capability (tasks.md Section 5,
 * data-layer half).
 *
 * DB-backed; tagged `database`. The report queue is a GLOBAL list (no
 * per-reporter filter knob — a moderator sees every report), so — unlike the
 * `admin-actions-log-viewer` repo test which isolates by `adminId` — each test
 * here seeds its rows at a DISJOINT, far-future `created_at` window and bounds
 * the query by that window so counts / ordering / keyset boundaries are
 * deterministic regardless of pre-existing `reports` rows. Membership
 * assertions additionally filter to the seeded report ids.
 */
@Tags("database")
class ReportQueueRepositoryTest : StringSpec({

    val dataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val seeded = mutableListOf<UUID>()
    afterEach {
        ReportQueueTestSupport.cleanup(dataSource, seeded)
        seeded.clear()
    }

    val repo = ReportQueueRepository(dataSource)

    fun user(username: String? = null): UUID = ReportQueueTestSupport.seedUser(dataSource, username).also { seeded.add(it) }

    /** A query bounded to a [base, base + days) UTC window for test isolation. */
    fun windowQuery(
        base: Instant,
        days: Long = 1,
        pageSize: Int = ReportQueueRepository.PAGE_SIZE,
        cursor: id.nearyou.app.admin.actionslog.ActionLogCursor? = null,
        status: String? = null,
        targetType: String? = null,
        reasonCategory: String? = null,
        trigger: String? = null,
    ) = ReportQueueQuery(
        status = status,
        targetType = targetType,
        reasonCategory = reasonCategory,
        trigger = trigger,
        fromInclusive = base,
        toExclusive = base.plus(java.time.Duration.ofDays(days)),
        cursor = cursor,
        pageSize = pageSize,
    )

    "5.3 — rows are returned newest-first (created_at DESC)" {
        val reporter = user()
        val base = Instant.parse("2090-01-01T00:00:00Z")
        ReportQueueTestSupport.seedReport(dataSource, reporter, "user", user(), base, reasonNote = "r-old")
        ReportQueueTestSupport.seedReport(dataSource, reporter, "user", user(), base.plusSeconds(60), reasonNote = "r-mid")
        ReportQueueTestSupport.seedReport(dataSource, reporter, "user", user(), base.plusSeconds(120), reasonNote = "r-new")

        val rows = repo.query(windowQuery(base)).rows
        rows.map { it.reasonNote } shouldBe listOf("r-new", "r-mid", "r-old")
    }

    "5.5 — keyset: first page full + has-next; cursor returns strictly-older, non-overlapping rows" {
        val reporter = user()
        val base = Instant.parse("2090-02-01T00:00:00Z")
        repeat(5) { i ->
            ReportQueueTestSupport.seedReport(dataSource, reporter, "user", user(), base.plusSeconds(i.toLong()))
        }

        val page1 = repo.query(windowQuery(base, pageSize = 2))
        page1.rows.size shouldBe 2
        page1.nextCursor.shouldNotBeNull()

        val page2 = repo.query(windowQuery(base, pageSize = 2, cursor = page1.nextCursor))
        val p1LastCreated = page1.rows.last().createdAt
        page2.rows.forEach { (it.createdAt <= p1LastCreated) shouldBe true }
        val p1Ids = page1.rows.map { it.id }.toSet()
        page2.rows.forEach { p1Ids.contains(it.id) shouldBe false }
    }

    "5.17 — exact page-size boundary: N rows => no next cursor; N+1 rows => has next" {
        val reporter = user()
        val base = Instant.parse("2090-03-01T00:00:00Z")
        val size = 3
        repeat(size) { i ->
            ReportQueueTestSupport.seedReport(dataSource, reporter, "user", user(), base.plusSeconds(i.toLong()))
        }
        val exact = repo.query(windowQuery(base, pageSize = size))
        exact.rows.size shouldBe size
        exact.nextCursor.shouldBeNull()

        ReportQueueTestSupport.seedReport(dataSource, reporter, "user", user(), base.plusSeconds(1000))
        val overflow = repo.query(windowQuery(base, pageSize = size))
        overflow.rows.size shouldBe size
        overflow.nextCursor.shouldNotBeNull()
    }

    "5.17b — id tiebreaker on identical created_at: no row skipped or duplicated across the cursor" {
        val reporter = user()
        val base = Instant.parse("2090-04-01T00:00:00Z")
        val ts = base.plusSeconds(10)
        repeat(3) { ReportQueueTestSupport.seedReport(dataSource, reporter, "user", user(), ts) }

        val page1 = repo.query(windowQuery(base, pageSize = 2))
        page1.rows.size shouldBe 2
        page1.nextCursor.shouldNotBeNull()
        val page2 = repo.query(windowQuery(base, pageSize = 2, cursor = page1.nextCursor))
        page2.rows.size shouldBe 1

        val all = (page1.rows + page2.rows).map { it.id }
        all.toSet().size shouldBe 3 // no duplicates, none skipped
    }

    "5.17c — pagination composes with a status filter (cursor stays filtered + non-overlapping)" {
        val reporter = user()
        val base = Instant.parse("2090-04-15T00:00:00Z")
        // 3 pending (newest→oldest) interleaved with a dismissed row that must never surface.
        ReportQueueTestSupport.seedReport(dataSource, reporter, "user", user(), base.plusSeconds(1), status = "pending", reasonNote = "p-1")
        ReportQueueTestSupport.seedReport(
            dataSource,
            reporter,
            "user",
            user(),
            base.plusSeconds(2),
            status = "dismissed",
            reasonNote = "d-x",
        )
        ReportQueueTestSupport.seedReport(dataSource, reporter, "user", user(), base.plusSeconds(3), status = "pending", reasonNote = "p-2")
        ReportQueueTestSupport.seedReport(dataSource, reporter, "user", user(), base.plusSeconds(4), status = "pending", reasonNote = "p-3")

        val page1 = repo.query(windowQuery(base, pageSize = 2, status = "pending"))
        page1.rows.map { it.reasonNote } shouldBe listOf("p-3", "p-2")
        page1.rows.all { it.status == "pending" } shouldBe true
        page1.nextCursor.shouldNotBeNull()

        val page2 = repo.query(windowQuery(base, pageSize = 2, status = "pending", cursor = page1.nextCursor))
        page2.rows.map { it.reasonNote } shouldBe listOf("p-1") // strictly older, still filtered, no overlap, dismissed excluded
        page2.rows.all { it.status == "pending" } shouldBe true
    }

    "5.6 — filters narrow + compose with AND (status / target_type / reason_category)" {
        val reporter = user()
        val base = Instant.parse("2090-05-01T00:00:00Z")
        ReportQueueTestSupport.seedReport(
            dataSource,
            reporter,
            "post",
            user(),
            base.plusSeconds(1),
            reasonCategory = "spam",
            status = "pending",
        )
        ReportQueueTestSupport.seedReport(
            dataSource,
            reporter,
            "user",
            user(),
            base.plusSeconds(2),
            reasonCategory = "harassment",
            status = "pending",
        )
        ReportQueueTestSupport.seedReport(
            dataSource,
            reporter,
            "post",
            user(),
            base.plusSeconds(3),
            reasonCategory = "spam",
            status = "dismissed",
        )

        repo.query(windowQuery(base, status = "pending")).rows.all { it.status == "pending" } shouldBe true
        repo.query(windowQuery(base, targetType = "post")).rows.all { it.targetType == "post" } shouldBe true
        repo.query(windowQuery(base, reasonCategory = "spam")).rows.all { it.reasonCategory == "spam" } shouldBe true

        // AND composition: status=pending AND target_type=post → only the first report
        val anded = repo.query(windowQuery(base, status = "pending", targetType = "post")).rows
        anded.size shouldBe 1
        anded.single().reasonCategory shouldBe "spam"
    }

    "5.6b — date range bounds created_at with inclusive whole-day upper bound (tz-deterministic UTC)" {
        val reporter = user()
        ReportQueueTestSupport.seedReport(
            dataSource,
            reporter,
            "user",
            user(),
            Instant.parse("2090-06-20T12:00:00Z"),
            reasonNote = "before",
        )
        ReportQueueTestSupport.seedReport(dataSource, reporter, "user", user(), Instant.parse("2090-06-25T23:59:59Z"), reasonNote = "edge")
        ReportQueueTestSupport.seedReport(dataSource, reporter, "user", user(), Instant.parse("2090-06-26T00:00:00Z"), reasonNote = "after")

        val day = LocalDate.parse("2090-06-25")
        val q =
            ReportQueueQuery(
                fromInclusive = day.atStartOfDay(ZoneOffset.UTC).toInstant(),
                toExclusive = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
            )
        repo.query(q).rows.map { it.reasonNote } shouldBe listOf("edge")
    }

    "5.7 — trigger filter constrains to reports whose target has a matching moderation_queue row (EXISTS)" {
        val reporter = user()
        val base = Instant.parse("2090-07-01T00:00:00Z")
        val withQueue = user()
        val withoutQueue = user()
        val rWith = ReportQueueTestSupport.seedReport(dataSource, reporter, "user", withQueue, base.plusSeconds(2))
        ReportQueueTestSupport.seedReport(dataSource, reporter, "user", withoutQueue, base.plusSeconds(1))
        ReportQueueTestSupport.seedQueueRow(dataSource, "user", withQueue, trigger = "auto_hide_3_reports")

        val rows = repo.query(windowQuery(base, trigger = "auto_hide_3_reports")).rows
        rows.map { it.id } shouldBe listOf(rWith)
    }

    "5.8 — SQL-metacharacter / over-long status is a literal (0 rows, no error, table survives)" {
        val reporter = user()
        val base = Instant.parse("2090-08-01T00:00:00Z")
        ReportQueueTestSupport.seedReport(dataSource, reporter, "user", user(), base.plusSeconds(1))

        repo.query(windowQuery(base, status = "pending'); DROP TABLE reports;--")).rows.size shouldBe 0
        repo.query(windowQuery(base, status = "x".repeat(40))).rows.size shouldBe 0
        ReportQueueTestSupport.reportsTableExists(dataSource) shouldBe true
        // window still returns the one seeded row when unfiltered
        repo.query(windowQuery(base)).rows.size shouldBe 1
    }

    "5.9 — moderation_queue context: present shows trigger/priority/status; absent → null; two triggers → one representative row" {
        val reporter = user()
        val base = Instant.parse("2090-09-01T00:00:00Z")

        // (a) report WITH a queue row
        val tWith = user()
        val rWith = ReportQueueTestSupport.seedReport(dataSource, reporter, "user", tWith, base.plusSeconds(3))
        ReportQueueTestSupport.seedQueueRow(dataSource, "user", tWith, trigger = "auto_hide_3_reports", priority = 5, status = "pending")

        // (b) report WITHOUT a queue row
        val rWithout = ReportQueueTestSupport.seedReport(dataSource, reporter, "user", user(), base.plusSeconds(2))

        // (c) report whose target has TWO queue rows (distinct triggers + priorities) → one representative row
        val tTwo = user()
        val rTwo = ReportQueueTestSupport.seedReport(dataSource, reporter, "user", tTwo, base.plusSeconds(1))
        ReportQueueTestSupport.seedQueueRow(dataSource, "user", tTwo, trigger = "admin_flag", priority = 9, status = "pending")
        ReportQueueTestSupport.seedQueueRow(dataSource, "user", tTwo, trigger = "csam_detected", priority = 1, status = "pending")

        val rows = repo.query(windowQuery(base)).rows
        rows.size shouldBe 3 // exactly one display row per report — no fan-out from (c)

        val withRow = rows.single { it.id == rWith }
        withRow.queueTrigger shouldBe "auto_hide_3_reports"
        withRow.queuePriority?.toInt() shouldBe 5
        withRow.queueStatus shouldBe "pending"

        val withoutRow = rows.single { it.id == rWithout }
        withoutRow.queueTrigger.shouldBeNull()
        withoutRow.queuePriority.shouldBeNull()
        withoutRow.queueStatus.shouldBeNull()

        // representative row = lowest priority (ASC) = csam_detected (priority 1)
        rows.single { it.id == rTwo }.queueTrigger shouldBe "csam_detected"
    }

    "5.10 — deep-link user resolves per target_type; hard-deleted target → null" {
        val reporter = user()
        val base = Instant.parse("2090-10-01T00:00:00Z")

        val targetUser = user()
        val rUser = ReportQueueTestSupport.seedReport(dataSource, reporter, "user", targetUser, base.plusSeconds(5))

        val postAuthor = user()
        val postId = ReportQueueTestSupport.seedPost(dataSource, postAuthor).also { seeded.add(it) }
        val rPost = ReportQueueTestSupport.seedReport(dataSource, reporter, "post", postId, base.plusSeconds(4))

        val replyAuthor = user()
        val replyId = ReportQueueTestSupport.seedReply(dataSource, postId, replyAuthor).also { seeded.add(it) }
        val rReply = ReportQueueTestSupport.seedReport(dataSource, reporter, "reply", replyId, base.plusSeconds(3))

        val sender = user()
        val msgId = ReportQueueTestSupport.seedChatMessage(dataSource, sender).also { seeded.add(it) }
        val rChat = ReportQueueTestSupport.seedReport(dataSource, reporter, "chat_message", msgId, base.plusSeconds(2))

        // hard-deleted user target → no resolvable base row → null
        val ghost = ReportQueueTestSupport.seedUser(dataSource).also { seeded.add(it) }
        val rGhost = ReportQueueTestSupport.seedReport(dataSource, reporter, "user", ghost, base.plusSeconds(1))
        ReportQueueTestSupport.hardDeleteUser(dataSource, ghost)

        val rows = repo.query(windowQuery(base)).rows.associateBy { it.id }
        rows.getValue(rUser).actionUserId shouldBe targetUser
        rows.getValue(rPost).actionUserId shouldBe postAuthor
        rows.getValue(rReply).actionUserId shouldBe replyAuthor
        rows.getValue(rChat).actionUserId shouldBe sender
        rows.getValue(rGhost).actionUserId.shouldBeNull()
    }

    "5.15 — reporter identity resolves to the reporter's username" {
        val reporter = user(username = "rq_known_reporter")
        val base = Instant.parse("2090-11-01T00:00:00Z")
        val rId = ReportQueueTestSupport.seedReport(dataSource, reporter, "user", user(), base.plusSeconds(1))

        val row = repo.query(windowQuery(base)).rows.single { it.id == rId }
        row.reporterUsername shouldBe "rq_known_reporter"
        row.reporterId shouldBe reporter
    }

    "5.16 — out-of-enum status yields zero matches (not an error)" {
        val reporter = user()
        val base = Instant.parse("2090-12-01T00:00:00Z")
        ReportQueueTestSupport.seedReport(dataSource, reporter, "user", user(), base.plusSeconds(1))
        repo.query(windowQuery(base, status = "not-an-enum-value")).rows.size shouldBe 0
    }

    "V19 — creates the non-partial (created_at DESC, id DESC) keyset index on reports" {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'reports'
                  AND indexname = 'reports_created_idx'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    val indexdef = rs.getString("indexdef")
                    indexdef.shouldNotBeNull()
                    indexdef.contains("created_at DESC") shouldBe true
                    indexdef.contains("id DESC") shouldBe true
                    indexdef.uppercase().contains(" WHERE ") shouldBe false // non-partial
                    rs.next() shouldBe false // exactly one index row
                }
            }
        }
    }
})
