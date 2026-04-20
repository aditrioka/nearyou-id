plugins {
    id("nearyou.kotlin.jvm")
}

dependencies {
    implementation(projects.core.domain)
    implementation(projects.core.data)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.hikaricp)
    runtimeOnly(libs.postgresql)
}
