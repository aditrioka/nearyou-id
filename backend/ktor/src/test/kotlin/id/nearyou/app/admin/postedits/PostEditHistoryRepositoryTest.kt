package id.nearyou.app.admin.postedits

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

/**
 * DB-backed tests for [PostEditHistoryRepository] — the keyset pagination,
 * version numbering, and the SQL-derived `location_changed` boolean
 * (`admin-post-edit-history` tasks.md 4.2 / 4.5 / 4.8). Tagged `database`; the
 * pool autoCloses in `afterSpec` (CI connection-budget discipline, docs/11
 * §3.2 — tasks.md 4.11). Pagination is exercised at a small page size so the
 * boundary cases need only a handful of seeded rows.
 */
@Tags("database")
class PostEditHistoryRepositoryTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val repo = PostEditHistoryRepository(dataSource)

    val seededUsers = mutableListOf<UUID>()
    afterEach {
        seededUsers.forEach { PostEditsTestSupport.deletePostsByAuthor(dataSource, it) }
        seededUsers.forEach { PostEditsTestSupport.deleteUser(dataSource, it) }
        seededUsers.clear()
    }

    fun user(username: String? = null): UUID = PostEditsTestSupport.seedUser(dataSource, username).also { seededUsers.add(it) }

    val t1 = Instant.parse("2026-06-10T19:58:00Z")
    val t2 = Instant.parse("2026-06-10T20:12:00Z")
    val t3 = Instant.parse("2026-06-10T20:40:00Z")

    "liveVersion returns the live post; null for an unknown id" {
        val author = user("pe_live_author")
        val postId = PostEditsTestSupport.seedPost(dataSource, author, content = "the live text")

        val live = repo.liveVersion(postId)
        live.shouldNotBeNull()
        live.content shouldBe "the live text"
        live.authorId shouldBe author
        live.authorUsername shouldBe "pe_live_author"

        repo.liveVersion(UUID.randomUUID()).shouldBeNull()
    }

    "liveVersion timestamp uses COALESCE(updated_at, created_at): updated_at wins, else created_at" {
        val author = user()
        val created = Instant.parse("2026-06-10T19:00:00Z")
        val updated = Instant.parse("2026-06-10T20:40:00Z")
        // updated_at present → it is the displayed timestamp (primary COALESCE branch).
        val edited = PostEditsTestSupport.seedPost(dataSource, author, createdAt = created, updatedAt = updated)
        repo.liveVersion(edited)!!.timestamp shouldBe updated
        // updated_at NULL (never edited) → fall back to created_at.
        val fresh = PostEditsTestSupport.seedPost(dataSource, author, createdAt = created, updatedAt = null)
        repo.liveVersion(fresh)!!.timestamp shouldBe created
    }

    "liveVersion is null for a hard-deleted post" {
        val author = user()
        val postId = PostEditsTestSupport.seedPost(dataSource, author)
        PostEditsTestSupport.deletePost(dataSource, postId)
        repo.liveVersion(postId).shouldBeNull()
    }

    "version numbers count over the whole post: newest snapshot = 2, oldest = N+1" {
        val author = user()
        val postId = PostEditsTestSupport.seedPost(dataSource, author, content = "v-live")
        PostEditsTestSupport.seedEdit(dataSource, postId, author, "v-oldest", t1)
        PostEditsTestSupport.seedEdit(dataSource, postId, author, "v-mid", t2)
        PostEditsTestSupport.seedEdit(dataSource, postId, author, "v-newest", t3)

        val page = repo.snapshotPage(postId, cursor = null, pageSize = 10)
        // newest-first
        page.snapshots.map { it.content } shouldBe listOf("v-newest", "v-mid", "v-oldest")
        // version_number = count_newer + 2 → 2,3,4 (live is 1). N+1 = 4 versions total.
        page.snapshots.map { it.versionNumber } shouldBe listOf(2, 3, 4)
        page.nextCursor.shouldBeNull()
    }

    "location_changed compares each snapshot to its adjacent newer version (newest vs live)" {
        val author = user()
        // live at the default point; snapshots: A==live, B differs from A, C==B.
        val postId = PostEditsTestSupport.seedPost(dataSource, author)
        PostEditsTestSupport.seedEdit(dataSource, postId, author, "C", t1, lon = 107.0, lat = -6.3)
        PostEditsTestSupport.seedEdit(dataSource, postId, author, "B", t2, lon = 107.0, lat = -6.3)
        PostEditsTestSupport.seedEdit(dataSource, postId, author, "A", t3) // == live default point

        val page = repo.snapshotPage(postId, cursor = null, pageSize = 10)
        val byContent = page.snapshots.associateBy { it.content }
        // A (newest) vs live: same point → unchanged.
        byContent.getValue("A").locationChanged.shouldBeFalse()
        // B vs A: different point → changed.
        byContent.getValue("B").locationChanged.shouldBeTrue()
        // C vs B: same point → unchanged.
        byContent.getValue("C").locationChanged.shouldBeFalse()
    }

    "keyset pages are non-overlapping and gap-free at the boundary" {
        val author = user()
        val postId = PostEditsTestSupport.seedPost(dataSource, author)
        PostEditsTestSupport.seedEdit(dataSource, postId, author, "s1", t1)
        PostEditsTestSupport.seedEdit(dataSource, postId, author, "s2", t2)
        PostEditsTestSupport.seedEdit(dataSource, postId, author, "s3", t3)

        val page1 = repo.snapshotPage(postId, cursor = null, pageSize = 2)
        page1.snapshots.map { it.content } shouldBe listOf("s3", "s2")
        page1.nextCursor.shouldNotBeNull()

        val page2 = repo.snapshotPage(postId, cursor = page1.nextCursor, pageSize = 2)
        page2.snapshots.map { it.content } shouldBe listOf("s1")
        page2.nextCursor.shouldBeNull()
    }

    "exactly pageSize snapshots omits the next cursor (no off-by-one second page)" {
        val author = user()
        val postId = PostEditsTestSupport.seedPost(dataSource, author)
        PostEditsTestSupport.seedEdit(dataSource, postId, author, "s1", t1)
        PostEditsTestSupport.seedEdit(dataSource, postId, author, "s2", t2)

        // exactly pageSize (2) snapshots → over-fetch finds no extra row → no cursor.
        val page = repo.snapshotPage(postId, cursor = null, pageSize = 2)
        page.snapshots.map { it.content } shouldBe listOf("s2", "s1")
        page.nextCursor.shouldBeNull()
    }

    "a post with zero snapshots yields an empty page" {
        val author = user()
        val postId = PostEditsTestSupport.seedPost(dataSource, author)
        val page = repo.snapshotPage(postId, cursor = null, pageSize = 10)
        page.snapshots.shouldBe(emptyList())
        page.nextCursor.shouldBeNull()
    }
})
