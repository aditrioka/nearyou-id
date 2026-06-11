plugins {
    id("nearyou.kotlin.jvm")
    id("nearyou.detekt")
}

dependencies {
    implementation(projects.core.domain)
}
