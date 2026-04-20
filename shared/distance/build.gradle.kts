plugins {
    id("nearyou.kotlin.multiplatform")
}

kotlin {
    jvm()

    // iosArm64() / iosSimulatorArm64() / androidTarget() are deferred to the
    // mobile change. When they land, they will provide `actual` implementations
    // for the `expect` declarations in commonMain (HMAC-SHA256, unixMillis).

    sourceSets {
        commonMain.dependencies {
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotest.assertionsCore)
            implementation(libs.kotest.runnerJunit5)
        }
        jvmTest.dependencies {
            implementation(libs.kotest.runnerJunit5)
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
