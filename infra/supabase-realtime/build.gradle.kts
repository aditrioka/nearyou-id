import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("nearyou.kotlin.multiplatform")
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinxSerialization)
}

// mobile-chat-screen: the mobile (KMP) Realtime SUBSCRIBE side of 1:1 chat. This is the ONLY
// module permitted to import the supabase-kt vendor SDK (CLAUDE.md invariant — no vendor SDK
// import outside :infra:*). It exposes the vendor-FREE `ChatRealtimeSubscriber` interface +
// `ChatMessageInbound` model + `RealtimeTokenProvider` seam (commonMain, no vendor symbol) and
// the `SupabaseChatRealtimeSubscriber` implementation that fences the supabase-kt import.
// :mobile:app depends on this module for the interface; the supabase-kt transitive dep is
// `implementation`-scoped, so it never leaks onto the app's compile classpath.
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
            implementation(project.dependencies.platform(libs.supabase.bom))
            implementation(libs.supabase.realtime)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            // Ktor client core (supabase-kt is Ktor-backed). Platform engines (okhttp/darwin)
            // are supplied by the consuming :mobile:app at runtime.
            implementation(libs.ktor.kmp.clientCore)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "id.nearyou.app.infra.supabaserealtime"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
