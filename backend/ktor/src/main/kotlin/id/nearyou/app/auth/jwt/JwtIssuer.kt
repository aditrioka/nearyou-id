package id.nearyou.app.auth.jwt

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.time.Instant
import java.util.Date
import java.util.UUID

const val ACCESS_TOKEN_TTL_SECONDS = 900L

// content-moderation-appeal: the limited appeal token (auth-signin) is short-lived —
// it only needs to survive long enough for the actioned user to file one appeal.
const val APPEAL_TOKEN_TTL_SECONDS = 3600L

// content-moderation-appeal: the `scope` claim that marks a token as the limited
// appeal credential. The user realm REJECTS any token carrying this scope (it is
// confined to the ban-exempt appeal realm); the appeal realm accepts any scope.
const val APPEAL_TOKEN_SCOPE = "appeal"

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

    /**
     * Issues the limited-scope appeal token returned in the banned/suspended sign-in
     * 403 (auth-signin). It carries the current `token_version` (so it is revoked by a
     * later bump just like a normal token) and `scope = "appeal"`, which the user realm
     * rejects — the token is usable ONLY on the ban-exempt appeal realm. No
     * `refresh_tokens` row is created; this is the user's sole credential to contest
     * the action.
     */
    fun issueAppealToken(
        userId: UUID,
        tokenVersion: Int,
    ): String {
        val now = nowProvider()
        return JWT.create()
            .withKeyId(keys.kid)
            .withSubject(userId.toString())
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plusSeconds(APPEAL_TOKEN_TTL_SECONDS)))
            .withClaim("token_version", tokenVersion)
            .withClaim("scope", APPEAL_TOKEN_SCOPE)
            .sign(algorithm)
    }
}
