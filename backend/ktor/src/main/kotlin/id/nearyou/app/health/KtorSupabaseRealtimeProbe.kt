package id.nearyou.app.health

import id.nearyou.app.core.domain.health.ProbeError
import id.nearyou.app.core.domain.health.ProbeResult
import id.nearyou.app.core.domain.health.SupabaseRealtimeProbe
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.net.ConnectException
import java.net.UnknownHostException
import java.time.Duration
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * Ktor-backed Supabase Realtime liveness probe. Issues GET against
 * `${supabaseUrl}/rest/v1/` and treats any HTTP response (200, 401, 404, 500, 502,
 * 503) as `ok = true` — the probe asserts EDGE reachability, not data-plane
 * authorization. Only TLS / DNS / connection-refused / timeout failures map to
 * `ok = false`.
 *
 * The Realtime WSS endpoint and the REST endpoint share the same Supabase edge,
 * so a successful HTTP response from `/rest/v1/` proves the network path, DNS,
 * TLS, and the upstream service are alive enough for Cloud Run readiness purposes.
 *
 * Error vocabulary mapping:
 *  - `withTimeoutOrNull` returns null → `ProbeError.TIMEOUT`.
 *  - `UnknownHostException` → `ProbeError.DNS_FAILURE`.
 *  - `SSLHandshakeException` / `SSLException` → `ProbeError.TLS_FAILURE`.
 *  - `ConnectException` → `ProbeError.CONNECTION_REFUSED`.
 *  - Any other throwable → `ProbeError.UNKNOWN` (logged at WARN with full
 *    context).
 */
class KtorSupabaseRealtimeProbe(
    private val httpClient: HttpClient,
    private val supabaseUrl: String,
) : SupabaseRealtimeProbe {
    private val probeUrl: String =
        if (supabaseUrl.endsWith("/")) "${supabaseUrl}rest/v1/" else "$supabaseUrl/rest/v1/"

    override suspend fun ping(timeout: Duration): ProbeResult {
        val startNs = System.nanoTime()
        return try {
            val result =
                withTimeoutOrNull(timeout.toMillis()) {
                    // Any HTTP response counts as ok=true. Ktor only throws on
                    // network-layer failures or explicit response-validation
                    // errors; the default HttpClient does NOT throw on 4xx/5xx
                    // unless `expectSuccess = true` is set, which we do NOT use.
                    httpClient.get(probeUrl)
                }
            val elapsedMs = elapsedMs(startNs)
            if (result == null) {
                ProbeResult(ok = false, latencyMs = elapsedMs, error = ProbeError.TIMEOUT)
            } else {
                ProbeResult(ok = true, latencyMs = elapsedMs, error = null)
            }
        } catch (e: HttpRequestTimeoutException) {
            logProbeFailure(e)
            ProbeResult(ok = false, latencyMs = elapsedMs(startNs), error = ProbeError.TIMEOUT)
        } catch (e: UnknownHostException) {
            logProbeFailure(e)
            ProbeResult(ok = false, latencyMs = elapsedMs(startNs), error = ProbeError.DNS_FAILURE)
        } catch (e: SSLHandshakeException) {
            logProbeFailure(e)
            ProbeResult(ok = false, latencyMs = elapsedMs(startNs), error = ProbeError.TLS_FAILURE)
        } catch (e: SSLException) {
            logProbeFailure(e)
            ProbeResult(ok = false, latencyMs = elapsedMs(startNs), error = ProbeError.TLS_FAILURE)
        } catch (e: ConnectException) {
            logProbeFailure(e)
            ProbeResult(ok = false, latencyMs = elapsedMs(startNs), error = ProbeError.CONNECTION_REFUSED)
        } catch (e: Throwable) {
            logProbeFailure(e)
            ProbeResult(ok = false, latencyMs = elapsedMs(startNs), error = ProbeError.UNKNOWN)
        }
    }

    private fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000L

    private fun logProbeFailure(e: Throwable) {
        logger.warn(
            "event=supabase_probe_failed url={} reason={} message={}",
            probeUrl,
            e.javaClass.simpleName,
            e.message,
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(KtorSupabaseRealtimeProbe::class.java)
    }
}
