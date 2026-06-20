package id.nearyou.app.account

import id.nearyou.app.core.domain.lint.AllowActualLocationRead
import id.nearyou.app.core.domain.lint.AllowMissingBlockJoin
import id.nearyou.app.core.domain.lint.AllowRawPostsRead
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

// ---------------------------------------------------------------------------
// Typed per-category rows — mirror the docs/06 § Data Export Scope Matrix
// Format column. Singleton/state categories (profile, DOB, hashed auth id,
// analytics consent) ride [DataExportProfile]; the tabular categories are lists.
// Peer ids are ALREADY HMAC-hashed at the gather (never raw) for follow / block /
// chat / report counterparties.
// ---------------------------------------------------------------------------

/** Singleton "state" categories → serialized as JSON. NEVER carries `is_shadow_banned`. */
data class DataExportProfile(
    val userId: UUID,
    val username: String,
    val displayName: String,
    val bio: String?,
    val dateOfBirth: String,
    val googleIdHash: String?,
    val appleIdHash: String?,
    /** Raw `analytics_consent` JSONB text (current state — the schema keeps no history). */
    val analyticsConsentJson: String?,
    val createdAt: Instant,
)

/** `username_history` (own) — CSV. */
data class UsernameHistoryExportRow(
    val oldUsername: String,
    val newUsername: String,
    val changedAt: Instant,
)

/** A post the user authored — CSV. Carries the user's OWN `actual_location` (own-data). */
data class PostExportRow(
    val id: UUID,
    val content: String,
    val actualLat: Double,
    val actualLng: Double,
    val cityName: String?,
    val createdAt: Instant,
    val deletedAt: Instant?,
)

/** `post_edits` for the user's own posts — CSV. */
data class PostEditExportRow(
    val postId: UUID,
    val contentSnapshot: String,
    val editedAt: Instant,
)

/** Likes the user gave — CSV. */
data class LikeExportRow(
    val postId: UUID,
    val createdAt: Instant,
)

/** Replies the user gave — CSV. */
data class ReplyExportRow(
    val id: UUID,
    val parentPostId: UUID,
    val content: String,
    val createdAt: Instant,
    val deletedAt: Instant?,
)

/** Follow edge (either direction) — CSV. Peer id is HMAC-hashed. */
data class FollowExportRow(
    /** "following" (the requester follows the peer) or "follower" (the peer follows the requester). */
    val direction: String,
    val peerIdHash: String,
    val createdAt: Instant,
)

/** Block the user made — CSV. Peer id is HMAC-hashed. */
data class BlockExportRow(
    val blockedIdHash: String,
    val createdAt: Instant,
)

/** A chat message the user sent OR received — CSV. Peer id is HMAC-hashed. */
data class ChatMessageExportRow(
    val conversationId: UUID,
    val peerIdHash: String,
    /** "sent" (the requester is the sender) or "received". */
    val direction: String,
    val content: String?,
    val createdAt: Instant,
)

/** A report the user submitted — CSV. Target id is HMAC-hashed. */
data class ReportExportRow(
    val targetType: String,
    val targetIdHash: String,
    val reasonCategory: String,
    val reasonNote: String?,
    val createdAt: Instant,
)

/** A notification addressed to the user — CSV. */
data class NotificationExportRow(
    val type: String,
    val targetType: String?,
    val targetId: String?,
    val createdAt: Instant,
    val readAt: Instant?,
)

/** A moderation action APPLIED to the user — CSV (admin_id omitted; shadow-ban entries excluded). */
data class ModerationActionExportRow(
    val actionType: String,
    val createdAt: Instant,
)

/** Session history — CSV. 90-day window. (IP is not stored in MVP; only the fingerprint hash.) */
data class SessionExportRow(
    val deviceFingerprintHash: String?,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
)

/** Premium subscription event — CSV. (No `tier` column in the schema; `eventType` is the closest.) */
data class SubscriptionExportRow(
    val eventType: String,
    val source: String,
    val entitlementStart: Instant?,
    val entitlementEnd: Instant?,
    val createdAt: Instant,
)

/** The full own-data bundle the [DataExportArchiveService] serializes. */
data class DataExportBundle(
    val profile: DataExportProfile,
    val usernameHistory: List<UsernameHistoryExportRow>,
    val posts: List<PostExportRow>,
    val postEdits: List<PostEditExportRow>,
    val likes: List<LikeExportRow>,
    val replies: List<ReplyExportRow>,
    val follows: List<FollowExportRow>,
    val blocks: List<BlockExportRow>,
    val chatMessages: List<ChatMessageExportRow>,
    val reports: List<ReportExportRow>,
    val notifications: List<NotificationExportRow>,
    val moderationActions: List<ModerationActionExportRow>,
    val sessions: List<SessionExportRow>,
    val subscriptions: List<SubscriptionExportRow>,
)

/**
 * Own-data-only gather for the canonical docs/06 § Data Export Scope Matrix (design D5).
 *
 * Every query is keyed strictly on the requester's `user_id`, so no other user's data can
 * enter the bundle. Peer references that leave the export (follow / block / chat / report
 * counterparties) are hashed via [PeerIdHasher] (keyed `HMAC-SHA256`) at the gather — a raw
 * peer id is never carried in a returned row.
 *
 * These are deliberately own-content RAW reads — they intentionally read the user's real data,
 * including the user's OWN `actual_location` (NOT the HMAC-fuzzed `display_location`) and the
 * user's own block list. The detekt-enforced invariants are bypassed with the sanctioned
 * own-content annotations on the class:
 *  - [AllowRawPostsRead] — the posts/replies own-content `FROM posts|post_replies` reads,
 *  - [AllowMissingBlockJoin] — own-content has no viewer-block exclusion (it is the requester's
 *    own data; the `FROM users|posts|chat_messages|post_replies` reads carry no `user_blocks` join),
 *  - [AllowActualLocationRead] — the posts own-`actual_location` read (the exact token
 *    `CoordinateJitterRule` requires to exempt a non-admin `actual_location` read; the other two
 *    alone would still fail the coordinate-jitter detekt lane).
 *
 * EXCLUDED per the matrix + the shadow-ban stealth invariant: reports-received, attestation,
 * admin-audit, CSAM, `rejected_identifiers`, the `users.is_shadow_banned` column itself, and any
 * shadow-ban moderation entry (the `moderation_queue_resolved` rows whose
 * `after_state->>'resolution' = 'shadow_ban_author'` are filtered out of moderation actions).
 */
@AllowRawPostsRead(
    "data export own-content — the requester's own posts/replies incl. auto-hidden + " +
        "soft-deleted-in-grace, which visible_posts would wrongly exclude",
)
@AllowMissingBlockJoin(
    "data export own-content — every read is keyed on the requester's own user_id; no " +
        "viewer-block exclusion applies to one's own data",
)
@AllowActualLocationRead("data export own-content")
class DataExportGatherRepository(
    private val dataSource: DataSource,
    private val peerIdHasher: PeerIdHasher,
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Gathers the full own-data bundle for [userId]. One connection, a fixed set of bounded queries. */
    suspend fun gather(userId: UUID): DataExportBundle =
        withContext(dbDispatcher) {
            dataSource.connection.use { conn ->
                DataExportBundle(
                    profile = gatherProfile(conn, userId),
                    usernameHistory = gatherUsernameHistory(conn, userId),
                    posts = gatherPosts(conn, userId),
                    postEdits = gatherPostEdits(conn, userId),
                    likes = gatherLikes(conn, userId),
                    replies = gatherReplies(conn, userId),
                    follows = gatherFollows(conn, userId),
                    blocks = gatherBlocks(conn, userId),
                    chatMessages = gatherChatMessages(conn, userId),
                    reports = gatherReports(conn, userId),
                    notifications = gatherNotifications(conn, userId),
                    moderationActions = gatherModerationActions(conn, userId),
                    sessions = gatherSessions(conn, userId),
                    subscriptions = gatherSubscriptions(conn, userId),
                )
            }
        }

    // is_shadow_banned is deliberately NOT selected (shadow-ban stealth invariant).
    private fun gatherProfile(
        conn: Connection,
        userId: UUID,
    ): DataExportProfile =
        conn.prepareStatement(SQL_PROFILE).use { ps ->
            ps.setObject(1, userId)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "no users row for export subject" }
                DataExportProfile(
                    userId = userId,
                    username = rs.getString("username"),
                    displayName = rs.getString("display_name"),
                    bio = rs.getString("bio"),
                    dateOfBirth = rs.getString("date_of_birth"),
                    googleIdHash = rs.getString("google_id_hash"),
                    appleIdHash = rs.getString("apple_id_hash"),
                    analyticsConsentJson = rs.getString("analytics_consent"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                )
            }
        }

    private fun gatherUsernameHistory(
        conn: Connection,
        userId: UUID,
    ): List<UsernameHistoryExportRow> =
        conn.queryList(SQL_USERNAME_HISTORY, userId) { rs ->
            UsernameHistoryExportRow(
                oldUsername = rs.getString("old_username"),
                newUsername = rs.getString("new_username"),
                changedAt = rs.getTimestamp("changed_at").toInstant(),
            )
        }

    private fun gatherPosts(
        conn: Connection,
        userId: UUID,
    ): List<PostExportRow> =
        conn.queryList(SQL_POSTS, userId) { rs ->
            PostExportRow(
                id = UUID.fromString(rs.getString("id")),
                content = rs.getString("content"),
                actualLat = rs.getDouble("lat"),
                actualLng = rs.getDouble("lng"),
                cityName = rs.getString("city_name"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                deletedAt = rs.getTimestamp("deleted_at")?.toInstant(),
            )
        }

    private fun gatherPostEdits(
        conn: Connection,
        userId: UUID,
    ): List<PostEditExportRow> =
        conn.queryList(SQL_POST_EDITS, userId) { rs ->
            PostEditExportRow(
                postId = UUID.fromString(rs.getString("post_id")),
                contentSnapshot = rs.getString("content_snapshot"),
                editedAt = rs.getTimestamp("edited_at").toInstant(),
            )
        }

    private fun gatherLikes(
        conn: Connection,
        userId: UUID,
    ): List<LikeExportRow> =
        conn.queryList(SQL_LIKES, userId) { rs ->
            LikeExportRow(
                postId = UUID.fromString(rs.getString("post_id")),
                createdAt = rs.getTimestamp("created_at").toInstant(),
            )
        }

    private fun gatherReplies(
        conn: Connection,
        userId: UUID,
    ): List<ReplyExportRow> =
        conn.queryList(SQL_REPLIES, userId) { rs ->
            ReplyExportRow(
                id = UUID.fromString(rs.getString("id")),
                parentPostId = UUID.fromString(rs.getString("post_id")),
                content = rs.getString("content"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                deletedAt = rs.getTimestamp("deleted_at")?.toInstant(),
            )
        }

    private fun gatherFollows(
        conn: Connection,
        userId: UUID,
    ): List<FollowExportRow> =
        conn.prepareStatement(SQL_FOLLOWS).use { ps ->
            ps.setObject(1, userId)
            ps.setObject(2, userId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val followerId = UUID.fromString(rs.getString("follower_id"))
                        val followeeId = UUID.fromString(rs.getString("followee_id"))
                        val (direction, peerId) =
                            if (followerId == userId) {
                                "following" to followeeId
                            } else {
                                "follower" to followerId
                            }
                        add(
                            FollowExportRow(
                                direction = direction,
                                peerIdHash = peerIdHasher.hash(peerId),
                                createdAt = rs.getTimestamp("created_at").toInstant(),
                            ),
                        )
                    }
                }
            }
        }

    private fun gatherBlocks(
        conn: Connection,
        userId: UUID,
    ): List<BlockExportRow> =
        conn.queryList(SQL_BLOCKS, userId) { rs ->
            BlockExportRow(
                blockedIdHash = peerIdHasher.hash(UUID.fromString(rs.getString("blocked_id"))),
                createdAt = rs.getTimestamp("created_at").toInstant(),
            )
        }

    private fun gatherChatMessages(
        conn: Connection,
        userId: UUID,
    ): List<ChatMessageExportRow> =
        conn.prepareStatement(SQL_CHAT).use { ps ->
            // 1 = conversation membership (the INNER JOIN scopes to the user's conversations);
            // 2 = peer resolution (the OTHER participant). Direction is derived from sender_id.
            ps.setObject(1, userId)
            ps.setObject(2, userId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val peerId = rs.getString("peer_id")?.let { UUID.fromString(it) }
                        add(
                            ChatMessageExportRow(
                                conversationId = UUID.fromString(rs.getString("conversation_id")),
                                // `""` only for a degenerate single-participant conversation (the
                                // LEFT JOIN found no active peer) — unreachable in the 1:1 product.
                                peerIdHash = peerId?.let { peerIdHasher.hash(it) } ?: "",
                                direction = if (rs.getString("sender_id") == userId.toString()) "sent" else "received",
                                content = rs.getString("content"),
                                createdAt = rs.getTimestamp("created_at").toInstant(),
                            ),
                        )
                    }
                }
            }
        }

    private fun gatherReports(
        conn: Connection,
        userId: UUID,
    ): List<ReportExportRow> =
        conn.queryList(SQL_REPORTS, userId) { rs ->
            ReportExportRow(
                targetType = rs.getString("target_type"),
                targetIdHash = peerIdHasher.hash(UUID.fromString(rs.getString("target_id"))),
                reasonCategory = rs.getString("reason_category"),
                reasonNote = rs.getString("reason_note"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
            )
        }

    private fun gatherNotifications(
        conn: Connection,
        userId: UUID,
    ): List<NotificationExportRow> =
        conn.queryList(SQL_NOTIFICATIONS, userId) { rs ->
            NotificationExportRow(
                type = rs.getString("type"),
                targetType = rs.getString("target_type"),
                targetId = rs.getString("target_id"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                readAt = rs.getTimestamp("read_at")?.toInstant(),
            )
        }

    // Moderation actions APPLIED to the user (admin_actions_log, target_type='user').
    // Shadow-ban stealth: the load-bearing exclusion is the target_type='user' predicate —
    // a shadow-ban is logged under target_type='moderation_queue', never 'user', so it is
    // already out of scope here. The after_state->>'resolution' <> 'shadow_ban_author' and
    // action_type NOT ILIKE '%shadow_ban%' clauses are belt-and-suspenders. admin_id is NOT selected.
    private fun gatherModerationActions(
        conn: Connection,
        userId: UUID,
    ): List<ModerationActionExportRow> =
        conn.prepareStatement(SQL_MODERATION_ACTIONS).use { ps ->
            ps.setString(1, userId.toString()) // admin_actions_log.target_id is TEXT
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            ModerationActionExportRow(
                                actionType = rs.getString("action_type"),
                                createdAt = rs.getTimestamp("created_at").toInstant(),
                            ),
                        )
                    }
                }
            }
        }

    private fun gatherSessions(
        conn: Connection,
        userId: UUID,
    ): List<SessionExportRow> =
        conn.queryList(SQL_SESSIONS, userId) { rs ->
            SessionExportRow(
                deviceFingerprintHash = rs.getString("device_fingerprint_hash"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                lastUsedAt = rs.getTimestamp("last_used_at")?.toInstant(),
            )
        }

    private fun gatherSubscriptions(
        conn: Connection,
        userId: UUID,
    ): List<SubscriptionExportRow> =
        conn.queryList(SQL_SUBSCRIPTIONS, userId) { rs ->
            SubscriptionExportRow(
                eventType = rs.getString("event_type"),
                source = rs.getString("source"),
                entitlementStart = rs.getTimestamp("entitlement_start")?.toInstant(),
                entitlementEnd = rs.getTimestamp("entitlement_end")?.toInstant(),
                createdAt = rs.getTimestamp("created_at").toInstant(),
            )
        }

    /**
     * The requester's own email (for the delivery email — NOT serialized into the archive).
     * Own-row read keyed on `id`; `null` when absent (e.g. an Apple relay address was nulled).
     * Runs on the dispatcher; the worker calls it after the export is already `ready`.
     */
    suspend fun emailForUser(userId: UUID): String? =
        withContext(dbDispatcher) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(SQL_EMAIL).use { ps ->
                    ps.setObject(1, userId)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rs.getString("email") else null
                    }
                }
            }
        }

    /** Single-`user_id`-bound list query helper. */
    private fun <T> Connection.queryList(
        sql: String,
        userId: UUID,
        map: (java.sql.ResultSet) -> T,
    ): List<T> =
        prepareStatement(sql).use { ps ->
            ps.setObject(1, userId)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(map(rs)) }
            }
        }

    private companion object {
        const val SQL_EMAIL = "SELECT email FROM users WHERE id = ?"

        const val SQL_PROFILE =
            """
            SELECT username, display_name, bio, date_of_birth,
                   google_id_hash, apple_id_hash, analytics_consent, created_at
              FROM users
             WHERE id = ?
            """

        const val SQL_USERNAME_HISTORY =
            """
            SELECT old_username, new_username, changed_at
              FROM username_history
             WHERE user_id = ?
             ORDER BY changed_at ASC
            """

        // Own posts incl. auto-hidden + soft-deleted-in-grace; own actual_location (lat/lng).
        const val SQL_POSTS =
            """
            SELECT id, content,
                   ST_Y(actual_location::geometry) AS lat,
                   ST_X(actual_location::geometry) AS lng,
                   city_name, created_at, deleted_at
              FROM posts
             WHERE author_id = ?
             ORDER BY created_at ASC
            """

        // Edits on the user's own posts (chronological, all versions).
        const val SQL_POST_EDITS =
            """
            SELECT pe.post_id, pe.content_snapshot, pe.edited_at
              FROM post_edits pe
              JOIN posts p ON p.id = pe.post_id
             WHERE p.author_id = ?
             ORDER BY pe.edited_at ASC
            """

        const val SQL_LIKES =
            """
            SELECT post_id, created_at
              FROM post_likes
             WHERE user_id = ?
             ORDER BY created_at ASC
            """

        const val SQL_REPLIES =
            """
            SELECT id, post_id, content, created_at, deleted_at
              FROM post_replies
             WHERE author_id = ?
             ORDER BY created_at ASC
            """

        const val SQL_FOLLOWS =
            """
            SELECT follower_id, followee_id, created_at
              FROM follows
             WHERE follower_id = ? OR followee_id = ?
             ORDER BY created_at ASC
            """

        const val SQL_BLOCKS =
            """
            SELECT blocked_id, created_at
              FROM user_blocks
             WHERE blocker_id = ?
             ORDER BY created_at ASC
            """

        // Sent AND received: every message in a conversation the user participates in
        // (the INNER JOIN on the requester's membership is the scope). peer_id = the
        // OTHER participant (slot-agnostic); direction is derived from sender_id.
        const val SQL_CHAT =
            """
            SELECT cm.conversation_id,
                   cm.sender_id,
                   cm.content,
                   cm.created_at,
                   peer.user_id AS peer_id
              FROM chat_messages cm
              JOIN conversation_participants me
                ON me.conversation_id = cm.conversation_id AND me.user_id = ?
              LEFT JOIN conversation_participants peer
                ON peer.conversation_id = cm.conversation_id
               AND peer.user_id <> ?
               AND peer.left_at IS NULL
             ORDER BY cm.created_at ASC
            """

        const val SQL_REPORTS =
            """
            SELECT target_type, target_id, reason_category, reason_note, created_at
              FROM reports
             WHERE reporter_id = ?
             ORDER BY created_at ASC
            """

        const val SQL_NOTIFICATIONS =
            """
            SELECT type, target_type, target_id, created_at, read_at
              FROM notifications
             WHERE user_id = ?
             ORDER BY created_at ASC
            """

        // Applied-to-user actions; shadow-ban entries filtered for stealth.
        const val SQL_MODERATION_ACTIONS =
            """
            SELECT action_type, created_at
              FROM admin_actions_log
             WHERE target_type = 'user'
               AND target_id = ?
               AND COALESCE(after_state ->> 'resolution', '') <> 'shadow_ban_author'
               AND action_type NOT ILIKE '%shadow_ban%'
             ORDER BY created_at ASC
            """

        // Session history — 90-day window (NOW() in WHERE is fine here: not a partial index).
        const val SQL_SESSIONS =
            """
            SELECT device_fingerprint_hash, created_at, last_used_at
              FROM refresh_tokens
             WHERE user_id = ?
               AND created_at >= NOW() - INTERVAL '90 days'
             ORDER BY created_at ASC
            """

        const val SQL_SUBSCRIPTIONS =
            """
            SELECT event_type, source, entitlement_start, entitlement_end, created_at
              FROM subscription_events
             WHERE user_id = ?
             ORDER BY created_at ASC
            """
    }
}
