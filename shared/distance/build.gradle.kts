import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("nearyou.kotlin.multiplatform")
    alias(libs.plugins.androidLibrary)
}

kotlin {
    jvm()

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    // android + iOS targets make `:shared:distance` consumable by `:mobile:app`
    // (mobile-nearby-timeline-screen). `androidMain` / `iosMain` provide the `actual`
    // implementations of the `commonMain` `expect`s (HMAC-SHA256, unixMillis). NO
    // `binaries.framework` block — `:shared:distance` is a transitive dependency of
    // `:mobile:app`, never Xcode-consumed directly (the `:mobile:app` ComposeApp framework
    // is the only iOS-published artifact).
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
        }
        // commonTest carries ONLY `kotlin.test` (multiplatform) so it compiles + runs on every
        // target — including the iOS simulator, where the new CommonCrypto `hmacSha256` actual is
        // exercised by the RFC 4231 known-answer test. Kotest (`kotest-runner-junit5`) is JVM-only
        // and MUST NOT leak into commonTest, or the Native compilation breaks.
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation(libs.kotest.assertionsCore)
            implementation(libs.kotest.runnerJunit5)
        }
        // Android unit tests run the commonTest `kotlin.test` known-answer test via the JUnit4
        // binding (no `useJUnitPlatform()` on the Android task — that is scoped to `jvmTest` for
        // Kotest below). This exercises the `androidMain` javax.crypto actual against the RFC vector.
        androidUnitTest.dependencies {
            implementation(libs.kotlin.testJunit)
        }
    }
}

android {
    namespace = "id.nearyou.distance"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Kotest (the `jvmTest` `DistanceRendererTest` / `HaversineDistanceTest` / `JitterEngineTest`
// StringSpecs) runs on the JUnit5 platform — scoped to `jvmTest` ONLY. The Android unit-test task
// keeps the default JUnit4 runner so the commonTest `kotlin.test` known-answer test executes there
// against the `androidMain` actual; the iOS sim test runs on the Kotlin/Native test runner.
tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
