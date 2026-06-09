package id.nearyou.app.timeline

/**
 * The Nearby-timeline orchestration contract consumed by `NearbyTimelineScreen`. The production
 * binding is [NearbyTimelineRepository]; commonTest substitutes a `FakeNearbyTimelineFlow` so the
 * screen tests can drive a specific outcome / assert (re-)invocation without a backend — mirroring
 * `mobile-auth-signin`'s `AuthFlow` seam.
 */
interface NearbyTimelineFlow {
    /**
     * Loads the first page of the Nearby feed (≤ 30 posts). Pull-to-refresh re-invokes this; the
     * returned [NearbyTimelineOutcome.Loaded.nextCursor] is retained but not yet consumed for
     * load-more (deferred to `mobile-nearby-timeline-infinite-scroll`).
     */
    suspend fun loadFirstPage(): NearbyTimelineOutcome
}

/**
 * Every Nearby fetch maps to EXACTLY one member, keyed on the HTTP **status** / transport-failure
 * type — NOT on a parsed `error.code` — with no generic "load failed" fallthrough (design D6).
 *
 * The rate-limit hard cap is ALSO a `200` (empty `posts` + `upsell.hard`), so it is carried inside
 * [Loaded] (not a distinct member); the screen derives the hard/soft presentation from the parsed
 * [UpsellDto] flags. A terminal `401` (one that survived the shipped `Auth` `refreshTokens` because
 * the refresh itself failed) maps to [SessionExpired] (mobile-session-expiry-and-proactive-refresh
 * D4) — the shipped `Auth` plugin + `SessionInvalidator` still own the actual re-route to sign-in;
 * this member only guarantees the brief pre-re-route render is a neutral redirect placeholder, never
 * the connectivity copy.
 */
sealed interface NearbyTimelineOutcome {
    /**
     * HTTP 200. Carries the raw parsed [posts] (incl. the `authorUserId` / `latitude` / `longitude`
     * fields that the UI-state projection STRIPS — PII never reaches the rendered state), the bare
     * camelCase [nextCursor] (retained, not yet used for load-more), and the optional [upsell].
     */
    data class Loaded(
        val posts: List<NearbyPostDto>,
        val nextCursor: String?,
        val upsell: UpsellDto?,
    ) : NearbyTimelineOutcome

    /** HTTP 5xx, transport/IO failure, or any unenumerated non-2xx status — retryable (connectivity copy). */
    data object NetworkError : NearbyTimelineOutcome

    /** HTTP 400 (`invalid_request` / `*_out_of_bounds` / `invalid_cursor`) — retryable, logged. */
    data object Error : NearbyTimelineOutcome

    /** Terminal HTTP 401 (survived the `Auth` refresh) — session expired. The screen renders a neutral
     *  redirect placeholder (NO retry, NOT the connectivity copy); navigation is owned by the `Auth`
     *  plugin + `SessionInvalidator`. MUST NOT map to [NetworkError]/[Error]. */
    data object SessionExpired : NearbyTimelineOutcome
}
