package id.nearyou.app.infra.otel

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context

/**
 * Canonical manual-span helper per the `observability-otel-foundation`
 * capability spec § "`withSpan` helper is the canonical manual-span surface".
 *
 * Behavior:
 *  - Creates a span via the global [io.opentelemetry.api.OpenTelemetry] tracer
 *    `id.nearyou.backend`. The span's parent is the active context — nested
 *    `withSpan` calls produce a parent-child relationship in the trace tree.
 *  - Applies the caller-provided [attributes] verbatim before activating the
 *    span. The helper does NOT mutate, drop, or rename caller attributes;
 *    callers are responsible for honoring the spec's forbidden-attributes
 *    contract at the call site.
 *  - Records exceptions thrown from [block] via [Span.recordException] and
 *    sets status to [StatusCode.ERROR].
 *  - Re-throws the original throwable (no swallowing). Exception transparency
 *    is mandatory so error-handling logic at call sites is unchanged.
 *  - Closes the span via `try/finally` so the span ends on `Throwable` (not
 *    just `Exception`) — covers `kotlinx.coroutines.CancellationException`
 *    AND `java.lang.Error` subclasses.
 *
 * Manual span sites (Realtime publish, FCM dispatch, future sites) MUST go
 * through this helper. Direct `tracer.spanBuilder(...)` use is allowed only
 * inside `:infra:otel` itself.
 */
inline fun <T> withSpan(
    name: String,
    attributes: Map<String, Any> = emptyMap(),
    block: () -> T,
): T {
    val tracer = GlobalOpenTelemetry.getTracer(TRACER_NAME)
    val attrs =
        if (attributes.isEmpty()) {
            Attributes.empty()
        } else {
            val builder: AttributesBuilder = Attributes.builder()
            for ((k, v) in attributes) {
                applyAttribute(builder, k, v)
            }
            builder.build()
        }
    val span =
        tracer
            .spanBuilder(name)
            .setAllAttributes(attrs)
            .startSpan()
    val parent = Context.current().with(span)
    val scope = parent.makeCurrent()
    try {
        return block()
    } catch (t: Throwable) {
        span.recordException(t)
        span.setStatus(StatusCode.ERROR)
        throw t
    } finally {
        scope.close()
        span.end()
    }
}

/**
 * Tracer name for the project. Stable identifier — observability dashboards in
 * Grafana Tempo filter by this. Changing it is a follow-up change requiring
 * dashboard updates.
 */
const val TRACER_NAME: String = "id.nearyou.backend"

/**
 * Applies a single attribute key/value to the builder, choosing the typed
 * setter from the value's runtime type. Public so the inline [withSpan] can
 * call it across the module boundary.
 */
fun applyAttribute(
    builder: AttributesBuilder,
    key: String,
    value: Any,
) {
    when (value) {
        is String -> builder.put(AttributeKey.stringKey(key), value)
        is Long -> builder.put(AttributeKey.longKey(key), value)
        is Int -> builder.put(AttributeKey.longKey(key), value.toLong())
        is Double -> builder.put(AttributeKey.doubleKey(key), value)
        is Float -> builder.put(AttributeKey.doubleKey(key), value.toDouble())
        is Boolean -> builder.put(AttributeKey.booleanKey(key), value)
        else -> builder.put(AttributeKey.stringKey(key), value.toString())
    }
}
