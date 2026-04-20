package id.nearyou.app.auth.signup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * In-memory adjective/noun/modifier lists sourced from
 * `backend/ktor/src/main/resources/username/wordpairs.json`.
 *
 * Fail-fast validation at startup: missing file, non-matching schema, or any
 * string violating the `[a-z0-9]+` rule causes the Ktor application to refuse
 * to boot rather than surface a malformed username at signup time.
 */
class WordPairResource(
    val adjectives: List<String>,
    val nouns: List<String>,
    val modifiers: List<String>,
) {
    init {
        require(adjectives.isNotEmpty()) { "wordpairs.json: adjectives is empty" }
        require(nouns.isNotEmpty()) { "wordpairs.json: nouns is empty" }
        require(modifiers.isNotEmpty()) { "wordpairs.json: modifiers is empty" }
        val bad = (adjectives + nouns + modifiers).firstOrNull { !VALID.matches(it) }
        require(bad == null) { "wordpairs.json: invalid token '$bad' (must match $VALID)" }
    }

    companion object {
        private val VALID = Regex("^[a-z0-9]+$")
        private const val RESOURCE_PATH = "/username/wordpairs.json"
        private val JSON = Json { ignoreUnknownKeys = true }

        fun loadFromClasspath(path: String = RESOURCE_PATH): WordPairResource {
            val stream =
                WordPairResource::class.java.getResourceAsStream(path)
                    ?: error("wordpairs resource not found at $path")
            val raw = stream.use { it.readBytes().toString(Charsets.UTF_8) }
            val parsed = JSON.decodeFromString<Raw>(raw)
            return WordPairResource(parsed.adjectives, parsed.nouns, parsed.modifiers)
        }

        @Serializable
        private data class Raw(
            val adjectives: List<String>,
            val nouns: List<String>,
            val modifiers: List<String>,
        )
    }
}
