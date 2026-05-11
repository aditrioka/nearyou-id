package id.nearyou.app.post

import id.nearyou.app.guard.ContentLengthGuard
import id.nearyou.app.infra.repo.NewPostRow
import id.nearyou.app.infra.repo.PostRepository
import id.nearyou.app.moderation.Layer3DispatcherScope
import id.nearyou.app.moderation.Layer3Moderator
import id.nearyou.app.moderation.TargetType
import id.nearyou.app.moderation.TextModerator
import id.nearyou.app.moderation.Verdict
import id.nearyou.data.repository.ModerationQueueRepository
import id.nearyou.data.repository.ReportTargetType
import id.nearyou.distance.JitterEngine
import id.nearyou.distance.LatLng
import id.nearyou.distance.UuidV7
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.coroutines.coroutineContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toJavaUuid

/**
 * Post creation orchestration — length guard → coord envelope → text moderation →
 * UUIDv7 → HMAC jitter → single-INSERT (+ optional moderation_queue row in same
 * transaction on Verdict.Flag).
 *
 * The text-moderation gate runs AFTER the existing length validation (a 281-char
 * payload is rejected by the length guard before consuming Redis/Remote Config
 * bandwidth) AND BEFORE the canonical INSERT (Verdict.Reject prevents the row from
 * ever existing). See `### Requirement: TextModerator integration is invoked AFTER
 * existing length and rate-limit gates, BEFORE INSERT` for the call-order contract.
 *
 * The endpoint surface is documented in the `post-creation` capability spec; the
 * moderation gate is added by `content-moderation-keyword-lists`.
 */
class CreatePostService(
    private val dataSource: DataSource,
    private val posts: PostRepository,
    private val contentGuard: ContentLengthGuard,
    private val textModerator: TextModerator,
    private val moderationQueue: ModerationQueueRepository,
    private val jitterSecret: ByteArray,
    private val layer3DispatcherScope: Layer3DispatcherScope? = null,
    private val layer3Moderator: Layer3Moderator? = null,
    private val nowProvider: () -> Instant = Instant::now,
) {
    /**
     * Validates + persists the post inside a single DB transaction. Returns the
     * created row descriptor for the route to render.
     *
     * Exceptions propagate to the route handler / StatusPages:
     *  - [ContentEmptyException] / [ContentTooLongException] → 400
     *  - [LocationOutOfBoundsException] → 400 `location_out_of_bounds`
     *  - [ContentModeratedProfanityException] → 400 `content_moderated_profanity`
     *    (NEW from `content-moderation-keyword-lists`)
     *  - Any DB exception (FK violation on author_id if user was hard-deleted
     *    between auth + INSERT) → 500 via the generic StatusPages handler
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun create(
        authorId: UUID,
        rawContent: String?,
        latitude: Double,
        longitude: Double,
    ): CreatedPost {
        // 1. Length + normalize (throws ContentEmpty / ContentTooLong).
        val content = contentGuard.enforce("post.content", rawContent)

        // 2. Envelope check — Indonesia + 12-mile maritime buffer per docs/08 Phase 1 item 21.
        //    Polygon-precise `admin_regions` check is a separate change.
        if (latitude !in LAT_MIN..LAT_MAX || longitude !in LNG_MIN..LNG_MAX) {
            throw LocationOutOfBoundsException(latitude, longitude)
        }

        // 3. Text moderation gate — Reject short-circuits to 400, Flag falls through
        //    to INSERT + queue row in same tx, Allow falls through to plain INSERT.
        //    Per call-order spec: AFTER length + envelope, BEFORE INSERT. Coord-envelope
        //    ordering relative to moderator is unconstrained per the spec scenario
        //    "Static analysis confirms call order".
        val verdict = textModerator.moderate(content)
        if (verdict is Verdict.Reject) {
            throw ContentModeratedProfanityException(verdict.matchedKeywords)
        }

        // 4. Generate UUIDv7 app-side so the HMAC input is known before INSERT.
        val kotlinUuid = UuidV7.next()
        val postId = kotlinUuid.toJavaUuid()

        // 5. Fuzz the coordinate deterministically — docs/05 § Coordinate Fuzzing.
        val display = JitterEngine.offsetByBearing(LatLng(latitude, longitude), kotlinUuid, jitterSecret)

        // 6. Single-INSERT transaction (+ moderation_queue row on Flag).
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                posts.create(
                    conn,
                    NewPostRow(
                        id = postId,
                        authorId = authorId,
                        content = content,
                        actualLat = latitude,
                        actualLng = longitude,
                        displayLat = display.lat,
                        displayLng = display.lng,
                    ),
                )
                if (verdict is Verdict.Flag) {
                    moderationQueue.upsertUuIteKeywordMatchRow(
                        conn = conn,
                        targetType = ReportTargetType.POST,
                        targetId = postId,
                    )
                }
                conn.commit()
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            } finally {
                conn.autoCommit = true
            }
        }

        // Layer 3 (Perspective API) async dispatch — fire-and-forget, BEFORE return.
        // Lives AFTER the commit so a rolled-back INSERT never produces a Layer 3
        // moderation queue row. Passing `coroutineContext` propagates the OTel
        // trace context per design.md Decision 13 — the Layer 3 span is parented
        // under the originating request span.
        //
        // Local shadows of the constructor properties so smart-cast works on the
        // null check (Kotlin doesn't smart-cast `private val` properties through
        // a multi-condition `if`).
        @Suppress("NAME_SHADOWING")
        val layer3DispatcherScope = layer3DispatcherScope

        @Suppress("NAME_SHADOWING")
        val layer3Moderator = layer3Moderator
        if (layer3DispatcherScope != null && layer3Moderator != null) {
            layer3DispatcherScope.dispatch(coroutineContext) {
                layer3Moderator.moderate(TargetType.POST, postId, content)
            }
        }

        return CreatedPost(
            id = postId,
            content = content,
            latitude = latitude,
            longitude = longitude,
            createdAt = nowProvider(),
        )
    }

    companion object {
        // Bounding box for the archipelago + 12-mile maritime buffer; see docs/08 Phase 1 item 21.
        const val LAT_MIN: Double = -11.0
        const val LAT_MAX: Double = 6.5
        const val LNG_MIN: Double = 94.0
        const val LNG_MAX: Double = 142.0
    }
}

/**
 * Thrown when the post content matches the Layer 1 profanity blocklist. Mapped at
 * the `StatusPages` layer to HTTP 400 with `error.code = "content_moderated_profanity"`
 * and the canonical Bahasa Indonesia message. The matched-keyword list is captured
 * here for ops/Sentry but NEVER appears in the HTTP response body (would tip off
 * bypass attempts) — see `### Requirement: User-facing rejection message is in
 * Bahasa Indonesia, omits matched keywords`.
 */
class ContentModeratedProfanityException(val matchedKeywords: List<String>) :
    RuntimeException("content matched the profanity blocklist (${matchedKeywords.size} distinct keywords)")

data class CreatedPost(
    val id: UUID,
    val content: String,
    val latitude: Double,
    val longitude: Double,
    val createdAt: Instant,
)

class LocationOutOfBoundsException(val latitude: Double, val longitude: Double) :
    RuntimeException("coordinate ($latitude, $longitude) is outside the Indonesia envelope")
