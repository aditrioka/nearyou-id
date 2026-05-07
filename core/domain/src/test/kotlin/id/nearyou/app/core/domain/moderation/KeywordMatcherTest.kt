package id.nearyou.app.core.domain.moderation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for [KeywordMatcher]. Covers all 11 spec scenarios in
 * `### Requirement: KeywordMatcher is a pure-Kotlin Aho-Corasick engine in :core:domain`
 * (8 round-0 + 3 round-1 additions) plus 4 implementation edge cases:
 *  - keyword equal to entire content
 *  - overlapping Aho-Corasick suffix-link emission
 *  - Indonesian non-ASCII content lowercased
 *  - long content × many keywords (no quadratic blowup)
 */
class KeywordMatcherTest : StringSpec({

    "Single match returns one element" {
        val result = KeywordMatcher.match("apa kabar dunia", listOf("kabar", "salam"))
        result shouldBe MatchResult(matchedKeywords = listOf("kabar"), matchCount = 1)
    }

    "Multiple distinct matches in first-occurrence order" {
        val result =
            KeywordMatcher.match(
                "ini adalah teks yang penuh dengan kata-kata",
                listOf("ini", "yang", "kata"),
            )
        result.matchedKeywords shouldBe listOf("ini", "yang", "kata")
        result.matchCount shouldBe 3
    }

    "Repeated occurrence of one keyword counts once" {
        val result = KeywordMatcher.match("foo bar foo baz foo", listOf("foo"))
        result shouldBe MatchResult(matchedKeywords = listOf("foo"), matchCount = 1)
    }

    "Word boundary respected — embedded substring does not match" {
        val result = KeywordMatcher.match("foobar barfoo foo", listOf("foo"))
        result shouldBe MatchResult(matchedKeywords = listOf("foo"), matchCount = 1)
    }

    "Case-insensitive match" {
        val result = KeywordMatcher.match("APA KABAR", listOf("kabar"))
        result shouldBe MatchResult(matchedKeywords = listOf("kabar"), matchCount = 1)
    }

    "Empty keyword list returns no-match" {
        val result = KeywordMatcher.match("any text", emptyList())
        result shouldBe MatchResult(matchedKeywords = emptyList(), matchCount = 0)
    }

    "Empty content returns no-match" {
        val result = KeywordMatcher.match("", listOf("foo", "bar"))
        result shouldBe MatchResult(matchedKeywords = emptyList(), matchCount = 0)
    }

    "Punctuation and whitespace boundaries both match" {
        val result = KeywordMatcher.match("foo, bar! baz.", listOf("foo", "bar", "baz"))
        result.matchedKeywords shouldBe listOf("foo", "bar", "baz")
        result.matchCount shouldBe 3
    }

    "Keyword equal to entire content matches" {
        val result = KeywordMatcher.match("foo", listOf("foo"))
        result shouldBe MatchResult(matchedKeywords = listOf("foo"), matchCount = 1)
    }

    "Overlapping keywords both match via Aho-Corasick suffix-link emission" {
        val result = KeywordMatcher.match("abcd", listOf("abc", "bcd"))
        result.matchedKeywords shouldBe listOf("abc", "bcd")
        result.matchCount shouldBe 2
    }

    "Overlapping keywords inside larger word do NOT match (no outer boundary)" {
        // Per spec scenario: 'xabcdy' with ['abc', 'bcd'] — outer-left 'x' and outer-right 'y'
        // are not boundaries → neither emits. The chain treatment ensures both fall together.
        val result = KeywordMatcher.match("xabcdy", listOf("abc", "bcd"))
        result shouldBe MatchResult(matchedKeywords = emptyList(), matchCount = 0)
    }

    "Indonesian non-ASCII content lowercased correctly preserves diacritics" {
        val result = KeywordMatcher.match("halo Dünia", listOf("dünia"))
        result shouldBe MatchResult(matchedKeywords = listOf("dünia"), matchCount = 1)
    }

    "Multiple repeated occurrences across word boundaries dedupe" {
        // Three 'foo' tokens, all word-boundary valid. Distinct count = 1.
        val result = KeywordMatcher.match("foo. foo, foo!", listOf("foo"))
        result shouldBe MatchResult(matchedKeywords = listOf("foo"), matchCount = 1)
    }

    "No-match path returns canonical empty result (zero allocations beyond MatchResult)" {
        val result = KeywordMatcher.match("perfectly clean indonesian text", listOf("xyzabc", "qrs"))
        result.matchedKeywords shouldBe emptyList()
        result.matchCount shouldBe 0
    }

    "Long content with 50 patterns scans in linear time" {
        // Edge-case test: 10,000+ char content × 50 patterns. Aho-Corasick is O(content + matches),
        // so this should complete in well under a second even on a slow CI runner. The assertion
        // is correctness (no spurious matches) rather than wall-clock perf, since perf assertions
        // are flaky in CI; the test exists to confirm we did not introduce a quadratic codepath.
        // Use a zero-padded numeric suffix so patterns are not prefix-substrings of each other
        // (e.g., "px01" is NOT a prefix of "px15" — they differ at position 2). Otherwise
        // Aho-Corasick's suffix-link emission semantics would (correctly) emit overlapping
        // shorter prefixes, which is a separate scenario covered elsewhere.
        val keywords = (1..50).map { "px${it.toString().padStart(2, '0')}" }
        val content =
            buildString {
                repeat(2000) { append("clean indonesian content. ") }
                append("px01 px07 px15 px30 px50.")
            }
        val result = KeywordMatcher.match(content, keywords)
        result.matchedKeywords shouldBe listOf("px01", "px07", "px15", "px30", "px50")
        result.matchCount shouldBe 5
    }

    "Empty string in keyword list is silently ignored" {
        // Empty keyword has no semantic value; the matcher skips empties during build rather
        // than treating them as 'matches everything'.
        val result = KeywordMatcher.match("hello world", listOf("", "world"))
        result shouldBe MatchResult(matchedKeywords = listOf("world"), matchCount = 1)
    }

    "Brackets and parentheses are recognized as word boundaries" {
        val result = KeywordMatcher.match("text(foo)more[bar]end{baz}", listOf("foo", "bar", "baz"))
        result.matchedKeywords shouldBe listOf("foo", "bar", "baz")
        result.matchCount shouldBe 3
    }
})
