package id.nearyou.app.timeline

import id.nearyou.distance.LatLng

/**
 * The Nearby-timeline orchestration contract consumed by `NearbyTimelineScreen`. The production
 * binding is [NearbyTimelineRepository]; commonTest substitutes a `FakeNearbyTimelineFlow` so the
 * screen tests can drive a specific outcome / assert (re-)invocation without a backend — mirroring
 * `mobile-auth-signin`'s `AuthFlow` seam.
 */
interface NearbyTimelineFlow {
    /**
     * Loads the first page of the Nearby feed (≤ 30 posts) at [radiusM] (default [NEARBY_RADIUS_M] =
     * 20 km; `mobile-nearby-radius-slider`). Pull-to-refresh re-invokes this at the currently-selected
     * radius; the returned [NearbyTimelineOutcome.Loaded.nextCursor] drives [loadMore], and the resolved
     * [NearbyTimelineOutcome.Loaded.anchor] is the coordinate [loadMore] reuses.
     */
    suspend fun loadFirstPage(radiusM: Int = NEARBY_RADIUS_M): NearbyTimelineOutcome

    /**
     * Loads a subsequent page for [cursor], **reusing the first-page [anchor] coordinate** rather than
     * re-acquiring a device fix (`mobile-nearby-timeline-infinite-scroll`, design D4). The backend
     * cursor is chronological (`createdAt`, `id`), so ordering is anchor-independent; reusing the anchor
     * keeps the selected [radiusM] stable across pages and avoids redundant GPS. [anchor] is request
     * state only — it is never rendered or logged.
     */
    suspend fun loadMore(
        cursor: String,
        anchor: LatLng,
        radiusM: Int = NEARBY_RADIUS_M,
    ): NearbyTimelineOutcome

    /**
     * Reloads page 1 at a newly-selected [radiusM] (`mobile-nearby-radius-slider`). Distinct from
     * [loadFirstPage] because it surfaces the server Premium gate ([RadiusChangeResult.PremiumGated],
     * HTTP 403 `radius_premium_only`) as its OWN result rather than collapsing it into the frozen
     * status→outcome mapping — so the ViewModel can interpret the 403 (→ revert to 20 km + Premium
     * upsell) WITHOUT a new [NearbyTimelineOutcome] member.
     */
    suspend fun changeRadius(radiusM: Int): RadiusChangeResult
}

/**
 * The result of an explicit radius selection ([NearbyTimelineFlow.changeRadius]). Kept distinct from
 * [NearbyTimelineOutcome] so the `radius_premium_only` 403 can be surfaced WITHOUT adding a member to
 * the frozen status→outcome mapping (`mobile-nearby-radius-slider`): the ViewModel interprets
 * [PremiumGated] (→ revert to 20 km + Premium upsell) vs [Loaded] (→ adopt the new page) itself.
 */
sealed interface RadiusChangeResult {
    /** The radius was accepted; carries the normal fetch [outcome] (200 → `Loaded`, or the retryable
     *  `Error`/`NetworkError`/`SessionExpired` fallback for any other status — the unchanged mapping). */
    data class Loaded(val outcome: NearbyTimelineOutcome) : RadiusChangeResult

    /** HTTP 403 with `error.code = "radius_premium_only"` — the server-side Premium gate. A stale-tier
     *  backstop: the client gate normally prevents a Free session from ever issuing a non-20 km radius. */
    data object PremiumGated : RadiusChangeResult
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
        // The coordinate this page was fetched against — retained so load-more can reuse the first-page
        // anchor (design D4). VM-held request state ONLY: it is NEVER projected to the UI state, rendered,
        // or logged (the same discipline that strips the per-post latitude/longitude). Nullable + default
        // so commonTest fakes that don't model the anchor still construct Loaded.
        val anchor: LatLng? = null,
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
