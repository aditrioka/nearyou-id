package id.nearyou.app.post

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.config.RemoteConfig
import id.nearyou.app.config.StubRemoteConfig
import id.nearyou.app.core.domain.ratelimit.InMemoryRateLimiter
import id.nearyou.app.guard.ContentEmptyException
import id.nearyou.app.guard.ContentLengthGuard
import id.nearyou.app.guard.ContentTooLongException
import id.nearyou.app.infra.repo.JdbcModerationQueueRepository
import id.nearyou.app.infra.repo.JdbcPostRepository
import id.nearyou.app.infra.repo.LockedPostForEdit
import id.nearyou.app.infra.repo.PostEditEligibility
import id.nearyou.app.infra.repo.PostRepository
import id.nearyou.app.moderation.Layer3DispatcherScope
import id.nearyou.app.moderation.Layer3Moderator
import id.nearyou.app.moderation.ModerationList
import id.nearyou.app.moderation.ModerationListLoader
import id.nearyou.app.moderation.Outcome
import id.nearyou.app.moderation.TargetType
import id.nearyou.app.moderation.TextModerator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.sql.Connection
import java.sql.Date
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Service-level DB-tagged tests for the `premium-post-editing` capability — the gate
 * order (premium → rate-limit → length), the race-safe `FOR UPDATE` transaction, the
 * D7 non-leaky disambiguation, the D9 no-op rejection, re-moderation (D1), atomicity,
 * concurrency, the temporal-collision retry (D4), and the per-user edit rate limit
 * (D8). Route-layer mapping + the history read are covered by [PostEditRoutesTest].
 *
 * autoClose + maximumPoolSize=2 per the CI connection-budget rule (PR #157): a leaked
 * per-spec pool tips the CI Postgres into "too many clients".
 */
@Tags("database")
class PostEditServiceTest : StringSpec({

    val dataSource = autoClose(hikariLocal())
    val realPosts = JdbcPostRepository()
    val moderationQueueRepo = JdbcModerationQueueRepository()
    val contentGuard = ContentLengthGuard(mapOf("post.content" to 280))

    fun moderator(
        reject: List<String> = emptyList(),
        flag: List<String> = emptyList(),
        threshold: Int = 3,
    ): TextModerator {
        val loader =
            object : ModerationListLoader {
                override fun load(list: ModerationList): List<String> =
                    when (list) {
                        ModerationList.ProfanityList -> reject
                        ModerationList.UuIteList -> flag
                    }

                override fun loadThreshold(): Int = threshold
            }
        return TextModerator(loader)
    }

    /** Records every Layer-3 dispatch target so a test can assert dispatch / no-dispatch. */
    class RecordingLayer3Moderator : Layer3Moderator {
        val moderated = ConcurrentLinkedQueue<UUID>()

        override suspend fun moderate(
            targetType: TargetType,
            targetId: UUID,
            content: String,
        ): Outcome {
            moderated.add(targetId)
            return Outcome.NoAction
        }
    }

    fun service(
        posts: PostRepository = realPosts,
        textModerator: TextModerator = moderator(),
        rateLimiter: PostEditRateLimiter = PostEditRateLimiter(InMemoryRateLimiter()),
        remoteConfig: RemoteConfig = StubRemoteConfig(),
        layer3Scope: Layer3DispatcherScope? = null,
        layer3Moderator: Layer3Moderator? = null,
    ): PostEditService =
        PostEditService(
            dataSource = dataSource,
            posts = posts,
            contentGuard = contentGuard,
            textModerator = textModerator,
            moderationQueue = moderationQueueRepo,
            rateLimiter = rateLimiter,
            remoteConfig = remoteConfig,
            layer3DispatcherScope = layer3Scope,
            layer3Moderator = layer3Moderator,
        )

    fun seedUser(subscriptionStatus: String = "premium_active"): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix, subscription_status)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "pe_$short")
                ps.setString(3, "Post Edit Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "e${short.take(7)}")
                ps.setString(6, subscriptionStatus)
                ps.executeUpdate()
            }
        }
        return id
    }

    /** Seeds a post with [content], created [ageMinutes] ago, optionally soft-deleted. */
    fun seedPost(
        authorId: UUID,
        content: String = "original content",
        ageMinutes: Long = 0,
        softDeleted: Boolean = false,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO posts (id, author_id, content, display_location, actual_location, created_at, deleted_at)
                VALUES (?, ?, ?,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                  NOW() - (? || ' minutes')::interval,
                  ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, authorId)
                ps.setString(3, content)
                ps.setDouble(4, 106.8)
                ps.setDouble(5, -6.2)
                ps.setDouble(6, 106.8)
                ps.setDouble(7, -6.2)
                ps.setString(8, ageMinutes.toString())
                ps.setObject(9, if (softDeleted) java.sql.Timestamp(System.currentTimeMillis()) else null)
                ps.executeUpdate()
            }
        }
        return id
    }

    data class PostState(val content: String, val updatedAt: java.time.Instant?, val actualLocationWkt: String)

    fun postState(id: UUID): PostState =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT content, updated_at, ST_AsText(actual_location) AS loc FROM posts WHERE id = ?",
            ).use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use { rs ->
                    check(rs.next())
                    PostState(rs.getString("content"), rs.getTimestamp("updated_at")?.toInstant(), rs.getString("loc"))
                }
            }
        }

    fun editCount(postId: UUID): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM post_edits WHERE post_id = ?").use { ps ->
                ps.setObject(1, postId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    fun firstSnapshotContent(postId: UUID): String? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT content_snapshot FROM post_edits WHERE post_id = ? ORDER BY edited_at LIMIT 1",
            ).use { ps ->
                ps.setObject(1, postId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    fun queueCount(postId: UUID): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM moderation_queue WHERE target_type = 'post' AND target_id = ?",
            ).use { ps ->
                ps.setObject(1, postId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    fun cleanup(vararg ids: UUID) {
        dataSource.connection.use { conn ->
            ids.forEach { id ->
                conn.createStatement().use { st ->
                    st.executeUpdate("DELETE FROM moderation_queue WHERE target_id IN (SELECT id FROM posts WHERE author_id = '$id')")
                    st.executeUpdate("DELETE FROM posts WHERE author_id = '$id'")
                    st.executeUpdate("DELETE FROM users WHERE id = '$id'")
                }
            }
        }
    }

    // 5.1 — Premium author edits within window → 200; one snapshot holds PRE-edit content;
    // posts.content/updated_at updated; location unchanged.
    "5.1 premium author edits within window: snapshot captures pre-edit content, post updated, location unchanged" {
        val author = seedUser("premium_active")
        try {
            val postId = seedPost(author, content = "before edit", ageMinutes = 5)
            val before = postState(postId)
            val out = runBlocking { service().edit(author, postId, "after edit", "premium_active") }
            out.content shouldBe "after edit"
            editCount(postId) shouldBe 1
            firstSnapshotContent(postId) shouldBe "before edit"
            val after = postState(postId)
            after.content shouldBe "after edit"
            after.updatedAt shouldBe after.updatedAt // non-null assertion below
            (after.updatedAt != null) shouldBe true
            // Location is unchanged by the content edit.
            after.actualLocationWkt shouldBe before.actualLocationWkt
        } finally {
            cleanup(author)
        }
    }

    // 5.2 — premium_billing_retry user edits within window → success.
    "5.2 premium_billing_retry user edits within window succeeds" {
        val author = seedUser("premium_billing_retry")
        try {
            val postId = seedPost(author, content = "grace before", ageMinutes = 5)
            val out = runBlocking { service().edit(author, postId, "grace after", "premium_billing_retry") }
            out.content shouldBe "grace after"
            editCount(postId) shouldBe 1
        } finally {
            cleanup(author)
        }
    }

    // 5.3 — Free user → 403 premium_required, post unchanged.
    "5.3 free user is paywalled with PostEditPremiumRequiredException, post unchanged" {
        val author = seedUser("free")
        try {
            val postId = seedPost(author, content = "free original", ageMinutes = 5)
            runBlocking {
                shouldThrow<PostEditPremiumRequiredException> {
                    service().edit(author, postId, "free attempt", "free")
                }
            }
            postState(postId).content shouldBe "free original"
            editCount(postId) shouldBe 0
        } finally {
            cleanup(author)
        }
    }

    // 5.4 — Non-author edit → 404 (does not confirm existence), no change.
    "5.4 non-author edit throws uniform PostEditNotFoundException, no change" {
        val author = seedUser("premium_active")
        val other = seedUser("premium_active")
        try {
            val postId = seedPost(author, content = "owned by author", ageMinutes = 5)
            runBlocking {
                shouldThrow<PostEditNotFoundException> {
                    service().edit(other, postId, "hijack attempt", "premium_active")
                }
            }
            postState(postId).content shouldBe "owned by author"
            editCount(postId) shouldBe 0
        } finally {
            cleanup(author, other)
        }
    }

    // 5.5 — Edit after 30-min window (author's own post created 31 min ago) → 409 edit_window_expired.
    "5.5 author edits own post created 31 min ago throws PostEditWindowExpiredException, no change" {
        val author = seedUser("premium_active")
        try {
            val postId = seedPost(author, content = "stale content", ageMinutes = 31)
            runBlocking {
                shouldThrow<PostEditWindowExpiredException> {
                    service().edit(author, postId, "too late", "premium_active")
                }
            }
            postState(postId).content shouldBe "stale content"
            editCount(postId) shouldBe 0
        } finally {
            cleanup(author)
        }
    }

    // 5.6 — Edit author's own soft-deleted post → 404, no change.
    "5.6 author edits own soft-deleted post throws PostEditNotFoundException (gone), no change" {
        val author = seedUser("premium_active")
        try {
            val postId = seedPost(author, content = "deleted content", ageMinutes = 5, softDeleted = true)
            runBlocking {
                shouldThrow<PostEditNotFoundException> {
                    service().edit(author, postId, "revive", "premium_active")
                }
            }
            postState(postId).content shouldBe "deleted content"
            editCount(postId) shouldBe 0
        } finally {
            cleanup(author)
        }
    }

    // 5.7 — 281-char edit → 400; empty edit → 400; post unchanged.
    "5.7 over-length and empty edits are rejected before the transaction, post unchanged" {
        val author = seedUser("premium_active")
        try {
            val postId = seedPost(author, content = "valid original", ageMinutes = 5)
            runBlocking {
                shouldThrow<ContentTooLongException> {
                    service().edit(author, postId, "x".repeat(281), "premium_active")
                }
                shouldThrow<ContentEmptyException> {
                    service().edit(author, postId, "   ", "premium_active")
                }
            }
            postState(postId).content shouldBe "valid original"
            editCount(postId) shouldBe 0
        } finally {
            cleanup(author)
        }
    }

    // 5.8 — Reject → 400, no change, no Layer-3 dispatch (5.17 reject half);
    //       Flag → persists + moderation_queue row in same tx.
    "5.8 edit to profane content (Reject) is blocked + no Layer-3 dispatch; flagged content persists with a queue row" {
        val author = seedUser("premium_active")
        try {
            // Reject path — also asserts NO Layer-3 dispatch (5.17 reject half).
            val rejectPost = seedPost(author, content = "clean original", ageMinutes = 5)
            val recorder = RecordingLayer3Moderator()
            val scope = Layer3DispatcherScope.forTestWithDefensiveHandler(CoroutineScope(SupervisorJob() + Dispatchers.IO))
            runBlocking {
                shouldThrow<ContentModeratedProfanityException> {
                    service(textModerator = moderator(reject = listOf("badword")), layer3Scope = scope, layer3Moderator = recorder)
                        .edit(author, rejectPost, "this has badword inside", "premium_active")
                }
            }
            scope.shutdown(drainMillis = 2_000L)
            postState(rejectPost).content shouldBe "clean original"
            editCount(rejectPost) shouldBe 0
            recorder.moderated.isEmpty() shouldBe true

            // Flag path — persists + queue row in the same transaction.
            val flagPost = seedPost(author, content = "flag original", ageMinutes = 5)
            runBlocking {
                service(textModerator = moderator(flag = listOf("uuite"), threshold = 1))
                    .edit(author, flagPost, "contains uuite term", "premium_active")
            }
            postState(flagPost).content shouldBe "contains uuite term"
            editCount(flagPost) shouldBe 1
            queueCount(flagPost) shouldBe 1
        } finally {
            cleanup(author)
        }
    }

    // 5.9 — Atomicity: simulated UPDATE failure after snapshot insert → full rollback, no orphan post_edits row.
    "5.9 atomicity: an UPDATE posts failure after the snapshot insert rolls back the whole transaction" {
        val author = seedUser("premium_active")
        try {
            val postId = seedPost(author, content = "atomic original", ageMinutes = 5)
            // Decorator that lets selectForEdit + insertEditSnapshot run, then fails the UPDATE.
            val failingPosts =
                object : PostRepository by realPosts {
                    override fun updateContent(
                        conn: Connection,
                        postId: UUID,
                        newContent: String,
                    ) {
                        throw SQLException("simulated UPDATE failure", "XX000")
                    }
                }
            runBlocking {
                shouldThrow<SQLException> {
                    service(posts = failingPosts).edit(author, postId, "atomic new", "premium_active")
                }
            }
            // Snapshot insert is rolled back with the failed UPDATE → no orphan row, content intact.
            editCount(postId) shouldBe 0
            postState(postId).content shouldBe "atomic original"
        } finally {
            cleanup(author)
        }
    }

    // 5.10 — Concurrency: two concurrent edits serialized, each writes its own snapshot, no lost
    //        update; forced temporal collision → 409 "Coba lagi sebentar."
    "5.10 two concurrent edits to one post are serialized into two snapshots with no lost update" {
        val author = seedUser("premium_active")
        try {
            val postId = seedPost(author, content = "concurrent original", ageMinutes = 5)
            val svc = service()
            runBlocking {
                val a = async(Dispatchers.IO) { runCatching { svc.edit(author, postId, "edit A", "premium_active") } }
                val b = async(Dispatchers.IO) { runCatching { svc.edit(author, postId, "edit B", "premium_active") } }
                awaitAll(a, b)
            }
            // FOR UPDATE serializes them: both succeed, each produces its own snapshot, the final
            // content is one of the two writes (no lost update / no partial state).
            editCount(postId) shouldBe 2
            (postState(postId).content in setOf("edit A", "edit B")) shouldBe true
        } finally {
            cleanup(author)
        }
    }

    "5.10b forced persistent temporal collision throws PostEditConflictException (Coba lagi sebentar)" {
        val author = seedUser("premium_active")
        try {
            val postId = seedPost(author, content = "collision original", ageMinutes = 5)
            // Decorator forces a 23505 on every snapshot insert → the single retry also collides → 409.
            val collidingPosts =
                object : PostRepository by realPosts {
                    override fun insertEditSnapshot(
                        conn: Connection,
                        postId: UUID,
                    ) {
                        throw SQLException("duplicate key value violates unique constraint post_edits_temporal_idx", "23505")
                    }
                }
            runBlocking {
                shouldThrow<PostEditConflictException> {
                    service(posts = collidingPosts).edit(author, postId, "collide", "premium_active")
                }
            }
            editCount(postId) shouldBe 0
            postState(postId).content shouldBe "collision original"
        } finally {
            cleanup(author)
        }
    }

    // 5.15 — No-op edit (content identical to current) → 400 no_changes; no row, updated_at unchanged.
    "5.15 no-op edit identical to current content throws PostEditNoChangesException, no snapshot, updated_at unchanged" {
        val author = seedUser("premium_active")
        try {
            val postId = seedPost(author, content = "identical content", ageMinutes = 5)
            val before = postState(postId)
            runBlocking {
                shouldThrow<PostEditNoChangesException> {
                    service().edit(author, postId, "identical content", "premium_active")
                }
            }
            editCount(postId) shouldBe 0
            postState(postId).updatedAt shouldBe before.updatedAt
        } finally {
            cleanup(author)
        }
    }

    // 5.16 — Per-user edit rate limit exceeded → 429 + positive Retry-After; post unchanged.
    // The cap is ops-tunable via Remote Config (design D8): a fake RC returns 2, so the
    // 3rd edit in the rolling window is rate-limited.
    "5.16 per-user edit rate limit (Remote-Config cap) throws PostEditRateLimitedException with positive Retry-After" {
        val author = seedUser("premium_active")
        try {
            val postId = seedPost(author, content = "rl original", ageMinutes = 5)
            // Ops-tunable cap = 2 via Remote Config (the production knob, design D8).
            val rcCap2 =
                object : RemoteConfig {
                    override fun getLong(key: String): Long? = if (key == PostEditService.POST_EDIT_CAP_OVERRIDE_KEY) 2L else null

                    override fun getBoolean(key: String): Boolean? = null
                }
            val svc = service(rateLimiter = PostEditRateLimiter(InMemoryRateLimiter()), remoteConfig = rcCap2)
            runBlocking {
                svc.edit(author, postId, "rl edit 1", "premium_active")
                svc.edit(author, postId, "rl edit 2", "premium_active")
                val ex =
                    shouldThrow<PostEditRateLimitedException> {
                        svc.edit(author, postId, "rl edit 3", "premium_active")
                    }
                ex.retryAfterSeconds shouldBeGreaterThan 0L
            }
            // Two edits committed; the rate-limited third made no change.
            editCount(postId) shouldBe 2
            postState(postId).content shouldBe "rl edit 2"
        } finally {
            cleanup(author)
        }
    }

    // 5.17 (allow half) — successful edit dispatches Layer-3 carrying the post id.
    "5.17 successful edit dispatches Layer-3 moderation of the new content after commit" {
        val author = seedUser("premium_active")
        try {
            val postId = seedPost(author, content = "dispatch original", ageMinutes = 5)
            val recorder = RecordingLayer3Moderator()
            val scope = Layer3DispatcherScope.forTestWithDefensiveHandler(CoroutineScope(SupervisorJob() + Dispatchers.IO))
            runBlocking {
                service(layer3Scope = scope, layer3Moderator = recorder)
                    .edit(author, postId, "dispatch new", "premium_active")
            }
            scope.shutdown(drainMillis = 5_000L) // drain the fire-and-forget dispatch
            recorder.moderated.toList() shouldContainExactly listOf(postId)
        } finally {
            cleanup(author)
        }
    }

    // 5.19 — Window boundary: 29 min ago succeeds; 31 min ago → 409 edit_window_expired.
    "5.19 window boundary: an edit 29 min in succeeds; 31 min out is edit_window_expired" {
        val author = seedUser("premium_active")
        try {
            val inside = seedPost(author, content = "inside window", ageMinutes = 29)
            runBlocking { service().edit(author, inside, "inside edited", "premium_active") }
            postState(inside).content shouldBe "inside edited"

            val outside = seedPost(author, content = "outside window", ageMinutes = 31)
            runBlocking {
                shouldThrow<PostEditWindowExpiredException> {
                    service().edit(author, outside, "outside edited", "premium_active")
                }
            }
            postState(outside).content shouldBe "outside window"
        } finally {
            cleanup(author)
        }
    }

    // Defensive: the repo disambiguation helpers return the documented shapes
    // (guards a future refactor that swaps the D7 select).
    "repository disambiguation: eligibilityForEdit returns null for non-author and a row for the author" {
        val author = seedUser("premium_active")
        val other = seedUser("premium_active")
        try {
            val postId = seedPost(author, content = "disambig", ageMinutes = 45)
            dataSource.connection.use { conn ->
                realPosts.eligibilityForEdit(conn, postId, other) shouldBe null
                val mine: PostEditEligibility? = realPosts.eligibilityForEdit(conn, postId, author)
                (mine != null) shouldBe true
                mine!!.softDeleted shouldBe false
                // selectForEdit returns null for a 45-min-old post (out of window).
                val locked: LockedPostForEdit? = realPosts.selectForEdit(conn, postId, author)
                locked shouldBe null
            }
        } finally {
            cleanup(author, other)
        }
    }
})

private fun hikariLocal(): HikariDataSource {
    val config =
        HikariConfig().apply {
            jdbcUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
            username = System.getenv("DB_USER") ?: "postgres"
            password = System.getenv("DB_PASSWORD") ?: "postgres"
            maximumPoolSize = 2
            minimumIdle = 0
            initializationFailTimeout = -1
        }
    return HikariDataSource(config)
}
