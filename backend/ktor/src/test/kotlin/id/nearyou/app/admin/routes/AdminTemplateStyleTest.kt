package id.nearyou.app.admin.routes

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import java.io.File

/**
 * Regression guard for #525: admin templates once used frame-specific classes
 * (`quota-chip`, `ff-*`, `wl-*`) with no rule in `admin.css`, plus ad-hoc
 * inline `style=` attributes, so those surfaces rendered with browser
 * defaults instead of the board look (docs/11 § 3.6: vendored CSS, no inline
 * styles in production templates). Source scan only — no DB, runs in PR CI.
 */
class AdminTemplateStyleTest : StringSpec({

    val templates = resource("templates/admin").listFiles { f -> f.extension == "peb" }.orEmpty().toList()
    val cssClasses =
        CSS_CLASS.findAll(resource("admin/static/admin.css").readText().replace(CSS_COMMENT, ""))
            .map { it.groupValues[1] }
            .toSet()

    "no admin template carries an inline style attribute" {
        templates.map { it.name } shouldContain "layout.peb"
        templates
            .filter { STYLE_ATTR.containsMatchIn(it.readText().replace(SPARK_BAR_HEIGHT, "")) }
            .map { it.name }
            .shouldBeEmpty()
    }

    "every frame-family class used by an admin template has an admin.css rule" {
        templates
            .flatMap { f ->
                classTokens(f.readText())
                    .filter { FRAME_FAMILY.matches(it) && it !in cssClasses }
                    .map { "${f.name}: .$it" }
            }.distinct()
            .shouldBeEmpty()
    }
})

// The dashboard sparkline's per-bar height is data, not styling (index.peb), so
// it is the one inline style a class cannot express. Tracked for CSP under #531.
private const val SPARK_BAR_HEIGHT = """<i style="height: {{ h }}%">"""

private val STYLE_ATTR = Regex("""\sstyle\s*=""")
private val CLASS_ATTR = Regex("""class="([^"]*)"""")
private val PEBBLE_TAG = Regex("""\{%.*?%}|\{\{.*?}}""")
private val CSS_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
private val CSS_CLASS = Regex("""\.([a-zA-Z][\w-]*)""")

// The surface-specific families #525 named: feature flags, wordlist editor, and
// the queue-fragment quota pill.
private val FRAME_FAMILY = Regex("""(ff|wl)-[\w-]+|quota[\w-]*""")

private fun classTokens(template: String): List<String> =
    CLASS_ATTR.findAll(template)
        .flatMap { it.groupValues[1].replace(PEBBLE_TAG, " ").split(' ') }
        .filter { it.isNotBlank() }
        .toList()

private fun resource(path: String): File =
    File(checkNotNull(AdminTemplateStyleTest::class.java.classLoader.getResource(path)).toURI())
