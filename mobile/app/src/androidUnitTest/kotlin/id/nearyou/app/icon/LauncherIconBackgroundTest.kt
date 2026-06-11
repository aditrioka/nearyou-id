package id.nearyou.app.icon

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Strips XML comments. The flavor `colors.xml` files intentionally mention the OTHER environments'
 *  hex values in explanatory comments (e.g. "overrides androidMain's #1E4FD6"), so the scan MUST
 *  strip comments first or it would mis-read the override color (source-scan-guard precedent). */
private fun String.stripXmlComments(): String = Regex("""<!--[\s\S]*?-->""").replace(this, " ")

/**
 * Static-source guard for `mobile-env-launcher-icons`. The launcher-icon background is
 * environment-differentiated via Android flavor `res/` overrides (production keeps the brand cobalt;
 * dev/staging override it); the foreground glyph is identical across flavors. These are properties of
 * the committed resource tree, not of a render, so they are asserted by reading the files directly —
 * mirroring the repo's `PostCreationSourceGuardTest` / `VendorSdkLeakageScanTest` idiom (walk to the
 * repo root, read text, assert). This is NOT a `*ScreenTest`: it uses no Compose/Robolectric host, so
 * it runs in every variant and needs NO entry in `build.gradle.kts`'s release-variant test exclude.
 *
 * Maps to spec `shared-resources` requirement "Launcher icon background is environment-differentiated"
 * scenarios: dev→#15803D, staging→#C2410C, production-inherits (no override), foreground-identical.
 */
class LauncherIconBackgroundTest {
    private val repoRoot: File = findRepoRoot()

    private fun rawSource(relativePath: String): String {
        val file = File(repoRoot, relativePath)
        assertTrue(file.exists(), "expected file missing: $relativePath (resolved under $repoRoot)")
        return file.readText()
    }

    /** Extracts the `ic_launcher_background` hex from a `colors.xml`, comments stripped first. */
    private fun icLauncherBackground(relativePath: String): String {
        val stripped = rawSource(relativePath).stripXmlComments()
        val match =
            Regex("""<color\s+name="ic_launcher_background"\s*>\s*(#[0-9A-Fa-f]{6})\s*</color>""")
                .find(stripped)
                ?: error("no <color name=\"ic_launcher_background\"> found in $relativePath")
        return match.groupValues[1].uppercase()
    }

    // ---- per-flavor background hex (spec: dev/staging override; production base) ----

    @Test
    fun productionBaseBackground_isBrandCobalt() {
        assertEquals(
            "#1E4FD6",
            icLauncherBackground("mobile/app/src/androidMain/res/values/colors.xml"),
            "androidMain (production/base) launcher background must be brand cobalt #1E4FD6",
        )
    }

    @Test
    fun stagingFlavorBackground_isBurntOrange() {
        assertEquals(
            "#C2410C",
            icLauncherBackground("mobile/app/src/staging/res/values/colors.xml"),
            "staging flavor launcher background must be burnt orange #C2410C",
        )
    }

    @Test
    fun devFlavorBackground_isForestGreen() {
        assertEquals(
            "#15803D",
            icLauncherBackground("mobile/app/src/dev/res/values/colors.xml"),
            "dev flavor launcher background must be forest green #15803D",
        )
    }

    // ---- production inherits: no flavor override (spec: "Android production inherits…") ----

    @Test
    fun productionFlavor_hasNoLauncherBackgroundOverride() {
        val override = File(repoRoot, "mobile/app/src/production/res/values/colors.xml")
        assertFalse(
            override.exists(),
            "production must NOT override ic_launcher_background — it inherits androidMain's #1E4FD6",
        )
    }

    // ---- only the background is overridden (spec: "Foreground glyph is identical…") ----

    @Test
    fun nonProductionFlavors_overrideOnlyColorsXml() {
        for (flavor in listOf("dev", "staging")) {
            val resDir = File(repoRoot, "mobile/app/src/$flavor/res")
            assertTrue(resDir.isDirectory, "expected $flavor flavor res dir at ${resDir.path}")
            val relFiles =
                resDir.walkTopDown()
                    .filter { it.isFile }
                    .map { it.relativeTo(resDir).invariantSeparatorsPath }
                    .sorted()
                    .toList()
            // The dev flavor additionally carries the local-backend cleartext allowlist
            // (xml/network_security_config.xml — device-via-adb-reverse + emulator 10.0.2.2;
            // 2026-06-11 verification infra). It is NOT an icon resource, so the icon
            // invariant ("only the background changes") is untouched.
            val expected =
                if (flavor == "dev") {
                    listOf("values/colors.xml", "xml/network_security_config.xml")
                } else {
                    listOf("values/colors.xml")
                }
            assertEquals(
                expected,
                relFiles,
                "the $flavor flavor res tree must contain ONLY the expected files — no foreground, " +
                    "monochrome, round, or adaptive-icon XML override (only the background changes)",
            )
        }
    }

    // ---- foreground/background refs intact in the shared adaptive icons ----

    @Test
    fun sharedAdaptiveIcons_referenceSharedForegroundAndBackground() {
        for (icon in listOf("ic_launcher.xml", "ic_launcher_round.xml")) {
            val xml = rawSource("mobile/app/src/androidMain/res/mipmap-anydpi-v26/$icon")
            assertTrue(
                xml.contains("@drawable/ic_launcher_foreground"),
                "$icon must reference the shared @drawable/ic_launcher_foreground glyph",
            )
            assertTrue(
                xml.contains("@color/ic_launcher_background"),
                "$icon must reference @color/ic_launcher_background (the per-flavor-overridable color)",
            )
            assertTrue(
                xml.contains("@drawable/ic_launcher_monochrome"),
                "$icon must reference the shared @drawable/ic_launcher_monochrome layer (not env-overridden)",
            )
        }
    }

    // ---- iOS staging icon selection (static CI guard: the xcodebuild -showBuildSettings check
    //      can't run in Linux CI, so pin the source-of-truth files that drive that resolution) ----

    @Test
    fun iosStagingAppIconSet_existsAsSingleUniversalEntry() {
        val contents = rawSource("iosApp/iosApp/Assets.xcassets/AppIcon-Staging.appiconset/Contents.json")
        assertTrue(contents.contains("\"app-icon-1024.png\""), "AppIcon-Staging must reference app-icon-1024.png")
        assertTrue(contents.contains("\"1024x1024\""), "AppIcon-Staging entry must be 1024x1024")
        assertTrue(contents.contains("\"universal\""), "AppIcon-Staging must use the universal idiom")
        assertFalse(contents.contains("luminosity"), "AppIcon-Staging must be a single universal entry (no dark/tinted variants)")
        val png = File(repoRoot, "iosApp/iosApp/Assets.xcassets/AppIcon-Staging.appiconset/app-icon-1024.png")
        assertTrue(png.exists(), "the AppIcon-Staging 1024 PNG must be present")
    }

    @Test
    fun iosXcconfigs_selectStagingIconForNonProductionAndCobaltForProduction() {
        val stagingRe = Regex("""(?m)^[ \t]*ASSETCATALOG_COMPILER_APPICON_NAME\s*=\s*AppIcon-Staging""")
        val cobaltRe = Regex("""(?m)^[ \t]*ASSETCATALOG_COMPILER_APPICON_NAME\s*=\s*AppIcon(?![-\w])""")
        for (cfg in listOf("Config.xcconfig", "Staging.xcconfig")) {
            assertTrue(
                stagingRe.containsMatchIn(rawSource("iosApp/Configuration/$cfg")),
                "$cfg must set ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon-Staging",
            )
        }
        assertTrue(
            cobaltRe.containsMatchIn(rawSource("iosApp/Configuration/Production.xcconfig")),
            "Production.xcconfig must set ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon (cobalt production)",
        )
        // mobile-ios-build-config-matrix: the Dev env config selects the forest-green AppIcon-Dev.
        val devRe = Regex("""(?m)^[ \t]*ASSETCATALOG_COMPILER_APPICON_NAME\s*=\s*AppIcon-Dev""")
        assertTrue(
            devRe.containsMatchIn(rawSource("iosApp/Configuration/Dev.xcconfig")),
            "Dev.xcconfig must set ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon-Dev",
        )
    }

    @Test
    fun iosPbxproj_hasNoAppIconHardcode() {
        // mobile-ios-build-config-matrix review F2: the icon resolves per-config from xcconfig, so the
        // project.pbxproj target buildSettings must carry NO ASSETCATALOG_COMPILER_APPICON_NAME hardcode
        // (which would override the xcconfig). Linux-CI guard for the "no hardcode" spec scenario.
        val pbxproj = rawSource("iosApp/iosApp.xcodeproj/project.pbxproj")
        assertFalse(
            pbxproj.contains("ASSETCATALOG_COMPILER_APPICON_NAME"),
            "project.pbxproj must NOT hardcode ASSETCATALOG_COMPILER_APPICON_NAME — the icon resolves per-config from xcconfig",
        )
    }

    @Test
    fun iosDevAppIconSet_existsAsSingleUniversalEntry() {
        val contents = rawSource("iosApp/iosApp/Assets.xcassets/AppIcon-Dev.appiconset/Contents.json")
        assertTrue(contents.contains("\"app-icon-1024.png\""), "AppIcon-Dev must reference app-icon-1024.png")
        assertFalse(contents.contains("luminosity"), "AppIcon-Dev must be a single universal entry (no dark/tinted variants)")
        val png = File(repoRoot, "iosApp/iosApp/Assets.xcassets/AppIcon-Dev.appiconset/app-icon-1024.png")
        assertTrue(png.exists(), "the AppIcon-Dev 1024 PNG must be present")
    }

    // ---- iOS per-config xcconfig + KMP build-type wiring (mobile-ios-build-config-matrix). Each leaf
    //      `<Config>.xcconfig` layers its env xcconfig + the build-type-correct Pods base, and each
    //      custom config name maps to a Kotlin/Native build type in build.gradle.kts. These drive the
    //      per-config Pods link + the `ComposeApp` framework sync; the real `xcodebuild build` proof
    //      can't run in Linux CI, so pin both statically (a rename here would silently break the
    //      framework build with CI green — the gap the impl-review test-coverage lens flagged). ----

    @Test
    fun iosPerConfigXcconfigs_layerEnvXcconfigAndMatchingPodsBase() {
        // config → (env xcconfig it #includes, matching Pods base it #includes)
        val matrix =
            listOf(
                Triple("Dev Debug", "Dev.xcconfig", "Pods-iosApp.dev debug.xcconfig"),
                Triple("Staging Debug", "Staging.xcconfig", "Pods-iosApp.staging debug.xcconfig"),
                Triple("Prod Debug", "Production.xcconfig", "Pods-iosApp.prod debug.xcconfig"),
                Triple("Prod Release", "Production.xcconfig", "Pods-iosApp.prod release.xcconfig"),
            )
        for ((config, envInclude, podsBase) in matrix) {
            val xcconfig = rawSource("iosApp/Configuration/$config.xcconfig")
            assertTrue(
                xcconfig.contains("#include \"$envInclude\""),
                "the `$config` per-config xcconfig must #include its env xcconfig \"$envInclude\"",
            )
            assertTrue(
                xcconfig.contains(podsBase),
                "the `$config` per-config xcconfig must #include its matching Pods base \"$podsBase\" " +
                    "(debug-typed config → debug Pods, release-typed → release Pods)",
            )
        }
    }

    @Test
    fun buildGradle_mapsAllFourMatrixConfigsToNativeBuildType() {
        // Without these, the KMP cocoapods plugin fails the ComposeApp framework sync with
        // "Could not identify build type for Kotlin framework 'ComposeApp' ... CONFIGURATION=Prod Release".
        val gradle = rawSource("mobile/app/build.gradle.kts")
        val mappings =
            listOf(
                "Dev Debug" to "DEBUG",
                "Staging Debug" to "DEBUG",
                "Prod Debug" to "DEBUG",
                "Prod Release" to "RELEASE",
            )
        for ((config, type) in mappings) {
            val re =
                Regex(
                    """xcodeConfigurationToNativeBuildType\[\s*"${Regex.escape(config)}"\s*]\s*=\s*NativeBuildType\.$type""",
                )
            assertTrue(
                re.containsMatchIn(gradle),
                "build.gradle.kts must map xcodeConfigurationToNativeBuildType[\"$config\"] = NativeBuildType.$type",
            )
        }
    }

    @Test
    fun iosStagingIconSource_usesStagingTintAndGeneratorViewBox() {
        val svg = rawSource("dev/assets/icon-src/icon-staging.svg")
        assertTrue(svg.contains("#C2410C"), "the iOS staging icon source SVG must use the staging tint #C2410C")
        assertTrue(
            svg.contains("viewBox=\"0 0 108 108\""),
            "the source SVG must declare viewBox=\"0 0 108 108\" (generate-ios-app-icons.sh contract)",
        )
    }

    // ---- dormant alternate icon is NOT env-differentiated (spec: not touched by this change) ----

    @Test
    fun dormantAlternateIcon_remainsBlueOnWhite_notEnvTinted() {
        val alt = rawSource("mobile/app/src/androidMain/res/mipmap-anydpi-v26/ic_launcher_alt.xml")
        assertTrue(alt.contains("@android:color/white"), "the dormant alt icon must stay blue-on-white")
        assertTrue(alt.contains("@drawable/ic_launcher_foreground_alt"), "the alt icon must reference its own foreground")
        assertFalse(
            alt.contains("@color/ic_launcher_background"),
            "the alt icon must NOT use the env-differentiated background color (it is not env-tinted)",
        )
    }

    private companion object {
        fun findRepoRoot(): File {
            var dir: File? = File(System.getProperty("user.dir")).canonicalFile
            while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
                dir = dir.parentFile
            }
            return dir ?: error("could not locate repo root (settings.gradle.kts) from ${System.getProperty("user.dir")}")
        }
    }
}
