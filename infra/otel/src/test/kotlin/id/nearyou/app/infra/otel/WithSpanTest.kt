package id.nearyou.app.infra.otel

import id.nearyou.app.infra.otel.testing.SpanRecorder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.CancellationException

class WithSpanTest : StringSpec({
    beforeEach {
        // Each test mounts a fresh in-process pipeline backed by SpanRecorder.
        // Reset the global slot so the new pipeline takes effect.
        try {
            GlobalOpenTelemetry.resetForTest()
        } catch (_: Throwable) {
            // Ignore on SDKs that don't expose the reset.
        }
    }

    "block returns normally → span ends with OK status and helper returns the value" {
        val (sdk, recorder) = SpanRecorder.newPipeline()
        GlobalOpenTelemetry.set(sdk)
        val result = withSpan("test.op") { 42 }
        result shouldBe 42
        val captured = recorder.recordedSpans()
        captured shouldHaveSize 1
        captured[0].name shouldBe "test.op"
        // OTel spans default to OK status when no explicit error is set.
        (captured[0].status.statusCode in setOf(StatusCode.OK, StatusCode.UNSET)) shouldBe true
    }

    "block throws Exception subclass → span recorded as ERROR + recordException + re-thrown" {
        val (sdk, recorder) = SpanRecorder.newPipeline()
        GlobalOpenTelemetry.set(sdk)
        val ex =
            shouldThrow<IllegalStateException> {
                withSpan("test.op") { throw IllegalStateException("boom") }
            }
        ex.message shouldBe "boom"
        val captured = recorder.recordedSpans()
        captured shouldHaveSize 1
        captured[0].status.statusCode shouldBe StatusCode.ERROR
        // recordException attaches an exception event.
        val exceptionEvents = captured[0].events.filter { it.name == "exception" }
        exceptionEvents shouldHaveSize 1
    }

    "block throws CancellationException → recorded as ERROR and re-thrown (NOT swallowed)" {
        val (sdk, recorder) = SpanRecorder.newPipeline()
        GlobalOpenTelemetry.set(sdk)
        shouldThrow<CancellationException> {
            withSpan("test.op") { throw CancellationException("cancelled by caller") }
        }
        val captured = recorder.recordedSpans()
        captured shouldHaveSize 1
        captured[0].status.statusCode shouldBe StatusCode.ERROR
    }

    "block throws Throwable subclass (Error) → span ends via try/finally" {
        val (sdk, recorder) = SpanRecorder.newPipeline()
        GlobalOpenTelemetry.set(sdk)
        shouldThrow<OutOfMemoryError> {
            withSpan("test.op") { throw OutOfMemoryError("simulated") }
        }
        val captured = recorder.recordedSpans()
        // The span MUST end via try/finally even though OutOfMemoryError is
        // an Error subclass, not Exception.
        captured shouldHaveSize 1
        captured[0].status.statusCode shouldBe StatusCode.ERROR
    }

    "nested withSpan calls produce parent-child relationship in trace tree" {
        val (sdk, recorder) = SpanRecorder.newPipeline()
        GlobalOpenTelemetry.set(sdk)
        withSpan("outer") {
            withSpan("inner") {
                Unit
            }
        }
        val captured = recorder.recordedSpans()
        captured shouldHaveSize 2
        val inner = captured.first { it.name == "inner" }
        val outer = captured.first { it.name == "outer" }
        // Both spans share the same trace id; inner's parent is outer.
        inner.traceId shouldBe outer.traceId
        inner.parentSpanId shouldBe outer.spanId
    }

    "caller-provided attributes appear verbatim on the captured span" {
        val (sdk, recorder) = SpanRecorder.newPipeline()
        GlobalOpenTelemetry.set(sdk)
        withSpan("test.op", mapOf("foo" to "bar", "n" to 42L)) { Unit }
        val captured = recorder.recordedSpans()
        captured shouldHaveSize 1
        val attrs = captured[0].attributes
        attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey("foo")) shouldBe "bar"
        attrs.get(io.opentelemetry.api.common.AttributeKey.longKey("n")) shouldBe 42L
    }
})
