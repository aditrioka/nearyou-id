package id.nearyou.app.core.domain.lint

/**
 * Annotation on a function or class that intentionally reads `posts` directly instead
 * of going through the `visible_posts` view. Paired with the `RawFromPostsRule` Detekt
 * rule (see `:lint:detekt-rules`) — the rule looks for this annotation's short name on
 * any enclosing declaration and suppresses the check when present.
 *
 * Lives in `:core:domain` (alongside `ratelimit/Annotations.kt`) so every module the
 * custom ruleset scans — `:backend:ktor`, `:infra:*`, `:core:*` — can reference one
 * canonical declaration. The rule matches the SHORT name via PSI, so the package move
 * from `id.nearyou.app.lint` is transparent to enforcement.
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
 *  - `"embedded_snapshot"` — writes that copy ALREADY-MODERATED content verbatim
 *    (e.g., a chat message embedding a post snapshot whose content passed Layer 1/2
 *    at post-creation time); no NEW user text enters the system.
 *  - `"service_layer_moderated"` — repository-layer INSERT sinks invoked exclusively
 *    by service paths that run `TextModerator.moderate()` BEFORE the call (docs/11
 *    layering puts moderation in the service, not the repository). The call-order
 *    contract stays enforced at the service call site (rule arm (b)) and by the 3
 *    moderation integration source-scan tests.
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

/**
 * Annotation on a declaration that intentionally reads the raw `location` column
 * (actual user coordinates) instead of `display_location`. Paired with the
 * `CoordinateJitterRule` Detekt rule (see `:lint:detekt-rules`), which documents this
 * annotation as the sanctioned bypass for non-admin paths; until this declaration the
 * class existed only in the rule's KDoc and test fixtures.
 *
 * Same enforcement contract as the sibling annotations: [reason] is mandatory and
 * non-blank, and the reviewer evaluates it (e.g., "distance computation input —
 * never serialized to the client").
 */
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FILE,
)
@Retention(AnnotationRetention.SOURCE)
annotation class AllowActualLocationRead(val reason: String)
