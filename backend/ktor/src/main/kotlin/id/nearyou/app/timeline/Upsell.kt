package id.nearyou.app.timeline

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Optional response object surfacing Layer 2 timeline-read cap signals to the mobile
 * client. Per the `timeline-read-rate-limit` capability spec § "Upsell response object
 * shape":
 *
 *  - `soft = true` — set when the per-session pre-check (50/session) returned
 *    `RateLimited`; the request still admits, posts are returned, the mobile UI
 *    surfaces an upsell banner.
 *  - `hard = true` — set when the per-user rolling pre-check (150/hour) returned
 *    `RateLimited`; the request short-circuits before the DB query, posts is `[]`,
 *    `next_cursor` is `null`, and `soft` is omitted (the session pre-check never
 *    executed per the limiter ordering).
 *
 * `@EncodeDefault(NEVER)` ensures null-valued fields are stripped from the serialized
 * JSON regardless of the global `Json { explicitNulls = false }` config — robust against
 * a future config change. The enclosing optional `upsell` field on each timeline
 * response DTO uses the same convention; clients defensively read
 * `?.upsell?.soft ?? false`.
 *
 * Wire format example (soft cap reached):
 * ```json
 * { "posts": [...], "nextCursor": "...", "upsell": { "soft": true } }
 * ```
 *
 * Below all caps the entire `upsell` object is omitted (additive, no breaking change
 * for clients that don't read it).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Upsell(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val soft: Boolean? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val hard: Boolean? = null,
)
