import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("nearyou.kotlin.multiplatform")
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinxSerialization)
}

// mobile-amplitude-analytics: the mobile (KMP: Android + iOS) product-analytics transport seam.
// commonMain exposes the vendor-free `AnalyticsTracker` interface + `NoOpAnalyticsTracker` + the
// `AmplitudeAnalyticsTracker` HTTP V2 wrapper. There is NO vendor SDK (docs/04 § Amplitude prescribes
// an HTTP API wrapper), so — unlike :infra:sentry / :infra:revenuecat — invariant #16 is trivially
// satisfied (nothing to fence) and the impl can live in commonMain. The HTTP transport uses the
// already-pinned Ktor KMP client + kotlinx.serialization (zero new pins). The platform engine is
// INJECTED by the caller (:mobile:app's `httpClientEngine()`; tests pass `MockEngine`), so this module
// needs no per-platform engine dependency and stays pure commonMain. Mobile-gated in settings.gradle.kts.
kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.kmp.clientCore)
            implementation(libs.ktor.kmp.clientContentNegotiation)
            implementation(libs.ktor.kmp.serializationKotlinxJson)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.kmp.clientMock)
        }
    }
}

android {
    namespace = "id.nearyou.app.infra.amplitude"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
