import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("nearyou.kotlin.multiplatform")
    alias(libs.plugins.androidLibrary)
}

// mobile-paywall-screen: the mobile (KMP: Android + iOS) RevenueCat purchase + entitlement seam. This
// is the ONLY module permitted to import the RevenueCat `purchases-kmp` vendor SDK (CLAUDE.md invariant
// #16 — no vendor SDK import outside :infra:*). It exposes the vendor-FREE `PurchaseController`
// interface + plain-Kotlin domain models (`PaywallPackage` / `OfferingsResult` / `PurchaseResult`) in
// commonMain; the `RevenueCatPurchaseController` implementation that fences the `purchases-kmp` import
// lands alongside the SDK dependency (added with the binding). :mobile:app depends on this module for
// the interface only; the `purchases-kmp` dep is `implementation`-scoped, so it never leaks onto the
// app's compile classpath. Mirrors the §2.6 realtime-consumer seam (`:infra:supabase-realtime`).
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
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "id.nearyou.app.infra.revenuecat"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
