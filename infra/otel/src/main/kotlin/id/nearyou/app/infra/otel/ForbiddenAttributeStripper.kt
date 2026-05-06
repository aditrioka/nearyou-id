package id.nearyou.app.infra.otel

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.DelegatingSpanData
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter

/**
 * SpanExporter wrapper that strips attribute keys forbidden by the
 * `observability-otel-foundation` capability spec § "Forbidden span attributes"
 * before delegating to the wrapped exporter.
 *
 * The Ktor 3.x server instrumentation attaches the OTel HTTP semconv keys
 * `client.address`, `net.peer.ip`, `net.sock.peer.addr`, `http.client_ip` by
 * default; behind Cloudflare these carry the Cloudflare-edge peer IP. The
 * project's posture forbids exposing peer-IPs even when they're edge IPs —
 * the real client IP lives in `CF-Connecting-IP` and is sourced via the
 * `clientIp` request-context value at attribute-set sites, never via the
 * auto-instrumented HTTP semconv.
 *
 * The strip is applied at export time (defense in depth: regardless of which
 * instrumentation set the attribute, it never reaches Tempo). The internal
 * `Span.current()` API still observes the attribute on the live span, but
 * the persisted shape excludes it.
 *
 * Forbidden keys are intentionally a small enumerated set rather than a
 * regex — wrong-positives are worse than wrong-negatives here, and a
 * concrete list lets reviewers confirm exactly what's stripped.
 */
internal class ForbiddenAttributeStripper(
    private val delegate: SpanExporter,
) : SpanExporter {
    override fun export(spans: Collection<SpanData>): CompletableResultCode = delegate.export(spans.map { strip(it) })

    override fun flush(): CompletableResultCode = delegate.flush()

    override fun shutdown(): CompletableResultCode = delegate.shutdown()

    private fun strip(source: SpanData): SpanData {
        val original = source.attributes
        if (FORBIDDEN_KEYS.none { original.get(AttributeKey.stringKey(it)) != null }) {
            return source
        }
        val builder = Attributes.builder()
        original.forEach { key, value ->
            if (key.key !in FORBIDDEN_KEYS) {
                copyAttribute(builder, key, value)
            }
        }
        val filtered = builder.build()
        return object : DelegatingSpanData(source) {
            override fun getAttributes(): Attributes = filtered

            override fun getTotalAttributeCount(): Int = filtered.size()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun copyAttribute(
        builder: io.opentelemetry.api.common.AttributesBuilder,
        key: AttributeKey<*>,
        value: Any,
    ) {
        // OTel API erases the type parameter at runtime; the only safe form is
        // a key-typed put with the unchecked cast. The original `Attributes`
        // got it right by construction, so the cast preserves correctness.
        builder.put(key as AttributeKey<Any>, value)
    }

    companion object {
        /**
         * Forbidden attribute keys per the spec § "Forbidden span attributes".
         * These are the OTel HTTP semconv keys the Ktor server instrumentation
         * attaches by default that carry peer-IP data.
         */
        val FORBIDDEN_KEYS: Set<String> =
            setOf(
                "client.address",
                "net.peer.ip",
                "net.sock.peer.addr",
                "http.client_ip",
                // Defensive: also strip raw user UUIDs at common typo'd keys
                // in case a future writer bypasses `UserIdHasher`. The spec's
                // sentinel-based tests will still catch a value-leak.
                "user_id",
                "user_uuid",
                "user.uuid",
            )
    }
}
