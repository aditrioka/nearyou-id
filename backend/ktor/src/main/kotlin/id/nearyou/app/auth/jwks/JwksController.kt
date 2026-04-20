package id.nearyou.app.auth.jwks

import id.nearyou.app.auth.jwt.RsaKeyLoader
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import java.util.Base64

@Serializable
data class JwkResponse(val keys: List<Jwk>)

@Serializable
data class Jwk(
    val kty: String,
    val kid: String,
    val use: String,
    val alg: String,
    val n: String,
    val e: String,
)

fun Application.jwksRoutes() {
    val keys by inject<RsaKeyLoader>()
    val urlEncoder = Base64.getUrlEncoder().withoutPadding()
    val jwk =
        Jwk(
            kty = "RSA",
            kid = keys.kid,
            use = "sig",
            alg = "RS256",
            n = urlEncoder.encodeToString(keys.publicKey.modulus.toUnsignedBytes()),
            e = urlEncoder.encodeToString(keys.publicKey.publicExponent.toUnsignedBytes()),
        )
    val response = JwkResponse(keys = listOf(jwk))

    routing {
        get("/.well-known/jwks.json") {
            call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
            call.respond(response)
        }
    }
}

private fun java.math.BigInteger.toUnsignedBytes(): ByteArray {
    val raw = toByteArray()
    return if (raw.isNotEmpty() && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
}
