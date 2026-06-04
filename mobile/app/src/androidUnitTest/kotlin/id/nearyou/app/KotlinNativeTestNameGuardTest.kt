package id.nearyou.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Repo-wide guard: every backtick-quoted test function name compiled into the iOS test binary must
 * be a legal Kotlin/Native symbol. Kotlin/Native forbids `,` `(` `)` `#` in test function names —
 * such names compile fine on the JVM (the Android/Robolectric `testDevDebugUnitTest` run) but fail
 * `compileTestKotlinIosSimulatorArm64` with "Name contains illegal characters", and that aborts the
 * WHOLE iOS test compile, so no Kotlin/Native test runs at all. Because CI is Linux-only (no iOS
 * target), the breakage is invisible until someone runs `:mobile:app:iosSimulatorArm64Test` on a
 * Mac. This JVM scan runs in CI and keeps the shared/iOS test source Kotlin/Native-clean for free.
 *
 * It scans every source set that feeds the iOS test compilation — `commonTest` AND `iosTest` — not
 * just `commonTest`, since both compile into the same `iosSimulatorArm64Test` binary. Mirrors the
 * file-scan idiom of [id.nearyou.app.post.PostCreationSourceGuardTest] / the `:lint:detekt-rules`
 * `VendorSdkLeakageScanTest` (walk to the repo root, read `.kt` text, assert). Comments are stripped
 * first so an explanatory note that mentions a forbidden token near `fun ...` cannot trip the scan
 * (the PR #145 lesson). Spaces, `-`, `_`, and `'` are legal in Kotlin/Native names and are NOT
 * flagged.
 *
 * Precedent: PR #145 introduced two such names in `PostCreationUiStateTest` (one with `,`, one with
 * `()`) and the iOS test compile silently broke. See the repo memory note "Enabling iOS unit tests:
 * K/N naming + iosTest placement".
 */
class KotlinNativeTestNameGuardTest {
    @Test
    fun nativeTestSourceSets_haveNoKotlinNativeIllegalFunctionNames() {
        val repoRoot = findRepoRoot()
        // The source sets that compile into the iosSimulatorArm64Test binary. commonTest is always
        // present; iosTest holds the iOS UI flow tests and is scanned when present.
        val roots = listOf("mobile/app/src/commonTest", "mobile/app/src/iosTest").map { File(repoRoot, it) }
        assertTrue(
            roots.first().isDirectory,
            "commonTest source root missing: ${roots.first()} (resolved under $repoRoot)",
        )

        val ktFiles =
            roots
                .filter { it.isDirectory }
                .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

        var scannedNames = 0
        val offenders = mutableListOf<String>()
        for (file in ktFiles) {
            for (match in FUN_NAME.findAll(file.readText().stripComments())) {
                scannedNames++
                val name = match.groupValues[1]
                val bad = FORBIDDEN.filter { name.contains(it) }
                if (bad.isEmpty()) continue
                val chars = bad.joinToString(" ") { "'$it'" }
                offenders += "  - ${file.toRelativeString(repoRoot)} -> `$name` contains $chars"
            }
        }

        // Guard against a vacuous pass: a moved source root or a broken regex would scan nothing.
        assertTrue(scannedNames > 0, "scanned no backtick test function names — the scan path or regex is wrong")

        assertTrue(
            offenders.isEmpty(),
            buildString {
                appendLine("Kotlin/Native forbids , ( ) # in test function names; the names below compile on the")
                appendLine("JVM but break `:mobile:app:iosSimulatorArm64Test` (compileTestKotlinIosSimulatorArm64):")
                offenders.forEach { appendLine(it) }
                append("Rename them (names only — no behavior change) using spaces / - / _ instead.")
            },
        )
    }

    private companion object {
        /** Forbidden inside a Kotlin/Native test function name (all legal on the JVM). */
        val FORBIDDEN = listOf(',', '(', ')', '#')

        /** `fun` immediately followed by a backtick-quoted identifier; captures the name only. A
         *  backtick identifier never spans lines, so newlines are excluded defensively. */
        val FUN_NAME = Regex("""\bfun\s+`([^`\n]+)`""")

        /** Walks up from the test working directory to the repo root (the dir holding
         *  settings.gradle.kts), so the scan resolves whether `user.dir` is the module dir or root. */
        fun findRepoRoot(): File {
            var dir: File? = File(System.getProperty("user.dir")).canonicalFile
            while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
                dir = dir.parentFile
            }
            return dir ?: error("could not locate the repo root (settings.gradle.kts) from ${System.getProperty("user.dir")}")
        }

        /** Strips block comments (incl. KDoc) then line comments — mirrors
         *  PostCreationSourceGuardTest.stripComments. The scanned `.kt` files have no `//` inside
         *  string literals that would affect `fun ...` detection, so the naive line strip is safe. */
        fun String.stripComments(): String {
            val noBlock = Regex("""/\*[\s\S]*?\*/""").replace(this, " ")
            return noBlock.lineSequence().joinToString("\n") { it.substringBefore("//") }
        }
    }
}
