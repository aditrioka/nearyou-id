package id.nearyou.app.data.block

/**
 * A block-create maps to EXACTLY one member. `204` → [Blocked]; `429` → [RateLimited] (carrying the
 * parsed `Retry-After` seconds — the 30/h block rate limit); `5xx`/transport/any other status (incl.
 * an unreachable-from-UI constant `404 user_not_found` and the backend's belt-and-suspenders
 * `400 cannot_block_self`) → the retryable [NetworkError]. `401` is delegated to the shipped `Auth`
 * plugin and never mapped here.
 *
 * Relocated from `id.nearyou.app.profile.ProfileFlow` into the shared `data/block/` seam
 * (mobile-block-from-content, design D2) so the profile AND post-detail (post-header + reply-row)
 * surfaces consume ONE outcome type — mirroring how `data/report/ReportOutcome` serves every report
 * surface.
 */
sealed interface BlockOutcome {
    data object Blocked : BlockOutcome

    data class RateLimited(val retryAfterSeconds: Long) : BlockOutcome

    data object NetworkError : BlockOutcome
}
