package id.nearyou.app.followlist

import kotlinx.serialization.Serializable

/**
 * Which of the two lists a tab shows. `@Serializable` so it can ride in the `FollowListRoute` `NavKey`
 * (the iOS-saveable back stack) as the deep-link `initialTab` — tapping the follower count opens
 * [Followers]; the following count opens [Following]. PascalCase entries match the codebase's `Tab.Nearby`
 * enum convention.
 */
@Serializable
enum class FollowListTab { Followers, Following }

/**
 * One list row — the display projection the row renders. Compose-free and PII-safe-by-rendering: [userId]
 * is retained ONLY as the `ProfileRoute` resource key (the row tap target) and MUST NEVER be rendered as a
 * UI string. [username] / [displayName] are NOT NULL on the wire (V2) — there is NO `akun_dihapus`
 * placeholder or null-identity fallback on these lists (hidden members are excluded server-side via
 * `visible_users`, per `social-list-profile-summaries` — design D2). [isPremium] is true iff the row user's
 * `subscription_status` is `premium_active` (the `user-profile-read` formula the backend applies).
 */
data class FollowListUser(
    val userId: String,
    val username: String,
    val displayName: String,
    val isPremium: Boolean,
)

/** A loaded page: the visible rows + the keyset [nextCursor] (null on the last page → no further fetch). */
data class FollowListPage(
    val users: List<FollowListUser>,
    val nextCursor: String?,
)

/**
 * A list read maps to EXACTLY one member (mirrors `ProfileOutcome`). `200` → [Loaded]; the CONSTANT
 * byte-identical `404 user_not_found` (unknown / soft-deleted / shadow-banned / blocked-either-direction —
 * all indistinguishable per `follow-system` leak-safety) → the SINGLE [NotFound] (no per-cause branch);
 * `5xx` / transport / parse failure / the unreachable-from-UI `400 invalid_cursor` → the retryable
 * [NetworkError]. `401` is delegated to the shipped `Auth` plugin and never mapped here. No generic
 * fallthrough.
 */
sealed interface FollowListOutcome {
    data class Loaded(val page: FollowListPage) : FollowListOutcome

    data object NotFound : FollowListOutcome

    data object NetworkError : FollowListOutcome
}

/**
 * The list orchestration contract consumed by `FollowListViewModel`. The production binding is
 * [FollowListRepository] (a stateless Koin singleton); commonTest substitutes a `FakeFollowListFlow` so the
 * screen + VM tests drive specific outcomes without a backend (mirroring `FakeProfileFlow`). [cursor] `null`
 * = the first page; a non-null cursor = a load-more page. The method maps each HTTP status + transport
 * failure to EXACTLY one [FollowListOutcome] member, with no generic "failed" wildcard.
 */
interface FollowListFlow {
    suspend fun load(
        userId: String,
        tab: FollowListTab,
        cursor: String?,
    ): FollowListOutcome
}
