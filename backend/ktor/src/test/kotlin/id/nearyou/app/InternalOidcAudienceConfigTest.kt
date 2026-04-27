package id.nearyou.app

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.server.config.MapApplicationConfig

/**
 * Boot-time fail-fast tests for the `oidc.internalAudience` config (5.3 / spec
 * § "Configured audience is required at boot"). Exercised against the
 * extracted `resolveInternalOidcAudience` helper rather than the full
 * Application module — the boot-fail behavior is what's being asserted, not the
 * end-to-end Ktor lifecycle.
 */
class InternalOidcAudienceConfigTest : StringSpec({

    "9.25 boot fails on missing oidc.internalAudience config" {
        val config = MapApplicationConfig() // no key set
        val ex = shouldThrow<IllegalStateException> { resolveInternalOidcAudience(config) }
        ex.message!! shouldBe ex.message
        // Message must name the missing key for operator debugging.
        require(ex.message!!.contains("oidc.internalAudience")) {
            "expected error message to name 'oidc.internalAudience', was: ${ex.message}"
        }
    }

    "9.26 boot fails on blank oidc.internalAudience config" {
        val config = MapApplicationConfig().apply { put("oidc.internalAudience", "") }
        val ex = shouldThrow<IllegalStateException> { resolveInternalOidcAudience(config) }
        require(ex.message!!.contains("oidc.internalAudience"))
    }

    "9.26b boot fails on whitespace-only audience" {
        val config = MapApplicationConfig().apply { put("oidc.internalAudience", "   ") }
        shouldThrow<IllegalStateException> { resolveInternalOidcAudience(config) }
    }

    "9.26c boot fails on non-URL audience" {
        val config = MapApplicationConfig().apply { put("oidc.internalAudience", "not-a-url") }
        val ex = shouldThrow<IllegalArgumentException> { resolveInternalOidcAudience(config) }
        require(ex.message!!.contains("syntactically valid URL"))
    }

    "9.26d boot succeeds on valid https URL" {
        val config =
            MapApplicationConfig().apply {
                put("oidc.internalAudience", "https://api-staging.nearyou.id")
            }
        resolveInternalOidcAudience(config) shouldBe "https://api-staging.nearyou.id"
    }
})
