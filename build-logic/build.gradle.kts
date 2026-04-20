plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.gradlePlugin.kotlin)
    implementation(libs.gradlePlugin.android)
    implementation(libs.gradlePlugin.ktor)
    implementation(libs.gradlePlugin.ktlint)
    implementation(libs.gradlePlugin.flyway)
    implementation(libs.flyway.databasePostgresql)
    implementation(libs.gradlePlugin.kotlinSerialization)
    implementation(libs.gradlePlugin.detekt)
}
