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

    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serverStatusPages)
    implementation(libs.ktor.serverCallLogging)
    implementation(libs.ktor.serializationKotlinxJson)

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
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
