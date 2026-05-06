plugins {
    id("nearyou.kotlin.jvm")
    alias(libs.plugins.kotlinxSerialization)
}

dependencies {
    implementation(projects.core.domain)
    implementation(projects.core.data)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hikaricp)
    implementation(libs.slf4j.api)
    // Ktor HttpClient (CIO engine) backs SupabaseBroadcastChatClient — hand-rolled REST
    // against Supabase Realtime broadcast HTTP API per `chat-realtime-broadcast` design § D7
    // (fallback path: no SDK adoption).
    implementation(libs.ktor.clientCore)
    implementation(libs.ktor.clientCio)
    // compileOnly gives PGobject typed access for jsonb params in
    // JdbcNotificationRepository without promoting the driver to a transitive
    // runtime dep beyond this module — `runtimeOnly` still drives the actual
    // registration.
    compileOnly(libs.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.kotest.runnerJunit5)
    testImplementation(libs.kotest.assertionsCore)
    // Ktor MockEngine drives unit tests against SupabaseBroadcastChatClient without
    // hitting a real Supabase project (matches `:infra:redis`'s in-process limiter
    // pattern at the network-tagged adapter test boundary).
    testImplementation(libs.ktor.clientMock)
    // Logback ListAppender used by the service-role-key-not-in-logs scenario in
    // SupabaseBroadcastChatClientTest (matches the precedent set by `:infra:redis`).
    testImplementation(libs.logback)
    // SpanRecorder + the OTel surface for the W3C `traceparent` outbound
    // propagation test (`SupabaseBroadcastTraceparentPropagationTest`).
    testImplementation(projects.infra.otel)
    testImplementation(testFixtures(projects.infra.otel))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    listOf("kotest.tags", "kotest.filter.tests", "kotest.filter.specs").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
