# content-moderation-keyword-lists Specification

## Purpose

The `content-moderation-keyword-lists` capability is the foundational text-moderation gate every text-write path runs through before content reaches the database. It owns three tightly-coupled components: a pure-Kotlin Aho-Corasick `KeywordMatcher` in `:core:domain`, a `ModerationListLoader` with the canonical 4-tier fallback ladder (Redis 5-min cache → Firebase Remote Config Server template → repo-committed `*.default.txt` resource files → GCP Secret Manager last-resort), and a `TextModerator` orchestrator that combines them into a single `moderate(content): Verdict` entrypoint mapping to `Allow` / `Reject` (Layer 1 profanity blocklist, sync HTTP 4xx) / `Flag` (Layer 2 UU ITE wordlist, sync soft-flag → `moderation_queue` row).

This capability closes the Pre-Launch security-review §5 gap by making sure no user-supplied post / reply / chat-message content is persisted without first being keyword-matched against operator-curated wordlists. It establishes the matcher contract and observability surface (Sentry breadcrumbs that NEVER carry user content) that future Perspective API integration (Phase 2 §16) and Premium username customization (separate change) will both consume — both writers will reuse the same matcher engine and queue-writer pattern. Decision rules + threshold semantics are canonicalized at [`docs/06-Security-Privacy.md:153`](../../../docs/06-Security-Privacy.md); operational tuning happens via Firebase Remote Config without a backend redeploy.

## Requirements
### Requirement: `KeywordMatcher` is a pure-Kotlin Aho-Corasick engine in `:core:domain`

A pure-Kotlin Aho-Corasick `KeywordMatcher` SHALL live in `:core:domain` (no vendor dependencies, no I/O). The matcher SHALL expose a single function `match(content: String, keywords: List<String>): MatchResult` that returns:

```
data class MatchResult(
    val matchedKeywords: List<String>,  // distinct keywords that hit; empty when no match
    val matchCount: Int                  // total distinct keyword hits (== matchedKeywords.size)
)
```

The matcher SHALL be:
- **Case-insensitive**: input content and keywords are lowercased (Locale-Indonesian where applicable) before comparison.
- **Word-boundary-aware**: a keyword `"foo"` SHALL match the substring `"foo"` in `"hello foo bar"` AND in `"foo!"` (boundary chars: whitespace, punctuation `[.,!?;:()\[\]{}]`, start-of-string, end-of-string), but SHALL NOT match the substring `"foo"` inside `"foobar"` or `"barfoo"` (no boundary).
- **Distinct-keyword counting**: a keyword that appears multiple times in the content counts once (the matcher returns *distinct keywords matched*, not total occurrences). Rationale: threshold semantics are over distinct list entries, not repetitions of one entry.
- **Allocation-free for the common no-match path**: when no keyword matches, the returned `matchedKeywords` is an empty immutable list (singleton), no per-call allocation beyond the `MatchResult` data instance.

The matcher SHALL NOT mutate the keyword list. Automaton-build cost is sub-millisecond for ~50 patterns; the matcher SHALL build a fresh automaton per call by default (no internal cache). If profiling at p99 shows the rebuild matters, a future change MAY introduce a per-call-site memoization layer; the current contract is "build per call, no caching" so test surface stays simple and the implementation is deterministic.

#### Scenario: Single match returns one element
- **WHEN** `KeywordMatcher.match("apa kabar dunia", listOf("kabar", "salam"))` is invoked
- **THEN** the result equals `MatchResult(matchedKeywords = listOf("kabar"), matchCount = 1)`

#### Scenario: Multiple distinct matches
- **WHEN** `KeywordMatcher.match("ini adalah teks yang penuh dengan kata-kata", listOf("ini", "yang", "kata"))` is invoked
- **THEN** the result's `matchedKeywords` contains exactly the elements `["ini", "yang", "kata"]` in the order they first appear in the content AND `matchCount == 3`

#### Scenario: Repeated occurrence of one keyword counts once
- **WHEN** `KeywordMatcher.match("foo bar foo baz foo", listOf("foo"))` is invoked
- **THEN** the result equals `MatchResult(matchedKeywords = listOf("foo"), matchCount = 1)` (distinct keywords, not occurrences)

#### Scenario: Word boundary respected — substring inside larger word does not match
- **WHEN** `KeywordMatcher.match("foobar barfoo foo", listOf("foo"))` is invoked
- **THEN** the result equals `MatchResult(matchedKeywords = listOf("foo"), matchCount = 1)` (the standalone `foo` matches; the embedded substrings do NOT match)

#### Scenario: Case-insensitive match
- **WHEN** `KeywordMatcher.match("APA KABAR", listOf("kabar"))` is invoked
- **THEN** the result equals `MatchResult(matchedKeywords = listOf("kabar"), matchCount = 1)`

#### Scenario: Empty keyword list returns no-match
- **WHEN** `KeywordMatcher.match("any text", emptyList())` is invoked
- **THEN** the result equals `MatchResult(matchedKeywords = emptyList(), matchCount = 0)`

#### Scenario: Empty content returns no-match
- **WHEN** `KeywordMatcher.match("", listOf("foo", "bar"))` is invoked
- **THEN** the result equals `MatchResult(matchedKeywords = emptyList(), matchCount = 0)`

#### Scenario: Punctuation and whitespace boundaries both match
- **WHEN** `KeywordMatcher.match("foo, bar! baz.", listOf("foo", "bar", "baz"))` is invoked
- **THEN** all three keywords are matched (boundaries: `,`, `!`, `.`, whitespace, start-of-string, end-of-string)

#### Scenario: Keyword equal to entire content matches
- **WHEN** `KeywordMatcher.match("foo", listOf("foo"))` is invoked
- **THEN** the result equals `MatchResult(matchedKeywords = listOf("foo"), matchCount = 1)` (start-of-string + end-of-string serve as the boundary on both sides)

#### Scenario: Overlapping keywords both match (Aho-Corasick suffix-link emission)
- **WHEN** `KeywordMatcher.match("abcd", listOf("abc", "bcd"))` is invoked
- **THEN** the result equals `MatchResult(matchedKeywords = listOf("abc", "bcd"), matchCount = 2)` — Aho-Corasick's suffix-link emission detects both patterns from a single content scan; first-occurrence ordering surfaces `"abc"` before `"bcd"`. Note: word-boundary semantics still apply per the existing scenario — `"abcd"` content has start-of-string before `"abc"` and end-of-string after `"bcd"`, both valid boundaries; if instead the content were `"xabcdy"`, neither would match (no word boundary on either side of either keyword)

#### Scenario: Indonesian non-ASCII content lowercased correctly
- **WHEN** `KeywordMatcher.match("halo Dünia", listOf("dünia"))` is invoked
- **THEN** the result equals `MatchResult(matchedKeywords = listOf("dünia"), matchCount = 1)` (the `"D"` lowercases to `"d"` AND the `"ü"` is preserved; Indonesian-locale lowercasing does not strip diacritics)

### Requirement: `:core:domain` MUST NOT depend on a vendor Aho-Corasick library

The `KeywordMatcher` implementation SHALL be in-house pure Kotlin. `:core:domain`'s `build.gradle.kts` SHALL NOT add a dependency on any third-party Aho-Corasick library (e.g., `org.ahocorasick:ahocorasick`, `com.hankcs:hanlp`, etc.). Rationale: a single ~150-LOC implementation does not justify a third-party dependency that imports an indirect-deps surface.

#### Scenario: No vendor Aho-Corasick library on the `:core:domain` classpath
- **WHEN** `:core:domain`'s `dependencies { ... }` block in `build.gradle.kts` is inspected AND its resolved runtime classpath is enumerated
- **THEN** no artifact named `org.ahocorasick:*`, `com.hankcs:*`, or any other third-party Aho-Corasick package appears

### Requirement: `ModerationListLoader` resolves keyword lists via the canonical 4-tier fallback ladder

A `ModerationListLoader` SHALL live in `:backend:ktor` (under `id.nearyou.app.moderation`) and expose:

```
fun load(list: ModerationList): List<String>
```

where `ModerationList` is a sealed/enum type with at least the values `ProfanityList` and `UuIteList`.

The loader SHALL consult these tiers in strict order, falling through to the next tier on failure (network error, parse error, empty payload, missing resource, missing secret):

1. **Tier 1 — Redis 5-min cache.** Read JSON-serialized `List<String>` from key `{scope:mod_list}:{tier:profanity}` (or `{tier:uu_ite}`). Hit: return parsed list. Miss: proceed to Tier 2.
2. **Tier 2 — Firebase Remote Config.** Fetch parameter `moderation_profanity_list` (or `moderation_uu_ite_list`) as a string-array. On success: cache to Tier 1 with 5-min TTL via Lettuce `SETEX` and return. On network error / parse error / empty payload: emit `Sentry.warn("moderation_list_fallback", tier = "remote_config", to = "repo_file", list = "<...>", reason = "<...>")` and proceed to Tier 3.
3. **Tier 3 — Repo-committed resource file.** Read `backend/ktor/src/main/resources/moderation/profanity.default.txt` (or `uu_ite.default.txt`) line-by-line, trim whitespace, drop empty lines + comment lines (lines starting with `#`). On success: cache to Tier 1 with 5-min TTL and return. On missing resource: emit `Sentry.warn("moderation_list_fallback", tier = "repo_file", to = "secret_manager", list = "<...>")` and proceed to Tier 4.
4. **Tier 4 — Secret Manager.** Read slot value via `secretResolver.resolve("content-moderation-fallback-list")` (the bare slot name; the `SecretResolver` implementation handles env-prefix derivation internally per the precedent at [`backend/ktor/.../Application.kt:386–388`](../../../../../backend/ktor/src/main/kotlin/id/nearyou/app/Application.kt) where `secrets.resolve("firebase-admin-sa")` is the canonical call shape and `secretKey(env, "firebase-admin-sa")` is computed only for the diagnostic log message). The resolver returns a JSON document with shape `{"profanity": [...], "uu_ite": [...]}`. On success: extract the relevant list, cache to Tier 1 with 5-min TTL, return. On unresolved/null: emit `Sentry.error("moderation_list_unavailable", list = "<...>", outcome = "fail_open")` and return `emptyList()`.

The loader SHALL emit at most ONE Sentry event per `load(list)` call (not per tier-miss); Sentry's built-in deduplication suppresses event floods during sustained outages.

The diagnostic log line emitted on Tier 4 failure SHALL include the env-aware slot name derived via `secretKey(env, "content-moderation-fallback-list")` so operators can immediately see whether `staging-content-moderation-fallback-list` or `content-moderation-fallback-list` was the missing slot — this matches the `Application.kt:391-393` precedent (`event=fcm_init_failed reason=missing_secret slot={} env={}`). Direct hardcoded slot-name reads (bypassing both the `SecretResolver` AND the `secretKey()` helper for diagnostic logging) are a lint violation per [`openspec/project.md`](../../project.md) § "Coding Conventions & CI Lint Rules" (the convention is enforced today via `Secrets.kt`'s `secretKey()` helper as a naming convention; a future Detekt rule reservation `SecretKeyHelperRule` is documented in the convention text but not yet implemented — the implementation MUST follow the convention regardless).

The Redis cache key SHALL match the pattern `{scope:mod_list}:{tier:<tier-name>}` exactly, where `<tier-name>` is `profanity` or `uu_ite` (kebab-case, no underscores in the value portion). The key SHALL include the hash-tag braces `{...}` for cluster-safe multi-key ops per `RedisHashTagRule`.

#### Scenario: Tier 1 cache hit short-circuits
- **GIVEN** Redis key `{scope:mod_list}:{tier:profanity}` holds the JSON `["a", "b", "c"]`
- **WHEN** `loader.load(ProfanityList)` is invoked
- **THEN** the result equals `["a", "b", "c"]` AND no Remote Config fetch, file read, or Secret Manager call is made AND no Sentry event is emitted

#### Scenario: Tier 1 cache miss, Tier 2 Remote Config success
- **GIVEN** Redis key `{scope:mod_list}:{tier:profanity}` does NOT exist AND Firebase Remote Config returns `moderation_profanity_list = ["a", "b"]`
- **WHEN** `loader.load(ProfanityList)` is invoked
- **THEN** the result equals `["a", "b"]` AND the Redis key is populated with TTL 300s AND no Sentry event is emitted

#### Scenario: Tier 2 Remote Config fails, Tier 3 repo file succeeds
- **GIVEN** Redis cache miss AND Firebase Remote Config returns a network error
- **WHEN** `loader.load(ProfanityList)` is invoked AND `backend/ktor/src/main/resources/moderation/profanity.default.txt` contains `"a\nb\n# comment\n\nc\n"`
- **THEN** the result equals `["a", "b", "c"]` AND exactly one Sentry WARN is emitted with `event = "moderation_list_fallback"`, `tier = "remote_config"`, `to = "repo_file"`, `list = "profanity"`

#### Scenario: Tier 2 returns empty list, Tier 3 repo file succeeds (empty is treated as failure)
- **GIVEN** Redis cache miss AND Firebase Remote Config returns `moderation_profanity_list = []` (empty array)
- **WHEN** `loader.load(ProfanityList)` is invoked AND the repo file contains `["a", "b"]`
- **THEN** the result equals `["a", "b"]` AND a Sentry WARN with `reason = "empty"` is emitted (empty payload from Remote Config is operator error or stale state; cascade to fallback)

#### Scenario: Tier 3 missing, Tier 4 Secret Manager succeeds
- **GIVEN** Redis cache miss AND Remote Config network error AND the repo resource file does not exist
- **WHEN** `loader.load(ProfanityList)` is invoked AND `secretResolver.resolve("content-moderation-fallback-list")` returns the JSON `{"profanity": ["a", "b"], "uu_ite": ["c"]}`
- **THEN** the result equals `["a", "b"]` AND a Sentry WARN with `tier = "repo_file"`, `to = "secret_manager"` is emitted

#### Scenario: All four tiers fail, return empty list and emit ERROR
- **GIVEN** Redis cache miss AND Remote Config network error AND no repo resource file AND `secretResolver.resolve(...)` returns `null`
- **WHEN** `loader.load(ProfanityList)` is invoked
- **THEN** the result equals `emptyList()` AND exactly one Sentry ERROR is emitted with `event = "moderation_list_unavailable"`, `list = "profanity"`, `outcome = "fail_open"`

#### Scenario: Tier 2 returns malformed payload, cascade to Tier 3
- **GIVEN** Redis cache miss AND Firebase Remote Config returns a value that fails JSON-array deserialization (e.g., the parameter holds the literal string `"not-an-array"` or `{"key": "value"}`)
- **WHEN** `loader.load(ProfanityList)` is invoked AND the repo file contains `["a", "b"]`
- **THEN** the result equals `["a", "b"]` AND exactly one Sentry WARN is emitted with `tier = "remote_config"`, `to = "repo_file"`, `reason = "parse"`

#### Scenario: Tier 3 file present but contains only comment / blank lines, cascade to Tier 4
- **GIVEN** Redis cache miss AND Remote Config returns a network error AND the repo resource file `profanity.default.txt` exists but contains ONLY comment lines (`# placeholder`) and blank lines (no parseable keyword lines remaining after the trim+drop step)
- **WHEN** `loader.load(ProfanityList)` is invoked AND `secretResolver.resolve(...)` returns the JSON `{"profanity": ["a", "b"], "uu_ite": ["c"]}`
- **THEN** the result equals `["a", "b"]` AND exactly one Sentry WARN is emitted with `tier = "repo_file"`, `to = "secret_manager"`, `reason = "empty"` (an empty post-parse list is treated as a Tier 3 failure analogous to Tier 2 empty payload — locks the repo-file integrity contract from `### Requirement: Repo-committed default fallback wordlists exist`)

#### Scenario: Tier 4 returns malformed JSON, cascade to empty list + ERROR
- **GIVEN** Redis cache miss AND Remote Config network error AND the repo resource file is missing AND `secretResolver.resolve("content-moderation-fallback-list")` returns the literal string `"not-a-json-document"`
- **WHEN** `loader.load(ProfanityList)` is invoked
- **THEN** the result equals `emptyList()` AND exactly one Sentry ERROR is emitted with `event = "moderation_list_unavailable"`, `list = "profanity"`, `outcome = "fail_open"` AND the `reason` tag (if present) is `"parse"`

#### Scenario: Tier 4 resolve call matches the established `SecretResolver` precedent
- **WHEN** the loader's Tier 4 call site is inspected statically
- **THEN** the call site SHALL be `secretResolver.resolve("content-moderation-fallback-list")` (bare slot name; the `SecretResolver` implementation owns env-prefix derivation, mirroring [`Application.kt:388`](../../../../../backend/ktor/src/main/kotlin/id/nearyou/app/Application.kt) `secrets.resolve("firebase-admin-sa")`) AND a `secretKey(env, "content-moderation-fallback-list")` computation SHALL be present for the diagnostic log emitted on Tier 4 failure (mirroring `Application.kt:386` `secretSlot = secretKey(ktorEnv, "firebase-admin-sa")` used at line 392 in the structured error log) — neither shape SHALL be a hardcoded `"staging-content-moderation-fallback-list"` literal

#### Scenario: Cache key uses canonical hash-tag format
- **WHEN** the loader writes to Redis after a successful Tier 2 / 3 / 4 resolution for the profanity list
- **THEN** the Redis key written equals exactly `"{scope:mod_list}:{tier:profanity}"` (matching the `RedisHashTagRule` Detekt expectation)

### Requirement: `ModerationMatchThresholdLoader` resolves the threshold via the same Remote Config + fallback path

A separate loader OR a sibling method on `ModerationListLoader` SHALL resolve the integer `moderation_match_threshold` from Firebase Remote Config (default value 3 when Remote Config is unreachable or returns a non-positive integer or returns a non-integer string). The 5-min Redis cache and Sentry-on-fallback contract apply equally; the cache key SHALL be `{scope:mod_list}:{tier:threshold}` (the `mod_list` scope namespace is reused across `tier:profanity` / `tier:uu_ite` / `tier:threshold` for cluster-safe `MGET`/`MULTI` operations on the same Redis hash slot — the threshold is treated as another moderation parameter colocated with the lists; the alternative `{scope:moderation}` scope was considered but `mod_list` keeps continuity with the lists' shape).

The threshold loader SHALL clamp the resolved value to `[1, 10000]`; values outside that range fall back to the default 3 (mirrors the oversized-flag clamp in `like-rate-limit` / `reply-rate-limit` precedents). The clamp SHALL apply on EVERY read, including Tier 1 (Redis cache) reads — a stale cached value from a prior bad Remote Config push (e.g., `0` or `999999`) MUST NOT propagate to the matcher. The default 3 aligns with Pre-Phase 1 §36 (`moderation_match_threshold` numeric parameter, default 3).

#### Scenario: Threshold default 3 when Remote Config unreachable
- **GIVEN** Redis cache miss for `{scope:mod_list}:{tier:threshold}` AND Firebase Remote Config returns a network error
- **WHEN** `loader.loadThreshold()` is invoked AND no repo fallback for the threshold exists
- **THEN** the result equals `3` AND a Sentry WARN with `tier = "remote_config"`, `to = "default_3"` is emitted

#### Scenario: Threshold clamped on out-of-range value
- **GIVEN** Firebase Remote Config returns `moderation_match_threshold = 0` (out of `[1, 10000]`)
- **WHEN** `loader.loadThreshold()` is invoked
- **THEN** the result equals `3` (default; out-of-range value is treated as failure) AND a Sentry WARN with `reason = "out_of_range"` is emitted

#### Scenario: Threshold honored when within range
- **GIVEN** Firebase Remote Config returns `moderation_match_threshold = 5`
- **WHEN** `loader.loadThreshold()` is invoked
- **THEN** the result equals `5` (no clamp, no fallback)

#### Scenario: Threshold clamped on cached out-of-range value (cached-0 poisoning prevention)
- **GIVEN** Redis key `{scope:mod_list}:{tier:threshold}` holds the value `"0"` (a stale cached value from a prior bad Remote Config push that has since been corrected, but the cache TTL has not yet elapsed)
- **WHEN** `loader.loadThreshold()` is invoked
- **THEN** the result equals `3` (the default; the Tier 1 cache hit's `0` value is rejected by the same `[1, 10000]` clamp that applies to Tier 2 reads — a single bad Remote Config push CANNOT poison Layer 2 enforcement for the 5-min cache window)

#### Scenario: Threshold falls back to default on non-integer Remote Config value
- **GIVEN** Redis cache miss AND Firebase Remote Config returns `moderation_match_threshold = "abc"` (non-numeric string)
- **WHEN** `loader.loadThreshold()` is invoked
- **THEN** the result equals `3` (the default; the `RemoteConfigClient.fetchInt(...)` returns `null` for non-integer values, cascading to the next tier; if no further tier resolves, the default 3 is used) AND a Sentry WARN with `reason = "parse"` is emitted

### Requirement: `TextModerator.moderate(content)` orchestrates loader + matcher into a `Verdict`

A `TextModerator` SHALL live in `:backend:ktor` (under `id.nearyou.app.moderation`) and expose:

```
fun moderate(content: String): Verdict

sealed interface Verdict {
    data object Allow : Verdict
    data class Reject(val matchedKeywords: List<String>) : Verdict
    data class Flag(val matchedKeywords: List<String>) : Verdict
}
```

The orchestrator SHALL:
1. Load the profanity list via `loader.load(ProfanityList)`.
2. Run `KeywordMatcher.match(content, profanityList)`.
3. If `matchCount >= 1`, return `Verdict.Reject(matchedKeywords)` immediately (do NOT consult Layer 2).
4. Otherwise load the UU ITE list via `loader.load(UuIteList)` and the threshold via `loader.loadThreshold()`.
5. Run `KeywordMatcher.match(content, uuIteList)`.
6. If `matchCount >= threshold`, return `Verdict.Flag(matchedKeywords)`.
7. Otherwise return `Verdict.Allow`.

When BOTH lists return empty (full fail-open path per the loader's all-tier-fail behavior), the moderator SHALL return `Verdict.Allow` (treats empty list as "no possible matches"). The all-tier-fail Sentry ERROR is emitted by the loader, not the moderator; the moderator does not duplicate the alert.

The moderator SHALL be a Koin singleton instance — `TextModerator.moderate(...)` is invoked once per content-write request, and the underlying loader handles caching internally.

#### Scenario: Single profanity match short-circuits to Reject
- **GIVEN** profanity list `["badword"]` AND UU ITE list `["sara"]` AND threshold `3`
- **WHEN** `TextModerator.moderate("this contains badword somewhere")` is invoked
- **THEN** the result equals `Verdict.Reject(matchedKeywords = listOf("badword"))` AND the UU ITE list and threshold are NOT loaded (short-circuit)

#### Scenario: UU ITE single match below threshold returns Allow
- **GIVEN** profanity list `[]` AND UU ITE list `["sara1", "sara2", "sara3"]` AND threshold `3`
- **WHEN** `TextModerator.moderate("only one sara1 in this text")` is invoked
- **THEN** the result equals `Verdict.Allow` (matchCount = 1, threshold = 3, below threshold)

#### Scenario: UU ITE matches at threshold returns Flag
- **GIVEN** profanity list `[]` AND UU ITE list `["sara1", "sara2", "sara3", "sara4"]` AND threshold `3`
- **WHEN** `TextModerator.moderate("text with sara1 and sara2 and sara3")` is invoked
- **THEN** the result equals `Verdict.Flag(matchedKeywords = listOf("sara1", "sara2", "sara3"))` (matchCount = 3, ≥ threshold)

#### Scenario: UU ITE matches above threshold returns Flag
- **GIVEN** profanity list `[]` AND UU ITE list `["sara1", "sara2", "sara3", "sara4"]` AND threshold `3`
- **WHEN** `TextModerator.moderate("text with sara1 and sara2 and sara3 and sara4")` is invoked
- **THEN** the result equals `Verdict.Flag(matchedKeywords = listOf("sara1", "sara2", "sara3", "sara4"))` (matchCount = 4, > threshold)

#### Scenario: Both lists empty (fail-open) returns Allow
- **GIVEN** profanity list `[]` AND UU ITE list `[]` (loader returned empty for both — full fail-open path, ERROR already logged by loader)
- **WHEN** `TextModerator.moderate("any content here, including profane words")` is invoked
- **THEN** the result equals `Verdict.Allow` (no possible matches when both lists are empty)

#### Scenario: Profanity precedence — content matching both lists is Rejected (not Flagged)
- **GIVEN** profanity list `["badword"]` AND UU ITE list `["sara1", "sara2", "sara3"]` AND threshold `3` AND content `"this contains badword and sara1, sara2, sara3"`
- **WHEN** `TextModerator.moderate(content)` is invoked
- **THEN** the result equals `Verdict.Reject(matchedKeywords = listOf("badword"))` AND `loader.load(UuIteList)` was NOT called AND `loader.loadThreshold()` was NOT called (Layer 1 short-circuits before Layer 2 runs; UU ITE matches are not surfaced; verifiable via mock-spy call count on the loader)

### Requirement: `TextModerator` Sentry events do NOT carry user content

When `TextModerator.moderate(...)` produces a `Reject` or `Flag`, the orchestrator SHALL emit a Sentry breadcrumb-level event for ops auditability (the SHALL — not MAY — matches the test contract in `tasks.md` 6.6 which asserts the event fires; without emission the omission test cannot run). The event payload SHALL include:
- `event = "content_moderation_rejected"` (Reject) or `"content_moderation_flagged"` (Flag)
- `verdict_kind = "reject" | "flag"`
- `matched_keyword_count = <int>`

The event SHALL NOT include:
- The original `content` string (raw user input)
- The full `matchedKeywords` list (would leak which list entries triggered, useful for bypass-pattern reverse-engineering)
- Any user identifier (the calling handler may layer on a hashed `user.id` per the OTel forbidden-attributes contract from [`observability-otel-foundation/spec.md`](../../../specs/observability-otel-foundation/spec.md), but `TextModerator` itself does not consume the user identity)

The matched-keyword set MAY be shipped as a separate, sample-rate-limited DEBUG breadcrumb consumed only by ops review tooling — outside the Sentry surface this requirement governs.

#### Scenario: Reject Sentry event omits raw content
- **GIVEN** content `"sentinel-content-DO-NOT-LEAK badword"` produces `Verdict.Reject(matchedKeywords = listOf("badword"))`
- **WHEN** the Sentry event for the rejection is captured
- **THEN** the event payload does NOT contain the substring `"sentinel-content-DO-NOT-LEAK"` AND does NOT contain the matched keyword `"badword"` AND has `matched_keyword_count = 1`

#### Scenario: Flag Sentry event omits matched keyword list
- **GIVEN** content matches 3 UU ITE keywords (above threshold) AND produces `Verdict.Flag(matchedKeywords = listOf("k1", "k2", "k3"))`
- **WHEN** the Sentry event for the flag is captured
- **THEN** the event payload does NOT contain the literal strings `"k1"`, `"k2"`, or `"k3"` AND has `matched_keyword_count = 3`

### Requirement: Repo-committed default fallback wordlists exist

The repo SHALL ship the following resource files committed to source control:
- `backend/ktor/src/main/resources/moderation/profanity.default.txt`
- `backend/ktor/src/main/resources/moderation/uu_ite.default.txt`

Each file SHALL be UTF-8 plaintext, one keyword per line, supporting comment lines (`#` prefix) and blank lines. Each file SHALL contain at least one non-comment, non-blank line so Tier 3 fallback can succeed under boot-time integrity verification — initial content MAY be a placeholder sentinel that no genuine user input would match (e.g., `__seed_profanity_placeholder__`). Production wordlists are operator-managed via Firebase Remote Config; the repo files exist as fail-soft fallbacks, not as the operational list.

The files SHALL be addressable from the `:backend:ktor` JVM classpath via `/moderation/profanity.default.txt` (i.e., resources root, not Kotlin package layout).

#### Scenario: Both default resource files exist on the classpath
- **WHEN** the `:backend:ktor` JVM is booted AND `Thread.currentThread().contextClassLoader.getResource("/moderation/profanity.default.txt")` is invoked AND the same is invoked for `"/moderation/uu_ite.default.txt"`
- **THEN** both calls return non-null URLs

#### Scenario: Both files contain at least one non-comment line
- **GIVEN** both default resource files are read line-by-line AND empty/whitespace lines AND lines starting with `#` are dropped
- **THEN** the remaining line count is ≥ 1 for each file

### Requirement: Boot-time loader prime exercises Tier 3 fallback per list

On `Application.module()` startup, the loader SHALL invoke `load(ProfanityList)` AND `load(UuIteList)` exactly once each in a non-blocking coroutine. This boot-prime: (a) seeds the Redis cache with the canonical wordlists (so first-traffic requests do not pay the Tier 2 / 3 / 4 cascade cost), (b) verifies Tier 3 integrity at boot rather than at first user-content-write traffic — a missing or corrupt repo resource surfaces in Sentry pre-traffic instead of degrading the first user's submission. The boot-prime SHALL NOT block startup; if it fails entirely, the fail-open posture (per `### Requirement: TextModerator.moderate(content) orchestrates loader + matcher into a Verdict`) preserves availability and the Sentry events emitted by the loader's cascade are the operator-visible signal.

#### Scenario: Boot prime emits Sentry WARN if Tier 3 resource is missing
- **GIVEN** the repo resource `/moderation/profanity.default.txt` is absent at boot AND Remote Config is unreachable AND Secret Manager is reachable with a valid `content-moderation-fallback-list` value
- **WHEN** `Application.module()` boots AND the boot-prime coroutine completes
- **THEN** within 5s of boot, exactly one Sentry event is emitted with `event = "moderation_list_fallback"`, `tier = "repo_file"`, `to = "secret_manager"`, `list = "profanity"` AND startup completes (the boot-prime does not throw)

#### Scenario: Boot prime success does not emit Sentry events on the happy path
- **GIVEN** Tier 1 cache miss AND Tier 2 Remote Config returns valid lists for both profanity and UU ITE
- **WHEN** `Application.module()` boots AND the boot-prime coroutine completes
- **THEN** within 5s of boot, the Redis cache holds populated keys for both `{scope:mod_list}:{tier:profanity}` and `{scope:mod_list}:{tier:uu_ite}` AND zero Sentry events are emitted by the loader (Tier 2 success is not a fallback)

### Requirement: Tier-fallback Sentry events emit at most once per `load(list)` call

A single `loader.load(list)` invocation that cascades through multiple tiers SHALL emit at most one Sentry event (the most-severe one in the cascade — typically the highest-tier WARN or the all-tier ERROR). Repeated tier-misses within the SAME `load(list)` call SHALL NOT each emit their own Sentry event; only the cumulative outcome is reported. This prevents Sentry-event flooding during sustained Remote Config outages where every `moderate()` call would otherwise produce 3+ events.

Sentry's built-in event deduplication MAY further suppress repeated events of the same `(event, tier, list)` key across calls; the in-call rate limit is the spec contract, the cross-call dedup is best-effort.

#### Scenario: Cascade through 3 tiers emits exactly 1 Sentry event
- **GIVEN** Redis miss → Remote Config network error → repo file missing → Secret Manager success
- **WHEN** `loader.load(ProfanityList)` is invoked
- **THEN** exactly ONE Sentry event is emitted (the latest cascade transition: `tier = "repo_file"`, `to = "secret_manager"`) — NOT one event for each cascade step

#### Scenario: Full-fallthrough cascade emits exactly 1 ERROR event
- **GIVEN** Redis miss → Remote Config error → repo file missing → Secret Manager null
- **WHEN** `loader.load(ProfanityList)` is invoked
- **THEN** exactly ONE Sentry ERROR event is emitted (the all-tier-fail condition) AND no intermediate WARN events are emitted (the ERROR supersedes the cumulative WARN trail)

### Requirement: `:infra:remote-config` is the sole owner of the Firebase Remote Config Admin SDK

A new Gradle module `:infra:remote-config` SHALL be created under `infra/remote-config/` (alongside `:infra:fcm`, `:infra:oidc`, `:infra:otel`, `:infra:redis`, `:infra:supabase`). All `import com.google.firebase.remoteconfig.*` AND all Firebase Admin SDK Remote Config Java types SHALL live entirely inside `:infra:remote-config`.

`:backend:ktor` SHALL depend on `:infra:remote-config` and SHALL NOT carry any direct `com.google.firebase.*` import in its source files. Business modules (`:core:domain`, `:core:data`, `:shared:*`) SHALL NOT depend on `:infra:remote-config` — they remain pure-Kotlin / interface-only.

The new module SHALL initialize its OWN named FirebaseApp (e.g., `"nearyou-rc"`) reading the service-account JSON via `secretResolver.resolve("firebase-admin-sa")` (bare slot name, mirroring the [`Application.kt:388`](../../../../../backend/ktor/src/main/kotlin/id/nearyou/app/Application.kt) precedent), with `secretKey(env, "firebase-admin-sa")` computed for the diagnostic startup log only. This is the same secret slot already consumed by `:infra:fcm`'s `FirebaseAdminInit`, but a separate FirebaseApp instance — keeps the modules cleanly independent without an `:infra:remote-config` → `:infra:fcm` dependency edge. The named-FirebaseApp pattern mirrors `FirebaseAdminInit`'s `"nearyou-default"` pattern.

The module SHALL expose ONLY the `RemoteConfigClient` public interface + a Koin-bindable implementation. The Firebase Admin SDK types (`FirebaseRemoteConfig`, `Template`, `Parameter`, etc.) SHALL NOT leak into the public interface — `RemoteConfigClient` returns plain Kotlin types (`Map<String, String>` for parameters, `List<String>` for parsed string-arrays, `Int` for parsed numbers).

#### Scenario: Module exists at the canonical path with the canonical name
- **WHEN** the project structure is inspected
- **THEN** `infra/remote-config/build.gradle.kts` exists AND the module is included in `settings.gradle.kts` as `:infra:remote-config`

#### Scenario: `:backend:ktor` depends on `:infra:remote-config`
- **WHEN** `:backend:ktor`'s `build.gradle.kts` `dependencies { ... }` block is inspected
- **THEN** `implementation(project(":infra:remote-config"))` (or equivalent) appears in the declarations

#### Scenario: `:core:domain` source files contain no Firebase import
- **WHEN** the source files of `:core:domain` are scanned for `import com.google.firebase`
- **THEN** zero matches are found

#### Scenario: `:backend:ktor` source files contain no Firebase Remote Config import
- **WHEN** the source files of `:backend:ktor` are scanned for `import com.google.firebase.remoteconfig`
- **THEN** zero matches are found (the `RemoteConfigClient` symbol imported into `:backend:ktor` is from `:infra:remote-config`, not from the Firebase Admin SDK directly)

#### Scenario: `RemoteConfigClient` interface returns plain Kotlin types, not Firebase SDK types
- **WHEN** the public interface declaration of `RemoteConfigClient` in `:infra:remote-config` is inspected
- **THEN** every public function's return type is a Kotlin standard-library type (`String`, `Int`, `Long`, `List<String>`, `Map<String, ...>`, `Boolean`, or null variants thereof) — no `Template`, `Parameter`, `ParameterValue`, or any other `com.google.firebase.remoteconfig.*` type appears in the public surface

#### Scenario: `:infra:remote-config` initializes a named FirebaseApp distinct from `:infra:fcm`'s
- **WHEN** the `RemoteConfigClient` implementation is initialized
- **THEN** the FirebaseApp instance bound to it has a `name` value distinct from `"nearyou-default"` (the name owned by `:infra:fcm`'s `FirebaseAdminInit`)

#### Scenario: `:infra:remote-config` resolves the service-account secret via the precedent call shape
- **WHEN** the module's FirebaseApp initialization call site is inspected statically
- **THEN** the service-account JSON is sourced via `secretResolver.resolve("firebase-admin-sa")` (bare slot name, mirroring [`Application.kt:388`](../../../../../backend/ktor/src/main/kotlin/id/nearyou/app/Application.kt)) AND a `secretKey(env, "firebase-admin-sa")` computation is present for the diagnostic startup log line (mirroring `Application.kt:386` and used at line 392 — `event=...init_failed reason=missing_secret slot={} env={}`) AND no hardcoded `"staging-firebase-admin-sa"` literal appears

### Requirement: User-facing rejection message is in Bahasa Indonesia, omits matched keywords

When a write-path handler maps a `Verdict.Reject` to an HTTP 4xx response, the response body SHALL include `code = "content_moderated_profanity"` and a Bahasa Indonesia user-facing `message` field. The matched keyword list from the verdict SHALL NOT appear in the response (would tip off bypass attempts). Initial canonical message:

> `"Konten ini mengandung kata yang tidak diperbolehkan. Silakan ubah dan coba lagi."`

Other 4xx envelope fields (request ID, timestamp, etc.) follow the existing error envelope pattern from [`openspec/specs/post-creation/spec.md`](../../specs/post-creation/spec.md) `### Requirement: Error envelope matches existing auth routes`.

#### Scenario: Reject response carries the canonical code and Bahasa Indonesia message
- **GIVEN** a write-path handler that received `Verdict.Reject(matchedKeywords = listOf("badword"))`
- **WHEN** the handler maps the Reject to an HTTP response
- **THEN** the response status is 400 AND the response body's `error.code` is `"content_moderated_profanity"` AND the response body's `error.message` is `"Konten ini mengandung kata yang tidak diperbolehkan. Silakan ubah dan coba lagi."` AND the response body does NOT contain the literal `"badword"`

#### Scenario: Reject response does not include matched keywords list
- **GIVEN** content matched 3 distinct profanity keywords AND a `Verdict.Reject(matchedKeywords = listOf("k1", "k2", "k3"))` was produced
- **WHEN** the response body is captured AND scanned for the literal strings `"k1"`, `"k2"`, `"k3"`
- **THEN** none of the literals appear

### Requirement: `TextModerator` integration is invoked AFTER existing length and rate-limit gates, BEFORE INSERT

Every write-path handler that wires `TextModerator.moderate(...)` SHALL run the moderator AFTER existing length validation, rate-limit check, and (where applicable) block check, AND BEFORE the content INSERT. The exact order is:

1. Authentication / authorization
2. Per-endpoint rate limit (existing where applicable)
3. Block check (existing where applicable: chat send)
4. Content length guard (post 280 / reply 280 / chat 2000)
5. **`TextModerator.moderate(content)`** ← THIS CHANGE
6. INSERT into the relevant table
7. (chat path only) broadcast publish via `ChatRealtimeClient`

This ordering ensures: cheap deterministic checks (length, rate limit, block) reject malformed/abusive requests before invoking the moderator (which has Redis/Remote Config network surface); the moderator runs against already-length-validated content (no content too long to fingerprint); content is moderated before becoming visible.

For `Verdict.Flag`, the `moderation_queue` row SHALL be written in the same SQL transaction as the content INSERT, with `ON CONFLICT (target_type, target_id, trigger) DO NOTHING` for idempotency (per existing [`moderation-queue/spec.md`](../../specs/moderation-queue/spec.md) UNIQUE constraint).

#### Scenario: Moderator runs after length guard, before INSERT
- **WHEN** a write-path handler is statically analyzed for the call order of `contentLengthGuard`, `TextModerator.moderate`, and the canonical INSERT call
- **THEN** the call order is exactly: `contentLengthGuard` → `TextModerator.moderate` → `INSERT` (no INSERT before moderate; no moderate before length guard)

#### Scenario: Flag verdict writes moderation_queue row in the same transaction as INSERT
- **WHEN** `Verdict.Flag` is produced AND the handler proceeds to INSERT
- **THEN** the SQL transaction emits both the content INSERT (e.g., `INSERT INTO posts ...`) AND the `INSERT INTO moderation_queue (target_type, target_id, trigger, ...) VALUES (...) ON CONFLICT DO NOTHING` AND a single COMMIT (atomicity)

#### Scenario: Flag with idempotent retry does not double-write moderation_queue
- **GIVEN** a `Verdict.Flag` writes a `moderation_queue` row with `(target_type, target_id, trigger) = ('post', U, 'uu_ite_keyword_match')` AND a retry of the same content+target produces another `Verdict.Flag`
- **WHEN** the second handler attempts the INSERT
- **THEN** the `ON CONFLICT (target_type, target_id, trigger) DO NOTHING` clause suppresses the duplicate AND the queue contains exactly one row for that target+trigger

#### Scenario: Concurrent Flag inserts collapse to one queue row
- **GIVEN** two concurrent transactions T1 and T2 each produce a `Verdict.Flag` for the same target tuple `(target_type='post', target_id=P, trigger='uu_ite_keyword_match')` (e.g., a retry race where both clients submit the same content nearly simultaneously, both pass auth + length + moderation, both reach the INSERT step)
- **WHEN** both transactions execute `INSERT INTO moderation_queue ... ON CONFLICT (target_type, target_id, trigger) DO NOTHING` AND both COMMIT
- **THEN** exactly one `moderation_queue` row exists for the tuple AND neither transaction surfaces a unique-violation error to its caller (mirrors the existing `moderation-queue` capability's idempotency guarantee on the auto-hide-3-reports writer; the Layer 2 writer this change introduces has the same semantics)

