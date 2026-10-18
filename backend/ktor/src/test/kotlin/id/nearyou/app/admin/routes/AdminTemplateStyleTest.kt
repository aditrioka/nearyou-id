package id.nearyou.app.admin.routes

import id.nearyou.app.admin.auth.AdminAuthTestSupport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import java.io.File

/**
 * Regression guard for #525: admin templates once used frame-specific classes
 * (`quota-chip`, `ff-*`, `wl-*`) with no rule in `admin.css`, plus ad-hoc
 * inline `style=` attributes, so those surfaces rendered with browser
 * defaults instead of the board look (docs/11 § 3.6: vendored CSS, no inline
 * styles in production templates). #531 adds the script half: no inline
 * `<script>` / `on*=` handler, so the panel runs under CSP 'self'. Source scan
 * only — no DB, runs in PR CI.
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
            .filter { STYLE_ATTR.containsMatchIn(it.readText()) }
            .map { it.name }
            .shouldBeEmpty()
    }

    "no admin template carries an inline script, inline event handler or eval-dependent htmx feature" {
        // #531: the panel runs under CSP script-src 'self' (no 'unsafe-inline' /
        // 'unsafe-eval') with htmx allowEval:false — any of these would fail silently
        // in the browser (a console event only). Panel JS lives in admin.js.
        templates
            .filter { f ->
                val src = f.readText()
                AdminAuthTestSupport.INLINE_SCRIPT_TAG.containsMatchIn(src) || CSP_BLOCKED.containsMatchIn(src)
            }.map { it.name }
            .shouldBeEmpty()
    }

    "every quantized sparkline bar height spark() can emit has an admin.css class" {
        (SPARK_STEP_PCT..100 step SPARK_STEP_PCT)
            .map { "h-$it" }
            .filter { it !in cssClasses }
            .shouldBeEmpty()
    }

    "every admin banner opens with its board glyph" {
        // board frames 2/4/8-21: each .banner leads with an icon; a bare-text
        // banner reads as an unstyled block next to its glyphed siblings
        templates
            .flatMap { f -> BANNER_WITHOUT_GLYPH.findAll(f.readText()).map { "${f.name}: ${it.value}" }.toList() }
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

private val STYLE_ATTR = Regex("""\sstyle\s*=""")

// on*= handler attributes, hx-on / hx-vars, js:/javascript: values, hx-trigger [filters].
private val CSP_BLOCKED =
    Regex("""\son[a-z]+\s*=|hx-on|hx-vars|\bjs:|javascript:|hx-trigger="[^"]*\[""", RegexOption.IGNORE_CASE)
private val CLASS_ATTR = Regex("""class="([^"]*)"""")
private val PEBBLE_TAG = Regex("""\{%.*?%}|\{\{.*?}}""")
private val CSS_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
private val CSS_CLASS = Regex("""\.([a-zA-Z][\w-]*)""")

private val BANNER_WITHOUT_GLYPH = Regex("""<div class="banner[^"]*">(?!\s*\{\{\s*icon\()""")

// The surface-specific families #525 named: feature flags, wordlist editor, and
// the queue-fragment quota pill. Plus the pager links: appeals.peb pages with
// `pagination-newer` and csam-log-table.peb with `pagination-next`, which once
// had no rule beside `pagination-older`; and the table column hooks (`col-*`).
private val FRAME_FAMILY = Regex("""(ff|wl)-[\w-]+|quota[\w-]*|pagination-[\w-]+|col-[\w-]+""")

private fun classTokens(template: String): List<String> =
    CLASS_ATTR.findAll(template)
        .flatMap { it.groupValues[1].replace(PEBBLE_TAG, " ").split(' ') }
        .filter { it.isNotBlank() }
        .toList()

private fun resource(path: String): File = File(checkNotNull(AdminTemplateStyleTest::class.java.classLoader.getResource(path)).toURI())
