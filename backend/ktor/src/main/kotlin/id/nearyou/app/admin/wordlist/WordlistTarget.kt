package id.nearyou.app.admin.wordlist

/**
 * The two editable moderation wordlists (`admin-moderation-wordlist-editor`
 * capability). Maps the URL slug (`/admin/feature-flags/wordlists/{list}`) to the
 * Firebase Remote Config Server-template parameter name the moderation reader
 * (`content-moderation-keyword-lists`) consumes. An unknown slug resolves to
 * `null` → the route 404s without disclosing state.
 */
enum class WordlistTarget(
    val slug: String,
    val parameterName: String,
    val label: String,
) {
    PROFANITY("profanity", "moderation_profanity_list", "Profanity blocklist (Layer 1 — sync reject)"),
    UU_ITE("uu_ite", "moderation_uu_ite_list", "UU ITE wordlist (Layer 2 — soft-flag)"),
    ;

    companion object {
        fun fromSlug(slug: String?): WordlistTarget? = entries.firstOrNull { it.slug == slug }

        fun fromParameterName(parameterName: String): WordlistTarget? = entries.firstOrNull { it.parameterName == parameterName }
    }
}
