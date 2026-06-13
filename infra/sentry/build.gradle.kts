import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("nearyou.kotlin.multiplatform")
    alias(libs.plugins.androidLibrary)
}

// mobile-sentry-crash-reporting: the KMP crash + error reporting module. This is the ONLY module
// permitted to import the Sentry KMP vendor SDK (CLAUDE.md invariant #16 — no vendor SDK import
// outside :infra:*). commonMain exposes the vendor-FREE `CrashReporter` interface + models +
// `NoOpCrashReporter` (no vendor symbol); the Sentry SDK + the platform `SentryCrashReporter`
// actuals land alongside, `implementation`-scoped so the vendor transitive dep never reaches
// :mobile:app's compile classpath (mirrors the :infra:supabase-realtime seam, docs/11 §2.6).
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
            // The ONLY permitted Sentry vendor import (invariant #16). `implementation`-scoped so the
            // vendor transitive dep never reaches :mobile:app's compile classpath (docs/11 §2.6).
            implementation(libs.sentry.kotlinMultiplatform)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

android {
    namespace = "id.nearyou.app.infra.sentry"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
