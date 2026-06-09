package id.nearyou.app.engagement

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.config.StubRemoteConfig
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import id.nearyou.app.infra.fcm.FcmAndInAppDispatcher
import id.nearyou.app.infra.fcm.FcmDispatcher
import id.nearyou.app.infra.fcm.FcmSendResult
import id.nearyou.app.infra.fcm.FcmSender
import id.nearyou.app.infra.repo.JdbcNotificationRepository
import id.nearyou.app.infra.repo.JdbcPostLikeRepository
import id.nearyou.app.infra.repo.JdbcUserRepository
import id.nearyou.app.notifications.DbNotificationEmitter
import id.nearyou.data.repository.ActorUsernameLookup
import id.nearyou.data.repository.FcmTokenRow
import id.nearyou.data.repository.NotificationDispatcher
import id.nearyou.data.repository.TokenSnapshot
import id.nearyou.data.repository.UserFcmTokenReader
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import java.sql.Date
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientCN

/**
 * Composition-level wiring proof for the LIKE emit-site → composite dispatcher →
 * FCM per-token fan-out, closing the earlier `fcm-end-to-end-composite-test`
 * follow-up.
 *
 * Why this is a directly-constructed surface (the entry's explicit fallback,
 * action item 2) rather than a `testApplication { module() }` Koin override
 * (action item 1): booting `Application.module()` cannot exercise the composite.
 * In the `test` ktor profile `module()` hardwires `notificationDispatcher =
 * inAppDispatcher` (the Noop) and never builds the composite; in any non-test
 * profile it calls `buildFcmComposite` which resolves the real `firebase-admin-sa`
 * secret + initializes the Firebase Admin SDK (not CI-viable). And `LikeService`
 * is constructed EAGERLY with the dispatcher value (`Application.kt`), so a
 * post-`module()` Koin override of `NotificationDispatcher` cannot reach the
 * already-built service. The override approach is therefore genuinely brittle —
 * this test wires the real [FcmAndInAppDispatcher] composite directly over a
 * mock [FcmSender] and drives a real like, asserting the FCM leg fans out once
 * per registered token AND the in-app leg fires (the regression class chat
 * `ChatMessageNotificationTest` 4.12 catches for the chat emit-site; the like
 * emit-site had no equivalent — `LikeEndpointsTest` uses a Noop dispatcher).
 *
 * The FCM dispatcher's deps are faked (a 2-token reader; the real
 * [JdbcNotificationRepository] resolves the just-committed row) and the scope is
 * `Dispatchers.Unconfined` so the fan-out completes synchronously, mirroring the
 * `FcmDispatcherTest` pattern in `:infra:fcm`.
 */
@Tags("database")
class FcmCompositeWiringTest : StringSpec(
    {
        val dataSource = hikariCompositeWiring()
        val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-composite")
        val jwtIssuer = JwtIssuer(keys)
        val users = JdbcUserRepository(dataSource)
        val likes = JdbcPostLikeRepository(dataSource)
        val notificationsRepo = JdbcNotificationRepository(dataSource)
        val notificationEmitter = DbNotificationEmitter(notificationsRepo)
        val noopLimiter =
            object : RateLimiter {
                override fun tryAcquire(
                    userId: UUID,
                    key: String,
                    capacity: Int,
                    ttl: java.time.Duration,
                ): RateLimiter.Outcome = RateLimiter.Outcome.Allowed(remaining = capacity - 1)

                override fun tryAcquireByKey(
                    key: String,
                    capacity: Int,
                    ttl: java.time.Duration,
                ): RateLimiter.Outcome = RateLimiter.Outcome.Allowed(remaining = capacity - 1)

                override fun releaseMostRecent(
                    userId: UUID,
                    key: String,
                ) = Unit
            }

        afterSpec { dataSource.close() }

        fun seedUser(): Pair<UUID, String> {
            val id = UUID.randomUUID()
            val short = id.toString().replace("-", "").take(8)
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix)
                    VALUES (?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, id)
                    ps.setString(2, "fcw_$short")
                    ps.setString(3, "FCM Composite Wiring Tester")
                    ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                    ps.setString(5, "f${short.take(7)}")
                    ps.executeUpdate()
                }
            }
            return id to jwtIssuer.issueAccessToken(id, tokenVersion = 0)
        }

        fun seedPost(authorId: UUID): UUID {
            val id = UUID.randomUUID()
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO posts (id, author_id, content, display_location, actual_location)
                    VALUES (?, ?, ?,
                      ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                      ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, id)
                    ps.setObject(2, authorId)
                    ps.setString(3, "fcw-post-${id.toString().take(6)}")
                    ps.setDouble(4, 106.8)
                    ps.setDouble(5, -6.2)
                    ps.setDouble(6, 106.8)
                    ps.setDouble(7, -6.2)
                    ps.executeUpdate()
                }
            }
            return id
        }

        fun cleanup(vararg ids: UUID) {
            dataSource.connection.use { conn ->
                conn.createStatement().use { st ->
                    ids.forEach {
                        st.executeUpdate("DELETE FROM notifications WHERE actor_user_id = '$it' OR user_id = '$it'")
                        st.executeUpdate("DELETE FROM posts WHERE author_id = '$it'")
                        st.executeUpdate("DELETE FROM users WHERE id = '$it'")
                    }
                }
            }
        }

        "like emit fans out one FCM send per registered token via the composite AND fires the in-app leg" {
            val (liker, tl) = seedUser()
            val (author, _) = seedUser()
            val post = seedPost(author)
            try {
                // FCM leg: mock sender (counts sends) + a reader returning 2 tokens for
                // the recipient. The real JdbcNotificationRepository resolves the row the
                // like just committed; Unconfined scope makes the fan-out synchronous.
                val sendCount = AtomicInteger(0)
                val mockSender =
                    FcmSender {
                        sendCount.incrementAndGet()
                        FcmSendResult.Sent("test-msg-id")
                    }
                val twoTokenReader =
                    object : UserFcmTokenReader {
                        override fun activeTokens(userId: UUID): TokenSnapshot =
                            TokenSnapshot(
                                tokens =
                                    listOf(
                                        FcmTokenRow("android", "tok-A", Instant.now()),
                                        FcmTokenRow("android", "tok-B", Instant.now()),
                                    ),
                                dispatchStartedAt = Instant.now(),
                            )

                        override fun deleteTokenIfStale(
                            userId: UUID,
                            platform: String,
                            token: String,
                            dispatchStartedAt: Instant,
                        ): Int = 0
                    }
                val fcmDispatcher =
                    FcmDispatcher(
                        notificationRepository = notificationsRepo,
                        userFcmTokenReader = twoTokenReader,
                        sender = mockSender,
                        actorUsernameLookup = ActorUsernameLookup { "liker" },
                        dispatcherScope = CoroutineScope(Dispatchers.Unconfined),
                    )
                // In-app leg: a recording dispatcher so we can prove the composite
                // invokes BOTH legs (not just the FCM one).
                val inAppDispatched = ConcurrentLinkedQueue<UUID>()
                val composite =
                    FcmAndInAppDispatcher(
                        fcm = fcmDispatcher,
                        inApp = NotificationDispatcher { inAppDispatched.add(it) },
                    )
                val service =
                    LikeService(
                        dataSource = dataSource,
                        likes = likes,
                        notifications = notificationEmitter,
                        dispatcher = composite,
                        rateLimiter = noopLimiter,
                        remoteConfig = StubRemoteConfig(),
                    )

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
                        install(Authentication) { configureUserJwt(keys, users, Instant::now) }
                        likeRoutes(service)
                    }
                    val resp =
                        createClient { install(ClientCN) { json() } }
                            .post("/api/v1/posts/$post/like") {
                                header(HttpHeaders.Authorization, "Bearer $tl")
                            }
                    resp.status shouldBe HttpStatusCode.NoContent
                }

                // FCM leg fanned out exactly once per registered token (2 tokens → 2 sends).
                sendCount.get() shouldBe 2
                // The composite ALSO fired the in-app leg, exactly once for the emitted row.
                inAppDispatched.size shouldBe 1
            } finally {
                cleanup(liker, author)
            }
        }
    },
)

private fun hikariCompositeWiring(): HikariDataSource {
    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val user = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"
    val config =
        HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            maximumPoolSize = 4
            initializationFailTimeout = -1
        }
    return HikariDataSource(config)
}
