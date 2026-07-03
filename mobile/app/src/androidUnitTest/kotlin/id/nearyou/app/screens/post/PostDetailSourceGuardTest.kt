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
 * the clients + repository never `println`/log); no BLOCK affordance (report now ships via
 * mobile-content-report — only block stays deferred); the replies cursor drives load-more (a `cursor=`-bearing
 * request IS issued); and deferral bookkeeping (tracked as `follow-up` GitHub issues: block #200 — report
 * half now shipped, inline-card #201, by-id #202; replies infinite-scroll #188 is implemented).
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

    // mobile-block-from-content spec § "No hardcoded block strings": the shared dialog + the block menu
    // items resolve every user-facing string via Res.string.* (the canonical docs/03 copy lives in
    // shared/resources, not in the composables).
    @Test
    fun blockConfirmDialog_hasNoHardcodedUiStringLiterals() {
        val dialog = code("mobile/app/src/commonMain/kotlin/id/nearyou/app/ui/components/BlockConfirmDialog.kt")
        assertFalse(dialog.contains("Text(\""), "BlockConfirmDialog has a hardcoded Text(\"…\") literal")
        assertFalse(Regex("""text\s*=\s*"""").containsMatchIn(dialog), "BlockConfirmDialog has a hardcoded text = \"…\" literal")
        assertTrue(dialog.contains("stringResource(Res.string.profile_block_confirm_title"), "the title must use stringResource")
        assertTrue(dialog.contains("stringResource(Res.string.profile_block_confirm_body)"), "the body must use stringResource")
        assertTrue(dialog.contains("stringResource(Res.string.cta_block)"), "the confirm must use stringResource")
        assertTrue(dialog.contains("stringResource(Res.string.cta_cancel)"), "the dismiss must use stringResource")
        // The block menu items on the screen interpolate the username via the parameterized resource.
        assertTrue(screen.contains("stringResource(Res.string.profile_block_action,"), "the block menu label must use the parameterized resource")
    }

    @Test
    fun postDetailScreen_holdsNoBackStackReference() {
        // Navigation comes only via the hoisted onBack lambda (spec § "PostDetailScreen holds no back-stack reference").
        assertFalse(screen.contains("NavBackStack"), "the screen must not reference NavBackStack")
        assertFalse(screen.contains("backStack"), "the screen must not perform its own back-stack mutation")
        assertTrue(screen.contains("onBack"), "the screen takes navigation via the hoisted onBack lambda")
    }

    // mobile-block-from-content UN-defers the post/reply block: the screen now hosts the shared
    // BlockConfirmDialog + the VM-wired block menu items. The deferral guard MOVES to the timeline card:
    // PostCard stays block-free (footprint-disjoint from in-flight #354 — the spec's
    // "Timeline-card block entry point is deferred" negative guard).
    @Test
    fun postDetailScreen_hasBlockAffordance_andPostCardStaysBlockFree() {
        assertTrue(screen.contains("BlockConfirmDialog"), "PostDetailScreen hosts the shared block dialog (mobile-block-from-content)")
        assertTrue(screen.contains("onBlockPostClicked"), "the post-header block wires through the VM")
        assertTrue(screen.contains("onBlockReplyClicked"), "the reply-row block wires through the VM")
        val postCard = code("mobile/app/src/commonMain/kotlin/id/nearyou/app/ui/components/PostCard.kt")
        assertFalse(postCard.contains("BlockConfirmDialog"), "PostCard must carry no block dialog (timeline-card block deferred)")
        assertFalse(postCard.contains("BlockSubmitter"), "PostCard must issue no block call (timeline-card block deferred)")
        assertFalse(postCard.contains("profile_block_action"), "PostCard must carry no block menu item (timeline-card block deferred)")
    }

    // mobile-block-from-content spec § "A single shared block-create seam": exactly ONE source file may
    // issue POST /api/v1/blocks/{userId} — the data/block/BlockSubmitter. A second call site is the
    // patchwork failure mode the shared seam exists to prevent.
    @Test
    fun exactlyOneBlockCreateCallSite() {
        val commonMain = File(repoRoot, "mobile/app/src/commonMain")
        val hits =
            commonMain
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { it.readText().stripComments().contains("post(\"/api/v1/blocks/") }
                .map { it.name }
                .sorted()
                .toList()
        assertTrue(
            hits == listOf("BlockSubmitter.kt"),
            "exactly ONE block-create call site (data/block/BlockSubmitter.kt) may POST /api/v1/blocks/{userId}; found: $hits",
        )
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
    fun replyApiClient_wiresCursorLoadMore() {
        val replyApi = code("mobile/app/src/commonMain/kotlin/id/nearyou/app/post/ReplyApiClient.kt")
        // The DTO retains the snake_case next_cursor…
        assertTrue(replyApi.contains("next_cursor"), "ReplyListResponse must parse the snake_case next_cursor")
        // …and load-more now issues a cursor=-bearing GET (mobile-nearby-timeline-infinite-scroll, #188).
        assertTrue(replyApi.contains("parameter(\"cursor\""), "replies load-more must issue a cursor= request")
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
