package id.nearyou.app.infra.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

data class DbConfig(
    val url: String,
    val user: String,
    val password: String,
    // docs/11 §3.2: small per-instance pools (2-10); maxInstances × poolSize must
    // fit the Supavisor budget. Env-tunable via DB_MAX_POOL_SIZE (application.conf).
    val maxPoolSize: Int = 10,
)

object DataSourceFactory {
    fun create(config: DbConfig): DataSource {
        val hikariConfig =
            HikariConfig().apply {
                jdbcUrl = config.url
                username = config.user
                password = config.password
                maximumPoolSize = config.maxPoolSize
                driverClassName = "org.postgresql.Driver"
                // Defer connectivity check to first checkout; /health/ready is the canonical probe.
                initializationFailTimeout = -1
                // Tight timeout so /health/ready can flip to 503 within its 500 ms budget.
                connectionTimeout = 1_500
                validationTimeout = 500
                // Recycle connections well below pooler idle cutoffs (Supavisor closes
                // idle server connections; Hikari's default 30 min maxLifetime can hand
                // out connections the pooler already dropped). docs/11 §3.2.
                maxLifetime = 300_000
                // Checkout held >10 s without close logs WARN + stack trace — cheap
                // leak detection for the bounded-pool discipline.
                leakDetectionThreshold = 10_000
                // Supavisor transaction mode (:6543) requires server-side prepared
                // statements OFF (docs/11 §3.2; Supabase "connecting to postgres" docs).
                // pgjdbc still prepares client-side; on direct Postgres (dev/CI) this
                // only skips the server-prepare optimization — correctness first.
                addDataSourceProperty("prepareThreshold", "0")
            }
        return HikariDataSource(hikariConfig)
    }
}
