package id.nearyou.app.auth.jwt

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.time.Instant
import java.util.Date
import java.util.UUID

const val ACCESS_TOKEN_TTL_SECONDS = 900L

class JwtIssuer(
    private val keys: RsaKeyLoader,
    private val nowProvider: () -> Instant = Instant::now,
) {
    private val algorithm: Algorithm = Algorithm.RSA256(keys.publicKey, keys.privateKey)

    fun issueAccessToken(
        userId: UUID,
        tokenVersion: Int,
    ): String {
        val now = nowProvider()
        return JWT.create()
            .withKeyId(keys.kid)
            .withSubject(userId.toString())
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plusSeconds(ACCESS_TOKEN_TTL_SECONDS)))
            .withClaim("token_version", tokenVersion)
            .sign(algorithm)
    }

    fun verifier() = JWT.require(algorithm).build()
}
