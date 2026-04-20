package id.nearyou.app.auth.jwks

import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.math.BigInteger
import java.util.Base64
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation

class JwksControllerTest : StringSpec({
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-jwks")

    "JWKS body lists the configured key with matching modulus and exponent" {
        testApplication {
            application {
                install(Koin) {
                    modules(module { single { keys } })
                }
                install(ContentNegotiation) { json() }
                jwksRoutes()
            }
            val client =
                createClient {
                    install(ClientContentNegotiation) { json() }
                }

            val response = client.get("/.well-known/jwks.json")
            response.status shouldBe HttpStatusCode.OK
            response.headers[HttpHeaders.CacheControl] shouldBe "public, max-age=3600"

            val body: JwkResponse = response.body()
            body.keys.size shouldBe 1
            val jwk = body.keys[0]
            jwk.kid shouldBe "test-jwks"
            jwk.alg shouldBe "RS256"
            jwk.kty shouldBe "RSA"
            jwk.use shouldBe "sig"

            val urlDecoder = Base64.getUrlDecoder()
            BigInteger(1, urlDecoder.decode(jwk.n)) shouldBe keys.publicKey.modulus
            BigInteger(1, urlDecoder.decode(jwk.e)) shouldBe keys.publicKey.publicExponent
        }
    }
})
