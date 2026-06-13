package id.nearyou.app.admin.chatredaction

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuthProvider
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import id.nearyou.app.admin.reportqueue.ReportQueueTestSupport
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import java.util.UUID

/**
 * Route-level integration tests for `adminChatRedaction` — the HTTP auth / CSRF /
 * owner-admin-tier gate + gate ORDER, malformed/blank-input handling, and the
 * no-JS 303 / HTMX-fragment dual-mode (`admin-chat-message-redaction`, tasks.md
 * Section 5: 5.1 (HTTP half), 5.2, 5.7). The repository-internal transaction
 * semantics (flags / notifications / audit / idempotency / cap) are covered by
 * [ChatRedactionRepositoryTest]. DB-backed; tagged `database`.
 */
@Tags("database")
class ChatRedactionRouteTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val seededIds = mutableListOf<UUID>()
    val seededAdmins = mutableListOf<UUID>()
    afterEach {
        ReportQueueTestSupport.cleanup(dataSource, seededIds)
        seededIds.clear()
        seededAdmins.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seededAdmins.clear()
    }

    fun seedAdmin(role: String = "owner"): AdminAuthTestSupport.SeededAdmin =
        AdminAuthTestSupport.seedAdmin(dataSource, role = role).also { seededAdmins += it.id }

    fun cookie(token: String) = "${AdminAuthProvider.COOKIE_NAME}=$token"

    fun formBody(vararg pairs: Pair<String, String>): String = pairs.toList().formUrlEncode()

    /** Seed a 2-participant conversation + a message from A; returns the message id. */
    fun seedMessage(content: String = "alamat rumah dia di jl X"): UUID {
        val a = ChatRedactionTestSupport.seedUser(dataSource).also { seededIds += it }
        val b = ChatRedactionTestSupport.seedUser(dataSource).also { seededIds += it }
        val conv = ChatRedactionTestSupport.seedConversation(dataSource, a)
        ChatRedactionTestSupport.addParticipant(dataSource, conv, a, 1)
        ChatRedactionTestSupport.addParticipant(dataSource, conv, b, 2)
        return ChatRedactionTestSupport.seedMessage(dataSource, conv, a, content = content).also { seededIds += it }
    }

    fun isRedacted(msg: UUID): Boolean = ChatRedactionTestSupport.loadRedaction(dataSource, msg).redactedAt != null

    // ===================== 5.1 (HTTP) GET gating + render ======================

    "GET owner renders the redaction page with the message content in context" {
        val admin = seedAdmin("owner")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val msg = seedMessage(content = "alamat rumah dia")
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/chat-messages/$msg") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "alamat rumah dia"
            res.bodyAsText() shouldContain "Redact message"
        }
    }

    "GET moderator → 403, no content" {
        val admin = seedAdmin("moderator")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val msg = seedMessage()
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.get("/admin/chat-messages/$msg") { header(HttpHeaders.Cookie, cookie(token)) }
                .status shouldBe HttpStatusCode.Forbidden
        }
    }

    "GET read_only → 403" {
        val admin = seedAdmin("read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val msg = seedMessage()
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.get("/admin/chat-messages/$msg") { header(HttpHeaders.Cookie, cookie(token)) }
                .status shouldBe HttpStatusCode.Forbidden
        }
    }

    "GET malformed id → 400" {
        val admin = seedAdmin("owner")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.get("/admin/chat-messages/not-a-uuid") { header(HttpHeaders.Cookie, cookie(token)) }
                .status shouldBe HttpStatusCode.BadRequest
        }
    }

    "GET non-existent → 404" {
        val admin = seedAdmin("owner")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.get("/admin/chat-messages/${UUID.randomUUID()}") { header(HttpHeaders.Cookie, cookie(token)) }
                .status shouldBe HttpStatusCode.NotFound
        }
    }

    // ===================== 5.2 POST gating + order =============================

    "POST without a CSRF token → 403 + admin_csrf_violation, no write" {
        val admin = seedAdmin("owner")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val msg = seedMessage()
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/chat-messages/$msg/redact") { header(HttpHeaders.Cookie, cookie(token)) }
                .status shouldBe HttpStatusCode.Forbidden
        }
        isRedacted(msg) shouldBe false
        AdminAuthTestSupport.latestAuditRows(dataSource, admin.id).any { it.actionType == "admin_csrf_violation" } shouldBe true
    }

    "POST moderator with valid CSRF → 403, no write" {
        val admin = seedAdmin("moderator")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val msg = seedMessage()
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/chat-messages/$msg/redact") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("_csrf" to AdminAuthTestSupport.csrfFor(token), "reason" to "x"))
                }
            res.status shouldBe HttpStatusCode.Forbidden
        }
        isRedacted(msg) shouldBe false
    }

    "POST read_only with valid CSRF → 403, no write" {
        val admin = seedAdmin("read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val msg = seedMessage()
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/chat-messages/$msg/redact") {
                header(HttpHeaders.Cookie, cookie(token))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(formBody("_csrf" to AdminAuthTestSupport.csrfFor(token), "reason" to "x"))
            }.status shouldBe HttpStatusCode.Forbidden
        }
        isRedacted(msg) shouldBe false
    }

    "POST moderator + missing CSRF → CSRF path fires first (403 + admin_csrf_violation)" {
        val admin = seedAdmin("moderator")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val msg = seedMessage()
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/chat-messages/$msg/redact") { header(HttpHeaders.Cookie, cookie(token)) }
                .status shouldBe HttpStatusCode.Forbidden
        }
        // the CSRF gate runs BEFORE the role gate, so the violation is recorded
        // (not masked by a silent role-403)
        AdminAuthTestSupport.latestAuditRows(dataSource, admin.id).any { it.actionType == "admin_csrf_violation" } shouldBe true
        isRedacted(msg) shouldBe false
    }

    "POST blank reason → 400, no write" {
        val admin = seedAdmin("owner")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val msg = seedMessage()
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/chat-messages/$msg/redact") {
                header(HttpHeaders.Cookie, cookie(token))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(formBody("_csrf" to AdminAuthTestSupport.csrfFor(token), "reason" to "   "))
            }.status shouldBe HttpStatusCode.BadRequest
        }
        isRedacted(msg) shouldBe false
    }

    // ===================== 5.7 dual-mode response ==============================

    "POST owner no-JS applies → 303 redirect + message redacted" {
        val admin = seedAdmin("owner")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val msg = seedMessage()
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/chat-messages/$msg/redact") {
                    header(HttpHeaders.Cookie, cookie(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("_csrf" to AdminAuthTestSupport.csrfFor(token), "reason" to "doxxing"))
                }
            res.status shouldBe HttpStatusCode.SeeOther
            res.headers[HttpHeaders.Location] shouldBe "/admin/chat-messages/$msg"
        }
        isRedacted(msg) shouldBe true
    }

    "POST owner HTMX applies → 200 fragment showing the redacted placeholder" {
        val admin = seedAdmin("owner")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val msg = seedMessage()
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/chat-messages/$msg/redact") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("HX-Request", "true")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("_csrf" to AdminAuthTestSupport.csrfFor(token), "reason" to "doxxing"))
                }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "Pesan ini telah dihapus oleh moderator."
        }
        isRedacted(msg) shouldBe true
    }

    "POST owner on an already-redacted message → in-band message, idempotent" {
        val admin = seedAdmin("owner")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val msg = seedMessage()
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            // first redaction (HTMX) applies
            client.post("/admin/chat-messages/$msg/redact") {
                header(HttpHeaders.Cookie, cookie(token))
                header("HX-Request", "true")
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(formBody("_csrf" to AdminAuthTestSupport.csrfFor(token), "reason" to "first"))
            }.status shouldBe HttpStatusCode.OK
            // second redaction (HTMX) → in-band "already redacted"
            val res =
                client.post("/admin/chat-messages/$msg/redact") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("HX-Request", "true")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody("_csrf" to AdminAuthTestSupport.csrfFor(token), "reason" to "second"))
                }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "already redacted"
        }
    }
})
