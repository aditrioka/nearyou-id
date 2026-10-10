package id.nearyou.app

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.server.config.MapApplicationConfig
import org.slf4j.LoggerFactory
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * `oidc.allowedPrincipals` resolution (internal-endpoint-auth § "Allowed caller principals are
 * configured, and an empty allowlist denies every caller", #544). Exercised against the extracted
 * resolver, mirroring [InternalOidcAudienceConfigTest]: empty fails boot in a deployed environment,
 * and in dev/test resolves to an empty (deny-all) set with a WARN.
 */
class InternalOidcAllowedPrincipalsConfigTest : StringSpec({

    fun resolveCapturingWarns(config: MapApplicationConfig): Pair<Set<String>, List<String>> {
        val logger = LoggerFactory.getLogger("id.nearyou.app.Application") as LogbackLogger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val resolved = resolveInternalOidcAllowedPrincipals(config)
            return resolved to appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    "unset allowlist in dev/test boots (no throw) and resolves to an empty, denying set with a WARN" {
        val (resolved, warns) = resolveCapturingWarns(MapApplicationConfig("ktor.environment" to "test"))
        resolved.shouldBeEmpty()
        warns.any { it.contains("event=internal_oidc_allowlist_empty") } shouldBe true
    }

    "blank entries resolve to an empty set with a WARN (fail-closed, not allow-all)" {
        val (resolved, warns) =
            resolveCapturingWarns(MapApplicationConfig("ktor.environment" to "dev", "oidc.allowedPrincipals" to " , ,"))
        resolved.shouldBeEmpty()
        warns.any { it.contains("event=internal_oidc_allowlist_empty") } shouldBe true
    }

    "comma-separated allowlist is trimmed and lowercased, no WARN" {
        val (resolved, warns) =
            resolveCapturingWarns(
                MapApplicationConfig().apply {
                    put("ktor.environment", "staging")
                    put("oidc.allowedPrincipals", " Scheduler-A@p.iam.gserviceaccount.com , scheduler-b@p.iam.gserviceaccount.com ,")
                },
            )
        resolved shouldBe setOf("scheduler-a@p.iam.gserviceaccount.com", "scheduler-b@p.iam.gserviceaccount.com")
        warns.none { it.contains("event=internal_oidc_allowlist_empty") } shouldBe true
    }

    "an empty allowlist fails boot outside local envs (staging, production, unset = production, a typo)" {
        for (env in listOf("staging", "production", null, "prod", "")) {
            for (blank in listOf(null, " , ")) {
                val config =
                    MapApplicationConfig().apply {
                        if (env != null) put("ktor.environment", env)
                        if (blank != null) put("oidc.allowedPrincipals", blank)
                    }
                val ex = shouldThrow<IllegalStateException> { resolveInternalOidcAllowedPrincipals(config) }
                ex.message!! shouldContain "INTERNAL_OIDC_ALLOWED_PRINCIPALS"
            }
        }
    }
})
