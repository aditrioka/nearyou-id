plugins {
    id("nearyou.kotlin.jvm")
    id("nearyou.detekt")
    alias(libs.plugins.kotlinxSerialization)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    // RevenueCat has no official JVM SDK → raw Ktor client for the v1 promotional-
    // entitlement grant endpoint (the :infra:cloudflare-images / :infra:openai-moderation
    // precedent). Fenced here (no vendor / HTTP-client symbol outside :infra:*).
    implementation(libs.ktor.clientCore)
    implementation(libs.ktor.clientApache5)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotest.runnerJunit5)
    testImplementation(libs.kotest.assertionsCore)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.ktor.clientMock)
    testImplementation(libs.logback)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    listOf("kotest.tags", "kotest.filter.tests", "kotest.filter.specs").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
