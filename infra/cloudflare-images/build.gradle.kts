plugins {
    id("nearyou.kotlin.jvm")
    id("nearyou.detekt")
    alias(libs.plugins.kotlinxSerialization)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    // Cloudflare Images has no official JVM SDK → raw Ktor client (the openai-moderation
    // Apache5-engine precedent). Fenced here (no vendor/HTTP-client symbol outside :infra:*).
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
