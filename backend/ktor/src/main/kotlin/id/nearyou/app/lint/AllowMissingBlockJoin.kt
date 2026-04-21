package id.nearyou.app.lint

/**
 * Annotation on a function or class that intentionally queries one of the protected tables
 * (`posts`, `visible_posts`, `users`, `chat_messages`, `post_replies`) WITHOUT the canonical
 * bidirectional `user_blocks` exclusion subqueries. Paired with the `BlockExclusionJoinRule`
 * Detekt rule (see `:lint:detekt-rules`) — the rule looks for this annotation's short name on
 * any enclosing declaration and suppresses the check when present.
 *
 * The `reason` is mandatory and MUST be non-blank; it documents why the bypass is safe
 * (e.g., "aggregate count for analytics, no per-user surfaces affected" or "internal job
 * runs as the system user, no end-user visibility"). Code review rejects empty-string reasons —
 * Kotlin annotations cannot enforce non-empty string contents at compile time.
 */
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FILE,
)
@Retention(AnnotationRetention.SOURCE)
annotation class AllowMissingBlockJoin(val reason: String)
