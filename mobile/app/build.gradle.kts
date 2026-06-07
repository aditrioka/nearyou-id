import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    id("nearyou.kotlin.multiplatform")
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
    kotlin("native.cocoapods")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    iosArm64()
    iosSimulatorArm64()

    cocoapods {
        version = "1.0"
        summary = "NearYou Mobile App — ComposeApp KMP framework"
        homepage = "https://nearyou.id"
        ios.deploymentTarget = "13.0"
        // mobile-ios-build-config-matrix: map the custom Xcode build-configuration matrix names to
        // Kotlin/Native build types. The KMP cocoapods plugin only auto-detects the default
        // Debug/Release configs; without this it fails the ComposeApp framework sync with
        // "Could not identify build type for Kotlin framework 'ComposeApp' ... CONFIGURATION=Prod Release".
        xcodeConfigurationToNativeBuildType["Dev Debug"] = NativeBuildType.DEBUG
        xcodeConfigurationToNativeBuildType["Staging Debug"] = NativeBuildType.DEBUG
        xcodeConfigurationToNativeBuildType["Prod Debug"] = NativeBuildType.DEBUG
        xcodeConfigurationToNativeBuildType["Prod Release"] = NativeBuildType.RELEASE
        framework {
            baseName = "ComposeApp"
            isStatic = true
        }
        // GoogleSignIn iOS SDK is consumed via the KMP cocoapods plugin per Decision 1
        // (`design.md` Mobile #3). The iosApp Xcode project must consume this through the
        // generated Pods.xcworkspace + a Podfile at `iosApp/Podfile` referencing
        // `pod 'app', :path => '../mobile/app'` (the pod name is the `:mobile:app` Gradle
        // project — `ComposeApp` is the framework module/baseName, NOT the pod name). Build +
        // run runbook (incl. the Compose-resource bootstrap that prevents a clean-build
        // MissingResourceException): `dev/docs/ios-build.md`; Google Sign-In OAuth wiring:
        // `dev/docs/google-cloud-oauth-clients.md`.
        pod("GoogleSignIn") {
            version = libs.versions.googleSigninIos.get()
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)
            // Mobile #3 — Credential Manager + Google ID helper + DataStore + Tink + Ktor OkHttp engine.
            implementation(libs.androidx.credentials)
            implementation(libs.androidx.credentials.playServicesAuth)
            implementation(libs.googleid)
            implementation(libs.androidx.datastore.preferences)
            implementation(libs.google.tink)
            // mobile-location-permission-flow — Fused Location Provider (coarse device location).
            implementation(libs.google.playServicesLocation)
            implementation(libs.ktor.kmp.clientOkhttp)
            // koin-android gives `androidContext()` so the platform Koin module can supply a
            // Context to `SecureTokenStore` without a bespoke context-holder.
            implementation(libs.koin.android)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.androidx.lifecycle.viewmodelNavigation3)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.composeViewmodel)
            // mobile-nav-swap-to-navigation3 — Navigation 3 (JetBrains CMP port) replaces Voyager.
            // `navigation3-ui` brings navigation3-runtime/-common transitively; the @Serializable NavKey
            // routes rely on the kotlinxSerialization plugin (already applied above). `koin-composeNavigation3`
            // (the Koin 4.2.x Nav3 integration, replacing `voyager-koin`) is FORWARD-WIRED: no symbol from it
            // is referenced yet because entry-scoped `koinViewModel` + the ViewModel-store decorator are
            // deferred (design Decision 5 — every screen is stateless and resolves singletons via
            // `koinInject` from `koin-compose`). It lands in use with the first ViewModel-backed screen.
            implementation(libs.navigation3.ui)
            implementation(libs.koin.composeNavigation3)
            implementation(projects.shared.resources)
            // mobile-nearby-timeline-screen — DistanceRenderer.render + LatLng (the jitter
            // algorithm ships transitively; JITTER_SECRET never does — backend-injected only).
            implementation(projects.shared.distance)
            // Mobile #3 — Ktor KMP client + serialization + datetime for token expiration.
            implementation(libs.ktor.kmp.clientCore)
            implementation(libs.ktor.kmp.clientContentNegotiation)
            implementation(libs.ktor.kmp.serializationKotlinxJson)
            implementation(libs.ktor.kmp.clientAuth)
            implementation(libs.ktor.kmp.clientLogging)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            // Mobile #3 — MockEngine for AuthApiClient tests + runTest for suspend tests.
            implementation(libs.ktor.kmp.clientMock)
            implementation(libs.kotlinx.coroutines.test)
            // Mobile #3 — Compose UI test API (runComposeUiTest). The SignInScreen /
            // RootRouterScreen render+interaction tests live in androidUnitTest (Robolectric
            // JVM runner); the API itself is shared so the fakes can live in commonTest.
            implementation(libs.compose.ui.test)
        }
        androidUnitTest.dependencies {
            // Mobile #3 — Robolectric runs the Compose UI tests on the JVM (no emulator).
            implementation(libs.robolectric)
        }
        iosMain.dependencies {
            // Mobile #3 — Darwin engine for iOS Ktor client.
            implementation(libs.ktor.kmp.clientDarwin)
        }
    }
}

android {
    namespace = "id.nearyou.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "id.nearyou.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        // Mobile #3 — required so per-flavor `API_BASE_URL` is exposed on `BuildConfig`.
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Mobile #3 — Robolectric-backed Compose UI tests need merged Android resources.
            isIncludeAndroidResources = true
        }
    }

    // Mobile #3 — env-aware API base URL per `openspec/project.md` § Environments. Per-flavor
    // `applicationIdSuffix` lets dev / staging / production install side-by-side on the same
    // device; `id.nearyou.app.staging` is also the bundle ID used by the staging Google OAuth
    // client (tasks.md 10.2). Production has no suffix.
    flavorDimensions += "env"
    productFlavors {
        // GOOGLE_SERVER_CLIENT_ID is the backend's Google OAuth **web/server** client ID (the
        // audience of the Google ID token). Per-flavor placeholders until Google Cloud Console
        // provisioning (tasks.md 10.2/10.3); per CLAUDE.md § Public-repo posture these IDs are
        // non-sensitive once real (the SHA-1 signing-cert binding is the security boundary), so
        // they may be committed verbatim once provisioned.
        create("dev") {
            dimension = "env"
            applicationIdSuffix = ".dev"
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8080\"")
            buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"REPLACE_WITH_DEV_SERVER_CLIENT_ID.apps.googleusercontent.com\"")
        }
        create("staging") {
            dimension = "env"
            applicationIdSuffix = ".staging"
            buildConfigField("String", "API_BASE_URL", "\"https://api-staging.nearyou.id\"")
            buildConfigField(
                "String",
                "GOOGLE_SERVER_CLIENT_ID",
                "\"27815942904-egrmb6ou96poualok9gooi63mjo2a0om.apps.googleusercontent.com\"",
            )
        }
        create("production") {
            dimension = "env"
            buildConfigField("String", "API_BASE_URL", "\"https://api.nearyou.id.PLACEHOLDER\"")
            buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"REPLACE_WITH_PRODUCTION_SERVER_CLIENT_ID.apps.googleusercontent.com\"")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// The Robolectric Compose UI tests (SignInScreenTest / RootRouterScreenTest / AgeGateScreenTest /
// NearbyTimelineScreenTest / NearbyLocationGateScreenTest / NearYouThemeTest / PostCreationScreenTest /
// HomeScreenFabTest / GlobalTimelineScreenTest / HomeTabHostScreenTest) need the debug-only
// `androidx.compose.ui:ui-test-manifest` ComponentActivity, which is NOT merged into release variants —
// so `./gradlew test` (all variants) fails `testDevReleaseUnitTest` etc. with a host-activity
// RuntimeException. Skip those classes in release unit-test tasks; they are build-type-agnostic (they
// exercise the composable, not the build type) and run fully in the debug variants. Non-UI unit tests
// (e.g. PostCreationSourceGuardTest, CreatePostFlowKoinResolutionTest, GlobalTimelineKoinResolutionTest,
// FollowingTabNoFetchScanTest) still run in every variant.
tasks.withType<Test>().configureEach {
    if (name.contains("Release")) {
        exclude(
            "**/SignInScreenTest*",
            "**/RootRouterScreenTest*",
            "**/AgeGateScreenTest*",
            "**/NearbyTimelineScreenTest*",
            "**/NearbyLocationGateScreenTest*",
            "**/NearYouThemeTest*",
            "**/PostCreationScreenTest*",
            "**/HomeScreenFabTest*",
            "**/GlobalTimelineScreenTest*",
            "**/HomeTabHostScreenTest*",
        )
    }
}

dependencies {
    debugImplementation(libs.compose.uiTooling)
    // Mobile #3 — merges `androidx.activity.ComponentActivity` into the debug manifest so
    // Robolectric's `runComposeUiTest` (ActivityScenario) can resolve a host activity.
    debugImplementation(libs.androidx.composeUi.testManifest)
}
