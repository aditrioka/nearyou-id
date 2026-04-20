package id.nearyou.app.infra.repo

import java.sql.Connection
import javax.sql.DataSource

/**
 * Read/write access to the `rejected_identifiers` blocklist.
 * Used for under-18 signup rejection anti-bypass and (later) attestation
 * persistent-fail blocklisting.
 */
interface RejectedIdentifierRepository {
    fun exists(
        identifierHash: String,
        type: IdentifierType,
    ): Boolean

    /** In-transaction variant: reuses the caller's connection. */
    fun exists(
        conn: Connection,
        identifierHash: String,
        type: IdentifierType,
    ): Boolean

    /**
     * ON CONFLICT (identifier_hash, identifier_type) DO NOTHING — idempotent.
     * Returns true if a new row was inserted, false if the row already existed.
     */
    fun insert(
        conn: Connection,
        identifierHash: String,
        type: IdentifierType,
        reason: RejectedReason,
    ): Boolean
}

class JdbcRejectedIdentifierRepository(
    private val dataSource: DataSource,
) : RejectedIdentifierRepository {
    override fun exists(
        identifierHash: String,
        type: IdentifierType,
    ): Boolean = dataSource.connection.use { conn -> exists(conn, identifierHash, type) }

    override fun exists(
        conn: Connection,
        identifierHash: String,
        type: IdentifierType,
    ): Boolean {
        conn.prepareStatement(
            "SELECT 1 FROM rejected_identifiers WHERE identifier_hash = ? AND identifier_type = ? LIMIT 1",
        ).use { ps ->
            ps.setString(1, identifierHash)
            ps.setString(2, type.sql)
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }

    override fun insert(
        conn: Connection,
        identifierHash: String,
        type: IdentifierType,
        reason: RejectedReason,
    ): Boolean {
        conn.prepareStatement(
            """
            INSERT INTO rejected_identifiers (identifier_hash, identifier_type, reason)
            VALUES (?, ?, ?)
            ON CONFLICT (identifier_hash, identifier_type) DO NOTHING
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, identifierHash)
            ps.setString(2, type.sql)
            ps.setString(3, reason.sql)
            return ps.executeUpdate() > 0
        }
    }
}
