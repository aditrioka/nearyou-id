package id.nearyou.app.account

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkException
import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date as UtilDate

/*
 * Shared OIDC-gate fixtures for this package's internal-worker route tests
 * (DataExportWorkerRouteTest, AccountHardDeleteWorkerRouteTest): a static-JWK verifier
 * keypair, a token signer, and a DataSource the rejected paths never touch.
 */

internal const val TEST_AUDIENCE = "https://api-staging.nearyou.id"
internal const val TEST_KID = "test-route-kid"

internal fun rsaKeypair(): Pair<RSAPublicKey, RSAPrivateKey> {
    val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
    val kp = gen.generateKeyPair()
    return kp.public as RSAPublicKey to kp.private as RSAPrivateKey
}

internal class FakeJwk(keyId: String, private val pubKey: RSAPublicKey) :
    Jwk(keyId, "RSA", "RS256", null, emptyList(), null, null, null, null) {
    override fun getPublicKey(): java.security.PublicKey = pubKey
}

internal class StaticJwkProvider(private val mapping: Map<String, Jwk>) : JwkProvider {
    override fun get(keyId: String): Jwk = mapping[keyId] ?: throw JwkException("kid not found: $keyId")
}

internal fun signedJwt(
    privateKey: RSAPrivateKey,
    publicKey: RSAPublicKey,
    audience: String = TEST_AUDIENCE,
    kid: String = TEST_KID,
    expiresAt: Instant = Instant.now().plus(1, ChronoUnit.HOURS),
    subject: String = "scheduler@nearyou-staging.iam.gserviceaccount.com",
): String =
    JWT.create()
        .withKeyId(kid)
        .withSubject(subject)
        .withAudience(audience)
        .withIssuedAt(UtilDate.from(Instant.now()))
        .withExpiresAt(UtilDate.from(expiresAt))
        .sign(Algorithm.RSA256(publicKey, privateKey))

/** Worker ctor needs a DataSource; the 401 paths never touch it. */
internal object UnusedDataSource : javax.sql.DataSource {
    override fun getConnection(): java.sql.Connection = error("not used")

    override fun getConnection(
        username: String?,
        password: String?,
    ): java.sql.Connection = error("not used")

    override fun getLogWriter(): java.io.PrintWriter = error("not used")

    override fun setLogWriter(out: java.io.PrintWriter?) = error("not used")

    override fun setLoginTimeout(seconds: Int) = error("not used")

    override fun getLoginTimeout(): Int = error("not used")

    override fun getParentLogger(): java.util.logging.Logger = error("not used")

    override fun <T : Any?> unwrap(iface: Class<T>?): T = error("not used")

    override fun isWrapperFor(iface: Class<*>?): Boolean = error("not used")
}
