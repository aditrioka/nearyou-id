plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jlleitschuh.gradle.ktlint")
}

ktlint {
    android.set(false)
    ignoreFailures.set(false)
    filter {
        exclude { it.file.path.contains("/build/") }
    }
}

tasks.named("check") {
    dependsOn("ktlintCheck")
}
