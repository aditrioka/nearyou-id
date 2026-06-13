package id.nearyou.app.chat

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Coverage of the JWT `sub` decode used for own-vs-other alignment (never logged, never rendered). */
@OptIn(ExperimentalEncodingApi::class)
class ViewerIdProviderTest {
    private fun jwtWithPayload(json: String): String {
        // base64url WITHOUT padding (the JWT spec strips it) → the decoder must restore it.
        val payload = Base64.UrlSafe.encode(json.encodeToByteArray()).trimEnd('=')
        return "headerstub.$payload.sigstub"
    }

    @Test
    fun `extracts the sub claim from a well-formed token`() {
        val token = jwtWithPayload("""{"sub":"user-123","role":"authenticated","exp":9999999999}""")
        assertEquals("user-123", jwtSubject(token))
    }

    @Test
    fun `returns null for a malformed token`() {
        assertNull(jwtSubject("not-a-jwt"))
        assertNull(jwtSubject(""))
        assertNull(jwtSubject("only.two"))
    }

    @Test
    fun `returns null when the payload has no sub`() {
        assertNull(jwtSubject(jwtWithPayload("""{"role":"authenticated"}""")))
    }
}
