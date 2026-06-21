package id.nearyou.app.admin.wordlist

import java.util.Locale

/**
 * Pure (no I/O) normalization + staging + diff for the moderation wordlist editor
 * (`admin-moderation-wordlist-editor`). Entries are normalized to the exact form
 * the `KeywordMatcher` reader compares against (`content-moderation-keyword-lists`):
 * trimmed, lowercased with the Indonesian locale (diacritics preserved), and
 * de-duplicated (the matcher counts DISTINCT keywords, so duplicates are inert).
 */
object WordlistNormalizer {
    /** Per-entry character ceiling (design D5). */
    const val MAX_ENTRY_LENGTH: Int = 100

    /** Resulting-list size ceiling (design D5) — well above realistic curation. */
    const val MAX_LIST_SIZE: Int = 10_000

    private val ID_LOCALE: Locale = Locale.forLanguageTag("id")

    /** Trim + Indonesian-locale lowercase a single token (diacritics preserved). */
    fun normalizeEntry(raw: String): String = raw.trim().lowercase(ID_LOCALE)

    /** A single add-single entry, normalized; `null` when blank. Taken literally — a
     *  leading `#` is NOT stripped here (that is bulk-import-only syntax, design D5/N1). */
    fun normalizeSingle(raw: String): String? = normalizeEntry(raw).takeIf { it.isNotEmpty() }

    /**
     * Direct-edit normalization for the staged-list textarea: split on lines,
     * normalize each, drop blanks, dedup (order-preserving, first occurrence wins).
     * Does NOT strip `#` lines — the textarea is the literal staged list, not a
     * paste of repo-file comment syntax (design D5/N1).
     */
    fun normalizeDirect(text: String): List<String> =
        text.lineSequence().map { normalizeEntry(it) }.filter { it.isNotEmpty() }.toList().distinct()

    /**
     * Parse bulk-import text (newline- or comma-separated): normalize each token,
     * skip blank lines AND leading-`#` comment lines (Tier-3 repo-file syntax, NOT
     * Remote Config array entries — design D5), dedup. The skipped count feeds the
     * import report.
     */
    fun parseImport(text: String): ImportParse {
        var commentOrBlankSkipped = 0
        val out = mutableListOf<String>()
        for (line in text.lineSequence()) {
            val trimmedLine = line.trim()
            // Comment + blank detection is LINE-oriented: a leading-`#` line is a
            // comment in full, so `# foo, bar` is skipped entirely (NOT split on the
            // comma into a stray `bar` keyword).
            if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) {
                commentOrBlankSkipped++
                continue
            }
            // Within a non-comment line, commas separate entries (single-column
            // lists are one-per-line; comma-separated is the convenience form).
            for (token in trimmedLine.split(',')) {
                val t = token.trim()
                if (t.isNotEmpty()) out += normalizeEntry(t)
            }
        }
        return ImportParse(entries = out.distinct(), commentOrBlankSkipped = commentOrBlankSkipped)
    }

    /**
     * Build the resulting staged list from the three inputs: the direct-edit
     * textarea (`entriesText`), an optional literal add-single (`addText`), and an
     * optional bulk import (`importText`, CSV rules). Order: entries → add → new
     * import entries; deduped across all three. Returns [StageResult.Invalid] when
     * any entry exceeds [MAX_ENTRY_LENGTH] or the resulting list exceeds
     * [MAX_LIST_SIZE] (no publish on either).
     */
    fun stage(
        entriesText: String,
        addText: String,
        importText: String,
    ): StageResult {
        val resulting = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        fun include(entry: String) {
            if (entry.isNotEmpty() && seen.add(entry)) resulting += entry
        }

        normalizeDirect(entriesText).forEach { include(it) }
        normalizeSingle(addText)?.let { include(it) }

        val parsed = parseImport(importText)
        var importAdded = 0
        var importDuplicatesSkipped = 0
        for (entry in parsed.entries) {
            if (entry in seen) {
                importDuplicatesSkipped++
            } else {
                include(entry)
                importAdded++
            }
        }

        resulting.firstOrNull { it.length > MAX_ENTRY_LENGTH }?.let { tooLong ->
            return StageResult.Invalid(
                "Entry exceeds the $MAX_ENTRY_LENGTH-character limit: \"${tooLong.take(24)}…\". No change made.",
            )
        }
        if (resulting.size > MAX_LIST_SIZE) {
            return StageResult.Invalid("Resulting list (${resulting.size}) exceeds the $MAX_LIST_SIZE-entry cap. No change made.")
        }

        return StageResult.Valid(
            resulting = resulting,
            report = StagingReport(importAdded, importDuplicatesSkipped, parsed.commentOrBlankSkipped),
        )
    }

    /**
     * Diff a [resulting] list against the [current] Server-template list, by SET
     * membership (the matcher is order-independent). A staged removal of an entry
     * not in [current] does not increment `removed` (it was never there).
     */
    fun diff(
        current: List<String>,
        resulting: List<String>,
    ): WordlistDiff {
        val currentSet = current.toSet()
        val resultingSet = resulting.toSet()
        return WordlistDiff(
            added = resultingSet.count { it !in currentSet },
            removed = currentSet.count { it !in resultingSet },
            resultingTotal = resulting.size,
        )
    }
}

/** Parsed bulk-import result: the normalized+deduped [entries] + the skipped count. */
data class ImportParse(
    val entries: List<String>,
    val commentOrBlankSkipped: Int,
)

/** The import-merge report surfaced to the operator after a preview/publish. */
data class StagingReport(
    val importAdded: Int,
    val importDuplicatesSkipped: Int,
    val importCommentBlankSkipped: Int,
)

/** Set-membership diff of a staged list vs the current Server-template list. */
data class WordlistDiff(
    val added: Int,
    val removed: Int,
    val resultingTotal: Int,
) {
    val isNoOp: Boolean get() = added == 0 && removed == 0
}

/** Outcome of building a staged list: a normalized [Valid] result or an [Invalid] cap violation. */
sealed interface StageResult {
    data class Valid(val resulting: List<String>, val report: StagingReport) : StageResult

    data class Invalid(val message: String) : StageResult
}
