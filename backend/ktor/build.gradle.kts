plugins {
    id("nearyou.ktor")
}

group = "id.nearyou.app"
version = "1.0.0"

application {
    mainClass.set("io.ktor.server.netty.EngineMain")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

dependencies {
    implementation(projects.shared.tmp)
    implementation(projects.shared.distance)
    implementation(projects.core.domain)
    implementation(projects.core.data)
    implementation(projects.infra.supabase)
    implementation(projects.infra.redis)

    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serverStatusPages)
    implementation(libs.ktor.serverCallLogging)
    implementation(libs.ktor.serverAuth)
    implementation(libs.ktor.serverAuthJwt)
    implementation(libs.ktor.serializationKotlinxJson)
    implementation(libs.ktor.clientCore)
    implementation(libs.ktor.clientCio)
    implementation(libs.ktor.clientContentNegotiation)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    implementation(libs.koin.core)
    implementation(libs.koin.ktor)
    implementation(libs.koin.loggerSlf4j)

    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.databasePostgresql)
    implementation(libs.hikaricp)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotest.runnerJunit5)
    testImplementation(libs.kotest.assertionsCore)
    // The V10 notifications smoke test constructs `PGobject` directly to seed
    // jsonb columns; promote the driver out of runtime-only on the test classpath.
    testImplementation(libs.postgresql)

    detektPlugins(projects.lint.detektRules)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Forward Kotest system properties (e.g. -Dkotest.tags=!network) to the test JVM.
    listOf("kotest.tags", "kotest.filter.tests", "kotest.filter.specs").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}

// Dev-only: mint an access JWT for an existing users.id, for manual curl smoke tests.
// Wrapped by `dev/scripts/mint-dev-jwt.sh`. Reads KTOR_RSA_PRIVATE_KEY from the env.
tasks.register<JavaExec>("mintDevJwt") {
    group = "application"
    description = "Mint a dev access JWT for the given user UUID."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("id.nearyou.app.dev.MintDevJwtKt")
    standardInput = System.`in`
    // Quiet down Gradle's own output so the captured stdout is just the token.
    logging.captureStandardOutput(LogLevel.QUIET)
}
