rootProject.name = "NearYouID"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

// Mobile (Android + Compose Multiplatform) is conditionally excluded when building
// backend-only artefacts from environments without an Android SDK — e.g. the
// Cloud Run Docker builder image. Pass `-PincludeMobile=false` to skip the
// `:mobile:app` project from settings evaluation; the Android plugin is then
// never applied, so the JDK-only builder can run Gradle successfully. All other
// builds (local dev, CI `assemble`, CI `test`) include mobile by default so the
// KMP compile surface stays covered.
val includeMobile: String = (providers.gradleProperty("includeMobile").orNull ?: "true")
if (includeMobile.toBoolean()) {
    include(":mobile:app")
    include(":shared:resources")
    // mobile-sentry-crash-reporting — :infra:sentry is a mobile-only KMP module (Android + iOS,
    // consumed ONLY by :mobile:app; NOT a :backend:ktor dependency). Mobile-gated so the JDK-only
    // backend Docker builder (`-PincludeMobile=false`) does not try to configure a KMP/Android module
    // it has no SDK for — and so the settings↔Dockerfile drift check does not demand a Dockerfile COPY
    // (a KMP module can't be COPY'd into the JDK-only builder; cf. :infra:supabase-realtime). Mirrors
    // the :shared:resources placement.
    include(":infra:sentry")
}
include(":backend:ktor")
include(":shared:tmp")
include(":shared:distance")
include(":core:domain")
include(":core:data")
include(":infra:fcm")
include(":infra:oidc")
include(":infra:openai-moderation")
include(":infra:otel")
include(":infra:redis")
include(":infra:remote-config")
include(":infra:supabase")
include(":infra:supabase-realtime")
include(":lint:detekt-rules")