plugins {
    id("nearyou.kotlin.jvm")
}

dependencies {
    implementation(libs.firebase.admin)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotest.runnerJunit5)
    testImplementation(libs.kotest.assertionsCore)
    testImplementation(libs.logback)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    listOf("kotest.tags", "kotest.filter.tests", "kotest.filter.specs").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
