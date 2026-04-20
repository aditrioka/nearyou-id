package id.nearyou.app.auth.jwt

import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

class RsaKeyLoader(
    encodedPemPrivateKey: String,
    val kid: String = "dev-1",
) {
    val privateKey: RSAPrivateKey
    val publicKey: RSAPublicKey

    init {
        val pemBody = decodeToPemBody(encodedPemPrivateKey)
        val keyBytes = Base64.getDecoder().decode(pemBody)
        val keyFactory = KeyFactory.getInstance("RSA")
        privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(keyBytes)) as RSAPrivateKey

        val rsaCrtKey = privateKey as java.security.interfaces.RSAPrivateCrtKey
        val publicSpec = java.security.spec.RSAPublicKeySpec(rsaCrtKey.modulus, rsaCrtKey.publicExponent)
        publicKey = keyFactory.generatePublic(publicSpec) as RSAPublicKey
    }

    private fun decodeToPemBody(encoded: String): String {
        val decoded = String(Base64.getDecoder().decode(encoded.trim()))
        return decoded
            .lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
            .replace(Regex("\\s"), "")
    }
}
