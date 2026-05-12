package id.nearyou.app.lint

/**
 * Annotation on a function that intentionally writes content into `posts.content`,
 * `post_replies.content`, or `chat_messages.content` WITHOUT a preceding
 * `TextModerator.moderate(content)` call. Paired with the
 * `ContentWriteRequiresModerationRule` Detekt rule (see `:lint:detekt-rules`) — the
 * rule looks for this annotation's short name on the enclosing function and
 * suppresses the check when present, the [reason] is non-blank, AND the [reason]
 * is one of the enumerated values below.
 *
 * Allowed [reason] values (the rule rejects anything else, including empty / blank):
 *
 *  - `"tombstone"` — admin-driven content tombstone replacement (overwrites user
 *    content with a fixed sentinel string like `[konten dihapus]` — no need to
 *    moderate the system-controlled string).
 *  - `"admin_redaction"` — Phase 3.5 admin chat-message redaction (overwrites
 *    user content with a fixed system-controlled redaction string).
 *  - `"seed"` — Flyway-driven seed harnesses or test fixtures populating content
 *    directly (Flyway `.sql` migrations themselves are out of Detekt's scope, but
 *    Kotlin-driven seed harnesses living in `src/main/kotlin` need this carve-out).
 *
 * Per the rule contract the [reason] MUST be a non-blank string literal. Kotlin
 * annotations cannot enforce non-empty contents at compile time; the rule's
 * `isNotBlank()` + enumeration check closes the silent-bypass loophole. Empty-
 * string reasons or non-enumerated reasons MUST fail the rule. See the
 * `content-moderation-keyword-lists` capability spec § `ContentWriteRequiresModerationRule
 * fences content-write call sites without preceding TextModerator.moderate` for the
 * authoritative invariant.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class AllowContentWriteWithoutModeration(val reason: String)
