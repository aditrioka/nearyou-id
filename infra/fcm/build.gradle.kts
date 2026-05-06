plugins {
    id("nearyou.kotlin.jvm")
}

dependencies {
    api(projects.core.data)
    implementation(projects.core.domain)
    implementation(projects.infra.otel)
    implementation(libs.firebase.admin)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotest.runnerJunit5)
    testImplementation(libs.kotest.assertionsCore)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.logback)
    // SpanRecorder + FailingSpanProcessor for the OTel-foundation amendment
    // tests covering the WARN-log↔span-event pairing scenarios per
    // `fcm-push-dispatch` spec.
    testImplementation(testFixtures(projects.infra.otel))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    listOf("kotest.tags", "kotest.filter.tests", "kotest.filter.specs").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
