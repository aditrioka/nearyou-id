package id.nearyou.app.infra.otel

import io.opentelemetry.sdk.trace.samplers.Sampler

/**
 * Returns the [Sampler] for the given environment per the
 * `observability-otel-foundation` capability spec § "Sampling profile per environment":
 *
 *  - `"dev"` and `"staging"` → [Sampler.alwaysOn] (head 100%, parity for the
 *    Phase 2 §14 benchmark surface).
 *  - `"production"` → [Sampler.parentBased] over [Sampler.traceIdRatioBased]
 *    at [PRODUCTION_BASE_SAMPLING_RATIO] (10% base ratio).
 *
 * **No force-keep `SpanProcessor` is created in this change** — design § D4
 * deferred force-keep promotion to the `observability-otel-collector-tail-sampling`
 * follow-up because the SDK-level approach (secondary `BatchSpanProcessor`
 * with re-emission) loses trace_id linkage.
 *
 * Any unknown env value is treated as production (defensive: a typo in
 * `KTOR_ENV` should NOT silently flip a real prod deployment to head-100%
 * sampling).
 */
internal fun samplerFor(env: String): Sampler =
    when (env) {
        "dev", "staging" -> Sampler.alwaysOn()
        else -> Sampler.parentBased(Sampler.traceIdRatioBased(PRODUCTION_BASE_SAMPLING_RATIO))
    }
