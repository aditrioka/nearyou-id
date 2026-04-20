package id.nearyou.app.auth.jwt

import java.security.KeyPairGenerator
import java.util.Base64

object TestKeys {
    fun freshEncodedPemPrivateKey(): String {
        val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val keyPair = gen.generateKeyPair()
        val pem =
            "-----BEGIN PRIVATE KEY-----\n" +
                Base64.getEncoder().encodeToString(keyPair.private.encoded) +
                "\n-----END PRIVATE KEY-----\n"
        return Base64.getEncoder().encodeToString(pem.toByteArray())
    }
}
