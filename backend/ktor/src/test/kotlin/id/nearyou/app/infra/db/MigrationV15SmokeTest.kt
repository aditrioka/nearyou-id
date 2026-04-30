package id.nearyou.app.infra.db

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.flywaydb.core.Flyway
import java.sql.DriverManager

/**
 * Database-dependent smoke test for V15 (`V15__chat_foundation.sql`). Boots
 * Flyway against the running dev Postgres (or the CI service container) and
 * asserts the chat-foundation schema invariants:
 *
 *   - V15 present in `flyway_schema_history` with success.
 *   - `conversations`, `conversation_participants`, `chat_messages` tables exist
 *     with the canonical column set.
 *   - Six new indexes are installed (1 partial unique on slot + 2 on
 *     conversation_participants + 3 on chat_messages).
 *   - Four CHECK constraints exist (slot ∈ {1,2}, empty-message guard, redaction
 *     atomicity 3-column coupling).
 *   - Deferred-FK columns `chat_messages.redacted_by` and
 *     `chat_messages.embedded_post_edit_id` have NO FK referential constraints
 *     and carry COMMENT ON COLUMN markers documenting the deferred targets.
 *   - When the `realtime` schema exists (Supabase), the
 *     `participants_can_subscribe` policy is installed on `realtime.messages`
 *     AND its body does NOT contain the `is_shadow_banned` clause that V2
 *     drafted (the V15 subscriber-side correction per design.md § D9).
 *
 * Tagged `database` so CI excludes it by default. Run locally with:
 *   `./gradlew :backend:ktor:test -Dkotest.tags=database`
 */
@Tags("database")
class MigrationV15SmokeTest : StringSpec({

    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val user = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"

    Flyway
        .configure()
        .dataSource(url, user, password)
        .locations("classpath:db/migration")
        .load()
        .migrate()

    "V15 present in flyway_schema_history with success" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT success FROM flyway_schema_history WHERE version = '15'",
                ).use { rs ->
                    rs.next() shouldBe true
                    rs.getBoolean(1) shouldBe true
                }
            }
        }
    }

    "all three chat-foundation tables exist" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT table_name FROM information_schema.tables
                     WHERE table_schema = 'public'
                       AND table_name IN ('conversations', 'conversation_participants', 'chat_messages')
                    """.trimIndent(),
                ).use { rs ->
                    val names = mutableSetOf<String>()
                    while (rs.next()) names.add(rs.getString(1))
                    names shouldContainAll
                        setOf("conversations", "conversation_participants", "chat_messages")
                }
            }
        }
    }

    "conversations has the canonical column set" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT column_name FROM information_schema.columns
                     WHERE table_schema = 'public' AND table_name = 'conversations'
                    """.trimIndent(),
                ).use { rs ->
                    val cols = mutableSetOf<String>()
                    while (rs.next()) cols.add(rs.getString(1))
                    cols shouldContainAll
                        setOf("id", "created_at", "created_by", "last_message_at")
                }
            }
        }
    }

    "conversation_participants has the canonical column set + composite PK" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT column_name FROM information_schema.columns
                     WHERE table_schema = 'public' AND table_name = 'conversation_participants'
                    """.trimIndent(),
                ).use { rs ->
                    val cols = mutableSetOf<String>()
                    while (rs.next()) cols.add(rs.getString(1))
                    cols shouldContainAll
                        setOf(
                            "conversation_id",
                            "user_id",
                            "slot",
                            "joined_at",
                            "left_at",
                            "last_read_at",
                        )
                }
            }
            // Composite PK on (conversation_id, user_id).
            conn.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT a.attname
                      FROM pg_index i
                      JOIN pg_class c ON c.oid = i.indrelid
                      JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = ANY(i.indkey)
                     WHERE c.relname = 'conversation_participants' AND i.indisprimary
                     ORDER BY a.attnum
                    """.trimIndent(),
                ).use { rs ->
                    val pkCols = mutableListOf<String>()
                    while (rs.next()) pkCols.add(rs.getString(1))
                    pkCols.toSet() shouldBe setOf("conversation_id", "user_id")
                }
            }
        }
    }

    "chat_messages has the canonical column set" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT column_name FROM information_schema.columns
                     WHERE table_schema = 'public' AND table_name = 'chat_messages'
                    """.trimIndent(),
                ).use { rs ->
                    val cols = mutableSetOf<String>()
                    while (rs.next()) cols.add(rs.getString(1))
                    cols shouldContainAll
                        setOf(
                            "id",
                            "conversation_id",
                            "sender_id",
                            "content",
                            "embedded_post_id",
                            "embedded_post_snapshot",
                            "embedded_post_edit_id",
                            "created_at",
                            "redacted_at",
                            "redacted_by",
                            "redaction_reason",
                        )
                }
            }
        }
    }

    "all six new indexes are installed" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT indexname FROM pg_indexes
                     WHERE schemaname = 'public'
                       AND indexname IN (
                           'conv_slot_unique',
                           'conversation_participants_user_active_idx',
                           'conversation_participants_conversation_idx',
                           'chat_messages_conv_idx',
                           'chat_messages_sender_idx',
                           'chat_messages_redacted_idx'
                       )
                    """.trimIndent(),
                ).use { rs ->
                    val names = mutableSetOf<String>()
                    while (rs.next()) names.add(rs.getString(1))
                    names shouldContainAll
                        setOf(
                            "conv_slot_unique",
                            "conversation_participants_user_active_idx",
                            "conversation_participants_conversation_idx",
                            "chat_messages_conv_idx",
                            "chat_messages_sender_idx",
                            "chat_messages_redacted_idx",
                        )
                }
            }
        }
    }

    "conv_slot_unique is a partial unique index with WHERE left_at IS NULL" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT indexdef FROM pg_indexes WHERE indexname = 'conv_slot_unique'",
                ).use { rs ->
                    rs.next() shouldBe true
                    val def = rs.getString(1).lowercase()
                    def shouldContain "unique"
                    def shouldContain "where"
                    def shouldContain "left_at is null"
                }
            }
        }
    }

    "chat_messages_redacted_idx is partial on redacted_at IS NOT NULL" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT indexdef FROM pg_indexes WHERE indexname = 'chat_messages_redacted_idx'",
                ).use { rs ->
                    rs.next() shouldBe true
                    val def = rs.getString(1).lowercase()
                    def shouldContain "where"
                    def shouldContain "redacted_at is not null"
                }
            }
        }
    }

    "conversation_participants has a slot CHECK constraint" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT pg_get_constraintdef(c.oid)
                      FROM pg_constraint c
                      JOIN pg_class t ON t.oid = c.conrelid
                     WHERE t.relname = 'conversation_participants'
                       AND c.contype = 'c'
                    """.trimIndent(),
                ).use { rs ->
                    val defs = mutableListOf<String>()
                    while (rs.next()) defs.add(rs.getString(1).lowercase())
                    // At least one CHECK should mention slot ∈ {1, 2}.
                    defs.any { it.contains("slot") && it.contains("1") && it.contains("2") } shouldBe true
                }
            }
        }
    }

    "chat_messages has empty-message + redaction-atomicity CHECK constraints" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT pg_get_constraintdef(c.oid)
                      FROM pg_constraint c
                      JOIN pg_class t ON t.oid = c.conrelid
                     WHERE t.relname = 'chat_messages'
                       AND c.contype = 'c'
                    """.trimIndent(),
                ).use { rs ->
                    val defs = mutableListOf<String>()
                    while (rs.next()) defs.add(rs.getString(1).lowercase())
                    // Empty-message guard: content / embedded_post_id / embedded_post_snapshot.
                    defs.any {
                        it.contains("content") &&
                            it.contains("embedded_post_id") &&
                            it.contains("embedded_post_snapshot")
                    } shouldBe true
                    // Redaction atomicity: 3-column coupling on redacted_at + redacted_by + redaction_reason.
                    defs.any {
                        it.contains("redacted_at") && it.contains("redacted_by") && it.contains("redaction_reason")
                    } shouldBe true
                }
            }
        }
    }

    "deferred-FK columns redacted_by + embedded_post_edit_id have no referential constraint" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT a.attname
                      FROM pg_constraint c
                      JOIN pg_class t ON t.oid = c.conrelid
                      JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
                     WHERE t.relname = 'chat_messages'
                       AND c.contype = 'f'
                       AND a.attname IN ('redacted_by', 'embedded_post_edit_id')
                    """.trimIndent(),
                ).use { rs ->
                    val fkCols = mutableSetOf<String>()
                    while (rs.next()) fkCols.add(rs.getString(1))
                    // Neither column should appear in any FK constraint.
                    fkCols shouldBe emptySet()
                }
            }
        }
    }

    "deferred-FK columns carry COMMENT ON COLUMN markers" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT col_description(
                        ('public.chat_messages')::regclass,
                        (SELECT attnum FROM pg_attribute
                          WHERE attrelid = 'public.chat_messages'::regclass
                            AND attname = 'redacted_by')
                    )
                    """.trimIndent(),
                ).use { rs ->
                    rs.next() shouldBe true
                    val comment = rs.getString(1)
                    comment shouldNotBe null
                    comment shouldContain "admin_users"
                    comment.lowercase() shouldContain "deferred"
                }
            }
            conn.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT col_description(
                        ('public.chat_messages')::regclass,
                        (SELECT attnum FROM pg_attribute
                          WHERE attrelid = 'public.chat_messages'::regclass
                            AND attname = 'embedded_post_edit_id')
                    )
                    """.trimIndent(),
                ).use { rs ->
                    rs.next() shouldBe true
                    val comment = rs.getString(1)
                    comment shouldNotBe null
                    comment shouldContain "post_edits"
                    comment.lowercase() shouldContain "deferred"
                }
            }
        }
    }

    "embedded_post_id has FK to posts ON DELETE SET NULL" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT pg_get_constraintdef(c.oid)
                      FROM pg_constraint c
                      JOIN pg_class t ON t.oid = c.conrelid
                      JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
                     WHERE t.relname = 'chat_messages'
                       AND c.contype = 'f'
                       AND a.attname = 'embedded_post_id'
                    """.trimIndent(),
                ).use { rs ->
                    rs.next() shouldBe true
                    val def = rs.getString(1).lowercase()
                    def shouldContain "references posts"
                    def shouldContain "on delete set null"
                }
            }
        }
    }

    "created_by + sender_id FKs use ON DELETE RESTRICT" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT t.relname, a.attname, pg_get_constraintdef(c.oid)
                      FROM pg_constraint c
                      JOIN pg_class t ON t.oid = c.conrelid
                      JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
                     WHERE c.contype = 'f'
                       AND ((t.relname = 'conversations' AND a.attname = 'created_by')
                         OR (t.relname = 'chat_messages' AND a.attname = 'sender_id'))
                    """.trimIndent(),
                ).use { rs ->
                    val byKey = mutableMapOf<String, String>()
                    while (rs.next()) {
                        byKey["${rs.getString(1)}.${rs.getString(2)}"] = rs.getString(3).lowercase()
                    }
                    byKey["conversations.created_by"]!! shouldContain "on delete restrict"
                    byKey["chat_messages.sender_id"]!! shouldContain "on delete restrict"
                }
            }
        }
    }

    // RLS policy assertions. The realtime schema is only present on Supabase
    // (and the CI Supabase-mode service); on plain Postgres the V15 DO block
    // is a no-op and these assertions do not apply, so the test short-circuits.
    "participants_can_subscribe policy is installed when realtime schema exists" {
        DriverManager.getConnection(url, user, password).use { conn ->
            val realtimePresent =
                conn.createStatement().use { st ->
                    st.executeQuery(
                        "SELECT 1 FROM information_schema.schemata WHERE schema_name = 'realtime'",
                    ).use { rs -> rs.next() }
                }
            if (realtimePresent) {
                conn.createStatement().use { st ->
                    st.executeQuery(
                        """
                        SELECT polname FROM pg_policy
                         WHERE polrelid = 'realtime.messages'::regclass
                           AND polname = 'participants_can_subscribe'
                        """.trimIndent(),
                    ).use { rs ->
                        rs.next() shouldBe true
                        rs.getString(1) shouldBe "participants_can_subscribe"
                    }
                }
            }
        }
    }

    "participants_can_subscribe policy body does NOT carry the V2 is_shadow_banned clause" {
        DriverManager.getConnection(url, user, password).use { conn ->
            val realtimePresent =
                conn.createStatement().use { st ->
                    st.executeQuery(
                        "SELECT 1 FROM information_schema.schemata WHERE schema_name = 'realtime'",
                    ).use { rs -> rs.next() }
                }
            if (realtimePresent) {
                conn.createStatement().use { st ->
                    st.executeQuery(
                        """
                        SELECT pg_get_expr(polqual, polrelid)
                          FROM pg_policy
                         WHERE polrelid = 'realtime.messages'::regclass
                           AND polname = 'participants_can_subscribe'
                        """.trimIndent(),
                    ).use { rs ->
                        rs.next() shouldBe true
                        val body = rs.getString(1).lowercase()
                        body shouldContain "conversation_participants"
                        body shouldContain "left_at is null"
                        // The V2 subscriber-side clause is REMOVED in V15 per design.md § D9.
                        body shouldNotContain "is_shadow_banned"
                    }
                }
            }
        }
    }
})
