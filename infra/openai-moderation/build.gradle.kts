plugins {
    id("nearyou.kotlin.jvm")
    alias(libs.plugins.kotlinxSerialization)
    `java-test-fixtures`
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.clientCore)
    // OkHttp engine for the production HttpClient. The previous CIO engine
    // caused 80%+ Layer 3 dispatch timeouts in staging (issue #88) — the CIO
    // event-loop body-read step stretched > 3000ms even for 1KB JSON bodies
    // under typical Cloud Run sizing. OkHttp uses a dedicated Dispatcher
    // thread pool (separate from Ktor's selector event loops) so the body-read
    // is decoupled from Ktor CIO's scheduling. CIO is retained ONLY for
    // backward source compatibility of `engine: HttpClientEngine? = null`
    // ctor overloads / tests; tests inject `MockEngine` directly.
    implementation(libs.ktor.clientCio)
    implementation(libs.ktor.clientOkhttp)
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
