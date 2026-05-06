package id.nearyou.app.infra.otel

/**
 * Resolved configuration for the OTel SDK pipeline.
 *
 * Built by [OtelBootstrap] after resolving the OTLP endpoint + token via the
 * `secretKey(env, ...)` + `SecretResolver.resolve(...)` two-step pattern shared
 * with every other secret in `:backend:ktor`. Callers do NOT instantiate this
 * directly — it is an internal seam between bootstrap (which owns secret
 * resolution) and the SDK builder (which owns SDK wiring).
 *
 * Stored by-value so the secret token is short-lived in memory; once the SDK is
 * configured the reference goes out of scope.
 */
internal data class OtelConfig(
    val env: String,
    val endpoint: String?,
    val token: String?,
    val samplingProfile: String,
) {
    fun isExporterConfigured(): Boolean = !endpoint.isNullOrBlank() && !token.isNullOrBlank()
}

/**
 * Production sampling base ratio. Extracted as a top-level constant so a future
 * tuning change is a one-line edit (see spec § "Sampling profile per environment").
 */
internal const val PRODUCTION_BASE_SAMPLING_RATIO: Double = 0.1
