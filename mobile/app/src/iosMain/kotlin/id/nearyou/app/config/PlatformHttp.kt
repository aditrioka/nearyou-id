package id.nearyou.app.config

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

actual fun httpClientEngine(): HttpClientEngine = Darwin.create()

@OptIn(ExperimentalNativeApi::class)
actual val isDebugBuild: Boolean
    get() = Platform.isDebugBinary
