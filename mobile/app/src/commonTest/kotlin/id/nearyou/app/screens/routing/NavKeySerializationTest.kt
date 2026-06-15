package id.nearyou.app.screens.routing

import androidx.navigation3.runtime.NavKey
import id.nearyou.app.followlist.FollowListTab
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
                // The parameterless search route (mobile-search) — a missing subclass(...) registration
                // in AppNavSerialization.kt would fail this round-trip.
                SearchRoute,
                // The first payload-carrying route — a Nearby-origin instance (distanceM non-null) and a
                // Global-origin instance (distanceM null) both exercise the polymorphic serializer; the
                // reply-shortcut variant (focusReplyComposer = true, mobile-inline-post-actions) pins the
                // non-default boolean through the round-trip.
                samplePostDetailRoute(distanceM = 1234.5),
                samplePostDetailRoute(distanceM = null),
                samplePostDetailRoute(distanceM = null, focusReplyComposer = true),
                // mobile-settings — the three parameterless settings routes (no identity payload).
                SettingsRoute,
                BlockedUsersRoute,
                ConsentSettingsRoute,
                // The second payload-carrying route (mobile-profile) — a missing subclass(...) registration
                // for ProfileRoute would fail this round-trip.
                ProfileRoute("11111111-1111-1111-1111-555555555555"),
                // The follower/following list route (mobile-follow-lists) — both tab variants exercise the
                // @Serializable FollowListTab payload; a missing subclass(...) registration fails here.
                FollowListRoute("11111111-1111-1111-1111-666666666666", FollowListTab.Followers),
                FollowListRoute("11111111-1111-1111-1111-666666666666", FollowListTab.Following),
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

    /** mobile-post-detail § "A payload predating focusReplyComposer still decodes" — a back stack
     *  serialized BEFORE mobile-inline-post-actions lacks the field; the default (false) keeps
     *  process-death restore decoding instead of throwing, and a whole-card open stays autofocus-free. */
    @Test
    fun postDetailRoute_payloadWithoutFocusReplyComposer_decodesToFalse() {
        // The default-valued field is not encoded (encodeDefaults = false), so this serialized form IS
        // the pre-change payload shape; strip defensively anyway in case encoding defaults ever flips.
        val withoutFlag =
            json
                .encodeToString(navKeySerializer, samplePostDetailRoute(distanceM = 1234.5))
                .replace(Regex(""","focusReplyComposer":(true|false)"""), "")
        val decoded = json.decodeFromString(navKeySerializer, withoutFlag) as PostDetailRoute
        assertEquals(false, decoded.focusReplyComposer, "a pre-change payload decodes with the false default")
        assertEquals("p1", decoded.postId)
    }

    /** The reply-shortcut variant survives the round-trip with the flag intact. */
    @Test
    fun postDetailRoute_replyShortcutVariant_roundTripsWithTheFlag() {
        val original = samplePostDetailRoute(distanceM = null, focusReplyComposer = true)
        val decoded =
            json.decodeFromString(navKeySerializer, json.encodeToString(navKeySerializer, original))
                as PostDetailRoute
        assertEquals(true, decoded.focusReplyComposer)
        assertEquals(original, decoded)
    }

    private fun samplePostDetailRoute(
        distanceM: Double?,
        focusReplyComposer: Boolean = false,
    ): PostDetailRoute =
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
            focusReplyComposer = focusReplyComposer,
        )
}
