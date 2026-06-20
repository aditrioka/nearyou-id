plugins {
    id("nearyou.kotlin.jvm")
    id("nearyou.detekt")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    // Cloudflare R2 has no R2-specific JVM SDK; it speaks the S3 API, and Cloudflare
    // officially documents the coroutine-native AWS SDK for Kotlin path (design D1).
    // Fenced here (no `aws.sdk.*` / `aws.smithy.*` symbol outside :infra:*).
    implementation(libs.aws.s3)
    implementation(libs.koin.core)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotest.runnerJunit5)
    testImplementation(libs.kotest.assertionsCore)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.logback)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    listOf("kotest.tags", "kotest.filter.tests", "kotest.filter.specs").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
