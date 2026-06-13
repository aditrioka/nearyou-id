package id.nearyou.app.admin.routes

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.pebble.Pebble
import io.ktor.server.pebble.PebbleContent
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.pebbletemplates.pebble.loader.ClasspathLoader

/**
 * Renders `index.peb` (the Operational Dashboard) directly against a model —
 * no DB, runs in PR CI — to cover the two template-conditional behaviors the
 * spec pins: the DB-size widget omit path
 * ("Database-size widget is omitted when unavailable") and the deferred-widget
 * negative guard ("Deferred tiles are not rendered"). Mirrors
 * `AdminTemplateEscapeTest`'s standalone-Pebble approach.
 */
class AdminDashboardTemplateTest : StringSpec({

    fun model(overrides: Map<String, Any> = emptyMap()): Map<String, Any> =
        buildMap {
            put("adminName", "Oka")
            put("pendingReports", 0L)
            put("oldestPendingAge", "—")
            put("rejectedLast24h", 0L)
            put("rejectedTopReason", "—")
            put("auditActionsToday", 0L)
            put("auditLastActionType", "—")
            put("postsLast24h", 3L)
            put("postsSpark", listOf(0, 40, 100))
            put("signupsLast24h", 1L)
            put("signupsSpark", listOf(100))
            put("ageGateRejections24h", 0L)
            put("reportsLast24h", 0L)
            put("reportsSpark", emptyList<Int>())
            put("topCities", emptyList<Any>())
            put("dbSizePresent", true)
            put("dbSize", "12.3 MB")
            putAll(overrides)
        }

    "renders the database-size card when the size is present" {
        renderIndex(model(mapOf("dbSizePresent" to true, "dbSize" to "12.3 MB"))) { body ->
            body shouldContain "Database size"
            body shouldContain "12.3 MB"
        }
    }

    "omits the database-size card when the size is unavailable" {
        renderIndex(model(mapOf("dbSizePresent" to false, "dbSize" to "—"))) { body ->
            body shouldNotContain "Database size"
        }
    }

    "does not render any deferred-cluster widget" {
        renderIndex(model()) { body ->
            // Data-source-absent widgets are deferred (spec negative guard).
            listOf(
                "Subscription", "CSAM", "Attestation", "DAU", "MAU",
                "RevenueCat", "Sentry", "Amplitude", "Resend", "Realtime",
            ).forEach { body shouldNotContain it }
        }
    }

    "renders sparkline bars from a per-hour series" {
        renderIndex(model(mapOf("postsSpark" to listOf(0, 40, 100)))) { body ->
            body shouldContain "class=\"spark\""
            body shouldContain "height: 100%"
        }
    }
})

private suspend fun renderIndex(
    model: Map<String, Any>,
    assert: (String) -> Unit,
) {
    testApplication {
        application {
            install(Pebble) {
                loader(ClasspathLoader().apply { prefix = "templates/admin" })
            }
            routing {
                get("/render-index") {
                    call.respond(PebbleContent("index.peb", model = model))
                }
            }
        }
        assert(client.get("/render-index").bodyAsText())
    }
}
