package id.nearyou.app.account

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.infra.r2.ObjectStore
import id.nearyou.app.infra.r2.ObjectStoreUnconfiguredException
import id.nearyou.app.infra.repo.JdbcNotificationRepository
import id.nearyou.app.infra.resend.EmailSender
import id.nearyou.app.infra.resend.EmailTemplate
import id.nearyou.app.infra.resend.SendResult
import id.nearyou.app.notifications.DbNotificationEmitter
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.sql.Date
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import ch.qos.logback.classic.Logger as LogbackLogger
import kotlin.time.Duration as KtDuration

private fun hikari(): HikariDataSource {
    val config =
        HikariConfig().apply {
            jdbcUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
            username = System.getenv("DB_USER") ?: "postgres"
            password = System.getenv("DB_PASSWORD") ?: "postgres"
            // 4 conns: the concurrency scenario runs two worker invocations in parallel.
            maximumPoolSize = 4
            initializationFailTimeout = -1
        }
    return HikariDataSource(config)
}

/** Records puts; presigns a deterministic URL. Optionally throws on put (fail-soft case a). */
private class CapturingObjectStore(
    private val throwOnPut: Boolean = false,
    private val configured: Boolean = true,
) : ObjectStore {
    val puts = ConcurrentHashMap<String, ByteArray>()
    val putCount = AtomicInteger(0)

    override fun isConfigured(): Boolean = configured

    override suspend fun put(
        key: String,
        bytes: ByteArray,
        contentType: String,
    ) {
        if (throwOnPut) {
            if (configured) throw RuntimeException("simulated upload failure") else throw ObjectStoreUnconfiguredException()
        }
        putCount.incrementAndGet()
        puts[key] = bytes
    }

    override suspend fun presignedGetUrl(
        key: String,
        ttl: KtDuration,
    ): String = "https://fake.r2.example/$key?sig=test"

    override suspend fun delete(key: String) = Unit
}

/** Records sends; optionally fails (fail-soft case b). */
private class RecordingEmailSender(
    private val outcome: SendResult = SendResult.Sent("msg-test"),
) : EmailSender {
    data class Sent(val to: String, val template: EmailTemplate, val idempotencyKey: String)

    val sends = mutableListOf<Sent>()

    override fun isConfigured(): Boolean = outcome !is SendResult.Skipped

    override suspend fun send(
        to: String,
        template: EmailTemplate,
        idempotencyKey: String,
    ): SendResult {
        sends += Sent(to, template, idempotencyKey)
        return outcome
    }
}

/**
 * DB-tagged worker tests (capability `account-data-export`, Phase 7). Uses a FAKE
 * [ObjectStore] (deterministic URL, captured bytes) + FAKE [EmailSender] (records sends) so
 * NO real R2/Resend is hit; the notification + status writes go to the real DB.
 *
 *  - 8.5 / 8.9 happy path: pending → ready + r2_object_key + download_expires_at ≈ now+24h +
 *    a `data_export_ready` notification row (body_data {signed_url, expires_at}, NULL target) +
 *    exactly one email sent.
 *  - 8.6 concurrency: two parallel invocations over one pending request → exactly one processes.
 *  - 8.7 fail-soft (a) ObjectStore throws → status=failed + attempt_count≥1, no ready/notification/email.
 *  - 8.7 fail-soft (b) upload OK but EmailSender fails → stays READY (notification emitted), NOT reverted.
 *  - 8.8 scope matrix: unzip the captured archive — canonical Included categories present; chat
 *    sent+received with the peer HASHED (raw peer id/username absent, HMAC present); posts carry
 *    own actual_location; out-of-scope categories absent; shadow-ban stealth; no other user's data.
 */
@Tags("database")
class DataExportWorkerTest : StringSpec({

    val dataSource = hikari()
    val gatherRepo = DataExportGatherRepository(dataSource, PeerIdHasher.fromSecret(null))
    val archiveService = DataExportArchiveService()
    val requestRepo = DataExportRequestRepository(dataSource)
    val notificationEmitter = DbNotificationEmitter(JdbcNotificationRepository(dataSource))

    val seededUsers = mutableListOf<UUID>()
    val seededAdmins = mutableListOf<UUID>()
    val seededConversations = mutableListOf<UUID>()

    fun worker(
        objectStore: ObjectStore,
        emailSender: EmailSender,
    ): DataExportWorker =
        DataExportWorker(
            dataSource = dataSource,
            requests = requestRepo,
            gather = gatherRepo,
            archiveService = archiveService,
            objectStore = objectStore,
            emailSender = emailSender,
            notificationEmitter = notificationEmitter,
        )

    fun exec(
        sql: String,
        vararg args: Any?,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                args.forEachIndexed { i, a ->
                    if (a == null) ps.setNull(i + 1, java.sql.Types.OTHER) else ps.setObject(i + 1, a)
                }
                ps.executeUpdate()
            }
        }
    }

    /** A bare user with an email (for the delivery-email path). */
    fun seedUser(
        emailAddr: String? = "export@example.com",
        shadowBanned: Boolean = false,
    ): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (id, username, display_name, bio, email, date_of_birth, invite_code_prefix, is_shadow_banned)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "dxw_$short")
                ps.setString(3, "Worker Export Test")
                ps.setString(4, "my bio")
                if (emailAddr != null) ps.setString(5, emailAddr) else ps.setNull(5, java.sql.Types.VARCHAR)
                ps.setDate(6, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(7, "w${short.take(7)}")
                ps.setBoolean(8, shadowBanned)
                ps.executeUpdate()
            }
        }
        seededUsers += id
        return id
    }

    fun seedAdmin(): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO admin_users (id, email, display_name, password_hash, role) VALUES (?, ?, ?, ?, 'moderator')",
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "admin_$short@example.com")
                ps.setString(3, "Admin $short")
                ps.setString(4, "argon2-hash")
                ps.executeUpdate()
            }
        }
        seededAdmins += id
        return id
    }

    fun seedPendingRequest(userId: UUID): UUID {
        val id = UUID.randomUUID()
        exec("INSERT INTO data_export_requests (id, user_id, status) VALUES (?, ?, 'pending')", id, userId)
        return id
    }

    /** Deep-ocean post (no kabupaten covers -10.5/105.0) so it can't pollute geo-timeline suites. */
    fun seedPost(
        authorId: UUID,
        content: String,
        deleted: Boolean = false,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO posts (id, author_id, content, city_name, display_location, actual_location, deleted_at)
                VALUES (?, ?, ?, 'Deep Ocean',
                  ST_SetSRID(ST_MakePoint(105.0, -10.5), 4326)::geography,
                  ST_SetSRID(ST_MakePoint(105.0, -10.5), 4326)::geography,
                  ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, authorId)
                ps.setString(3, content)
                if (deleted) ps.setTimestamp(4, Timestamp.from(Instant.now())) else ps.setNull(4, java.sql.Types.TIMESTAMP)
                ps.executeUpdate()
            }
        }
        return id
    }

    fun seedConversationWith(
        owner: UUID,
        peer: UUID,
    ): UUID {
        val convId = UUID.randomUUID()
        exec("INSERT INTO conversations (id, created_by) VALUES (?, ?)", convId, owner)
        exec("INSERT INTO conversation_participants (conversation_id, user_id, slot) VALUES (?, ?, 1)", convId, owner)
        exec("INSERT INTO conversation_participants (conversation_id, user_id, slot) VALUES (?, ?, 2)", convId, peer)
        seededConversations += convId
        return convId
    }

    fun seedMessage(
        convId: UUID,
        senderId: UUID,
        content: String,
    ) {
        exec(
            "INSERT INTO chat_messages (id, conversation_id, sender_id, content) VALUES (?, ?, ?, ?)",
            UUID.randomUUID(),
            convId,
            senderId,
            content,
        )
    }

    fun cleanup() {
        dataSource.connection.use { conn ->
            seededConversations.forEach { cid ->
                conn.prepareStatement("DELETE FROM chat_messages WHERE conversation_id = ?").use {
                    it.setObject(1, cid)
                    it.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM conversation_participants WHERE conversation_id = ?").use {
                    it.setObject(1, cid)
                    it.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM conversations WHERE id = ?").use {
                    it.setObject(1, cid)
                    it.executeUpdate()
                }
            }
            seededUsers.forEach { id ->
                listOf(
                    "DELETE FROM data_export_requests WHERE user_id = ?",
                    "DELETE FROM notifications WHERE user_id = ?",
                    "DELETE FROM username_history WHERE user_id = ?",
                    "DELETE FROM post_likes WHERE user_id = ?",
                    "DELETE FROM post_replies WHERE author_id = ?",
                    "DELETE FROM reports WHERE reporter_id = ?",
                    "DELETE FROM subscription_events WHERE user_id = ?",
                    "DELETE FROM refresh_tokens WHERE user_id = ?",
                    "DELETE FROM login_events WHERE user_id = ?",
                ).forEach { sql ->
                    conn.prepareStatement(sql).use {
                        it.setObject(1, id)
                        it.executeUpdate()
                    }
                }
                conn.prepareStatement("DELETE FROM follows WHERE follower_id = ? OR followee_id = ?").use {
                    it.setObject(1, id)
                    it.setObject(2, id)
                    it.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM user_blocks WHERE blocker_id = ? OR blocked_id = ?").use {
                    it.setObject(1, id)
                    it.setObject(2, id)
                    it.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM posts WHERE author_id = ?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
            }
            seededAdmins.forEach { id ->
                conn.prepareStatement("DELETE FROM admin_actions_log WHERE admin_id = ?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
            }
            seededUsers.forEach { id ->
                conn.prepareStatement("DELETE FROM users WHERE id = ?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
            }
            seededAdmins.forEach { id ->
                conn.prepareStatement("DELETE FROM admin_users WHERE id = ?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
            }
        }
        seededUsers.clear()
        seededAdmins.clear()
        seededConversations.clear()
    }

    afterTest { cleanup() }
    afterSpec { dataSource.close() }

    fun statusOf(requestId: UUID): String =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT status FROM data_export_requests WHERE id = ?").use { ps ->
                ps.setObject(1, requestId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getString("status")
                }
            }
        }

    fun attemptCountOf(requestId: UUID): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT attempt_count FROM data_export_requests WHERE id = ?").use { ps ->
                ps.setObject(1, requestId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt("attempt_count")
                }
            }
        }

    fun notificationCount(userId: UUID): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = 'data_export_ready'").use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    /** Unzips the captured archive into name → UTF-8-text. */
    fun unzip(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                out[entry.name] = zis.readBytes().toString(Charsets.UTF_8)
                entry = zis.nextEntry
            }
        }
        return out
    }

    /**
     * Captures the worker logger's events for the duration of [block] so a test can assert what
     * the worker did (or did NOT) log. Mirrors the idiom in
     * [id.nearyou.app.admin.PrivacyFlipWorkerRouteTest].
     */
    fun withLogCapture(block: (ListAppender<ILoggingEvent>) -> Unit) {
        val logger = LoggerFactory.getLogger("id.nearyou.app.account.DataExportWorker") as LogbackLogger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        val previous = logger.level
        logger.level = Level.TRACE
        try {
            block(appender)
        } finally {
            logger.detachAppender(appender)
            logger.level = previous
            appender.stop()
        }
    }

    // ---- 8.5 / 8.9 happy path --------------------------------------------

    "8.5 happy path: pending → ready + key + ~24h deadline + notification + one email" {
        val store = CapturingObjectStore()
        val email = RecordingEmailSender(SendResult.Sent("msg-1"))
        val uid = seedUser(emailAddr = "person@example.com")
        val reqId = seedPendingRequest(uid)

        val result = runBlocking { worker(store, email).execute() }

        result.readyCount shouldBe 1
        statusOf(reqId) shouldBe "ready"

        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT r2_object_key, download_expires_at FROM data_export_requests WHERE id = ?").use { ps ->
                ps.setObject(1, reqId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getString("r2_object_key") shouldBe "exports/$uid/$reqId.zip"
                    val deadline = rs.getTimestamp("download_expires_at").toInstant()
                    val expected = Instant.now().plus(24, ChronoUnit.HOURS)
                    (Duration.between(deadline, expected).abs().toMinutes() < 5) shouldBe true
                }
            }
        }
        // One upload + one email + exactly one notification.
        store.putCount.get() shouldBe 1
        email.sends.size shouldBe 1
        email.sends.single().to shouldBe "person@example.com"
        notificationCount(uid) shouldBe 1
    }

    "8.9 notification row has type=data_export_ready, body_data {signed_url, expires_at}, NULL target" {
        val store = CapturingObjectStore()
        val email = RecordingEmailSender()
        val uid = seedUser()
        val reqId = seedPendingRequest(uid)

        runBlocking { worker(store, email).execute() }

        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT type, target_type, target_id, body_data::text AS bd FROM notifications WHERE user_id = ?",
            ).use { ps ->
                ps.setObject(1, uid)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getString("type") shouldBe "data_export_ready"
                    (rs.getString("target_type") == null) shouldBe true
                    (rs.getString("target_id") == null) shouldBe true
                    val body = Json.parseToJsonElement(rs.getString("bd")).jsonObject
                    (body["signed_url"]?.jsonPrimitive?.content == "https://fake.r2.example/exports/$uid/$reqId.zip?sig=test") shouldBe true
                    (body["expires_at"]?.jsonPrimitive?.content?.isNotBlank() == true) shouldBe true
                }
            }
        }
    }

    // ---- 8.6 concurrency --------------------------------------------------

    "8.6 two parallel invocations over one pending request → exactly one processes it" {
        val store = CapturingObjectStore()
        val email = RecordingEmailSender()
        val uid = seedUser()
        val reqId = seedPendingRequest(uid)

        val (r1, r2) =
            runBlocking {
                val a = async { worker(store, email).execute() }
                val b = async { worker(store, email).execute() }
                awaitAll(a, b).let { it[0] to it[1] }
            }

        // Exactly one invocation reports ready; the row is processed once.
        (r1.readyCount + r2.readyCount) shouldBe 1
        statusOf(reqId) shouldBe "ready"
        store.putCount.get() shouldBe 1
        notificationCount(uid) shouldBe 1
        email.sends.size shouldBe 1
    }

    // ---- 8.7 fail-soft ----------------------------------------------------

    "8.7a ObjectStore throws → status=failed + attempt_count≥1, no ready/notification/email" {
        val store = CapturingObjectStore(throwOnPut = true)
        val email = RecordingEmailSender()
        val uid = seedUser()
        val reqId = seedPendingRequest(uid)

        val result = runBlocking { worker(store, email).execute() }

        result.failedCount shouldBe 1
        result.readyCount shouldBe 0
        statusOf(reqId) shouldBe "failed"
        (attemptCountOf(reqId) >= 1) shouldBe true
        notificationCount(uid) shouldBe 0
        email.sends.size shouldBe 0
    }

    "8.7a ObjectStore unconfigured (presign would throw too) → failed, nothing delivered" {
        val store = CapturingObjectStore(throwOnPut = true, configured = false)
        val email = RecordingEmailSender()
        val uid = seedUser()
        val reqId = seedPendingRequest(uid)

        runBlocking { worker(store, email).execute() }

        statusOf(reqId) shouldBe "failed"
        (attemptCountOf(reqId) >= 1) shouldBe true
        notificationCount(uid) shouldBe 0
        email.sends.size shouldBe 0
    }

    "8.7b upload OK but EmailSender fails → stays READY (notification emitted), NOT reverted; failure log carries no PII" {
        val store = CapturingObjectStore()
        val email = RecordingEmailSender(SendResult.Failed("smtp_5xx"))
        // Distinctive recipient so we can prove it never appears in the failure log line.
        val recipient = "secret.recipient.8b7@example.com"
        val uid = seedUser(emailAddr = recipient)
        val reqId = seedPendingRequest(uid)

        withLogCapture { appender ->
            val result = runBlocking { worker(store, email).execute() }

            // The email failed, but the export is the in-app-notification-durable channel → READY.
            result.readyCount shouldBe 1
            statusOf(reqId) shouldBe "ready"
            notificationCount(uid) shouldBe 1
            email.sends.size shouldBe 1 // attempted, returned Failed; the worker does not revert

            // The failure path must have logged (proves we exercised the email branch)…
            val lines = appender.list.map { it.formattedMessage }
            lines.any { it.contains("event=data_export_email_failed") } shouldBe true
            // …but NO log line may leak PII: not the recipient address, and not the signed download
            // URL (the CapturingObjectStore presigns a fake.r2.example host — its presence in any
            // log line would mean the URL leaked).
            lines.none { it.contains(recipient) } shouldBe true
            lines.none { it.contains("fake.r2.example") } shouldBe true
            lines.none { it.contains("?sig=") } shouldBe true
        }
    }

    // ---- 8.8 scope matrix -------------------------------------------------

    "8.8 archive contains the canonical Included categories" {
        val store = CapturingObjectStore()
        val email = RecordingEmailSender()
        val uid = seedUser()
        seedPendingRequest(uid)

        runBlocking { worker(store, email).execute() }
        val files = unzip(store.puts.values.single())

        listOf(
            "profile.json", "username_history.csv", "posts.csv", "post_edits.csv", "likes.csv",
            "replies.csv", "follows.csv", "blocks.csv", "chat_messages.csv", "reports.csv",
            "notifications.csv", "moderation_actions.csv", "session_history.csv",
            "subscription_history.csv", "manifest.json", "README.txt",
        ).forEach { name -> files.containsKey(name) shouldBe true }
    }

    "8.8 session history is sourced from login_events and now includes the IP + /24 subnet" {
        val store = CapturingObjectStore()
        val email = RecordingEmailSender()
        val uid = seedUser()
        dataSource.connection.use { conn ->
            // an in-window login event with an IP
            conn.prepareStatement(
                "INSERT INTO login_events (user_id, event_type, ip, device_fingerprint_hash) " +
                    "VALUES (?, 'signin', ?::inet, ?)",
            ).use { ps ->
                ps.setObject(1, uid)
                ps.setString(2, "203.0.113.45")
                ps.setString(3, "fp-export")
                ps.executeUpdate()
            }
            // a >90-day-old event — must be EXCLUDED by the export's 90-day window
            conn.prepareStatement(
                "INSERT INTO login_events (user_id, occurred_at, event_type, ip) " +
                    "VALUES (?, NOW() - INTERVAL '91 days', 'signin', ?::inet)",
            ).use { ps ->
                ps.setObject(1, uid)
                ps.setString(2, "198.51.100.7")
                ps.executeUpdate()
            }
        }
        seedPendingRequest(uid)

        runBlocking { worker(store, email).execute() }
        val csv = unzip(store.puts.values.single())["session_history.csv"]!!

        csv.contains("occurred_at") shouldBe true // login_events-sourced header
        csv.contains("event_type") shouldBe true
        csv.contains("ip_subnet_24") shouldBe true
        csv.contains("203.0.113.45") shouldBe true // the IP is now present (omitted pre-V34)
        csv.contains("203.0.113.0/24") shouldBe true // the generated /24 subnet
        csv.contains("198.51.100.7") shouldBe false // the >90-day row is outside the window
    }

    "8.8 chat export carries sent AND received with the peer id HASHED (raw id/username absent)" {
        val store = CapturingObjectStore()
        val email = RecordingEmailSender()
        val owner = seedUser()
        val peer = seedUser(emailAddr = null)
        // Make the peer username distinctive so we can assert it never appears raw.
        val peerUsername =
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT username FROM users WHERE id = ?").use { ps ->
                    ps.setObject(1, peer)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getString(1)
                    }
                }
            }
        val conv = seedConversationWith(owner, peer)
        seedMessage(conv, owner, "hello from owner")
        seedMessage(conv, peer, "hi back from peer")
        seedPendingRequest(owner)

        runBlocking { worker(store, email).execute() }
        val chat = unzip(store.puts.values.single())["chat_messages.csv"]!!

        // Both directions present.
        chat.contains("hello from owner") shouldBe true
        chat.contains("hi back from peer") shouldBe true
        chat.contains("sent") shouldBe true
        chat.contains("received") shouldBe true
        // The peer's RAW id and username NEVER appear; the HMAC of the peer id DOES.
        chat.contains(peer.toString()) shouldBe false
        chat.contains(peerUsername) shouldBe false
        val expectedHash = PeerIdHasher.fromSecret(null).hash(peer)
        chat.contains(expectedHash) shouldBe true
    }

    "8.8 posts export carries the user's own actual_location + city (not the fuzzed display)" {
        val store = CapturingObjectStore()
        val email = RecordingEmailSender()
        val uid = seedUser()
        seedPost(uid, "my located post")
        seedPost(uid, "soft deleted post", deleted = true)
        seedPendingRequest(uid)

        runBlocking { worker(store, email).execute() }
        val posts = unzip(store.puts.values.single())["posts.csv"]!!

        posts.contains("my located post") shouldBe true
        posts.contains("soft deleted post") shouldBe true // soft-deleted-in-grace included
        // Own actual_location (the deep-ocean seed coords) + city are present.
        posts.contains("105.0") shouldBe true
        posts.contains("-10.5") shouldBe true
        posts.contains("Deep Ocean") shouldBe true
    }

    "8.8 out-of-scope categories are absent from the archive" {
        val store = CapturingObjectStore()
        val email = RecordingEmailSender()
        val uid = seedUser()
        seedPendingRequest(uid)

        runBlocking { worker(store, email).execute() }
        val names = unzip(store.puts.values.single()).keys

        // No reports-received, attestation, admin-audit, CSAM, or rejected_identifiers files.
        names.none { it.contains("reports_received", ignoreCase = true) } shouldBe true
        names.none { it.contains("attestation", ignoreCase = true) } shouldBe true
        names.none { it.contains("admin_audit", ignoreCase = true) || it.contains("audit_log", ignoreCase = true) } shouldBe true
        names.none { it.contains("csam", ignoreCase = true) } shouldBe true
        names.none { it.contains("rejected_identifier", ignoreCase = true) } shouldBe true
    }

    "8.8 shadow-ban stealth: a shadow-banned user's archive reveals no shadow-ban" {
        val store = CapturingObjectStore()
        val email = RecordingEmailSender()
        val admin = seedAdmin()
        val uid = seedUser(shadowBanned = true)
        // A shadow-ban moderation entry that MUST be filtered out of moderation_actions.
        exec(
            """
            INSERT INTO admin_actions_log (admin_id, action_type, target_type, target_id, after_state)
            VALUES (?, 'resolve_report', 'user', ?, '{"resolution":"shadow_ban_author"}'::jsonb)
            """.trimIndent(),
            admin,
            uid.toString(),
        )
        // A benign moderation entry that SHOULD appear.
        exec(
            "INSERT INTO admin_actions_log (admin_id, action_type, target_type, target_id) VALUES (?, 'warn_user', 'user', ?)",
            admin,
            uid.toString(),
        )
        seedPendingRequest(uid)

        runBlocking { worker(store, email).execute() }
        val files = unzip(store.puts.values.single())

        // No file anywhere reveals the shadow-ban.
        files.values.none { it.contains("shadow_ban", ignoreCase = true) } shouldBe true
        files.values.none { it.contains("is_shadow_banned", ignoreCase = true) } shouldBe true
        // The benign moderation action still surfaces.
        files["moderation_actions.csv"]!!.contains("warn_user") shouldBe true
        // profile.json carries no shadow-ban field.
        files["profile.json"]!!.contains("shadow", ignoreCase = true) shouldBe false
    }

    "8.8 no other user's raw data leaks into the archive (peer-only hashing)" {
        val store = CapturingObjectStore()
        val email = RecordingEmailSender()
        val owner = seedUser()
        val other = seedUser(emailAddr = null)
        // owner follows + blocks other; other follows owner.
        exec("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", owner, other)
        exec("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", other, owner)
        exec("INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (?, ?)", owner, other)
        seedPendingRequest(owner)

        runBlocking { worker(store, email).execute() }
        val files = unzip(store.puts.values.single())

        // The other user's raw UUID appears in no file (follow + block peers are HMAC-hashed).
        files.values.none { it.contains(other.toString()) } shouldBe true
        // The HMAC of the other id is present in follows + blocks.
        val otherHash = PeerIdHasher.fromSecret(null).hash(other)
        files["follows.csv"]!!.contains(otherHash) shouldBe true
        files["blocks.csv"]!!.contains(otherHash) shouldBe true
    }
})
