package id.nearyou.app.infra.repo

import java.sql.Connection
import java.util.UUID

class JdbcPostRepository : PostRepository {
    override fun create(
        conn: Connection,
        row: NewPostRow,
    ): UUID {
        // Single-statement INSERT writes both geographies — the author's-own-content
        // INSERT path; no `FROM posts` here so the raw-posts lint rule does not fire.
        conn.prepareStatement(
            """
            INSERT INTO posts (
                id, author_id, content,
                display_location,
                actual_location
            ) VALUES (
                ?, ?, ?,
                ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography
            )
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, row.id)
            ps.setObject(2, row.authorId)
            ps.setString(3, row.content)
            // ST_MakePoint takes (x = longitude, y = latitude)
            ps.setDouble(4, row.displayLng)
            ps.setDouble(5, row.displayLat)
            ps.setDouble(6, row.actualLng)
            ps.setDouble(7, row.actualLat)
            ps.executeUpdate()
        }
        return row.id
    }
}
