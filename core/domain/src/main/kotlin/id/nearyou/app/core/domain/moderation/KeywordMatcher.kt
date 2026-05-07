package id.nearyou.app.core.domain.moderation

import java.util.Locale

/**
 * Result of a [KeywordMatcher.match] call.
 *
 * `matchedKeywords` is in first-occurrence order across the content (left-to-right),
 * with each distinct keyword listed at most once even if it appears multiple times.
 * `matchCount` is the size of `matchedKeywords` (distinct count, not occurrence count).
 *
 * The threshold semantics in `TextModerator` are over distinct list entries — repeating
 * the same keyword does not move you closer to the threshold. See `### Requirement:
 * KeywordMatcher is a pure-Kotlin Aho-Corasick engine in :core:domain` § "Distinct-keyword
 * counting" rationale.
 */
data class MatchResult(
    val matchedKeywords: List<String>,
    val matchCount: Int,
)

/**
 * Pure-Kotlin Aho-Corasick keyword matcher. No vendor SDK dependency, no I/O, no internal
 * caching — a fresh automaton is built per [match] call (sub-millisecond cost for ~50
 * patterns; rebuild profiling deferred to a future memoization layer if p99 ever shows
 * this matters per `content-moderation-keyword-lists` spec).
 *
 * Match semantics:
 *  - **Case-insensitive** — content and keywords are lowercased with `Locale.forLanguageTag("id")`
 *    before comparison so Indonesian text is folded correctly + non-ASCII diacritics are preserved.
 *  - **Word-boundary-aware** — a keyword's match span MUST be flanked on both sides by either
 *    start-of-string / end-of-string OR one of the boundary chars
 *    `whitespace | . , ! ? ; : ( ) [ ] { }`. Adjacent / overlapping matches form a "chain"
 *    whose outer boundaries are checked once for the whole chain (so `"abcd"` matches both
 *    `"abc"` and `"bcd"` because the chain `[0,3]` has start-of-string + end-of-string;
 *    `"xabcdy"` matches neither).
 *  - **Distinct counting** — repeated occurrences of one keyword count once.
 *
 * The matcher SHALL NOT mutate the keyword list.
 */
object KeywordMatcher {
    private val ID_LOCALE: Locale = Locale.forLanguageTag("id")

    /**
     * Boundary characters per `### Requirement: KeywordMatcher is a pure-Kotlin
     * Aho-Corasick engine in :core:domain` § "Word-boundary-aware". Whitespace
     * uses `Char.isWhitespace()` at boundary-check time (covers space, tab, CR,
     * LF, etc.); punctuation set is the explicit list from the spec
     * (`[.,!?;:()[]{}]`) plus hyphen (`-`) — hyphen is a boundary because
     * Indonesian uses hyphen to delimit reduplicated compounds like
     * `"kata-kata"` (= "words"), and the spec scenario "Multiple distinct
     * matches" expects each `"kata"` in `"kata-kata"` to match as a separate
     * word. The hyphen addition is consistent with the scenario's intent
     * even though it is not enumerated in the spec's prose list.
     */
    private val BOUNDARY_PUNCTUATION: Set<Char> =
        setOf('.', ',', '!', '?', ';', ':', '(', ')', '[', ']', '{', '}', '-')

    private val EMPTY_RESULT = MatchResult(matchedKeywords = emptyList(), matchCount = 0)

    fun match(
        content: String,
        keywords: List<String>,
    ): MatchResult {
        if (content.isEmpty() || keywords.isEmpty()) return EMPTY_RESULT

        val loweredContent = content.lowercase(ID_LOCALE)
        val loweredKeywords = keywords.map { it.lowercase(ID_LOCALE) }

        val automaton = AhoCorasickAutomaton.build(loweredKeywords)
        if (automaton.isEmpty) return EMPTY_RESULT

        val rawMatches = collectRawMatches(loweredContent, automaton, loweredKeywords)
        if (rawMatches.isEmpty()) return EMPTY_RESULT

        // Sort by (startIdx, endIdx). Same startIdx is rare (would require two keywords
        // ending at the same content position with same length — only if the keywords are
        // duplicates, which we dedupe at the LinkedHashSet level downstream).
        rawMatches.sortWith(compareBy({ it.startIdx }, { it.endIdx }))

        return assembleChainsAndEmit(loweredContent, rawMatches, loweredKeywords)
    }

    private fun collectRawMatches(
        loweredContent: String,
        automaton: AhoCorasickAutomaton,
        loweredKeywords: List<String>,
    ): MutableList<RawMatch> {
        val rawMatches = mutableListOf<RawMatch>()
        var node = AhoCorasickAutomaton.ROOT
        for (i in loweredContent.indices) {
            val c = loweredContent[i]
            // Follow failure links until a transition exists or we hit the root.
            while (node != AhoCorasickAutomaton.ROOT && automaton.transition(node, c) == AhoCorasickAutomaton.NO_TRANSITION) {
                node = automaton.failOf(node)
            }
            val next = automaton.transition(node, c)
            if (next != AhoCorasickAutomaton.NO_TRANSITION) {
                node = next
            }
            // Walk the output link chain to collect ALL keyword emissions ending at i
            // (including suffix-link emissions for shorter keywords nested inside longer ones).
            var u = node
            while (u != AhoCorasickAutomaton.ROOT) {
                val kwIdx = automaton.outputAt(u)
                if (kwIdx >= 0) {
                    val kw = loweredKeywords[kwIdx]
                    val startIdx = i - kw.length + 1
                    rawMatches.add(RawMatch(startIdx = startIdx, endIdx = i, keywordIdx = kwIdx))
                }
                u = automaton.outputLinkOf(u)
            }
        }
        return rawMatches
    }

    private fun assembleChainsAndEmit(
        loweredContent: String,
        rawMatches: List<RawMatch>,
        loweredKeywords: List<String>,
    ): MatchResult {
        // LinkedHashSet preserves first-occurrence order while deduping by lowercased
        // keyword string (so two keyword indices that lowercase to the same string also dedupe).
        val emitted = LinkedHashSet<String>()
        var chainStart = rawMatches[0].startIdx
        var chainMaxEnd = rawMatches[0].endIdx
        val chainBuffer = mutableListOf(rawMatches[0])
        for (idx in 1 until rawMatches.size) {
            val m = rawMatches[idx]
            // A new chain starts when the current match's startIdx leaves a gap (>1) past
            // the previous chain's max end. Adjacent (startIdx == maxEnd + 1) and
            // overlapping (startIdx <= maxEnd) keep the chain open.
            if (m.startIdx > chainMaxEnd + 1) {
                flushChain(loweredContent, chainStart, chainMaxEnd, chainBuffer, loweredKeywords, emitted)
                chainStart = m.startIdx
                chainMaxEnd = m.endIdx
                chainBuffer.clear()
                chainBuffer.add(m)
            } else {
                chainBuffer.add(m)
                if (m.endIdx > chainMaxEnd) chainMaxEnd = m.endIdx
            }
        }
        flushChain(loweredContent, chainStart, chainMaxEnd, chainBuffer, loweredKeywords, emitted)
        if (emitted.isEmpty()) return EMPTY_RESULT
        return MatchResult(matchedKeywords = emitted.toList(), matchCount = emitted.size)
    }

    private fun flushChain(
        loweredContent: String,
        chainStart: Int,
        chainMaxEnd: Int,
        chainBuffer: List<RawMatch>,
        loweredKeywords: List<String>,
        emitted: LinkedHashSet<String>,
    ) {
        val leftOk = chainStart == 0 || isBoundary(loweredContent[chainStart - 1])
        val rightOk = chainMaxEnd == loweredContent.length - 1 || isBoundary(loweredContent[chainMaxEnd + 1])
        if (!leftOk || !rightOk) return
        for (m in chainBuffer) {
            emitted.add(loweredKeywords[m.keywordIdx])
        }
    }

    private fun isBoundary(c: Char): Boolean = c.isWhitespace() || c in BOUNDARY_PUNCTUATION

    private data class RawMatch(val startIdx: Int, val endIdx: Int, val keywordIdx: Int)
}

/**
 * Aho-Corasick automaton internals. Sparse-trie representation: each node holds a
 * `Map<Char, Int>` of transitions to keep memory bounded for narrow alphabets
 * (Indonesian text + ASCII punctuation; ~30-100 distinct characters typically).
 *
 * Failure links + output links are precomputed via BFS during [build].
 */
private class AhoCorasickAutomaton(
    private val transitions: List<Map<Char, Int>>,
    private val fail: IntArray,
    private val output: IntArray,
    private val outputLink: IntArray,
) {
    val isEmpty: Boolean get() = transitions.size == 1

    fun transition(
        node: Int,
        c: Char,
    ): Int = transitions[node][c] ?: NO_TRANSITION

    fun failOf(node: Int): Int = fail[node]

    fun outputAt(node: Int): Int = output[node]

    fun outputLinkOf(node: Int): Int = outputLink[node]

    companion object {
        const val ROOT: Int = 0
        const val NO_TRANSITION: Int = -1

        fun build(keywords: List<String>): AhoCorasickAutomaton {
            val transitions: MutableList<MutableMap<Char, Int>> = mutableListOf(mutableMapOf())
            val outputBuilder: MutableList<Int> = mutableListOf(-1)
            // Insert each keyword into the trie. Empty keywords are skipped (no semantic value
            // — an empty pattern matches everything which is nonsensical for moderation).
            for ((kwIdx, kw) in keywords.withIndex()) {
                if (kw.isEmpty()) continue
                var node = ROOT
                for (c in kw) {
                    val existing = transitions[node][c]
                    if (existing != null) {
                        node = existing
                    } else {
                        val newNode = transitions.size
                        transitions.add(mutableMapOf())
                        outputBuilder.add(-1)
                        transitions[node][c] = newNode
                        node = newNode
                    }
                }
                // First-write-wins: if two keywords map to the same trie path (e.g., duplicates
                // after lowercase folding), the first index is kept. The match-phase dedupes by
                // lowercased keyword string downstream so this only affects the index recorded.
                if (outputBuilder[node] == -1) {
                    outputBuilder[node] = kwIdx
                }
            }

            val n = transitions.size
            val fail = IntArray(n)
            val outputLink = IntArray(n)

            // BFS from depth-1 nodes to compute failure + outputLink in one pass.
            val queue: ArrayDeque<Int> = ArrayDeque()
            for ((_, child) in transitions[ROOT]) {
                fail[child] = ROOT
                outputLink[child] = if (outputBuilder[ROOT] >= 0) ROOT else 0
                queue.add(child)
            }
            while (queue.isNotEmpty()) {
                val u = queue.removeFirst()
                for ((c, v) in transitions[u]) {
                    var f = fail[u]
                    while (f != ROOT && transitions[f][c] == null) {
                        f = fail[f]
                    }
                    val candidate = transitions[f][c]
                    fail[v] = if (candidate != null && candidate != v) candidate else ROOT
                    val failV = fail[v]
                    outputLink[v] =
                        if (outputBuilder[failV] >= 0) {
                            failV
                        } else {
                            outputLink[failV]
                        }
                    queue.add(v)
                }
            }

            return AhoCorasickAutomaton(
                transitions = transitions,
                fail = fail,
                output = outputBuilder.toIntArray(),
                outputLink = outputLink,
            )
        }
    }
}
