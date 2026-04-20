package id.nearyou.app.post

import id.nearyou.app.guard.ContentLengthGuard
import id.nearyou.app.infra.repo.NewPostRow
import id.nearyou.app.infra.repo.PostRepository
import id.nearyou.distance.JitterEngine
import id.nearyou.distance.LatLng
import id.nearyou.distance.UuidV7
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toJavaUuid

/**
 * Post creation orchestration — length guard → coord envelope → UUIDv7 → HMAC jitter →
 * single-INSERT transaction. The endpoint surface is documented in the `post-creation`
 * capability spec.
 */
class CreatePostService(
    private val dataSource: DataSource,
    private val posts: PostRepository,
    private val contentGuard: ContentLengthGuard,
    private val jitterSecret: ByteArray,
    private val nowProvider: () -> Instant = Instant::now,
) {
    /**
     * Validates + persists the post inside a single DB transaction. Returns the
     * created row descriptor for the route to render.
     *
     * Exceptions propagate to the route handler / StatusPages:
     *  - [ContentEmptyException] / [ContentTooLongException] → 400
     *  - [LocationOutOfBoundsException] → 400 `location_out_of_bounds`
     *  - Any DB exception (FK violation on author_id if user was hard-deleted
     *    between auth + INSERT) → 500 via the generic StatusPages handler
     */
    @OptIn(ExperimentalUuidApi::class)
    fun create(
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

        // 3. Generate UUIDv7 app-side so the HMAC input is known before INSERT.
        val kotlinUuid = UuidV7.next()
        val postId = kotlinUuid.toJavaUuid()

        // 4. Fuzz the coordinate deterministically — docs/05 § Coordinate Fuzzing.
        val display = JitterEngine.offsetByBearing(LatLng(latitude, longitude), kotlinUuid, jitterSecret)

        // 5. Single-INSERT transaction.
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
                conn.commit()
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            } finally {
                conn.autoCommit = true
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

data class CreatedPost(
    val id: UUID,
    val content: String,
    val latitude: Double,
    val longitude: Double,
    val createdAt: Instant,
)

class LocationOutOfBoundsException(val latitude: Double, val longitude: Double) :
    RuntimeException("coordinate ($latitude, $longitude) is outside the Indonesia envelope")
