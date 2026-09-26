package id.nearyou.app.infra.sentryjvm

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import io.sentry.ITransportFactory
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.logback.SentryAppender
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Backend error reporting entrypoint (`backend-error-reporting` capability). Vendor-free surface:
 * `:backend:ktor` calls [start] once, right after `OtelBootstrap.start`, and never sees `io.sentry`.
 *
 * Capture path: a logback [SentryAppender] on the root logger turns every `ERROR` event into a
 * Sentry event — including StatusPages' `event=unhandled_exception` 500 line, which carries the
 * stack trace — and the SDK's default uncaught-exception handler covers non-request threads.
 *
 * Privacy (design D3–D5): `sendDefaultPii = false`, no `server_name`, no user, log breadcrumbs
 * OFF (thread-local scopes would mix unrelated requests on shared coroutine threads), and a
 * [PiiScrubber] `beforeSend`. Correlation is the `call_id` MDC value promoted to a tag.
 * Tracing stays off (no traces sample rate) — OpenTelemetry owns traces.
 */
object SentryBootstrap {
    internal const val APPENDER_NAME = "SENTRY"

    private val log = LoggerFactory.getLogger(SentryBootstrap::class.java)

    /**
     * Returns `true` when reporting is enabled. Blank [dsn] → no-op (`reason=dsn_missing`); an SDK
     * init failure → no-op (`reason=init_failed`), never a startup crash. Idempotent: a repeated
     * call finds the root logger's `SENTRY` appender and returns without re-initializing.
     */
    fun start(
        env: String,
        dsn: String?,
        release: String?,
    ): Boolean = start(env, dsn, release, transportFactory = null)

    /** [transportFactory] is the `SentryEventRecorder` test-fixture seam. */
    internal fun start(
        env: String,
        dsn: String?,
        release: String?,
        transportFactory: ITransportFactory?,
    ): Boolean {
        val loggerContext = LoggerFactory.getILoggerFactory() as? LoggerContext
        val root = loggerContext?.getLogger(Logger.ROOT_LOGGER_NAME)
        if (root?.getAppender(APPENDER_NAME) != null) return true
        if (dsn.isNullOrBlank()) {
            log.info("event=sentry_disabled reason=dsn_missing")
            return false
        }
        return try {
            checkNotNull(root) { "logback is not the SLF4J binding" }
            Sentry.init { options ->
                configure(options, env, dsn, release)
                transportFactory?.let(options::setTransportFactory)
            }
            check(Sentry.isEnabled()) { "Sentry did not enable" }
            // The appender's own start() re-runs Sentry.init at InitPriority.LOWEST; the SDK skips
            // it because ours ran at the default MEDIUM, so these options stay authoritative.
            // ponytail: an explicitly EMPTY `SENTRY_DSN` env var would make that re-init close the
            // SDK (external config) — we never set it; the DSN lives in SENTRY_BACKEND_DSN.
            val appender =
                SentryAppender().apply {
                    name = APPENDER_NAME
                    context = loggerContext
                    setMinimumEventLevel(Level.ERROR)
                    setMinimumBreadcrumbLevel(Level.OFF)
                    start()
                }
            root.addAppender(appender)
            log.info("event=sentry_enabled environment={} release={}", env, release)
            true
        } catch (e: Exception) {
            Sentry.close()
            log.info("event=sentry_disabled reason=init_failed error_class={}", e.javaClass.name)
            false
        }
    }

    private fun configure(
        options: SentryOptions,
        env: String,
        dsn: String,
        release: String?,
    ) {
        options.dsn = dsn
        options.environment = env
        options.release = release
        options.isSendDefaultPii = false
        options.isAttachServerName = false
        options.addContextTag("call_id")
        options.addInAppInclude("id.nearyou")
        options.setBeforeSend { event, _ -> scrub(event) }
    }

    internal fun scrub(event: SentryEvent): SentryEvent {
        event.user = null
        event.message?.let { message ->
            message.formatted = message.formatted?.let(PiiScrubber::scrub)
            message.message = message.message?.let(PiiScrubber::scrub)
        }
        event.exceptions?.forEach { exception -> exception.value = exception.value?.let(PiiScrubber::scrub) }
        return event
    }
}
