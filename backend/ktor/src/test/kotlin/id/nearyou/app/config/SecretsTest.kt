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
            EnvVarSecretResolver(getenv = { name ->
                if (name == "KTOR_RSA_PRIVATE_KEY") "the-pem" else null
            })
        resolver.resolve("ktor-rsa-private-key") shouldBe "the-pem"
    }

    "EnvVarSecretResolver normalizes dashes and case" {
        var seen: String? = null
        val resolver =
            EnvVarSecretResolver(getenv = { name ->
                seen = name
                "value"
            })
        resolver.resolve("admin-app-db-connection-string")
        seen shouldBe "ADMIN_APP_DB_CONNECTION_STRING"
    }

    "EnvVarSecretResolver returns null when env var missing" {
        val resolver = EnvVarSecretResolver(getenv = { null })
        resolver.resolve("anything").shouldBeNull()
    }

    "EnvVarSecretResolver treats blank as missing" {
        val resolver = EnvVarSecretResolver(getenv = { "   " })
        resolver.resolve("anything").shouldBeNull()
    }

    "EnvVarSecretResolver trims surrounding whitespace" {
        val resolver = EnvVarSecretResolver(getenv = { "  GxT3lL5vR2==\n" })
        resolver.resolve("invite-code-secret") shouldBe "GxT3lL5vR2=="
    }

    "invite-code-secret resolves via INVITE_CODE_SECRET env var" {
        var seen: String? = null
        val resolver =
            EnvVarSecretResolver(getenv = { name ->
                seen = name
                "GxT3lL5vR2=="
            })
        resolver.resolve("invite-code-secret") shouldBe "GxT3lL5vR2=="
        seen shouldBe "INVITE_CODE_SECRET"
    }

    "jitter-secret resolves via JITTER_SECRET env var and decodes to 32 bytes" {
        var seen: String? = null
        val thirtyTwoByteBase64 = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        val resolver =
            EnvVarSecretResolver(getenv = { name ->
                seen = name
                thirtyTwoByteBase64
            })
        val value = resolver.resolve("jitter-secret")
        seen shouldBe "JITTER_SECRET"
        value shouldBe thirtyTwoByteBase64
        java.util.Base64.getDecoder().decode(value!!).size shouldBe 32
    }

    // Wiring guard for #381: resolve() must take the UN-prefixed logical name
    // (env var populated by the deploy's --set-secrets mapping). Passing
    // secretKey() output into resolve() reads a STAGING_-prefixed env var no
    // deploy sets, so the secret silently resolves null and fail-soft callers
    // degrade to NoOp in staging AND prod. secretKey() is for diagnostics
    // (slot names in error messages) only. Unit tests with fake resolvers
    // cannot catch this, so we scan the source instead.
    "no source file passes secretKey() output into SecretResolver.resolve()" {
        val srcRoot = java.nio.file.Paths.get("src/main/kotlin")
        java.nio.file.Files.exists(srcRoot) shouldBe true
        val forbidden = Regex("""resolve\(\s*secretKey\(""")
        val offenders =
            java.nio.file.Files.walk(srcRoot).use { paths ->
                paths
                    .filter { it.toString().endsWith(".kt") }
                    .filter { forbidden.containsMatchIn(java.nio.file.Files.readString(it)) }
                    .map { it.toString() }
                    .toList()
            }
        offenders shouldBe emptyList()
    }

    // rate-limit-infrastructure § "Redis URL resolved via SecretResolver": the one
    // shared Redis client is built from the BARE `redis-url` slot (env var REDIS_URL,
    // which deploy-staging.yml binds to `staging-redis-url`). A booted-app test would
    // need live Redis; the wiring is two lines of Application.kt, so pin the source.
    "Application.kt builds the shared Redis handles from the bare redis-url slot" {
        val src = java.io.File("src/main/kotlin/id/nearyou/app/Application.kt").readText()
        Regex("""val\s+redisUrl\s*=\s*secrets\.resolve\(\s*"redis-url"\s*\)""")
            .findAll(src).count() shouldBe 1
        Regex("""redisHandlesFromUrl\(""").findAll(src).count() shouldBe 1
        Regex("""redisUrl\?\.let\s*\{\s*redisHandlesFromUrl\(\s*it\s*\)""")
            .containsMatchIn(src) shouldBe true

        // Named args: a trailing lambda would bind to the LAST parameter
        // (getSystemProperty), and CI's real REDIS_URL env var would short-circuit it.
        val seen = mutableListOf<String>()
        val resolver =
            EnvVarSecretResolver(
                getenv = { name ->
                    seen += name
                    null
                },
                getSystemProperty = { null },
            )
        resolver.resolve("redis-url")
        seen shouldBe listOf("REDIS_URL")
    }
})
