package id.nearyou.app.infra.sentryjvm.testing

import ch.qos.logback.classic.LoggerContext
import id.nearyou.app.infra.sentryjvm.SentryBootstrap
import io.sentry.Hint
import io.sentry.ISerializer
import io.sentry.ITransportFactory
import io.sentry.Sentry
import io.sentry.SentryEnvelope
import io.sentry.hints.DiskFlushNotification
import io.sentry.transport.ITransport
import io.sentry.transport.RateLimiter
import io.sentry.util.HintUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

/** Vendor-free view of one event as it left the process (after `beforeSend` scrubbing). */
data class CapturedSentryEvent(
    val level: String?,
    val message: String?,
    val messageParams: List<String>?,
    val exceptionTypes: List<String>,
    val exceptionValues: List<String?>,
    val tags: Map<String, String>,
    val environment: String?,
    val release: String?,
    val hasUser: Boolean,
    val serverName: String?,
    val breadcrumbCount: Int,
)

/**
 * Starts [SentryBootstrap] with a synchronous recording transport (no network) so tests — including
 * `:backend:ktor`'s, which may not import `io.sentry` — can assert what would be sent. [close]
 * detaches the appender and closes the SDK; always `use {}` it.
 */
class SentryEventRecorder private constructor(
    private val envelopes: MutableList<Pair<SentryEnvelope, ISerializer>>,
) : AutoCloseable {
    val events: List<CapturedSentryEvent>
        get() =
            envelopes.flatMap { (envelope, serializer) ->
                envelope.items.mapNotNull { it.getEvent(serializer) }.map { event ->
                    CapturedSentryEvent(
                        level = event.level?.name?.lowercase(),
                        message = event.message?.formatted,
                        messageParams = event.message?.params,
                        exceptionTypes = event.exceptions.orEmpty().mapNotNull { it.type },
                        exceptionValues = event.exceptions.orEmpty().map { it.value },
                        tags = event.tags.orEmpty(),
                        environment = event.environment,
                        release = event.release,
                        hasUser = event.user != null,
                        serverName = event.serverName,
                        breadcrumbCount = event.breadcrumbs.orEmpty().size,
                    )
                }
            }

    override fun close() = reset()

    companion object {
        const val FAKE_DSN = "https://public@sentry.invalid/1"
        private const val APPENDER_NAME = "SENTRY"

        /** Detaches the root `SENTRY` appender and closes the SDK — undoes any [SentryBootstrap.start]. */
        fun reset() {
            val context = LoggerFactory.getILoggerFactory() as LoggerContext
            context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(APPENDER_NAME)
            Sentry.close()
        }

        fun start(
            env: String = "test",
            release: String? = "test-release",
        ): SentryEventRecorder {
            val envelopes = CopyOnWriteArrayList<Pair<SentryEnvelope, ISerializer>>()
            val factory =
                ITransportFactory { options, _ ->
                    object : ITransport {
                        override fun send(
                            envelope: SentryEnvelope,
                            hint: Hint,
                        ) {
                            envelopes += envelope to options.serializer
                            // The uncaught-exception path blocks until the transport marks the
                            // event flushed (AsyncHttpTransport does it after the upload).
                            (HintUtils.getSentrySdkHint(hint) as? DiskFlushNotification)?.markFlushed()
                        }

                        override fun flush(timeoutMillis: Long) = Unit

                        override fun getRateLimiter(): RateLimiter? = null

                        override fun close(isRestarting: Boolean) = Unit

                        override fun close() = Unit
                    }
                }
            check(SentryBootstrap.start(env, FAKE_DSN, release, factory)) { "SentryBootstrap did not enable" }
            return SentryEventRecorder(envelopes)
        }
    }
}
