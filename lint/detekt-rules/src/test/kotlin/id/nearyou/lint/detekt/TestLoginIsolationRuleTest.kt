package id.nearyou.lint.detekt

import io.gitlab.arturbosch.detekt.test.lint
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class TestLoginIsolationRuleTest : StringSpec({

    val rule = TestLoginIsolationRule()

    // ---- FIRES: test-login symbols declared in shipping source sets -------------------------
    // Synthetic `lint(String)` files get a virtualFilePath of "Test.kt" (no /src/dev/ or
    // /src/stagingDebug/ segment), so they stand in for "declared in a shipping source set".

    "class containing TestLogin in a shipping (non-dev/staging) file fires" {
        val code =
            """
            package id.nearyou.app.screens.routing

            class StagingTestLoginActivity {
                fun go() = Unit
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "object containing TestLogin fires" {
        val code =
            """
            package id.nearyou.app.util

            object TestLoginHelper
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "top-level function containing TestLogin fires" {
        val code =
            """
            package id.nearyou.app.util

            fun bootstrapTestLoginSession(): String = "boom"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "CamelCase 'Testlogin' (capital T, lowercase l) fires" {
        val code =
            """
            package id.nearyou.app.util

            class FakeTestloginInjector
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "unrelated symbol without TestLogin does not fire" {
        val code =
            """
            package id.nearyou.app.screens.auth

            class SignInScreen {
                fun login() = Unit
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    // Guard the most likely false positive: 'latestLogin' / 'greatestLogin' contain the lowercase
    // substring 'testlogin' but no capital-T 'Test' segment, so they MUST NOT fire.
    "'latestLogin' / 'LatestLoginScreen' style identifiers do NOT fire" {
        val code =
            """
            package id.nearyou.app.profile

            class LatestLoginScreen {
                val greatestLoginStreak: Int = 0
                fun latestLogin(): String = "ok"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    // ---- PASSES: the two allowlisted source sets (real file paths) ---------------------------

    "DevTestLoginActivity under .../src/dev/ PASSES" {
        val dir = Files.createTempDirectory("test-login-rule-dev")
        val path =
            dir.resolve("src").resolve("dev").resolve("kotlin")
                .resolve("DevTestLoginActivity.kt")
        Files.createDirectories(path.parent)
        path.writeText(
            """
            package id.nearyou.app.dev

            class DevTestLoginActivity
            """.trimIndent(),
        )
        try {
            rule.lint(path).shouldBeEmpty()
        } finally {
            cleanupDir(dir)
        }
    }

    "StagingTestLoginActivity under .../src/stagingDebug/ PASSES" {
        val dir = Files.createTempDirectory("test-login-rule-staging-debug")
        val path =
            dir.resolve("src").resolve("stagingDebug").resolve("kotlin")
                .resolve("StagingTestLoginActivity.kt")
        Files.createDirectories(path.parent)
        path.writeText(
            """
            package id.nearyou.app.staging

            class StagingTestLoginActivity
            """.trimIndent(),
        )
        try {
            rule.lint(path).shouldBeEmpty()
        } finally {
            cleanupDir(dir)
        }
    }

    // ---- FIRES: staging FLAVOR-WIDE is NOT allowlisted (enforces staging-debug-only) ---------

    "test-login symbol under .../src/staging/ (flavor-wide) STILL FIRES" {
        val dir = Files.createTempDirectory("test-login-rule-staging-wide")
        val path =
            dir.resolve("src").resolve("staging").resolve("kotlin")
                .resolve("StagingTestLoginActivity.kt")
        Files.createDirectories(path.parent)
        path.writeText(
            """
            package id.nearyou.app.staging

            class StagingTestLoginActivity
            """.trimIndent(),
        )
        try {
            rule.lint(path) shouldHaveSize 1
        } finally {
            cleanupDir(dir)
        }
    }

    "test-login symbol under .../src/production/ FIRES" {
        val dir = Files.createTempDirectory("test-login-rule-prod")
        val path =
            dir.resolve("src").resolve("production").resolve("kotlin")
                .resolve("ProdTestLoginBackdoor.kt")
        Files.createDirectories(path.parent)
        path.writeText(
            """
            package id.nearyou.app.production

            class ProdTestLoginBackdoor
            """.trimIndent(),
        )
        try {
            rule.lint(path) shouldHaveSize 1
        } finally {
            cleanupDir(dir)
        }
    }
})

private fun cleanupDir(dir: Path) {
    Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
}
