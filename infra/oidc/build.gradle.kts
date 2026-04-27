plugins {
    id("nearyou.kotlin.jvm")
}

dependencies {
    implementation(projects.core.domain)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
    implementation(libs.auth0.javaJwt)
    implementation(libs.auth0.jwksRsa)

    testImplementation(libs.kotest.runnerJunit5)
    testImplementation(libs.kotest.assertionsCore)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    listOf("kotest.tags", "kotest.filter.tests", "kotest.filter.specs").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
