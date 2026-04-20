package id.nearyou.app.lint

/**
 * Annotation on a function or class that intentionally reads `posts` directly instead
 * of going through the `visible_posts` view. Paired with the `RawFromPostsRule` Detekt
 * rule (see `:lint:detekt-rules`) — the rule looks for this annotation's short name on
 * any enclosing declaration and suppresses the check when present.
 *
 * The `reason` is mandatory and must be non-blank; it documents why the bypass is safe
 * (e.g., "legacy admin migration path — being removed in Phase 3.5").
 */
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FILE,
)
@Retention(AnnotationRetention.SOURCE)
annotation class AllowRawPostsRead(val reason: String)
