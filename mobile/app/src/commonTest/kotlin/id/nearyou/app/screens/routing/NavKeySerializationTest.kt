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
        val routes: List<NavKey> = listOf(RootRoute, SignInRoute, HomeRoute, AgeGateRoute, PostCreationRoute)
        for (route in routes) {
            val encoded = json.encodeToString(navKeySerializer, route)
            val decoded = json.decodeFromString(navKeySerializer, encoded)
            assertEquals(route, decoded, "route $route must round-trip via the polymorphic NavKey module")
        }
    }
}
