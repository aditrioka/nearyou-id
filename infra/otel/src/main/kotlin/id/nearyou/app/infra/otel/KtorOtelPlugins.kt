package id.nearyou.app.infra.otel

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.ktor.v3_0.KtorClientTelemetry
import io.opentelemetry.instrumentation.ktor.v3_0.KtorServerTelemetry

/**
 * Ktor 3.x server + client OTel plugin installers. Hides every
 * `io.opentelemetry.instrumentation.ktor.*` import behind `:infra:otel`'s
 * boundary so `:backend:ktor` only imports `:infra:otel` types.
 *
 * Forbidden-attribute suppression:
 *
 * The OTel HTTP server semconv attaches `client.address`, `net.peer.ip`,
 * `net.sock.peer.addr`, `http.client_ip` by default. When running behind
 * Cloudflare these would carry the Cloudflare-edge peer IP (NOT the real
 * client IP, which is sourced via `CF-Connecting-IP`), but the project's
 * posture forbids exposing peer-IPs at all per the
 * `observability-otel-foundation` capability spec § "Forbidden span
 * attributes".
 *
 * The OTel SDK 1.55.0 `KtorServerTelemetry` builder does not expose a
 * per-attribute suppression API for these keys, so the suppression is
 * implemented downstream via [ForbiddenAttributeStripper] — registered as a
 * `SpanProcessor` on the bootstrap pipeline so the strip happens before
 * export regardless of which instrumentation set the attribute.
 */
fun Application.installKtorServerTelemetry(openTelemetry: OpenTelemetry = getOpenTelemetry()) {
    install(KtorServerTelemetry) {
        setOpenTelemetry(openTelemetry)
    }
}

/**
 * Build a Ktor [HttpClient] with the [KtorClientTelemetry] plugin pre-installed
 * so outbound HTTP requests carry the W3C `traceparent` header populated
 * from the active span context. Replaces direct `HttpClient(CIO)` calls
 * in `:backend:ktor`.
 *
 * Per the `observability-otel-foundation` capability spec § "W3C Trace
 * Context propagation on outbound HTTP from `:backend:ktor` (excluding FCM)",
 * every outbound HTTP request initiated via the JDK / CIO HTTP client SHALL
 * carry `traceparent`. The `KtorClientTelemetry` plugin handles this.
 */
fun httpClientWithOtel(
    openTelemetry: OpenTelemetry = getOpenTelemetry(),
    block: HttpClientConfig<*>.() -> Unit = {},
): HttpClient =
    HttpClient(CIO) {
        install(KtorClientTelemetry) {
            setOpenTelemetry(openTelemetry)
        }
        block()
    }
