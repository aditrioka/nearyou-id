plugins {
    id("nearyou.kotlin.jvm")
}

dependencies {
    implementation(projects.core.domain)
    implementation(projects.core.data)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.hikaricp)
    // compileOnly gives PGobject typed access for jsonb params in
    // JdbcNotificationRepository without promoting the driver to a transitive
    // runtime dep beyond this module — `runtimeOnly` still drives the actual
    // registration.
    compileOnly(libs.postgresql)
    runtimeOnly(libs.postgresql)
}
