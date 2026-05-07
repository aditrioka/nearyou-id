package id.nearyou.app.infra.otel

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.trace.data.LinkData
import io.opentelemetry.sdk.trace.samplers.Sampler
import io.opentelemetry.sdk.trace.samplers.SamplingDecision

class SamplingProfileTest : StringSpec({
    "dev environment samples at 100%" {
        val sampler = samplerFor("dev")
        repeat(100) {
            val decision = sampler.shouldSampleRoot()
            decision shouldBe SamplingDecision.RECORD_AND_SAMPLE
        }
    }

    "staging environment samples at 100% (parity with dev)" {
        val sampler = samplerFor("staging")
        repeat(100) {
            val decision = sampler.shouldSampleRoot()
            decision shouldBe SamplingDecision.RECORD_AND_SAMPLE
        }
        // Defense against a copy-paste regression that would silently demote
        // staging to the prod 10%-base shape: assert staging is NOT the
        // production parent-based ratio sampler.
        sampler shouldNotBe samplerFor("production")
    }

    "production environment samples at 10% base across 1000 trace seeds" {
        val sampler = samplerFor("production")
        var sampled = 0
        repeat(1000) { idx ->
            // Generate a deterministic trace id per iteration so the test is
            // reproducible but exercises a wide range of trace-id seeds.
            // Pad to 32 hex chars; xor mixes the low bits into the upper half
            // so consecutive iterations don't share trace-id prefixes.
            val mixed = (idx.toLong() * 6364136223846793005L) xor (idx.toLong() shl 17)
            val traceId = "%016x%016x".format(mixed.inv(), mixed)
            val decision =
                sampler.shouldSample(
                    Context.root(),
                    traceId,
                    "test-span",
                    SpanKind.SERVER,
                    Attributes.empty(),
                    emptyList<LinkData>(),
                ).decision
            if (decision == SamplingDecision.RECORD_AND_SAMPLE) sampled += 1
        }
        // Statistical tolerance for a 10% target across 1000 seeds (per spec
        // scenario): 5%-15% inclusive.
        (sampled in 50..150) shouldBe true
    }

    "production sampler does NOT force-keep an error span (deferred to Collector follow-up)" {
        val sampler = samplerFor("production")
        // Pick a trace-id seed that the 10% ratio drops, then assert the
        // sampler still drops it even when the span is tagged with an
        // `http.status_code = 500` attribute. Locks the deferral: a future
        // SDK-level force-keep regression would surface as a behavioral change.
        val droppedSeed = findDroppedSeed(sampler)
        val decision =
            sampler.shouldSample(
                Context.root(),
                droppedSeed,
                "GET /timeline",
                SpanKind.SERVER,
                Attributes.builder()
                    .put("http.status_code", 500L)
                    .build(),
                emptyList<LinkData>(),
            ).decision
        decision shouldNotBe SamplingDecision.RECORD_AND_SAMPLE
    }

    "production sampler does NOT force-keep a slow span (deferred to Collector follow-up)" {
        val sampler = samplerFor("production")
        val droppedSeed = findDroppedSeed(sampler)
        // Duration cannot be set at sample-time (the span hasn't run yet),
        // but we approximate the spec scenario: an attribute-tagged-with-
        // duration-hint span is still subject to the base ratio. The actual
        // Collector tail-sampling layer would inspect duration post-end.
        val decision =
            sampler.shouldSample(
                Context.root(),
                droppedSeed,
                "GET /timeline",
                SpanKind.SERVER,
                Attributes.builder()
                    .put("http.status_code", 200L)
                    .put("duration.ms.hint", 800L)
                    .build(),
                emptyList<LinkData>(),
            ).decision
        decision shouldNotBe SamplingDecision.RECORD_AND_SAMPLE
    }

    "production sampler drops a fast healthy span outside the base ratio" {
        val sampler = samplerFor("production")
        val droppedSeed = findDroppedSeed(sampler)
        val decision =
            sampler.shouldSample(
                Context.root(),
                droppedSeed,
                "GET /timeline",
                SpanKind.SERVER,
                Attributes.builder()
                    .put("http.status_code", 200L)
                    .build(),
                emptyList<LinkData>(),
            ).decision
        decision shouldNotBe SamplingDecision.RECORD_AND_SAMPLE
    }
})

/**
 * Helper: scan trace-id seeds until one falls in the production sampler's
 * "drop" window. Bounded loop guards against an infinite spin if the sampler
 * is mis-configured and always-sampling.
 */
private fun findDroppedSeed(sampler: Sampler): String {
    repeat(10_000) { idx ->
        val mixed = (idx.toLong() * 2862933555777941757L) xor (idx.toLong() shl 23)
        val seed = "%016x%016x".format(mixed.inv(), mixed)
        val decision =
            sampler.shouldSample(
                Context.root(),
                seed,
                "probe",
                SpanKind.INTERNAL,
                Attributes.empty(),
                emptyList<LinkData>(),
            ).decision
        if (decision != SamplingDecision.RECORD_AND_SAMPLE) return seed
    }
    error("sampler appears to be always-on; unable to find a dropped seed")
}

private fun Sampler.shouldSampleRoot(): SamplingDecision =
    shouldSample(
        Context.root(),
        "00000000000000000000000000000001",
        "test",
        SpanKind.INTERNAL,
        Attributes.empty(),
        emptyList<LinkData>(),
    ).decision
