plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.ktor) apply false
}

/**
 * Verifies the `observability-otel-foundation` capability spec scenario
 * "Business modules carry no `:infra:otel` Gradle dependency". Fails the
 * build if either `:core:domain` or `:core:data` build.gradle.kts contains
 * `project(":infra:otel")` or any `libs.opentelemetry.*` reference. Runs
 * as part of `check` so CI catches regressions where someone adds a dep
 * edge without writing an `import io.opentelemetry.*` (which the source-
 * scan scenario would catch separately).
 *
 * Pure textual check: the build.gradle.kts files are Kotlin DSL but we
 * scan them as plain text rather than parsing the AST — the violation
 * surface is small and unambiguous.
 */
tasks.register("verifyBusinessModulesNoOtel") {
    group = "verification"
    description = "Asserts :core:domain and :core:data carry no :infra:otel dependency"
    // Capture file references via providers so the task body is
    // configuration-cache compatible (no script-scope closures).
    val coreDomain = layout.projectDirectory.file("core/domain/build.gradle.kts")
    val coreData = layout.projectDirectory.file("core/data/build.gradle.kts")
    inputs.files(coreDomain, coreData)
    val rootDirPath = layout.projectDirectory.asFile.absolutePath
    doLast {
        val forbiddenPatterns =
            listOf(
                "project(\":infra:otel\")",
                "projects.infra.otel",
                "libs.opentelemetry",
                "io.opentelemetry",
            )
        val violations = mutableListOf<String>()
        for (buildFile in listOf(coreDomain.asFile, coreData.asFile)) {
            if (!buildFile.exists()) continue
            val text = buildFile.readText()
            val relPath = buildFile.absolutePath.removePrefix("$rootDirPath/")
            for (pattern in forbiddenPatterns) {
                if (text.contains(pattern)) {
                    violations.add("$relPath contains forbidden token '$pattern'")
                }
            }
        }
        if (violations.isNotEmpty()) {
            error(
                "Business modules MUST NOT depend on :infra:otel " +
                    "(observability-otel-foundation capability spec):\n  " +
                    violations.joinToString("\n  "),
            )
        }
    }
}

// Wire the root `check` lifecycle to depend on the verification so the
// existing `./gradlew check` flow that CI runs picks it up. (Avoids
// `allprojects { afterEvaluate { ... } }` cross-project task lookup which
// breaks Gradle's configuration cache.)
tasks.matching { it.name == "check" }.configureEach {
    dependsOn("verifyBusinessModulesNoOtel")
}
