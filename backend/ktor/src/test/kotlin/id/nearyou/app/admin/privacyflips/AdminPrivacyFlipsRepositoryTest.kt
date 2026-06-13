package id.nearyou.app.admin.privacyflips

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

/**
 * DB-backed unit tests for `AdminPrivacyFlipsRepository` — the query / summary
 * substrate of the `admin-privacy-flip-monitor` capability (tasks.md Section 6,
 * repository-level). Tagged `database`. Each test seeds only the `users` rows
 * it needs (with explicit `privacy_flip_scheduled_at` offsets relative to a
 * FIXED `evalInstant`) and removes them in `afterEach`, since `users` is shared.
 *
 * Covers: IN_WINDOW / OVERDUE classification + the boundary fencepost,
 * ascending ordering, keyset pagination (fencepost + id-tiebreaker), the
 * `status` filter (incl. no-skew at the boundary), the `q` lookup
 * (username/UUID), soft-deleted inclusion, and the count summary.
 */
@Tags("database")
class AdminPrivacyFlipsRepositoryTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val seeded = mutableListOf<UUID>()
    afterEach {
        seeded.forEach { PrivacyFlipsTestSupport.deleteUser(dataSource, it) }
        seeded.clear()
    }

    // A fixed evaluation instant — the seed offsets are relative to THIS, never
    // wall-clock, so the classification boundary is deterministic.
    val eval = Instant.parse("2026-06-13T12:00:00Z")

    fun seed(
        username: String,
        scheduledAt: Instant?,
        isPrivate: Boolean = true,
        deletedAt: Instant? = null,
        displayName: String = "Flip Tester",
    ): UUID =
        PrivacyFlipsTestSupport.seedUser(
            dataSource,
            username,
            displayName,
            scheduledAt,
            isPrivate,
            deletedAt,
        ).also { seeded.add(it) }

    fun query(
        status: String? = null,
        qUserId: UUID? = null,
        qUsername: String? = null,
        cursor: PrivacyFlipsCursor? = null,
        pageSize: Int = AdminPrivacyFlipsRepository.PAGE_SIZE,
    ) = AdminPrivacyFlipsRepository(dataSource).query(
        PrivacyFlipsQuery(eval, status, qUserId, qUsername, cursor, pageSize),
    )

    fun summary(
        qUserId: UUID? = null,
        qUsername: String? = null,
    ) = AdminPrivacyFlipsRepository(dataSource).summary(
        PrivacyFlipsQuery(eval, qUserId = qUserId, qUsername = qUsername),
    )

    fun rowFor(
        page: PrivacyFlipsPage,
        id: UUID,
    ) = page.rows.firstOrNull { it.id == id }

    "classifies a future-dated flip IN_WINDOW and a past-dated flip OVERDUE" {
        val future = seed("flip_future", eval.plusSeconds(47 * 3600))
        val past = seed("flip_past", eval.minusSeconds(25 * 3600))

        val page = query()

        rowFor(page, future)!!.status shouldBe STATUS_IN_WINDOW
        rowFor(page, past)!!.status shouldBe STATUS_OVERDUE
    }

    "a flip scheduled EXACTLY at the evaluation instant is OVERDUE (boundary fencepost)" {
        val boundary = seed("flip_boundary", eval)

        val page = query()

        // `? < privacy_flip_scheduled_at` is strict, so equality is OVERDUE.
        rowFor(page, boundary)!!.status shouldBe STATUS_OVERDUE
    }

    "the boundary row filters identically to its class (no read-vs-filter skew)" {
        val boundary = seed("flip_boundary2", eval)

        // Same evalInstant drives class + filter: the boundary row is in the
        // overdue-filtered set and NOT in the in_window-filtered set.
        rowFor(query(status = STATUS_OVERDUE), boundary).shouldNotBeNull()
        rowFor(query(status = STATUS_IN_WINDOW), boundary).shouldBeNull()
    }

    "orders most-overdue / soonest-deadline first (ascending privacy_flip_scheduled_at)" {
        val overdue = seed("flip_a_overdue", eval.minusSeconds(25 * 3600))
        val inWindow = seed("flip_b_inwindow", eval.plusSeconds(2 * 3600))

        val page = query()

        // Overdue (earlier/past deadline) sorts before the in-window row.
        val ids = page.rows.filter { it.id in listOf(overdue, inWindow) }.map { it.id }
        ids shouldContainExactly listOf(overdue, inWindow)
    }

    "excludes a user with no pending flip; includes a soft-deleted user that still has one" {
        val noFlip = seed("flip_none", scheduledAt = null)
        val softDeleted = seed("flip_deleted", eval.minusSeconds(3600), deletedAt = eval.minusSeconds(60))

        val ids = query().rows.map { it.id }

        ids.contains(noFlip) shouldBe false
        ids.contains(softDeleted) shouldBe true // design D6 — stuck-row signal, no deleted_at filter
        rowFor(query(), softDeleted)!!.deleted shouldBe true
    }

    "keyset pagination: caps the page, then the cursor returns the next non-overlapping page" {
        // 3 rows, ascending deadlines; page size 2.
        val a = seed("flip_p_a", eval.minusSeconds(3600))
        val b = seed("flip_p_b", eval.plusSeconds(3600))
        val c = seed("flip_p_c", eval.plusSeconds(7200))

        val first = query(pageSize = 2)
        first.rows.map { it.id } shouldContainExactly listOf(a, b)
        first.nextCursor.shouldNotBeNull()

        val second = query(pageSize = 2, cursor = first.nextCursor)
        second.rows.map { it.id } shouldContainExactly listOf(c)
        second.nextCursor.shouldBeNull() // last page
    }

    "keyset id-tiebreaker: rows with identical scheduledAt paginate exactly once" {
        val shared = eval.plusSeconds(3600)
        // Postgres orders UUIDs by unsigned byte comparison (≠ Java's signed
        // UUID.compareTo), so we don't predict the exact order — the property
        // under test is no-skip / no-duplicate across the page boundary.
        val ids = (1..3).map { seed("flip_tie_$it", shared) }.toSet()

        val seen = mutableListOf<UUID>()
        var cursor: PrivacyFlipsCursor? = null
        repeat(3) {
            val page = query(pageSize = 1, cursor = cursor)
            seen += page.rows.map { it.id }
            cursor = page.nextCursor
        }
        val seenTie = seen.filter { it in ids }
        seenTie.size shouldBe 3 // each appears exactly once (no duplicate)
        seenTie.toSet() shouldBe ids // all three present (no skip)
    }

    "status filter selects the matching bucket only" {
        val overdue = seed("flip_s_overdue", eval.minusSeconds(3600))
        val inWindow = seed("flip_s_inwindow", eval.plusSeconds(3600))

        query(status = STATUS_OVERDUE).rows.map { it.id }.let {
            it.contains(overdue) shouldBe true
            it.contains(inWindow) shouldBe false
        }
        query(status = STATUS_IN_WINDOW).rows.map { it.id }.let {
            it.contains(inWindow) shouldBe true
            it.contains(overdue) shouldBe false
        }
    }

    "q lookup by exact case-insensitive username and by UUID" {
        val target = seed("Budi_Kopi", eval.plusSeconds(3600))
        seed("other_user", eval.plusSeconds(3600))

        query(qUsername = "budi_kopi").rows.map { it.id } shouldContainExactly listOf(target)
        query(qUserId = target).rows.map { it.id } shouldContainExactly listOf(target)
    }

    "summary reports both buckets within the q scope and overall" {
        seed("flip_sum_o1", eval.minusSeconds(3600))
        seed("flip_sum_o2", eval.minusSeconds(7200))
        seed("flip_sum_i1", eval.plusSeconds(3600))

        val all = summary()
        all.overdue shouldBe 2
        all.inWindow shouldBe 1
        all.total shouldBe 3
    }

    "summary narrows to an active q scope" {
        val target = seed("flip_sum_q", eval.minusSeconds(3600)) // overdue
        seed("flip_sum_other", eval.plusSeconds(3600)) // in_window, different user

        val scoped = summary(qUserId = target)
        scoped.overdue shouldBe 1
        scoped.inWindow shouldBe 0
        scoped.total shouldBe 1
    }
})
