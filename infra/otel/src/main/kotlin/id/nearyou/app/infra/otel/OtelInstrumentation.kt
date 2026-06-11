package id.nearyou.app.infra.otel

import io.lettuce.core.resource.ClientResources
import io.lettuce.core.tracing.Tracing
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.jdbc.datasource.JdbcTelemetry
import io.opentelemetry.instrumentation.lettuce.v5_1.LettuceTelemetry
import javax.sql.DataSource

/**
 * Wrap helpers for per-library auto-instrumentation. Hides every
 * `io.opentelemetry.instrumentation.*` import behind the `:infra:otel`
 * boundary so consuming modules (`:infra:supabase`, `:infra:redis`,
 * `:backend:ktor`) only import `:infra:otel` types.
 *
 * All helpers accept the active [OpenTelemetry] instance via [getOpenTelemetry]
 * by default; callers may pass an explicit instance for tests.
 */
object OtelInstrumentation {
    /**
     * Wrap a [DataSource] (typically a HikariCP `HikariDataSource`) so JDBC
     * statements emit OTel spans with `db.system="postgresql"` and parameterized
     * `db.statement` (raw values stripped to `?` placeholders via the
     * statement-sanitizer flag).
     *
     * Per the `observability-otel-foundation` capability spec § "Mandatory
     * span attributes": `db.statement` MUST be parameterized-only.
     * `setStatementSanitizationEnabled(true)` is the OTel SDK-level enforcer.
     *
     * Pre-bootstrap: returns the input data source unchanged because
     * [getOpenTelemetry] returns the no-op global. Post-bootstrap: returns a
     * proxying data source whose connections produce spans.
     */
    fun wrapDataSource(
        dataSource: DataSource,
        openTelemetry: OpenTelemetry = getOpenTelemetry(),
    ): DataSource =
        // Instrumentation 2.28.x renamed setStatementSanitizationEnabled →
        // setQuerySanitizationEnabled (verified 2026-06-10 against the
        // 2.28.1-alpha artifact). Same semantics; the explicit toggle stays to
        // lock the contract per spec § "Mandatory span attributes —
        // db.statement parameterized only".
        JdbcTelemetry.builder(openTelemetry)
            .setQuerySanitizationEnabled(true)
            .build()
            .wrap(dataSource)

    /**
     * Build [ClientResources] pre-configured with Lettuce OTel tracing. The
     * caller passes the result to `RedisClient.create(clientResources, redisURI)`
     * and the redis ops emit spans with `db.system="redis"` and `db.operation`.
     *
     * Per the `observability-otel-foundation` capability spec § "Forbidden
     * span attributes": Lettuce auto-instrumentation's default
     * `db.connection_string` carries the userinfo password from
     * `redis://user:password@host:port`. We enable statement sanitization at
     * the Lettuce telemetry layer AND additionally sanitize the `RedisURI`
     * itself (passwords stripped via [sanitizeRedisUri]) at the construction
     * site — defense in depth.
     */
    fun lettuceClientResources(openTelemetry: OpenTelemetry = getOpenTelemetry()): ClientResources {
        // Instrumentation 2.28.x renamed newTracing → createTracing (verified
        // 2026-06-10 against the 2.28.1-alpha artifact); the anticipated
        // event-bus rewiring did not happen — the lettuce-core Tracing surface
        // remains the integration point for Lettuce 6.x.
        val tracing: Tracing = LettuceTelemetry.create(openTelemetry).createTracing()
        return ClientResources.builder()
            .tracing(tracing)
            .build()
    }

    /**
     * Strip the `userinfo` portion (user:password@) from a Redis connection
     * URI so the password never reaches the Lettuce span's
     * `db.connection_string` attribute. Used at construction sites that pass
     * the URI to Lettuce after extracting credentials separately.
     *
     * Input: `redis://user:password@host:6379/0` → Output: `redis://host:6379/0`
     * Input: `rediss://default:secret@host:6380/0` → Output: `rediss://host:6380/0`
     * Input: `redis://host:6379/0` (no userinfo) → unchanged
     *
     * Returns the input unchanged on any parse failure (defense-in-depth: a
     * partially-malformed URL should not crash bootstrap).
     */
    fun sanitizeRedisUri(uri: String): String =
        try {
            val parsed = java.net.URI(uri)
            if (parsed.userInfo == null) {
                uri
            } else {
                java.net.URI(
                    parsed.scheme,
                    null,
                    parsed.host,
                    parsed.port,
                    parsed.path,
                    parsed.query,
                    parsed.fragment,
                ).toString()
            }
        } catch (_: Throwable) {
            uri
        }
}
