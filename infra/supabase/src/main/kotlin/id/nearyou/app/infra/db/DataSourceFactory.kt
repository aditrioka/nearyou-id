package id.nearyou.app.infra.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

data class DbConfig(
    val url: String,
    val user: String,
    val password: String,
    val maxPoolSize: Int = 20,
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
            }
        return HikariDataSource(hikariConfig)
    }
}
