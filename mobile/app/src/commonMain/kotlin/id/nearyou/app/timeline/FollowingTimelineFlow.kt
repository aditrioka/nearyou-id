package id.nearyou.app.timeline

/**
 * The Following-timeline orchestration contract consumed by `FollowingTimelineScreen`. The production
 * binding is [FollowingTimelineRepository]; commonTest substitutes a `FakeFollowingTimelineFlow` so the
 * screen tests can drive a specific outcome / assert (re-)invocation without a backend — mirroring the
 * Nearby/Global `*TimelineFlow` seam.
 */
interface FollowingTimelineFlow {
    /**
     * Loads the first page of the Following feed. Pull-to-refresh re-invokes this; the returned
     * [FollowingTimelineOutcome.Loaded.nextCursor] is retained but not yet consumed for load-more
     * (deferred alongside `mobile-nearby-timeline-infinite-scroll`, extended to cover Following).
     */
    suspend fun loadFirstPage(): FollowingTimelineOutcome
}

/**
 * Every Following fetch maps to EXACTLY one member, keyed on the HTTP **status** / transport-failure
 * type — NOT on a parsed `error.code` — with no generic "load failed" fallthrough (design D1, mirroring
 * `GlobalTimelineOutcome`).
 *
 * The rate-limit hard cap is ALSO a `200` (empty `posts` + `upsell.hard`), so it is carried inside
 * [Loaded] (not a distinct member); the screen derives the hard/soft presentation from the parsed
 * [UpsellDto] flags. A terminal `401` (one that survived the shipped `Auth` `refreshTokens` because the
 * refresh itself failed) maps to [SessionExpired] — the shipped `Auth` plugin + `SessionInvalidator`
 * still own the actual re-route to sign-in; this member only guarantees the brief pre-re-route render is
 * a neutral redirect placeholder, never the connectivity copy.
 */
sealed interface FollowingTimelineOutcome {
    /**
     * HTTP 200. Carries the raw parsed [posts] (incl. the `authorUserId` / `latitude` / `longitude`
     * fields that the UI-state projection STRIPS — PII never reaches the rendered state), the bare
     * camelCase [nextCursor] (retained, not yet used for load-more), and the optional [upsell].
     */
    data class Loaded(
        val posts: List<FollowingPostDto>,
        val nextCursor: String?,
        val upsell: UpsellDto?,
    ) : FollowingTimelineOutcome

    /** HTTP 5xx, transport/IO failure, or any unenumerated non-2xx status — retryable (connectivity copy). */
    data object NetworkError : FollowingTimelineOutcome

    /** HTTP 400 (`invalid_cursor` — not expected on the always-valid first page) — retryable, logged. */
    data object Error : FollowingTimelineOutcome

    /** Terminal HTTP 401 (survived the `Auth` refresh) — session expired. The screen renders a neutral
     *  redirect placeholder (NO retry, NOT the connectivity copy); navigation is owned by the `Auth`
     *  plugin + `SessionInvalidator`. MUST NOT map to [NetworkError]/[Error]. */
    data object SessionExpired : FollowingTimelineOutcome
}
