package id.nearyou.app.post

import id.nearyou.app.core.domain.ratelimit.computeTTLToNextReset
import id.nearyou.app.guard.ContentLengthGuard
import id.nearyou.app.image.ImageUploadRepository
import id.nearyou.app.infra.repo.NewPostRow
import id.nearyou.app.infra.repo.PostRepository
import id.nearyou.app.moderation.Layer3DispatcherScope
import id.nearyou.app.moderation.Layer3Moderator
import id.nearyou.app.moderation.TargetType
import id.nearyou.app.moderation.TextModerator
import id.nearyou.app.moderation.Verdict
import id.nearyou.app.subscription.PREMIUM_STATES
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Post creation orchestration — length guard → coord envelope → daily cap (Free) →
 * text moderation → UUIDv7 → HMAC jitter → per-area density gate → single-INSERT
 * (+ optional moderation_queue row in same transaction on Verdict.Flag and/or the
 * Layer 4 area-density `area_spam` soft flag).
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
    // docs/05 § Layer 2 daily cap — 10/day Free, Premium skips (2026-06-10 audit, 02-M2).
    private val rateLimiter: PostRateLimiter = PostRateLimiter(),
    // premium-image-upload-pipeline — owner-validated image attach (null on the text-only
    // path / pre-image test fixtures). Required only when create() is called with an imageId.
    private val imageUploads: ImageUploadRepository? = null,
    // docs/05 § Layer 4 per-area density gate — flags the next post in an over-dense
    // ~1.1 km cell (50/1h) to manual review, ALL tiers (post-area-density-cap). The
    // default keeps pre-gate fixtures constructing (in-memory limiter, fresh counter).
    private val areaDensityLimiter: AreaPostDensityLimiter = AreaPostDensityLimiter(),
    // Pool-bounded JDBC dispatcher (docs/11 §3.2); production passes DbDispatchers.db.
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
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
        // The route MUST pass the principal's tier (PostRoutes does); the default
        // exists only so pre-cap test fixtures keep constructing the Free path.
        subscriptionStatus: String = "free",
        // Optional Cloudflare image id to attach (premium-image-upload-pipeline). Validated
        // owner + unattached and flipped to 'attached' in the same tx as the post INSERT.
        imageId: String? = null,
    ): CreatedPost {
        // 1. Length + normalize (throws ContentEmpty / ContentTooLong).
        val content = contentGuard.enforce("post.content", rawContent)

        // 2. Envelope check — Indonesia + 12-mile maritime buffer per docs/08 Phase 1 item 21.
        //    Polygon-precise `admin_regions` check is a separate change.
        if (latitude !in LAT_MIN..LAT_MAX || longitude !in LNG_MIN..LNG_MAX) {
            throw LocationOutOfBoundsException(latitude, longitude)
        }

        // 2.5. Daily cap — 10/day Free, Premium skips (docs/05 § Layer 2; 02-M2).
        //    AFTER the free validation gates (malformed requests burn no slot),
        //    BEFORE the moderation I/O (a capped user burns no Redis/RC budget).
        //    Slots consumed by later rejections (moderation Reject) are NOT
        //    released — anti-abuse, mirrors the like limiter's 404-no-release.
        if (subscriptionStatus !in PREMIUM_STATES) {
            val outcome =
                withContext(dbDispatcher) {
                    rateLimiter.tryAcquire(authorId, computeTTLToNextReset(authorId, nowProvider()))
                }
            if (outcome is PostRateLimiter.Outcome.RateLimited) {
                throw PostRateLimitedException(outcome.retryAfterSeconds)
            }
        }

        // 3. Text moderation gate — Reject short-circuits to 400, Flag falls through
        //    to INSERT + queue row in same tx, Allow falls through to plain INSERT.
        //    Per call-order spec: AFTER length + envelope, BEFORE INSERT. Coord-envelope
        //    ordering relative to moderator is unconstrained per the spec scenario
        //    "Static analysis confirms call order".
        //    Runs on the bounded dispatcher — moderate() does Redis/Remote-Config/
        //    Secret-Manager I/O on a cold cache (2026-06-10 audit, 04-#2).
        val verdict = withContext(dbDispatcher) { textModerator.moderate(content) }
        if (verdict is Verdict.Reject) {
            throw ContentModeratedProfanityException(verdict.matchedKeywords)
        }

        // 4. Generate UUIDv7 app-side so the HMAC input is known before INSERT.
        val kotlinUuid = UuidV7.next()
        val postId = kotlinUuid.toJavaUuid()

        // 5. Fuzz the coordinate deterministically — docs/05 § Coordinate Fuzzing.
        val display = JitterEngine.offsetByBearing(LatLng(latitude, longitude), kotlinUuid, jitterSecret)

        // 5.5. Per-area density gate — docs/05 § Layer 4 (post-area-density-cap).
        //    AFTER the moderator Reject short-circuit (a rejected, never-created post
        //    neither counts toward area density nor produces a queue row — design
        //    Decision 7) AND AFTER display derivation it keys on, BEFORE the INSERT.
        //    Keys on the fuzzed `display` coordinate (NEVER actual — spatial-fuzzing
        //    invariant). Applies to ALL tiers (area-keyed, Premium NOT exempt). The
        //    INCR runs on the bounded dispatcher; OverThreshold drives a same-tx
        //    moderation_queue write below — NEVER a 429 (soft flag, not reject).
        val areaOutcome =
            withContext(dbDispatcher) {
                areaDensityLimiter.tryAcquire(display.lat, display.lng)
            }

        // 6. Single-INSERT transaction (+ moderation_queue row on Flag / area_spam) —
        //    on the pool-bounded dispatcher (docs/11 §3.2; 02-H2).
        withContext(dbDispatcher) {
            dataSource.connection.use { conn ->
                conn.autoCommit = false
                try {
                    // Image attach (premium-image-upload-pipeline): validate ownership +
                    // unattached, then flip 'uploaded' → 'attached' in THIS tx so a failed
                    // post INSERT rolls the flip back. The conditional markAttached resolves
                    // a concurrent-attach race to a single winner (zero-rows → 422).
                    if (imageId != null) {
                        val repo =
                            checkNotNull(imageUploads) {
                                "imageUploads repository is required to attach an image_id"
                            }
                        val ledgerRow =
                            repo.findForAttach(conn, imageId)
                                ?: throw ImageAttachInvalidException(imageId)
                        if (ledgerRow.uploaderUserId != authorId) {
                            throw ImageAttachForbiddenException(imageId)
                        }
                        if (ledgerRow.status != "uploaded" || !repo.markAttached(conn, imageId)) {
                            throw ImageAttachInvalidException(imageId)
                        }
                    }
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
                            imageId = imageId,
                        ),
                    )
                    if (verdict is Verdict.Flag) {
                        moderationQueue.upsertUuIteKeywordMatchRow(
                            conn = conn,
                            targetType = ReportTargetType.POST,
                            targetId = postId,
                        )
                    }
                    // Area-density soft flag — composes with the Flag write above
                    // (both may fire → two rows, one per trigger). Idempotent via
                    // ON CONFLICT (target_type, target_id, trigger) DO NOTHING. The
                    // post stays visible (is_auto_hidden untouched → FALSE).
                    if (areaOutcome is AreaPostDensityLimiter.Outcome.OverThreshold) {
                        moderationQueue.upsertAreaSpamRow(
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

/**
 * Thrown when the Free-tier daily post cap is exhausted (docs/05 § Layer 2;
 * 2026-06-10 audit, finding 02-M2). Mapped at the StatusPages layer to HTTP 429
 * with a `Retry-After` header carrying [retryAfterSeconds] (time to the user's
 * WIB-staggered daily reset).
 */
class PostRateLimitedException(val retryAfterSeconds: Long) :
    RuntimeException("post daily cap exhausted; retry after $retryAfterSeconds s")

/**
 * Thrown when a post attaches an `image_id` owned by a different user (premium-image-upload-
 * pipeline). Mapped at StatusPages to HTTP 403 `image_not_owned`.
 */
class ImageAttachForbiddenException(val imageId: String) :
    RuntimeException("image $imageId is not owned by the caller")

/**
 * Thrown when a post attaches an `image_id` that does not exist, is already attached, or
 * loses the concurrent-attach race (premium-image-upload-pipeline). Mapped at StatusPages to
 * HTTP 422 `image_not_attachable`.
 */
class ImageAttachInvalidException(val imageId: String) :
    RuntimeException("image $imageId is not attachable (missing, already attached, or race lost)")
