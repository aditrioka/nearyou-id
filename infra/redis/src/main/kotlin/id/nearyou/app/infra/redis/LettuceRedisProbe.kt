package id.nearyou.app.infra.redis

import id.nearyou.app.core.domain.health.ProbeError
import id.nearyou.app.core.domain.health.ProbeResult
import id.nearyou.app.core.domain.health.RedisProbe
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisConnectionException
import io.lettuce.core.RedisException
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.net.UnknownHostException
import java.time.Duration
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * Lettuce-backed [RedisProbe]. Issues `PING` over a lazily-established connection
 * with a cooperative timeout. Reuses the connection across calls (no eager
 * connect; lazy on first call, retained until process exit or explicit close).
 *
 * The probe MUST NOT throw; all failure modes map to a [ProbeResult] with one of
 * the fixed-vocabulary error strings from [ProbeError]. Original exceptions are
 * logged at WARN with full context for operator debugging.
 */
class LettuceRedisProbe(
    private val client: RedisClient,
) : RedisProbe {
    @Volatile
    private var connection: StatefulRedisConnection<String, String>? = null

    override suspend fun ping(timeout: Duration): ProbeResult {
        val startNs = System.nanoTime()
        return try {
            val result =
                withTimeoutOrNull(timeout.toMillis()) {
                    withContext(Dispatchers.IO) {
                        connectionOrCreate().sync().ping()
                    }
                }
            val elapsedMs = elapsedMs(startNs)
            if (result == null) {
                ProbeResult(ok = false, latencyMs = elapsedMs, error = ProbeError.TIMEOUT)
            } else {
                ProbeResult(ok = true, latencyMs = elapsedMs, error = null)
            }
        } catch (e: UnknownHostException) {
            logProbeFailure(e)
            ProbeResult(ok = false, latencyMs = elapsedMs(startNs), error = ProbeError.DNS_FAILURE)
        } catch (e: SSLHandshakeException) {
            logProbeFailure(e)
            ProbeResult(ok = false, latencyMs = elapsedMs(startNs), error = ProbeError.TLS_FAILURE)
        } catch (e: SSLException) {
            logProbeFailure(e)
            ProbeResult(ok = false, latencyMs = elapsedMs(startNs), error = ProbeError.TLS_FAILURE)
        } catch (e: RedisConnectionException) {
            logProbeFailure(e)
            ProbeResult(ok = false, latencyMs = elapsedMs(startNs), error = ProbeError.CONNECTION_REFUSED)
        } catch (e: RedisException) {
            logProbeFailure(e)
            ProbeResult(ok = false, latencyMs = elapsedMs(startNs), error = ProbeError.CONNECTION_REFUSED)
        } catch (e: Throwable) {
            logProbeFailure(e)
            ProbeResult(ok = false, latencyMs = elapsedMs(startNs), error = ProbeError.UNKNOWN)
        }
    }

    private fun connectionOrCreate(): StatefulRedisConnection<String, String> {
        connection?.let { existing -> if (existing.isOpen) return existing }
        return synchronized(this) {
            connection?.let { existing -> if (existing.isOpen) return@synchronized existing }
            val fresh = client.connect()
            connection = fresh
            fresh
        }
    }

    private fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000L

    private fun logProbeFailure(e: Throwable) {
        logger.warn(
            "event=redis_probe_failed reason={} message={}",
            e.javaClass.simpleName,
            e.message,
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(LettuceRedisProbe::class.java)
    }
}
