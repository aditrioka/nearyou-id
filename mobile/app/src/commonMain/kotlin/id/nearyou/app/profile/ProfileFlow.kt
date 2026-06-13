package id.nearyou.app.profile

/**
 * The display + action model of a user profile (the domain projection the screen renders). Compose-free
 * and PII-safe-by-rendering: [userId] is retained ONLY as the follow/block/report action target and the
 * route resource key — it MUST NEVER be rendered as a UI string. [bio] is null when absent; [isPrivate]
 * is non-null only on the self read (self-only state). [isPremium] is true iff the target's
 * `subscription_status` is `premium_active`.
 */
data class UserProfile(
    val userId: String,
    val username: String,
    val displayName: String,
    val bio: String?,
    val followerCount: Int,
    val followingCount: Int,
    val isSelf: Boolean,
    val followedByViewer: Boolean,
    val isPremium: Boolean,
    val isPrivate: Boolean?,
)

/**
 * The profile read maps to EXACTLY one member. `200` → [Loaded]; the CONSTANT byte-identical
 * `404 user_not_found` (unknown / soft-deleted / shadow-banned / blocked-either-direction — all
 * indistinguishable per `user-profile-read` leak-safety) → the SINGLE [NotFound] (no per-cause branch);
 * `5xx` / transport / parse failure / any other status → the retryable [NetworkError]. `401` is delegated
 * to the shipped `Auth` plugin and never mapped here.
 */
sealed interface ProfileOutcome {
    data class Loaded(val profile: UserProfile) : ProfileOutcome

    data object NotFound : ProfileOutcome

    data object NetworkError : ProfileOutcome
}

/**
 * A follow / unfollow toggle maps to EXACTLY one member. The `DELETE` (unfollow) is an idempotent `204`
 * no-op (never 404). The `POST` (follow) → `204` [Followed]; a constant `404 user_not_found` (the target
 * became unresolvable since the read) → [TargetGone]; `429` → [RateLimited]; `5xx`/transport/any other →
 * [NetworkError]. The `DELETE` `204` → [Unfollowed].
 */
sealed interface FollowToggleOutcome {
    data object Followed : FollowToggleOutcome

    data object Unfollowed : FollowToggleOutcome

    /** `429` — carries the `Retry-After` seconds (the screen surfaces the follow-churn limit message). */
    data class RateLimited(val retryAfterSeconds: Long) : FollowToggleOutcome

    /** The follow `POST` constant `404 user_not_found` — the target is no longer resolvable. */
    data object TargetGone : FollowToggleOutcome

    /** `5xx`, transport/IO, or any unenumerated status — retryable. */
    data object NetworkError : FollowToggleOutcome
}

/**
 * A block maps to EXACTLY one member. `204` → [Blocked]; `429` → [RateLimited]; `5xx`/transport/any
 * other (incl. an unreachable-from-UI `404`) → [NetworkError].
 */
sealed interface BlockOutcome {
    data object Blocked : BlockOutcome

    data class RateLimited(val retryAfterSeconds: Long) : BlockOutcome

    data object NetworkError : BlockOutcome
}

/**
 * A report maps to EXACTLY one member. `204` → [Submitted]; `409 duplicate_report` → [Duplicate] (the
 * SHIPPED code — NOT the stale `reports.duplicate` in the reports-spec purpose line); `429` →
 * [RateLimited]; `5xx`/transport/any other → [NetworkError]. (`self_report_rejected` is unreachable — no
 * kebab on the self read.)
 */
sealed interface ReportOutcome {
    data object Submitted : ReportOutcome

    data object Duplicate : ReportOutcome

    data class RateLimited(val retryAfterSeconds: Long) : ReportOutcome

    data object NetworkError : ReportOutcome
}

/**
 * The profile orchestration contract consumed by `ProfileViewModel`. The production binding is
 * [ProfileRepository] (a stateless Koin singleton); commonTest substitutes a `FakeProfileFlow` so the
 * screen + VM tests drive specific outcomes without a backend — mirroring `PostDetailFlow`. Every method
 * takes the target [userId] explicitly (the repository is a singleton — it holds no per-screen id) and
 * maps each HTTP status + transport-failure to EXACTLY one member of its sealed outcome, with no generic
 * "failed" wildcard. `401` is delegated to the shipped `Auth` plugin; `CancellationException` is rethrown
 * by the ApiClient layer (never mapped to a failure).
 */
interface ProfileFlow {
    suspend fun loadProfile(userId: String): ProfileOutcome

    suspend fun follow(userId: String): FollowToggleOutcome

    suspend fun unfollow(userId: String): FollowToggleOutcome

    suspend fun block(userId: String): BlockOutcome

    suspend fun report(
        userId: String,
        reasonCategory: ReportReasonCategory,
        note: String?,
    ): ReportOutcome
}
