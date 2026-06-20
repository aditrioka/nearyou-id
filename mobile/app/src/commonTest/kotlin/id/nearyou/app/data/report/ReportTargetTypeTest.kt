package id.nearyou.app.data.report

import kotlin.test.Test
import kotlin.test.assertEquals

class ReportTargetTypeTest {
    @Test
    fun `each target type maps to its shipped wire value`() {
        assertEquals("user", ReportTargetType.USER.wire)
        assertEquals("post", ReportTargetType.POST.wire)
        assertEquals("reply", ReportTargetType.REPLY.wire)
    }

    @Test
    fun `exactly the three surfaced target types exist - no chat_message`() {
        val wires = ReportTargetType.entries.map { it.wire }.toSet()
        assertEquals(setOf("user", "post", "reply"), wires)
        // chat_message is a deferred chat-surface change — not surfaced by this capability.
        assertEquals(false, wires.contains("chat_message"))
    }
}
