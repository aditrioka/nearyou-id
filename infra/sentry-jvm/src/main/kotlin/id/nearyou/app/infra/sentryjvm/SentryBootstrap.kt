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
    private const val FLUSH_TIMEOUT_MILLIS = 2_000L

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
            // The appender's own start() re-runs Sentry.init with external configuration and a null
            // DSN: normally that throws "DSN is required." (swallowed by the appender) before
            // touching our instance; with an external SENTRY_DSN set, the InitPriority check (LOWEST
            // vs our MEDIUM) skips it. ponytail: an explicitly EMPTY `SENTRY_DSN` env var would make
            // that re-init close the SDK — we never set it; the DSN lives in SENTRY_BACKEND_DSN.
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
        } catch (t: Throwable) {
            // Throwable, not Exception: a LinkageError / ServiceConfigurationError from the SDK must
            // not crash startup either (the OtelBootstrap precedent).
            runCatching { Sentry.close() }
            log.info("event=sentry_disabled reason=init_failed error_class={}", t.javaClass.name)
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
        // Nothing else installs a default uncaught handler, so without this the SDK's handler would
        // swallow the JVM's "Exception in thread …" stderr trace — Cloud Logging stays the log store.
        options.isPrintUncaughtStackTrace = true
        // The uncaught path blocks the throwing thread until the upload flushes (default 15 s); on a
        // coroutine worker that thread lives on, so cap the stall.
        options.flushTimeoutMillis = FLUSH_TIMEOUT_MILLIS
        options.setBeforeSend { event, _ -> scrub(event) }
    }

    internal fun scrub(event: SentryEvent): SentryEvent {
        event.user = null
        event.message?.let { message ->
            message.formatted = message.formatted?.let(PiiScrubber::scrub)
            message.message = message.message?.let(PiiScrubber::scrub)
            // The appender copies each raw log argument into `params`; `formatted` (scrubbed above)
            // already carries them, so the raw copies never leave the process.
            message.params = null
        }
        event.exceptions?.forEach { exception -> exception.value = exception.value?.let(PiiScrubber::scrub) }
        return event
    }
}
