package id.nearyou.app.infra.otel

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.context.propagation.TextMapPropagator
import io.opentelemetry.exporter.logging.LoggingSpanExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Functional secret-resolution surface — matches `:backend:ktor`'s
 * `SecretResolver.resolve(name)` shape via Kotlin's SAM-conversion / method
 * reference. Avoids a circular dependency between `:infra:otel` and
 * `:backend:ktor` while keeping the call-site terse:
 *
 * ```kotlin
 * OtelBootstrap.start(env, secrets::resolve)
 * ```
 *
 * Mirrors the `:infra:redis` precedent (`redisKoinModule(env, resolveSecret)`).
 */
fun interface SecretResolver {
    fun resolve(name: String): String?
}

/**
 * Handle returned by [OtelBootstrap.start] for graceful shutdown. Idempotent:
 * calling [shutdown] more than once is safe and a no-op after the first.
 */
class OtelHandle internal constructor(
    private val sdk: OpenTelemetrySdk?,
) {
    private val closed = AtomicBoolean(false)

    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        sdk?.sdkTracerProvider?.shutdown()?.join(5, java.util.concurrent.TimeUnit.SECONDS)
    }
}

/**
 * OTel SDK initialization entrypoint per the `observability-otel-foundation`
 * capability spec § "`OtelBootstrap.start(env, secretResolver)` initializes
 * the SDK at Ktor startup, idempotent and exception-safe".
 *
 * Behavior:
 *
 *  - Resolves slot names via the local [slotName] helper (mirrors
 *    `secretKey(env, name)` from `:backend:ktor`'s `Secrets.kt` — staging
 *    prefixes `staging-`, dev/prod use the bare name).
 *  - Resolves slot values via the supplied [SecretResolver].
 *  - Idempotent: a second call within the same JVM is a no-op + INFO line.
 *  - Exception-safe: misconfiguration / unreachable endpoint does NOT crash
 *    `Application.module()` — the function returns normally with a no-op
 *    `LoggingSpanExporter` fallback and one INFO line.
 *  - Deterministic precedence on either-secret-absent: endpoint checked
 *    first; if endpoint is null, emit `reason="endpoint_missing"` and return
 *    without checking the token.
 *  - The OTLP token VALUE is held only in the local [OtelConfig] reference
 *    and the OTLP exporter's outbound `Authorization` header. NEVER appears
 *    in any log line, span attribute, span name, or HTTP body produced by
 *    `:backend:ktor`.
 */
object OtelBootstrap {
    private val log = LoggerFactory.getLogger(OtelBootstrap::class.java)

    private val initialized = AtomicBoolean(false)
    private val handleRef = AtomicReference<OtelHandle?>(null)

    /**
     * Service name attached as a Resource attribute. Differentiates the
     * `nearyou-staging` vs `nearyou-prod` Grafana stacks even though both
     * stacks live in one Grafana Cloud project (per setup checklist § 3.7).
     */
    private const val SERVICE_NAME_KEY = "service.name"
    private const val CLOUD_REGION_KEY = "cloud.region"

    fun start(
        env: String,
        secretResolver: SecretResolver,
    ): OtelHandle {
        if (!initialized.compareAndSet(false, true)) {
            log.info("event=otel_bootstrap_already_initialized")
            return handleRef.get() ?: OtelHandle(null)
        }

        // Slot names — staging-prefixed in staging, bare in dev/production.
        // These appear in operator-facing error messages so the operator
        // knows which GCP Secret Manager slot to populate. NOT used to
        // drive `secretResolver.resolve(...)`.
        val endpointSlotName = slotName(env, "otel-grafana-otlp-endpoint")
        val tokenSlotName = slotName(env, "otel-grafana-otlp-token")

        // Resolution: call resolver with the bare logical name (no prefix).
        // `EnvVarSecretResolver` uppercases + replaces dashes → looks up
        // env var `OTEL_GRAFANA_OTLP_ENDPOINT` (NOT `STAGING_OTEL_...`).
        // The staging deploy workflow maps the env var to the staging-prefixed
        // Secret Manager slot via `--set-secrets=OTEL_GRAFANA_OTLP_ENDPOINT=staging-otel-grafana-otlp-endpoint:latest`.
        // Convention precedent: every other secret in `:backend:ktor`
        // (redis-url, supabase-service-role-key, firebase-admin-sa, etc.)
        // follows this same shape — see Application.kt line ~412 where the
        // earlier `secretKey()`-prefixed `redisUrl` resolution was caught
        // failing at staging deploy time.
        val endpoint = secretResolver.resolve("otel-grafana-otlp-endpoint")
        // Deterministic precedence per spec: check endpoint first; if absent,
        // emit `reason=endpoint_missing` and return WITHOUT consulting the
        // token slot. Locks the reason field across runs (alerting queries
        // against `event=otel_exporter_disabled reason=endpoint_missing` are
        // stable).
        val handle =
            if (endpoint.isNullOrBlank()) {
                emitDisabled(env, "endpoint_missing", endpointSlotName)
                wireSdk(env, exporter = LoggingSpanExporter.create())
            } else {
                val token = secretResolver.resolve("otel-grafana-otlp-token")
                if (token.isNullOrBlank()) {
                    emitDisabled(env, "token_missing", tokenSlotName)
                    wireSdk(env, exporter = LoggingSpanExporter.create())
                } else {
                    val config = OtelConfig(env = env, endpoint = endpoint, token = token, samplingProfile = env)
                    val live =
                        try {
                            buildOtlpExporter(config)
                        } catch (t: Throwable) {
                            // Misconfiguration during exporter construction (e.g., a
                            // malformed endpoint URL) MUST NOT crash the application.
                            // Fall back to LoggingSpanExporter; emit INFO so the
                            // operator notices.
                            log.info(
                                "event=otel_exporter_disabled reason=exporter_init_failed error_class={} sampling_profile={}",
                                t.javaClass.name,
                                env,
                            )
                            LoggingSpanExporter.create()
                        }
                    log.info(
                        "event=otel_exporter_configured endpoint={} sampling_profile={}",
                        endpoint,
                        env,
                    )
                    wireSdk(env, exporter = live)
                }
            }
        handleRef.set(handle)
        return handle
    }

    /**
     * Idempotency reset surface for tests. Production code MUST NOT call this.
     */
    internal fun resetForTesting() {
        handleRef.get()?.shutdown()
        handleRef.set(null)
        initialized.set(false)
        // Best-effort: clear the global registration. Multiple tests within the
        // same JVM exercise different startup states, so the global slot must
        // be reusable.
        try {
            GlobalOpenTelemetry.resetForTest()
        } catch (_: Throwable) {
            // Older SDK versions may not expose resetForTest; tolerate.
        }
    }

    private fun emitDisabled(
        env: String,
        reason: String,
        slotName: String,
    ) {
        // The `slot` field is the staging-prefixed (or bare in prod) GCP
        // Secret Manager slot name — it tells the operator exactly which
        // slot is missing/unpopulated when triaging a staging deploy.
        log.info(
            "event=otel_exporter_disabled reason={} slot={} sampling_profile={}",
            reason,
            slotName,
            env,
        )
    }

    private fun wireSdk(
        env: String,
        exporter: SpanExporter,
    ): OtelHandle {
        val resource =
            Resource.getDefault().merge(
                Resource.create(
                    Attributes.builder()
                        .put(AttributeKey.stringKey(SERVICE_NAME_KEY), serviceName(env))
                        .put(AttributeKey.stringKey(CLOUD_REGION_KEY), CloudRegionProbe.fetch())
                        .build(),
                ),
            )

        // Defense-in-depth: every exporter is wrapped in
        // ForbiddenAttributeStripper so peer-IP attributes attached by the
        // Ktor 3.x server instrumentation never reach Grafana Tempo.
        val filteredExporter = ForbiddenAttributeStripper(exporter)
        val tracerProvider =
            SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(samplerFor(env))
                .addSpanProcessor(BatchSpanProcessor.builder(filteredExporter).build())
                .build()

        val sdk =
            OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(
                    ContextPropagators.create(
                        TextMapPropagator.composite(
                            io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance(),
                            io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator.getInstance(),
                        ),
                    ),
                )
                .build()

        // Register globally so auto-instrumentation packages (Ktor server,
        // JDBC, Lettuce) and the `withSpan` helper share the same pipeline.
        try {
            GlobalOpenTelemetry.set(sdk)
        } catch (t: Throwable) {
            // GlobalOpenTelemetry.set throws on second registration in the
            // same JVM. The idempotency guard above SHOULD prevent this in
            // production; tolerate to keep startup resilient.
            log.info(
                "event=otel_global_already_set message={}",
                t.message,
            )
        }
        return OtelHandle(sdk)
    }

    private fun buildOtlpExporter(config: OtelConfig): SpanExporter =
        OtlpHttpSpanExporter.builder()
            .setEndpoint(traceEndpoint(config.endpoint!!))
            // Grafana Cloud OTLP gateway expects `Authorization: Basic <base64>`
            // where the base64 payload is `<instance_id>:<api_token>`. The
            // wizard at https://<stack>.grafana.net/.../otel/instrumentation
            // generates this base64 string for the operator; we store it
            // verbatim in the `otel-grafana-otlp-token` Secret Manager slot
            // and prepend the `Basic ` scheme here. Bearer is wrong for
            // Grafana Cloud (was a copy-paste from the OTel Java docs which
            // assume vendor-agnostic OAuth2 — Grafana Cloud is HTTP Basic).
            .addHeader("Authorization", "Basic ${config.token}")
            .build()

    /**
     * Grafana Cloud Tempo's OTLP/HTTP endpoint expects the suffix
     * `/v1/traces` for span ingest; the slot value stored in Secret Manager
     * is the base URL (e.g., `https://tempo-prod-XX.grafana.net/tempo`). If
     * the operator already appended `/v1/traces` (idempotent setup), respect
     * it; otherwise append it here.
     */
    internal fun traceEndpoint(base: String): String {
        val trimmed = base.trimEnd('/')
        return if (trimmed.endsWith("/v1/traces")) trimmed else "$trimmed/v1/traces"
    }

    /**
     * Service name that distinguishes the `nearyou-staging` Grafana stack
     * from `nearyou-prod`. The staging stack receives spans tagged
     * `service.name=nearyou-staging`; production receives `nearyou-prod`.
     * Local dev gets `nearyou-dev` so spans surfacing in the no-op
     * exporter (LoggingSpanExporter at DEBUG severity) carry an
     * identifiable tag if a developer enables DEBUG logging.
     */
    private fun serviceName(env: String): String =
        when (env) {
            "staging" -> "nearyou-staging"
            "production" -> "nearyou-prod"
            else -> "nearyou-dev"
        }

    /**
     * Mirror of `secretKey(env, name)` from `:backend:ktor`'s `Secrets.kt`.
     * Inlined here to keep the `:infra:otel` boundary clean — taking a
     * dependency on `:backend:ktor` would invert the module hierarchy.
     * The duplication trade-off is identical to the `:infra:redis`
     * precedent (`redisUrlSlot(env)`).
     */
    internal fun slotName(
        env: String,
        name: String,
    ): String = if (env == "staging") "staging-$name" else name
}

/**
 * Returns the global OpenTelemetry instance once the SDK has been initialized
 * via [OtelBootstrap.start]. Pre-init or in tests that do not bootstrap, this
 * returns the no-op global from the SDK — auto-instrumentation packages
 * silently degrade to no-op. Useful at JDBC / Lettuce wrap sites in
 * `:backend:ktor` where the wrap call is unconditional but the SDK may or may
 * not be live.
 */
fun getOpenTelemetry(): OpenTelemetry = GlobalOpenTelemetry.get()
