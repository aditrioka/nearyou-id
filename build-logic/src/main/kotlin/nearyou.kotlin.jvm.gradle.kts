plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

kotlin {
    jvmToolchain(21)
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
