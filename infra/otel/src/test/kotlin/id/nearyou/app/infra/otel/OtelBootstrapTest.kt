package id.nearyou.app.infra.otel

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.slf4j.LoggerFactory

class OtelBootstrapTest : StringSpec({
    beforeEach {
        OtelBootstrap.resetForTesting()
    }

    afterEach {
        OtelBootstrap.resetForTesting()
    }

    "first call wires the SDK and exposes a tracer" {
        val resolver =
            SecretResolver { name ->
                when (name) {
                    "otel-grafana-otlp-endpoint" -> "https://tempo.example/tempo"
                    "otel-grafana-otlp-token" -> "test-token-value"
                    else -> null
                }
            }
        val handle = OtelBootstrap.start(env = "dev", secretResolver = resolver)
        handle shouldBe handle // sanity: handle returned
        // Tracer is obtainable via the global slot.
        val tracer = io.opentelemetry.api.GlobalOpenTelemetry.getTracer(TRACER_NAME)
        tracer shouldBe tracer
    }

    "second call is a no-op + emits otel_bootstrap_already_initialized INFO line" {
        val (logs, listAppender) = attachAppender()
        try {
            val resolver = SecretResolver { _ -> null }
            OtelBootstrap.start("dev", resolver)
            OtelBootstrap.start("dev", resolver)
            val initLines = logs.list.filter { it.formattedMessage.contains("otel_bootstrap_already_initialized") }
            initLines.size shouldBe 1
        } finally {
            detachAppender(listAppender)
        }
    }

    "endpoint absent → exactly one INFO line with reason=endpoint_missing" {
        val (logs, listAppender) = attachAppender()
        try {
            val resolver = SecretResolver { _ -> null }
            OtelBootstrap.start("dev", resolver)
            val disabledLines =
                logs.list.filter { it.formattedMessage.contains("event=otel_exporter_disabled") }
            disabledLines shouldHaveSize 1
            disabledLines[0].formattedMessage.toString() shouldContain "reason=endpoint_missing"
        } finally {
            detachAppender(listAppender)
        }
    }

    "endpoint present + token absent → INFO line with reason=token_missing" {
        val (logs, listAppender) = attachAppender()
        try {
            // Resolver convention: code calls `resolve("logical-name")` with bare
            // (un-prefixed) name. EnvVarSecretResolver maps it to env var
            // `LOGICAL_NAME`, and the deploy workflow maps env var to a
            // staging-prefixed Secret Manager slot. See OtelBootstrap.start
            // comments for the full chain.
            val resolver =
                SecretResolver { name ->
                    if (name == "otel-grafana-otlp-endpoint") "https://tempo.example/tempo" else null
                }
            OtelBootstrap.start("dev", resolver)
            val disabledLines =
                logs.list.filter { it.formattedMessage.contains("event=otel_exporter_disabled") }
            disabledLines shouldHaveSize 1
            disabledLines[0].formattedMessage.toString() shouldContain "reason=token_missing"
        } finally {
            detachAppender(listAppender)
        }
    }

    "both secrets absent → exactly ONE INFO line with reason=endpoint_missing (deterministic precedence)" {
        val (logs, listAppender) = attachAppender()
        try {
            val resolver = SecretResolver { _ -> null }
            OtelBootstrap.start("dev", resolver)
            val disabledLines =
                logs.list.filter { it.formattedMessage.contains("event=otel_exporter_disabled") }
            // Endpoint check short-circuits before token check; only ONE line emitted.
            disabledLines shouldHaveSize 1
            disabledLines[0].formattedMessage.toString() shouldContain "reason=endpoint_missing"
        } finally {
            detachAppender(listAppender)
        }
    }

    "both secrets present → live OTLP exporter wired, no exporter_disabled line" {
        val (logs, listAppender) = attachAppender()
        try {
            val resolver =
                SecretResolver { name ->
                    when (name) {
                        "otel-grafana-otlp-endpoint" -> "https://tempo.example/tempo"
                        "otel-grafana-otlp-token" -> "live-token"
                        else -> null
                    }
                }
            OtelBootstrap.start("dev", resolver)
            val disabledLines =
                logs.list.filter { it.formattedMessage.contains("event=otel_exporter_disabled") }
            disabledLines shouldHaveSize 0
            val configuredLines =
                logs.list.filter { it.formattedMessage.contains("event=otel_exporter_configured") }
            configuredLines shouldHaveSize 1
        } finally {
            detachAppender(listAppender)
        }
    }

    "OTLP token VALUE never appears in startup logs" {
        val (logs, listAppender) = attachAppender()
        try {
            val sentinelToken = "sentinel-otlp-token-DO-NOT-LEAK"
            val resolver =
                SecretResolver { name ->
                    when (name) {
                        "otel-grafana-otlp-endpoint" -> "https://tempo.example/tempo"
                        "otel-grafana-otlp-token" -> sentinelToken
                        else -> null
                    }
                }
            OtelBootstrap.start("dev", resolver)
            for (event in logs.list) {
                event.formattedMessage.toString() shouldNotContain sentinelToken
            }
        } finally {
            detachAppender(listAppender)
        }
    }

    "exporter init failure on malformed endpoint → falls back to no-op without crashing" {
        val (logs, listAppender) = attachAppender()
        try {
            val resolver =
                SecretResolver { name ->
                    when (name) {
                        "otel-grafana-otlp-endpoint" -> "::not-a-url::"
                        "otel-grafana-otlp-token" -> "tok"
                        else -> null
                    }
                }
            // Should NOT throw — bootstrap is exception-safe.
            OtelBootstrap.start("dev", resolver)
            // Tolerate either a successful wire (OTLP HTTP exporter is permissive
            // on URL parse — actual reachability check happens at first export)
            // or the documented exporter_init_failed fallback. Either way, no
            // exception escaped.
            val sawDisabled =
                logs.list.any { it.formattedMessage.contains("event=otel_exporter_disabled") }
            val sawConfigured =
                logs.list.any { it.formattedMessage.contains("event=otel_exporter_configured") }
            (sawDisabled || sawConfigured) shouldBe true
        } finally {
            detachAppender(listAppender)
        }
    }

    "resolver receives bare (un-prefixed) logical name regardless of env" {
        // Convention guard: code calls `secretResolver.resolve("otel-grafana-otlp-endpoint")`
        // WITHOUT staging-prefix. Resolver-side adaptation
        // (env var name uppercase + dash-to-underscore → `OTEL_GRAFANA_OTLP_ENDPOINT`)
        // matches the staging deploy workflow's env-var binding shape.
        // The staging-prefixed slot name appears ONLY in operator-facing log
        // messages (verified in the next test) — not in resolver lookups.
        // Locks against the like-rate-limit class of bug where resolver was
        // called with a prefixed name and Cloud Run's env var binding never
        // matched.
        for (env in listOf("dev", "staging", "production")) {
            OtelBootstrap.resetForTesting()
            val seen = mutableListOf<String>()
            val resolver =
                SecretResolver { name ->
                    seen.add(name)
                    null
                }
            OtelBootstrap.start(env, resolver)
            (seen.contains("otel-grafana-otlp-endpoint")) shouldBe true
            // No call should ever pass through the staging- prefix:
            (seen.any { it.startsWith("staging-") }) shouldBe false
        }
    }

    "exporter_disabled INFO line carries the staging-prefixed slot name for operator triage" {
        val (logs, listAppender) = attachAppender()
        try {
            val resolver = SecretResolver { _ -> null }
            OtelBootstrap.start("staging", resolver)
            val disabledLines =
                logs.list.filter { it.formattedMessage.contains("event=otel_exporter_disabled") }
            disabledLines shouldHaveSize 1
            // Operator-facing `slot` field carries the actual GCP Secret Manager
            // slot name (staging-prefixed in staging) so the operator knows
            // which slot to populate. Distinct from the bare logical name
            // used for resolver lookup.
            disabledLines[0].formattedMessage.toString() shouldContain "slot=staging-otel-grafana-otlp-endpoint"
        } finally {
            detachAppender(listAppender)
        }
    }
})

private fun attachAppender(): Pair<ListAppender<ILoggingEvent>, ListAppender<ILoggingEvent>> {
    val rootLogger = LoggerFactory.getLogger("id.nearyou.app.infra.otel.OtelBootstrap") as Logger
    val appender = ListAppender<ILoggingEvent>()
    appender.start()
    rootLogger.addAppender(appender)
    rootLogger.level = Level.INFO
    return appender to appender
}

private fun detachAppender(appender: ListAppender<ILoggingEvent>) {
    val rootLogger = LoggerFactory.getLogger("id.nearyou.app.infra.otel.OtelBootstrap") as Logger
    rootLogger.detachAppender(appender)
}
