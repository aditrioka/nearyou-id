plugins {
    id("nearyou.kotlin.jvm")
    alias(libs.plugins.kotlinxSerialization)
}

dependencies {
    // kotlinx-serialization-json is required by `ChatRealtimeClient`'s `ChatMessageBroadcast`
    // payload (JsonElement-typed `embeddedPostSnapshot` + per-field `@SerialName` annotations
    // for wire-contract visibility, per `chat-realtime-broadcast` design § D13).
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotest.runnerJunit5)
    testImplementation(libs.kotest.assertionsCore)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
