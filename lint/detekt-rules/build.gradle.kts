import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktlint)
}

// Detekt 1.23.x runs on a Java 17 runtime, so our custom rules must target 17 (class file
// version 61). The rest of the project uses Kotlin toolchain 21 (set by the `nearyou.kotlin.jvm`
// convention plugin); this module deliberately does NOT apply that convention — it sits alongside
// it and mirrors only the necessary bits.
kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
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

dependencies {
    compileOnly(libs.detekt.api)

    testImplementation(libs.kotest.runnerJunit5)
    testImplementation(libs.kotest.assertionsCore)
    testImplementation(libs.detekt.test)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
