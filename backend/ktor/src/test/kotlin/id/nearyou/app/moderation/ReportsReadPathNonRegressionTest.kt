package id.nearyou.app.moderation

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.infra.repo.JdbcPostAutoHideRepository
import id.nearyou.app.infra.repo.JdbcPostReplyRepository
import id.nearyou.app.infra.repo.JdbcPostsFollowingRepository
import id.nearyou.app.infra.repo.JdbcPostsTimelineRepository
import id.nearyou.data.repository.ReportTargetType
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Date
import java.time.LocalDate
import java.util.UUID

/**
 * V9 read-path non-regression: the auto-hide flag flipped by the V9 reports
 * path MUST propagate through the existing V4 `visible_posts` view and the
 * V8 reply-list filter without any read-path code change. This test proves
 * that by flipping the flag via the `PostAutoHideRepository` (same call the
 * service makes) and then reading through the existing timeline + reply
 * repositories.
 *
 * Also asserts static invariants:
 *  - `visible_posts` view definition still equals
 *    `SELECT * FROM posts WHERE is_auto_hidden = FALSE` (semantic equivalence
 *    via `pg_views`).
 *  - V9-introduced source files under `.../app/moderation/` contain NO
 *    `FROM visible_posts` literals (V9 is a write-only consumer of the flag).
 */
@Tags("database")
class ReportsReadPathNonRegressionTest : StringSpec({

    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val dbUser = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"
    val dataSource: HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = url
                username = dbUser
                this.password = password
                maximumPoolSize = 2
                initializationFailTimeout = -1
            },
        )

    fun seedUser(): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "rr_$short")
                ps.setString(3, "Read Path Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "p${short.take(7)}")
                ps.executeUpdate()
            }
        }
        return id
    }

    fun seedPost(author: UUID): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO posts (
                    id, author_id, content, display_location, actual_location, is_auto_hidden
                ) VALUES (?, ?, ?,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                  FALSE)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, author)
                ps.setString(3, "rr-${id.toString().take(6)}")
                ps.setDouble(4, 106.8)
                ps.setDouble(5, -6.2)
                ps.setDouble(6, 106.8)
                ps.setDouble(7, -6.2)
                ps.executeUpdate()
            }
        }
        return id
    }

    fun seedReply(postId: UUID, author: UUID): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO post_replies (id, post_id, author_id, content) VALUES (?, ?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, postId)
                ps.setObject(3, author)
                ps.setString(4, "re-${id.toString().take(6)}")
                ps.executeUpdate()
            }
        }
        return id
    }

    fun insertFollow(follower: UUID, followee: UUID) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO follows (follower_id, followee_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
            ).use { ps ->
                ps.setObject(1, follower)
                ps.setObject(2, followee)
                ps.executeUpdate()
            }
        }
    }

    fun cleanup(vararg ids: UUID) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                ids.forEach {
                    st.executeUpdate("DELETE FROM post_replies WHERE post_id = '$it' OR id = '$it' OR author_id = '$it'")
                    st.executeUpdate("DELETE FROM follows WHERE follower_id = '$it' OR followee_id = '$it'")
                    st.executeUpdate("DELETE FROM posts WHERE id = '$it' OR author_id = '$it'")
                    st.executeUpdate("DELETE FROM users WHERE id = '$it'")
                }
            }
        }
    }

    val autoHide = JdbcPostAutoHideRepository()
    val timelineRepo = JdbcPostsTimelineRepository(dataSource)
    val followingRepo = JdbcPostsFollowingRepository(dataSource)
    val replyRepo = JdbcPostReplyRepository(dataSource)

    // --- 7.1 nearby timeline ---

    "7.1 flipping is_auto_hidden=TRUE hides post from nearby timeline for non-author" {
        val author = seedUser()
        val viewer = seedUser()
        val p = seedPost(author)
        try {
            val beforeIds = timelineRepo.nearby(
                viewerId = viewer,
                viewerLat = -6.2,
                viewerLng = 106.8,
                radiusMeters = 50_000,
                cursorCreatedAt = null,
                cursorPostId = null,
                limit = 50,
            ).map { it.id }
            (p in beforeIds) shouldBe true
            dataSource.connection.use { conn ->
                autoHide.flipIsAutoHidden(conn, ReportTargetType.POST, p)
            }
            val afterIds = timelineRepo.nearby(
                viewerId = viewer,
                viewerLat = -6.2,
                viewerLng = 106.8,
                radiusMeters = 50_000,
                cursorCreatedAt = null,
                cursorPostId = null,
                limit = 50,
            ).map { it.id }
            (p !in afterIds) shouldBe true
        } finally {
            cleanup(author, viewer)
        }
    }

    // --- 7.2 following timeline ---

    "7.2 flipping is_auto_hidden=TRUE hides post from following timeline for non-author" {
        val author = seedUser()
        val follower = seedUser()
        val p = seedPost(author)
        try {
            insertFollow(follower, author)
            val beforeIds = followingRepo.following(
                viewerId = follower,
                cursorCreatedAt = null,
                cursorPostId = null,
                limit = 50,
            ).map { it.id }
            (p in beforeIds) shouldBe true
            dataSource.connection.use { conn ->
                autoHide.flipIsAutoHidden(conn, ReportTargetType.POST, p)
            }
            val afterIds = followingRepo.following(
                viewerId = follower,
                cursorCreatedAt = null,
                cursorPostId = null,
                limit = 50,
            ).map { it.id }
            (p !in afterIds) shouldBe true
        } finally {
            cleanup(author, follower)
        }
    }

    // --- 7.3 replies list ---

    "7.3 flipping post_replies.is_auto_hidden=TRUE hides reply from /replies for non-author" {
        val postAuthor = seedUser()
        val replyAuthor = seedUser()
        val viewer = seedUser()
        val p = seedPost(postAuthor)
        val r = seedReply(p, replyAuthor)
        try {
            val before = replyRepo.listByPost(
                postId = p,
                viewerId = viewer,
                cursorCreatedAt = null,
                cursorReplyId = null,
                limit = 50,
            ).map { it.id }
            (r in before) shouldBe true
            dataSource.connection.use { conn ->
                autoHide.flipIsAutoHidden(conn, ReportTargetType.REPLY, r)
            }
            val after = replyRepo.listByPost(
                postId = p,
                viewerId = viewer,
                cursorCreatedAt = null,
                cursorReplyId = null,
                limit = 50,
            ).map { it.id }
            (r !in after) shouldBe true
        } finally {
            cleanup(postAuthor, replyAuthor, viewer)
        }
    }

    // --- 7.4 author bypass ---

    "7.4 author still sees their own auto-hidden reply" {
        val postAuthor = seedUser()
        val replyAuthor = seedUser()
        val p = seedPost(postAuthor)
        val r = seedReply(p, replyAuthor)
        try {
            dataSource.connection.use { conn ->
                autoHide.flipIsAutoHidden(conn, ReportTargetType.REPLY, r)
            }
            val own = replyRepo.listByPost(
                postId = p,
                viewerId = replyAuthor, // author is viewer
                cursorCreatedAt = null,
                cursorReplyId = null,
                limit = 50,
            ).map { it.id }
            (r in own) shouldBe true
        } finally {
            cleanup(postAuthor, replyAuthor)
        }
    }

    // --- 7.5 reply_count preserved ---
    //
    // V8 spec: timeline reply_count excludes shadow-banned authors via
    // `JOIN visible_users`, but does NOT filter on `is_auto_hidden`. V9's
    // auto-hide flip on a reply must therefore leave reply_count unchanged on
    // both timelines.
    "7.5 reply_count unchanged after post_replies.is_auto_hidden flipped TRUE" {
        val author = seedUser()
        val replyAuthor = seedUser()
        val follower = seedUser()
        val p = seedPost(author)
        val r = seedReply(p, replyAuthor)
        try {
            insertFollow(follower, author)
            val beforeNearby = timelineRepo.nearby(
                viewerId = follower,
                viewerLat = -6.2,
                viewerLng = 106.8,
                radiusMeters = 50_000,
                cursorCreatedAt = null,
                cursorPostId = null,
                limit = 50,
            ).first { it.id == p }
            val beforeFollowing = followingRepo.following(
                viewerId = follower,
                cursorCreatedAt = null,
                cursorPostId = null,
                limit = 50,
            ).first { it.id == p }
            beforeNearby.replyCount shouldBe 1
            beforeFollowing.replyCount shouldBe 1
            dataSource.connection.use { conn ->
                autoHide.flipIsAutoHidden(conn, ReportTargetType.REPLY, r)
            }
            val afterNearby = timelineRepo.nearby(
                viewerId = follower,
                viewerLat = -6.2,
                viewerLng = 106.8,
                radiusMeters = 50_000,
                cursorCreatedAt = null,
                cursorPostId = null,
                limit = 50,
            ).first { it.id == p }
            val afterFollowing = followingRepo.following(
                viewerId = follower,
                cursorCreatedAt = null,
                cursorPostId = null,
                limit = 50,
            ).first { it.id == p }
            // V8 counter is shadow-ban-filter-only — auto-hide flip is invisible to it.
            afterNearby.replyCount shouldBe 1
            afterFollowing.replyCount shouldBe 1
        } finally {
            cleanup(author, replyAuthor, follower)
        }
    }

    // --- 7.6 POST /posts response shape + insert default ---
    //
    // V9 introduces no change to the POST /api/v1/posts path. Assert (a) the
    // PostRoutes response JSON builder emits exactly the documented 6-key set
    // `{ id, content, latitude, longitude, distance_m, created_at }`, (b) a
    // freshly-inserted `posts` row defaults `is_auto_hidden` to FALSE at the
    // schema level. Grep + schema check is cheaper and more stable than
    // bringing up the full Ktor + jitter + auth stack for a non-regression
    // assertion that V9 deliberately did not touch.
    "7.6 PostRoutes response JSON still emits the 6 documented keys (no V9 drift)" {
        val path = Path.of(
            System.getProperty("user.dir"),
            "src", "main", "kotlin",
            "id", "nearyou", "app", "post", "PostRoutes.kt",
        ).let { local ->
            if (Files.exists(local)) local
            else Path.of(
                System.getProperty("user.dir"),
                "backend", "ktor", "src", "main", "kotlin",
                "id", "nearyou", "app", "post", "PostRoutes.kt",
            )
        }
        Files.exists(path) shouldBe true
        val src = Files.readString(path)
        // All 6 keys present, nothing else named in the buildJsonObject block.
        listOf(
            """put("id",""",
            """put("content",""",
            """put("latitude",""",
            """put("longitude",""",
            """put("distance_m",""",
            """put("created_at",""",
        ).forEach { needle -> src shouldContain needle }
        // Spot-check: no 7th key slipped in.
        Regex("""put\("([^"]+)",""").findAll(src).map { it.groupValues[1] }.toSet() shouldBe
            setOf("id", "content", "latitude", "longitude", "distance_m", "created_at")
    }

    "7.6b posts.is_auto_hidden column default is FALSE at schema level" {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT column_default
                  FROM information_schema.columns
                 WHERE table_name = 'posts' AND column_name = 'is_auto_hidden'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    rs.getString(1).lowercase() shouldContain "false"
                }
            }
        }
    }

    // --- 7.7 reply list SQL literal still has the author-bypass OR clause ---

    "7.7 JdbcPostReplyRepository.listByPost SQL literal still contains (is_auto_hidden = FALSE OR author_id = viewer)" {
        // `user.dir` is `backend/ktor` when gradle runs tests on that submodule;
        // the file lives in a sibling module (`infra/supabase`), so walk up to the
        // repo root (the directory containing `settings.gradle.kts`) and resolve
        // from there.
        val path = repoRoot().resolve(
            "infra/supabase/src/main/kotlin/id/nearyou/app/infra/repo/JdbcPostReplyRepository.kt",
        )
        Files.exists(path) shouldBe true
        val src = Files.readString(path)
        // V8 literal uses positional `?` for viewer and `pr.` alias.
        src shouldContain "pr.is_auto_hidden = FALSE OR pr.author_id = ?"
    }

    // --- 7.8 visible_posts view definition equivalence ---

    "7.8 visible_posts view still equals SELECT * FROM posts WHERE is_auto_hidden = FALSE" {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT definition FROM pg_views WHERE viewname = 'visible_posts'",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    val def = rs.getString(1)
                    // Postgres normalizes: `SELECT ... FROM posts WHERE (NOT posts.is_auto_hidden)`
                    // OR preserves literal `is_auto_hidden = false`. Accept either form.
                    val normalized = def.replace("\\s+".toRegex(), " ").lowercase()
                    (
                        normalized.contains("from posts") &&
                            (
                                normalized.contains("is_auto_hidden = false") ||
                                    normalized.contains("not") && normalized.contains("is_auto_hidden")
                            )
                    ) shouldBe true
                }
            }
        }
    }

    // --- 7.9 V9 source scan — no FROM visible_posts in moderation/ ---

    "7.9 V9-introduced moderation sources contain no FROM visible_posts literals" {
        val moderationDir =
            Path.of(
                System.getProperty("user.dir"),
                "src",
                "main",
                "kotlin",
                "id",
                "nearyou",
                "app",
                "moderation",
            )
        // Walk from backend/ktor if user.dir is the project root.
        val root =
            if (Files.exists(moderationDir)) {
                moderationDir
            } else {
                Path.of(
                    System.getProperty("user.dir"),
                    "backend", "ktor", "src", "main", "kotlin",
                    "id", "nearyou", "app", "moderation",
                )
            }
        Files.exists(root) shouldBe true
        val kts = Files.walk(root).filter { it.toString().endsWith(".kt") }.toList()
        kts.forEach { path ->
            val text = Files.readString(path)
            // assert NOT contained
            (text.lowercase().contains("from visible_posts")) shouldBe false
        }
    }
})

// Tiny custom-matcher helper just to keep assertion output readable.
@Suppress("unused")
private fun String.shouldContainIgnoringCase(substr: String) = this.lowercase() shouldContain substr.lowercase()

/**
 * Walk up from `user.dir` until we find the directory containing `settings.gradle.kts`
 * — that's the repo root. Gradle sets `user.dir` to the submodule dir when running
 * submodule tests, so cross-module source lookups need this.
 */
private fun repoRoot(): Path {
    var dir: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
    while (dir != null) {
        if (Files.exists(dir.resolve("settings.gradle.kts"))) return dir
        dir = dir.parent
    }
    error("Could not locate repo root (settings.gradle.kts) from ${System.getProperty("user.dir")}")
}
