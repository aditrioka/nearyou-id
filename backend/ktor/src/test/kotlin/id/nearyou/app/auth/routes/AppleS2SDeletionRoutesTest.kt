package id.nearyou.app.auth.routes

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.account.AccountDeletionRepository
import id.nearyou.app.account.AccountHardDeleteWorker
import id.nearyou.app.auth.provider.JwksCache
import id.nearyou.app.infra.repo.JdbcNotificationRepository
import id.nearyou.app.infra.repo.JdbcUserRepository
import id.nearyou.app.notifications.DbNotificationEmitter
import id.nearyou.data.repository.NotificationDispatcher
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.PrintWriter
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.sql.Date
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource
import ch.qos.logback.classic.Logger as LogbackLogger
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientCN

private fun sha256HexDb(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

private class DeletionStubJwksCache(
    private val kid: String,
    private val key: RSAPublicKey,
) : JwksCache(io.ktor.client.HttpClient(), "stub://", java.time.Clock.systemUTC()) {
    override suspend fun keyFor(kid: String): RSAPublicKey? = if (kid == this.kid) key else null
}

private fun signedPayload(
    privateKey: RSAPrivateKey,
    publicKey: RSAPublicKey,
    kid: String,
    type: String,
    sub: String?,
    transactionId: String?,
    audience: String,
): String {
    val builder =
        JWT.create()
            .withKeyId(kid)
            .withAudience(audience)
            .withIssuer("https://appleid.apple.com")
            .withIssuedAt(java.util.Date())
            .withExpiresAt(java.util.Date(System.currentTimeMillis() + 60_000))
            .withClaim("type", type)
    if (sub != null) builder.withClaim("sub", sub)
    if (transactionId != null) builder.withClaim("transaction_id", transactionId)
    return builder.sign(Algorithm.RSA256(publicKey, privateKey))
}

/** Fails the FIRST getConnection, then delegates — models a transient DB blip for the retry test. */
private class FlakyOnceDataSource(private val real: DataSource) : DataSource by real {
    private var failed = false

    override fun getConnection(): java.sql.Connection =
        if (!failed) {
            failed = true
            error("transient DB blip")
        } else {
            real.connection
        }
}

/** Throwing source for the pre-persist (5.4) + synchronous-execution-failure (5.3 / 5.14) paths. */
private object UnusableDataSource : DataSource {
    override fun getConnection(): java.sql.Connection = error("DB unavailable")

    override fun getConnection(
        username: String?,
        password: String?,
    ): java.sql.Connection = error("DB unavailable")

    override fun getLogWriter(): PrintWriter = error("not used")

    override fun setLogWriter(out: PrintWriter?) = error("not used")

    override fun setLoginTimeout(seconds: Int) = error("not used")

    override fun getLoginTimeout(): Int = error("not used")

    override fun getParentLogger(): java.util.logging.Logger = error("not used")

    override fun <T : Any?> unwrap(iface: Class<T>?): T = error("not used")

    override fun isWrapperFor(iface: Class<*>?): Boolean = error("not used")
}

private fun hikari(): HikariDataSource {
    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val config =
        HikariConfig().apply {
            jdbcUrl = url
            username = System.getenv("DB_USER") ?: "postgres"
            password = System.getenv("DB_PASSWORD") ?: "postgres"
            maximumPoolSize = 3
            initializationFailTimeout = -1
        }
    return HikariDataSource(config)
}

/**
 * DB-backed (`@Tags("database")`) end-to-end coverage of the two Apple S2S deletion
 * events at `POST /internal/apple/s2s-notifications` (`apple-s2s-deletion-flows`).
 * The route is wired with the REAL `JdbcUserRepository` + `AccountDeletionRepository`
 * + `AccountHardDeleteWorker` against Postgres, so the FK, the synchronous tombstone,
 * the 30-day grace, the session-kick, and the immediate-index backstop are exercised
 * for real. The no-DB scenarios (dedup, unresolvable-sub no-op) live in the in-memory
 * `AppleS2SRoutesTest`.
 *
 * The pool is `autoClose`d (docs/11 §3.2 / CI connection budget — never leak a HikariPool).
 */
@Tags("database")
class AppleS2SDeletionRoutesTest : StringSpec({
    val kid = "apple-del-kid"
    val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val priv = keyPair.private as RSAPrivateKey
    val pub = keyPair.public as RSAPublicKey
    val bundleId = "id.nearyou.app"

    val dataSource = autoClose(hikari())
    val users = JdbcUserRepository(dataSource)
    val realDeletionRepo = AccountDeletionRepository(dataSource)
    val realWorker = AccountHardDeleteWorker(dataSource)

    fun seedUser(
        sub: String,
        deleted: Boolean = false,
    ): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix, apple_id_hash, deleted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "adel_$short")
                ps.setString(3, "Real Name")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "a${short.take(7)}")
                ps.setString(6, sha256HexDb(sub))
                if (deleted) ps.setTimestamp(7, Timestamp.from(Instant.now())) else ps.setNull(7, java.sql.Types.TIMESTAMP)
                ps.executeUpdate()
            }
        }
        return id
    }

    fun seedPendingRow(
        userId: UUID,
        source: String,
        scheduled: Instant,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO deletion_requests (user_id, scheduled_hard_delete_at, source) VALUES (?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, userId)
                ps.setTimestamp(2, Timestamp.from(scheduled))
                ps.setString(3, source)
                ps.executeUpdate()
            }
        }
    }

    fun intQuery(
        sql: String,
        vararg args: Any,
    ): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    fun pendingCount(userId: UUID): Int =
        intQuery(
            "SELECT COUNT(*) FROM deletion_requests WHERE user_id = ? AND cancelled_at IS NULL AND executed_at IS NULL",
            userId,
        )

    fun deletionRequestCount(
        userId: UUID,
        source: String,
    ): Int = intQuery("SELECT COUNT(*) FROM deletion_requests WHERE user_id = ? AND source = ?", userId, source)

    fun deletionLogCount(userId: UUID): Int = intQuery("SELECT COUNT(*) FROM deletion_log WHERE user_id = ?", userId)

    fun tokenVersion(userId: UUID): Int = intQuery("SELECT token_version FROM users WHERE id = ?", userId)

    fun userDeletedAt(userId: UUID): Instant? =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT deleted_at FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getTimestamp("deleted_at")?.toInstant() else null }
            }
        }

    /** Latest deletion_requests row for the user: (source, scheduled, executedAt). */
    fun latestRow(userId: UUID): Triple<String, Instant, Instant?>? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT source, scheduled_hard_delete_at, executed_at FROM deletion_requests " +
                    "WHERE user_id = ? ORDER BY requested_at DESC LIMIT 1",
            ).use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) {
                        null
                    } else {
                        Triple(
                            rs.getString("source"),
                            rs.getTimestamp("scheduled_hard_delete_at").toInstant(),
                            rs.getTimestamp("executed_at")?.toInstant(),
                        )
                    }
                }
            }
        }

    fun cleanup(vararg ids: UUID) {
        dataSource.connection.use { conn ->
            ids.forEach { id ->
                listOf(
                    "DELETE FROM deletion_requests WHERE user_id = ?",
                    "DELETE FROM deletion_log WHERE user_id = ?",
                    "DELETE FROM refresh_tokens WHERE user_id = ?",
                    "DELETE FROM follows WHERE follower_id = ? OR followee_id = ?",
                    "DELETE FROM user_blocks WHERE blocker_id = ? OR blocked_id = ?",
                    "DELETE FROM user_fcm_tokens WHERE user_id = ?",
                    "DELETE FROM notifications WHERE user_id = ?",
                    "DELETE FROM login_events WHERE user_id = ?",
                    "DELETE FROM posts WHERE author_id = ?",
                    "DELETE FROM users WHERE id = ?",
                ).forEach { sql ->
                    conn.prepareStatement(sql).use { ps ->
                        ps.setObject(1, id)
                        if (sql.contains("OR")) ps.setObject(2, id)
                        ps.executeUpdate()
                    }
                }
            }
        }
    }

    suspend fun runApp(
        deletionRepo: AccountDeletionRepository = realDeletionRepo,
        worker: AccountHardDeleteWorker = realWorker,
        dispatcher: NotificationDispatcher = NotificationDispatcher { },
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                appleS2SRoutes(
                    DeletionStubJwksCache(kid, pub),
                    setOf(bundleId),
                    users,
                    deletionRepo,
                    worker,
                    dataSource,
                    DbNotificationEmitter(JdbcNotificationRepository(dataSource)),
                    dispatcher,
                )
            }
            block()
        }
    }

    suspend fun ApplicationTestBuilder.postEvent(
        type: String,
        sub: String?,
        txId: String,
    ) = createClient { install(ClientCN) { json() } }
        .post("/internal/apple/s2s-notifications") {
            contentType(ContentType.Application.Json)
            setBody(AppleS2SEnvelope(signedPayload = signedPayload(priv, pub, kid, type, sub, txId, bundleId)))
        }

    suspend fun withLogCapture(block: suspend (ListAppender<ILoggingEvent>) -> Unit) {
        val logger = LoggerFactory.getLogger("AppleS2SRoutes") as LogbackLogger
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

    // 5.1 — account-delete happy path: immediate row + synchronous tombstone before the 200.
    "5.1 account-delete inserts an immediate row and tombstones synchronously" {
        val sub = "del-sub-51"
        val uid = seedUser(sub)
        try {
            runApp { postEvent("account-delete", sub, "tx-51").status shouldBe HttpStatusCode.OK }
            val row = latestRow(uid)!!
            row.first shouldBe "apple_s2s_account_delete"
            (Duration.between(row.second, Instant.now()).abs() < Duration.ofMinutes(5)) shouldBe true
            (row.third != null) shouldBe true // executed_at stamped
            (userDeletedAt(uid) != null) shouldBe true
            deletionLogCount(uid) shouldBe 1
        } finally {
            cleanup(uid)
        }
    }

    // 5.2 — account-delete rows are non-cancellable (deletion stands).
    "5.2 account-delete is not cancellable" {
        val sub = "del-sub-52"
        val uid = seedUser(sub)
        try {
            runApp { postEvent("account-delete", sub, "tx-52").status shouldBe HttpStatusCode.OK }
            runBlocking { realDeletionRepo.cancelDeletion(uid) } shouldBe false
            (userDeletedAt(uid) != null) shouldBe true
        } finally {
            cleanup(uid)
        }
    }

    // 5.3 — synchronous failure leaves a durable row for the daily backstop; still 200.
    "5.3 synchronous execution failure leaves a due row + 200; daily worker backstops it" {
        val sub = "del-sub-53"
        val uid = seedUser(sub)
        val throwingWorker = AccountHardDeleteWorker(UnusableDataSource)
        try {
            // Route uses the REAL repo (row persists) + a THROWING worker (executeImmediate throws).
            runApp(worker = throwingWorker) {
                postEvent("account-delete", sub, "tx-53").status shouldBe HttpStatusCode.OK
            }
            val row = latestRow(uid)!!
            row.first shouldBe "apple_s2s_account_delete"
            (row.third == null) shouldBe true // executed_at IS NULL — still due
            (userDeletedAt(uid) == null) shouldBe true // not yet tombstoned
            // Daily backstop completes it via deletion_requests_immediate_idx.
            runBlocking { realWorker.execute() }
            (latestRow(uid)!!.third != null) shouldBe true
            (userDeletedAt(uid) != null) shouldBe true
            deletionLogCount(uid) shouldBe 1
        } finally {
            cleanup(uid)
        }
    }

    // 5.4 — pre-persist failure (DB down before the row commits) → non-2xx so Apple retries.
    "5.4 pre-persist failure returns non-2xx" {
        val sub = "del-sub-54"
        val uid = seedUser(sub)
        val throwingRepo = AccountDeletionRepository(UnusableDataSource)
        try {
            runApp(deletionRepo = throwingRepo) {
                val status = postEvent("account-delete", sub, "tx-54").status
                (status.value in 500..599) shouldBe true
            }
            deletionRequestCount(uid, "apple_s2s_account_delete") shouldBe 0
            (userDeletedAt(uid) == null) shouldBe true
        } finally {
            cleanup(uid)
        }
    }

    // 5.5 — consent-revoked: cancellable 30-day grace + session-kick.
    "5.5 consent-revoked schedules a ~30-day grace and bumps token_version" {
        val sub = "del-sub-55"
        val uid = seedUser(sub)
        try {
            runApp { postEvent("consent-revoked", sub, "tx-55").status shouldBe HttpStatusCode.OK }
            val row = latestRow(uid)!!
            row.first shouldBe "apple_s2s_consent_revoked"
            val expected = Instant.now().plus(30, ChronoUnit.DAYS)
            (Duration.between(row.second, expected).abs() < Duration.ofHours(2)) shouldBe true
            (row.third == null) shouldBe true // not executed — it is a grace row
            tokenVersion(uid) shouldBe 1
        } finally {
            cleanup(uid)
        }
    }

    // 5.6 — consent-revoked grace is cancellable (the existing cancel path restores it).
    "5.6 consent-revoked grace is cancellable within the window" {
        val sub = "del-sub-56"
        val uid = seedUser(sub)
        try {
            runApp { postEvent("consent-revoked", sub, "tx-56").status shouldBe HttpStatusCode.OK }
            pendingCount(uid) shouldBe 1
            runBlocking { realDeletionRepo.cancelDeletion(uid) } shouldBe true
            pendingCount(uid) shouldBe 0
        } finally {
            cleanup(uid)
        }
    }

    // 5.7 — consent-revoked is idempotent against an existing pending row but STILL kicks sessions.
    "5.7 consent-revoked no-ops the insert when a row is pending but still bumps token_version" {
        val sub = "del-sub-57"
        val uid = seedUser(sub)
        try {
            seedPendingRow(uid, "user", Instant.now().plus(30, ChronoUnit.DAYS))
            tokenVersion(uid) shouldBe 0
            runApp { postEvent("consent-revoked", sub, "tx-57").status shouldBe HttpStatusCode.OK }
            pendingCount(uid) shouldBe 1 // no second pending row
            deletionRequestCount(uid, "apple_s2s_consent_revoked") shouldBe 0 // the insert no-op'd
            tokenVersion(uid) shouldBe 1 // session-kick fired anyway
        } finally {
            cleanup(uid)
        }
    }

    // 5.8 — unknown / already-deleted sub → 200 no-op, no row, no token bump (both events).
    "5.8 already-deleted sub is a safe no-op for both events" {
        val subA = "del-sub-58a"
        val subC = "del-sub-58c"
        val uidA = seedUser(subA, deleted = true)
        val uidC = seedUser(subC, deleted = true)
        try {
            runApp { postEvent("account-delete", subA, "tx-58a").status shouldBe HttpStatusCode.OK }
            runApp { postEvent("consent-revoked", subC, "tx-58c").status shouldBe HttpStatusCode.OK }
            deletionRequestCount(uidA, "apple_s2s_account_delete") shouldBe 0
            deletionRequestCount(uidC, "apple_s2s_consent_revoked") shouldBe 0
            tokenVersion(uidC) shouldBe 0 // no session-kick for an already-deleted user
        } finally {
            cleanup(uidA, uidC)
        }
    }

    // 5.11 — missing sub on a deletion event → 400, no row (mirrors the email branch).
    "5.11 missing sub on a deletion event returns 400 with no row" {
        val sub = "del-sub-511"
        val uid = seedUser(sub)
        try {
            runApp { postEvent("account-delete", null, "tx-511a").status shouldBe HttpStatusCode.BadRequest }
            runApp { postEvent("consent-revoked", null, "tx-511c").status shouldBe HttpStatusCode.BadRequest }
            deletionRequestCount(uid, "apple_s2s_account_delete") shouldBe 0
            deletionRequestCount(uid, "apple_s2s_consent_revoked") shouldBe 0
        } finally {
            cleanup(uid)
        }
    }

    // 5.12 — account-delete escalates an existing grace to immediate.
    "5.12 account-delete escalates an existing grace row to immediate deletion" {
        val sub = "del-sub-512"
        val uid = seedUser(sub)
        try {
            seedPendingRow(uid, "apple_s2s_consent_revoked", Instant.now().plus(30, ChronoUnit.DAYS))
            runApp { postEvent("account-delete", sub, "tx-512").status shouldBe HttpStatusCode.OK }
            deletionRequestCount(uid, "apple_s2s_account_delete") shouldBe 1
            (userDeletedAt(uid) != null) shouldBe true // tombstoned NOW, not left in grace
        } finally {
            cleanup(uid)
        }
    }

    // 5.13 — double-execute safety: immediate then daily worker → exactly one deletion_log row.
    "5.13 executeImmediate then execute() tombstones exactly once" {
        val sub = "del-sub-513"
        val uid = seedUser(sub)
        try {
            val rowId = runBlocking { realDeletionRepo.scheduleAppleAccountDelete(uid) }!!
            runBlocking { realWorker.executeImmediate(rowId) } shouldBe true
            runBlocking { realWorker.executeImmediate(rowId) } shouldBe false // already executed
            runBlocking { realWorker.execute() } // daily backstop scan — must not re-process
            deletionLogCount(uid) shouldBe 1
        } finally {
            cleanup(uid)
        }
    }

    // Review B1 — a pre-persist failure must NOT consume the dedup key: Apple's retry of
    // the SAME transaction_id is processed (not short-circuited to "duplicate") and completes.
    "B1 pre-persist failure leaves the dedup key unconsumed — the retry completes the deletion" {
        val sub = "del-sub-b1"
        val uid = seedUser(sub)
        val flakyRepo = AccountDeletionRepository(FlakyOnceDataSource(dataSource))
        try {
            runApp(deletionRepo = flakyRepo) {
                // First receipt: transient DB blip pre-persist → non-2xx (Apple will retry).
                val first = postEvent("account-delete", sub, "tx-b1")
                (first.status.value in 500..599) shouldBe true
                // Retry with the SAME transaction_id inside the same app instance (same dedup):
                // must be processed, not deduped.
                val second = postEvent("account-delete", sub, "tx-b1")
                second.status shouldBe HttpStatusCode.OK
                second.bodyAsText() shouldBe """{"status":"ok"}"""
            }
            deletionRequestCount(uid, "apple_s2s_account_delete") shouldBe 1
            (userDeletedAt(uid) != null) shouldBe true
            deletionLogCount(uid) shouldBe 1
        } finally {
            cleanup(uid)
        }
    }

    // Review B2 — a leftover grace row for an already-tombstoned user is MOOTED by the
    // daily worker (stamped executed, no second deletion_log row, deleted_at untouched).
    "B2 leftover grace row after account-delete is mooted — no second deletion_log, no re-tombstone" {
        val sub = "del-sub-b2"
        val uid = seedUser(sub)
        try {
            seedPendingRow(uid, "apple_s2s_consent_revoked", Instant.now().plus(30, ChronoUnit.DAYS))
            runApp { postEvent("account-delete", sub, "tx-b2").status shouldBe HttpStatusCode.OK }
            deletionLogCount(uid) shouldBe 1
            val tombstonedAt = userDeletedAt(uid)!!
            // Force the grace row due and run the daily worker.
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "UPDATE deletion_requests SET scheduled_hard_delete_at = NOW() - INTERVAL '1 minute' " +
                        "WHERE user_id = ? AND source = 'apple_s2s_consent_revoked'",
                ).use { ps ->
                    ps.setObject(1, uid)
                    ps.executeUpdate()
                }
            }
            runBlocking { realWorker.execute() }
            deletionLogCount(uid) shouldBe 1 // NOT re-logged
            userDeletedAt(uid) shouldBe tombstonedAt // NOT re-tombstoned
            pendingCount(uid) shouldBe 0 // the moot row was stamped executed
        } finally {
            cleanup(uid)
        }
    }

    // 5.14 — a synchronous-execution failure logs NO PII (only event type + error class).
    "5.14 synchronous-execution failure logs no PII" {
        val sub = "del-sub-514"
        val uid = seedUser(sub)
        val throwingWorker = AccountHardDeleteWorker(UnusableDataSource)
        try {
            withLogCapture { appender ->
                runApp(worker = throwingWorker) {
                    postEvent("account-delete", sub, "tx-514").status shouldBe HttpStatusCode.OK
                }
                val lines = appender.list.map { it.formattedMessage }
                val failureLine = lines.firstOrNull { it.contains("apple_s2s_account_delete_exec_failed") }
                (failureLine != null) shouldBe true
                val hash = sha256HexDb(sub)
                failureLine!!.contains(sub) shouldBe false
                failureLine.contains(uid.toString()) shouldBe false
                failureLine.contains(hash) shouldBe false
            }
        } finally {
            cleanup(uid)
        }
    }

    // --- email-relay events (follow-up #433): flag write + apple_relay_email_changed
    // notification in ONE transaction, dispatched post-commit. Moved here from the
    // in-memory spec — the notification insert needs the real DB (V10 CHECK included).

    // body_data is compared via `->>` (extracted value), not `::text` — PG's jsonb
    // text rendering inserts a space after the colon, which is not the contract.
    fun relayNotification(userId: UUID): Triple<String, UUID?, String?>? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT type, actor_user_id, body_data->>'relay_enabled' AS relay FROM notifications WHERE user_id = ?",
            ).use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) {
                        null
                    } else {
                        Triple(rs.getString("type"), rs.getObject("actor_user_id", UUID::class.java), rs.getString("relay"))
                    }
                }
            }
        }

    "email-enabled flips the flag AND inserts apple_relay_email_changed, dispatched post-commit" {
        val sub = "email-sub-433-on"
        val uid = seedUser(sub)
        val dispatched = mutableListOf<UUID>()
        try {
            runApp(dispatcher = NotificationDispatcher { dispatched.add(it) }) {
                postEvent("email-enabled", sub, "tx-433-on").status shouldBe HttpStatusCode.OK
            }
            intQuery("SELECT COUNT(*) FROM users WHERE id = ? AND apple_relay_email = TRUE", uid) shouldBe 1
            val row = relayNotification(uid)
            (row != null) shouldBe true
            row!!.first shouldBe "apple_relay_email_changed"
            row.second shouldBe null // system-originated: actor is Apple, not a users row
            row.third shouldBe "true"
            dispatched.size shouldBe 1
        } finally {
            cleanup(uid)
        }
    }

    "email-disabled flips the flag back AND records relay_enabled=false" {
        val sub = "email-sub-433-off"
        val uid = seedUser(sub)
        dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE users SET apple_relay_email = TRUE WHERE id = ?").use { ps ->
                ps.setObject(1, uid)
                ps.executeUpdate()
            }
        }
        try {
            runApp {
                postEvent("email-disabled", sub, "tx-433-off").status shouldBe HttpStatusCode.OK
            }
            intQuery("SELECT COUNT(*) FROM users WHERE id = ? AND apple_relay_email = FALSE", uid) shouldBe 1
            relayNotification(uid)!!.third shouldBe "false"
        } finally {
            cleanup(uid)
        }
    }
})
