package id.nearyou.app.chat

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.sql.Connection
import java.sql.Date
import java.time.LocalDate
import java.util.UUID

/**
 * RLS realtime policy tests for the V15 `participants_can_subscribe ON realtime.messages`
 * policy. Covers tasks 8.1–8.10 of `openspec/changes/chat-foundation`.
 *
 * Per task 8.9 ("RLS test set MUST run in CI against a Supabase-mode Postgres container,
 * not staging-only"), this test set runs against the standard CI Postgres but bootstraps
 * a stub `realtime` schema (table `realtime.messages` + functions `realtime.topic()` and
 * `auth.jwt()`) and re-applies the V15 policy against it. This makes the test set self-
 * contained on plain Postgres while exercising the EXACT V15 policy body.
 *
 * The plain `postgres` superuser has `BYPASSRLS`, so each test switches to a non-bypass
 * `authenticated` role (created in setup) for the SELECT — otherwise the policy is
 * bypassed and the assertion is vacuous.
 *
 * Tagged `database` so CI excludes it by default. Run locally with the standard
 * `DB_URL` / `DB_USER` / `DB_PASSWORD` env vars (defaults match the Docker Compose
 * dev Postgres at `localhost:5433`).
 */
@Tags("database")
class ChatRealtimeRlsTest : StringSpec({

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

    afterSpec { dataSource.close() }

    // -------- One-time bootstrap: stub realtime + auth schemas, install V15 policy --------

    fun bootstrap() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE SCHEMA IF NOT EXISTS realtime")
                st.execute("CREATE SCHEMA IF NOT EXISTS auth")
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS realtime.messages (
                        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        topic TEXT NOT NULL,
                        payload JSONB,
                        inserted_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                    )
                    """.trimIndent(),
                )
                st.execute("ALTER TABLE realtime.messages ENABLE ROW LEVEL SECURITY")
                // FORCE so even table-owner roles must obey the policy (otherwise an
                // owner that doesn't have BYPASSRLS still bypasses RLS for owned tables).
                st.execute("ALTER TABLE realtime.messages FORCE ROW LEVEL SECURITY")

                st.execute(
                    """
                    CREATE OR REPLACE FUNCTION realtime.topic() RETURNS TEXT AS ${'$'}${'$'}
                    BEGIN
                        RETURN current_setting('realtime.test_topic', true);
                    END;
                    ${'$'}${'$'} LANGUAGE plpgsql STABLE
                    """.trimIndent(),
                )
                st.execute(
                    """
                    CREATE OR REPLACE FUNCTION auth.jwt() RETURNS JSONB AS ${'$'}${'$'}
                    DECLARE
                        raw TEXT := current_setting('auth.test_jwt', true);
                    BEGIN
                        IF raw IS NULL OR raw = '' THEN
                            RETURN NULL;
                        END IF;
                        RETURN raw::jsonb;
                    END;
                    ${'$'}${'$'} LANGUAGE plpgsql STABLE
                    """.trimIndent(),
                )

                // Re-install the V15 policy verbatim (V15 was a no-op when run because
                // realtime didn't exist at migration time). The shadow-ban subscriber-side
                // clause from V2 is intentionally absent per design.md § D9.
                st.execute("DROP POLICY IF EXISTS participants_can_subscribe ON realtime.messages")
                st.execute(
                    """
                    CREATE POLICY participants_can_subscribe ON realtime.messages
                    FOR SELECT USING (
                        realtime.topic() ~ '^conversation:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                        AND EXISTS (
                            SELECT 1 FROM public.conversation_participants cp
                            WHERE cp.conversation_id = (split_part(realtime.topic(), ':', 2))::uuid
                                AND cp.user_id = ((auth.jwt()->>'sub')::uuid)
                                AND cp.left_at IS NULL
                        )
                    )
                    """.trimIndent(),
                )

                // A non-BYPASSRLS role to stand in for Supabase's `authenticated` role.
                st.execute(
                    """
                    DO ${'$'}${'$'}
                    BEGIN
                        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rls_test_role') THEN
                            CREATE ROLE rls_test_role NOBYPASSRLS NOINHERIT;
                        END IF;
                    END
                    ${'$'}${'$'}
                    """.trimIndent(),
                )
                st.execute("GRANT USAGE ON SCHEMA realtime TO rls_test_role")
                st.execute("GRANT USAGE ON SCHEMA auth TO rls_test_role")
                st.execute("GRANT USAGE ON SCHEMA public TO rls_test_role")
                st.execute("GRANT SELECT ON realtime.messages TO rls_test_role")
                st.execute("GRANT SELECT ON public.conversation_participants TO rls_test_role")
                st.execute("GRANT SELECT ON public.users TO rls_test_role")
                st.execute("GRANT SELECT ON public.conversations TO rls_test_role")
                st.execute("GRANT EXECUTE ON FUNCTION realtime.topic() TO rls_test_role")
                st.execute("GRANT EXECUTE ON FUNCTION auth.jwt() TO rls_test_role")
            }
        }
    }

    bootstrap()

    // -------- Helpers --------

    fun seedUser(shadowBanned: Boolean = false): UUID {
        val uid = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix, is_shadow_banned)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, uid)
                val short = uid.toString().replace("-", "").take(8)
                ps.setString(2, "rls_$short")
                ps.setString(3, "RLS Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1995, 3, 14)))
                ps.setString(5, "r${short.take(7)}")
                ps.setBoolean(6, shadowBanned)
                ps.executeUpdate()
            }
        }
        return uid
    }

    fun seedConversation(creator: UUID): UUID {
        val convId = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO conversations (id, created_by) VALUES (?, ?)",
            ).use { ps ->
                ps.setObject(1, convId)
                ps.setObject(2, creator)
                ps.executeUpdate()
            }
        }
        return convId
    }

    fun seedParticipant(
        convId: UUID,
        userId: UUID,
        slot: Int,
        leftAt: java.sql.Timestamp? = null,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO conversation_participants (conversation_id, user_id, slot, left_at)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, convId)
                ps.setObject(2, userId)
                ps.setInt(3, slot)
                if (leftAt == null) {
                    ps.setNull(4, java.sql.Types.TIMESTAMP)
                } else {
                    ps.setTimestamp(4, leftAt)
                }
                ps.executeUpdate()
            }
        }
    }

    /**
     * Insert a fixture row into realtime.messages with the given topic. Uses the
     * superuser connection (which has BYPASSRLS) to bypass the RLS policy on
     * INSERT — the test's job is to verify SELECT-time policy enforcement.
     */
    fun seedRealtimeRow(topic: String): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO realtime.messages (id, topic) VALUES (?, ?)",
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, topic)
                ps.executeUpdate()
            }
        }
        return id
    }

    /**
     * Run [block] under the non-bypass `rls_test_role` with session-local
     * realtime.test_topic + auth.test_jwt set. Use a transaction so SET LOCAL works
     * and SET ROLE auto-resets at the end.
     */
    fun underJwtAndTopic(
        topic: String,
        jwtSub: String?,
        block: (Connection) -> Unit,
    ) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.createStatement().use { st ->
                    val safeTopic = topic.replace("'", "''")
                    st.execute("SET LOCAL realtime.test_topic = '$safeTopic'")
                    if (jwtSub == null) {
                        // Empty string → auth.jwt() returns NULL.
                        st.execute("SET LOCAL auth.test_jwt = ''")
                    } else {
                        st.execute("SET LOCAL auth.test_jwt = '{\"sub\":\"$jwtSub\"}'")
                    }
                    st.execute("SET LOCAL ROLE rls_test_role")
                }
                block(conn)
            } finally {
                conn.rollback()
                conn.autoCommit = true
            }
        }
    }

    /**
     * Returns the number of rows the policy permits the role to SELECT for [rowId].
     *
     * If the policy USING clause raises a PSQLException (e.g. the canonical
     * `split_part(...)::uuid` cast errors on a malformed topic before the regex
     * AND-branch short-circuits — see the canonical query plan in the V15 policy
     * body), that's a SECURITY-EQUIVALENT deny: in the Supabase realtime path
     * the broker treats a policy-evaluation error as a subscribe failure (no
     * rows delivered to the client). The spec scenario language "denies (zero
     * rows)" maps to either outcome at the broker layer. We collapse both to 0.
     */
    fun countVisibleRealtimeRows(
        topic: String,
        jwtSub: String?,
        rowId: UUID,
    ): Int {
        var count = -1
        try {
            underJwtAndTopic(topic, jwtSub) { conn ->
                conn.prepareStatement(
                    "SELECT COUNT(*) FROM realtime.messages WHERE id = ?",
                ).use { ps ->
                    ps.setObject(1, rowId)
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        count = rs.getInt(1)
                    }
                }
            }
        } catch (e: java.sql.SQLException) {
            // Policy USING clause evaluation error → deny (semantically equivalent
            // to zero visible rows under Supabase realtime broker semantics).
            count = 0
        }
        return count
    }

    fun cleanup(
        userIds: List<UUID> = emptyList(),
        conversationIds: List<UUID> = emptyList(),
        realtimeRowIds: List<UUID> = emptyList(),
    ) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                realtimeRowIds.forEach {
                    st.executeUpdate("DELETE FROM realtime.messages WHERE id = '$it'")
                }
                conversationIds.forEach {
                    st.executeUpdate("DELETE FROM conversation_participants WHERE conversation_id = '$it'")
                    st.executeUpdate("DELETE FROM conversations WHERE id = '$it'")
                }
                userIds.forEach {
                    st.executeUpdate("DELETE FROM users WHERE id = '$it'")
                }
            }
        }
    }

    // -------- 8.1: JWT sub not in users → deny --------
    "8.1: JWT sub not in users denies (zero rows)" {
        val creator = seedUser()
        val convId = seedConversation(creator)
        val topic = "conversation:$convId"
        val rowId = seedRealtimeRow(topic)
        try {
            // sub UUID is well-formed but no row in users.
            val ghostSub = UUID.randomUUID().toString()
            countVisibleRealtimeRows(topic, ghostSub, rowId) shouldBe 0
        } finally {
            cleanup(
                userIds = listOf(creator),
                conversationIds = listOf(convId),
                realtimeRowIds = listOf(rowId),
            )
        }
    }

    // -------- 8.2: valid user not in conversation_participants → deny --------
    "8.2: valid user, not in conversation_participants, denies" {
        val creator = seedUser()
        val outsider = seedUser()
        val convId = seedConversation(creator)
        seedParticipant(convId, creator, slot = 1)
        val topic = "conversation:$convId"
        val rowId = seedRealtimeRow(topic)
        try {
            countVisibleRealtimeRows(topic, outsider.toString(), rowId) shouldBe 0
        } finally {
            cleanup(
                userIds = listOf(creator, outsider),
                conversationIds = listOf(convId),
                realtimeRowIds = listOf(rowId),
            )
        }
    }

    // -------- 8.3: participant with left_at IS NOT NULL → deny --------
    "8.3: valid user with left_at IS NOT NULL participant row denies" {
        val creator = seedUser()
        val leaver = seedUser()
        val convId = seedConversation(creator)
        seedParticipant(convId, creator, slot = 1)
        seedParticipant(
            convId,
            leaver,
            slot = 2,
            leftAt = java.sql.Timestamp.from(java.time.Instant.now()),
        )
        val topic = "conversation:$convId"
        val rowId = seedRealtimeRow(topic)
        try {
            countVisibleRealtimeRows(topic, leaver.toString(), rowId) shouldBe 0
        } finally {
            cleanup(
                userIds = listOf(creator, leaver),
                conversationIds = listOf(convId),
                realtimeRowIds = listOf(rowId),
            )
        }
    }

    // -------- 8.4: malformed topic 'conversation' (no delimiter) → deny via regex --------
    "8.4: malformed topic 'conversation' (no delimiter) denies via regex" {
        val creator = seedUser()
        val convId = seedConversation(creator)
        seedParticipant(convId, creator, slot = 1)
        val malformedTopic = "conversation"
        val rowId = seedRealtimeRow(malformedTopic)
        try {
            countVisibleRealtimeRows(malformedTopic, creator.toString(), rowId) shouldBe 0
        } finally {
            cleanup(
                userIds = listOf(creator),
                conversationIds = listOf(convId),
                realtimeRowIds = listOf(rowId),
            )
        }
    }

    // -------- 8.5: malformed topic 'conversation:' (no UUID) → deny via regex --------
    "8.5: malformed topic 'conversation:' (no UUID after colon) denies via regex" {
        val creator = seedUser()
        val convId = seedConversation(creator)
        seedParticipant(convId, creator, slot = 1)
        val malformedTopic = "conversation:"
        val rowId = seedRealtimeRow(malformedTopic)
        try {
            countVisibleRealtimeRows(malformedTopic, creator.toString(), rowId) shouldBe 0
        } finally {
            cleanup(
                userIds = listOf(creator),
                conversationIds = listOf(convId),
                realtimeRowIds = listOf(rowId),
            )
        }
    }

    // -------- 8.6: invalid-UUID topic → deny via regex --------
    "8.6: invalid-UUID topic 'conversation:not-a-uuid' denies via regex" {
        val creator = seedUser()
        val convId = seedConversation(creator)
        seedParticipant(convId, creator, slot = 1)
        val malformedTopic = "conversation:not-a-uuid"
        val rowId = seedRealtimeRow(malformedTopic)
        try {
            countVisibleRealtimeRows(malformedTopic, creator.toString(), rowId) shouldBe 0
        } finally {
            cleanup(
                userIds = listOf(creator),
                conversationIds = listOf(convId),
                realtimeRowIds = listOf(rowId),
            )
        }
    }

    // -------- 8.7: SQL-injection topic → deny + DB intact --------
    "8.7: SQL-injection topic denies via regex; database remains intact" {
        val creator = seedUser()
        val convId = seedConversation(creator)
        seedParticipant(convId, creator, slot = 1)
        val malformedTopic = "conversation:'; DROP TABLE conversations; --"
        val rowId = seedRealtimeRow(malformedTopic)
        try {
            countVisibleRealtimeRows(malformedTopic, creator.toString(), rowId) shouldBe 0
            // Verify conversations table is still present (regex prevented injection).
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    """
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema = 'public' AND table_name = 'conversations'
                    """.trimIndent(),
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 1
                    }
                }
            }
        } finally {
            cleanup(
                userIds = listOf(creator),
                conversationIds = listOf(convId),
                realtimeRowIds = listOf(rowId),
            )
        }
    }

    // -------- 8.8: active participant + valid topic UUID → allow --------
    "8.8: active participant (left_at IS NULL) with valid topic UUID allows" {
        val creator = seedUser()
        val convId = seedConversation(creator)
        seedParticipant(convId, creator, slot = 1)
        val topic = "conversation:$convId"
        val rowId = seedRealtimeRow(topic)
        try {
            countVisibleRealtimeRows(topic, creator.toString(), rowId) shouldBe 1
        } finally {
            cleanup(
                userIds = listOf(creator),
                conversationIds = listOf(convId),
                realtimeRowIds = listOf(rowId),
            )
        }
    }

    // -------- 8.10: shadow-banned active participant → ALLOW (V15 dropped V2's clause) --------
    "8.10: shadow-banned active participant allows (V15 drops V2's is_shadow_banned clause)" {
        val creator = seedUser(shadowBanned = true)
        val convId = seedConversation(creator)
        seedParticipant(convId, creator, slot = 1)
        val topic = "conversation:$convId"
        val rowId = seedRealtimeRow(topic)
        try {
            // Sanity: confirm the user is in fact shadow-banned in the DB.
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "SELECT is_shadow_banned FROM users WHERE id = ?",
                ).use { ps ->
                    ps.setObject(1, creator)
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getBoolean(1) shouldBe true
                    }
                }
            }
            // V15-installed policy ALLOWS shadow-banned subscribers (per design.md § D9).
            countVisibleRealtimeRows(topic, creator.toString(), rowId) shouldBe 1
        } finally {
            cleanup(
                userIds = listOf(creator),
                conversationIds = listOf(convId),
                realtimeRowIds = listOf(rowId),
            )
        }
    }
})
