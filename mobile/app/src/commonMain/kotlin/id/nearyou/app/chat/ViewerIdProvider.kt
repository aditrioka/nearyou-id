package id.nearyou.app.chat

import id.nearyou.app.auth.TokenStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Supplies the signed-in viewer's own user id — the value chat messages carry as `sender_id`, used by
 * the thread merge ONLY to decide own-vs-other bubble alignment (never rendered, never logged). The
 * production binding [TokenViewerIdProvider] reads it from the access-token JWT `sub` claim (the backend
 * sets `sub = principal.userId`, the same id used for `sender_id` and the realtime token). A seam so the
 * ViewModel test can inject a fixed id.
 */
fun interface ViewerIdProvider {
    /** The viewer's own user id, or null if no valid token is present (treated as "no own messages"). */
    suspend fun viewerId(): String?
}

/** Reads the viewer id from the access token's JWT `sub` claim. The token is never logged. */
class TokenViewerIdProvider(
    private val tokenStore: TokenStore,
) : ViewerIdProvider {
    override suspend fun viewerId(): String? = tokenStore.read()?.accessToken?.let(::jwtSubject)
}

/**
 * Extracts the `sub` claim from an unverified JWT (signature NOT checked — the server is authoritative;
 * this is only the viewer's OWN id for bubble alignment). commonMain-safe: splits on `.`, base64url-
 * decodes the payload (padding restored), parses the JSON, and reads `sub`. Returns null on any
 * malformation. Never throws, never logs the token.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun jwtSubject(jwt: String): String? =
    try {
        val parts = jwt.split('.')
        if (parts.size < 2) {
            null
        } else {
            val payload = parts[1]
            // Restore base64url padding the JWT spec strips (length to a multiple of 4).
            val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
            val json = Base64.UrlSafe.decode(padded).decodeToString()
            Json.parseToJsonElement(json).jsonObject["sub"]?.jsonPrimitive?.content
        }
    } catch (_: Throwable) {
        null
    }
