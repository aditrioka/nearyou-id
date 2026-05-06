package id.nearyou.app.infra.otel.testing

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.ktor.v3_0.KtorClientTelemetry

/**
 * Test-fixture helper: builds a Ktor [HttpClient] backed by [MockEngine]
 * with the [KtorClientTelemetry] plugin pre-installed against the supplied
 * [openTelemetry] instance. Consumers test outbound `traceparent`
 * propagation without leaking direct
 * `io.opentelemetry.instrumentation.ktor.v3_0.*` imports across module
 * boundaries.
 *
 * Mirrors the production [id.nearyou.app.infra.otel.httpClientWithOtel]
 * shape but for tests that need to capture / inject HTTP responses
 * deterministically.
 */
fun instrumentedMockClient(
    openTelemetry: OpenTelemetry,
    handler: MockRequestHandler,
): HttpClient =
    HttpClient(MockEngine(handler)) {
        expectSuccess = false
        install(KtorClientTelemetry) {
            setOpenTelemetry(openTelemetry)
        }
    }
