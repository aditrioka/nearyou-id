package id.nearyou.app.health

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory
import javax.sql.DataSource

private val logger = LoggerFactory.getLogger("HealthRoutes")

const val READY_PROBE_TIMEOUT_MS = 500L

fun Application.healthRoutes() {
    val dataSource by inject<DataSource>()

    routing {
        get("/health/live") {
            call.respond(HttpStatusCode.OK)
        }
        get("/health/ready") {
            val ok = probeDatabase(dataSource)
            if (ok) {
                call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            } else {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("status" to "degraded"))
            }
        }
    }
}

private suspend fun probeDatabase(dataSource: DataSource): Boolean {
    return withTimeoutOrNull(READY_PROBE_TIMEOUT_MS) {
        runCatching {
            withContext(Dispatchers.IO) {
                dataSource.connection.use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.executeQuery("SELECT 1").use { rs -> rs.next() }
                    }
                }
            }
        }.onFailure { logger.warn("readiness probe failed", it) }.getOrNull()
    } ?: false
}
