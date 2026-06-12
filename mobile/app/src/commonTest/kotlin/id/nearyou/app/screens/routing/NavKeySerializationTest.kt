package id.nearyou.app.screens.routing

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 7.1 — every declared [NavKey] route round-trips through the PRODUCTION polymorphic module
 * ([navSavedStateConfiguration]'s `serializersModule`, NOT a re-declared copy), so a missing
 * `subclass(...)` registration in `AppNavSerialization.kt` fails this test (`mobile-app-scaffold` §
 * "Every route key round-trips through the polymorphic module"). The polymorphic `SerializersModule`
 * is what makes the back stack saveable on non-JVM targets (iOS); JSON here is just the cross-platform
 * vehicle to exercise that module's registration completeness in commonTest.
 */
class NavKeySerializationTest {
    private val json = Json { serializersModule = navSavedStateConfiguration.serializersModule }
    private val navKeySerializer = PolymorphicSerializer(NavKey::class)

    @Test
    fun everyRouteKey_roundTripsThroughThePolymorphicModule() {
        val routes: List<NavKey> =
            listOf(
                RootRoute,
                SignInRoute,
                HomeRoute,
                AgeGateRoute,
                PostCreationRoute,
                ConsentRoute,
                // The first payload-carrying route — a Nearby-origin instance (distanceM non-null) and a
                // Global-origin instance (distanceM null) both exercise the polymorphic serializer.
                samplePostDetailRoute(distanceM = 1234.5),
                samplePostDetailRoute(distanceM = null),
            )
        for (route in routes) {
            val encoded = json.encodeToString(navKeySerializer, route)
            val decoded = json.decodeFromString(navKeySerializer, encoded)
            assertEquals(route, decoded, "route $route must round-trip via the polymorphic NavKey module")
        }
    }

    /** 9.1 — the payload-carrying [PostDetailRoute] survives a serialized back-stack round-trip with all
     *  fields intact (the iOS-safe saved-state path); a missing `subclass(...)` registration would throw. */
    @Test
    fun postDetailRoute_roundTripsWithAllFieldsIntact() {
        val original = samplePostDetailRoute(distanceM = 1234.5)
        val decoded = json.decodeFromString(navKeySerializer, json.encodeToString(navKeySerializer, original))
        assertEquals(original, decoded)
        val typed = decoded as PostDetailRoute
        assertEquals("p1", typed.postId)
        assertEquals("halo", typed.content)
        assertEquals("Jakarta", typed.cityName)
        assertEquals(1234.5, typed.distanceM)
        assertEquals("2026-06-06T10:00:00Z", typed.createdAtIso)
        assertEquals(true, typed.likedByViewer)
        assertEquals(3, typed.replyCount)
        assertEquals("raka.jkt", typed.authorUsername)
        assertEquals("Raka Pratama", typed.authorDisplayName)
    }

    /** mobile-post-detail § "A payload predating the identity fields still decodes" — a back stack
     *  serialized BEFORE mobile-timeline-card-redesign lacks authorUsername/authorDisplayName; the
     *  defaults ("") keep process-death restore decoding instead of throwing. */
    @Test
    fun postDetailRoute_payloadWithoutIdentityFields_decodesWithEmptyDefaults() {
        val legacyEncoded = json.encodeToString(navKeySerializer, samplePostDetailRoute(distanceM = 1234.5))
        // Strip the identity fields from the serialized form to simulate a pre-change payload.
        val withoutIdentity =
            legacyEncoded
                .replace(Regex(""","authorUsername":"[^"]*""""), "")
                .replace(Regex(""","authorDisplayName":"[^"]*""""), "")
        val decoded = json.decodeFromString(navKeySerializer, withoutIdentity) as PostDetailRoute
        assertEquals("", decoded.authorUsername)
        assertEquals("", decoded.authorDisplayName)
        assertEquals("p1", decoded.postId)
    }

    private fun samplePostDetailRoute(distanceM: Double?): PostDetailRoute =
        PostDetailRoute(
            postId = "p1",
            content = "halo",
            cityName = "Jakarta",
            distanceM = distanceM,
            createdAtIso = "2026-06-06T10:00:00Z",
            likedByViewer = true,
            replyCount = 3,
            authorUsername = "raka.jkt",
            authorDisplayName = "Raka Pratama",
        )
}
