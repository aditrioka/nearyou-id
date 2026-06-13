package id.nearyou.app.admin.blockregistry

import id.nearyou.app.admin.auth.AdminAuthTestSupport
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

/**
 * Integration tests for [AdminBlockRegistryRepository] — the
 * read/filter/paginate core of the `admin-block-registry` capability
 * (tasks.md Section 7). DB-backed; tagged `database`.
 *
 * `user_blocks` is shared, so each test starts from a [deleteAllBlocks] clean
 * slate (block rows are cheap test artifacts) and tracks seeded `users` rows
 * for cleanup. Asserts ordering, keyset pagination (boundary + identical-
 * created_at tiebreaker), the either-side username/UUID search (exact, case-
 * insensitive), the bidirectional `EXISTS` flag, and the empty-result path.
 *
 * No index-existence test: design.md D2 ships ZERO migrations — the read path
 * is served by the existing PK + `users_username_lower_idx`.
 */
@Tags("database")
class AdminBlockRegistryRepositoryTest : StringSpec({

    val dataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val seededUsers = mutableListOf<UUID>()
    beforeEach { BlockRegistryTestSupport.deleteAllBlocks(dataSource) }
    afterEach {
        BlockRegistryTestSupport.deleteAllBlocks(dataSource)
        seededUsers.forEach { BlockRegistryTestSupport.deleteUser(dataSource, it) }
        seededUsers.clear()
    }

    val repo = AdminBlockRegistryRepository(dataSource)
    val base = Instant.parse("2026-05-20T00:00:00Z")

    fun user(username: String? = null): UUID =
        BlockRegistryTestSupport.seedUser(dataSource, username).also { seededUsers.add(it) }

    fun block(
        blocker: UUID,
        blocked: UUID,
        at: Instant = base,
    ) = BlockRegistryTestSupport.seedBlock(dataSource, blocker, blocked, at)

    "7.2 — block pairs are returned newest-first (created_at DESC)" {
        val a = user()
        val b = user()
        val c = user()
        val d = user()
        block(a, b, base)
        block(b, c, base.plusSeconds(60))
        block(c, d, base.plusSeconds(120))

        val rows = repo.query(BlockRegistryQuery()).rows

        rows.map { it.createdAt } shouldContainExactly
            listOf(base.plusSeconds(120), base.plusSeconds(60), base)
        rows.first().blockerId shouldBe c
        rows.first().blockedId shouldBe d
    }

    "7.3 — keyset: first page full + has-next; cursor returns strictly-older, non-overlapping rows" {
        val a = user()
        val b = user()
        val c = user()
        val d = user()
        // 3 pairs, ascending time → newest first is (c,d)@+120, (b,c)@+60, (a,b)@0.
        block(a, b, base)
        block(b, c, base.plusSeconds(60))
        block(c, d, base.plusSeconds(120))

        val page1 = repo.query(BlockRegistryQuery(pageSize = 2))
        page1.rows.map { it.createdAt } shouldContainExactly listOf(base.plusSeconds(120), base.plusSeconds(60))
        page1.nextCursor.shouldNotBeNull()

        val page2 = repo.query(BlockRegistryQuery(pageSize = 2, cursor = page1.nextCursor))
        page2.rows.map { it.createdAt } shouldContainExactly listOf(base)
        page2.nextCursor.shouldBeNull()
        // no overlap between the two pages
        (page1.rows.map { it.blockerId to it.blockedId } intersect
            page2.rows.map { it.blockerId to it.blockedId }.toSet()) shouldBe emptySet()
    }

    "7.3 — identical created_at rows paginate by the (blocker_id, blocked_id) PK tiebreaker, no loss/dup" {
        val a = user()
        val b = user()
        val c = user()
        val d = user()
        val e = user()
        val f = user()
        // 3 pairs sharing the EXACT same created_at — the PK breaks the tie.
        val sameTs = base.plusSeconds(99)
        block(a, b, sameTs)
        block(c, d, sameTs)
        block(e, f, sameTs)

        val seen = mutableListOf<Pair<UUID, UUID>>()
        var cursor = repo.query(BlockRegistryQuery(pageSize = 2)).also {
            seen += it.rows.map { r -> r.blockerId to r.blockedId }
        }.nextCursor
        while (cursor != null) {
            val page = repo.query(BlockRegistryQuery(pageSize = 2, cursor = cursor))
            seen += page.rows.map { r -> r.blockerId to r.blockedId }
            cursor = page.nextCursor
        }

        seen shouldContainExactlyInAnyOrder listOf(a to b, c to d, e to f)
        seen.toSet().size shouldBe 3 // no duplication
    }

    "7.3 — exact page-size boundary omits the cursor; one more older row produces it" {
        val a = user()
        val b = user()
        val c = user()
        val d = user()
        block(a, b, base.plusSeconds(10))
        block(c, d, base.plusSeconds(20))

        // EXACTLY page-size rows → no older page.
        repo.query(BlockRegistryQuery(pageSize = 2)).nextCursor.shouldBeNull()

        // One more (older) row → page-size+1 → cursor appears.
        val e = user()
        val f = user()
        block(e, f, base) // older than the other two
        repo.query(BlockRegistryQuery(pageSize = 2)).nextCursor.shouldNotBeNull()
    }

    "7.4 — search by username matches pairs on EITHER side" {
        val budi = user("budi_kopi")
        val x = user()
        val y = user()
        val z = user()
        val w = user()
        block(budi, x, base.plusSeconds(1)) // budi is blocker
        block(y, budi, base.plusSeconds(2)) // budi is blocked
        block(z, w, base.plusSeconds(3)) // unrelated

        val rows = repo.query(BlockRegistryQuery(searchUsername = "budi_kopi")).rows
        rows.map { it.blockerId to it.blockedId } shouldContainExactlyInAnyOrder
            listOf(budi to x, y to budi)
    }

    "7.4 — username match is case-insensitive and EXACT (not substring)" {
        val u = user("Budi_Kopi")
        val other = user()
        block(u, other, base)

        // case-insensitive hit
        repo.query(BlockRegistryQuery(searchUsername = "budi_kopi")).rows.size shouldBe 1
        // substring is NOT a match
        repo.query(BlockRegistryQuery(searchUsername = "budi")).rows.shouldBeEmpty()
    }

    "7.4 — search by user-ID UUID matches pairs on either side" {
        val target = user()
        val x = user()
        val y = user()
        block(target, x, base.plusSeconds(1)) // target is blocker
        block(y, target, base.plusSeconds(2)) // target is blocked

        val rows = repo.query(BlockRegistryQuery(searchUserId = target)).rows
        rows.map { it.blockerId to it.blockedId } shouldContainExactlyInAnyOrder
            listOf(target to x, y to target)
    }

    "7.4 — a search matching no user returns no rows" {
        val a = user()
        val b = user()
        block(a, b, base)
        repo.query(BlockRegistryQuery(searchUsername = "nobody_has_this_username")).rows.shouldBeEmpty()
    }

    "7.5 — bidirectional flag: mutual pair affirmative, one-directional negative, no row duplication" {
        val a = user()
        val b = user()
        val c = user()
        val d = user()
        block(a, b, base.plusSeconds(1))
        block(b, a, base.plusSeconds(2)) // mutual with (a,b)
        block(c, d, base.plusSeconds(3)) // one-directional

        val rows = repo.query(BlockRegistryQuery()).rows
        val byPair = rows.associateBy { it.blockerId to it.blockedId }

        byPair[a to b].shouldNotBeNull().bidirectional.shouldBeTrue()
        byPair[b to a].shouldNotBeNull().bidirectional.shouldBeTrue()
        byPair[c to d].shouldNotBeNull().bidirectional.shouldBeFalse()
        // the directed (a,b) row appears exactly once (the EXISTS flag does not multiply rows)
        rows.count { it.blockerId == a && it.blockedId == b } shouldBe 1
        rows.size shouldBe 3
    }

    "7.10 — an empty registry returns an empty page (no rows, no cursor)" {
        val page = repo.query(BlockRegistryQuery())
        page.rows.shouldBeEmpty()
        page.nextCursor.shouldBeNull()
    }
})
