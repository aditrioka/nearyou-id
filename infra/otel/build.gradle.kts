plugins {
    id("nearyou.kotlin.jvm")
    `java-test-fixtures`
}

dependencies {
    // Both BOMs are imported as platforms so the SDK + per-library instrumentation
    // versions are managed centrally. The two BOMs ship from different upstream
    // release cadences but we keep them in lockstep at version-bump time
    // (see docs/09-Versions.md). `api(platform(...))` propagates the BOM
    // constraint to consumers so transitive `opentelemetry-api` resolves to
    // the BOM-managed version when `:backend:ktor` consumes our `api()` deps.
    api(platform(libs.opentelemetry.bom))
    api(platform(libs.opentelemetry.instrumentationBomAlpha))

    // Public surface for callers in `:backend:ktor` (e.g., `Span.current()` use
    // at the auth principal extraction site).
    api(libs.opentelemetry.api)
    api(libs.opentelemetry.semconv)

    // SDK + autoconfigure are infrastructure-private — callers go through
    // `OtelBootstrap` and never see the SDK builder surface.
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.sdkExtensionAutoconfigure)
    implementation(libs.opentelemetry.exporterOtlp)
    implementation(libs.opentelemetry.exporterLogging)
    // `Context.asContextElement()` extension for kotlinx.coroutines so the
    // active OTel span context propagates across `suspend` boundaries
    // (Ktor client outbound HTTP, etc.). Without this, `traceparent` is not
    // injected on outbound requests made from within a suspend function.
    api(libs.opentelemetry.extensionKotlin)

    // Per-library instrumentation. Versions resolved from the instrumentation BOM.
    implementation(libs.opentelemetry.instrumentationKtor3)
    implementation(libs.opentelemetry.instrumentationJdbc)
    implementation(libs.opentelemetry.instrumentationLettuce)

    // Auto-instrumented surfaces — declared as `compileOnly` so consumers must
    // bring their own classpath dependency. `:infra:otel` is not the lifecycle
    // owner of the JDBC driver, the Lettuce client, or the Ktor server.
    compileOnly(libs.ktor.serverCore)
    compileOnly(libs.ktor.clientCore)
    compileOnly(libs.ktor.clientCio)
    compileOnly(libs.lettuce.core)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotest.runnerJunit5)
    testImplementation(libs.kotest.assertionsCore)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.logback)
    // H2 in-memory database for the JDBC sanitization regression test —
    // exercises `OtelInstrumentation.wrapDataSource(...)`'s
    // `setStatementSanitizationEnabled(true)` flag end-to-end without
    // needing a Postgres container.
    testImplementation(libs.h2)
    testImplementation(libs.hikaricp)

    // SpanRecorder test fixture — published via `java-test-fixtures` so
    // `:backend:ktor` and `:infra:fcm` test classpaths can consume it via
    // `testImplementation(testFixtures(project(":infra:otel")))` per task §4.9.
    testFixturesApi(platform(libs.opentelemetry.bom))
    testFixturesApi(platform(libs.opentelemetry.instrumentationBomAlpha))
    testFixturesApi(libs.opentelemetry.api)
    testFixturesApi(libs.opentelemetry.sdk)
    // Consumers of `instrumentedMockClient(...)` test fixture (e.g.
    // `:infra:supabase`'s `SupabaseBroadcastTraceparentPropagationTest`)
    // need the Ktor MockEngine + KtorClientTelemetry on their classpath.
    testFixturesImplementation(libs.opentelemetry.instrumentationKtor3)
    testFixturesImplementation(libs.ktor.clientCore)
    testFixturesImplementation(libs.ktor.clientMock)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    listOf("kotest.tags", "kotest.filter.tests", "kotest.filter.specs").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
