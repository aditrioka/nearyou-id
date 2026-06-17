plugins {
    id("nearyou.kotlin.jvm")
    id("nearyou.detekt")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    // Google Cloud Vision SDK — fenced inside this module (invariant: no vendor SDK
    // import outside :infra:*). Brings gRPC / protobuf / google-auth transitively.
    implementation(libs.google.cloud.vision)
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
