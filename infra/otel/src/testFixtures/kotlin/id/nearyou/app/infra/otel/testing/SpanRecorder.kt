package id.nearyou.app.infra.otel.testing

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.samplers.Sampler
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory [SpanProcessor] that captures every `onEnd(span)` invocation as
 * a [SpanData] for assertion in unit tests. Consumed by:
 *
 *   - `:infra:otel`'s own tests (`UserIdHasherTest`, `WithSpanTest`,
 *     `OtelBootstrapTest`).
 *   - `:backend:ktor`'s integration tests for the chat-realtime + FCM
 *     span-pairing scenarios (per `chat-realtime-broadcast` spec §
 *     "OTel span emitted on PublishResult.Failure pairs with WARN log" and
 *     `fcm-push-dispatch` spec § "WARN-log scenarios SHALL pair with an
 *     OTel span event").
 *
 * Thread-safe: spans are appended to a `CopyOnWriteArrayList` so concurrent
 * test scenarios don't lose captures.
 */
class SpanRecorder : SpanProcessor {
    private val spans = CopyOnWriteArrayList<SpanData>()

    override fun onStart(
        parentContext: Context,
        span: ReadWriteSpan,
    ) {
        // No-op — recording at end captures the final attribute set + status.
    }

    override fun onEnd(span: ReadableSpan) {
        spans.add(span.toSpanData())
    }

    override fun isStartRequired(): Boolean = false

    override fun isEndRequired(): Boolean = true

    fun recordedSpans(): List<SpanData> = spans.toList()

    fun clear() {
        spans.clear()
    }

    companion object {
        /**
         * Build a fresh [OpenTelemetry] instance backed by this recorder
         * (and the supplied [Sampler], default `alwaysOn`). Useful for unit
         * tests that need a self-contained trace pipeline without touching
         * the SDK global state.
         *
         * Returns the [OpenTelemetry] instance plus the [SpanRecorder] for
         * post-test assertion access.
         */
        fun newPipeline(sampler: Sampler = Sampler.alwaysOn()): Pair<OpenTelemetry, SpanRecorder> {
            val recorder = SpanRecorder()
            val provider =
                SdkTracerProvider.builder()
                    .setSampler(sampler)
                    .addSpanProcessor(recorder)
                    .build()
            // W3C trace-context propagators wired so the OTel Ktor client
            // instrumentation can inject `traceparent` on outbound requests.
            // Without propagators, the auto-injection silently no-ops.
            val sdk =
                OpenTelemetrySdk.builder()
                    .setTracerProvider(provider)
                    .setPropagators(
                        io.opentelemetry.context.propagation.ContextPropagators.create(
                            io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance(),
                        ),
                    )
                    .build()
            return sdk to recorder
        }
    }
}

/**
 * SpanProcessor whose `onEnd(span)` always throws. Used by the
 * `fcm-push-dispatch` spec § "Span recording failure does not block dispatch"
 * scenario — verifies the dispatcher's span-event recording path is wrapped
 * in a swallow-all `try/catch` so an SDK failure cannot poison the FCM send.
 */
class FailingSpanProcessor(
    private val message: String = "simulated SDK failure",
) : SpanProcessor {
    override fun onStart(
        parentContext: Context,
        span: ReadWriteSpan,
    ) {
        // No-op on start — only end is contractually allowed to throw.
    }

    override fun onEnd(span: ReadableSpan) {
        throw IllegalStateException(message)
    }

    override fun isStartRequired(): Boolean = false

    override fun isEndRequired(): Boolean = true
}
