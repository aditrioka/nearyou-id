package id.nearyou.app.search

/**
 * The client-side query-length guard, mirroring the backend `premium-search` § "Query length guard
 * 2..100" so a guaranteed-`400` round trip never fires (a UX optimization — the backend guard remains
 * authoritative; a divergent `400` still maps to [SearchOutcome.Error], never a crash). Pure +
 * deterministic — no UI, unit-testable in commonTest.
 */
object SearchQueryGuard {
    const val MIN_LENGTH: Int = 2
    const val MAX_LENGTH: Int = 100

    /** Trim leading/trailing Unicode whitespace; the trimmed string is what the request sends. */
    fun normalize(query: String): String = query.trim()

    /** The trimmed length in Unicode code points (so a surrogate-pair character counts as one). */
    fun codePointLength(query: String): Int = normalize(query).let { codePointCount(it) }

    /**
     * Eligible to fetch iff the trimmed query is between [MIN_LENGTH] and [MAX_LENGTH] code points. A
     * below-[MIN_LENGTH] query keeps the screen in the Idle state (no request); the field caps input at
     * [MAX_LENGTH].
     */
    fun isEligible(query: String): Boolean = codePointLength(query) in MIN_LENGTH..MAX_LENGTH

    /** Cap [query] at [MAX_LENGTH] code points (the text field's input filter). */
    fun cap(query: String): String {
        if (codePointCount(query) <= MAX_LENGTH) return query
        val builder = StringBuilder()
        var count = 0
        var i = 0
        while (i < query.length && count < MAX_LENGTH) {
            val ch = query[i]
            if (ch.isHighSurrogate() && i + 1 < query.length && query[i + 1].isLowSurrogate()) {
                builder.append(ch).append(query[i + 1])
                i += 2
            } else {
                builder.append(ch)
                i += 1
            }
            count++
        }
        return builder.toString()
    }

    /** Unicode code-point count (surrogate pairs counted once). */
    private fun codePointCount(value: String): Int {
        var count = 0
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            i +=
                if (ch.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate()) 2 else 1
            count++
        }
        return count
    }
}
