package id.nearyou.app.image

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.core.domain.ratelimit.InMemoryRateLimiter
import id.nearyou.app.infra.cloudflareimages.CloudflareImagesConfig
import id.nearyou.app.infra.repo.JdbcPostsFollowingRepository
import id.nearyou.app.infra.repo.JdbcPostsGlobalRepository
import id.nearyou.app.infra.repo.JdbcPostsTimelineRepository
import id.nearyou.app.infra.repo.JdbcSinglePostRepository
import id.nearyou.app.infra.repo.JdbcUserRepository
import id.nearyou.app.post.LocationOutOfBoundsException
import id.nearyou.app.post.PostReadService
import id.nearyou.app.post.singlePostRoutes
import id.nearyou.app.timeline.FollowingTimelineService
import id.nearyou.app.timeline.GlobalTimelineService
import id.nearyou.app.timeline.NearbyTimelineService
import id.nearyou.app.timeline.TimelineReadRateLimiter
import id.nearyou.app.timeline.followingTimelineRoutes
import id.nearyou.app.timeline.globalTimelineRoutes
import id.nearyou.app.timeline.timelineRoutes
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Date
import java.time.LocalDate
import java.util.UUID
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientCN

private fun hikari(): HikariDataSource {
    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val config =
        HikariConfig().apply {
            jdbcUrl = url
            username = System.getenv("DB_USER") ?: "postgres"
            password = System.getenv("DB_PASSWORD") ?: "postgres"
            // Frugal pool + explicit close (afterSpec, NOT autoClose) so it never races ahead of the
            // afterTest cleanup connection use, and so this spec doesn't add to the CI Postgres
            // connection budget (PR #157 / #325 precedents).
            maximumPoolSize = 2
            minimumIdle = 0
            initializationFailTimeout = -1
        }
    return HikariDataSource(config)
}

/**
 * `image-attached-posts` task 7.6 — backend `imageUrl`-surfacing coverage for the four read endpoints
 * (Nearby / Following / Global timelines + the single-post by-id read). Pins the MODIFIED requirement
 * "Post read responses surface the attached image delivery URL" and its scenarios:
 *
 *  - the read item carries the 4-segment `<base>/<accountHash>/<image_id>/public` delivery URL (built
 *    by the SHARED [CloudflareImagesConfig.deliveryUrl] builder via [imageDeliveryUrls]) for an
 *    image-bearing post,
 *  - `imageUrl` is null/absent for a text-only post (the read shape is otherwise unchanged),
 *  - an image-bearing post whose author is shadow-banned / blocked (either direction) / auto-hidden is
 *    excluded exactly as a text-only post — no `imageUrl` leak (it rides `visible_posts`),
 *  - the self-arm STILL projects `image_id` (Nearby / Global / single-post): a viewer reading their OWN
 *    shadow-banned (or auto-hidden) image post via the self arm still gets their `imageUrl`. Following
 *    has no self arm → not covered there.
 *
 * Test config: a complete [CloudflareImagesConfig] with a non-blank `accountHash` + a deterministic
 * `deliveryBaseUrl` so the builder emits a real URL; the expected string is asserted against those
 * values (no Cloudflare network — the read path only formats a URL string).
 *
 * Isolation: usernames carry the spec-unique `iu_` prefix and posts sit in the deep Indian Ocean
 * (-10.5, 105.0 — no kabupaten polygon, far outside any Nearby radius), and every row is deleted in
 * `afterTest` so this spec never pollutes the location-agnostic Global timeline suite (the
 * project.md § Test-data conventions pattern).
 */
@Tags("database")
class ImageUrlSurfacingTest : StringSpec({

    val dataSource = hikari()
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-image-surfacing")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)

    // Complete config (all four fields non-blank) → imageDeliveryUrls emits a real URL. accountHash +
    // deliveryBaseUrl are the load-bearing values asserted below; apiToken/accountId only gate
    // isComplete(). Distinct test host so a leaked prod/staging base can't masquerade as a pass.
    val testConfig =
        CloudflareImagesConfig(
            apiToken = "test-token",
            accountId = "test-account-id",
            accountHash = "testhash123",
            deliveryBaseUrl = "https://img-test.nearyou.id",
        )
    val deliveryUrls = imageDeliveryUrls(testConfig)

    fun expectedUrl(imageId: String): String = "https://img-test.nearyou.id/testhash123/$imageId/public"

    val nearbyService = NearbyTimelineService(JdbcPostsTimelineRepository(dataSource))
    val followingService = FollowingTimelineService(JdbcPostsFollowingRepository(dataSource))
    val globalService = GlobalTimelineService(JdbcPostsGlobalRepository(dataSource))
    val postReadService = PostReadService(JdbcSinglePostRepository(dataSource), deliveryUrls)
    val rateLimiter = TimelineReadRateLimiter(InMemoryRateLimiter())

    // Deep Indian Ocean (>50km from any kabupaten even with the 12nm maritime buffer); inside the
    // envelope [-11, 6.5] x [94, 142] so Nearby accepts the coords, but outside every other timeline
    // test's Nearby radius.
    val lat = -10.5
    val lng = 105.0

    fun seedUser(
        displayName: String = "Image Surfacing Tester",
        shadowBanned: Boolean = false,
    ): Pair<UUID, String> {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix, is_shadow_banned)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "iu_$short")
                ps.setString(3, displayName)
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "u${short.take(7)}")
                ps.setBoolean(6, shadowBanned)
                ps.executeUpdate()
            }
        }
        return id to jwtIssuer.issueAccessToken(id, tokenVersion = 0)
    }

    // Inserts the ownership-ledger row (status 'attached', mirroring the post-attach flip) and returns
    // the cf_image_id. The read path reads posts.image_id; the ledger row exists so the fixture matches
    // the real shipped state (an attached image always has a backing image_uploads row).
    fun seedAttachedUpload(uploader: UUID): String {
        val cfImageId = "img-" + UUID.randomUUID().toString().take(12)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO image_uploads (cf_image_id, uploader_user_id, status) VALUES (?, ?, 'attached')",
            ).use { ps ->
                ps.setString(1, cfImageId)
                ps.setObject(2, uploader)
                ps.executeUpdate()
            }
        }
        return cfImageId
    }

    fun seedPost(
        authorId: UUID,
        imageId: String? = null,
        autoHidden: Boolean = false,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO posts (id, author_id, content, display_location, actual_location, is_auto_hidden, image_id)
                VALUES (?, ?, ?,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                  ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, authorId)
                ps.setString(3, "post-${id.toString().take(6)}")
                ps.setDouble(4, lng)
                ps.setDouble(5, lat)
                ps.setDouble(6, lng)
                ps.setDouble(7, lat)
                ps.setBoolean(8, autoHidden)
                if (imageId != null) ps.setString(9, imageId) else ps.setNull(9, java.sql.Types.VARCHAR)
                ps.executeUpdate()
            }
        }
        return id
    }

    fun follow(
        follower: UUID,
        followee: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO follows (follower_id, followee_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
            ).use { ps ->
                ps.setObject(1, follower)
                ps.setObject(2, followee)
                ps.executeUpdate()
            }
        }
    }

    fun insertBlock(
        blocker: UUID,
        blocked: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
            ).use { ps ->
                ps.setObject(1, blocker)
                ps.setObject(2, blocked)
                ps.executeUpdate()
            }
        }
    }

    fun setShadowBanned(
        userId: UUID,
        banned: Boolean,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE users SET is_shadow_banned = ? WHERE id = ?").use { ps ->
                ps.setBoolean(1, banned)
                ps.setObject(2, userId)
                ps.executeUpdate()
            }
        }
    }

    // Spec-scoped cleanup after every test (the per-test convention) so image/post/user rows never leak
    // into the location-agnostic Global suite. Children before parents (FK RESTRICT/CASCADE order-safe).
    afterTest {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    "DELETE FROM image_uploads WHERE uploader_user_id IN (SELECT id FROM users WHERE username LIKE 'iu\\_%')",
                )
                st.executeUpdate(
                    "DELETE FROM post_replies WHERE author_id IN (SELECT id FROM users WHERE username LIKE 'iu\\_%')",
                )
                st.executeUpdate(
                    "DELETE FROM posts WHERE author_id IN (SELECT id FROM users WHERE username LIKE 'iu\\_%')",
                )
                st.executeUpdate(
                    "DELETE FROM follows WHERE follower_id IN (SELECT id FROM users WHERE username LIKE 'iu\\_%')" +
                        " OR followee_id IN (SELECT id FROM users WHERE username LIKE 'iu\\_%')",
                )
                st.executeUpdate(
                    "DELETE FROM user_blocks WHERE blocker_id IN (SELECT id FROM users WHERE username LIKE 'iu\\_%')" +
                        " OR blocked_id IN (SELECT id FROM users WHERE username LIKE 'iu\\_%')",
                )
                st.executeUpdate("DELETE FROM users WHERE username LIKE 'iu\\_%'")
            }
        }
    }

    // Close the pool LAST so it never races ahead of the afterTest cleanup (autoClose-vs-cleanup hazard).
    afterSpec { dataSource.close() }

    // ── shared test-app harnesses (mirror the per-endpoint route tests, wired with the CONFIGURED
    //    delivery-URL builder so imageUrl is actually built). ──────────────────────────────────────

    suspend fun withTimelineApp(block: suspend ApplicationTestBuilder.() -> Unit) {
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            explicitNulls = false
                        },
                    )
                }
                install(StatusPages) {
                    exception<LocationOutOfBoundsException> { call, _ ->
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to mapOf("code" to "location_out_of_bounds", "message" to "envelope")),
                        )
                    }
                }
                install(Authentication) { configureUserJwt(keys, users, java.time.Instant::now) }
                timelineRoutes(nearbyService, rateLimiter, deliveryUrls)
                followingTimelineRoutes(followingService, rateLimiter, deliveryUrls)
                globalTimelineRoutes(globalService, rateLimiter, deliveryUrls)
                singlePostRoutes(postReadService)
            }
            block()
        }
    }

    fun postsArray(body: String): List<JsonObject> = Json.parseToJsonElement(body).jsonObject["posts"]!!.jsonArray.map { it.jsonObject }

    suspend fun ApplicationTestBuilder.nearby(token: String): List<JsonObject> {
        // radius_m MUST be an ALLOWED_RADII_M value (mobile-nearby-radius-slider added `radius_out_of_bounds`
        // 400 validation); use FREE_RADIUS_M (20 km) so the (Free) seed users also clear the Premium radius gate.
        val resp =
            createClient { install(ClientCN) { json() } }
                .get("/api/v1/timeline/nearby?lat=$lat&lng=$lng&radius_m=${NearbyTimelineService.FREE_RADIUS_M}") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
        resp.status shouldBe HttpStatusCode.OK
        return postsArray(resp.bodyAsText())
    }

    suspend fun ApplicationTestBuilder.following(token: String): List<JsonObject> {
        val resp =
            createClient { install(ClientCN) { json() } }
                .get("/api/v1/timeline/following") { header(HttpHeaders.Authorization, "Bearer $token") }
        resp.status shouldBe HttpStatusCode.OK
        return postsArray(resp.bodyAsText())
    }

    suspend fun ApplicationTestBuilder.global(token: String): List<JsonObject> {
        val resp =
            createClient { install(ClientCN) { json() } }
                .get("/api/v1/timeline/global") { header(HttpHeaders.Authorization, "Bearer $token") }
        resp.status shouldBe HttpStatusCode.OK
        return postsArray(resp.bodyAsText())
    }

    suspend fun ApplicationTestBuilder.singlePost(
        postId: UUID,
        token: String,
    ): Pair<HttpStatusCode, JsonObject?> {
        val resp =
            createClient { install(ClientCN) { json() } }
                .get("/api/v1/posts/$postId") { header(HttpHeaders.Authorization, "Bearer $token") }
        val body = resp.bodyAsText()
        return resp.status to (if (resp.status == HttpStatusCode.OK) body.let(Json::parseToJsonElement).jsonObject else null)
    }

    fun List<JsonObject>.byId(id: UUID): JsonObject = first { it["id"]!!.jsonPrimitive.content == id.toString() }

    fun List<JsonObject>.ids(): Set<String> = map { it["id"]!!.jsonPrimitive.content }.toSet()

    fun JsonObject.imageUrl(): String = this["imageUrl"]!!.jsonPrimitive.content

    fun JsonObject.imageUrlAbsentOrNull(): Boolean = this["imageUrl"] == null || this["imageUrl"] is JsonNull

    // ── Scenario 1: image post → 4-segment shared-builder delivery URL on every endpoint ──────────

    "7.6 image post — Nearby surfaces the shared-builder delivery URL" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        val image = seedAttachedUpload(author)
        val postId = seedPost(author, imageId = image)
        withTimelineApp {
            nearby(vt).byId(postId).imageUrl() shouldBe expectedUrl(image)
        }
    }

    "7.6 image post — Following surfaces the shared-builder delivery URL" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        follow(viewer, author)
        val image = seedAttachedUpload(author)
        val postId = seedPost(author, imageId = image)
        withTimelineApp {
            following(vt).byId(postId).imageUrl() shouldBe expectedUrl(image)
        }
    }

    "7.6 image post — Global surfaces the shared-builder delivery URL" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        val image = seedAttachedUpload(author)
        val postId = seedPost(author, imageId = image)
        withTimelineApp {
            global(vt).byId(postId).imageUrl() shouldBe expectedUrl(image)
        }
    }

    "7.6 image post — single-post read surfaces the shared-builder delivery URL" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        val image = seedAttachedUpload(author)
        val postId = seedPost(author, imageId = image)
        withTimelineApp {
            val (status, body) = singlePost(postId, vt)
            status shouldBe HttpStatusCode.OK
            body!!.imageUrl() shouldBe expectedUrl(image)
        }
    }

    // ── Scenario 2: text-only post → imageUrl null/absent on every endpoint ───────────────────────

    "7.6 text-only post — imageUrl absent on all four endpoints" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        follow(viewer, author)
        val postId = seedPost(author, imageId = null)
        withTimelineApp {
            nearby(vt).byId(postId).imageUrlAbsentOrNull() shouldBe true
            following(vt).byId(postId).imageUrlAbsentOrNull() shouldBe true
            global(vt).byId(postId).imageUrlAbsentOrNull() shouldBe true
            val (status, body) = singlePost(postId, vt)
            status shouldBe HttpStatusCode.OK
            body!!.imageUrlAbsentOrNull() shouldBe true
        }
    }

    // ── Scenario 3: image posts respect shadow-ban + block exclusion (no imageUrl leak) ───────────

    "7.6 shadow-banned author's image post is excluded with no imageUrl leak (timelines + 404)" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser(shadowBanned = true)
        follow(viewer, author)
        val image = seedAttachedUpload(author)
        val postId = seedPost(author, imageId = image)
        withTimelineApp {
            nearby(vt).ids().contains(postId.toString()) shouldBe false
            following(vt).ids().contains(postId.toString()) shouldBe false
            global(vt).ids().contains(postId.toString()) shouldBe false
            // Single post: constant opaque 404 (no body to leak the URL into).
            singlePost(postId, vt).first shouldBe HttpStatusCode.NotFound
        }
    }

    "7.6 blocked-author image post is excluded both directions with no imageUrl leak" {
        val (viewer, vt) = seedUser()
        val (viewerBlocksAuthor, _) = seedUser() // viewer -> author block
        val (authorBlocksViewer, _) = seedUser() // author -> viewer block
        follow(viewer, viewerBlocksAuthor)
        follow(viewer, authorBlocksViewer)
        insertBlock(viewer, viewerBlocksAuthor)
        insertBlock(authorBlocksViewer, viewer)
        val imageA = seedAttachedUpload(viewerBlocksAuthor)
        val imageB = seedAttachedUpload(authorBlocksViewer)
        val postA = seedPost(viewerBlocksAuthor, imageId = imageA)
        val postB = seedPost(authorBlocksViewer, imageId = imageB)
        withTimelineApp {
            val nearbyIds = nearby(vt).ids()
            val followingIds = following(vt).ids()
            val globalIds = global(vt).ids()
            listOf(postA, postB).forEach { p ->
                nearbyIds.contains(p.toString()) shouldBe false
                followingIds.contains(p.toString()) shouldBe false
                globalIds.contains(p.toString()) shouldBe false
                singlePost(p, vt).first shouldBe HttpStatusCode.NotFound
            }
        }
    }

    "7.6 auto-hidden image post is excluded for a non-author viewer with no imageUrl leak" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        follow(viewer, author)
        val image = seedAttachedUpload(author)
        val postId = seedPost(author, imageId = image, autoHidden = true)
        withTimelineApp {
            nearby(vt).ids().contains(postId.toString()) shouldBe false
            following(vt).ids().contains(postId.toString()) shouldBe false
            global(vt).ids().contains(postId.toString()) shouldBe false
            singlePost(postId, vt).first shouldBe HttpStatusCode.NotFound
        }
    }

    // ── Scenario 4: self arm STILL projects image_id → own hidden image post keeps its imageUrl ───
    //    (Nearby / Global / single-post have a self arm; Following has none → not covered there.)

    "7.6 self arm — shadow-banned author reading OWN image post still gets imageUrl (Nearby/Global/single)" {
        val (author, at) = seedUser(displayName = "Hidden Author")
        setShadowBanned(author, true)
        val image = seedAttachedUpload(author)
        val postId = seedPost(author, imageId = image)
        withTimelineApp {
            nearby(at).byId(postId).imageUrl() shouldBe expectedUrl(image)
            global(at).byId(postId).imageUrl() shouldBe expectedUrl(image)
            val (status, body) = singlePost(postId, at)
            status shouldBe HttpStatusCode.OK
            body!!.imageUrl() shouldBe expectedUrl(image)
        }
    }

    "7.6 self arm — author reading OWN auto-hidden image post still gets imageUrl (Nearby/Global/single)" {
        val (author, at) = seedUser()
        val image = seedAttachedUpload(author)
        val postId = seedPost(author, imageId = image, autoHidden = true)
        withTimelineApp {
            nearby(at).byId(postId).imageUrl() shouldBe expectedUrl(image)
            global(at).byId(postId).imageUrl() shouldBe expectedUrl(image)
            val (status, body) = singlePost(postId, at)
            status shouldBe HttpStatusCode.OK
            body!!.imageUrl() shouldBe expectedUrl(image)
        }
    }
})
