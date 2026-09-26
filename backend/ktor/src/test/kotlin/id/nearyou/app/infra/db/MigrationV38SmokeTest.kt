package id.nearyou.app.infra.db

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.Date
import java.time.LocalDate
import java.util.UUID

/**
 * Database-dependent smoke test for V38 (`V38__chat_embedded_post_author_erasure.sql`,
 * `embedded-snapshot-author-erasure`):
 *
 *   - `chat_messages.embedded_post_author_id` exists (nullable uuid), FK → `users` ON DELETE SET
 *     NULL + validated, and the `NOW()`-free partial index exists.
 *   - Backfill + retro-scrub: V38 is idempotent, so each test seeds a PRE-V38-shaped row
 *     (`embedded_post_author_id` NULL) and re-executes the exact migration file bytes.
 *
 * Seeds posts, so every test cleans up its own rows (the timeline suites assert exact post sets);
 * posts sit at deep-ocean coords. Tagged `database`.
 */
@Tags("database")
class MigrationV38SmokeTest : StringSpec({

    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val user = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"

    Flyway
        .configure()
        .dataSource(url, user, password)
        .locations("classpath:db/migration")
        .load()
        .migrate()

    val v38Sql =
        checkNotNull(javaClass.getResource("/db/migration/V38__chat_embedded_post_author_erasure.sql")).readText()

    fun connect(): Connection = java.sql.DriverManager.getConnection(url, user, password)

    fun seedUser(conn: Connection): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        conn.prepareStatement(
            """
            INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, id)
            ps.setString(2, "v38_$short")
            ps.setString(3, "V38 Tester $short")
            ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
            ps.setString(5, "w${short.take(7)}")
            ps.executeUpdate()
        }
        return id
    }

    fun seedPost(
        conn: Connection,
        authorId: UUID,
    ): UUID {
        val id = UUID.randomUUID()
        conn.prepareStatement(
            """
            INSERT INTO posts (id, author_id, content, display_location, actual_location)
            VALUES (?, ?, 'v38 post',
              ST_SetSRID(ST_MakePoint(105.0, -10.5), 4326)::geography,
              ST_SetSRID(ST_MakePoint(105.0, -10.5), 4326)::geography)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, id)
            ps.setObject(2, authorId)
            ps.executeUpdate()
        }
        return id
    }

    /** A pre-V38-shaped embed row: `embedded_post_author_id` left NULL. */
    fun seedEmbed(
        conn: Connection,
        senderId: UUID,
        postId: UUID,
        snapshotJson: String,
    ): UUID {
        val convId = UUID.randomUUID()
        conn.prepareStatement("INSERT INTO conversations (id, created_by) VALUES (?, ?)").use { ps ->
            ps.setObject(1, convId)
            ps.setObject(2, senderId)
            ps.executeUpdate()
        }
        val id = UUID.randomUUID()
        conn.prepareStatement(
            """
            INSERT INTO chat_messages (id, conversation_id, sender_id, embedded_post_id, embedded_post_snapshot)
            VALUES (?, ?, ?, ?, ?::jsonb)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, id)
            ps.setObject(2, convId)
            ps.setObject(3, senderId)
            ps.setObject(4, postId)
            ps.setString(5, snapshotJson)
            ps.executeUpdate()
        }
        return id
    }

    fun snapshotJson(
        username: String,
        displayName: String,
    ): String =
        """{"authorUsername":"$username","authorDisplayName":"$displayName","content":"v38 post",""" +
            """"cityName":null,"createdAt":"2026-09-01T00:00:00Z","editedAt":null}"""

    fun embedRow(
        conn: Connection,
        messageId: UUID,
    ): Pair<UUID?, String> =
        conn.prepareStatement(
            "SELECT embedded_post_author_id, embedded_post_snapshot::text FROM chat_messages WHERE id = ?",
        ).use { ps ->
            ps.setObject(1, messageId)
            ps.executeQuery().use { rs ->
                check(rs.next())
                rs.getObject(1, UUID::class.java) to rs.getString(2)
            }
        }

    fun cleanup(
        conn: Connection,
        vararg ids: UUID,
    ) {
        val inList = ids.joinToString(",") { "'$it'" }
        conn.createStatement().use { st ->
            st.executeUpdate("DELETE FROM conversations WHERE created_by IN ($inList)")
            st.executeUpdate("DELETE FROM posts WHERE author_id IN ($inList)")
            st.executeUpdate("DELETE FROM users WHERE id IN ($inList)")
        }
    }

    "V38 — column is nullable uuid, FK to users ON DELETE SET NULL validated, NOW()-free partial index" {
        connect().use { conn ->
            conn.prepareStatement(
                """
                SELECT data_type, is_nullable FROM information_schema.columns
                 WHERE table_name = 'chat_messages' AND column_name = 'embedded_post_author_id'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    rs.getString("data_type") shouldBe "uuid"
                    rs.getString("is_nullable") shouldBe "YES"
                }
            }
            conn.prepareStatement(
                """
                SELECT confdeltype, convalidated, confrelid::regclass::text AS target
                  FROM pg_constraint
                 WHERE contype = 'f' AND conname = 'chat_messages_embedded_post_author_id_fkey'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    rs.getString("confdeltype") shouldBe "n"
                    rs.getBoolean("convalidated") shouldBe true
                    rs.getString("target") shouldBe "users"
                }
            }
            conn.prepareStatement(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'chat_messages_embedded_post_author_idx'",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    val def = rs.getString(1)
                    def shouldContain "(embedded_post_author_id)"
                    def shouldContain "WHERE (embedded_post_author_id IS NOT NULL)"
                    def.lowercase() shouldNotContain "now()"
                }
            }
        }
    }

    "V38 — backfills embedded_post_author_id for a pre-V38 embed row" {
        connect().use { conn ->
            val author = seedUser(conn)
            val sender = seedUser(conn)
            try {
                val post = seedPost(conn, author)
                val msg = seedEmbed(conn, sender, post, snapshotJson("v38_live", "Live Author"))
                embedRow(conn, msg).first shouldBe null
                conn.createStatement().use { it.execute(v38Sql) }
                embedRow(conn, msg).first shouldBe author
            } finally {
                cleanup(conn, author, sender)
            }
        }
    }

    "V38 — retro-scrubs snapshots of already-tombstoned authors; live-author canary untouched" {
        connect().use { conn ->
            val deadAuthor = seedUser(conn)
            val liveAuthor = seedUser(conn)
            val sender = seedUser(conn)
            try {
                val deadPost = seedPost(conn, deadAuthor)
                val livePost = seedPost(conn, liveAuthor)
                val deadMsg = seedEmbed(conn, sender, deadPost, snapshotJson("orig_handle", "Orig Name"))
                val liveMsg = seedEmbed(conn, sender, livePost, snapshotJson("live_handle", "Live Name"))
                // Tombstone deadAuthor the way the worker does (identity columns only matter here).
                conn.prepareStatement(
                    """
                    UPDATE users SET deleted_at = NOW(), display_name = 'Akun Dihapus',
                           username = 'deleted_user_' || left(id::text, 8)
                     WHERE id = ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, deadAuthor)
                    ps.executeUpdate()
                }
                val liveBefore = embedRow(conn, liveMsg).second

                conn.createStatement().use { it.execute(v38Sql) }

                val dead = Json.parseToJsonElement(embedRow(conn, deadMsg).second).jsonObject
                dead["authorUsername"]!!.jsonPrimitive.content shouldBe "deleted_user_${deadAuthor.toString().take(8)}"
                dead["authorDisplayName"]!!.jsonPrimitive.content shouldBe "Akun Dihapus"
                dead["content"]!!.jsonPrimitive.content shouldBe "v38 post"
                dead["createdAt"]!!.jsonPrimitive.content shouldBe "2026-09-01T00:00:00Z"
                dead.keys shouldBe
                    setOf("authorUsername", "authorDisplayName", "content", "cityName", "createdAt", "editedAt")
                embedRow(conn, deadMsg).second shouldNotContain "orig_handle"
                embedRow(conn, deadMsg).second shouldNotContain "Orig Name"
                embedRow(conn, liveMsg).second shouldBe liveBefore
            } finally {
                cleanup(conn, deadAuthor, liveAuthor, sender)
            }
        }
    }
})
