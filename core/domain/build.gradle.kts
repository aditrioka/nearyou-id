plugins {
    id("nearyou.kotlin.jvm")
}

dependencies {
    testImplementation(libs.kotest.runnerJunit5)
    testImplementation(libs.kotest.assertionsCore)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
