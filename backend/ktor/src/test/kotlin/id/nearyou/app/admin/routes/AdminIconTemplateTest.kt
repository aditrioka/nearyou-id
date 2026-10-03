package id.nearyou.app.admin.routes

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
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
import java.io.File

/**
 * Regression guard for #501: admin templates once used Material-Symbols
 * markup (`<span class="ms">bolt</span>`) with no icon font vendored and no
 * `.ms` rule, so the glyph names leaked as literal text ("boltExpedite now").
 * The panel's icon idiom is the inline-SVG `icon()` macro in `icons.peb`
 * (admin-panel-scaffold § Shared base layout).
 *
 * 1. Source scan: no `templates/admin` file may reintroduce `class="ms"`.
 * 2. Render: every template that carried the spans (plus the frame-6
 *    `user-profile.peb` redline, #282) emits the expected `data-icon` glyphs
 *    and never the `missing-<name>` sentinel the macro prints for an unknown
 *    name. Standalone Pebble render — no DB, runs in PR CI.
 */
class AdminIconTemplateTest : StringSpec({

    "no admin template uses Material-Symbols class=\"ms\" markup" {
        val dir = File(checkNotNull(AdminIconTemplateTest::class.java.classLoader.getResource("templates/admin")).toURI())
        val templates = dir.listFiles { f -> f.extension == "peb" }.orEmpty()
        templates.map { it.name } shouldContain "icons.peb"
        templates.filter { MS_MARKUP in it.readText() }.map { it.name }.shouldBeEmpty()
    }

    RENDER_CASES.forEach { case ->
        "${case.template} renders ${case.icons.joinToString()} via the icon() macro" {
            renderTemplate(case.template, case.model) { body ->
                body shouldNotContain MS_MARKUP
                body shouldNotContain "data-icon=\"missing-"
                case.icons.forEach { body shouldContain "data-icon=\"$it\"" }
            }
        }
    }

    "user-profile.peb history chips are toned per action and username rows are self-attributed" {
        renderTemplate("user-profile.peb", profileModel()) { body ->
            body shouldContain "<span class=\"st err\">user_suspended</span>"
            body shouldContain "<span class=\"st pend\">user_warned</span>"
            body shouldContain "<span class=\"st neut\">username_changed</span>"
            body shouldContain "<td>self</td>"
        }
    }
})

private const val MS_MARKUP = "class=\"ms\""

private data class RenderCase(val template: String, val model: Map<String, Any>, val icons: List<String>)

private val deletionModel: Map<String, Any> =
    mapOf(
        "hasRows" to true,
        "rows" to listOf(mapOf("expedited" to false, "dueNow" to false, "expediteAction" to "/admin/deletion-requests/x/expedite")),
    )

private val exportModel: Map<String, Any> =
    mapOf(
        "hasRows" to true,
        "rows" to listOf(mapOf("delivered" to false, "triggerable" to true, "triggerAction" to "/admin/data-exports/x/trigger")),
    )

private val csamModel: Map<String, Any> =
    mapOf(
        "pendingCount" to 1L,
        "hasRows" to true,
        "rows" to
            listOf(
                mapOf(
                    "id" to "x",
                    "kominfoFiled" to false,
                    "kominfoAction" to "/admin/csam/x/kominfo",
                    "hasMetadata" to true,
                    "decryptAction" to "/admin/csam/x/decrypt",
                ),
            ),
    )

private fun profileModel(): Map<String, Any> =
    mapOf(
        "csrfToken" to "t",
        "adminRole" to "owner",
        "quotaUsed" to 3,
        "quotaCap" to 20,
        "profile" to
            mapOf(
                "id" to "7c1f43aa-0000-0000-0000-000000000000",
                "username" to "budi_kopi",
                "displayName" to "Budi",
                "isPremium" to true,
                "subscriptionStatus" to "active",
                "isBanned" to true,
                "isPermanentlyBanned" to false,
                "isShadowBanned" to false,
                "statusLabel" to "Suspended until 2026-06-18T09:42:00Z",
            ),
        "history" to
            listOf(
                historyAction("user_suspended"),
                historyAction("user_warned"),
                mapOf("kind" to "username", "at" to "2026-04-02T10:11:00Z", "oldUsername" to "kopibudi", "newUsername" to "budi_kopi"),
            ),
    )

private fun historyAction(type: String): Map<String, Any> =
    mapOf(
        "kind" to "action",
        "at" to "2026-06-11T09:42:00Z",
        "adminDisplayName" to "oka",
        "actionType" to type,
        "reason" to "—",
        "beforeState" to "—",
        "afterState" to "—",
    )

private val RENDER_CASES =
    listOf(
        RenderCase("deletion-requests.peb", deletionModel, listOf("warning", "bolt")),
        RenderCase("deletion-requests-table.peb", deletionModel, listOf("bolt")),
        RenderCase("data-exports.peb", exportModel, listOf("info", "play_arrow")),
        RenderCase("data-exports-table.peb", exportModel, listOf("play_arrow")),
        RenderCase("csam-log.peb", csamModel, listOf("gpp_maybe", "bolt", "filter_alt", "info", "gavel", "key")),
        RenderCase("csam-log-table.peb", csamModel, listOf("gavel", "key")),
        RenderCase(
            "user-profile.peb",
            profileModel(),
            listOf(
                "workspace_premium", "speed", "badge", "gavel", "notification_important",
                "timer", "lock_open", "block", "visibility_off", "history",
            ),
        ),
    )

private suspend fun renderTemplate(
    template: String,
    model: Map<String, Any>,
    assert: (String) -> Unit,
) {
    testApplication {
        application {
            install(Pebble) {
                loader(ClasspathLoader().apply { prefix = "templates/admin" })
            }
            routing {
                get("/render") {
                    call.respond(PebbleContent(template, model = model))
                }
            }
        }
        assert(client.get("/render").bodyAsText())
    }
}
