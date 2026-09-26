plugins {
    id("nearyou.kotlin.jvm")
    id("nearyou.detekt")
    `java-test-fixtures`
}

dependencies {
    // Sentry Java SDK — `implementation` so it never reaches :backend:ktor's compile classpath
    // (invariant #16). The backend-side counterpart of the mobile-only KMP :infra:sentry.
    implementation(libs.sentry.java.core)
    implementation(libs.sentry.java.logback)
    // The appender is attached programmatically to logback's root logger.
    implementation(libs.logback)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotest.runnerJunit5)
    testImplementation(libs.kotest.assertionsCore)

    // SentryEventRecorder — lets :backend:ktor assert end-to-end capture without importing
    // io.sentry (the VendorSdkLeakageScan covers backend test sources too).
    testFixturesImplementation(libs.sentry.java.core)
    testFixturesImplementation(libs.logback)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    listOf("kotest.tags", "kotest.filter.tests", "kotest.filter.specs").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
