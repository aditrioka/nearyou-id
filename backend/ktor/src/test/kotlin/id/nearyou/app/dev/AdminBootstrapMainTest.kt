package id.nearyou.app.dev

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.apache.commons.codec.binary.Base32
import java.security.SecureRandom
import java.util.UUID

/**
 * Secret-leakage invariant tests for the admin-bootstrap CLI (task 1.9).
 *
 * Drives the testable core [buildAdminBootstrap] + [operatorOutput] with
 * fixture inputs and asserts:
 *  (a) the operator output contains the SQL INSERT + the DO-NOT-SAVE warning,
 *  (b) the plaintext password never appears (only its Argon2id hash),
 *  (c) the AES key never appears,
 *  (d) the base32 TOTP secret appears exactly ONCE (the intended
 *      authenticator-app provisioning line) — not duplicated elsewhere,
 *  (e) the raw TOTP secret bytes never appear as hex/base64 (only the
 *      base32 form is surfaced).
 *
 * No DB, no subprocess — the core is pure, so these run in PR-time CI.
 */
class AdminBootstrapMainTest : StringSpec({

    val secureRandom = SecureRandom()
    val base32 = Base32()

    fun fixtureKey(): ByteArray = ByteArray(32) { (it + 3).toByte() }

    fun fixtureSecret(): ByteArray = ByteArray(20) { (it * 7 + 1).toByte() }

    "operator output contains the SQL INSERT and the DO-NOT-SAVE warning" {
        val out =
            operatorOutput(
                buildAdminBootstrap(
                    email = "oka@nearyou.id",
                    displayName = "Oka",
                    role = "owner",
                    password = "fixture-password",
                    aesKey = fixtureKey(),
                    totpSecret = fixtureSecret(),
                    adminId = UUID.randomUUID(),
                ),
            )
        out shouldContain "INSERT INTO admin_users"
        out shouldContain DO_NOT_SAVE_WARNING
        out shouldContain "decode('" // base64 encrypted secret
    }

    "operator output NEVER contains the plaintext password" {
        val password = "P@ssw0rd!SuperSecret-bootstrap"
        val out =
            operatorOutput(
                buildAdminBootstrap(
                    email = "oka@nearyou.id",
                    displayName = "Oka",
                    role = "owner",
                    password = password,
                    aesKey = fixtureKey(),
                    totpSecret = fixtureSecret(),
                    adminId = UUID.randomUUID(),
                ),
            )
        out shouldNotContain password
        // The Argon2id hash IS present (that's the stored credential).
        out shouldContain "\$argon2id\$"
    }

    "operator output NEVER contains the AES key (base64 or raw)" {
        val key = fixtureKey()
        val keyBase64 = java.util.Base64.getEncoder().encodeToString(key)
        val out =
            operatorOutput(
                buildAdminBootstrap(
                    email = "oka@nearyou.id",
                    displayName = "Oka",
                    role = "owner",
                    password = "fixture-password",
                    aesKey = key,
                    totpSecret = fixtureSecret(),
                    adminId = UUID.randomUUID(),
                ),
            )
        out shouldNotContain keyBase64
    }

    "base32 TOTP secret appears exactly once in the operator output" {
        val secret = fixtureSecret()
        val base32Secret = base32.encodeAsString(secret)
        val out =
            operatorOutput(
                buildAdminBootstrap(
                    email = "oka@nearyou.id",
                    displayName = "Oka",
                    role = "owner",
                    password = "fixture-password",
                    aesKey = fixtureKey(),
                    totpSecret = secret,
                    adminId = UUID.randomUUID(),
                ),
            )
        val occurrences = Regex(Regex.escape(base32Secret)).findAll(out).count()
        occurrences shouldBe 1
    }

    "raw TOTP secret bytes never appear as hex or base64 (only base32 is surfaced)" {
        val secret = fixtureSecret()
        val secretHex = java.util.HexFormat.of().formatHex(secret)
        val secretBase64 = java.util.Base64.getEncoder().encodeToString(secret)
        val out =
            operatorOutput(
                buildAdminBootstrap(
                    email = "oka@nearyou.id",
                    displayName = "Oka",
                    role = "owner",
                    password = "fixture-password",
                    aesKey = fixtureKey(),
                    totpSecret = secret,
                    adminId = UUID.randomUUID(),
                ),
            )
        out shouldNotContain secretHex
        out shouldNotContain secretBase64
    }

    "the encrypted TOTP secret in the SQL round-trips back to the raw secret" {
        val secret = fixtureSecret()
        val key = fixtureKey()
        val adminId = UUID.randomUUID()
        val result =
            buildAdminBootstrap(
                email = "oka@nearyou.id",
                displayName = "Oka",
                role = "owner",
                password = "fixture-password",
                aesKey = key,
                totpSecret = secret,
                adminId = adminId,
            )
        // Extract the base64 from `decode('<base64>', 'base64')` and decrypt.
        val base64 = Regex("""decode\('([^']+)', 'base64'\)""").find(result.sqlInsert)!!.groupValues[1]
        val ciphertext = java.util.Base64.getDecoder().decode(base64)
        val decrypted =
            id.nearyou.app.admin.auth.AesGcmCipher.decrypt(
                ciphertext,
                key,
                id.nearyou.app.admin.auth.AesGcmCipher.adminUuidAsBytes(adminId),
            )
        decrypted.toList() shouldBe secret.toList()
    }

    "parseArgs rejects an invalid role and missing fields" {
        parseArgs(arrayOf("--email", "x@y.z", "--display-name", "X", "--role", "superuser")) shouldBe null
        parseArgs(arrayOf("--email", "x@y.z", "--role", "owner")) shouldBe null // missing display-name
        parseArgs(arrayOf("--display-name", "X", "--role", "owner")) shouldBe null // missing email
    }

    "parseArgs accepts all four valid roles" {
        listOf("owner", "admin", "moderator", "read_only").forEach { role ->
            val parsed = parseArgs(arrayOf("--email", "x@y.z", "--display-name", "X", "--role", role))
            parsed?.role shouldBe role
        }
    }
})
