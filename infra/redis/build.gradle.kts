plugins {
    id("nearyou.kotlin.jvm")
}

dependencies {
    implementation(projects.core.domain)
    implementation(projects.infra.otel)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.koin.core)
    implementation(libs.lettuce.core)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotest.runnerJunit5)
    testImplementation(libs.kotest.assertionsCore)
    // Logback ListAppender used by RedisRateLimiterTelemetryTest to capture
    // WARN log output and verify the user_id-omission invariant on
    // tryAcquireByKey (per `health-check-test-coverage-gaps` follow-up).
    testImplementation(libs.logback)
    // SpanRecorder + OpenTelemetry SDK pulled via `:infra:otel`'s test
    // fixtures so LettuceSpanCaptureTest can capture the EVALSHA span and
    // verify the hashed IP-axis Lua-key shape (per
    // `observability-otel-foundation` MODIFIED scenario "No raw client IP
    // appears in Lua key on EVALSHA span").
    testImplementation(testFixtures(projects.infra.otel))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    listOf("kotest.tags", "kotest.filter.tests", "kotest.filter.specs").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
