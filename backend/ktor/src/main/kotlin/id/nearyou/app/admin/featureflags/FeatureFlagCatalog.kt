package id.nearyou.app.admin.featureflags

/**
 * The canonical Feature Flag Admin catalog (`admin-feature-flags` spec, mockup
 * frame 20) — the SINGLE source of truth for both render (the typed control per
 * parameter) and write validation. Adding a flag here is the only edit needed to
 * surface + validate it.
 */
object FeatureFlagCatalog {
    const val MATCH_THRESHOLD_MIN: Int = 1
    const val MATCH_THRESHOLD_MAX: Int = 10_000

    const val IMAGE_UPLOAD_CAP_MIN: Int = 1
    const val IMAGE_UPLOAD_CAP_MAX: Int = 10_000

    val ATTESTATION_MODE_VALUES: List<String> = listOf("enforce", "warn", "off")

    /** Editable flags / parameters, in frame-20 render order. */
    val EDITABLE: List<FlagDef> =
        listOf(
            FlagDef(
                key = "image_upload_enabled",
                kind = FlagKind.Bool,
                description = "Media pipeline — off until image launch (docs/02).",
            ),
            FlagDef(
                key = "attestation_mode",
                kind = FlagKind.Enum(ATTESTATION_MODE_VALUES),
                description = "enforce / warn / off.",
                securitySensitive = true,
            ),
            FlagDef(
                key = "search_enabled",
                kind = FlagKind.Bool,
                description = "User + post search surfaces.",
            ),
            FlagDef(
                key = "perspective_api_enabled",
                kind = FlagKind.Bool,
                description = "Toxicity-scoring assist (Layer 3).",
            ),
            FlagDef(
                key = "premium_username_customization_enabled",
                kind = FlagKind.Bool,
                description = "Premium username-change flow.",
            ),
            FlagDef(
                key = "premium_like_cap_override",
                kind = FlagKind.Bool,
                description = "Emergency like-cap raise.",
            ),
            FlagDef(
                key = "premium_image_upload_cap_override",
                kind = FlagKind.IntRange(IMAGE_UPLOAD_CAP_MIN, IMAGE_UPLOAD_CAP_MAX),
                description = "Daily image-upload cap override (default 50; $IMAGE_UPLOAD_CAP_MIN–$IMAGE_UPLOAD_CAP_MAX).",
            ),
            FlagDef(
                key = "moderation_match_threshold",
                kind = FlagKind.IntRange(MATCH_THRESHOLD_MIN, MATCH_THRESHOLD_MAX),
                description = "Distinct-match count before Layer 2 flags ($MATCH_THRESHOLD_MIN–$MATCH_THRESHOLD_MAX).",
            ),
        )

    /**
     * Read-only moderation wordlists (count + version only). Array-CONTENT editing
     * is out of scope here — deferred to `admin-moderation-wordlist-editor` (spec
     * Req "Wordlist array-content editing is out of scope and guarded read-only").
     */
    val READ_ONLY_LISTS: List<FlagDef> =
        listOf(
            FlagDef("moderation_profanity_list", FlagKind.ReadOnlyList, "Layer 1 profanity blocklist."),
            FlagDef("moderation_uu_ite_list", FlagKind.ReadOnlyList, "Layer 2 UU ITE wordlist."),
        )

    /** Every parameter name the editor reads from the Server template (editable + read-only). */
    val ALL_PARAMETER_NAMES: Set<String> = (EDITABLE + READ_ONLY_LISTS).map { it.key }.toSet()

    fun editable(key: String): FlagDef? = EDITABLE.firstOrNull { it.key == key }

    /**
     * Validate [rawValue] for the editable parameter [key]. Returns the canonical
     * [ValueValidation.Valid.normalized] string to publish (lowercased boolean,
     * the parsed integer, the exact enum token) or a user-facing
     * [ValueValidation.Invalid] message. An unknown key, a read-only list, or a
     * type mismatch is [ValueValidation.Invalid] — never published.
     */
    fun validate(
        key: String,
        rawValue: String,
    ): ValueValidation {
        val def = editable(key) ?: return ValueValidation.Invalid("Unknown or non-editable parameter.")
        val trimmed = rawValue.trim()
        return when (val kind = def.kind) {
            FlagKind.Bool ->
                when (trimmed.lowercase()) {
                    "true" -> ValueValidation.Valid("true")
                    "false" -> ValueValidation.Valid("false")
                    else -> ValueValidation.Invalid("Value must be true or false.")
                }
            is FlagKind.Enum ->
                if (trimmed in kind.values) {
                    ValueValidation.Valid(trimmed)
                } else {
                    ValueValidation.Invalid("Value must be one of ${kind.values.joinToString(" / ")}.")
                }
            is FlagKind.IntRange -> {
                val parsed = trimmed.toIntOrNull() ?: return ValueValidation.Invalid("Value must be a whole number.")
                if (parsed < kind.min || parsed > kind.max) {
                    ValueValidation.Invalid("Value must be between ${kind.min} and ${kind.max}.")
                } else {
                    ValueValidation.Valid(parsed.toString())
                }
            }
            FlagKind.ReadOnlyList -> ValueValidation.Invalid("This list is read-only here.")
        }
    }
}

/** A single catalog entry. [securitySensitive] is render annotation only — ALL writes
 *  require owner/admin regardless (design D4). */
data class FlagDef(
    val key: String,
    val kind: FlagKind,
    val description: String,
    val securitySensitive: Boolean = false,
)

/** The control kind, which drives both the rendered input and the validation branch. */
sealed interface FlagKind {
    data object Bool : FlagKind

    data class Enum(val values: List<String>) : FlagKind

    data class IntRange(val min: Int, val max: Int) : FlagKind

    /** A read-only wordlist summary — no editable control here. */
    data object ReadOnlyList : FlagKind
}

sealed interface ValueValidation {
    /** Valid; [normalized] is the canonical raw string to publish. */
    data class Valid(val normalized: String) : ValueValidation

    /** Invalid; [message] is shown inline. No publish, no audit. */
    data class Invalid(val message: String) : ValueValidation
}
