package id.nearyou.app.username

/**
 * The client-side username format guard, mirroring the SHIPPED backend `premium-username-customization`
 * § "Candidate format validation" so a guaranteed-`422 invalid_username` round trip never fires and the
 * inline format feedback is instant (the backend guard remains authoritative — a divergent `422` still
 * maps to [UsernameChangeOutcome.InvalidFormat], never a crash). Pure + deterministic — no UI,
 * unit-testable in commonTest. Mirrors `SearchQueryGuard`.
 *
 * Rules (verbatim from the backend spec): post-trim length 3..30 Unicode code points, charset
 * `^[a-z0-9][a-z0-9_.]*[a-z0-9_]$` (lowercase alphanumerics; dots and underscores middle-only), and NO
 * consecutive dots (an explicit `!candidate.contains("..")` guard, since a single regex cannot cleanly
 * forbid it). A format-valid candidate gates BOTH the network availability probe AND submit.
 */
object UsernameFormatGuard {
    const val MIN_LENGTH: Int = 3
    const val MAX_LENGTH: Int = 30

    private val CHARSET = Regex("^[a-z0-9][a-z0-9_.]*[a-z0-9_]$")

    /** True iff [candidate] satisfies the length, charset, and no-consecutive-dots rules (no trimming —
     *  the field text is the candidate; whitespace is not a valid char so it fails the charset anyway). */
    fun isValid(candidate: String): Boolean {
        val length = codePointCount(candidate)
        if (length < MIN_LENGTH || length > MAX_LENGTH) return false
        if (candidate.contains("..")) return false
        return CHARSET.matches(candidate)
    }

    /** Cap [candidate] at [MAX_LENGTH] code points (the text field's input filter; mirrors
     *  `SearchQueryGuard.cap`). */
    fun cap(candidate: String): String {
        if (codePointCount(candidate) <= MAX_LENGTH) return candidate
        val builder = StringBuilder()
        var count = 0
        var i = 0
        while (i < candidate.length && count < MAX_LENGTH) {
            val ch = candidate[i]
            if (ch.isHighSurrogate() && i + 1 < candidate.length && candidate[i + 1].isLowSurrogate()) {
                builder.append(ch).append(candidate[i + 1])
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
