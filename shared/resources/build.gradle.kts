import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("nearyou.kotlin.multiplatform")
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "SharedResources"
            // isStatic = true preserved per shared-resources-swap-to-cmp-resources
            // design.md Decision 6 (amended post-Round-1-review) — it's the JetBrains
            // KMP-wizard 2026 default for iOS frameworks, NOT a Moko-coupled requirement.
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Exposed as `api` so :mobile:app (consumer) can resolve generated
            // Res accessor types (StringResource / DrawableResource / FontResource)
            // + the `painterResource` / `stringResource` / `Font` Compose accessors.
            api(libs.compose.components.resources)
            implementation(libs.compose.runtime)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            // Compose UI test — runComposeUiTest for composition-level testing.
            // No JUnit4 dep needed; Compose Multiplatform 1.10+ ships its own
            // test runner via this single artifact.
            implementation(libs.compose.ui.test)
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "id.nearyou.resources.generated.resources"
    generateResClass = auto
}

android {
    namespace = "id.nearyou.resources"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // `androidx.compose.ui.test.runComposeUiTest` reads
    // `Build.FINGERPRINT.toLowerCase(...)` to detect the runtime — Android
    // JVM unit tests stub `Build` static fields as `null`, so any Compose UI
    // test crashes with NullPointerException on `Build.FINGERPRINT`.
    // `isReturnDefaultValues` doesn't help (it stubs method returns, not
    // static fields). The proper fix is Robolectric or moving these tests to
    // `androidTest/` (instrumented) — tracked as the
    // `mobile-compose-ui-tests-android-instrumented` follow-up. Canonical
    // cross-platform verification ALREADY runs via
    // `:shared:resources:iosSimulatorArm64Test` (same test class, real
    // Compose runtime); excluding here on the Android JVM lane does not
    // reduce real coverage.
    testOptions {
        unitTests.all {
            it.exclude("**/ColorSchemeExtensionsTest*")
        }
    }
}
