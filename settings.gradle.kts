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

// Mobile-only modules (Android + Compose Multiplatform / KMP) are conditionally
// excluded when building backend-only artefacts from environments without an
// Android SDK — e.g. the Cloud Run Docker builder image. Pass
// `-PincludeMobile=false` to skip these projects from settings evaluation; the
// Android plugin they apply is then never resolved, so the JDK-only builder can
// run Gradle successfully. All other builds (local dev, CI `assemble`, CI `test`)
// include mobile by default so the KMP compile surface stays covered.
//
// INVARIANT: every module gated here is also intentionally ABSENT from the
// Dockerfile's hand-maintained COPY list, and vice-versa. A non-mobile-gated
// module whose project directory is not COPY'd into the builder makes the Docker
// build fail at settings evaluation ("Configuring project ... without an existing
// directory"), breaking every staging/prod deploy while PR CI stays green
// (PR #247 did exactly this). Guarded by dev/scripts/check-dockerfile-module-copies.sh.
val includeMobile: String = (providers.gradleProperty("includeMobile").orNull ?: "true")
if (includeMobile.toBoolean()) {
    include(":mobile:app")
    include(":shared:resources")
    // mobile-sentry-crash-reporting — :infra:sentry is a mobile-only KMP module (Android + iOS),
    // consumed solely by :mobile:app (NOT a :backend:ktor dependency). Mobile-gated like
    // :infra:supabase-realtime below so the JDK-only backend Docker builder excludes it — a KMP/Android
    // module can't be configured or COPY'd into the builder without the Android SDK.
    include(":infra:sentry")
    // KMP module (androidTarget + iOS only — no JVM target, applies the Android
    // library plugin); consumed solely by :mobile:app (the client-side chat
    // realtime *subscriber*). The backend publishes via :infra:supabase, so this
    // is mobile-only and MUST stay gated — it cannot be configured in the
    // JDK-only Docker builder.
    include(":infra:supabase-realtime")
    // mobile-paywall-screen — :infra:revenuecat is a mobile-only KMP module (androidTarget + iOS,
    // no JVM target; applies the Android library plugin), consumed solely by :mobile:app (the paywall
    // PurchaseController seam). The backend subscription contract is owned by :backend:ktor's webhook
    // (#291), NOT this module. Mobile-gated like :infra:sentry / :infra:supabase-realtime so the
    // JDK-only backend Docker builder excludes it (it can't be configured/COPY'd without the Android SDK).
    include(":infra:revenuecat")
    // mobile-amplitude-analytics — :infra:amplitude is a mobile-only KMP module (androidTarget + iOS,
    // no JVM target; applies the Android library plugin), consumed solely by :mobile:app (the product-
    // analytics AnalyticsTracker seam). It is a vendor-SDK-FREE Amplitude HTTP V2 wrapper (no vendor dep
    // to fence), so it carries no special invariant-#16 weight; it stays mobile-gated like the siblings
    // above so the JDK-only backend Docker builder excludes it (it can't be configured without the
    // Android SDK).
    include(":infra:amplitude")
    // mobile-admob-ads-foundation — :infra:admob is a mobile-only KMP module (androidTarget + iOS, no JVM
    // target; applies the Android library + Compose + native.cocoapods plugins), consumed solely by
    // :mobile:app (the AdProvider ad-serving seam fencing the Google Mobile Ads + UMP SDKs, invariant #16).
    // No backend counterpart — the ads_enabled gate is owned by :backend:ktor's ads-config route, NOT this
    // module. Mobile-gated like the siblings above so the JDK-only backend Docker builder excludes it (it
    // can't be configured/COPY'd without the Android SDK; the settings↔Dockerfile guard stays green — D8).
    include(":infra:admob")
}
include(":backend:ktor")
include(":shared:tmp")
include(":shared:distance")
include(":core:domain")
include(":core:data")
include(":infra:cloud-vision")
include(":infra:cloudflare-images")
include(":infra:fcm")
include(":infra:oidc")
include(":infra:openai-moderation")
include(":infra:otel")
include(":infra:r2")
include(":infra:redis")
include(":infra:remote-config")
include(":infra:resend")
include(":infra:revenuecat-api")
include(":infra:supabase")
include(":lint:detekt-rules")