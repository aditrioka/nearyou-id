package id.nearyou.app.core.domain.ratelimit

/**
 * Marks a declaration as exempt from [`RateLimitTtlRule`](
 *   ../../../../../../../../lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/RateLimitTtlRule.kt
 * ) — i.e., the daily-window `RateLimiter.tryAcquire(...)` calls inside the annotated
 * declaration may pass a hardcoded `ttl` instead of `computeTTLToNextReset(...)`.
 *
 * The [reason] parameter is a non-nullable, non-blank `String` (no default) — both
 * the annotation declaration AND the Detekt rule reject empty/blank reasons to close
 * the silent-bypass loophole. Callers MUST supply a meaningful explanation; in code
 * review, the reason is what the reviewer evaluates.
 *
 * Mirror of `@AllowActualLocationRead` (coordinate-jitter capability) and
 * `@AllowMissingBlockJoin` (block-exclusion-lint capability).
 */
@Retention(AnnotationRetention.SOURCE)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.VALUE_PARAMETER,
)
annotation class AllowDailyTtlOverride(val reason: String)

/**
 * Marks a declaration as exempt from [`RedisHashTagRule`](
 *   ../../../../../../../../lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/RedisHashTagRule.kt
 * ) — string literals inside the annotated declaration may use legacy `rate:...`
 * form or malformed `{scope:...` shapes without firing the rule.
 *
 * Same enforcement contract as [AllowDailyTtlOverride]: [reason] is a non-nullable,
 * non-blank `String` (no default); empty/blank reasons fail the rule.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.VALUE_PARAMETER,
)
annotation class AllowRawRedisKey(val reason: String)
