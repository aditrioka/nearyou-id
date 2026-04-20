package id.nearyou.app.auth.provider

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import java.time.Clock

/**
 * Network-dependent smoke test. Confirms Google's and Apple's JWKS endpoints
 * are reachable and that JwksCache parses + materializes RSA keys end-to-end.
 *
 * Tagged `network` so it can be excluded from default PR CI (which sets
 * `-Dkotest.tags=!network`). Run nightly / pre-deploy with no tag filter, or
 * explicitly with `./gradlew test -Dkotest.tags=network`.
 *
 * Covers the network-plumbing gap left by skipping live signin verification
 * (auth-foundation task 14.2) — no real OAuth client setup needed.
 */
@Tags("network")
class JwksReachabilityTest : StringSpec({
    val httpClient =
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 10_000
            }
        }

    "Google JWKS endpoint returns at least one usable RSA key" {
        val cache = JwksCache(httpClient, GOOGLE_JWKS_URL_DEFAULT, Clock.systemUTC())
        cache.availableKids().shouldNotBeEmpty()
    }

    "Apple JWKS endpoint returns at least one usable RSA key" {
        val cache = JwksCache(httpClient, APPLE_JWKS_URL_DEFAULT, Clock.systemUTC())
        cache.availableKids().shouldNotBeEmpty()
    }
})
