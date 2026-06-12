package id.nearyou.app.screens.shell

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Strips block comments (incl. KDoc) then line comments, so an explanatory comment in a scanned file
 *  mentioning a forbidden token does not trip the scan (per `feedback_source_scan_guard_strip_comments`
 *  / task 10.5). */
private fun String.stripComments(): String {
    val noBlock = Regex("""/\*[\s\S]*?\*/""").replace(this, " ")
    return noBlock.lineSequence().joinToString("\n") { it.substringBefore("//") }
}

/**
 * `mobile-design-system` substrate source-scan guard (mobile-home-shell-redesign task 10.3b) mechanizing
 * the inset-ownership + Material-icon + label-visibility rules over the 4 Home-surface files the change
 * declares (per the `mobile-design-system` § "Only the shell declares a Scaffold" scenario, which inspects
 * exactly `AppShellScreen.kt`, `HomeScreen.kt`, `NearbyTimelineScreen.kt`, `GlobalTimelineScreen.kt`):
 *
 * - **Exactly one `Scaffold` in the authenticated Home tree** — only `AppShellScreen` declares one;
 *   `HomeScreen` + both timeline content composables declare NO `Scaffold` and NO `TopAppBar` (the
 *   nested-Scaffold fix, design D1). (The Notifikasi section's own `Scaffold` is tracked separately by
 *   follow-up issue #205 (`mobile-notifications-inset-free-substrate-migration`) — out of this change's file scope.)
 * - **Inset consumption** — `AppShellScreen` consumes the shell padding (`consumeWindowInsets`).
 * - **Material icon affordances, not dots** — the shell nav + the post cards use `painterResource(Res.drawable.*)`
 *   and declare NO `CircleShape` placeholder dot; `HomeScreen`'s text-only tabs declare no `CircleShape` either.
 * - **Default M3 item theming** — the shell nav items use `NavigationBarItemDefaults` colors, and the feed
 *   `Tab`s pass NO custom `selectedContentColor`/`unselectedContentColor` (so labels stay visible — D5).
 *
 * Needles are assembled from fragments AND the scanned files are comment-stripped, so neither the scanned
 * files' KDoc nor this guard's own source trips it. Runs in every variant (NOT a Compose UI test).
 */
class ShellAndTimelineSourceGuardTest {
    // Assembled (not contiguous literals) so a scan that ever includes this guard does not flag it.
    private val scaffoldNeedle = "Scaf" + "fold("
    private val topAppBarNeedle = "Top" + "AppBar"
    private val circleShapeNeedle = "Circle" + "Shape"

    // Split: the shell passes a drawable VARIABLE to painterResource (painterResource(iconFilled)), while
    // the cards pass Res.drawable.* inline — so a "painterResource(Res.drawable." literal would miss the
    // shell. Assert painterResource(…) AND a Res.drawable.* reference are both present instead.
    private val painterNeedle = "painter" + "Resource("
    private val drawableRefNeedle = "Res." + "drawable."
    private val consumeInsetsNeedle = "consume" + "WindowInsets("
    private val navDefaultsNeedle = "NavigationBarItem" + "Defaults"
    private val selectedColorNeedle = "selected" + "ContentColor"
    private val unselectedColorNeedle = "unselected" + "ContentColor"

    // The card-time clock glyph is REMOVED by mobile-timeline-card-redesign (time renders as text in
    // the identity header) — the shared card must never reference it again.
    private val clockGlyphNeedle = "ic_post_" + "time"

    private val shell by lazy { read("shell/AppShellScreen.kt") }
    private val home by lazy { read("home/HomeScreen.kt") }
    private val nearby by lazy { read("timeline/NearbyTimelineScreen.kt") }
    private val global by lazy { read("timeline/GlobalTimelineScreen.kt") }

    // The ONE shared card (ui/components — mobile-timeline-card-redesign absorbed the per-screen
    // copies); the icon-affordance assertions moved here with it.
    private val postCard by lazy { readComponent("PostCard.kt") }

    @Test
    fun onlyTheShellDeclaresAScaffold_homeAndTimelinesAreInsetFree() {
        assertTrue(shell.contains(scaffoldNeedle), "AppShellScreen must declare the single inset-owning Scaffold")
        assertScaffoldAndTopBarAbsent("HomeScreen", home)
        assertScaffoldAndTopBarAbsent("NearbyTimelineScreen", nearby)
        assertScaffoldAndTopBarAbsent("GlobalTimelineScreen", global)
        // The shared card is shell-body content too — no Scaffold/TopAppBar of its own.
        assertScaffoldAndTopBarAbsent("PostCard", postCard)
    }

    private fun assertScaffoldAndTopBarAbsent(
        name: String,
        src: String,
    ) {
        assertFalse(src.contains(scaffoldNeedle), "$name must declare NO Scaffold (the shell owns the only one — design D1)")
        assertFalse(src.contains(topAppBarNeedle), "$name must declare NO TopAppBar (inset-free, no redundant header — design D1/D6)")
    }

    @Test
    fun theShellConsumesWindowInsets() {
        assertTrue(
            shell.contains(consumeInsetsNeedle),
            "AppShellScreen must consume the shell padding so nested content does not re-apply insets (design D1)",
        )
    }

    @Test
    fun navAndCardsUseMaterialIconDrawables_notDots() {
        // Shell nav items + the shared post card render Material icon drawables (painterResource over
        // a Res.drawable.*). The timeline screens no longer hold the cards (mobile-timeline-card-redesign
        // moved them to ui/components/PostCard.kt), so the card assertion targets the shared file.
        assertUsesIconDrawables("AppShellScreen", shell)
        assertUsesIconDrawables("PostCard", postCard)

        // …and NO CircleShape placeholder dot remains in the Home-surface screens. (The shared card's
        // LetterAvatar is a REAL circular avatar in its own file — an avatar, not a placeholder dot —
        // and is deliberately outside this needle's scope.)
        assertNoCircleShapeDot("AppShellScreen", shell)
        assertNoCircleShapeDot("HomeScreen", home)
        assertNoCircleShapeDot("NearbyTimelineScreen", nearby)
        assertNoCircleShapeDot("GlobalTimelineScreen", global)
        assertNoCircleShapeDot("PostCard", postCard)
    }

    // Hex color literals would bypass the NearYouTheme tokens (mobile-post-card § both-schemes
    // scenario: "the component source contains no hex color literals").
    private val hexColorNeedle = "Color(" + "0x"

    @Test
    fun cardComponentsUseThemeTokens_noHexColorLiterals() {
        val letterAvatar = readComponent("LetterAvatar.kt")
        assertFalse(postCard.contains(hexColorNeedle), "PostCard must use NearYouTheme tokens, not hex Color literals")
        assertFalse(letterAvatar.contains(hexColorNeedle), "LetterAvatar must use NearYouTheme tokens, not hex Color literals")
    }

    @Test
    fun cardTimeIsTextOnly_noClockGlyphReference() {
        // mobile-design-system § "Card time label is text-only in the identity header": the clock
        // glyph is gone from the card affordance set (docs/03 glyph list amended in the same PR).
        assertFalse(
            postCard.contains(clockGlyphNeedle),
            "PostCard must not reference the $clockGlyphNeedle glyph — time renders as text in the identity header",
        )
        assertFalse(nearby.contains(clockGlyphNeedle), "NearbyTimelineScreen must not reference the card clock glyph")
        assertFalse(global.contains(clockGlyphNeedle), "GlobalTimelineScreen must not reference the card clock glyph")
    }

    private fun assertNoCircleShapeDot(
        name: String,
        src: String,
    ) {
        assertFalse(
            src.contains(circleShapeNeedle),
            "$name must not render a CircleShape placeholder dot (real Material icons only — D4/D10)",
        )
    }

    private fun assertUsesIconDrawables(
        name: String,
        src: String,
    ) {
        assertTrue(src.contains(painterNeedle), "$name must render Material icons via painterResource(...)")
        assertTrue(src.contains(drawableRefNeedle), "$name must reference a bundled Res.drawable.* icon asset")
    }

    @Test
    fun itemColorsUseDefaultM3Theming_forLabelVisibility() {
        assertTrue(shell.contains(navDefaultsNeedle), "AppShellScreen nav items must use NavigationBarItemDefaults colors (D5)")
        // The feed Tabs pass NO custom content colors — they rely on the default Tab content color so the
        // selected label is always visible (the invisible-selected-label fix, D5).
        assertFalse(
            home.contains(selectedColorNeedle),
            "HomeScreen feed Tabs must NOT set a custom selectedContentColor (use the default — D5)",
        )
        assertFalse(
            home.contains(unselectedColorNeedle),
            "HomeScreen feed Tabs must NOT set a custom unselectedContentColor (use the default — D5)",
        )
    }

    private companion object {
        private const val COMMON_MAIN = "mobile/app/src/commonMain/kotlin/id/nearyou/app/screens"
        private const val UI_COMPONENTS = "mobile/app/src/commonMain/kotlin/id/nearyou/app/ui/components"

        fun read(relative: String): String = File(findRepoRoot(), "$COMMON_MAIN/$relative").readText().stripComments()

        fun readComponent(relative: String): String = File(findRepoRoot(), "$UI_COMPONENTS/$relative").readText().stripComments()

        /** Walks up to the repo root (the dir holding settings.gradle.kts) so the scan resolves whether
         *  `user.dir` is the module dir or the repo root. */
        fun findRepoRoot(): File {
            var dir: File? = File(System.getProperty("user.dir")).canonicalFile
            while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
                dir = dir.parentFile
            }
            return dir ?: error("could not locate the repo root (settings.gradle.kts) from ${System.getProperty("user.dir")}")
        }
    }
}
