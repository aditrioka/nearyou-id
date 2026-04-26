package id.nearyou.app.health

import id.nearyou.app.core.domain.health.PostgresProbe
import id.nearyou.app.core.domain.health.ProbeError
import id.nearyou.app.core.domain.health.ProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.time.Duration
import javax.sql.DataSource

/**
 * Production [PostgresProbe] implementation. Borrows a connection from the
 * supplied [DataSource] (typically the HikariCP pool) and issues `SELECT 1`
 * inside `withContext(Dispatchers.IO)`, wrapped by a cooperative
 * `withTimeoutOrNull` guard.
 *
 * Connection is returned to the pool via `.use { }` even if the timeout fires
 * after acquire — the JDBC blocking call itself may not honor cancellation, but
 * the caller's coroutine is freed and the outer 2-second cap (in the route
 * handler) bounds total latency as defense-in-depth.
 */
class JdbcPostgresProbe(
    private val dataSource: DataSource,
) : PostgresProbe {
    override suspend fun probe(timeout: Duration): ProbeResult {
        val startNs = System.nanoTime()
        val result =
            withTimeoutOrNull(timeout.toMillis()) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        dataSource.connection.use { conn ->
                            conn.createStatement().use { stmt ->
                                stmt.executeQuery("SELECT 1").use { rs -> rs.next() }
                            }
                        }
                    }
                }
            }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        return when {
            result == null ->
                ProbeResult(ok = false, latencyMs = elapsedMs, error = ProbeError.TIMEOUT)
            result.isFailure -> {
                val cause = result.exceptionOrNull()
                logger.warn(
                    "event=postgres_probe_failed reason={} message={}",
                    cause?.javaClass?.simpleName,
                    cause?.message,
                )
                val error =
                    when (cause) {
                        is SQLException -> ProbeError.CONNECTION_REFUSED
                        else -> ProbeError.UNKNOWN
                    }
                ProbeResult(ok = false, latencyMs = elapsedMs, error = error)
            }
            else -> ProbeResult(ok = true, latencyMs = elapsedMs, error = null)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(JdbcPostgresProbe::class.java)
    }
}
