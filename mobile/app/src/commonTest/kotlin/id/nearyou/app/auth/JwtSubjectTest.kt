package id.nearyou.app.auth

import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Builds a JWT whose payload is [payloadJson], base64url-encoded WITHOUT padding (the RFC 7515 shape
 *  `decodeJwtSubject` must accept). Header + signature are opaque (the decoder reads only the payload). */
private fun jwt(payloadJson: String): String {
    val segment =
        Base64
            .encode(payloadJson.encodeToByteArray())
            .replace('+', '-')
            .replace('/', '_')
            .trimEnd('=')
    return "aGVhZGVy.$segment.c2ln"
}

class JwtSubjectTest {
    @Test
    fun `decodes the sub claim from a well-formed token`() {
        assertEquals("user-abc-123", decodeJwtSubject(jwt("""{"sub":"user-abc-123"}""")))
    }

    @Test
    fun `decodes sub even with other claims present - ignores unknown keys`() {
        val token = jwt("""{"iss":"nearyou","sub":"11111111-2222-3333-4444-555555555555","exp":9999999999}""")
        assertEquals("11111111-2222-3333-4444-555555555555", decodeJwtSubject(token))
    }

    @Test
    fun `returns null when the sub claim is absent`() {
        assertNull(decodeJwtSubject(jwt("""{"iss":"nearyou","exp":1}""")))
    }

    @Test
    fun `returns null when the sub claim is blank`() {
        assertNull(decodeJwtSubject(jwt("""{"sub":""}""")))
    }

    @Test
    fun `returns null for a token without three segments`() {
        assertNull(decodeJwtSubject("not-a-jwt"))
        assertNull(decodeJwtSubject("only.two"))
        assertNull(decodeJwtSubject(""))
    }

    @Test
    fun `returns null when the payload is not valid base64url or JSON`() {
        assertNull(decodeJwtSubject("header.!!!not-base64!!!.sig"))
        // Valid base64url that decodes to non-JSON bytes → null, not a throw.
        val notJson = Base64.encode("plain text".encodeToByteArray()).replace('+', '-').replace('/', '_').trimEnd('=')
        assertNull(decodeJwtSubject("header.$notJson.sig"))
    }

    @Test
    fun `TokenStoreSelfUserIdProvider decodes the stored access token subject`() =
        runTest {
            val store = InMemoryTokenStore()
            store.write(
                TokenPair(
                    accessToken = jwt("""{"sub":"self-user-9"}"""),
                    refreshToken = "r",
                    accessExpiresAtEpochMillis = 0L,
                ),
            )
            assertEquals("self-user-9", TokenStoreSelfUserIdProvider(store).selfUserId())
        }

    @Test
    fun `TokenStoreSelfUserIdProvider returns null when no token is stored`() =
        runTest {
            assertNull(TokenStoreSelfUserIdProvider(InMemoryTokenStore()).selfUserId())
        }
}
