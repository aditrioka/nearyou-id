import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    id("nearyou.kotlin.multiplatform")
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
    // mobile-staging-test-login — run the project's custom Detekt ruleset over this module so
    // TestLoginIsolationRule guards the test-login symbols that live here (Detekt previously ran
    // only on :backend:ktor; the multiplatform convention applies ktlint only).
    alias(libs.plugins.detekt)
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
        // Deliberate supported floor, aligned across pbxproj IPHONEOS_DEPLOYMENT_TARGET,
        // this value, and the Podfile platform (2026-06-10 audit, 07-#10): the app target
        // was an accidental 18.2 (Xcode template default — a real market cut for an
        // Indonesia MVP) while this framework claimed 13.0 yet uses iOS-14+-only API
        // (CLLocationManager.authorizationStatus instance property, reduced accuracy) that
        // K/N cannot @available-check. 16.0 covers the iPhone 8/X-era second-hand market
        // and exceeds every dependency minimum (CMP 13+, GoogleSignIn 13+).
        ios.deploymentTarget = "16.0"
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
        // mobile-fcm-token-registration — Firebase iOS Messaging Pod (token acquisition + refresh on iOS).
        // The iosApp Xcode project supplies GoogleService-Info.plist + the APNs entitlement (OPERATOR setup,
        // tied to the separate staging Firebase project per docs/04 § 259); IosFcmTokenProvider returns null
        // when no FirebaseApp is configured, so the app runs without it. The KMP cocoapods plugin links the
        // Pod into the ComposeApp framework (the GoogleSignIn Pod precedent above).
        pod("FirebaseMessaging") {
            version = libs.versions.firebaseMessagingIos.get()
        }
        // mobile-sentry-crash-reporting — the sentry-cocoa framework REQUIRED by the Sentry KMP SDK
        // (the SDK is fenced in :infra:sentry, but its iOS klib links against sentry-cocoa, which the
        // final ComposeApp framework must provide). linkOnly = true: the :infra:sentry klib owns the
        // Kotlin bindings, so the Pod supplies ONLY the framework binary at link time (no duplicate
        // cinterop). -fmodules is required by sentry-cocoa's modular headers. Version pinned to the SDK's
        // Cocoa compatibility table (libs.versions.toml § sentryCocoaIos = 0.26.0 → 8.58.2).
        pod("Sentry") {
            version = libs.versions.sentryCocoaIos.get()
            linkOnly = true
            extraOpts += listOf("-compiler-option", "-fmodules")
        }
        // mobile-paywall-screen — the native RevenueCat iOS framework REQUIRED by the purchases-kmp SDK
        // (fenced in :infra:revenuecat; its iOS klib's SPM cinterop links against RevenueCat/purchases-ios).
        // linkOnly = true: the :infra:revenuecat klib owns the Kotlin bindings, so the Pod supplies ONLY
        // the framework binary at link time (no duplicate cinterop). -fmodules for the modular headers.
        // Version pinned to the purchases-kmp 3.0.6 upstream (libs.versions.toml § revenuecat-ios = 5.77.0).
        pod("RevenueCat") {
            version = libs.versions.revenuecat.ios.get()
            linkOnly = true
            extraOpts += listOf("-compiler-option", "-fmodules")
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
            // mobile-fcm-token-registration — Firebase Cloud Messaging client (token acquisition + refresh).
            // The BoM co-versions firebase-messaging. The google-services Gradle plugin + google-services.json
            // are OPERATOR setup (NOT applied here — the plugin hard-fails CI without the config; design D8);
            // AndroidFcmTokenProvider returns null when no FirebaseApp is configured, so the app runs without it.
            implementation(project.dependencies.platform(libs.firebase.bom))
            implementation(libs.firebase.messaging)
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
            // image-attached-posts (D5) — Coil 3 AsyncImage (read-path post image) + the Ktor-backed
            // network fetcher (reuses the existing Ktor stack — no new networking lib). The fetcher is
            // registered once at app init via SingletonImageLoader.setSafe (App.kt); images are public
            // (no Bearer), so this does NOT reuse the auth HttpClient (D6).
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
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
            // mobile-chat-screen — the vendor-FREE ChatRealtimeSubscriber / ChatMessageInbound /
            // RealtimeTokenProvider seam. The supabase-kt SDK is an `implementation`-scoped transitive
            // dep of this module, so it NEVER reaches the app's compile classpath (invariant #16).
            implementation(projects.infra.supabaseRealtime)
            // mobile-sentry-crash-reporting — the vendor-FREE CrashReporter interface + models. The
            // Sentry KMP SDK is an `implementation`-scoped transitive dep of :infra:sentry, so it NEVER
            // reaches the app's compile classpath (invariant #16).
            implementation(projects.infra.sentry)
            // mobile-paywall-screen — the vendor-FREE PurchaseController seam + domain models. The
            // RevenueCat purchases-kmp SDK is an `implementation`-scoped transitive dep of
            // :infra:revenuecat, so it NEVER reaches the app's compile classpath (invariant #16).
            implementation(projects.infra.revenuecat)
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
            // Default 10.0.2.2 = the EMULATOR-only host-loopback alias. A physical device
            // reaches the local backend via `adb reverse tcp:8080 tcp:8080` + this override:
            //   ./gradlew :mobile:app:installDevDebug -PdevApiBaseUrl=http://localhost:8080
            // (cleartext for the local hosts is allowlisted in the dev overlay's
            // network_security_config.xml; staging/production are unaffected).
            val devApiBaseUrl = (project.findProperty("devApiBaseUrl") as String?) ?: "http://10.0.2.2:8080"
            buildConfigField("String", "API_BASE_URL", "\"$devApiBaseUrl\"")
            buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"REPLACE_WITH_DEV_SERVER_CLIENT_ID.apps.googleusercontent.com\"")
            // mobile-chat-screen — local Supabase (CLI default). Anon key is the CLI's well-known
            // demo key; overridable via -PdevSupabaseUrl / -PdevSupabaseAnonKey.
            val devSupabaseUrl = (project.findProperty("devSupabaseUrl") as String?) ?: "http://10.0.2.2:54321"
            buildConfigField("String", "SUPABASE_URL", "\"$devSupabaseUrl\"")
            buildConfigField(
                "String",
                "SUPABASE_ANON_KEY",
                "\"${(project.findProperty("devSupabaseAnonKey") as String?) ?: "REPLACE_WITH_DEV_SUPABASE_ANON_KEY"}\"",
            )
            // mobile-sentry-crash-reporting — per-flavor Sentry DSN (a write-only client ingest key,
            // not a secret) + environment tag. Empty until the operator provisions it (task 1.3); an
            // empty DSN makes init no-op. Overridable via -PdevSentryDsn=.
            buildConfigField("String", "SENTRY_DSN", "\"${(project.findProperty("devSentryDsn") as String?) ?: ""}\"")
            // mobile-paywall-screen — RevenueCat publishable client key (ships in the binary; not a
            // secret). Blank in dev (no RevenueCat locally) → configure skipped → paywall Unconfigured.
            buildConfigField("String", "REVENUECAT_PUBLIC_KEY", "\"${(project.findProperty("devRevenueCatPublicKey") as String?) ?: ""}\"")
            buildConfigField("String", "SENTRY_ENVIRONMENT", "\"dev\"")
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
            // mobile-chat-screen — staging Supabase project URL + anon (publishable-class) key for the
            // Realtime subscribe. Provisioned 2026-06-13 (#273) from the nearyou-staging dashboard; the
            // anon JWT is role=anon, RLS-gated, "safe to share publicly" per Supabase — committed
            // verbatim like GOOGLE_SERVER_CLIENT_ID. The service_role key is NEVER here (backend-only,
            // GCP Secret Manager). Overridable via -PstagingSupabaseUrl=… / -PstagingSupabaseAnonKey=….
            val stagingSupabaseUrl =
                (project.findProperty("stagingSupabaseUrl") as String?)
                    ?: "https://hvlbfbuuorhackrlbouo.supabase.co"
            // Split across literals only to stay under the 140-col lint cap — it is one anon JWT.
            val stagingSupabaseAnonKey =
                (project.findProperty("stagingSupabaseAnonKey") as String?)
                    ?: (
                        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
                            "eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imh2bGJmYnV1b3JoYWNrcmxib3VvIiwi" +
                            "cm9sZSI6ImFub24iLCJpYXQiOjE3NzY4NDU5MDMsImV4cCI6MjA5MjQyMTkwM30." +
                            "mpR8wHtXLP38lw4WvCBzZNdaZO8W2X8ZEBpn-VIZBAQ"
                    )
            buildConfigField("String", "SUPABASE_URL", "\"$stagingSupabaseUrl\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"$stagingSupabaseAnonKey\"")
            // mobile-sentry-crash-reporting — staging Sentry DSN (operator-supplied; empty → init no-ops).
            // Overridable via -PstagingSentryDsn=.
            buildConfigField("String", "SENTRY_DSN", "\"${(project.findProperty("stagingSentryDsn") as String?) ?: ""}\"")
            // mobile-paywall-screen — RevenueCat Test Store publishable key (staging-revenuecat-test-api-key
            // slot, PR #319). Blank by default; CI injects via -PstagingRevenueCatPublicKey for the live path.
            buildConfigField(
                "String",
                "REVENUECAT_PUBLIC_KEY",
                "\"${(project.findProperty("stagingRevenueCatPublicKey") as String?) ?: ""}\"",
            )
            buildConfigField("String", "SENTRY_ENVIRONMENT", "\"staging\"")
        }
        create("production") {
            dimension = "env"
            buildConfigField("String", "API_BASE_URL", "\"https://api.nearyou.id.PLACEHOLDER\"")
            buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"REPLACE_WITH_PRODUCTION_SERVER_CLIENT_ID.apps.googleusercontent.com\"")
            buildConfigField("String", "SUPABASE_URL", "\"https://REPLACE_WITH_PROD_SUPABASE_REF.supabase.co\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"REPLACE_WITH_PRODUCTION_SUPABASE_ANON_KEY\"")
            // mobile-sentry-crash-reporting — production Sentry DSN (operator-supplied; empty → init no-ops).
            // Overridable via -PprodSentryDsn=.
            buildConfigField("String", "SENTRY_DSN", "\"${(project.findProperty("prodSentryDsn") as String?) ?: ""}\"")
            // mobile-paywall-screen — production RevenueCat publishable key. Blank until the prod twin is
            // provisioned (needs Apple/Google dev accounts); CI injects via -PprodRevenueCatPublicKey.
            buildConfigField("String", "REVENUECAT_PUBLIC_KEY", "\"${(project.findProperty("prodRevenueCatPublicKey") as String?) ?: ""}\"")
            buildConfigField("String", "SENTRY_ENVIRONMENT", "\"production\"")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            // docs/11 §2.4: R8 + the optimize default file. Enabled pre-launch deliberately
            // (2026-06-10 audit, 07-#9) — Tink + kotlinx.serialization + Compose need
            // keep-rule soak time, and flipping this late risks R8 breakage at the worst
            // moment. Library consumer rules carry most of the weight; app-specific keeps
            // live in proguard-rules.pro. Release builds need a manual device smoke per the
            // docs/11 §5 DoD before shipping.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// mobile-staging-test-login — mirror backend/ktor's Detekt setup: all builtin rulesets disabled,
// only the `nearyou` custom ruleset (contributed via `detektPlugins(projects.lint.detektRules)`).
// The plain `detekt` task is syntactic (no type resolution) — sufficient for the declaration-name
// scan in TestLoginIsolationRule. `./gradlew detekt` (unqualified) now covers this module too.
detekt {
    buildUponDefaultConfig = false
    allRules = false
    disableDefaultRuleSets = true
    config.setFrom(files("config/detekt/detekt.yml"))
    // Scan the WHOLE module source tree rather than a hand-enumerated source-set list — an explicit
    // list silently drifts as flavors/build-types are added and could omit a *shipping* set (e.g. the
    // flavor-wide `src/staging/` that merges into the internet-facing stagingRelease). TestLoginIsolationRule's
    // own path allowlist passes only `src/dev/` and `src/stagingDebug/`, so a `*TestLogin*` declaration
    // anywhere else is caught. The plain `detekt` task is syntactic (no type resolution), so scanning the
    // full tree (incl. test source sets, which currently declare no such symbol) is cheap.
    source.setFrom(files("src"))
}

tasks.named("check") {
    dependsOn("detekt")
}

// The Robolectric Compose UI tests (SignInScreenTest / RootRouterScreenTest / AgeGateScreenTest /
// NearbyTimelineScreenTest / NearbyLocationGateScreenTest / NearYouThemeTest / PostCreationScreenTest /
// HomeScreenFabTest / GlobalTimelineScreenTest / FollowingTimelineScreenTest / HomeTabHostScreenTest /
// NotificationsScreenTest / NotificationsScreenNavTest / AppShellScreenTest / PostDetailScreenTest / PostCardTest / ProfileScreenTest /
// ReportDialogTest / ConversationListScreenTest / ChatThreadScreenTest / SearchScreenTest) need the debug-only
// `androidx.compose.ui:ui-test-manifest` ComponentActivity, which is NOT merged into release variants —
// so `./gradlew test` (all variants) fails `testDevReleaseUnitTest` etc. with a host-activity
// RuntimeException. Skip those classes in release unit-test tasks; they are build-type-agnostic (they
// exercise the composable, not the build type) and run fully in the debug variants. Non-UI unit tests
// (e.g. PostCreationSourceGuardTest, CreatePostFlowKoinResolutionTest, GlobalTimelineKoinResolutionTest,
// FollowingTimelineKoinResolutionTest, NotificationsScreenNavFreeScanTest) still run in every variant.
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
            "**/ConsentScreenTest*",
            "**/GlobalTimelineScreenTest*",
            "**/FollowingTimelineScreenTest*",
            "**/HomeTabHostScreenTest*",
            "**/NotificationsScreenTest*",
            "**/NotificationsScreenNavTest*",
            "**/AppShellScreenTest*",
            "**/PostDetailScreenTest*",
            "**/EditPostScreenTest*",
            "**/PostCardTest*",
            "**/DailyCapUpsellDialogTest*",
            "**/ReportDialogTest*",
            "**/SettingsScreenTest*",
            "**/BlockedUsersScreenTest*",
            "**/ConsentSettingsScreenTest*",
            "**/ProfileScreenTest*",
            "**/FollowListScreenTest*",
            "**/ConversationListScreenTest*",
            "**/ChatThreadScreenTest*",
            "**/SearchScreenTest*",
            "**/PaywallScreenTest*",
            "**/UsernameCustomizationScreenTest*",
        )
    }
}

dependencies {
    debugImplementation(libs.compose.uiTooling)
    // Mobile #3 — merges `androidx.activity.ComponentActivity` into the debug manifest so
    // Robolectric's `runComposeUiTest` (ActivityScenario) can resolve a host activity.
    debugImplementation(libs.androidx.composeUi.testManifest)
    // mobile-staging-test-login — contribute the `nearyou` custom Detekt ruleset (TestLoginIsolationRule).
    detektPlugins(projects.lint.detektRules)
}
