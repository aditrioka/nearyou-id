plugins {
    id("nearyou.kotlin.jvm")
    alias(libs.plugins.kotlinxSerialization)
    `java-test-fixtures`
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.clientCore)
    implementation(libs.ktor.clientCio)
    implementation(libs.ktor.clientContentNegotiation)
    implementation(libs.ktor.serializationKotlinxJson)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotest.runnerJunit5)
    testImplementation(libs.kotest.assertionsCore)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.ktor.clientMock)
    testImplementation(libs.logback)

    // RecordingPerspectiveClient test fixture — published via `java-test-fixtures`
    // so `:backend:ktor` test classpath consumes it via
    // `testImplementation(testFixtures(projects.infra.perspective))`. Mirrors the
    // `:infra:otel` `SpanRecorder` precedent.
    testFixturesApi(libs.kotlinx.coroutines.core)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    listOf("kotest.tags", "kotest.filter.tests", "kotest.filter.specs").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
