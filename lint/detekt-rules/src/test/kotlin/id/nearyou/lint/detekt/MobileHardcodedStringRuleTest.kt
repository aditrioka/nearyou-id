package id.nearyou.lint.detekt

import io.gitlab.arturbosch.detekt.test.lint
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import java.nio.file.Files
import kotlin.io.path.writeText

class MobileHardcodedStringRuleTest : StringSpec({

    val rule = MobileHardcodedStringRule()

    // ---- FIRES: literal UI copy (synthetic `lint(String)` files stand in for a shipping source set) ----

    "positional Text literal fires" {
        rule.lint("""fun s() { Text("Masuk") }""") shouldHaveSize 1
    }

    "named text = literal fires, including the multi-line shape a grep would miss" {
        val code =
            """
            fun s() {
                Text(
                    modifier = Modifier,
                    text = "Kamu sudah mencapai batas baca",
                )
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "contentDescription = literal fires on any call" {
        rule.lint("""fun s() { Icon(imageVector = Icons.Back, contentDescription = "Kembali") }""") shouldHaveSize 1
    }

    "template with literal words fires" {
        rule.lint("""fun s(n: Int) { Text("${'$'}n postingan") }""") shouldHaveSize 1
    }

    // ---- PASSES ----

    "stringResource accessor passes" {
        val code =
            """
            fun s() {
                Text(stringResource(Res.string.cta_post))
                Icon(Icons.Back, contentDescription = stringResource(Res.string.cd_back))
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "formatting-only templates (no letters in the static parts) pass" {
        rule.lint("""fun s(u: String, a: Int, b: Int) { Text(text = "@${'$'}u"); Text("${'$'}a · ${'$'}b") }""").shouldBeEmpty()
    }

    "contentDescription = null passes" {
        rule.lint("""fun s() { Icon(Icons.Back, contentDescription = null) }""").shouldBeEmpty()
    }

    "non-text literal arguments of Text pass (e.g. a testTag)" {
        rule.lint("""fun s(t: String) { Text(t, modifier = Modifier.testTag("feedList")) }""").shouldBeEmpty()
    }

    "detekt-native @Suppress is the escape hatch" {
        rule.lint("""@Suppress("MobileHardcodedStringRule") fun s() { Text("NearYouID") }""").shouldBeEmpty()
    }

    "a literal under a test source set (src/androidUnitTest/) passes" {
        val dir = Files.createTempDirectory("hardcoded-string-rule")
        val path = dir.resolve("src").resolve("androidUnitTest").resolve("kotlin").resolve("FooScreenTest.kt")
        Files.createDirectories(path.parent)
        path.writeText("""fun s() { Text("throwaway") }""")
        try {
            rule.lint(path).shouldBeEmpty()
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    "the same literal under a shipping source set (src/commonMain/) fires" {
        val dir = Files.createTempDirectory("hardcoded-string-rule")
        val path = dir.resolve("src").resolve("commonMain").resolve("kotlin").resolve("FooScreen.kt")
        Files.createDirectories(path.parent)
        path.writeText("""fun s() { Text("throwaway") }""")
        try {
            rule.lint(path) shouldHaveSize 1
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
})
