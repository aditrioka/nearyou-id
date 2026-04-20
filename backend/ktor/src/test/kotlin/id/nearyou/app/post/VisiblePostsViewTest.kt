package id.nearyou.app.post

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.sql.Date
import java.time.LocalDate
import java.util.UUID

@Tags("database")
class VisiblePostsViewTest : StringSpec({

    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val dbUser = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"
    val dataSource: HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = url
                username = dbUser
                this.password = password
                maximumPoolSize = 2
                initializationFailTimeout = -1
            },
        )

    // Seed the minimum state needed: a disposable user + a post row that we toggle.
    fun seedUser(): UUID {
        val uid = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, uid)
                val short = uid.toString().replace("-", "").take(8)
                ps.setString(2, "vv_$short")
                ps.setString(3, "View Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1995, 3, 14)))
                ps.setString(5, "v${short.take(7)}")
                ps.executeUpdate()
            }
        }
        return uid
    }

    fun seedPost(
        userId: UUID,
        isAutoHidden: Boolean,
    ): UUID {
        val postId = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO posts (id, author_id, content, display_location, actual_location, is_auto_hidden)
                VALUES (
                    ?, ?, 'view test content',
                    ST_SetSRID(ST_MakePoint(106.8, -6.2), 4326)::geography,
                    ST_SetSRID(ST_MakePoint(106.8, -6.2), 4326)::geography,
                    ?
                )
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, postId)
                ps.setObject(2, userId)
                ps.setBoolean(3, isAutoHidden)
                ps.executeUpdate()
            }
        }
        return postId
    }

    fun cleanup(userId: UUID) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("DELETE FROM posts WHERE author_id = '$userId'")
                st.executeUpdate("DELETE FROM users WHERE id = '$userId'")
            }
        }
    }

    "is_auto_hidden = TRUE → invisible via visible_posts; flip to FALSE → visible" {
        val uid = seedUser()
        try {
            val pid = seedPost(uid, isAutoHidden = true)
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT COUNT(*) FROM visible_posts WHERE id = ?::uuid").use { ps ->
                    ps.setString(1, pid.toString())
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 0
                    }
                }
                // Flip the hidden flag.
                conn.prepareStatement("UPDATE posts SET is_auto_hidden = FALSE WHERE id = ?::uuid").use { ps ->
                    ps.setString(1, pid.toString())
                    ps.executeUpdate() shouldBe 1
                }
                conn.prepareStatement("SELECT COUNT(*) FROM visible_posts WHERE id = ?::uuid").use { ps ->
                    ps.setString(1, pid.toString())
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 1
                    }
                }
            }
        } finally {
            cleanup(uid)
        }
    }

    "default is_auto_hidden = FALSE → new rows visible without a flip" {
        val uid = seedUser()
        try {
            val pid = seedPost(uid, isAutoHidden = false)
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT COUNT(*) FROM visible_posts WHERE id = ?::uuid").use { ps ->
                    ps.setString(1, pid.toString())
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 1
                    }
                }
            }
        } finally {
            cleanup(uid)
        }
    }
})
