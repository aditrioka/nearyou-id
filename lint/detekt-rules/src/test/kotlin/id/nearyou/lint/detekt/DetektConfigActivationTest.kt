package id.nearyou.lint.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Pins the PROJECT detekt configs against the ruleset provider.
 *
 * detekt 1.23 treats a rule that is absent from a provided config as INACTIVE
 * (`Config.valueOrDefault(ACTIVE_KEY, false)`), while `Config.empty` — what the
 * `lint(...)` test harness uses — defaults rules to ACTIVE. That asymmetry let
 * 7 of 10 nearyou rules sit dormant in every `./gradlew detekt` run while this
 * module's unit tests stayed green (found by the 2026-06-11 backend review).
 *
 * This test closes the gap structurally: any rule added to [NearYouRuleSetProvider]
 * MUST be listed with `active: true` in BOTH scanning configs
 * (`backend/ktor/config/detekt/detekt.yml`, `config/detekt/invariants.yml`).
 * The mobile config is deliberately TestLoginIsolationRule-only (its header
 * documents why: the other rules target backend SQL / Redis / HTTP patterns),
 * so it is pinned to that single activation, not the full set.
 */
class DetektConfigActivationTest : StringSpec({

    val repoRoot =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "openspec").exists() }

    val providerRuleIds =
        NearYouRuleSetProvider()
            .instance(Config.empty)
            .rules
            .map { it.ruleId }
            .toSet()

    fun activeRuleNames(file: File): Set<String> {
        val lines = file.readLines()
        val active = mutableSetOf<String>()
        lines.forEachIndexed { i, line ->
            val name = Regex("""^ {2}(\w+):\s*$""").find(line)?.groupValues?.get(1) ?: return@forEachIndexed
            val next =
                lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.trim().startsWith("#") }
            if (next?.trim() == "active: true") active += name
        }
        return active
    }

    "provider ships the expected 10 rules" {
        providerRuleIds.size shouldBe 10
    }

    "backend/ktor detekt.yml activates every provider rule" {
        val config = File(repoRoot, "backend/ktor/config/detekt/detekt.yml")
        val active = activeRuleNames(config)
        providerRuleIds.forEach { ruleId ->
            withClue("$ruleId must be listed with `active: true` in ${config.path} — unlisted rules are silently skipped") {
                active shouldContain ruleId
            }
        }
    }

    "root invariants.yml (infra/core modules) activates every provider rule" {
        val config = File(repoRoot, "config/detekt/invariants.yml")
        val active = activeRuleNames(config)
        providerRuleIds.forEach { ruleId ->
            withClue("$ruleId must be listed with `active: true` in ${config.path} — unlisted rules are silently skipped") {
                active shouldContain ruleId
            }
        }
    }

    "mobile detekt.yml keeps its deliberate TestLoginIsolationRule-only activation" {
        val config = File(repoRoot, "mobile/app/config/detekt/detekt.yml")
        activeRuleNames(config) shouldBe setOf("TestLoginIsolationRule")
    }
})
