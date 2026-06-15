package id.nearyou.app.infra.sentry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * mobile-crash-reporting spec § "Crash payloads are scrubbed of PII" — the testable core of the
 * `beforeSend` backstop: coordinates + tokens never leave the device in a scrubbed string.
 */
class PiiScrubberTest {
    @Test
    fun redactsACoordinatePair() {
        val scrubbed = PiiScrubber.scrub("crashed near -6.2088,106.8456 on the map")
        assertFalse(scrubbed.contains("106.8456"), "longitude must be redacted")
        assertFalse(scrubbed.contains("-6.2088"), "latitude must be redacted")
    }

    @Test
    fun redactsBearerTokens() {
        assertEquals("auth=[redacted]", PiiScrubber.scrub("auth=Bearer abc.def_123-XYZ"))
    }

    @Test
    fun redactsJwtBlobs() {
        val scrubbed = PiiScrubber.scrub("token eyJhbGciOiJIUzI1NiJ9.payloadpart.sigpart end")
        assertFalse(scrubbed.contains("eyJ"), "JWT must be redacted")
    }

    @Test
    fun leavesBenignTextUntouched() {
        val benign = "nearby_network_error status=503 screen=timeline"
        assertEquals(benign, PiiScrubber.scrub(benign))
    }
}
