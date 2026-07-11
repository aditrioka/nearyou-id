package id.nearyou.app.auth.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import id.nearyou.app.account.AccountDeletionRepository
import id.nearyou.app.account.AccountHardDeleteWorker
import id.nearyou.app.auth.provider.JwksCache
import id.nearyou.app.auth.session.InMemoryUsers
import id.nearyou.app.auth.session.userRow
import id.nearyou.app.notifications.NotificationEmitter
import id.nearyou.data.repository.NotificationDispatcher
import id.nearyou.data.repository.NotificationType
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
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import java.io.PrintWriter
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date
import java.util.UUID
import javax.sql.DataSource
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientCN

private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

/**
 * The deletion repo/worker need a [DataSource] to construct, but every scenario in
 * THIS spec exercises a path that returns BEFORE any deletion DB write (email/aud,
 * unresolvable-sub no-op, or dedup short-circuit). A throwing source makes that an
 * assertion: if a branch wrongly reached the deletion substrate, the test fails loudly.
 * The DB-touching deletion scenarios live in `AppleS2SDeletionRoutesTest` (`@Tags("database")`).
 */
private object NoDbDataSource : DataSource {
    override fun getConnection(): java.sql.Connection = error("deletion path must not touch the DB in this spec")

    override fun getConnection(
        username: String?,
        password: String?,
    ): java.sql.Connection = error("deletion path must not touch the DB in this spec")

    override fun getLogWriter(): PrintWriter = error("not used")

    override fun setLogWriter(out: PrintWriter?) = error("not used")

    override fun setLoginTimeout(seconds: Int) = error("not used")

    override fun getLoginTimeout(): Int = error("not used")

    override fun getParentLogger(): java.util.logging.Logger = error("not used")

    override fun <T : Any?> unwrap(iface: Class<T>?): T = error("not used")

    override fun isWrapperFor(iface: Class<*>?): Boolean = error("not used")
}

private fun noDbDeletionRepo() = AccountDeletionRepository(NoDbDataSource)

private fun noDbHardDeleteWorker() = AccountHardDeleteWorker(NoDbDataSource)

/**
 * Every scenario here returns BEFORE the email branch's transactional flag+notification
 * write (aud failure, unresolvable/tombstoned sub, dedup) — a throwing emitter/dispatcher
 * makes that an assertion, like [NoDbDataSource] does for the deletion substrate. The
 * DB-touching email scenarios live in `AppleS2SDeletionRoutesTest` (`@Tags("database")`).
 */
private object NoEmitEmitter : NotificationEmitter {
    override fun emit(
        conn: java.sql.Connection,
        recipientId: UUID,
        actorUserId: UUID?,
        type: NotificationType,
        targetType: String?,
        targetId: UUID?,
        bodyData: JsonObject,
    ): UUID? = error("notification emitter must not be reached in this spec")
}

private val noDispatch = NotificationDispatcher { error("dispatcher must not be reached in this spec") }

private class StubJwksCache(
    private val kid: String,
    private val key: RSAPublicKey,
) : JwksCache(io.ktor.client.HttpClient(), "stub://", java.time.Clock.systemUTC()) {
    override suspend fun keyFor(kid: String): RSAPublicKey? = if (kid == this.kid) key else null
}

private fun makeAppleSignedPayload(
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
            .withIssuedAt(Date())
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .withClaim("type", type)
    if (sub != null) builder.withClaim("sub", sub)
    if (transactionId != null) builder.withClaim("transaction_id", transactionId)
    return builder.sign(Algorithm.RSA256(publicKey, privateKey))
}

class AppleS2SRoutesTest : StringSpec({
    val kid = "apple-test-kid"
    val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val priv = keyPair.private as RSAPrivateKey
    val pub = keyPair.public as RSAPublicKey
    val bundleId = "id.nearyou.app"

    suspend fun setup(
        users: InMemoryUsers,
        block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit,
    ) {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                appleS2SRoutes(
                    StubJwksCache(kid, pub),
                    setOf(bundleId),
                    users,
                    noDbDeletionRepo(),
                    noDbHardDeleteWorker(),
                    NoDbDataSource,
                    NoEmitEmitter,
                    noDispatch,
                )
            }
            block()
        }
    }

    "empty audience allow-list fails CLOSED (401, mirrors sign-in verifyRs256)" {
        val sub = "apple-sub-empty-aud"
        val user = userRow(appleIdHash = sha256Hex(sub), appleRelayEmail = false)
        val users = InMemoryUsers(listOf(user))
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                appleS2SRoutes(
                    StubJwksCache(kid, pub),
                    emptySet(),
                    users,
                    noDbDeletionRepo(),
                    noDbHardDeleteWorker(),
                    NoDbDataSource,
                    NoEmitEmitter,
                    noDispatch,
                )
            }
            val signed =
                makeAppleSignedPayload(priv, pub, kid, "email-enabled", sub, "tx-empty-aud", bundleId)
            val client = createClient { install(ClientCN) { json() } }
            val response =
                client.post("/internal/apple/s2s-notifications") {
                    contentType(ContentType.Application.Json)
                    setBody(AppleS2SEnvelope(signedPayload = signed))
                }
            response.status shouldBe HttpStatusCode.Unauthorized
            users.rows[user.id]!!.appleRelayEmail shouldBe false
        }
    }

    // The email happy-path scenarios (flag flip + apple_relay_email_changed notification
    // in one transaction) moved to `AppleS2SDeletionRoutesTest` (`@Tags("database")`,
    // follow-up #433) — the notification insert needs the real DB. Here we pin the two
    // pre-DB email exits: an unresolvable and a tombstoned sub are graceful 200 no-ops
    // that never reach the DataSource/emitter (which throw if touched).

    "email event for an unresolvable sub → 200 no-op, DB + emitter untouched" {
        setup(InMemoryUsers(emptyList())) {
            val signed =
                makeAppleSignedPayload(priv, pub, kid, "email-enabled", "apple-sub-unknown-email", "tx-1", bundleId)
            val client = createClient { install(ClientCN) { json() } }
            val response =
                client.post("/internal/apple/s2s-notifications") {
                    contentType(ContentType.Application.Json)
                    setBody(AppleS2SEnvelope(signedPayload = signed))
                }
            response.status shouldBe HttpStatusCode.OK
        }
    }

    "email event for a tombstoned user → 200 no-op, flag + emitter untouched" {
        val sub = "apple-sub-2"
        val user = userRow(appleIdHash = sha256Hex(sub), appleRelayEmail = true, deletedAt = Instant.now())
        val users = InMemoryUsers(listOf(user))
        setup(users) {
            val signed =
                makeAppleSignedPayload(priv, pub, kid, "email-disabled", sub, "tx-2", bundleId)
            val client = createClient { install(ClientCN) { json() } }
            val response =
                client.post("/internal/apple/s2s-notifications") {
                    contentType(ContentType.Application.Json)
                    setBody(AppleS2SEnvelope(signedPayload = signed))
                }
            response.status shouldBe HttpStatusCode.OK
            users.rows[user.id]!!.appleRelayEmail shouldBe true
        }
    }

    // Replaces the former `→ 501 not_implemented` cases (1.4): the deletion branch is now
    // real. The DB-touching happy/escalation/backstop scenarios live in
    // `AppleS2SDeletionRoutesTest` (`@Tags("database")`); here we pin the no-DB paths — an
    // unresolvable `sub` is a graceful 200 no-op that NEVER reaches the deletion substrate
    // (NoDbDataSource would throw if it did).
    "consent-revoked for an unresolvable sub → 200 no-op, deletion substrate untouched" {
        val sub = "apple-sub-3-unknown"
        // No user carries this apple hash → findByAppleIdHash returns null.
        setup(InMemoryUsers(emptyList())) {
            val signed =
                makeAppleSignedPayload(priv, pub, kid, "consent-revoked", sub, "tx-3", bundleId)
            val client = createClient { install(ClientCN) { json() } }
            val response =
                client.post("/internal/apple/s2s-notifications") {
                    contentType(ContentType.Application.Json)
                    setBody(AppleS2SEnvelope(signedPayload = signed))
                }
            response.status shouldBe HttpStatusCode.OK
        }
    }

    "account-delete for an unresolvable sub → 200 no-op, deletion substrate untouched" {
        val sub = "apple-sub-4-unknown"
        setup(InMemoryUsers(emptyList())) {
            val signed =
                makeAppleSignedPayload(priv, pub, kid, "account-delete", sub, "tx-4", bundleId)
            val client = createClient { install(ClientCN) { json() } }
            val response =
                client.post("/internal/apple/s2s-notifications") {
                    contentType(ContentType.Application.Json)
                    setBody(AppleS2SEnvelope(signedPayload = signed))
                }
            response.status shouldBe HttpStatusCode.OK
        }
    }

    // 5.9 — dedup gates deletion events too (it short-circuits BEFORE the type switch, so an
    // unresolvable sub keeps this DB-free): the same transaction_id replayed → 200 duplicate.
    "duplicate deletion notification is deduped → 200 {status:duplicate}, branch not re-run" {
        val sub = "apple-sub-dedup-unknown"
        setup(InMemoryUsers(emptyList())) {
            val signed =
                makeAppleSignedPayload(priv, pub, kid, "account-delete", sub, "tx-dedup", bundleId)
            val client = createClient { install(ClientCN) { json() } }
            val first =
                client.post("/internal/apple/s2s-notifications") {
                    contentType(ContentType.Application.Json)
                    setBody(AppleS2SEnvelope(signedPayload = signed))
                }
            first.status shouldBe HttpStatusCode.OK
            val second =
                client.post("/internal/apple/s2s-notifications") {
                    contentType(ContentType.Application.Json)
                    setBody(AppleS2SEnvelope(signedPayload = signed))
                }
            second.status shouldBe HttpStatusCode.OK
            second.bodyAsText() shouldBe """{"status":"duplicate"}"""
        }
    }

    // qodo review: a replayed malformed deletion event (no sub, no transaction_id) must
    // 400 on EVERY receipt — the dedup key is only recorded on a 2xx outcome, so the
    // 400 path never marks the key as seen.
    "replayed missing-sub deletion event returns 400 both times (never 'duplicate')" {
        setup(InMemoryUsers(emptyList())) {
            val signed =
                makeAppleSignedPayload(priv, pub, kid, "account-delete", null, null, bundleId)
            val client = createClient { install(ClientCN) { json() } }
            repeat(2) {
                val response =
                    client.post("/internal/apple/s2s-notifications") {
                        contentType(ContentType.Application.Json)
                        setBody(AppleS2SEnvelope(signedPayload = signed))
                    }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }
    }

    "wrong audience → 401" {
        val sub = "apple-sub-5"
        val user = userRow(appleIdHash = sha256Hex(sub))
        setup(InMemoryUsers(listOf(user))) {
            val signed =
                makeAppleSignedPayload(priv, pub, kid, "email-enabled", sub, "tx-5", "wrong.bundle")
            val client = createClient { install(ClientCN) { json() } }
            val response =
                client.post("/internal/apple/s2s-notifications") {
                    contentType(ContentType.Application.Json)
                    setBody(AppleS2SEnvelope(signedPayload = signed))
                }
            response.status shouldBe HttpStatusCode.Unauthorized
        }
    }
})
