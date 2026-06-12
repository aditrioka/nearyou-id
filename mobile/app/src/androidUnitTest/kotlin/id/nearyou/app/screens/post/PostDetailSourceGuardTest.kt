package id.nearyou.app.screens.post

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Strips block comments (incl. KDoc) then line comments — the guards are about CODE, so an explanatory
 *  KDoc mentioning a forbidden token (e.g. "MUST NOT declare latitude") must not trip the scan. */
private fun String.stripComments(): String {
    val noBlock = Regex("""/\*[\s\S]*?\*/""").replace(this, " ")
    return noBlock.lineSequence().joinToString("\n") { it.substringBefore("//") }
}

/**
 * Static-source guards for the `mobile-post-detail-screen` invariants that are properties of the source,
 * not of a single render (the spec's inspection scenarios). Mirrors `PostCreationSourceGuardTest`'s
 * file-scan idiom (walk to the repo root, read `.kt`/`.md` text, assert). Runs in every variant (NOT a
 * `*ScreenTest`, needs no Compose runner).
 *
 * Covers: no-hardcoded-UI-strings on the screen; the screen holds no back-stack reference; `PostDetailRoute`
 * declares no coordinate; the never-widen-logging discipline (`HttpClientFactory` stays `LogLevel.HEADERS`;
 * the clients + repository never `println`/log); no block/report affordance; the replies cursor is parsed
 * but no `cursor=`-bearing request is issued; and deferral bookkeeping (tracked as `follow-up` GitHub
 * issues: block/report #200, inline-card #201, by-id #202, plus the amended infinite-scroll issue #173).
 */
class PostDetailSourceGuardTest {
    private val repoRoot: File = findRepoRoot()

    private fun rawSource(relativePath: String): String {
        val file = File(repoRoot, relativePath)
        assertTrue(file.exists(), "expected source file missing: $relativePath (resolved under $repoRoot)")
        return file.readText()
    }

    private fun code(relativePath: String): String = rawSource(relativePath).stripComments()

    private val screen by lazy { code("mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/post/PostDetailScreen.kt") }

    @Test
    fun postDetailScreen_hasNoHardcodedUiStringLiterals() {
        assertFalse(screen.contains("Text(\""), "PostDetailScreen has a hardcoded Text(\"…\") literal")
        assertFalse(Regex("""text\s*=\s*"""").containsMatchIn(screen), "PostDetailScreen has a hardcoded text = \"…\" literal")
        assertFalse(
            Regex("""contentDescription\s*=\s*"""").containsMatchIn(screen),
            "PostDetailScreen has a hardcoded contentDescription = \"…\" literal",
        )
        // Positive: the copy flows through stringResource(Res.string.*).
        assertTrue(screen.contains("stringResource(Res.string.cta_reply)"), "the reply CTA must use stringResource")
        assertTrue(screen.contains("stringResource(Res.string.post_detail_posted_from"), "the header must use stringResource")
    }

    @Test
    fun postDetailScreen_holdsNoBackStackReference() {
        // Navigation comes only via the hoisted onBack lambda (spec § "PostDetailScreen holds no back-stack reference").
        assertFalse(screen.contains("NavBackStack"), "the screen must not reference NavBackStack")
        assertFalse(screen.contains("backStack"), "the screen must not perform its own back-stack mutation")
        assertTrue(screen.contains("onBack"), "the screen takes navigation via the hoisted onBack lambda")
    }

    @Test
    fun postDetailScreen_hasNoBlockOrReportAffordance() {
        for (forbidden in listOf("Blokir", "Laporkan", "DropdownMenu", "MoreVert", "kebab")) {
            assertFalse(screen.contains(forbidden), "PostDetailScreen must have no block/report affordance ($forbidden)")
        }
    }

    @Test
    fun postDetailRoute_declaresNoCoordinate() {
        val navKeys = code("mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/NavKeys.kt")
        assertFalse(navKeys.contains("latitude"), "no route may declare a latitude (raw coordinates must not enter the back stack)")
        assertFalse(navKeys.contains("longitude"), "no route may declare a longitude")
        // Positive: PostDetailRoute declares the expected non-PII display fields.
        assertTrue(navKeys.contains("data class PostDetailRoute"), "PostDetailRoute is a payload-carrying data class")
        val declaredFields =
            listOf(
                "postId", "content", "cityName", "distanceM", "createdAtIso",
                "likedByViewer", "replyCount", "authorUsername", "authorDisplayName",
            )
        for (field in declaredFields) {
            assertTrue(navKeys.contains(field), "PostDetailRoute must declare $field")
        }
    }

    @Test
    fun httpClientFactory_logLevelIsNotWidenedPastHeaders() {
        val factory = code("mobile/app/src/commonMain/kotlin/id/nearyou/app/network/HttpClientFactory.kt")
        assertTrue(factory.contains("LogLevel.HEADERS"), "the client log level must remain LogLevel.HEADERS")
        assertFalse(factory.contains("LogLevel.BODY"), "logging must NOT be widened to LogLevel.BODY")
        assertFalse(factory.contains("LogLevel.ALL"), "logging must NOT be widened to LogLevel.ALL")
    }

    @Test
    fun postDetailClientsAndRepository_neverLogBodies() {
        val files =
            listOf(
                "LikeApiClient" to code("mobile/app/src/commonMain/kotlin/id/nearyou/app/post/LikeApiClient.kt"),
                "ReplyApiClient" to code("mobile/app/src/commonMain/kotlin/id/nearyou/app/post/ReplyApiClient.kt"),
                "PostDetailRepository" to code("mobile/app/src/commonMain/kotlin/id/nearyou/app/post/PostDetailRepository.kt"),
            )
        for ((name, src) in files) {
            assertFalse(src.contains("println"), "$name must not println (bodies/coordinates must never be logged)")
            assertFalse(src.contains("LogLevel.BODY"), "$name must not widen logging to BODY")
            assertFalse(src.contains("LogLevel.ALL"), "$name must not widen logging to ALL")
        }
    }

    @Test
    fun replyApiClient_parsesNextCursorButIssuesNoCursorRequest() {
        val replyApi = code("mobile/app/src/commonMain/kotlin/id/nearyou/app/post/ReplyApiClient.kt")
        // The DTO retains next_cursor…
        assertTrue(replyApi.contains("next_cursor"), "ReplyListResponse must parse the snake_case next_cursor")
        // …but no cursor=-bearing GET is issued (load-more is deferred — design D9).
        assertFalse(replyApi.contains("parameter(\"cursor\""), "no cursor= request may be issued (replies load-more is deferred)")
    }

    // Note: deferral bookkeeping (block/report kebab, inline-card actions, by-id endpoint, replies
    // load-more) is tracked as GitHub issues (label `follow-up`), not in a repo file — see the
    // matching `mobile-post-detail` spec scenarios. No source-file assertion covers it here.

    private companion object {
        fun findRepoRoot(): File {
            var dir: File? = File(System.getProperty("user.dir")).canonicalFile
            while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
                dir = dir.parentFile
            }
            return dir ?: error("could not locate the repo root (settings.gradle.kts) from ${System.getProperty("user.dir")}")
        }
    }
}
