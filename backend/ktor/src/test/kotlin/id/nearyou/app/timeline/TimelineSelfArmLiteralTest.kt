package id.nearyou.app.timeline

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path

/**
 * Literal-inspection pins for `shadow-ban-feed-self-visibility` (tasks 3.3 + 3.6) —
 * source scans over the repository SQL literals, mirroring the ReplyEndpointsTest
 * "no hard DELETE: grep assertion" precedent. Deliberately NOT `@Tags("database")`:
 * these run in the no-Postgres lane.
 *
 * What they pin:
 *  - Nearby + Global: the visible arm still reads `FROM visible_posts`; the ONLY raw
 *    `posts` / raw `users` references are the self arm's (scoped to `author_id = ?`
 *    with `deleted_at IS NULL`); the arms are disjoint (`author_id <> ?` on the
 *    visible arm); the SQL-holding declaration carries `@AllowRawPostsRead`.
 *  - Following: NO self arm — no `UNION ALL`, no raw `FROM posts` arm, no
 *    `author_id = ?` self-predicate (the following-timeline "Following carries NO
 *    own-content self-arm" requirement; self-follow is impossible, so a self arm
 *    would invert the shadow-ban oracle).
 */
class TimelineSelfArmLiteralTest : StringSpec({

    // Walk up from `user.dir` until we find the directory containing
    // `settings.gradle.kts` — that's the repo root. Gradle sets `user.dir` to the
    // submodule dir (backend/ktor) when running submodule tests, and the inspected
    // files live in a sibling module (infra/supabase), so cross-module source
    // lookups need this (same approach as ReportsReadPathNonRegressionTest).
    fun repoRoot(): Path {
        var dir: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null) {
            if (Files.exists(dir.resolve("settings.gradle.kts"))) return dir
            dir = dir.parent
        }
        error("Could not locate repo root (settings.gradle.kts) from ${System.getProperty("user.dir")}")
    }

    fun repoSource(relative: String): String {
        val path = repoRoot().resolve(relative)
        Files.exists(path) shouldBe true
        return Files.readString(path)
    }

    // Word-bounded so `FROM visible_posts` / `JOIN visible_users` (the `_` is a word
    // character, so `\bposts\b` does not match inside `visible_posts`) and
    // `FROM post_replies` never count as raw-table reads.
    val rawPostsRead = Regex("""\bFROM\s+posts\b""")
    val rawUsersJoin = Regex("""\bJOIN\s+users\b""")

    fun assertTwoArmShape(src: String) {
        // Visible arm intact, disjoint from the self arm.
        src shouldContain "FROM visible_posts"
        src shouldContain "author_id <> ?"
        src shouldContain "UNION ALL"
        // Self arm: raw posts/users, scoped to the viewer, soft-deleted rows excluded.
        src shouldContain "FROM posts p"
        src shouldContain "JOIN users u"
        src shouldContain "author_id = ?"
        src shouldContain "deleted_at IS NULL"
        // The ONLY raw posts read / raw users join in the file is the self arm's.
        rawPostsRead.findAll(src).count() shouldBe 1
        rawUsersJoin.findAll(src).count() shouldBe 1
        // RawFromPostsRule allowlist annotation on the SQL-holding declaration.
        src shouldContain "@AllowRawPostsRead"
    }

    "Nearby literal — visible arm intact + allowlisted self arm scoped to the viewer" {
        assertTwoArmShape(
            repoSource("infra/supabase/src/main/kotlin/id/nearyou/app/infra/repo/JdbcPostsTimelineRepository.kt"),
        )
    }

    "Global literal — visible arm intact + allowlisted self arm scoped to the viewer" {
        assertTwoArmShape(
            repoSource("infra/supabase/src/main/kotlin/id/nearyou/app/infra/repo/JdbcPostsGlobalRepository.kt"),
        )
    }

    "Following literal — carries NO self arm (deliberate; self-follow is impossible)" {
        val src =
            repoSource("infra/supabase/src/main/kotlin/id/nearyou/app/infra/repo/JdbcPostsFollowingRepository.kt")
        // Still the single visible_posts shape with the follows filter.
        src shouldContain "FROM visible_posts"
        src shouldContain "follower_id = ?"
        // No two-arm union, no raw posts arm, no viewer self-predicate.
        src shouldNotContain "UNION ALL"
        rawPostsRead.containsMatchIn(src) shouldBe false
        src shouldNotContain "author_id = ?"
    }
})
