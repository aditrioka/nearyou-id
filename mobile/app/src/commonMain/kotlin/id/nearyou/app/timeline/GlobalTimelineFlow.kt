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
     * [GlobalTimelineOutcome.Loaded.nextCursor] drives [loadMore].
     */
    suspend fun loadFirstPage(): GlobalTimelineOutcome

    /**
     * Loads a subsequent page for [cursor]. Unlike Nearby, Global is **cursor-only** — there is no
     * spatial anchor to reuse (Global has no spatial filter), so the request carries only the cursor
     * (`mobile-nearby-timeline-infinite-scroll`, extended to Global). The backend cursor is chronological
     * (`createdAt`, `id`).
     */
    suspend fun loadMore(cursor: String): GlobalTimelineOutcome
}

/**
 * Every Global fetch maps to EXACTLY one member, keyed on the HTTP **status** / transport-failure
 * type — NOT on a parsed `error.code` — with no generic "load failed" fallthrough (design D4).
 *
 * The rate-limit hard cap is ALSO a `200` (empty `posts` + `upsell.hard`), so it is carried inside
 * [Loaded] (not a distinct member); the screen derives the hard/soft presentation from the parsed
 * [UpsellDto] flags. A terminal `401` (one that survived the shipped `Auth` `refreshTokens` because
 * the refresh itself failed) maps to [SessionExpired] (mobile-session-expiry-and-proactive-refresh
 * D4) — the shipped `Auth` plugin + `SessionInvalidator` still own the actual re-route to sign-in;
 * this member only guarantees the brief pre-re-route render is a neutral redirect placeholder, never
 * the connectivity copy.
 */
sealed interface GlobalTimelineOutcome {
    /**
     * HTTP 200. Carries the raw parsed [posts] (incl. the `authorUserId` / `latitude` / `longitude`
     * fields that the UI-state projection STRIPS — PII never reaches the rendered state), the bare
     * camelCase [nextCursor] (drives [loadMore]; `null` ⇒ end-reached), and the optional [upsell].
     */
    data class Loaded(
        val posts: List<GlobalPostDto>,
        val nextCursor: String?,
        val upsell: UpsellDto?,
    ) : GlobalTimelineOutcome

    /** HTTP 5xx, transport/IO failure, or any unenumerated non-2xx status — retryable (connectivity copy). */
    data object NetworkError : GlobalTimelineOutcome

    /** HTTP 400 (`invalid_cursor` — not expected on the always-valid first page) — retryable, logged. */
    data object Error : GlobalTimelineOutcome

    /** Terminal HTTP 401 (survived the `Auth` refresh) — session expired. The screen renders a neutral
     *  redirect placeholder (NO retry, NOT the connectivity copy); navigation is owned by the `Auth`
     *  plugin + `SessionInvalidator`. MUST NOT map to [NetworkError]/[Error]. */
    data object SessionExpired : GlobalTimelineOutcome
}
