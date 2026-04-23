package id.nearyou.app.infra.repo

/**
 * Returns the first [n] Unicode code points of this string. Handles surrogate
 * pairs correctly (emoji, astral-plane characters): a single code point may
 * occupy 2 `Char`s, so a naive `take(n)` would split a surrogate pair.
 *
 * Used by the V10 notification emit path for `post_excerpt` / `reply_excerpt`
 * at the documented 80-code-point cap. Strings shorter than [n] are returned
 * as-is.
 */
internal fun String.firstCodePoints(n: Int): String {
    if (n <= 0) return ""
    var cpCount = 0
    var i = 0
    while (i < length && cpCount < n) {
        val cp = codePointAt(i)
        i += Character.charCount(cp)
        cpCount++
    }
    return substring(0, i)
}
