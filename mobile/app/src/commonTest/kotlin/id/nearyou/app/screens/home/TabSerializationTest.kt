package id.nearyou.app.screens.home

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 3.2 / `mobile-home-tab-host` § "Selected tab survives a saved-state round-trip" — the selected
 * [Tab] round-trips through its generated `@Serializable` serializer (the path `rememberSaveable`
 * uses for a `@Serializable` value on Kotlin/Native), so the active tab survives saved-state on iOS
 * where reflection-based saving is unavailable. JSON is just the cross-platform vehicle to exercise
 * the serializable-enum path in commonTest (no `SerializationException`), exactly as
 * `NavKeySerializationTest` exercises the `NavKey` polymorphic module.
 */
class TabSerializationTest {
    @Test
    fun everyTab_roundTripsThroughItsSerializableEnumSaver() {
        for (tab in Tab.entries) {
            val encoded = Json.encodeToString(Tab.serializer(), tab)
            val decoded = Json.decodeFromString(Tab.serializer(), encoded)
            assertEquals(tab, decoded, "Tab.$tab must round-trip via the serializable-enum saver (iOS-safe)")
        }
    }
}
