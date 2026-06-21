package id.nearyou.app.data.report

import kotlin.test.Test
import kotlin.test.assertEquals

class ReportReasonCategoryTest {
    @Test
    fun `each user-facing category maps to its shipped wire value`() {
        assertEquals("spam", ReportReasonCategory.SPAM.toWire())
        assertEquals("hate_speech_sara", ReportReasonCategory.HATE_SPEECH_SARA.toWire())
        assertEquals("harassment", ReportReasonCategory.HARASSMENT.toWire())
        assertEquals("adult_content", ReportReasonCategory.ADULT_CONTENT.toWire())
        assertEquals("misinformation", ReportReasonCategory.MISINFORMATION.toWire())
        assertEquals("other", ReportReasonCategory.OTHER.toWire())
    }

    @Test
    fun `exactly the six user-facing categories exist - no self_harm or csam_suspected`() {
        val wireValues = ReportReasonCategory.entries.map { it.wireValue }.toSet()
        assertEquals(
            setOf("spam", "hate_speech_sara", "harassment", "adult_content", "misinformation", "other"),
            wireValues,
        )
        // The internal/automated classifications are NOT user-pickable.
        assertEquals(false, wireValues.contains("self_harm"))
        assertEquals(false, wireValues.contains("csam_suspected"))
    }
}
