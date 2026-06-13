package id.nearyou.app.admin.chatredaction

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import id.nearyou.app.admin.moderation.UserModerationTestSupport
import id.nearyou.app.admin.ratelimit.DestructiveActionRateLimiter
import id.nearyou.app.admin.reportqueue.ReportQueueTestSupport
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Repository-level integration tests for `ChatRedactionRepository` — the
 * idempotent atomic redaction transaction (flags + participant notifications +
 * audit row), the ±2-context read, the destructive-cap gate, and the no-op /
 * not-found outcomes (`admin-chat-message-redaction`, tasks.md Section 5: 5.3,
 * 5.4, 5.5, plus the read half of 5.1). DB-backed; tagged `database`. Calls the
 * repository directly (the HTTP auth / CSRF / role-gate wiring is exercised by
 * [ChatRedactionRouteTest]).
 */
@Tags("database")
class ChatRedactionRepositoryTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val auditLogger = AdminAuditLogger(dataSource)
    val rateLimiter = DestructiveActionRateLimiter(dataSource)
    val repo = ChatRedactionRepository(dataSource, auditLogger, rateLimiter)

    val seededIds = mutableListOf<UUID>()
    val seededAdmins = mutableListOf<UUID>()
    afterEach {
        ReportQueueTestSupport.cleanup(dataSource, seededIds)
        seededIds.clear()
        seededAdmins.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seededAdmins.clear()
    }

    fun seedAdmin(): UUID = AdminAuthTestSupport.seedAdmin(dataSource, role = "owner").id.also { seededAdmins += it }

    fun seedUser(): UUID = ChatRedactionTestSupport.seedUser(dataSource).also { seededIds += it }

    val ip = "203.0.113.7"
    val ua = "chat-redaction-test/1.0"

    /** Seed a 2-active-participant conversation (A=slot1, B=slot2) + a message
     *  from A; returns (msgId, convId, A, B). [leftB] soft-leaves B. */
    fun seedConvWithMessage(leftB: Boolean = false): Quad {
        val a = seedUser()
        val b = seedUser()
        val conv = ChatRedactionTestSupport.seedConversation(dataSource, a)
        ChatRedactionTestSupport.addParticipant(dataSource, conv, a, 1)
        ChatRedactionTestSupport.addParticipant(dataSource, conv, b, 2, left = leftB)
        val msg = ChatRedactionTestSupport.seedMessage(dataSource, conv, a, content = "alamat rumah dia di jl X")
        seededIds += msg
        return Quad(msg, conv, a, b)
    }

    fun redactedNotifs(userId: UUID) =
        UserModerationTestSupport.notificationsForUser(dataSource, userId).filter { it.type == "chat_message_redacted" }

    fun chatRedactionAudit(messageId: UUID) = UserModerationTestSupport.auditRowsForTarget(dataSource, messageId, "admin_chat_redaction")

    // ===================== 5.3 redaction write (repo) ==========================

    "first redaction sets redacted_at/by/reason + outcome Applied" {
        val admin = seedAdmin()
        val (msg, _, _, _) = seedConvWithMessage()

        repo.redact(msg, admin, "doxxing — home address", ip, ua) shouldBe RedactionOutcome.Applied

        val st = ChatRedactionTestSupport.loadRedaction(dataSource, msg)
        st.redactedAt.shouldNotBeNull()
        st.redactedBy shouldBe admin
        st.redactionReason shouldBe "doxxing — home address"
    }

    "re-redaction with a different reason is a no-op that preserves the original trio" {
        val a1 = seedAdmin()
        val a2 = seedAdmin()
        val (msg, _, _, _) = seedConvWithMessage()
        repo.redact(msg, a1, "first reason", ip, ua) shouldBe RedactionOutcome.Applied
        val first = ChatRedactionTestSupport.loadRedaction(dataSource, msg)

        repo.redact(msg, a2, "DIFFERENT reason", ip, ua) shouldBe RedactionOutcome.AlreadyRedacted

        val after = ChatRedactionTestSupport.loadRedaction(dataSource, msg)
        after.redactedBy shouldBe a1
        after.redactedAt shouldBe first.redactedAt
        after.redactionReason shouldBe "first reason"
        // no second audit row, no second notification set
        chatRedactionAudit(msg) shouldHaveSize 1
    }

    "redaction of a non-existent message → NotFound" {
        val admin = seedAdmin()
        repo.redact(UUID.randomUUID(), admin, "x", ip, ua) shouldBe RedactionOutcome.NotFound
    }

    "embedded-post-only message (content NULL) is redactable; audit before_state captures it" {
        val admin = seedAdmin()
        val a = seedUser()
        val b = seedUser()
        val conv = ChatRedactionTestSupport.seedConversation(dataSource, a)
        ChatRedactionTestSupport.addParticipant(dataSource, conv, a, 1)
        ChatRedactionTestSupport.addParticipant(dataSource, conv, b, 2)
        val msg = ChatRedactionTestSupport.seedMessage(dataSource, conv, a, embeddedOnly = true).also { seededIds += it }

        repo.redact(msg, admin, "embed violation", ip, ua) shouldBe RedactionOutcome.Applied

        ChatRedactionTestSupport.loadRedaction(dataSource, msg).redactedAt.shouldNotBeNull()
        val audit = chatRedactionAudit(msg).single()
        audit.beforeStateJson.shouldNotBeNull()
        audit.beforeStateJson shouldContain "had_embed"
    }

    // ===================== 5.4 participant notifications =======================

    "both active participants are notified; body_data = {conversation_id} only; actor NULL; target_type 'message'" {
        val admin = seedAdmin()
        val (msg, conv, a, b) = seedConvWithMessage()

        repo.redact(msg, admin, "doxxing", ip, ua) shouldBe RedactionOutcome.Applied

        for (uid in listOf(a, b)) {
            val notifs = redactedNotifs(uid)
            notifs shouldHaveSize 1
            notifs[0].actorUserId.shouldBeNull()
            val body = Json.parseToJsonElement(notifs[0].bodyDataJson!!).jsonObject
            body["conversation_id"]!!.jsonPrimitive.content shouldBe conv.toString()
            body.keys shouldBe setOf("conversation_id")
            notifs[0].bodyDataJson!! shouldNotContain "message_id"
            notifs[0].bodyDataJson!! shouldNotContain "alamat"
        }
        // target_type 'message' + target_id = the message id (canonical addressing)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT target_type, target_id FROM notifications WHERE type = 'chat_message_redacted' AND user_id = ?",
            ).use { ps ->
                ps.setObject(1, a)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getString("target_type") shouldBe "message"
                    rs.getString("target_id") shouldBe msg.toString()
                }
            }
        }
    }

    "a user_blocks row between participants does NOT suppress the redaction notice" {
        val admin = seedAdmin()
        val (msg, _, a, b) = seedConvWithMessage()
        dataSource.connection.use { conn ->
            conn.prepareStatement("INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (?, ?)").use { ps ->
                ps.setObject(1, a)
                ps.setObject(2, b)
                ps.executeUpdate()
            }
        }

        repo.redact(msg, admin, "doxxing", ip, ua) shouldBe RedactionOutcome.Applied

        redactedNotifs(a) shouldHaveSize 1
        redactedNotifs(b) shouldHaveSize 1
    }

    "a soft-left participant is not notified" {
        val admin = seedAdmin()
        val (msg, _, a, b) = seedConvWithMessage(leftB = true)

        repo.redact(msg, admin, "doxxing", ip, ua) shouldBe RedactionOutcome.Applied

        redactedNotifs(a) shouldHaveSize 1
        redactedNotifs(b).shouldHaveSize(0)
    }

    "a no-op re-redaction writes no new notification" {
        val admin = seedAdmin()
        val (msg, _, a, _) = seedConvWithMessage()
        repo.redact(msg, admin, "first", ip, ua) shouldBe RedactionOutcome.Applied
        repo.redact(msg, admin, "second", ip, ua) shouldBe RedactionOutcome.AlreadyRedacted
        redactedNotifs(a) shouldHaveSize 1
    }

    // ===================== 5.5 audit row =======================================

    "applied redaction writes exactly one admin_chat_redaction audit row" {
        val admin = seedAdmin()
        val (msg, _, _, _) = seedConvWithMessage()

        repo.redact(msg, admin, "doxxing reason", ip, ua) shouldBe RedactionOutcome.Applied

        val rows = chatRedactionAudit(msg)
        rows shouldHaveSize 1
        rows[0].targetType shouldBe "chat_message"
        rows[0].targetId shouldBe msg.toString()
        rows[0].reason shouldBe "doxxing reason"
        rows[0].ip shouldBe ip
    }

    // ===================== 5.6 destructive cap =================================

    "redaction at/over the destructive cap is rejected with no write" {
        val admin = seedAdmin()
        val (msg, _, a, _) = seedConvWithMessage()
        ChatRedactionTestSupport.seedRedactionAuditRows(dataSource, admin, DestructiveActionRateLimiter.DESTRUCTIVE_ACTION_CAP)

        repo.redact(msg, admin, "doxxing", ip, ua) shouldBe RedactionOutcome.RateLimited

        ChatRedactionTestSupport.loadRedaction(dataSource, msg).redactedAt.shouldBeNull()
        redactedNotifs(a).shouldHaveSize(0)
        // only the 20 pre-seeded rows; no new admin_chat_redaction targeting this message
        chatRedactionAudit(msg).shouldHaveSize(0)
    }

    // ===================== 5.1 (read) ±2 context window ========================

    "loadRedactionPage returns the target plus exactly ±2 context messages" {
        seedAdmin()
        val a = seedUser()
        val b = seedUser()
        val conv = ChatRedactionTestSupport.seedConversation(dataSource, a)
        ChatRedactionTestSupport.addParticipant(dataSource, conv, a, 1)
        ChatRedactionTestSupport.addParticipant(dataSource, conv, b, 2)
        val base = Instant.parse("2026-06-11T08:00:00Z")
        val ids =
            (0..6).map { i ->
                ChatRedactionTestSupport.seedMessage(dataSource, conv, a, content = "m$i", createdAt = base.plusSeconds(i * 60L))
                    .also { seededIds += it }
            }
        val target = ids[3]

        val page = repo.loadRedactionPage(target)
        page.shouldNotBeNull()
        page.context shouldHaveSize 5
        page.context.single { it.isTarget }.id shouldBe target
        page.context.map { it.id } shouldBe listOf(ids[1], ids[2], ids[3], ids[4], ids[5])
    }

    "loadRedactionPage clamps the window at a conversation boundary" {
        seedAdmin()
        val a = seedUser()
        val conv = ChatRedactionTestSupport.seedConversation(dataSource, a)
        ChatRedactionTestSupport.addParticipant(dataSource, conv, a, 1)
        val base = Instant.parse("2026-06-11T08:00:00Z")
        val first = ChatRedactionTestSupport.seedMessage(dataSource, conv, a, content = "first", createdAt = base).also { seededIds += it }
        val second =
            ChatRedactionTestSupport.seedMessage(dataSource, conv, a, content = "second", createdAt = base.plusSeconds(60)).also {
                seededIds += it
            }

        val page = repo.loadRedactionPage(first)
        page.shouldNotBeNull()
        // target is first → 0 earlier + 1 later
        page.context.map { it.id } shouldBe listOf(first, second)
        page.context.first { it.isTarget }.id shouldBe first
    }

    "loadRedactionPage of a non-existent message → null" {
        repo.loadRedactionPage(UUID.randomUUID()).shouldBeNull()
    }

    // ===================== concurrency + embedded-neighbor edge cases ==========

    "concurrent double-redaction yields exactly one Applied + one AlreadyRedacted" {
        val admin = seedAdmin()
        val (msg, _, a, _) = seedConvWithMessage()
        val outcomes = CopyOnWriteArrayList<RedactionOutcome>()
        (1..2).map {
            thread { outcomes.add(repo.redact(msg, admin, "race", ip, ua)) }
        }.forEach { it.join() }

        // the FOR UPDATE lock + WHERE redacted_at IS NULL guard serialize the race
        outcomes.count { it == RedactionOutcome.Applied } shouldBe 1
        outcomes.count { it == RedactionOutcome.AlreadyRedacted } shouldBe 1
        // exactly one effect set: one audit row, one notification per active participant
        chatRedactionAudit(msg) shouldHaveSize 1
        redactedNotifs(a) shouldHaveSize 1
    }

    "loadRedactionPage renders an embedded-only neighbor (content NULL) without error" {
        val a = seedUser()
        val conv = ChatRedactionTestSupport.seedConversation(dataSource, a)
        ChatRedactionTestSupport.addParticipant(dataSource, conv, a, 1)
        val base = Instant.parse("2026-06-11T08:00:00Z")
        val embed =
            ChatRedactionTestSupport.seedMessage(dataSource, conv, a, embeddedOnly = true, createdAt = base).also { seededIds += it }
        val target =
            ChatRedactionTestSupport.seedMessage(dataSource, conv, a, content = "target text", createdAt = base.plusSeconds(60)).also {
                seededIds += it
            }

        val page = repo.loadRedactionPage(target)
        page.shouldNotBeNull()
        val neighbor = page.context.first { it.id == embed }
        neighbor.isTarget shouldBe false
        neighbor.content.shouldBeNull()
        neighbor.hasEmbed shouldBe true
    }
})

/** Local 4-tuple to thread (msg, conv, A, B) out of the seed helper. */
private data class Quad(val msg: UUID, val conv: UUID, val a: UUID, val b: UUID)
