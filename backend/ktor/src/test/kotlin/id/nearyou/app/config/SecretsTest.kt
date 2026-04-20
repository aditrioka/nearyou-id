package id.nearyou.app.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class SecretsTest : StringSpec({
    "staging env prefixes the secret name" {
        secretKey("staging", "admin-app-db-connection-string") shouldBe
            "staging-admin-app-db-connection-string"
    }

    "production env returns the name unchanged" {
        secretKey("production", "admin-app-db-connection-string") shouldBe
            "admin-app-db-connection-string"
    }

    "unrecognized env is treated as production (no prefix)" {
        secretKey("dev", "ktor-rsa-private-key") shouldBe "ktor-rsa-private-key"
    }

    "EnvVarSecretResolver returns the env var when present" {
        val resolver =
            EnvVarSecretResolver { name ->
                if (name == "KTOR_RSA_PRIVATE_KEY") "the-pem" else null
            }
        resolver.resolve("ktor-rsa-private-key") shouldBe "the-pem"
    }

    "EnvVarSecretResolver normalizes dashes and case" {
        var seen: String? = null
        val resolver =
            EnvVarSecretResolver { name ->
                seen = name
                "value"
            }
        resolver.resolve("admin-app-db-connection-string")
        seen shouldBe "ADMIN_APP_DB_CONNECTION_STRING"
    }

    "EnvVarSecretResolver returns null when env var missing" {
        val resolver = EnvVarSecretResolver { null }
        resolver.resolve("anything").shouldBeNull()
    }

    "EnvVarSecretResolver treats blank as missing" {
        val resolver = EnvVarSecretResolver { "   " }
        resolver.resolve("anything").shouldBeNull()
    }
})
