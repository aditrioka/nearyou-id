package id.nearyou.app

import io.kotest.core.config.AbstractProjectConfig
import org.flywaydb.core.Flyway
import java.sql.DriverManager

/**
 * Project-wide test bootstrap. Runs once at the start of the test JVM; idempotent.
 *
 * 1. **Postgres / Flyway** — runs Flyway V1..V13 migrations if a reachable Postgres
 *    is on `DB_URL` (falling back to the local dev compose default). Individual
 *    integration tests assume a previously-migrated schema and `INSERT INTO users`
 *    directly without running Flyway themselves; in CI with a fresh service
 *    container, nothing has ever run Flyway, so those `INSERT`s blow up. Flyway
 *    migrations are idempotent — re-running on an already-migrated dev DB is a
 *    no-op.
 *
 * 2. **Redis URL injection** — exports `REDIS_URL` as a JVM system property if it
 *    isn't already set (System.getProperty fallback chain in `EnvVarSecretResolver`
 *    picks this up since the env var cannot be portably mutated from Kotlin).
 *    Mirrors the Postgres probe-then-skip pattern: if Redis is unreachable on the
 *    default URL, we don't set the property — `NoOpRateLimiter` will be bound at
 *    Application startup. Tests that need a real Redis (e.g., `LikeRateLimitTest`,
 *    section 8) MUST manage their own client lifecycle, identical to
 *    `RedisRateLimiterIntegrationTest` precedent in `:infra:redis`.
 *
 * If either DB is unreachable on a local unit-only run, we skip silently — unit
 * tests that don't touch external services still run; database-tagged specs fail
 * loudly on first connection attempt as they would have anyway.
 */
@Suppress("unused")
class KotestProjectConfig : AbstractProjectConfig() {
    override suspend fun beforeProject() {
        bootstrapPostgres()
        bootstrapRedis()
    }

    private fun bootstrapPostgres() {
        val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
        val user = System.getenv("DB_USER") ?: "postgres"
        val password = System.getenv("DB_PASSWORD") ?: "postgres"

        // Probe: if no Postgres is reachable, skip bootstrap entirely so unit-
        // only test runs don't fail at project startup.
        try {
            DriverManager.getConnection(url, user, password).use { it.isValid(2) }
        } catch (_: Exception) {
            return
        }

        Flyway
            .configure()
            .dataSource(url, user, password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    /**
     * If `REDIS_URL` env-or-system-property isn't already set, probe the local
     * dev compose default (`redis://localhost:6379`); on success, export it as a
     * JVM system property so Application bootstrap finds it via
     * `EnvVarSecretResolver` (which falls back to system properties when the
     * env var is absent).
     *
     * The probe is a TCP connect with a short timeout so a missing Redis on a
     * unit-only run never delays JVM startup.
     */
    private fun bootstrapRedis() {
        // Already set — caller is in control (CI sets `REDIS_URL=redis://localhost:6379`
        // explicitly; integration tests like `RedisRateLimiterIntegrationTest` read
        // their own value directly).
        if (!System.getenv("REDIS_URL").isNullOrBlank()) return
        if (!System.getProperty("REDIS_URL").isNullOrBlank()) return

        val defaultUrl = "redis://localhost:6379"
        if (probeRedis("localhost", 6379)) {
            System.setProperty("REDIS_URL", defaultUrl)
        }
        // No-op on probe failure — Application bootstrap will fall through to
        // NoOpRateLimiter (dev/test fail-soft). Tests that need a real Redis
        // declare it themselves.
    }

    private fun probeRedis(
        host: String,
        port: Int,
    ): Boolean =
        try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
                true
            }
        } catch (_: Exception) {
            false
        }

    companion object {
        private const val PROBE_TIMEOUT_MS = 500
    }
}
