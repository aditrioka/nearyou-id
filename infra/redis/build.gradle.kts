plugins {
    id("nearyou.kotlin.jvm")
}

dependencies {
    implementation(projects.core.domain)
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
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    listOf("kotest.tags", "kotest.filter.tests", "kotest.filter.specs").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
