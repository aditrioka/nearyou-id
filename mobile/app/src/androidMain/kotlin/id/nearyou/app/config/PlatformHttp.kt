package id.nearyou.app.config

import id.nearyou.app.BuildConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

actual fun httpClientEngine(): HttpClientEngine = OkHttp.create()

actual val isDebugBuild: Boolean
    get() = BuildConfig.DEBUG
