package id.nearyou.app.screens.shell

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 3.3 / `mobile-home-tab-host` § "Selected section survives a saved-state round-trip" — the selected
 * [Section] round-trips through its generated `@Serializable` serializer (the path `rememberSaveable`
 * uses for a `@Serializable` value on Kotlin/Native), so the active section survives saved-state on iOS
 * where reflection-based saving is unavailable. JSON is just the cross-platform vehicle to exercise the
 * serializable-enum path in commonTest (no `SerializationException`), exactly as `TabSerializationTest`
 * exercises the feed [id.nearyou.app.screens.home.Tab].
 */
class SectionSerializationTest {
    @Test
    fun everySection_roundTripsThroughItsSerializableEnumSaver() {
        for (section in Section.entries) {
            val encoded = Json.encodeToString(Section.serializer(), section)
            val decoded = Json.decodeFromString(Section.serializer(), encoded)
            assertEquals(section, decoded, "Section.$section must round-trip via the serializable-enum saver (iOS-safe)")
        }
    }
}
