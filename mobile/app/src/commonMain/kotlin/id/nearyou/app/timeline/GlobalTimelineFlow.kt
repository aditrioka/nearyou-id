package id.nearyou.app.timeline

/**
 * The Global-timeline orchestration contract consumed by `GlobalTimelineScreen`. The production
 * binding is [GlobalTimelineRepository]; commonTest substitutes a `FakeGlobalTimelineFlow` so the
 * screen tests can drive a specific outcome / assert (re-)invocation without a backend — mirroring
 * the Nearby `NearbyTimelineFlow` seam.
 */
interface GlobalTimelineFlow {
    /**
     * Loads the first page of the Global feed. Pull-to-refresh re-invokes this; the returned
     * [GlobalTimelineOutcome.Loaded.nextCursor] is retained but not yet consumed for load-more
     * (deferred alongside `mobile-nearby-timeline-infinite-scroll`).
     */
    suspend fun loadFirstPage(): GlobalTimelineOutcome
}

/**
 * Every Global fetch maps to EXACTLY one member, keyed on the HTTP **status** / transport-failure
 * type — NOT on a parsed `error.code` — with no generic "load failed" fallthrough (design D4).
 *
 * The rate-limit hard cap is ALSO a `200` (empty `posts` + `upsell.hard`), so it is carried inside
 * [Loaded] (not a distinct member); the screen derives the hard/soft presentation from the parsed
 * [UpsellDto] flags. `401` is delegated to the shipped `Auth` plugin (terminal 401 → store cleared
 * → re-route to sign-in), NEVER mapped here.
 */
sealed interface GlobalTimelineOutcome {
    /**
     * HTTP 200. Carries the raw parsed [posts] (incl. the `authorUserId` / `latitude` / `longitude`
     * fields that the UI-state projection STRIPS — PII never reaches the rendered state), the bare
     * camelCase [nextCursor] (retained, not yet used for load-more), and the optional [upsell].
     */
    data class Loaded(
        val posts: List<GlobalPostDto>,
        val nextCursor: String?,
        val upsell: UpsellDto?,
    ) : GlobalTimelineOutcome

    /** HTTP 5xx, transport/IO failure, or any unenumerated status — retryable. */
    data object NetworkError : GlobalTimelineOutcome

    /** HTTP 400 (`invalid_cursor` — not expected on the always-valid first page) — retryable, logged. */
    data object Error : GlobalTimelineOutcome
}
