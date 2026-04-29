package id.nearyou.app.chat

import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Chat data-plane repository per `chat-foundation` design.
 *
 * **Lock layering (design.md § D1)**: [findOrCreate1to1] takes a per-user-pair advisory lock
 * (`hashtext(LEAST||':'||GREATEST)`) for the duration of the create-or-return transaction,
 * then a per-conversation advisory lock (`hashtext(:conversation_id)`) before the participant
 * INSERTs. Both `pg_advisory_xact_lock(...)` so they release on commit/rollback.
 *
 * **Block contract (design.md § D5 + D10)**: send + create enforce 403 via explicit
 * bidirectional `user_blocks` queries. List-conversations and list-messages do NOT exclude
 * conversations / messages by `user_blocks` — per `docs/02-Product.md:234`, "Existing
 * conversations remain visible in history" applies to BOTH list views. The query sites carry
 * the source-comment annotation `// @allow-no-block-exclusion: chat-history-readable-after-block`
 * so `BlockExclusionJoinRule` is suppressed (per design.md § D10 — the rule already recognizes
 * the marker; see lint/detekt-rules/src/main/kotlin/.../BlockExclusionJoinRule.kt).
 *
 * **Shadow-ban (design.md § D9)**: list-messages inlines the per-row carve-out filter
 * `(sender_id = :viewer OR NOT EXISTS shadow-ban-on-sender)` so a shadow-banned user always
 * sees their own messages while non-banned viewers never see a shadow-banned sender's. The
 * recipient-existence check in [findOrCreate1to1] reads RAW `users` (NOT `visible_users`) so
 * a 201/404 differential cannot leak shadow-ban state to a probing third party.
 *
 * **Redaction render (design.md § D6)**: when `redacted_at IS NOT NULL`, the API NULL-masks
 * `content` and OMITS `redaction_reason` from the body. Both transformations happen at the
 * route handler via [chatMessageJson] — the repository returns the raw row.
 */
class BlockedException : RuntimeException("blocked")

class SelfDmException : RuntimeException("self_dm")

class RecipientNotFoundException : RuntimeException("recipient_not_found")

class NotParticipantException : RuntimeException("not_participant")

class ConversationNotFoundException : RuntimeException("conversation_not_found")

data class ConversationRow(
    val id: UUID,
    val createdAt: Instant,
    val createdBy: UUID,
    val lastMessageAt: Instant?,
)

data class ParticipantRow(
    val conversationId: UUID,
    val userId: UUID,
    val slot: Int,
    val joinedAt: Instant,
    val leftAt: Instant?,
)

data class CreateConversationOutcome(
    val conversation: ConversationRow,
    val participants: List<ParticipantRow>,
    val wasCreated: Boolean,
)

data class ConversationListRow(
    val conversation: ConversationRow,
    val partnerId: UUID,
    val partnerUsername: String,
    val partnerDisplayName: String,
    val partnerIsPremium: Boolean,
)

data class ChatMessageRow(
    val id: UUID,
    val conversationId: UUID,
    val senderId: UUID,
    val content: String?,
    val createdAt: Instant,
    val redactedAt: Instant?,
)

class ChatRepository(
    private val dataSource: DataSource,
) {
    /**
     * Create-or-return a 1:1 conversation between [callerId] and [recipientId].
     *
     * Order of operations per spec scenarios + design.md § D1:
     *  1. Self-DM rejection — runs BEFORE any DB call (so the user-pair lock is never taken
     *     for a self-DM attempt; verifiable via `pg_locks` introspection per spec).
     *  2. Open transaction.
     *  3. Take per-user-pair advisory lock (canonical-ordered via LEAST/GREATEST).
     *  4. Recipient-existence check on RAW `users` (no shadow-ban oracle — see design.md § D9).
     *  5. Bidirectional block check — 403 via [BlockedException] if hit.
     *  6. Lookup existing 1:1 conversation between the canonical pair.
     *  7. If found: return with `wasCreated = false`.
     *  8. If not: INSERT conversation, take per-conversation advisory lock, INSERT both
     *     participants (slot 1 = caller, slot 2 = recipient), commit, return with
     *     `wasCreated = true`.
     */
    fun findOrCreate1to1(
        callerId: UUID,
        recipientId: UUID,
    ): CreateConversationOutcome {
        if (callerId == recipientId) throw SelfDmException()
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                acquireUserPairLock(conn, callerId, recipientId)

                if (!recipientExistsRaw(conn, recipientId)) throw RecipientNotFoundException()

                if (isBlockedBidirectional(conn, callerId, recipientId)) throw BlockedException()

                val existingId = findExisting1to1ConversationId(conn, callerId, recipientId)
                if (existingId != null) {
                    val conv = loadConversation(conn, existingId) ?: throw ConversationNotFoundException()
                    val parts = loadParticipants(conn, existingId)
                    conn.commit()
                    return CreateConversationOutcome(conv, parts, wasCreated = false)
                }

                val createdConv = insertConversation(conn, createdBy = callerId)
                acquireConversationLock(conn, createdConv.id)
                insertParticipant(conn, createdConv.id, callerId, slot = 1)
                insertParticipant(conn, createdConv.id, recipientId, slot = 2)
                val parts = loadParticipants(conn, createdConv.id)
                conn.commit()
                return CreateConversationOutcome(createdConv, parts, wasCreated = true)
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            } finally {
                conn.autoCommit = true
            }
        }
    }

    /**
     * List the caller's active conversations.
     *
     * Block-exclusion lint annotation (in function body below): this method's body
     * intentionally does NOT carry a bidirectional `user_blocks` NOT-IN. Per
     * `docs/02-Product.md:234`, "Existing conversations remain visible in history" applies
     * to the list view. The marker tells `BlockExclusionJoinRule` to skip its automatic
     * enforcement on `JOIN visible_users`.
     */
    fun listMyConversations(
        viewerId: UUID,
        cursor: ConversationsCursor?,
        limit: Int,
    ): List<ConversationListRow> {
        // @allow-no-block-exclusion: chat-history-readable-after-block
        val sql =
            buildString {
                append(
                    """
                    SELECT c.id AS conv_id,
                           c.created_at AS conv_created_at,
                           c.created_by AS conv_created_by,
                           c.last_message_at AS conv_last_message_at,
                           other.user_id AS partner_id,
                           COALESCE(u.username, 'akun_dihapus') AS partner_username,
                           COALESCE(u.display_name, 'Akun Dihapus') AS partner_display_name,
                           COALESCE(u.subscription_status IN ('premium_active', 'premium_billing_retry'), FALSE) AS partner_is_premium
                      FROM conversations c
                      JOIN conversation_participants me
                        ON me.conversation_id = c.id
                       AND me.user_id = ?
                       AND me.left_at IS NULL
                      JOIN conversation_participants other
                        ON other.conversation_id = c.id
                       AND other.user_id <> ?
                      LEFT JOIN visible_users u ON u.id = other.user_id
                    """.trimIndent(),
                )
                if (cursor != null) {
                    // NULLS-LAST keyset pagination. The list is ordered:
                    //   last_message_at DESC NULLS LAST, created_at DESC, id DESC
                    // Three branches:
                    //   (a) cursor.lastMessageAt non-null => still inside the "messages exist"
                    //       block. Continue inside that block, OR jump into the NULL tail.
                    //   (b) cursor.lastMessageAt null => already in the NULL tail block. Continue
                    //       past the cursor row using (created_at, id) DESC.
                    if (cursor.lastMessageAt != null) {
                        append(
                            "\n WHERE (c.last_message_at IS NOT NULL " +
                                "AND (c.last_message_at, c.created_at, c.id) < (?, ?, ?)) " +
                                "OR c.last_message_at IS NULL",
                        )
                    } else {
                        append(
                            "\n WHERE c.last_message_at IS NULL " +
                                "AND (c.created_at, c.id) < (?, ?)",
                        )
                    }
                }
                append("\n ORDER BY c.last_message_at DESC NULLS LAST, c.created_at DESC, c.id DESC")
                append("\n LIMIT ?")
            }
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var i = 1
                ps.setObject(i++, viewerId)
                ps.setObject(i++, viewerId)
                if (cursor != null) {
                    if (cursor.lastMessageAt != null) {
                        ps.setTimestamp(i++, Timestamp.from(cursor.lastMessageAt))
                        ps.setTimestamp(i++, Timestamp.from(cursor.createdAt))
                        ps.setObject(i++, cursor.id)
                    } else {
                        ps.setTimestamp(i++, Timestamp.from(cursor.createdAt))
                        ps.setObject(i++, cursor.id)
                    }
                }
                ps.setInt(i, limit)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<ConversationListRow>()
                    while (rs.next()) {
                        out +=
                            ConversationListRow(
                                conversation =
                                    ConversationRow(
                                        id = rs.getObject("conv_id", UUID::class.java),
                                        createdAt = rs.getTimestamp("conv_created_at").toInstant(),
                                        createdBy = rs.getObject("conv_created_by", UUID::class.java),
                                        lastMessageAt = rs.getTimestamp("conv_last_message_at")?.toInstant(),
                                    ),
                                partnerId = rs.getObject("partner_id", UUID::class.java),
                                partnerUsername = rs.getString("partner_username"),
                                partnerDisplayName = rs.getString("partner_display_name"),
                                partnerIsPremium = rs.getBoolean("partner_is_premium"),
                            )
                    }
                    return out
                }
            }
        }
    }

    /**
     * List messages in [conversationId] for [viewerId].
     *
     * Block-exclusion lint annotation: list-history is readable post-block per
     * `docs/02-Product.md:234`. The participant-lookup query and the messages query both
     * read raw protected tables (`conversation_participants` joins `users` indirectly via
     * the shadow-ban filter; `chat_messages` is a Repository own-content path explicitly
     * allowed in `CLAUDE.md` § Critical invariants for the chat capability). The marker
     * above tells `BlockExclusionJoinRule` to skip enforcement on this function's queries.
     *
     * Throws:
     *  - [ConversationNotFoundException] if [conversationId] does not exist in `conversations`.
     *  - [NotParticipantException] if the viewer is not an active participant.
     */
    fun listMessages(
        conversationId: UUID,
        viewerId: UUID,
        cursor: MessagesCursor?,
        limit: Int,
    ): List<ChatMessageRow> {
        // @allow-no-block-exclusion: chat-history-readable-after-block
        dataSource.connection.use { conn ->
            // Existence check FIRST (404 vs 403 differential): if the conversation row is
            // absent we throw ConversationNotFoundException; if it exists but the viewer
            // isn't an active participant we throw NotParticipantException (-> 403).
            if (!conversationExists(conn, conversationId)) throw ConversationNotFoundException()
            if (!isActiveParticipant(conn, conversationId, viewerId)) throw NotParticipantException()

            val sql =
                buildString {
                    append(
                        """
                        SELECT cm.id,
                               cm.conversation_id,
                               cm.sender_id,
                               cm.content,
                               cm.created_at,
                               cm.redacted_at
                          FROM chat_messages cm
                         WHERE cm.conversation_id = ?
                           AND (
                                cm.sender_id = ?
                             OR NOT EXISTS (
                                    SELECT 1 FROM users u
                                     WHERE u.id = cm.sender_id
                                       AND u.is_shadow_banned = TRUE
                                )
                           )
                        """.trimIndent(),
                    )
                    if (cursor != null) {
                        append("\n   AND (cm.created_at, cm.id) < (?, ?)")
                    }
                    append("\n ORDER BY cm.created_at DESC, cm.id DESC")
                    append("\n LIMIT ?")
                }
            conn.prepareStatement(sql).use { ps ->
                var i = 1
                ps.setObject(i++, conversationId)
                ps.setObject(i++, viewerId)
                if (cursor != null) {
                    ps.setTimestamp(i++, Timestamp.from(cursor.createdAt))
                    ps.setObject(i++, cursor.id)
                }
                ps.setInt(i, limit)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<ChatMessageRow>()
                    while (rs.next()) out += rs.toChatMessageRow()
                    return out
                }
            }
        }
    }

    /**
     * Insert a chat message. Enforces the canonical bidirectional block check at
     * `docs/05-Implementation.md:1304-1308` via an EXPLICIT `user_blocks` query against
     * the OTHER active participant — throws [BlockedException] on hit. Active-participant
     * check is enforced by the participant-lookup. INSERT + UPDATE last_message_at run
     * in ONE transaction (design.md § D3).
     *
     * Block-exclusion lint annotation (above): the participant-lookup queries against
     * `conversation_participants` (joined to `users` through the explicit block check below)
     * deliberately do NOT carry an automatic NOT-IN — the canonical 403 contract is enforced
     * by the explicit query, NOT by the auto-applied join.
     */
    fun sendMessage(
        conversationId: UUID,
        senderId: UUID,
        content: String,
    ): ChatMessageRow {
        // @allow-no-block-exclusion: chat-history-readable-after-block
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                if (!conversationExists(conn, conversationId)) throw ConversationNotFoundException()
                if (!isActiveParticipant(conn, conversationId, senderId)) throw NotParticipantException()
                if (isBlockedAgainstOtherParticipant(conn, conversationId, senderId)) {
                    throw BlockedException()
                }
                val row = insertChatMessage(conn, conversationId, senderId, content)
                bumpLastMessageAt(conn, conversationId)
                conn.commit()
                return row
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            } finally {
                conn.autoCommit = true
            }
        }
    }

    // ---- internal helpers --------------------------------------------------

    private fun acquireUserPairLock(
        conn: Connection,
        a: UUID,
        b: UUID,
    ) {
        // Canonical-ordered pair: LEAST(a, b) || ':' || GREATEST(a, b).
        conn.prepareStatement(
            "SELECT pg_advisory_xact_lock(hashtext(LEAST(?::text, ?::text) || ':' || GREATEST(?::text, ?::text)))",
        ).use { ps ->
            ps.setObject(1, a)
            ps.setObject(2, b)
            ps.setObject(3, a)
            ps.setObject(4, b)
            ps.executeQuery().use { it.next() }
        }
    }

    private fun acquireConversationLock(
        conn: Connection,
        conversationId: UUID,
    ) {
        conn.prepareStatement(
            "SELECT pg_advisory_xact_lock(hashtext(?::text))",
        ).use { ps ->
            ps.setObject(1, conversationId)
            ps.executeQuery().use { it.next() }
        }
    }

    /**
     * Recipient-existence check via RAW `users` (NOT `visible_users`) per design.md § D9 —
     * a shadow-banned recipient is a valid creation target; the 201/404 split must NOT leak
     * shadow-ban state. The in-body marker suppresses `BlockExclusionJoinRule` here because
     * the FROM-`users` query is bound to a known recipient UUID; there's no "viewer-author"
     * relationship to apply bidirectional NOT-IN against, and applying one would actually
     * create the shadow-ban oracle this design deliberately avoids.
     */
    private fun recipientExistsRaw(
        conn: Connection,
        recipientId: UUID,
    ): Boolean {
        // @allow-no-block-exclusion: chat-history-readable-after-block
        conn.prepareStatement("SELECT 1 FROM users WHERE id = ?").use { ps ->
            ps.setObject(1, recipientId)
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }

    /**
     * Bidirectional block lookup: returns true if EITHER (caller blocks recipient)
     * OR (recipient blocks caller). Mirrors the canonical query at
     * `docs/05-Implementation.md:1304-1308` shape, here scoped to a known user pair (not
     * "the other participant of a conversation"). Carries the four lint-required tokens
     * (`user_blocks`, `blocker_id =`, `blocked_id =`) so the rule passes naturally; this
     * is the explicit block check the chat 403 contract relies on.
     */
    private fun isBlockedBidirectional(
        conn: Connection,
        a: UUID,
        b: UUID,
    ): Boolean {
        conn.prepareStatement(
            """
            SELECT 1 FROM user_blocks
             WHERE (blocker_id = ? AND blocked_id = ?)
                OR (blocker_id = ? AND blocked_id = ?)
             LIMIT 1
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, a)
            ps.setObject(2, b)
            ps.setObject(3, b)
            ps.setObject(4, a)
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }

    /**
     * Block check at send time per `docs/05-Implementation.md:1304-1308` — bidirectional
     * `user_blocks` lookup against the OTHER active participant of the conversation.
     */
    private fun isBlockedAgainstOtherParticipant(
        conn: Connection,
        conversationId: UUID,
        senderId: UUID,
    ): Boolean {
        conn.prepareStatement(
            """
            SELECT 1 FROM user_blocks ub
             WHERE (ub.blocker_id = ? AND ub.blocked_id IN (
                        SELECT cp.user_id FROM conversation_participants cp
                         WHERE cp.conversation_id = ? AND cp.user_id <> ?
                    ))
                OR (ub.blocked_id = ? AND ub.blocker_id IN (
                        SELECT cp.user_id FROM conversation_participants cp
                         WHERE cp.conversation_id = ? AND cp.user_id <> ?
                    ))
             LIMIT 1
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, senderId)
            ps.setObject(2, conversationId)
            ps.setObject(3, senderId)
            ps.setObject(4, senderId)
            ps.setObject(5, conversationId)
            ps.setObject(6, senderId)
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }

    private fun findExisting1to1ConversationId(
        conn: Connection,
        callerId: UUID,
        recipientId: UUID,
    ): UUID? {
        // Find the conversation where both users are active participants (left_at IS NULL).
        conn.prepareStatement(
            """
            SELECT cpa.conversation_id
              FROM conversation_participants cpa
              JOIN conversation_participants cpb
                ON cpa.conversation_id = cpb.conversation_id
             WHERE cpa.user_id = ?
               AND cpa.left_at IS NULL
               AND cpb.user_id = ?
               AND cpb.left_at IS NULL
             LIMIT 1
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, callerId)
            ps.setObject(2, recipientId)
            ps.executeQuery().use { rs ->
                return if (rs.next()) rs.getObject(1, UUID::class.java) else null
            }
        }
    }

    private fun insertConversation(
        conn: Connection,
        createdBy: UUID,
    ): ConversationRow {
        conn.prepareStatement(
            """
            INSERT INTO conversations (created_by)
            VALUES (?)
            RETURNING id, created_at, created_by, last_message_at
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, createdBy)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "INSERT ... RETURNING produced no row" }
                return ConversationRow(
                    id = rs.getObject("id", UUID::class.java),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    createdBy = rs.getObject("created_by", UUID::class.java),
                    lastMessageAt = rs.getTimestamp("last_message_at")?.toInstant(),
                )
            }
        }
    }

    private fun insertParticipant(
        conn: Connection,
        conversationId: UUID,
        userId: UUID,
        slot: Int,
    ) {
        conn.prepareStatement(
            "INSERT INTO conversation_participants (conversation_id, user_id, slot) VALUES (?, ?, ?)",
        ).use { ps ->
            ps.setObject(1, conversationId)
            ps.setObject(2, userId)
            ps.setInt(3, slot)
            ps.executeUpdate()
        }
    }

    private fun loadConversation(
        conn: Connection,
        conversationId: UUID,
    ): ConversationRow? {
        conn.prepareStatement(
            "SELECT id, created_at, created_by, last_message_at FROM conversations WHERE id = ?",
        ).use { ps ->
            ps.setObject(1, conversationId)
            ps.executeQuery().use { rs ->
                return if (rs.next()) {
                    ConversationRow(
                        id = rs.getObject("id", UUID::class.java),
                        createdAt = rs.getTimestamp("created_at").toInstant(),
                        createdBy = rs.getObject("created_by", UUID::class.java),
                        lastMessageAt = rs.getTimestamp("last_message_at")?.toInstant(),
                    )
                } else {
                    null
                }
            }
        }
    }

    private fun loadParticipants(
        conn: Connection,
        conversationId: UUID,
    ): List<ParticipantRow> {
        conn.prepareStatement(
            """
            SELECT conversation_id, user_id, slot, joined_at, left_at
              FROM conversation_participants
             WHERE conversation_id = ?
             ORDER BY slot ASC
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, conversationId)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<ParticipantRow>()
                while (rs.next()) {
                    out +=
                        ParticipantRow(
                            conversationId = rs.getObject("conversation_id", UUID::class.java),
                            userId = rs.getObject("user_id", UUID::class.java),
                            slot = rs.getInt("slot"),
                            joinedAt = rs.getTimestamp("joined_at").toInstant(),
                            leftAt = rs.getTimestamp("left_at")?.toInstant(),
                        )
                }
                return out
            }
        }
    }

    private fun conversationExists(
        conn: Connection,
        conversationId: UUID,
    ): Boolean {
        conn.prepareStatement("SELECT 1 FROM conversations WHERE id = ?").use { ps ->
            ps.setObject(1, conversationId)
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }

    private fun isActiveParticipant(
        conn: Connection,
        conversationId: UUID,
        userId: UUID,
    ): Boolean {
        conn.prepareStatement(
            """
            SELECT 1 FROM conversation_participants
             WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL
             LIMIT 1
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, conversationId)
            ps.setObject(2, userId)
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }

    private fun insertChatMessage(
        conn: Connection,
        conversationId: UUID,
        senderId: UUID,
        content: String,
    ): ChatMessageRow {
        conn.prepareStatement(
            """
            INSERT INTO chat_messages (conversation_id, sender_id, content)
            VALUES (?, ?, ?)
            RETURNING id, conversation_id, sender_id, content, created_at, redacted_at
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, conversationId)
            ps.setObject(2, senderId)
            ps.setString(3, content)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "INSERT ... RETURNING produced no row" }
                return rs.toChatMessageRow()
            }
        }
    }

    private fun bumpLastMessageAt(
        conn: Connection,
        conversationId: UUID,
    ) {
        conn.prepareStatement(
            "UPDATE conversations SET last_message_at = NOW() WHERE id = ?",
        ).use { ps ->
            ps.setObject(1, conversationId)
            ps.executeUpdate()
        }
    }

    private fun ResultSet.toChatMessageRow(): ChatMessageRow =
        ChatMessageRow(
            id = getObject("id", UUID::class.java),
            conversationId = getObject("conversation_id", UUID::class.java),
            senderId = getObject("sender_id", UUID::class.java),
            content = getString("content"),
            createdAt = getTimestamp("created_at").toInstant(),
            redactedAt = getTimestamp("redacted_at")?.toInstant(),
        )
}
