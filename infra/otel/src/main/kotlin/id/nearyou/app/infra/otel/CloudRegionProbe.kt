package id.nearyou.app.infra.otel

import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.URI

/**
 * One-shot probe for the GCP metadata server's `instance/region` endpoint.
 *
 * Called exactly ONCE at [OtelBootstrap.start] and the resolved value is
 * cached as a Resource attribute on the `SdkTracerProvider` — every span
 * carries `cloud.region` without per-span lookup overhead.
 *
 * Per the `observability-otel-foundation` capability spec § "Mandatory span
 * attributes": when the metadata server is unreachable (local dev outside
 * Cloud Run, network failure, parse error), the attribute defaults to the
 * literal string `"unknown"`. Any thrown exception or non-200 response is
 * caught and treated as a probe failure → `"unknown"`.
 */
internal object CloudRegionProbe {
    private val log = LoggerFactory.getLogger(CloudRegionProbe::class.java)

    private const val METADATA_URL = "http://metadata.google.internal/computeMetadata/v1/instance/region"
    private const val TIMEOUT_MS = 500
    private const val UNKNOWN = "unknown"

    fun fetch(): String =
        try {
            val url = URI.create(METADATA_URL).toURL()
            val conn = (url.openConnection() as HttpURLConnection)
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Metadata-Flavor", "Google")
            try {
                if (conn.responseCode != 200) {
                    log.debug(
                        "event=otel_cloud_region_probe_unreachable response_code={}",
                        conn.responseCode,
                    )
                    return UNKNOWN
                }
                val body = conn.inputStream.bufferedReader().use { it.readText().trim() }
                parseRegion(body) ?: UNKNOWN
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            // Local dev / CI / non-Cloud-Run JVMs cannot reach metadata.google.internal.
            // Network unreachable + connect-timeout + read-timeout + DNS failure all
            // funnel here. Treat all as "unknown" per spec; emit DEBUG-level so the
            // routine probe failure does not pollute Cloud Logging.
            log.debug(
                "event=otel_cloud_region_probe_failed error_class={} message={}",
                t.javaClass.name,
                t.message,
            )
            UNKNOWN
        }

    /**
     * The metadata server returns the region in the form
     * `projects/<project-num>/regions/<region>` (e.g.,
     * `projects/123456/regions/asia-southeast1`). Strip the prefix and return
     * the trailing region segment. Returns null on any unexpected format so
     * the caller falls back to "unknown".
     */
    internal fun parseRegion(body: String): String? {
        if (body.isBlank()) return null
        val marker = "regions/"
        val idx = body.lastIndexOf(marker)
        return if (idx < 0) {
            // Some metadata server installations return the bare region name.
            // Accept that shape if it looks like a Cloud Run region (e.g.,
            // `asia-southeast1`) — alphanumeric + hyphens, no path separators.
            if (body.matches(Regex("[a-z0-9-]+"))) body else null
        } else {
            body.substring(idx + marker.length).takeIf { it.isNotBlank() }
        }
    }
}
