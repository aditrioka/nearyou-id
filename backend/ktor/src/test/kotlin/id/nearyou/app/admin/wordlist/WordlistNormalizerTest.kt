package id.nearyou.app.admin.wordlist

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Pure-logic tests for [WordlistNormalizer] (`admin-moderation-wordlist-editor`):
 * entry normalization aligned to the `KeywordMatcher` reader contract, the
 * staged-merge (entries + add-single + bulk import), the diff, and the cap guards.
 * No DB — the route-level gates (auth/CSRF/rate-limit/empty-list/publish) are in
 * [id.nearyou.app.admin.routes.AdminWordlistEditorRouteTest].
 */
class WordlistNormalizerTest : StringSpec({

    "normalizeEntry trims + lowercases (id-locale) and preserves diacritics" {
        WordlistNormalizer.normalizeEntry("  Dünia  ") shouldBe "dünia"
        WordlistNormalizer.normalizeEntry("ANJING") shouldBe "anjing"
    }

    "normalizeSingle returns null for blank and keeps a leading # literally" {
        WordlistNormalizer.normalizeSingle("   ").shouldBeNull()
        // add-single is literal — '#' is NOT a comment here (CSV-import-only)
        WordlistNormalizer.normalizeSingle("#promo") shouldBe "#promo"
    }

    "normalizeDirect dedups, drops blanks, lowercases, and does NOT strip #" {
        WordlistNormalizer.normalizeDirect("Anjing\nanjing\n\n  \n#literal\nBabi")
            .shouldContainExactly("anjing", "#literal", "babi")
    }

    "parseImport strips # comment + blank lines, splits on newline/comma, dedups" {
        val parsed = WordlistNormalizer.parseImport("# header\n\nfoo, bar\nfoo\n# note")
        parsed.entries.shouldContainExactly("foo", "bar")
        parsed.commentOrBlankSkipped shouldBe 3 // "# header", blank, "# note"
    }

    "parseImport treats a # comment as line-oriented (a comma in a comment does not leak a keyword)" {
        // "# foo, bar" is a comment LINE in full — the comma must NOT promote "bar" to a keyword
        val parsed = WordlistNormalizer.parseImport("# foo, bar\nreal")
        parsed.entries.shouldContainExactly("real")
        parsed.commentOrBlankSkipped shouldBe 1
    }

    "stage merges entries + add-single + import and dedups across all three" {
        val result = WordlistNormalizer.stage(entriesText = "a\nb", addText = "c", importText = "d,b")
        result.shouldBeInstanceOf<StageResult.Valid>()
        result.resulting.shouldContainExactly("a", "b", "c", "d")
        result.report.importAdded shouldBe 1 // only "d" new; "b" already present
        result.report.importDuplicatesSkipped shouldBe 1 // "b"
    }

    "stage collapses case+diacritic collisions to one entry" {
        val result = WordlistNormalizer.stage(entriesText = "Anjïng", addText = "anjïng", importText = "ANJÏNG")
        result.shouldBeInstanceOf<StageResult.Valid>()
        result.resulting.shouldContainExactly("anjïng")
    }

    "stage rejects an over-length entry" {
        val tooLong = "x".repeat(WordlistNormalizer.MAX_ENTRY_LENGTH + 1)
        WordlistNormalizer.stage(entriesText = "ok\n$tooLong", addText = "", importText = "")
            .shouldBeInstanceOf<StageResult.Invalid>()
    }

    "stage rejects an over-cap list" {
        val many = (1..(WordlistNormalizer.MAX_LIST_SIZE + 1)).joinToString("\n") { "w$it" }
        WordlistNormalizer.stage(entriesText = many, addText = "", importText = "")
            .shouldBeInstanceOf<StageResult.Invalid>()
    }

    "an import that only contains duplicates/comments stages zero new entries" {
        val result = WordlistNormalizer.stage(entriesText = "a\nb", addText = "", importText = "# c\na\n\nb")
        result.shouldBeInstanceOf<StageResult.Valid>()
        result.resulting.shouldContainExactly("a", "b")
        result.report.importAdded shouldBe 0
        result.report.importDuplicatesSkipped shouldBe 2 // "a", "b"
        result.report.importCommentBlankSkipped shouldBe 2 // "# c", blank
    }

    "diff counts added/removed by set membership and resulting total" {
        val d = WordlistNormalizer.diff(current = listOf("a", "b", "c"), resulting = listOf("a", "c", "d"))
        d.added shouldBe 1 // d
        d.removed shouldBe 1 // b
        d.resultingTotal shouldBe 3
        d.isNoOp.shouldBeFalse()
    }

    "diff treats a phantom removal (entry never present) as no change" {
        // resulting drops nothing real; "ghost" was never in current
        val d = WordlistNormalizer.diff(current = listOf("a", "b"), resulting = listOf("a", "b"))
        d.removed shouldBe 0
        d.added shouldBe 0
        d.isNoOp.shouldBeTrue()
    }

    "diff isNoOp is true when the resulting set equals current regardless of order" {
        WordlistNormalizer.diff(current = listOf("a", "b"), resulting = listOf("b", "a")).isNoOp.shouldBeTrue()
    }
})
