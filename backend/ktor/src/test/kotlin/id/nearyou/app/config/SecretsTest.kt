package id.nearyou.app.config

import io.kotest.core.spec.style.StringSpec
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
})
