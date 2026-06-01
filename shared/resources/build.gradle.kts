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
        androidUnitTest.dependencies {
            // Robolectric supplies a real `Build` so `runComposeUiTest` runs on
            // the Android JVM lane (ColorSchemeExtensionsAndroidTest). commonTest
            // cannot declare the `@RunWith(RobolectricTestRunner)` it needs (it is
            // also an iOS/native target), so Android coverage is this separate class.
            implementation(libs.robolectric)
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
    // `Build.FINGERPRINT.toLowerCase(...)`, which is `null` on the plain Android
    // JVM unit-test lane → NPE. `@RunWith(RobolectricTestRunner)` fixes it but
    // CANNOT be applied to the `commonTest` `ColorSchemeExtensionsTest` (that
    // source set is also compiled for iOS/native). So the commonTest class is
    // excluded from the JVM lane (it still runs on `iosSimulatorArm64Test`), and
    // Android coverage comes from `ColorSchemeExtensionsAndroidTest` in
    // `androidUnitTest`, which DOES declare Robolectric and is NOT matched by the
    // `ColorSchemeExtensionsTest*` glob below. This exclude is therefore correct
    // config, not a coverage gap — both platforms run the shared assertions.
    testOptions {
        unitTests {
            // Robolectric's `runComposeUiTest` needs the merged Android resources
            // + manifest (which carries the ui-test-manifest ComponentActivity host).
            isIncludeAndroidResources = true
            all {
                it.exclude("**/ColorSchemeExtensionsTest*")
            }
        }
    }
}

dependencies {
    // ui-test-manifest merges the ComponentActivity host into the DEBUG manifest
    // that Robolectric's `runComposeUiTest` (ActivityScenario) needs. Debug-only,
    // so ColorSchemeExtensionsAndroidTest is excluded from the Release lane below.
    debugImplementation(libs.androidx.composeUi.testManifest)
}

// ColorSchemeExtensionsAndroidTest (Robolectric `runComposeUiTest`) needs the
// debug-only ui-test-manifest ComponentActivity, NOT merged into release variants
// — so `testReleaseUnitTest` would throw a host-activity RuntimeException. Skip it
// in release unit-test tasks; it is build-type-agnostic (exercises the composable,
// not the build type) and runs fully in the debug variant.
tasks.withType<Test>().configureEach {
    if (name.contains("Release")) {
        exclude("**/ColorSchemeExtensionsAndroidTest*")
    }
}
