package id.nearyou.app.account

import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.core.domain.oidc.OidcTokenVerifier
import id.nearyou.app.core.domain.oidc.VerifiedClaims
import id.nearyou.app.infra.repo.IdentifierType
import id.nearyou.app.infra.repo.InviterRow
import id.nearyou.app.infra.repo.NewUserRow
import id.nearyou.app.infra.repo.UserRepository
import id.nearyou.app.infra.repo.UserRow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Deferred-guard tests (capability `account-data-export`, task 8.14 — the "Admin Data Export
 * Queue surface is deferred" requirement, scenario "No admin route added here"). Mounts ONLY
 * the route functions this change introduces and pins the negative invariant behaviorally: the
 * change's routes are reachable (a request resolves to a handler/gate, not 404), while
 * representative admin paths under the admin subtree are NOT mounted by this change (404).
 * Mobile (`:mobile:app`) un-modification is a module-boundary fact, not a backend route.
 */
class DataExportRouteEnumerationTest : StringSpec({

    val noopVerifier =
        object : OidcTokenVerifier {
            override suspend fun verify(token: String): VerifiedClaims =
                VerifiedClaims(sub = "x", aud = "x", exp = Instant.now(), iat = null)
        }

    // A real JWT auth provider (no users → every request 401s) so the authenticate(AUTH_PROVIDER_USER)
    // block in accountDataExportRoutes resolves; without it the route throws MissingApplicationPlugin.
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-route-enum")

    suspend fun withChangeRoutes(block: suspend ApplicationTestBuilder.() -> Unit) {
        testApplication {
            application {
                install(Authentication) { configureUserJwt(keys, EmptyUserRepository, Instant::now) }
                // Mount EXACTLY the route functions this change adds — nothing else.
                accountDataExportRoutes(
                    DataExportService(DataExportRequestRepository(StubDataSource), id.nearyou.app.infra.r2.NoOpObjectStore),
                )
                routing {
                    route("/internal") {
                        dataExportWorkerRoute(stubWorker(), noopVerifier)
                    }
                }
            }
            block()
        }
    }

    "8.14 the change's three routes are mounted (resolve to a handler/gate, not 404)" {
        withChangeRoutes {
            // The point of THIS test is route existence: each of the three resolves to a
            // handler/auth-gate (NOT 404). The precise 401 behavior is covered exhaustively in
            // AccountDataExportRoutesTest (POST/GET) and DataExportWorkerRouteTest (worker OIDC).
            (client.post("/api/v1/account/export").status == HttpStatusCode.NotFound) shouldBe false
            (client.get("/api/v1/account/export").status == HttpStatusCode.NotFound) shouldBe false
            (client.post("/internal/data-export-worker").status == HttpStatusCode.NotFound) shouldBe false
        }
    }

    "8.14 NO admin route is mounted by this change — representative /admin/* paths 404" {
        withChangeRoutes {
            // The deferred admin Data Export Queue surface is NOT part of this change.
            listOf(
                "/admin/data-exports",
                "/admin/data-export-queue",
                "/admin/exports",
                "/admin/login",
            ).forEach { path ->
                client.get(path).status shouldBe HttpStatusCode.NotFound
                client.post(path).status shouldBe HttpStatusCode.NotFound
            }
        }
    }

    "8.14 the export routes do NOT live under /admin (the admin-prefixed twin 404s)" {
        withChangeRoutes {
            client.post("/admin/api/v1/account/export").status shouldBe HttpStatusCode.NotFound
            client.post("/admin/internal/data-export-worker").status shouldBe HttpStatusCode.NotFound
        }
    }
})

/** A UserRepository with no users — every JWT principal resolution fails → requests 401, never 404. */
private object EmptyUserRepository : UserRepository {
    override fun findById(id: UUID): UserRow? = null

    override fun findByGoogleIdHash(hash: String): UserRow? = null

    override fun findByAppleIdHash(hash: String): UserRow? = null

    override fun incrementTokenVersion(id: UUID): Int = error("unused")

    override fun setAppleRelayEmail(
        appleIdHash: String,
        enabled: Boolean,
    ): Int = error("unused")

    override fun existsByProviderHash(
        conn: java.sql.Connection,
        hash: String,
        type: IdentifierType,
    ): Boolean = error("unused")

    override fun existsByInviteCodePrefix(prefix: String): Boolean = error("unused")

    override fun findInviterByInviteCodePrefix(prefix: String): InviterRow? = error("unused")

    override fun create(
        conn: java.sql.Connection,
        row: NewUserRow,
    ): UUID = error("unused")

    override fun findByIdForUpdate(
        conn: java.sql.Connection,
        id: UUID,
    ): UserRow? = error("unused")

    override fun usernameExists(
        conn: java.sql.Connection,
        lowercaseCandidate: String,
    ): Boolean = error("unused")

    override fun updateUsername(
        conn: java.sql.Connection,
        id: UUID,
        newUsername: String,
    ) = error("unused")
}

private fun stubWorker(): DataExportWorker =
    DataExportWorker(
        dataSource = StubDataSource,
        requests = DataExportRequestRepository(StubDataSource),
        gather = DataExportGatherRepository(StubDataSource, PeerIdHasher.fromSecret(null)),
        archiveService = DataExportArchiveService(),
        objectStore = id.nearyou.app.infra.r2.NoOpObjectStore,
        emailSender = id.nearyou.app.infra.resend.NoOpEmailSender,
        notificationEmitter =
            object : id.nearyou.app.notifications.NotificationEmitter {
                override fun emit(
                    conn: java.sql.Connection,
                    recipientId: UUID,
                    actorUserId: UUID?,
                    type: id.nearyou.data.repository.NotificationType,
                    targetType: String?,
                    targetId: UUID?,
                    bodyData: kotlinx.serialization.json.JsonObject,
                ): UUID? = null
            },
    )

/** Routes are read at config time; the DataSource is never touched. */
private object StubDataSource : DataSource {
    override fun getConnection(): java.sql.Connection = error("not used")

    override fun getConnection(
        username: String?,
        password: String?,
    ): java.sql.Connection = error("not used")

    override fun getLogWriter(): java.io.PrintWriter = error("not used")

    override fun setLogWriter(out: java.io.PrintWriter?) = error("not used")

    override fun setLoginTimeout(seconds: Int) = error("not used")

    override fun getLoginTimeout(): Int = error("not used")

    override fun getParentLogger(): java.util.logging.Logger = error("not used")

    override fun <T : Any?> unwrap(iface: Class<T>?): T = error("not used")

    override fun isWrapperFor(iface: Class<*>?): Boolean = error("not used")
}
