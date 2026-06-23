plugins {
    id("nearyou.kotlin.jvm")
    id("nearyou.detekt")
    alias(libs.plugins.kotlinxSerialization)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    // Resend has no official JVM/Kotlin SDK → raw Ktor client (the `:infra:cloudflare-images`
    // precedent). Fenced here (no vendor/HTTP-client symbol outside :infra:*), so no new pin.
    implementation(libs.ktor.clientCore)
    implementation(libs.ktor.clientApache5)
    implementation(libs.kotlinx.serialization.json)
    // Koin module factory (`emailSenderModule`) mirrors `:infra:redis`'s `redisKoinModule`.
    implementation(libs.koin.core)
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
