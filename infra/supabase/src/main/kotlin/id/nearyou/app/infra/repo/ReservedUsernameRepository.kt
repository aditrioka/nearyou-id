package id.nearyou.app.infra.repo

import java.sql.Connection
import javax.sql.DataSource

/**
 * Lookups against the `reserved_usernames` table (seeded by Flyway V3).
 * The username generator consults this as a fast-path filter before
 * attempting an INSERT INTO users.
 */
interface ReservedUsernameRepository {
    /** Caller is responsible for lowercasing; the query uses an equality comparison. */
    fun exists(lowercaseUsername: String): Boolean

    /** In-transaction variant: reuses the caller's connection. */
    fun exists(
        conn: Connection,
        lowercaseUsername: String,
    ): Boolean
}

class JdbcReservedUsernameRepository(
    private val dataSource: DataSource,
) : ReservedUsernameRepository {
    override fun exists(lowercaseUsername: String): Boolean {
        dataSource.connection.use { conn ->
            return exists(conn, lowercaseUsername)
        }
    }

    override fun exists(
        conn: Connection,
        lowercaseUsername: String,
    ): Boolean {
        conn.prepareStatement(
            "SELECT 1 FROM reserved_usernames WHERE username = ? LIMIT 1",
        ).use { ps ->
            ps.setString(1, lowercaseUsername)
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }
}
