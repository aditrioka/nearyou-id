package id.nearyou.app.screens.post

import kotlinx.coroutines.flow.StateFlow
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * Covers: no-hardcoded-UI-strings on the screen + the shared `BlockConfirmDialog`; the screen holds no
 * back-stack reference; `PostDetailRoute` declares no coordinate; the never-widen-logging discipline
 * (`HttpClientFactory` stays `LogLevel.HEADERS`; the clients + repository never `println`/log); the
 * mobile-block-from-content structural guards (the screen HAS the block affordance — #200 un-deferred —
 * while `PostCard` stays block-free per the timeline-card deferral #456, and exactly ONE block-create
 * call site exists); the replies cursor drives load-more (a `cursor=`-bearing request IS issued); and
 * deferral bookkeeping (inline-card #201, by-id #202; replies infinite-scroll #188 is implemented).
 */
class PostDetailSourceGuardTest {
    private val repoRoot: File = findRepoRoot()

    private fun rawSource(relativePath: String): String {
        val file = File(repoRoot, relativePath)
        assertTrue(file.exists(), "expected source file missing: $relativePath (resolved under $repoRoot)")
        return file.readText()
    }

    private fun code(relativePath: String): String = rawSource(relativePath).stripComments()

    private val postDir = "mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/post"

    /** The four post-detail UI files (post-detail-vm-reply-delete-restyle split the 1161-LOC screen into the
     *  screen + header / replies / composer files), scanned together as "the screen". */
    private val uiFiles = listOf("PostDetailScreen.kt", "PostDetailHeader.kt", "PostDetailReplies.kt", "PostDetailComposer.kt")

    private val screen by lazy { uiFiles.joinToString("\n") { code("$postDir/$it") } }

    private val viewModel by lazy { code("$postDir/PostDetailViewModel.kt") }

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
        assertTrue(
            screen.contains("stringResource(Res.string.profile_block_action,"),
            "the block menu label must use the parameterized resource",
        )
    }

    @Test
    fun postDetailScreen_holdsNoBackStackReference() {
        // Navigation comes only via the hoisted onBack lambda (spec § "PostDetailScreen holds no back-stack reference").
        // The screen + its split-out header / replies / composer files (scanned together).
        assertFalse(screen.contains("NavBackStack"), "the screen must not reference NavBackStack")
        assertFalse(screen.contains("backStack"), "the screen must not perform its own back-stack mutation")
        assertTrue(screen.contains("onBack"), "the screen takes navigation via the hoisted onBack lambda")
    }

    // mobile-block-from-content UN-defers the post/reply block: the screen hosts the shared
    // BlockConfirmDialog + the VM-wired block menu items. timeline-card-block-kebab (#456) then
    // UN-deferred the timeline card too: PostCard carries the "Blokir @{username}" kebab item — but
    // stays presentation-only (the hoisted onBlock callback; the dialog + submission live in the feed
    // hosts via TimelineActionsOverlay/TimelineBlockController, never in the card).
    @Test
    fun postDetailScreen_hasBlockAffordance_andPostCardExposesTheBlockItemPresentationOnly() {
        assertTrue(screen.contains("BlockConfirmDialog"), "PostDetailScreen hosts the shared block dialog (mobile-block-from-content)")
        assertTrue(screen.contains("onBlockPostClicked"), "the post-header block wires through the VM")
        assertTrue(screen.contains("onBlockReplyClicked"), "the reply-row block wires through the VM")
        val postCard = code("mobile/app/src/commonMain/kotlin/id/nearyou/app/ui/components/PostCard.kt")
        assertTrue(
            postCard.contains("profile_block_action"),
            "PostCard carries the Blokir kebab item (timeline-card-block-kebab un-deferred #456)",
        )
        assertFalse(postCard.contains("BlockConfirmDialog"), "PostCard must not host the block dialog (presentation-only card)")
        assertFalse(postCard.contains("BlockSubmitter"), "PostCard must issue no block call (the shared-seam rule)")
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
                // The VM now holds authorUserId + the session id — it must never log either.
                "PostDetailViewModel" to viewModel,
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

    // cap-upsell-parity (mobile-cap-upsell-dialog § "The retired banner countdown no longer exists"): the
    // like / reply caps are the shared cap dialog — the inline cap banner + its coarse "jam" countdown are gone.
    @Test
    fun retiredCapBanner_isGone_andTheCapDialogIsUsed() {
        val uiState = code("mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/post/PostDetailUiState.kt")
        for (token in listOf("resetHours", "LikeCap", "ReplyCap")) {
            assertFalse(uiState.contains(token), "PostDetailUiState must not declare the retired cap banner ($token)")
        }
        val strings = rawSource("shared/resources/src/commonMain/composeResources/values/strings.xml")
        assertFalse(strings.contains("name=\"post_detail_reset_hours\""), "the retired banner countdown key must be gone")
        assertTrue(screen.contains("DailyCapUpsellDialog("), "post-detail surfaces its caps via the shared cap dialog")
    }

    // Note: deferral bookkeeping (block/report kebab, inline-card actions, by-id endpoint, replies
    // load-more) is tracked as GitHub issues (label `follow-up`), not in a repo file — see the
    // matching `mobile-post-detail` spec scenarios. No source-file assertion covers it here.

    // post-detail-vm-reply-delete-restyle (#542, docs/11 § 2.2): business work never launches from the
    // composables — the screen only constructs the VM from its injections; no composition scope launches a
    // repository call, and no injected dependency's member is called from UI code.
    @Test
    fun postDetailUi_launchesNoRepositoryWorkFromComposition() {
        assertFalse(screen.contains("rememberCoroutineScope"), "no composition-scoped launches (#542)")
        val call = Regex("""\b(flow|editFlow|selfUserIdProvider|reportSubmitter|blockSubmitter)\.\w+\(""")
        assertFalse(call.containsMatchIn(screen), "UI code must not call an injected dependency: ${call.find(screen)?.value}")
        for (file in uiFiles.drop(1)) {
            val src = code("$postDir/$file")
            for (seam in listOf(
                "PostDetailFlow",
                "PostEditFlow",
                "SelfUserIdProvider",
                "ReportSubmitter",
                "BlockSubmitter",
                "koinInject",
            )) {
                assertFalse(src.contains(seam), "$file must stay presentation-only (found $seam)")
            }
        }
    }

    @Test
    fun postDetailViewModel_exposesOneStateStream_andNoEventBus() {
        // Reflection, not a regex: catches `internal val`, inferred `= x.asStateFlow()`, and any getter shape.
        val stateFlowGetters =
            PostDetailViewModel::class.java.methods
                // Skip compiler-synthetic accessors (e.g. access$getState$p for the private state).
                .filter { !it.isSynthetic && StateFlow::class.java.isAssignableFrom(it.returnType) }
                .map { it.name }
        assertEquals(listOf("getUiState"), stateFlowGetters, "exactly one public StateFlow (uiState)")
        assertFalse(viewModel.contains("Channel"), "one-shots are state, never a Channel")
        assertFalse(viewModel.contains("SharedFlow"), "one-shots are state, never a SharedFlow")
    }

    // The frame-7 deferrals (#569 / #570 / #575) as structural negative guards.
    @Test
    fun frame7Deferrals_noComposerAvatar_noReplyLike_noReplyPremiumBadge() {
        // The composer bar is built in PostDetailComposer.kt and slotted from PostDetailScreen.kt's bottomBar.
        for (file in listOf("PostDetailComposer.kt", "PostDetailScreen.kt")) {
            assertFalse(code("$postDir/$file").contains("LetterAvatar"), "$file: the composer self-avatar is deferred (#569)")
        }
        val replies = code("$postDir/PostDetailReplies.kt")
        assertFalse(replies.contains("ic_post_like"), "per-reply likes are deferred (#570)")
        // The app's identity-badge idiom (ProfileScreen / FollowListScreen): ic_premium_star + the *_premium_badge_* copy.
        for (token in listOf("ic_premium_star", "premium_badge", "workspace_premium", "premiumBadge")) {
            assertFalse(replies.contains(token), "the reply-author Premium badge is deferred (#575): found $token")
        }
        assertFalse(screen.contains("follow", ignoreCase = true), "the header Ikuti button is deferred (#569)")
    }

    // The composer sits directly on the IME only when the activity resizes for it (edge-to-edge guidance;
    // without adjustResize the system panned the window AND imePadding lifted the bar again).
    @Test
    fun mainActivity_resizesForTheIme() {
        val manifest = rawSource("mobile/app/src/androidMain/AndroidManifest.xml")
        assertTrue(manifest.contains("android:windowSoftInputMode=\"adjustResize\""), "MainActivity must declare adjustResize")
    }

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
