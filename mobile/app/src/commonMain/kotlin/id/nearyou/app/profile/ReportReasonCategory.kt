package id.nearyou.app.profile

/**
 * The six **user-facing** report reason categories the profile reason picker exposes
 * (`docs/03-UX-Design.md` § Report UX), each mapped to its SHIPPED wire `reason_category` value
 * (`reports` spec enum). The wire also defines `self_harm` and `csam_suspected`, but those are
 * internal/automated classifications and are deliberately NOT user-pickable — they have no entry here.
 *
 * [wireValue] is the exact `reason_category` string the backend validates; [toWire] is the pure,
 * exhaustively-testable mapping the repository sends. Do NOT add `self_harm` / `csam_suspected`.
 */
enum class ReportReasonCategory(val wireValue: String) {
    /** "Spam" → `spam`. */
    SPAM("spam"),

    /** "Ujaran kebencian (SARA)" → `hate_speech_sara`. */
    HATE_SPEECH_SARA("hate_speech_sara"),

    /** "Pelecehan" → `harassment`. */
    HARASSMENT("harassment"),

    /** "Konten dewasa" → `adult_content`. */
    ADULT_CONTENT("adult_content"),

    /** "Misinformasi" → `misinformation`. */
    MISINFORMATION("misinformation"),

    /** "Lainnya" → `other`. */
    OTHER("other"),
}

/** The wire `reason_category` value for this picker category (pure; the repository sends it verbatim). */
fun ReportReasonCategory.toWire(): String = wireValue
