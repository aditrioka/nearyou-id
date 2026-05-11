plugins {
    id("nearyou.kotlin.jvm")
    alias(libs.plugins.kotlinxSerialization)
    `java-test-fixtures`
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.clientCore)
    // Apache5 engine for the production HttpClient — Ktor's own recommendation
    // for JVM HTTP clients (per ktor.io/docs/http-client-engines.html). Issue
    // #88 investigation revealed OkHttp (PR #92's prior choice) is documented
    // as one of the slower Ktor engines (HttpClient init ~2000ms, 3-4x slower
    // than CIO for body transfer, ~3x slower than Apache for batch requests).
    // Empirical staging measurement: raw curl from CRJ to OpenAI Moderation
    // shows p99 1.4s, zero outliers >2s — yet JVM-mediated calls (OkHttp engine
    // path) timed out >80% at 3000ms. Apache5 supports HTTP/1.1 and HTTP/2,
    // uses constant thread count regardless of concurrency, and is the engine
    // recommended for new Ktor projects per the 3.x docs. The Cio and Okhttp
    // deps are retained for backward source compatibility of any caller that
    // explicitly injects them via the `engine: HttpClientEngine? = null` ctor
    // overload; tests use `MockEngine` from `ktor-client-mock`.
    implementation(libs.ktor.clientCio)
    implementation(libs.ktor.clientOkhttp)
    implementation(libs.ktor.clientApache5)
    // JDK 11+ java.net.http.HttpClient via Ktor's Java engine — iter 13 (#88).
    // After CIO/OkHttp/Apache5 ALL showed 4-6s analyze() time vs raw-curl p99
    // of 1.4s, the engine choice itself didn't help. This is the last major
    // engine variant — uses JDK stdlib HTTP/2 directly, no Apache/OkHttp/Netty
    // intermediary. If still slow, the issue is in Ktor's pipeline above the
    // engine layer, not the engine itself.
    implementation(libs.ktor.clientJava)
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
