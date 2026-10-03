package id.nearyou.app.resources

import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.allStringResources
import id.nearyou.resources.generated.resources.empty_state_generic
import id.nearyou.resources.generated.resources.home_placeholder_title
import id.nearyou.resources.generated.resources.home_placeholder_version
import id.nearyou.resources.generated.resources.signin_error_no_account
import id.nearyou.resources.generated.resources.signin_screen_title
import id.nearyou.resources.generated.resources.tab_following_icon_description
import id.nearyou.resources.generated.resources.tab_global_icon_description
import id.nearyou.resources.generated.resources.tab_nearby_icon_description
import id.nearyou.resources.generated.resources.timeline_nearby_title
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mirrors the `<string>` catalog 1:1 with NO hand-maintained list. The declared key set is parsed
 * from `strings.xml` at test time and compared, both ways, against the CMP-generated
 * `Res.allStringResources` map: every declared key has a generated accessor, and every generated
 * accessor has a default-locale declaration (no orphan accessor, e.g. a key that only exists in a
 * future `values-<qualifier>/strings.xml`, which would crash at runtime on the default locale).
 * A duplicated name already fails the CMP resource build, so it isn't re-checked here.
 * Adding a string needs no edit here. The earlier version kept a manual accessor list plus a pinned
 * count, which every string-adding PR had to bump. It drifted twice: 118 tracked vs 190 declared
 * (#240), then 243 vs 364 (#479).
 *
 * Production use sites are the compile-time guard for rendered keys (a removed/renamed key breaks
 * the screen that imports it). The few keys deliberately kept in the catalog although no screen
 * renders them have no such use site, so [retainedUnrendered] pins their accessors here.
 *
 * Lives in androidUnitTest (not commonTest) because it reads the repo file. The catalog is
 * platform-agnostic, so the JVM lane is enough.
 */
class SharedStringsCatalogTest {
    private val declaredKeys: Set<String> by lazy {
        val file = File(findRepoRoot(), STRINGS_XML)
        assertTrue(file.exists(), "expected $STRINGS_XML")
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("name") }.toSet()
    }

    // Kept on purpose, rendered nowhere: shared-resources § "Foundational Bahasa Indonesia string
    // surface" + § Nearby timeline strings (home_placeholder_* SHALL be RETAINED), mobile-auth-signin
    // § "signin_screen_title is retained in the shared catalog", and the tab_*_icon_description keys
    // the strings.xml mobile-home-shell-redesign (D10) note keeps after the tabs went text-only.
    // Rendered keys are NOT listed: their screens already pin them.
    private val retainedUnrendered =
        listOf(
            Res.string.empty_state_generic,
            Res.string.home_placeholder_title,
            Res.string.home_placeholder_version,
            Res.string.signin_screen_title,
            Res.string.signin_error_no_account,
            Res.string.timeline_nearby_title,
            Res.string.tab_nearby_icon_description,
            Res.string.tab_following_icon_description,
            Res.string.tab_global_icon_description,
        )

    @Test
    fun `strings xml declarations and generated Res string accessors match 1 to 1`() {
        val generated = Res.allStringResources.keys
        assertEquals(emptySet(), declaredKeys - generated, "declared in $STRINGS_XML but no generated Res.string accessor")
        assertEquals(emptySet(), generated - declaredKeys, "generated Res.string accessor with no $STRINGS_XML declaration")
    }

    @Test
    fun `retained unrendered strings stay declared in the default catalog`() {
        assertEquals(emptySet(), retainedUnrendered.map { it.key }.toSet() - declaredKeys)
    }

    private companion object {
        const val STRINGS_XML = "shared/resources/src/commonMain/composeResources/values/strings.xml"

        fun findRepoRoot(): File {
            var dir: File? = File(System.getProperty("user.dir")).canonicalFile
            while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
                dir = dir.parentFile
            }
            return dir ?: error("could not locate the repo root (settings.gradle.kts) from ${System.getProperty("user.dir")}")
        }
    }
}
