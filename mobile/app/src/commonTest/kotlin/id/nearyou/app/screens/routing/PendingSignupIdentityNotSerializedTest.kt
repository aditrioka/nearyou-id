package id.nearyou.app.screens.routing

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 7.2 — the Decision-4 negative guard: a serialized back stack containing `AgeGateRoute` carries NO
 * `id_token`, even while the in-memory [PendingSignupIdentity] holds one (`mobile-age-gate` §
 * "Serialized back stack containing AgeGateRoute carries no id_token"). The identity lives ONLY in
 * the holder; `AgeGateRoute` is a parameterless marker, so no serialized navigation state can carry
 * the token. Serialization goes through the PRODUCTION [navSavedStateConfiguration] module.
 */
class PendingSignupIdentityNotSerializedTest {
    private val json = Json { serializersModule = navSavedStateConfiguration.serializersModule }

    @Test
    fun serializedBackStackContainingAgeGateRoute_carriesNoIdToken() {
        val pendingSignupIdentity = PendingSignupIdentity().apply { set("g-id-secret") }
        val backStack: List<NavKey> = listOf(RootRoute, SignInRoute, AgeGateRoute)

        val serialized = json.encodeToString(ListSerializer(PolymorphicSerializer(NavKey::class)), backStack)

        // Sanity: the holder really holds the sentinel (so the negative assertion below is meaningful)…
        assertTrue(pendingSignupIdentity.peek() == "g-id-secret", "precondition: holder set with the sentinel")
        // …yet the serialized navigation state contains NO occurrence of it.
        assertFalse(
            serialized.contains("g-id-secret"),
            "serialized back stack must not contain the id_token (it lives only in PendingSignupIdentity)",
        )
    }
}
