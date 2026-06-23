package id.nearyou.app.data.report

import kotlin.test.Test
import kotlin.test.assertEquals

class ReportTargetTypeTest {
    @Test
    fun `each target type maps to its shipped wire value`() {
        assertEquals("user", ReportTargetType.USER.wire)
        assertEquals("post", ReportTargetType.POST.wire)
        assertEquals("reply", ReportTargetType.REPLY.wire)
        assertEquals("chat_message", ReportTargetType.CHAT_MESSAGE.wire)
    }

    @Test
    fun `exactly the four shipped target types exist including chat_message`() {
        val wires = ReportTargetType.entries.map { it.wire }.toSet()
        assertEquals(setOf("user", "post", "reply", "chat_message"), wires)
        // chat_message is delivered by mobile-chat-message-report (the chat-thread long-press "Laporkan").
        assertEquals(true, wires.contains("chat_message"))
    }
}
