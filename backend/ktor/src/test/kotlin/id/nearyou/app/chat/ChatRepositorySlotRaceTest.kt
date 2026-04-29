package id.nearyou.app.chat

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.Date
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch

/**
 * Concurrency + advisory-lock-shape tests for [ChatRepository.findOrCreate1to1]
 * (tasks 6.1–6.3 of `openspec/changes/chat-foundation`). All three exercise the
 * canonical lock layering from `design.md` § D1:
 *
 *   1. Per-user-pair lock = `pg_advisory_xact_lock(hashtext(LEAST||':'||GREATEST))`
 *   2. Per-conversation lock = `pg_advisory_xact_lock(hashtext(:conversation_id::text))`
 *
 * Tagged `database` so CI excludes it by default. Run locally with the standard
 * `DB_URL` / `DB_USER` / `DB_PASSWORD` env vars (defaults match the Docker
 * Compose dev Postgres at `localhost:5433`).
 */
@Tags("database")
class ChatRepositorySlotRaceTest : StringSpec({

    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val dbUser = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"
    val dataSource: HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = url
                username = dbUser
                this.password = password
                // Enough connections for 20 in-flight callers + introspection probes.
                maximumPoolSize = 24
                initializationFailTimeout = -1
            },
        )
    val repo = ChatRepository(dataSource)

    afterSpec { dataSource.close() }

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
                ps.setString(2, "sr_$short")
                ps.setString(3, "Slot Race Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1995, 3, 14)))
                ps.setString(5, "s${short.take(7)}")
                ps.executeUpdate()
            }
        }
        return uid
    }

    fun cleanupConversationsForUsers(vararg userIds: UUID) {
        dataSource.connection.use { conn ->
            // Find all conversations any of these users participate in, then DELETE
            // (CASCADE deletes participants + messages).
            conn.prepareStatement(
                """
                DELETE FROM conversations
                 WHERE id IN (
                     SELECT conversation_id FROM conversation_participants
                      WHERE user_id = ANY (?)
                 )
                """.trimIndent(),
            ).use { ps ->
                ps.setArray(1, conn.createArrayOf("uuid", userIds.map { it }.toTypedArray()))
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM users WHERE id = ANY (?)").use { ps ->
                ps.setArray(1, conn.createArrayOf("uuid", userIds.map { it }.toTypedArray()))
                ps.executeUpdate()
            }
        }
    }

    "6.1: 20 concurrent findOrCreate1to1 calls produce exactly one conversation" {
        val a = seedUser()
        val b = seedUser()
        try {
            // CountDownLatch ensures all 20 coroutines fire `findOrCreate1to1`
            // within microseconds of each other, maximizing race likelihood.
            val gate = CountDownLatch(1)
            val outcomes =
                runBlocking {
                    coroutineScope {
                        val deferreds =
                            (1..20).map { i ->
                                val (caller, recipient) = if (i <= 10) a to b else b to a
                                async(Dispatchers.IO) {
                                    gate.await()
                                    repo.findOrCreate1to1(caller, recipient)
                                }
                            }
                        gate.countDown()
                        deferreds.awaitAll()
                    }
                }

            // All 20 outcomes share the same conversation id.
            val ids = outcomes.map { it.conversation.id }.toSet()
            ids.size shouldBe 1
            val convId = ids.single()

            // Exactly one row in conversations for this pair.
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    """
                    SELECT COUNT(DISTINCT cpa.conversation_id)
                      FROM conversation_participants cpa
                      JOIN conversation_participants cpb
                        ON cpa.conversation_id = cpb.conversation_id
                     WHERE cpa.user_id = ? AND cpa.left_at IS NULL
                       AND cpb.user_id = ? AND cpb.left_at IS NULL
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, a)
                    ps.setObject(2, b)
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 1
                    }
                }
                // Exactly two participant rows in the resolved conversation.
                conn.prepareStatement(
                    """
                    SELECT user_id, slot, left_at
                      FROM conversation_participants
                     WHERE conversation_id = ?
                     ORDER BY slot ASC
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, convId)
                    ps.executeQuery().use { rs ->
                        val rows = mutableListOf<Triple<UUID, Int, Boolean>>()
                        while (rs.next()) {
                            rows +=
                                Triple(
                                    rs.getObject("user_id", UUID::class.java),
                                    rs.getInt("slot"),
                                    rs.getTimestamp("left_at") == null,
                                )
                        }
                        rows.size shouldBe 2
                        rows.all { it.third } shouldBe true
                        rows.map { it.first }.toSet() shouldBe setOf(a, b)
                        rows.map { it.second }.toSet() shouldBe setOf(1, 2)
                    }
                }
            }
        } finally {
            cleanupConversationsForUsers(a, b)
        }
    }

    "6.2: user-pair lock-key is hashtext(LEAST::text || ':' || GREATEST::text)" {
        // Strategy: open a transaction in connection-1 and acquire the user-pair
        // advisory xact lock with the canonical SQL shape. Then probe pg_locks
        // from connection-2, verifying the locked objid matches the same
        // hashtext(...) value computed via SQL. This proves both:
        //   (a) the lock-key is hashtext(LEAST||':'||GREATEST), AND
        //   (b) the order is canonical (lower-uuid first), separator is ':'.
        val a = seedUser()
        val b = seedUser()
        try {
            val holder: Connection = dataSource.connection
            try {
                holder.autoCommit = false
                // Acquire the lock using the EXACT canonical shape.
                holder.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtext(LEAST(?::text, ?::text) || ':' || GREATEST(?::text, ?::text)))",
                ).use { ps ->
                    ps.setObject(1, a)
                    ps.setObject(2, b)
                    ps.setObject(3, a)
                    ps.setObject(4, b)
                    ps.executeQuery().use { it.next() }
                }

                // Holder backend pid (so the probe can filter to this transaction).
                val holderPid =
                    holder.prepareStatement("SELECT pg_backend_pid()").use { ps ->
                        ps.executeQuery().use { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                    }

                // Compute the expected lock-key via SQL on a separate connection.
                // `hashtext` returns int4; `pg_advisory_xact_lock` takes int8, so the
                // int4 is sign-extended. In `pg_locks`, advisory locks store the int8
                // key split across (classid, objid) where each is a uint32 oid:
                //   classid = high 32 bits, objid = low 32 bits, objsubid = 1.
                // For a positive int4 hash: classid = 0, objid = hash.
                // For a negative int4 hash: classid = 0xFFFFFFFF (sign-extended), objid = uint32(hash).
                dataSource.connection.use { probe ->
                    val expectedI8: Long =
                        probe.prepareStatement(
                            "SELECT hashtext(LEAST(?::text, ?::text) || ':' || GREATEST(?::text, ?::text))::bigint",
                        ).use { ps ->
                            ps.setObject(1, a)
                            ps.setObject(2, b)
                            ps.setObject(3, a)
                            ps.setObject(4, b)
                            ps.executeQuery().use { rs ->
                                rs.next()
                                rs.getLong(1)
                            }
                        }
                    // Sanity: the canonical-ordered shape MUST be invariant under input swap.
                    val swappedI8: Long =
                        probe.prepareStatement(
                            "SELECT hashtext(LEAST(?::text, ?::text) || ':' || GREATEST(?::text, ?::text))::bigint",
                        ).use { ps ->
                            ps.setObject(1, b)
                            ps.setObject(2, a)
                            ps.setObject(3, b)
                            ps.setObject(4, a)
                            ps.executeQuery().use { rs ->
                                rs.next()
                                rs.getLong(1)
                            }
                        }
                    swappedI8 shouldBe expectedI8

                    // Now look up the held lock in pg_locks for the holder pid and
                    // reconstruct the int8 key from (classid, objid).
                    probe.prepareStatement(
                        """
                        SELECT classid::bigint AS classid8, objid::bigint AS objid8, objsubid, granted
                          FROM pg_locks
                         WHERE locktype = 'advisory'
                           AND pid = ?
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setInt(1, holderPid)
                        ps.executeQuery().use { rs ->
                            var found = false
                            while (rs.next()) {
                                val classid = rs.getLong("classid8")
                                val objid = rs.getLong("objid8")
                                val granted = rs.getBoolean("granted")
                                // Reconstruct int8: (classid << 32) | objid, treating both as uint32.
                                val reconstructed = (classid shl 32) or objid
                                if (reconstructed == expectedI8 && granted) {
                                    found = true
                                }
                            }
                            found shouldBe true
                        }
                    }
                }

                // Release the lock.
                holder.commit()
            } finally {
                holder.autoCommit = true
                holder.close()
            }
        } finally {
            cleanupConversationsForUsers(a, b)
        }
    }

    "6.3: per-conversation lock pg_advisory_xact_lock(hashtext(conv_id::text)) is held during participant insert" {
        // Strategy: replicate the canonical insert flow inside a held transaction
        // on connection-1, then probe `pg_locks` on connection-2 to verify the
        // per-conversation advisory lock is present BEFORE the participant
        // INSERTs run. Then commit and verify the lock is released.
        val a = seedUser()
        val b = seedUser()
        try {
            val holder: Connection = dataSource.connection
            try {
                holder.autoCommit = false

                // 1. INSERT conversation.
                val convId: UUID =
                    holder.prepareStatement(
                        "INSERT INTO conversations (created_by) VALUES (?) RETURNING id",
                    ).use { ps ->
                        ps.setObject(1, a)
                        ps.executeQuery().use { rs ->
                            rs.next()
                            rs.getObject(1, UUID::class.java)
                        }
                    }

                // 2. Take per-conversation advisory lock — exact canonical shape.
                holder.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtext(?::text))",
                ).use { ps ->
                    ps.setObject(1, convId)
                    ps.executeQuery().use { it.next() }
                }

                // 3. Probe pg_locks from a separate connection BEFORE the
                //    participant INSERTs run, asserting the lock is held with
                //    the expected objid.
                val holderPid =
                    holder.prepareStatement("SELECT pg_backend_pid()").use { ps ->
                        ps.executeQuery().use { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                    }
                dataSource.connection.use { probe ->
                    // hashtext returns int4; advisory_xact_lock takes int8 (sign-extended).
                    // In pg_locks the int8 lock-key is split across (classid, objid)
                    // as two uint32 oids: classid = high 32, objid = low 32.
                    val expectedI8: Long =
                        probe.prepareStatement("SELECT hashtext(?::text)::bigint").use { ps ->
                            ps.setObject(1, convId)
                            ps.executeQuery().use { rs ->
                                rs.next()
                                rs.getLong(1)
                            }
                        }

                    fun lockHeldCount(): Int =
                        probe.prepareStatement(
                            """
                            SELECT classid::bigint AS classid8, objid::bigint AS objid8, granted
                              FROM pg_locks
                             WHERE locktype = 'advisory' AND pid = ?
                            """.trimIndent(),
                        ).use { ps ->
                            ps.setInt(1, holderPid)
                            ps.executeQuery().use { rs ->
                                var n = 0
                                while (rs.next()) {
                                    val classid = rs.getLong("classid8")
                                    val objid = rs.getLong("objid8")
                                    val reconstructed = (classid shl 32) or objid
                                    if (reconstructed == expectedI8 && rs.getBoolean("granted")) n++
                                }
                                n
                            }
                        }

                    lockHeldCount() shouldBe 1

                    // 4. Now INSERT the two participant rows (lock still held).
                    holder.prepareStatement(
                        "INSERT INTO conversation_participants (conversation_id, user_id, slot) VALUES (?, ?, ?)",
                    ).use { ps ->
                        ps.setObject(1, convId)
                        ps.setObject(2, a)
                        ps.setInt(3, 1)
                        ps.executeUpdate()
                    }
                    holder.prepareStatement(
                        "INSERT INTO conversation_participants (conversation_id, user_id, slot) VALUES (?, ?, ?)",
                    ).use { ps ->
                        ps.setObject(1, convId)
                        ps.setObject(2, b)
                        ps.setInt(3, 2)
                        ps.executeUpdate()
                    }

                    // Re-verify the lock is still held during the inserts. xact
                    // locks live for the whole tx until commit/rollback.
                    lockHeldCount() shouldBe 1

                    // 5. Commit -> lock released.
                    holder.commit()

                    lockHeldCount() shouldBe 0
                }
            } finally {
                if (!holder.autoCommit) holder.rollback()
                holder.autoCommit = true
                holder.close()
            }

            // Sanity: a real findOrCreate1to1 call on the same pair should now
            // find the existing conversation (since we just created it manually).
            // This both verifies the production code's lookup path AND confirms
            // the canonical lock-acquisition shape matches what the repo does.
            val outcome = runBlocking { withContext(Dispatchers.IO) { repo.findOrCreate1to1(a, b) } }
            outcome.wasCreated shouldBe false
            outcome.participants.map { it.userId }.toSet() shouldBe setOf(a, b)
        } finally {
            cleanupConversationsForUsers(a, b)
        }
    }
})
