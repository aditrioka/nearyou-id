plugins {
    id("nearyou.kotlin.jvm")
}

dependencies {
    implementation(projects.core.domain)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.koin.core)
    implementation(libs.lettuce.core)
}
