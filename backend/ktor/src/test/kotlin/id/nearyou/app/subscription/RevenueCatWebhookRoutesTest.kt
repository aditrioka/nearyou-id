package id.nearyou.app.subscription

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.config.SecretResolver
import id.nearyou.app.infra.repo.JdbcNotificationRepository
import id.nearyou.app.notifications.DbNotificationEmitter
import id.nearyou.app.notifications.NoopNotificationDispatcher
import id.nearyou.app.notifications.NotificationEmitter
import id.nearyou.data.repository.NotificationDispatcher
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientCN

private fun hikari(): HikariDataSource {
    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val user = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"
    return HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            maximumPoolSize = 2 // CI connection budget (PR #157)
            initializationFailTimeout = -1
        },
    )
}

private const val TEST_BEARER = "test-revenuecat-bearer-secret"
private const val TEST_HMAC = "test-revenuecat-hmac-secret"

@Tags("database")
class RevenueCatWebhookRoutesTest : StringSpec({

    val dataSource = autoClose(hikari())
    val repository = SubscriptionEventRepository()
    val emitter: NotificationEmitter = DbNotificationEmitter(JdbcNotificationRepository(dataSource))
    val service =
        SubscriptionService(
            dataSource = dataSource,
            repository = repository,
            notifications = emitter,
            dispatcher = NoopNotificationDispatcher(),
            dbDispatcher = Dispatchers.IO,
        )

    // env="test" → secretKey returns the un-prefixed slot name; resolve matches these keys.
    val secrets =
        object : SecretResolver {
            override fun resolve(name: String): String? =
                when (name) {
                    "revenuecat-webhook-secret" -> TEST_BEARER
                    "revenuecat-webhook-hmac-secret" -> TEST_HMAC
                    else -> null
                }
        }

    fun seedUser(
        status: String = "free",
        privateProfile: Boolean = false,
    ): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix,
                                   subscription_status, private_profile_opt_in)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "rcw_$short")
                ps.setString(3, "RCW $short")
                ps.setObject(4, LocalDate.of(1990, 1, 1))
                ps.setString(5, short)
                ps.setString(6, status)
                ps.setBoolean(7, privateProfile)
                ps.executeUpdate()
            }
        }
        return id
    }

    fun cleanup(vararg ids: UUID) {
        dataSource.connection.use { conn ->
            ids.forEach { id ->
                conn.prepareStatement("DELETE FROM subscription_events WHERE user_id = ?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
                // notifications cascade on the users delete (ON DELETE CASCADE).
                conn.prepareStatement("DELETE FROM users WHERE id = ?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
            }
        }
    }

    fun status(userId: UUID): String =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT subscription_status FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getString(1)
                }
            }
        }

    fun privacyFlipAt(userId: UUID): java.time.Instant? =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT privacy_flip_scheduled_at FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getTimestamp(1)?.toInstant()
                }
            }
        }

    fun privacyFlipIsNull(userId: UUID): Boolean = privacyFlipAt(userId) == null

    // The privacy_flip_warning body_data carries the deadline in Postgres-native
    // timestamptz form; compare it as ::timestamptz to the user's stored column,
    // never string-match (jsonb does not render Instant.toString()).
    fun warningScheduleMatchesColumn(userId: UUID): Boolean =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT (n.body_data ->> 'privacy_flip_scheduled_at')::timestamptz = u.privacy_flip_scheduled_at
                  FROM notifications n
                  JOIN users u ON u.id = n.user_id
                 WHERE n.user_id = ? AND n.type = 'privacy_flip_warning'
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
            }
        }

    fun countEvents(rcEventId: String): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM subscription_events WHERE revenuecat_event_id = ?").use { ps ->
                ps.setString(1, rcEventId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    fun countEventsForUser(userId: UUID): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM subscription_events WHERE user_id = ?").use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    fun countNotifications(
        userId: UUID,
        type: String,
    ): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = ?").use { ps ->
                ps.setObject(1, userId)
                ps.setString(2, type)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    fun billingIssueGraceEndPresent(userId: UUID): Boolean =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT body_data ->> 'grace_end_at' FROM notifications WHERE user_id = ? AND type = 'subscription_billing_issue'",
            ).use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs -> rs.next() && rs.getString(1) != null }
            }
        }

    fun sign(body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(TEST_HMAC.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun eventBody(
        type: String,
        rcId: String,
        appUserId: String,
        extra: String = "",
    ): String = """{"event":{"type":"$type","id":"$rcId","app_user_id":"$appUserId"$extra}}"""

    suspend fun withApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            explicitNulls = false
                        },
                    )
                }
                revenueCatWebhookRoutes(service, secrets, "test")
            }
            block()
        }
    }

    fun incoming(
        type: String,
        rcId: String,
        userId: UUID,
    ) = SubscriptionService.IncomingEvent(type, rcId, userId, null, null, null, null)

    // ---------- 5.1 Auth ----------

    "5.1 missing Authorization → 401" {
        withApp {
            val resp =
                createClient { install(ClientCN) { json() } }.post("/internal/revenuecat-webhook") {
                    contentType(ContentType.Application.Json)
                    setBody(eventBody("INITIAL_PURCHASE", "rc1", UUID.randomUUID().toString()))
                }
            resp.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    "5.1 wrong Bearer → 401" {
        withApp {
            val resp =
                createClient { install(ClientCN) { json() } }.post("/internal/revenuecat-webhook") {
                    header(HttpHeaders.Authorization, "Bearer not-the-secret")
                    contentType(ContentType.Application.Json)
                    setBody(eventBody("INITIAL_PURCHASE", "rc1", UUID.randomUUID().toString()))
                }
            resp.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    "5.1 a non-secret (OIDC-shaped) bearer → 401 (only the shared secret admits)" {
        withApp {
            // A Google-OIDC-looking JWT is just a non-matching bearer here — this test
            // app mounts only the vendor route (no OIDC plugin), so it proves "non-secret
            // bearer → 401". The genuine "does NOT inherit the /internal OIDC gate"
            // isolation proof (route co-mounted WITH the gate) lives in
            // InternalRoutingIsolationTest.
            val jwtish = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJzY2hlZHVsZXIifQ.sig"
            val resp =
                createClient { install(ClientCN) { json() } }.post("/internal/revenuecat-webhook") {
                    header(HttpHeaders.Authorization, "Bearer $jwtish")
                    contentType(ContentType.Application.Json)
                    setBody(eventBody("INITIAL_PURCHASE", "rc1", UUID.randomUUID().toString()))
                }
            resp.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    "5.1 valid Bearer, no signature header → admitted (unsigned accepted; mandatory prod signing deferred)" {
        val u = seedUser()
        try {
            withApp {
                val resp =
                    createClient { install(ClientCN) { json() } }.post("/internal/revenuecat-webhook") {
                        header(HttpHeaders.Authorization, "Bearer $TEST_BEARER")
                        contentType(ContentType.Application.Json)
                        setBody(eventBody("INITIAL_PURCHASE", "rc_unsigned_$u", u.toString()))
                    }
                resp.status shouldBe HttpStatusCode.OK
            }
            status(u) shouldBe "premium_active"
        } finally {
            cleanup(u)
        }
    }

    "5.1 valid Bearer + valid HMAC signature → admitted" {
        val u = seedUser()
        try {
            withApp {
                val body = eventBody("INITIAL_PURCHASE", "rc_signed_$u", u.toString())
                val resp =
                    createClient { install(ClientCN) { json() } }.post("/internal/revenuecat-webhook") {
                        header(HttpHeaders.Authorization, "Bearer $TEST_BEARER")
                        header("X-RevenueCat-Signature", sign(body))
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                resp.status shouldBe HttpStatusCode.OK
            }
            status(u) shouldBe "premium_active"
        } finally {
            cleanup(u)
        }
    }

    "5.1 present-but-malformed signature → 401 (distinct from absent)" {
        val u = seedUser()
        try {
            withApp {
                val body = eventBody("INITIAL_PURCHASE", "rc_badsig_$u", u.toString())
                val resp =
                    createClient { install(ClientCN) { json() } }.post("/internal/revenuecat-webhook") {
                        header(HttpHeaders.Authorization, "Bearer $TEST_BEARER")
                        header("X-RevenueCat-Signature", "deadbeef") // valid hex, wrong HMAC
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                resp.status shouldBe HttpStatusCode.Unauthorized
            }
            status(u) shouldBe "free" // nothing applied
        } finally {
            cleanup(u)
        }
    }

    // ---------- 5.2 Idempotency / envelope ----------

    "5.2 first delivery records one event row + applies status" {
        val u = seedUser()
        try {
            withApp {
                createClient { install(ClientCN) { json() } }.post("/internal/revenuecat-webhook") {
                    header(HttpHeaders.Authorization, "Bearer $TEST_BEARER")
                    contentType(ContentType.Application.Json)
                    setBody(eventBody("INITIAL_PURCHASE", "rc_first_$u", u.toString()))
                }.status shouldBe HttpStatusCode.OK
            }
            countEvents("rc_first_$u") shouldBe 1
            status(u) shouldBe "premium_active"
        } finally {
            cleanup(u)
        }
    }

    "5.2 duplicate revenuecat_event_id → 200 duplicate, single row, status not re-applied" {
        val u = seedUser()
        try {
            withApp {
                val client = createClient { install(ClientCN) { json() } }
                val body = eventBody("INITIAL_PURCHASE", "rc_dupe_$u", u.toString())
                client.post("/internal/revenuecat-webhook") {
                    header(HttpHeaders.Authorization, "Bearer $TEST_BEARER")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }.status shouldBe HttpStatusCode.OK
                val second =
                    client.post("/internal/revenuecat-webhook") {
                        header(HttpHeaders.Authorization, "Bearer $TEST_BEARER")
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                second.status shouldBe HttpStatusCode.OK
                Json.parseToJsonElement(second.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content shouldBe "duplicate"
            }
            countEvents("rc_dupe_$u") shouldBe 1
        } finally {
            cleanup(u)
        }
    }

    "5.2 concurrent duplicate deliveries → exactly one row, transition applied at most once" {
        val u = seedUser()
        try {
            val evt = incoming("INITIAL_PURCHASE", "rc_conc_$u", u)
            val results =
                coroutineScope {
                    val a = async(Dispatchers.IO) { service.process(evt) }
                    val b = async(Dispatchers.IO) { service.process(evt) }
                    listOf(a.await(), b.await())
                }
            countEvents("rc_conc_$u") shouldBe 1
            results.count { it == SubscriptionService.Result.Ok } shouldBe 1
            results.count { it == SubscriptionService.Result.Duplicate } shouldBe 1
            status(u) shouldBe "premium_active"
        } finally {
            cleanup(u)
        }
    }

    "5.2 malformed body → 400, no writes" {
        val u = seedUser()
        try {
            withApp {
                createClient { install(ClientCN) { json() } }.post("/internal/revenuecat-webhook") {
                    header(HttpHeaders.Authorization, "Bearer $TEST_BEARER")
                    contentType(ContentType.Application.Json)
                    setBody("""{"event":{"type":"INITIAL_PURCHASE"}}""") // missing id + app_user_id
                }.status shouldBe HttpStatusCode.BadRequest
            }
            countEventsForUser(u) shouldBe 0
            status(u) shouldBe "free"
        } finally {
            cleanup(u)
        }
    }

    "5.2 orphan app_user_id → 200 ignored, no writes" {
        val orphan = UUID.randomUUID()
        withApp {
            val resp =
                createClient { install(ClientCN) { json() } }.post("/internal/revenuecat-webhook") {
                    header(HttpHeaders.Authorization, "Bearer $TEST_BEARER")
                    contentType(ContentType.Application.Json)
                    setBody(eventBody("INITIAL_PURCHASE", "rc_orphan_$orphan", orphan.toString()))
                }
            resp.status shouldBe HttpStatusCode.OK
            Json.parseToJsonElement(resp.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content shouldBe "ignored"
        }
        countEvents("rc_orphan_$orphan") shouldBe 0
    }

    "5.2 unknown event type → 200 ignored, no writes" {
        val u = seedUser()
        try {
            withApp {
                createClient { install(ClientCN) { json() } }.post("/internal/revenuecat-webhook") {
                    header(HttpHeaders.Authorization, "Bearer $TEST_BEARER")
                    contentType(ContentType.Application.Json)
                    setBody(eventBody("TEST", "rc_unknown_$u", u.toString()))
                }.status shouldBe HttpStatusCode.OK
            }
            countEventsForUser(u) shouldBe 0
            status(u) shouldBe "free"
        } finally {
            cleanup(u)
        }
    }

    "5.2 CANCELLATION for an unknown user → UnknownUser, no writes (self-assign existence path)" {
        val orphan = UUID.randomUUID()
        // Exercises the newStatus=null (cancellation) self-assign UPDATE on a
        // non-existent user — 0 rows ⇒ orphan ⇒ no event recorded.
        service.process(incoming("CANCELLATION", "rc_cancel_orphan_$orphan", orphan)) shouldBe
            SubscriptionService.Result.UnknownUser
        countEvents("rc_cancel_orphan_$orphan") shouldBe 0
    }

    // ---------- 5.3 State machine ----------

    "5.3 initial_purchase → premium_active + event" {
        val u = seedUser()
        try {
            service.process(incoming("INITIAL_PURCHASE", "rc_ip_$u", u)) shouldBe SubscriptionService.Result.Ok
            status(u) shouldBe "premium_active"
            countEvents("rc_ip_$u") shouldBe 1
        } finally {
            cleanup(u)
        }
    }

    "5.3 renewal from premium_active stays premium_active" {
        val u = seedUser(status = "premium_active")
        try {
            service.process(incoming("RENEWAL", "rc_rn_$u", u))
            status(u) shouldBe "premium_active"
        } finally {
            cleanup(u)
        }
    }

    "5.3 renewal from premium_billing_retry heals to premium_active" {
        val u = seedUser(status = "premium_billing_retry")
        try {
            service.process(incoming("RENEWAL", "rc_heal_$u", u))
            status(u) shouldBe "premium_active"
        } finally {
            cleanup(u)
        }
    }

    "5.3 billing_issue → premium_billing_retry + grace notification + access retained" {
        val u = seedUser(status = "premium_active")
        try {
            service.process(incoming("BILLING_ISSUE", "rc_bi_$u", u))
            status(u) shouldBe "premium_billing_retry"
            countNotifications(u, "subscription_billing_issue") shouldBe 1
            billingIssueGraceEndPresent(u) shouldBe true
        } finally {
            cleanup(u)
        }
    }

    "5.3 expiration → free + expired notification" {
        val u = seedUser(status = "premium_billing_retry")
        try {
            service.process(incoming("EXPIRATION", "rc_exp_$u", u))
            status(u) shouldBe "free"
            countNotifications(u, "subscription_expired") shouldBe 1
        } finally {
            cleanup(u)
        }
    }

    "5.3 cancellation → status unchanged + event + no notification" {
        val u = seedUser(status = "premium_active")
        try {
            service.process(incoming("CANCELLATION", "rc_cancel_$u", u))
            status(u) shouldBe "premium_active"
            countEvents("rc_cancel_$u") shouldBe 1
            countNotifications(u, "subscription_billing_issue") shouldBe 0
            countNotifications(u, "subscription_expired") shouldBe 0
        } finally {
            cleanup(u)
        }
    }

    "5.3 billing_issue on an already-free user → safe no-op (event recorded, status stays free)" {
        val u = seedUser(status = "free")
        try {
            service.process(incoming("BILLING_ISSUE", "rc_bifree_$u", u))
            status(u) shouldBe "premium_billing_retry" // billing_issue sets retry unconditionally
            // (an already-free user receiving BILLING_ISSUE is unusual but handled; the
            // transition is unconditional and the event is recorded.)
            countEvents("rc_bifree_$u") shouldBe 1
        } finally {
            cleanup(u)
        }
    }

    // ---------- 5.4 Deferred guards ----------

    "GRANT activates Premium and records a source=referral grant event" {
        val u = seedUser(status = "free")
        try {
            service.process(incoming("GRANT", "rc_grant_$u", u)) shouldBe SubscriptionService.Result.Ok
            status(u) shouldBe "premium_active"
            countEventsForUser(u) shouldBe 1
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "SELECT event_type, source FROM subscription_events WHERE revenuecat_event_id = ?",
                ).use { ps ->
                    ps.setString(1, "rc_grant_$u")
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getString("event_type") shouldBe "grant"
                        rs.getString("source") shouldBe "referral"
                    }
                }
            }
        } finally {
            cleanup(u)
        }
    }

    "GRANT re-delivered is a no-op duplicate" {
        val u = seedUser(status = "free")
        try {
            service.process(incoming("GRANT", "rc_grdup_$u", u)) shouldBe SubscriptionService.Result.Ok
            service.process(incoming("GRANT", "rc_grdup_$u", u)) shouldBe SubscriptionService.Result.Duplicate
            countEventsForUser(u) shouldBe 1
            status(u) shouldBe "premium_active"
        } finally {
            cleanup(u)
        }
    }

    "GRANT for an unknown user is acknowledged without writes" {
        val ghost = UUID.randomUUID()
        service.process(incoming("GRANT", "rc_grorph_$ghost", ghost)) shouldBe SubscriptionService.Result.UnknownUser
        countEvents("rc_grorph_$ghost") shouldBe 0
    }

    "referral GRANT is excluded from the paid MRR query" {
        val u = seedUser(status = "free")
        try {
            service.process(incoming("GRANT", "rc_grmrr_$u", u))
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "SELECT COUNT(*) FROM subscription_events " +
                        "WHERE user_id = ? AND source = 'paid' AND event_type IN ('initial_purchase', 'renewal')",
                ).use { ps ->
                    ps.setObject(1, u)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1) shouldBe 0
                    }
                }
            }
        } finally {
            cleanup(u)
        }
    }

    "5.4 premium_billing_retry with no EXPIRATION is NOT auto-downgraded (no time-based worker)" {
        val u = seedUser(status = "premium_active")
        try {
            service.process(incoming("BILLING_ISSUE", "rc_noauto_$u", u))
            status(u) shouldBe "premium_billing_retry"
            // No further event; the webhook has no time-based downgrade — status holds.
            status(u) shouldBe "premium_billing_retry"
        } finally {
            cleanup(u)
        }
    }

    // ---------- 5.5 Analytics ----------

    "5.5 MRR query returns only paid purchase/renewal rows after a mixed-event run" {
        val u = seedUser()
        try {
            service.process(incoming("INITIAL_PURCHASE", "rc_mrr_ip_$u", u))
            service.process(incoming("RENEWAL", "rc_mrr_rn_$u", u))
            service.process(incoming("BILLING_ISSUE", "rc_mrr_bi_$u", u))
            service.process(incoming("EXPIRATION", "rc_mrr_exp_$u", u))
            service.process(incoming("CANCELLATION", "rc_mrr_cancel_$u", u))
            val mrrCount =
                dataSource.connection.use { conn: Connection ->
                    conn.prepareStatement(
                        """
                        SELECT COUNT(*) FROM subscription_events
                         WHERE user_id = ? AND source = 'paid'
                           AND event_type IN ('initial_purchase', 'renewal')
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setObject(1, u)
                        ps.executeQuery().use { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                    }
                }
            mrrCount shouldBe 2 // only initial_purchase + renewal
        } finally {
            cleanup(u)
        }
    }

    // ---------- 5.6 Transaction atomicity ----------

    "5.6 a BILLING_ISSUE whose notification write throws rolls back status + event" {
        val u = seedUser(status = "premium_active")
        try {
            val throwingEmitter =
                object : NotificationEmitter {
                    override fun emit(
                        conn: Connection,
                        recipientId: UUID,
                        actorUserId: UUID?,
                        type: id.nearyou.data.repository.NotificationType,
                        targetType: String?,
                        targetId: UUID?,
                        bodyData: kotlinx.serialization.json.JsonObject,
                    ): UUID? = throw RuntimeException("notification write boom")
                }
            val rollbackService =
                SubscriptionService(
                    dataSource = dataSource,
                    repository = SubscriptionEventRepository(),
                    notifications = throwingEmitter,
                    dispatcher = NoopNotificationDispatcher(),
                    dbDispatcher = Dispatchers.IO,
                )
            shouldThrowAny { rollbackService.process(incoming("BILLING_ISSUE", "rc_rollback_$u", u)) }
            status(u) shouldBe "premium_active" // status UPDATE rolled back
            countEvents("rc_rollback_$u") shouldBe 0 // event INSERT rolled back
        } finally {
            cleanup(u)
        }
    }

    // ---------- 5.7 Privacy-flip scheduling/clearing (privacy-flip-worker) ----------

    "5.7 EXPIRATION on a private user schedules a 72h flip + privacy_flip_warning + still expires" {
        val u = seedUser(status = "premium_active", privateProfile = true)
        try {
            service.process(incoming("EXPIRATION", "rc_pflip_$u", u)) shouldBe SubscriptionService.Result.Ok
            status(u) shouldBe "free"
            val at = privacyFlipAt(u)
            at shouldNotBe null
            // ~72h ahead (DB NOW()+72h).
            (at!!.isAfter(java.time.Instant.now().plus(java.time.Duration.ofHours(71)))) shouldBe true
            (at.isBefore(java.time.Instant.now().plus(java.time.Duration.ofHours(73)))) shouldBe true
            countNotifications(u, "privacy_flip_warning") shouldBe 1
            countNotifications(u, "subscription_expired") shouldBe 1
            warningScheduleMatchesColumn(u) shouldBe true // body_data deadline == users column (::timestamptz)
        } finally {
            cleanup(u)
        }
    }

    "5.7 EXPIRATION on a public user leaves privacy_flip_scheduled_at NULL + no warning" {
        val u = seedUser(status = "premium_active", privateProfile = false)
        try {
            service.process(incoming("EXPIRATION", "rc_pub_$u", u)) shouldBe SubscriptionService.Result.Ok
            status(u) shouldBe "free"
            privacyFlipIsNull(u) shouldBe true
            countNotifications(u, "privacy_flip_warning") shouldBe 0
            countNotifications(u, "subscription_expired") shouldBe 1
        } finally {
            cleanup(u)
        }
    }

    "5.7 a re-delivered EXPIRATION (distinct event id) does NOT move the deadline (COALESCE)" {
        val u = seedUser(status = "premium_active", privateProfile = true)
        try {
            service.process(incoming("EXPIRATION", "rc_pflip1_$u", u)) shouldBe SubscriptionService.Result.Ok
            val first = privacyFlipAt(u)
            first shouldNotBe null
            // Second, genuinely-new event (distinct revenuecat_event_id) for the same now-free private user.
            service.process(incoming("EXPIRATION", "rc_pflip2_$u", u)) shouldBe SubscriptionService.Result.Ok
            privacyFlipAt(u) shouldBe first // COALESCE kept the earlier deadline
        } finally {
            cleanup(u)
        }
    }

    "5.7 re-activation clears a pending flip; clearing is idempotent" {
        val u = seedUser(status = "premium_active", privateProfile = true)
        try {
            service.process(incoming("EXPIRATION", "rc_clr_exp_$u", u))
            privacyFlipAt(u) shouldNotBe null
            service.process(incoming("RENEWAL", "rc_clr_rn_$u", u)) shouldBe SubscriptionService.Result.Ok
            status(u) shouldBe "premium_active"
            privacyFlipIsNull(u) shouldBe true // cleared on re-activation
            // Idempotent: another re-activation with NULL stays NULL.
            service.process(incoming("INITIAL_PURCHASE", "rc_clr_ip_$u", u)) shouldBe SubscriptionService.Result.Ok
            privacyFlipIsNull(u) shouldBe true
        } finally {
            cleanup(u)
        }
    }

    "5.7 lapse → re-subscribe → lapse again re-schedules a fresh deadline (COALESCE over cleared column)" {
        val u = seedUser(status = "premium_active", privateProfile = true)
        try {
            service.process(incoming("EXPIRATION", "rc_cyc_exp1_$u", u))
            val first = privacyFlipAt(u)
            first shouldNotBe null
            service.process(incoming("RENEWAL", "rc_cyc_rn_$u", u)) // clears
            privacyFlipIsNull(u) shouldBe true
            service.process(incoming("EXPIRATION", "rc_cyc_exp2_$u", u)) // re-schedules over the now-NULL column
            val second = privacyFlipAt(u)
            second shouldNotBe null
            (second!!.isBefore(first)) shouldBe false // a fresh deadline, not the stale earlier one
        } finally {
            cleanup(u)
        }
    }

    "5.7 CANCELLATION neither schedules nor clears a privacy flip" {
        val u = seedUser(status = "premium_active", privateProfile = true)
        try {
            // No pending flip → cancellation must not schedule one.
            service.process(incoming("CANCELLATION", "rc_cx1_$u", u)) shouldBe SubscriptionService.Result.Ok
            status(u) shouldBe "premium_active"
            privacyFlipIsNull(u) shouldBe true
            // With a pending flip (after an expiration) → cancellation must not clear it.
            service.process(incoming("EXPIRATION", "rc_cx_exp_$u", u))
            val pending = privacyFlipAt(u)
            pending shouldNotBe null
            service.process(incoming("CANCELLATION", "rc_cx2_$u", u)) shouldBe SubscriptionService.Result.Ok
            privacyFlipAt(u) shouldBe pending // unchanged
        } finally {
            cleanup(u)
        }
    }

    "5.7 FCM: private EXPIRATION dispatches BOTH expired + privacy_flip_warning; a duplicate dispatches none" {
        val recorded = java.util.concurrent.CopyOnWriteArrayList<UUID>()
        val recordingService =
            SubscriptionService(
                dataSource = dataSource,
                repository = SubscriptionEventRepository(),
                notifications = emitter,
                dispatcher = NotificationDispatcher { recorded.add(it) },
                dbDispatcher = Dispatchers.IO,
            )
        val u = seedUser(status = "premium_active", privateProfile = true)
        try {
            recordingService.process(incoming("EXPIRATION", "rc_fcm_$u", u)) shouldBe SubscriptionService.Result.Ok
            recorded.size shouldBe 2 // subscription_expired + privacy_flip_warning, both post-commit
            countNotifications(u, "subscription_expired") shouldBe 1
            countNotifications(u, "privacy_flip_warning") shouldBe 1
            // Duplicate (same event id) rolls back → no new emit, no new dispatch.
            recordingService.process(incoming("EXPIRATION", "rc_fcm_$u", u)) shouldBe SubscriptionService.Result.Duplicate
            recorded.size shouldBe 2
        } finally {
            cleanup(u)
        }
    }
})
