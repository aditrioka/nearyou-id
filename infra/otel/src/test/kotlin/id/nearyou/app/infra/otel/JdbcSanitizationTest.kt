package id.nearyou.app.infra.otel

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.infra.otel.testing.SpanRecorder
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.opentelemetry.api.GlobalOpenTelemetry

/**
 * JDBC sanitization regression test per `observability-otel-foundation`
 * capability spec § "JDBC span carries parameterized db.statement".
 *
 * Locks `OtelInstrumentation.wrapDataSource(...)`'s
 * `setStatementSanitizationEnabled(true)` flag against a future SDK upgrade
 * or refactor regression: a query carrying a literal sentinel string in a
 * `PreparedStatement` parameter MUST emit a JDBC span whose `db.statement`
 * attribute contains `?` placeholders and does NOT contain the sentinel
 * value.
 *
 * Uses H2 in-memory database — exercises the wrap helper end-to-end without
 * needing a Postgres container.
 */
class JdbcSanitizationTest : StringSpec({
    beforeEach {
        try {
            GlobalOpenTelemetry.resetForTest()
        } catch (_: Throwable) {
            // tolerate older SDKs without resetForTest
        }
    }

    "wrapDataSource strips raw values from db.statement (sentinel-string scan)" {
        val sentinelValue = "sentinel-jdbc-DO-NOT-LEAK"
        val (sdk, recorder) = SpanRecorder.newPipeline()
        GlobalOpenTelemetry.set(sdk)

        val raw =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = "jdbc:h2:mem:otel-jdbc-${java.util.UUID.randomUUID()};DB_CLOSE_DELAY=-1"
                    driverClassName = "org.h2.Driver"
                    maximumPoolSize = 2
                    initializationFailTimeout = -1
                },
            )
        try {
            // Seed schema + a row to exercise a parameterized SELECT.
            raw.connection.use { c ->
                c.createStatement().use { st ->
                    st.execute("CREATE TABLE IF NOT EXISTS items (id INT PRIMARY KEY, label VARCHAR(255))")
                    st.execute("INSERT INTO items(id, label) VALUES (1, 'seed')")
                }
            }

            val wrapped = OtelInstrumentation.wrapDataSource(raw)
            // Within a `withSpan` so the JDBC span has a parent context to
            // attach to (the SDK pipeline still records orphan spans, but
            // wrapping makes the trace tree match production shape).
            withSpan("test.jdbc.sanitization") {
                wrapped.connection.use { c ->
                    c.prepareStatement("SELECT id FROM items WHERE label = ?").use { ps ->
                        ps.setString(1, sentinelValue)
                        ps.executeQuery().use { rs ->
                            // Drain the result — drives the span end.
                            while (rs.next()) {
                                rs.getInt("id")
                            }
                        }
                    }
                }
            }

            val spans = recorder.recordedSpans()
            // The JDBC instrumentation emits a SELECT span; find it by its
            // `db.statement` attribute presence.
            val dbStatementKey = io.opentelemetry.api.common.AttributeKey.stringKey("db.statement")
            val jdbcSpans = spans.filter { it.attributes.get(dbStatementKey) != null }
            // At minimum one JDBC span must have been emitted by the SELECT.
            jdbcSpans.isEmpty() shouldBe false

            for (span in jdbcSpans) {
                val stmt = span.attributes.get(dbStatementKey)
                stmt shouldNotBe null
                // Sanitizer replaces literal values with `?` placeholders.
                stmt!! shouldContain "?"
                // Defense in depth: the raw sentinel value MUST NOT appear
                // anywhere in the captured statement.
                stmt shouldNotContain sentinelValue
            }
        } finally {
            raw.close()
        }
    }
})
